package com.example.visor

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.Preview
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure GPU shader routing & aspect-ratio math for [GlVisorDualRenderer].
 * Stateless optical/CV modes run at 25-30 FPS inside a single OpenGL ES pass;
 * temporal multi-frame modes ([VisorMode.BIO_RPPG], [VisorMode.EULER_AMP]) stay on CPU.
 */
fun VisorMode.supportsGpuShader(): Boolean = when (this) {
    VisorMode.BIO_RPPG, VisorMode.EULER_AMP -> false
    else -> true
}

fun VisorMode.shaderModeId(): Int = when (this) {
    VisorMode.NORMAL -> 0
    VisorMode.THERMAL_LUT -> 1
    VisorMode.NIGHT_GAMMA -> 2
    VisorMode.VEIN_HEMO -> 3
    VisorMode.DERMA_UV_SIM -> 4
    VisorMode.OCR_CONTRAST -> 5
    VisorMode.DEHAZE_LIVE -> 6
    VisorMode.EDGE_TRACE -> 7
    VisorMode.VEG_CHLOROPHYLL -> 8
    VisorMode.IR_BEACON -> 9
    VisorMode.SKIN_CHROMA -> 10
    VisorMode.WIREFRAME_POSE -> 11
    VisorMode.BIO_RPPG, VisorMode.EULER_AMP -> -1
}

internal object GlVisorMath {

    /**
     * Computes a center-crop (FILL_CENTER) 4x4 MVP matrix so camera frames never stretch or squish
     * regardless of display TextureView aspect ratio, encoder 9:16/3:4 surface size, or 90/270
     * sensor rotation encoded in [stMatrix].
     */
    fun computeCenterCropMvp(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        stMatrix: FloatArray,
        outMvp: FloatArray,
        flipVertical: Boolean = false
    ) {
        if (outMvp.size < 16) return
        Matrix.setIdentityM(outMvp, 0)
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) return

        val rotated90or270 = stMatrix.size >= 6 &&
            abs(stMatrix[0]) < 0.15f &&
            abs(stMatrix[5]) < 0.15f
        val orientedW = if (rotated90or270) srcHeight.toFloat() else srcWidth.toFloat()
        val orientedH = if (rotated90or270) srcWidth.toFloat() else srcHeight.toFloat()

        val srcAspect = orientedW / max(1f, orientedH)
        val dstAspect = dstWidth.toFloat() / max(1f, dstHeight.toFloat())

        val scaleX: Float
        val scaleY: Float
        if (srcAspect > dstAspect) {
            scaleX = srcAspect / dstAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = dstAspect / srcAspect
        }
        Matrix.scaleM(outMvp, 0, scaleX, if (flipVertical) -scaleY else scaleY, 1f)
    }
}

/**
 * Aligns heterogeneous HAL SurfaceTexture timestamps (MONOTONIC, BOOTTIME, or 0) onto a strictly
 * monotonic relative timeline anchored at [sessionStartMonoNs] so video PTS matches AAC audio PTS.
 */
internal class GlVisorPtsNormalizer {
    private var sessionStartMonoNs: Long = 0L
    private var firstSensorNs: Long = -1L
    private var anchorOffsetNs: Long = 0L
    private var lastEmittedNs: Long = -1L

    fun reset(sessionStartNs: Long = System.nanoTime()) {
        sessionStartMonoNs = sessionStartNs
        firstSensorNs = -1L
        anchorOffsetNs = 0L
        lastEmittedNs = -1L
    }

    fun nextRelativePtsNs(rawSensorNs: Long, nowMonoNs: Long = System.nanoTime()): Long {
        if (sessionStartMonoNs == 0L) {
            sessionStartMonoNs = nowMonoNs
        }
        val wallRelNs = (nowMonoNs - sessionStartMonoNs).coerceAtLeast(0L)
        val candidateNs = if (rawSensorNs > 0L) {
            if (firstSensorNs < 0L) {
                firstSensorNs = rawSensorNs
                anchorOffsetNs = wallRelNs
                wallRelNs
            } else {
                val deltaFromFirst = rawSensorNs - firstSensorNs
                val anchored = anchorOffsetNs + deltaFromFirst
                // Reject huge clock-domain jumps (>2s mismatch against wall clock)
                if (deltaFromFirst >= 0L && abs(anchored - wallRelNs) < 2_000_000_000L) {
                    anchored
                } else {
                    wallRelNs
                }
            }
        } else {
            wallRelNs
        }

        val monotonicNs = if (lastEmittedNs >= 0L) {
            max(lastEmittedNs + 1_000_000L, candidateNs)
        } else {
            candidateNs
        }
        lastEmittedNs = monotonicNs
        return monotonicNs
    }
}

/**
 * Computes a compact 4x4 tile-histogram CLAHE lookup table (256 bins x 16 tiles = 4096 bytes)
 * on CPU from a low-res Y plane for Mali-G52-friendly GPU interpolation (zero compute shaders).
 */
internal object ClaheLutBuilder {
    const val GRID_X = 4
    const val GRID_Y = 4
    const val TILE_COUNT = GRID_X * GRID_Y
    const val BINS = 256
    const val LUT_BYTE_SIZE = BINS * TILE_COUNT

    fun build4x4TileLuts(
        yBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        clipLimit: Float = 2.4f,
        outLut: ByteArray = ByteArray(LUT_BYTE_SIZE)
    ): ByteArray {
        if (width < 16 || height < 16 || outLut.size < LUT_BYTE_SIZE) {
            // Identity ramp fallback
            for (t in 0 until TILE_COUNT) {
                val off = t * BINS
                for (i in 0 until BINS) outLut[off + i] = i.toByte()
            }
            return outLut
        }

        val dup = yBuffer.duplicate()
        val limit = dup.limit()
        val step = max(1, min(width, height) / 96)
        val hist = IntArray(BINS)
        val tilesY = height / GRID_Y
        val tilesX = width / GRID_X

        for (ty in 0 until GRID_Y) {
            val yStart = ty * tilesY
            val yEnd = if (ty == GRID_Y - 1) height else (ty + 1) * tilesY
            for (tx in 0 until GRID_X) {
                val xStart = tx * tilesX
                val xEnd = if (tx == GRID_X - 1) width else (tx + 1) * tilesX
                java.util.Arrays.fill(hist, 0)
                var count = 0
                var y = yStart
                while (y < yEnd) {
                    val rowBase = y * rowStride
                    var x = xStart
                    while (x < xEnd) {
                        val pos = rowBase + x
                        if (pos < limit) {
                            val luma = dup.get(pos).toInt() and 0xFF
                            hist[luma]++
                            count++
                        }
                        x += step
                    }
                    y += step
                }
                val tileOff = (ty * GRID_X + tx) * BINS
                if (count <= 0) {
                    for (i in 0 until BINS) outLut[tileOff + i] = i.toByte()
                    continue
                }

                // Clip excess counts & redistribute evenly
                val clipCount = max(1, ((clipLimit * count) / BINS.toFloat()).toInt())
                var excess = 0
                for (i in 0 until BINS) {
                    if (hist[i] > clipCount) {
                        excess += hist[i] - clipCount
                        hist[i] = clipCount
                    }
                }
                val avgInc = excess / BINS
                val rem = excess % BINS
                for (i in 0 until BINS) {
                    hist[i] += avgInc + if (i < rem) 1 else 0
                }

                // Normalized CDF -> 0..255
                var cdf = 0
                val total = count.toFloat()
                for (i in 0 until BINS) {
                    cdf += hist[i]
                    val mapped = ((cdf / total) * 255f).toInt().coerceIn(0, 255)
                    outLut[tileOff + i] = mapped.toByte()
                }
            }
        }
        return outLut
    }
}

internal object GlVisorShaders {

    const val VERTEX_300 = """#version 300 es
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec2 aTexCoord;
uniform mat4 uMvpMatrix;
uniform mat4 uTexMatrix;
out vec2 vTexCoord;
void main() {
    gl_Position = uMvpMatrix * aPosition;
    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
"""

    const val FRAGMENT_300 = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES sTexture;
uniform sampler2D uDehazeLut;
uniform int uHasDehazeLut;
uniform int uMode;
uniform vec2 uTexelStep;
in vec2 vTexCoord;
out vec4 fragColor;

float calcLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float calcGreenLuma(vec3 c) {
    return (c.r * 3.0 + c.g * 10.0 + c.b * 3.0) * 0.0625;
}

float sampleTileCdf(int tileIdx, float luma) {
    float rowCoord = (float(tileIdx) + 0.5) / 16.0;
    float colCoord = (clamp(luma, 0.0, 1.0) * 255.0 + 0.5) / 256.0;
    return texture(uDehazeLut, vec2(colCoord, rowCoord)).r;
}

bool isWarmSkin(vec3 c) {
    float y = calcLuma(c);
    float cb = 0.5 - 0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b;
    float cr = 0.5 + 0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b;
    bool ycbcr = (y > 0.078) && (cb >= 0.301 && cb <= 0.498) && (cr >= 0.521 && cr <= 0.678);
    bool rgbGuard = ((c.r - c.b) >= 0.031) && (c.r >= (c.g - 0.016)) && (c.r > 0.125 || c.g > 0.078);
    return ycbcr && rgbGuard;
}

int classifyVeg(vec3 c) {
    float luma = calcLuma(c);
    float minC = min(c.r, min(c.g, c.b));
    if (luma < 0.07 || minC > 0.80) return 0;
    if (c.g <= c.r + 0.015 || c.g <= c.b + 0.024) return 0;
    float denom = max(0.188, c.r + c.g + c.b);
    float nExG = (2.0 * c.g - c.r - c.b) / denom;
    float blueDrop = (c.g - c.b) / max(0.094, c.g);
    float exGr = (3.0 * c.g - 2.4 * c.r - c.b) / denom;
    bool neonDye = (c.g > 0.529 && c.r < c.g * 0.22 && c.b < c.g * 0.18);
    if (!neonDye && nExG >= 0.16 && blueDrop >= 0.18 && exGr >= 0.06) return 1;
    if (nExG >= 0.08 || ((c.g - c.r) >= 0.024 && (c.g - c.b) >= 0.024)) return 2;
    return 0;
}

void main() {
    vec3 rgb = texture(sTexture, vTexCoord).rgb;
    vec3 outRgb = rgb;

    if (uMode == 1) {
        // THERMAL_LUT (Ironbow)
        float t = clamp(calcLuma(rgb), 0.0, 1.0);
        float r = clamp(1.5 * t + 0.05 * sin(t * 3.14159265), 0.0, 1.0);
        float g = clamp(pow(t, 2.2), 0.0, 1.0);
        float b = (t < 0.5) ? sin(t * 4.3982297) * 0.85 : (t - 0.5) * 1.6;
        outRgb = vec3(r, g, clamp(b, 0.0, 1.0));
    } else if (uMode == 2) {
        // NIGHT_GAMMA
        outRgb = pow(clamp(rgb, 0.0, 1.0), vec3(0.42));
    } else if (uMode == 3) {
        // VEIN_HEMO
        float rf = max(rgb.r, 0.016);
        float gf = max(rgb.g, 0.016);
        float hemo = (log(rf) - log(gf)) * 0.8235;
        float baseLuma = calcLuma(rgb);
        float vein = clamp(baseLuma - hemo * 2.2, 0.0, 1.0);
        outRgb = vec3(vein * 0.25, vein, clamp(vein * 0.90 + 0.078, 0.0, 1.0));
    } else if (uMode == 4) {
        // DERMA_UV_SIM
        float uvc = clamp(rgb.b * 1.35 - rgb.r * 0.35, 0.0, 1.0);
        outRgb = vec3(uvc * 0.45, uvc * 0.65, clamp(uvc * 1.25 + 0.059, 0.0, 1.0));
    } else if (uMode == 5) {
        // OCR_CONTRAST
        float lum = calcLuma(rgb);
        float s = clamp((lum - 0.5) * 2.4 + 0.5, 0.0, 1.0);
        outRgb = vec3(s * 0.95, s, clamp(s + 0.047, 0.0, 1.0));
    } else if (uMode == 6) {
        // DEHAZE_LIVE: bilinear 4x4 CLAHE LUT interpolation when available, dark-channel fallback otherwise
        if (uHasDehazeLut == 1) {
            float lum = calcLuma(rgb);
            vec2 g = clamp(vTexCoord, 0.0, 0.999) * 4.0 - 0.5;
            vec2 gClamp = clamp(g, 0.0, 3.0);
            int x0 = int(floor(gClamp.x));
            int y0 = int(floor(gClamp.y));
            int x1 = min(3, x0 + 1);
            int y1 = min(3, y0 + 1);
            float fx = fract(gClamp.x);
            float fy = fract(gClamp.y);
            float c00 = sampleTileCdf(y0 * 4 + x0, lum);
            float c10 = sampleTileCdf(y0 * 4 + x1, lum);
            float c01 = sampleTileCdf(y1 * 4 + x0, lum);
            float c11 = sampleTileCdf(y1 * 4 + x1, lum);
            float eqLum = mix(mix(c00, c10, fx), mix(c01, c11, fx), fy);
            float gain = clamp(eqLum / max(0.035, lum), 0.65, 2.35);
            vec3 claheRgb = clamp(rgb * gain, 0.0, 1.0);
            outRgb = mix(rgb, claheRgb, 0.85);
        } else {
            vec2 d = uTexelStep * 4.0;
            float darkC = min(rgb.r, min(rgb.g, rgb.b));
            vec3 s1 = texture(sTexture, vTexCoord + vec2(d.x, 0.0)).rgb;
            vec3 s2 = texture(sTexture, vTexCoord - vec2(d.x, 0.0)).rgb;
            vec3 s3 = texture(sTexture, vTexCoord + vec2(0.0, d.y)).rgb;
            vec3 s4 = texture(sTexture, vTexCoord - vec2(0.0, d.y)).rgb;
            float veil = min(
                min(darkC, min(min(s1.r, min(s1.g, s1.b)), min(s2.r, min(s2.g, s2.b)))),
                min(min(s3.r, min(s3.g, s3.b)), min(s4.r, min(s4.g, s4.b)))
            ) * 0.72;
            float denom = max(0.32, 1.0 - veil);
            vec3 dehazed = clamp((rgb - vec3(veil)) / denom, 0.0, 1.0);
            outRgb = pow(dehazed, vec3(0.86));
        }
    } else if (uMode == 7) {
        // EDGE_TRACE (3x3 Sobel for copper traces & metallic solder pads)
        vec2 dx = vec2(uTexelStep.x, 0.0);
        vec2 dy = vec2(0.0, uTexelStep.y);
        float tl = calcGreenLuma(texture(sTexture, vTexCoord - dx - dy).rgb);
        float tc = calcGreenLuma(texture(sTexture, vTexCoord - dy).rgb);
        float tr = calcGreenLuma(texture(sTexture, vTexCoord + dx - dy).rgb);
        float ml = calcGreenLuma(texture(sTexture, vTexCoord - dx).rgb);
        float mc = calcGreenLuma(rgb);
        float mr = calcGreenLuma(texture(sTexture, vTexCoord + dx).rgb);
        float bl = calcGreenLuma(texture(sTexture, vTexCoord - dx + dy).rgb);
        float bc = calcGreenLuma(texture(sTexture, vTexCoord + dy).rgb);
        float br = calcGreenLuma(texture(sTexture, vTexCoord + dx + dy).rgb);
        float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
        float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
        float grad = clamp((abs(gx) + abs(gy)) * 0.5, 0.0, 1.0);
        float chroma = max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
        if (grad > 0.156) {
            if (grad > 0.298 && mc > 0.49 && chroma < 0.204) {
                outRgb = vec3(clamp(0.76 + grad * 0.25, 0.0, 1.0), clamp(0.61 + grad * 0.33, 0.0, 1.0), 0.204);
            } else {
                outRgb = vec3(0.0, clamp(grad + 0.215, 0.0, 1.0), clamp(grad + 0.333, 0.0, 1.0));
            }
        } else {
            float base = mc * 0.25;
            outRgb = vec3(base, clamp(base + 0.016, 0.0, 1.0), clamp(base + 0.047, 0.0, 1.0));
        }
    } else if (uMode == 8) {
        // VEG_CHLOROPHYLL (spectral + 4-neighbor organic texture)
        float luma = calcLuma(rgb);
        int cls = classifyVeg(rgb);
        if (cls == 1) {
            vec2 dx = vec2(uTexelStep.x, 0.0);
            vec2 dy = vec2(0.0, uTexelStep.y);
            vec3 cL = texture(sTexture, vTexCoord - dx * 2.0).rgb;
            vec3 cR = texture(sTexture, vTexCoord + dx * 2.0).rgb;
            vec3 cU = texture(sTexture, vTexCoord - dy * 2.0).rgb;
            vec3 cD = texture(sTexture, vTexCoord + dy * 2.0).rgb;
            int nLive = 0;
            if (classifyVeg(texture(sTexture, vTexCoord - dx).rgb) == 1) nLive++;
            if (classifyVeg(texture(sTexture, vTexCoord + dx).rgb) == 1) nLive++;
            if (classifyVeg(texture(sTexture, vTexCoord - dy).rgb) == 1) nLive++;
            if (classifyVeg(texture(sTexture, vTexCoord + dy).rgb) == 1) nLive++;
            float orgVar = abs(cL.g - rgb.g) + abs(cR.g - rgb.g) + abs(cU.g - rgb.g) + abs(cD.g - rgb.g);
            bool live = (nLive >= 2 && orgVar >= 0.023) || (nLive >= 3 && (rgb.g - rgb.b) >= 0.094 && orgVar >= 0.012);
            if (live) {
                float exg = 2.0 * rgb.g - rgb.r - rgb.b;
                float boost = clamp(luma * 0.33 + exg * 2.0 + 0.094, 0.0, 1.0);
                outRgb = vec3(0.086, boost, 0.275);
            } else {
                float dim = luma * 0.5;
                outRgb = vec3(clamp(dim + 0.255, 0.0, 1.0), clamp(dim * 0.5 + 0.078, 0.0, 1.0), clamp(dim + 0.294, 0.0, 1.0));
            }
        } else if (cls == 2) {
            float dim = luma * 0.5;
            outRgb = vec3(clamp(dim + 0.282, 0.0, 1.0), clamp(dim * 0.5 + 0.070, 0.0, 1.0), clamp(dim + 0.322, 0.0, 1.0));
        } else {
            float dim = luma * 0.42;
            outRgb = vec3(dim, dim, clamp(dim + 0.070, 0.0, 1.0));
        }
    } else if (uMode == 9) {
        // IR_BEACON
        float irScore = (rgb.r + rgb.b) - 2.0 * rgb.g;
        if (irScore > 0.215 && ((rgb.r > 0.667 && rgb.b > 0.568) || (rgb.r > 0.964 && rgb.g > 0.964 && rgb.b > 0.964))) {
            outRgb = vec3(1.0, 0.0, 0.333);
        } else {
            float dim = (rgb.r + rgb.g + rgb.b) / 9.0;
            outRgb = vec3(dim);
        }
    } else if (uMode == 10) {
        // SKIN_CHROMA with 4-neighbor spatial coherence
        float luma = calcLuma(rgb);
        bool cur = isWarmSkin(rgb);
        vec2 dx = vec2(uTexelStep.x, 0.0);
        vec2 dy = vec2(0.0, uTexelStep.y);
        int nSkin = 0;
        if (isWarmSkin(texture(sTexture, vTexCoord - dx).rgb)) nSkin++;
        if (isWarmSkin(texture(sTexture, vTexCoord + dx).rgb)) nSkin++;
        if (isWarmSkin(texture(sTexture, vTexCoord - dy).rgb)) nSkin++;
        if (isWarmSkin(texture(sTexture, vTexCoord + dy).rgb)) nSkin++;
        bool clean = cur ? (nSkin >= 2) : (nSkin >= 3);
        if (clean) {
            outRgb = vec3(clamp(0.51 + luma * 0.49, 0.0, 1.0), clamp(0.255 + luma * 0.45, 0.0, 1.0), clamp(0.07 + rgb.b * 0.25, 0.0, 1.0));
        } else {
            float dim = luma * 0.33;
            outRgb = vec3(dim * 0.50, dim * 0.72, clamp(dim + 0.07, 0.0, 1.0));
        }
    } else if (uMode == 11) {
        // WIREFRAME_POSE cool slate tint (bones/joints drawn in pose pass)
        outRgb = vec3(rgb.r * 0.45, rgb.g * 0.65, rgb.b * 0.85);
    }

    fragColor = vec4(outRgb, 1.0);
}
"""

    const val VERTEX_100 = """#version 100
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uMvpMatrix;
uniform mat4 uTexMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = uMvpMatrix * aPosition;
    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
"""

    const val FRAGMENT_100 = """#version 100
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES sTexture;
uniform int uMode;
uniform vec2 uTexelStep;
varying vec2 vTexCoord;

float calcLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec3 rgb = texture2D(sTexture, vTexCoord).rgb;
    vec3 outRgb = rgb;
    float luma = calcLuma(rgb);
    if (uMode == 1) {
        outRgb = vec3(clamp(1.5 * luma, 0.0, 1.0), pow(luma, 2.2), sin(luma * 3.14159) * 0.7);
    } else if (uMode == 2) {
        outRgb = pow(clamp(rgb, 0.0, 1.0), vec3(0.42));
    } else if (uMode == 3) {
        float hemo = (log(max(rgb.r, 0.02)) - log(max(rgb.g, 0.02))) * 0.82;
        float v = clamp(luma - hemo * 2.2, 0.0, 1.0);
        outRgb = vec3(v * 0.25, v, clamp(v * 0.9 + 0.08, 0.0, 1.0));
    } else if (uMode == 4) {
        float uvc = clamp(rgb.b * 1.35 - rgb.r * 0.35, 0.0, 1.0);
        outRgb = vec3(uvc * 0.45, uvc * 0.65, clamp(uvc * 1.25 + 0.06, 0.0, 1.0));
    } else if (uMode == 5) {
        float s = clamp((luma - 0.5) * 2.4 + 0.5, 0.0, 1.0);
        outRgb = vec3(s * 0.95, s, clamp(s + 0.05, 0.0, 1.0));
    } else if (uMode == 6) {
        float veil = min(rgb.r, min(rgb.g, rgb.b)) * 0.65;
        outRgb = pow(clamp((rgb - vec3(veil)) / max(0.35, 1.0 - veil), 0.0, 1.0), vec3(0.86));
    } else if (uMode == 7) {
        vec2 dx = vec2(uTexelStep.x, 0.0);
        vec2 dy = vec2(0.0, uTexelStep.y);
        float gx = calcLuma(texture2D(sTexture, vTexCoord + dx).rgb) - calcLuma(texture2D(sTexture, vTexCoord - dx).rgb);
        float gy = calcLuma(texture2D(sTexture, vTexCoord + dy).rgb) - calcLuma(texture2D(sTexture, vTexCoord - dy).rgb);
        float g = clamp(abs(gx) + abs(gy), 0.0, 1.0);
        outRgb = (g > 0.16) ? vec3(0.0, clamp(g + 0.25, 0.0, 1.0), clamp(g + 0.35, 0.0, 1.0)) : vec3(luma * 0.25);
    } else if (uMode == 8) {
        float exg = (2.0 * rgb.g - rgb.r - rgb.b);
        outRgb = (exg > 0.09) ? vec3(0.08, clamp(luma * 0.4 + exg * 2.0, 0.0, 1.0), 0.27) : vec3(luma * 0.4);
    } else if (uMode == 9) {
        float ir = (rgb.r + rgb.b) - 2.0 * rgb.g;
        outRgb = (ir > 0.21 && rgb.r > 0.66 && rgb.b > 0.56) ? vec3(1.0, 0.0, 0.33) : vec3((rgb.r + rgb.g + rgb.b) / 9.0);
    } else if (uMode == 10) {
        bool skin = (rgb.r - rgb.b) >= 0.05 && rgb.r > rgb.g && luma > 0.08;
        outRgb = skin ? vec3(clamp(0.5 + luma * 0.5, 0.0, 1.0), clamp(0.25 + luma * 0.45, 0.0, 1.0), 0.1) : vec3(luma * 0.18, luma * 0.25, luma * 0.35);
    } else if (uMode == 11) {
        outRgb = vec3(rgb.r * 0.45, rgb.g * 0.65, rgb.b * 0.85);
    }
    gl_FragColor = vec4(outRgb, 1.0);
}
"""

    const val POSE_VERTEX = """#version 100
attribute vec2 aPos;
uniform float uPointSize;
void main() {
    gl_Position = vec4(aPos.x * 2.0 - 1.0, 1.0 - aPos.y * 2.0, 0.0, 1.0);
    gl_PointSize = uPointSize;
}
"""

    const val POSE_FRAGMENT = """#version 100
precision mediump float;
uniform vec4 uColor;
void main() {
    gl_FragColor = uColor;
}
"""
}

/**
 * Single-GPU dual-output renderer (`OpenGL ES 3.0` with automatic `ES 2.0` fallback):
 * CameraX Preview -> `SurfaceTexture` (`GL_TEXTURE_EXTERNAL_OES`) -> GLSL fragment shader ->
 * 1) Live display [Surface] (`TextureView`)
 * 2) Optional hardware `MediaCodec.createInputSurface()` (`EGLExt.eglPresentationTimeANDROID`)
 *
 * Eliminates CPU YUV->ARGB conversion and Bitmap copies for 25-30 FPS live visor preview + MP4 recording.
 */
class GlVisorDualRenderer(
    private val onGpuUnavailable: (String) -> Unit
) {
    private val glThread = HandlerThread("ZverGlVisor").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val released = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)

    @Volatile
    var shaderModeId: Int = 0

    @Volatile
    var poseWireframe: PoseWireframe? = null

    private var onEncoderFramePushed: (() -> Unit)? = null

    // EGL & GL handles (strictly touched on glThread)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var displaySurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var displayWidth = 0
    private var displayHeight = 0
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWidth = 0
    private var encoderHeight = 0

    private var cameraTexId = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cameraInputSurface: Surface? = null
    private var srcBufferWidth = 640
    private var srcBufferHeight = 480

    private var isEs3 = false
    private var programId = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uMvpMatrixLoc = -1
    private var uTexMatrixLoc = -1
    private var uTexLoc = -1
    private var uDehazeLutLoc = -1
    private var uHasDehazeLutLoc = -1
    private var uModeLoc = -1
    private var uTexelStepLoc = -1

    private var dehazeLutTexId = 0
    private var hasDehazeLut = false
    private val dehazeLutBuf: ByteBuffer = ByteBuffer.allocateDirect(ClaheLutBuilder.LUT_BYTE_SIZE)
    private val ptsNormalizer = GlVisorPtsNormalizer()

    private var poseProgramId = 0
    private var posePosLoc = -1
    private var poseColorLoc = -1
    private var posePointSizeLoc = -1
    private var poseBoneFloatBuf: FloatBuffer? = null
    private var poseBoneCapFloats = 0
    private var poseJointFloatBuf: FloatBuffer? = null
    private var poseJointCapFloats = 0

    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val mvpMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(
            floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
        ).apply { position(0) }

    init {
        glHandler.post { initEglAndPrograms() }
    }

    val previewSurfaceProvider: Preview.SurfaceProvider = Preview.SurfaceProvider { request ->
        if (released.get()) {
            request.willNotProvideSurface()
            return@SurfaceProvider
        }
        val res = request.resolution
        glHandler.post {
            if (released.get() || !initialized.get()) {
                request.willNotProvideSurface()
                return@post
            }
            srcBufferWidth = res.width
            srcBufferHeight = res.height
            val st = cameraSurfaceTexture
            if (st == null) {
                request.willNotProvideSurface()
                return@post
            }
            st.setDefaultBufferSize(res.width, res.height)
            try { cameraInputSurface?.release() } catch (_: Exception) {}
            val surf = Surface(st)
            cameraInputSurface = surf
            request.provideSurface(surf, { cmd -> glHandler.post(cmd) }) { _ ->
                // CameraX finished using this Surface
            }
        }
    }

    fun attachDisplaySurface(surface: Surface?, width: Int, height: Int) {
        if (released.get()) return
        glHandler.post {
            if (released.get() || eglDisplay == EGL14.EGL_NO_DISPLAY) return@post
            destroyDisplaySurfaceOnly()
            if (surface != null && surface.isValid && width > 0 && height > 0) {
                val cfg = eglConfig ?: return@post
                try {
                    displaySurface = EGL14.eglCreateWindowSurface(
                        eglDisplay,
                        cfg,
                        surface,
                        intArrayOf(EGL14.EGL_NONE),
                        0
                    )
                    displayWidth = width
                    displayHeight = height
                } catch (_: Throwable) {
                    displaySurface = EGL14.EGL_NO_SURFACE
                    displayWidth = 0
                    displayHeight = 0
                }
            }
        }
    }

    fun resizeDisplay(width: Int, height: Int) {
        if (released.get()) return
        glHandler.post {
            displayWidth = width
            displayHeight = height
        }
    }

    fun updateDehazeClaheLut(lutBytes: ByteArray) {
        if (released.get() || !initialized.get() || lutBytes.size < ClaheLutBuilder.LUT_BYTE_SIZE) return
        val copy = lutBytes.copyOf(ClaheLutBuilder.LUT_BYTE_SIZE)
        glHandler.post {
            if (released.get() || !initialized.get() || dehazeLutTexId == 0) return@post
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            }
            dehazeLutBuf.clear()
            dehazeLutBuf.put(copy)
            dehazeLutBuf.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dehazeLutTexId)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                ClaheLutBuilder.BINS,
                ClaheLutBuilder.TILE_COUNT,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                dehazeLutBuf
            )
            hasDehazeLut = true
        }
    }

    fun attachEncoderSurface(
        surface: Surface?,
        width: Int,
        height: Int,
        sessionStartNanoTime: Long = System.nanoTime(),
        onFramePushed: (() -> Unit)? = null
    ) {
        if (released.get()) return
        glHandler.post {
            if (released.get() || eglDisplay == EGL14.EGL_NO_DISPLAY) return@post
            destroyEncoderSurfaceOnly()
            ptsNormalizer.reset(sessionStartNanoTime)
            onEncoderFramePushed = onFramePushed
            if (surface != null && surface.isValid && width > 0 && height > 0) {
                val cfg = eglConfig ?: return@post
                try {
                    encoderSurface = EGL14.eglCreateWindowSurface(
                        eglDisplay,
                        cfg,
                        surface,
                        intArrayOf(EGL14.EGL_NONE),
                        0
                    )
                    encoderWidth = width
                    encoderHeight = height
                } catch (_: Throwable) {
                    encoderSurface = EGL14.EGL_NO_SURFACE
                    encoderWidth = 0
                    encoderHeight = 0
                }
            }
        }
    }

    fun detachEncoderSurfaceBlocking() {
        if (released.get()) return
        val latch = CountDownLatch(1)
        val posted = glHandler.post {
            try {
                destroyEncoderSurfaceOnly()
                onEncoderFramePushed = null
            } finally {
                latch.countDown()
            }
        }
        if (posted) {
            try { latch.await(400, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        }
    }

    /**
     * Renders the latest external OES camera texture with active shader + pose wireframe
     * into an offscreen FBO and reads pixels into a freshly allocated upright ARGB_8888 Bitmap.
     */
    fun captureSnapshotBitmap(targetLongSide: Int = 960): Bitmap? {
        if (released.get() || !initialized.get()) return null
        val resultRef = AtomicReference<Bitmap?>(null)
        val latch = CountDownLatch(1)
        val posted = glHandler.post {
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY && pbufferSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                    val baseW = if (displayWidth > 0) displayWidth else srcBufferHeight
                    val baseH = if (displayHeight > 0) displayHeight else srcBufferWidth
                    val scale = if (max(baseW, baseH) > targetLongSide) {
                        targetLongSide.toFloat() / max(baseW, baseH).toFloat()
                    } else 1f
                    val outW = max(16, (baseW * scale).toInt() and 0x7FFFFFFE)
                    val outH = max(16, (baseH * scale).toInt() and 0x7FFFFFFE)

                    val fboIds = IntArray(1)
                    val texIds = IntArray(1)
                    GLES20.glGenFramebuffers(1, fboIds, 0)
                    GLES20.glGenTextures(1, texIds, 0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outW, outH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texIds[0], 0)

                    // Flip vertically inside FBO so glReadPixels (bottom-up) comes out upright top-down
                    drawQuadAndPose(outW, outH, flipVertical = true)

                    val rgbaBuf = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
                    rgbaBuf.position(0)
                    GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaBuf)
                    rgbaBuf.rewind()
                    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(rgbaBuf)
                    resultRef.set(bmp)

                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glDeleteFramebuffers(1, fboIds, 0)
                    GLES20.glDeleteTextures(1, texIds, 0)
                }
            } catch (_: Throwable) {
                resultRef.set(null)
            } finally {
                latch.countDown()
            }
        }
        if (posted) {
            try { latch.await(800, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        }
        return resultRef.get()
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val latch = CountDownLatch(1)
        val posted = glHandler.post {
            try {
                try { cameraSurfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Exception) {}
                try { cameraSurfaceTexture?.release() } catch (_: Exception) {}
                cameraSurfaceTexture = null
                try { cameraInputSurface?.release() } catch (_: Exception) {}
                cameraInputSurface = null

                destroyDisplaySurfaceOnly()
                destroyEncoderSurfaceOnly()

                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (programId != 0) GLES20.glDeleteProgram(programId)
                    if (poseProgramId != 0) GLES20.glDeleteProgram(poseProgramId)
                    if (cameraTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(cameraTexId), 0)
                    if (dehazeLutTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(dehazeLutTexId), 0)
                    if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                pbufferSurface = EGL14.EGL_NO_SURFACE
            } finally {
                latch.countDown()
            }
        }
        if (posted) {
            try { latch.await(600, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        }
        glThread.quitSafely()
    }

    private fun initEglAndPrograms() {
        try {
            val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (dpy == EGL14.EGL_NO_DISPLAY) {
                onGpuUnavailable("EGL display unavailable")
                return
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(dpy, version, 0, version, 1)) {
                onGpuUnavailable("EGL init failed")
                return
            }
            eglDisplay = dpy

            val es3 = setupContext(dpy, clientVersion = 3)
            if (!es3) {
                val es2 = setupContext(dpy, clientVersion = 2)
                if (!es2) {
                    onGpuUnavailable("EGL ES2/ES3 context failed")
                    return
                }
                isEs3 = false
            } else {
                isEs3 = true
            }

            // Compile shader (prefer 300 es, fall back to 100 if ES3 shader fails to compile)
            var prog = if (isEs3) buildProgram(GlVisorShaders.VERTEX_300, GlVisorShaders.FRAGMENT_300) else 0
            if (prog == 0) {
                prog = buildProgram(GlVisorShaders.VERTEX_100, GlVisorShaders.FRAGMENT_100)
                isEs3 = false
            }
            if (prog == 0) {
                onGpuUnavailable("GLSL compile failed")
                return
            }
            programId = prog
            aPositionLoc = GLES20.glGetAttribLocation(prog, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
            uMvpMatrixLoc = GLES20.glGetUniformLocation(prog, "uMvpMatrix")
            uTexMatrixLoc = GLES20.glGetUniformLocation(prog, "uTexMatrix")
            uTexLoc = GLES20.glGetUniformLocation(prog, "sTexture")
            uDehazeLutLoc = GLES20.glGetUniformLocation(prog, "uDehazeLut")
            uHasDehazeLutLoc = GLES20.glGetUniformLocation(prog, "uHasDehazeLut")
            uModeLoc = GLES20.glGetUniformLocation(prog, "uMode")
            uTexelStepLoc = GLES20.glGetUniformLocation(prog, "uTexelStep")

            val pProg = buildProgram(GlVisorShaders.POSE_VERTEX, GlVisorShaders.POSE_FRAGMENT)
            poseProgramId = pProg
            if (pProg != 0) {
                posePosLoc = GLES20.glGetAttribLocation(pProg, "aPos")
                poseColorLoc = GLES20.glGetUniformLocation(pProg, "uColor")
                posePointSizeLoc = GLES20.glGetUniformLocation(pProg, "uPointSize")
            }

            val texIds = IntArray(2)
            GLES20.glGenTextures(2, texIds, 0)
            cameraTexId = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            dehazeLutTexId = texIds[1]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dehazeLutTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_LUMINANCE,
                ClaheLutBuilder.BINS,
                ClaheLutBuilder.TILE_COUNT,
                0,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )

            val st = SurfaceTexture(cameraTexId)
            st.setOnFrameAvailableListener({ renderCurrentFrame() }, glHandler)
            cameraSurfaceTexture = st
            initialized.set(true)
        } catch (t: Throwable) {
            onGpuUnavailable(t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    private fun setupContext(dpy: EGLDisplay, clientVersion: Int): Boolean {
        val renderableFlag = if (clientVersion == 3) 0x0040 else EGL14.EGL_OPENGL_ES2_BIT
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderableFlag,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
            return false
        }
        val cfg = configs[0] ?: return false
        val ctx = EGL14.eglCreateContext(
            dpy,
            cfg,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE),
            0
        )
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT || EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
            return false
        }
        val pbuf = EGL14.eglCreatePbufferSurface(
            dpy,
            cfg,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        if (pbuf == null || pbuf == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(dpy, ctx)
            return false
        }
        if (!EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)) {
            EGL14.eglDestroySurface(dpy, pbuf)
            EGL14.eglDestroyContext(dpy, ctx)
            return false
        }
        eglConfig = cfg
        eglContext = ctx
        pbufferSurface = pbuf
        return true
    }

    private fun renderCurrentFrame() {
        if (released.get() || !initialized.get()) return
        val st = cameraSurfaceTexture ?: return
        try {
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            }
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        } catch (_: Throwable) {
            return
        }

        val ptsNs = st.timestamp

        if (displaySurface != EGL14.EGL_NO_SURFACE && displayWidth > 0 && displayHeight > 0) {
            if (EGL14.eglMakeCurrent(eglDisplay, displaySurface, displaySurface, eglContext)) {
                drawQuadAndPose(displayWidth, displayHeight, flipVertical = false)
                EGL14.eglSwapBuffers(eglDisplay, displaySurface)
            }
        }

        if (encoderSurface != EGL14.EGL_NO_SURFACE && encoderWidth > 0 && encoderHeight > 0) {
            if (EGL14.eglMakeCurrent(eglDisplay, encoderSurface, encoderSurface, eglContext)) {
                drawQuadAndPose(encoderWidth, encoderHeight, flipVertical = false)
                val normalizedPtsNs = ptsNormalizer.nextRelativePtsNs(ptsNs, System.nanoTime())
                EGLExt.eglPresentationTimeANDROID(
                    eglDisplay,
                    encoderSurface,
                    normalizedPtsNs
                )
                EGL14.eglSwapBuffers(eglDisplay, encoderSurface)
                onEncoderFramePushed?.invoke()
            }
        }
    }

    private fun drawQuadAndPose(targetW: Int, targetH: Int, flipVertical: Boolean) {
        GLES20.glViewport(0, 0, targetW, targetH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GlVisorMath.computeCenterCropMvp(
            srcWidth = srcBufferWidth,
            srcHeight = srcBufferHeight,
            dstWidth = targetW,
            dstHeight = targetH,
            stMatrix = stMatrix,
            outMvp = mvpMatrix,
            flipVertical = flipVertical
        )

        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glUniform1i(uTexLoc, 0)
        if (uDehazeLutLoc >= 0 && dehazeLutTexId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dehazeLutTexId)
            GLES20.glUniform1i(uDehazeLutLoc, 1)
        }
        if (uHasDehazeLutLoc >= 0) {
            GLES20.glUniform1i(uHasDehazeLutLoc, if (hasDehazeLut) 1 else 0)
        }
        val activeMode = shaderModeId.coerceAtLeast(0)
        GLES20.glUniform1i(uModeLoc, activeMode)
        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, stMatrix, 0)
        GLES20.glUniform2f(
            uTexelStepLoc,
            1.5f / max(16, srcBufferWidth).toFloat(),
            1.5f / max(16, srcBufferHeight).toFloat()
        )

        quadVertices.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)

        quadVertices.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        if (activeMode == 11) {
            val wf = poseWireframe
            if (wf != null && poseProgramId != 0 && (wf.bones.isNotEmpty() || wf.joints.isNotEmpty())) {
                drawPoseWireframePass(wf, targetW, flipVertical)
            }
        }
    }

    private fun ensurePoseBoneBuffer(neededFloats: Int): FloatBuffer {
        val existing = poseBoneFloatBuf
        if (existing != null && poseBoneCapFloats >= neededFloats) return existing
        val cap = max(neededFloats, 128)
        return ByteBuffer.allocateDirect(cap * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
            poseBoneFloatBuf = it
            poseBoneCapFloats = cap
        }
    }

    private fun ensurePoseJointBuffer(neededFloats: Int): FloatBuffer {
        val existing = poseJointFloatBuf
        if (existing != null && poseJointCapFloats >= neededFloats) return existing
        val cap = max(neededFloats, 96)
        return ByteBuffer.allocateDirect(cap * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
            poseJointFloatBuf = it
            poseJointCapFloats = cap
        }
    }

    private fun drawPoseWireframePass(wf: PoseWireframe, targetW: Int, flipVertical: Boolean) {
        GLES20.glUseProgram(poseProgramId)
        if (wf.bones.isNotEmpty()) {
            val needed = wf.bones.size * 4
            val buf = ensurePoseBoneBuffer(needed)
            buf.clear()
            for ((a, b) in wf.bones) {
                buf.put(a.x)
                buf.put(if (flipVertical) 1f - a.y else a.y)
                buf.put(b.x)
                buf.put(if (flipVertical) 1f - b.y else b.y)
            }
            buf.position(0)
            GLES20.glLineWidth(max(3f, targetW * 0.006f))
            GLES20.glUniform4f(poseColorLoc, 0f, 0.90f, 1f, 1f)
            GLES20.glUniform1f(posePointSizeLoc, 6f)
            GLES20.glEnableVertexAttribArray(posePosLoc)
            GLES20.glVertexAttribPointer(posePosLoc, 2, GLES20.GL_FLOAT, false, 0, buf)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, wf.bones.size * 2)
            GLES20.glDisableVertexAttribArray(posePosLoc)
        }
        if (wf.joints.isNotEmpty()) {
            val needed = wf.joints.size * 2
            val buf = ensurePoseJointBuffer(needed)
            buf.clear()
            for (j in wf.joints) {
                buf.put(j.x)
                buf.put(if (flipVertical) 1f - j.y else j.y)
            }
            buf.position(0)
            GLES20.glUniform4f(poseColorLoc, 0f, 1f, 0.4f, 1f)
            GLES20.glUniform1f(posePointSizeLoc, max(8f, targetW * 0.015f))
            GLES20.glEnableVertexAttribArray(posePosLoc)
            GLES20.glVertexAttribPointer(posePosLoc, 2, GLES20.GL_FLOAT, false, 0, buf)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, wf.joints.size)
            GLES20.glDisableVertexAttribArray(posePosLoc)
        }
    }

    private fun destroyDisplaySurfaceOnly() {
        if (displaySurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            }
            EGL14.eglDestroySurface(eglDisplay, displaySurface)
        }
        displaySurface = EGL14.EGL_NO_SURFACE
        displayWidth = 0
        displayHeight = 0
    }

    private fun destroyEncoderSurfaceOnly() {
        if (encoderSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            }
            EGL14.eglDestroySurface(eglDisplay, encoderSurface)
        }
        encoderSurface = EGL14.EGL_NO_SURFACE
        encoderWidth = 0
        encoderHeight = 0
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        if (vShader == 0) return 0
        val fShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fShader == 0) {
            GLES20.glDeleteShader(vShader)
            return 0
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vShader)
        GLES20.glAttachShader(prog, fShader)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        if (linked[0] == 0) {
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
