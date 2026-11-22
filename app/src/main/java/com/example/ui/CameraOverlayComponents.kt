package com.example.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import com.example.visor.GlVisorDualRenderer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.visor.VisorMode
import com.example.visor.VisorTelemetry
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

private val CyberBlack = Color(0xFF070B0E)
private val CyberGreen = Color(0xFF00FF66)
private val CyberGreenDark = Color(0xFF008F39)
private val CyberRed = Color(0xFFFF1744)
private val CyberMuted = Color(0xFF8FA3B0)
private val CyberSurface = Color(0xFF111820)
private val CyberBorder = Color(0xFF223240)
private val NeonCyan = Color(0xFF00E5FF)
private val NeonYellow = Color(0xFFFFEA00)

/**
 * Isolated mutable state holder for high-frequency camera telemetry (sensors, Laplacian sharpness,
 * zebra hotspots, visor bitmap frames, recording counters) so updates recompose only tiny leaf
 * composables instead of the entire GhostCameraScreen tree.
 */
@Stable
class CameraTelemetryState {
    var liveLaplacianVariance by mutableDoubleStateOf(0.0)
    var peakingPoints by mutableStateOf<List<Offset>>(emptyList())
    var overexposedPercent by mutableIntStateOf(0)
    var underexposedPercent by mutableIntStateOf(0)
    var liveHistogramBins32 by mutableStateOf(FloatArray(32))
    var liveMeanLuma by mutableIntStateOf(128)
    var liveZebraPoints by mutableStateOf<List<Offset>>(emptyList())

    var gyroAngularSpeed by mutableFloatStateOf(0f)
    var isGyroStable by mutableStateOf(true)
    var isTripodMode by mutableStateOf(false)
    var documentTiltDeg by mutableFloatStateOf(0f)
    var isDocumentParallel by mutableStateOf(false)
    var isProximityLocked by mutableStateOf(false)

    var visorDisplayState by mutableStateOf<Triple<Bitmap, VisorTelemetry, Long>?>(null)
    var gpuVisorTelemetry by mutableStateOf<VisorTelemetry?>(null)

    var recordingDurationSeconds by mutableIntStateOf(0)
    var recordingBytesWritten by mutableLongStateOf(0L)

    var sentinelMotionScore by mutableIntStateOf(0)
    var sentinelAvgLuma by mutableIntStateOf(128)
    var sentinelIsNight by mutableStateOf(false)
    var sentinelStepLabel by mutableStateOf("ОЖИДАНИЕ")

    var retroHotspots by mutableStateOf<List<com.example.camera.RetroReflectionDetector.RetroHotspot>>(emptyList())
    var retroSummaryLabel by mutableStateOf("")
    var isRetroScanning by mutableStateOf(false)

    var isTimeLapseRecording by mutableStateOf(false)
    var timeLapseFrames by mutableIntStateOf(0)
    var timeLapseElapsedSec by mutableIntStateOf(0)
}

@Composable
internal fun CameraViewportSurface(
    useGpuVisorPipeline: Boolean,
    glRenderer: GlVisorDualRenderer,
    onPreviewViewReady: (PreviewView) -> Unit,
    onTextureViewReady: (TextureView) -> Unit,
    onPreviewStreaming: (() -> Unit)? = null
) {
    if (useGpuVisorPipeline) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var attachedSurface: Surface? = null

                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            attachedSurface?.release()
                            val s = Surface(st)
                            attachedSurface = s
                            glRenderer.attachDisplaySurface(s, w, h)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            glRenderer.resizeDisplay(w, h)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            glRenderer.attachDisplaySurface(null, 0, 0)
                            attachedSurface?.release()
                            attachedSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                    onTextureViewReady(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    previewStreamState.observeForever { state ->
                        if (state == PreviewView.StreamState.STREAMING) {
                            onPreviewStreaming?.invoke()
                        }
                    }
                    onPreviewViewReady(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
internal fun VisorLiveOverlayLayer(
    visorMode: VisorMode,
    useGpuVisorPipeline: Boolean,
    telemetry: CameraTelemetryState
) {
    if (visorMode == VisorMode.NORMAL || useGpuVisorPipeline) return
    val activeVisorFrame = telemetry.visorDisplayState ?: return
    if (activeVisorFrame.first.isRecycled) return

    Image(
        bitmap = activeVisorFrame.first.asImageBitmap(),
        contentDescription = "Био-Визор (${visorMode.title})",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )

    if (visorMode == VisorMode.WIREFRAME_POSE) {
        val wf = activeVisorFrame.second.poseWireframe
        if (wf != null && (wf.bones.isNotEmpty() || wf.joints.isNotEmpty())) {
            val bmpW = activeVisorFrame.first.width.toFloat().coerceAtLeast(1f)
            val bmpH = activeVisorFrame.first.height.toFloat().coerceAtLeast(1f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scale = max(size.width / bmpW, size.height / bmpH)
                val drawW = bmpW * scale
                val drawH = bmpH * scale
                val offX = (size.width - drawW) * 0.5f
                val offY = (size.height - drawH) * 0.5f
                val boneStroke = 2.5.dp.toPx()
                val jointRadius = 4.5.dp.toPx()
                for ((a, b) in wf.bones) {
                    drawLine(
                        color = NeonCyan,
                        start = Offset(offX + a.x * drawW, offY + a.y * drawH),
                        end = Offset(offX + b.x * drawW, offY + b.y * drawH),
                        strokeWidth = boneStroke
                    )
                }
                for (j in wf.joints) {
                    drawCircle(
                        color = CyberGreen,
                        radius = jointRadius,
                        center = Offset(offX + j.x * drawW, offY + j.y * drawH)
                    )
                }
            }
        }
    }
}

@Composable
internal fun CameraGestureLayer(
    previewView: PreviewView?,
    textureView: TextureView?,
    useGpuVisorPipeline: Boolean,
    isToolsDrawerOpen: Boolean,
    selectedCameraMode: String,
    cameraModes: List<Pair<String, String>>,
    onCloseDrawer: () -> Unit,
    onTapFocus: (Offset) -> Unit,
    onTriggerFocusAndMetering: (FocusMeteringAction) -> Unit,
    onHapticStealth: () -> Unit,
    onSelectMode: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenVikaSheet: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(previewView, textureView, useGpuVisorPipeline, isToolsDrawerOpen) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        if (isToolsDrawerOpen) {
                            onCloseDrawer()
                            return@detectTapGestures
                        }
                        val point = if (!useGpuVisorPipeline && previewView != null) {
                            previewView.meteringPointFactory.createPoint(tapOffset.x, tapOffset.y)
                        } else if (textureView != null && textureView.width > 0 && textureView.height > 0) {
                            SurfaceOrientedMeteringPointFactory(
                                textureView.width.toFloat(),
                                textureView.height.toFloat()
                            ).createPoint(tapOffset.x, tapOffset.y)
                        } else null

                        point?.let { pt ->
                            val action = FocusMeteringAction.Builder(
                                pt,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                            )
                                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                                .build()
                            onTriggerFocusAndMetering(action)
                            onTapFocus(tapOffset)
                        }
                    }
                )
            }
            .pointerInput(selectedCameraMode, isToolsDrawerOpen) {
                var totalDx = 0f
                var totalDy = 0f
                var handled = false
                detectDragGestures(
                    onDragStart = {
                        totalDx = 0f
                        totalDy = 0f
                        handled = false
                    },
                    onDrag = { _, dragAmount ->
                        if (handled) return@detectDragGestures
                        totalDx += dragAmount.x
                        totalDy += dragAmount.y
                        val threshold = 105f
                        if (abs(totalDx) > threshold && abs(totalDx) > abs(totalDy) * 1.3f) {
                            handled = true
                            val curIdx = cameraModes.indexOfFirst { it.second == selectedCameraMode }
                                .let { if (it >= 0) it else 1 }
                            val nextIdx = if (totalDx < 0f) {
                                (curIdx + 1).coerceAtMost(cameraModes.lastIndex)
                            } else {
                                (curIdx - 1).coerceAtLeast(0)
                            }
                            if (nextIdx != curIdx) {
                                onHapticStealth()
                                onSelectMode(cameraModes[nextIdx].second)
                            }
                        } else if (totalDy > threshold && abs(totalDy) > abs(totalDx) * 1.3f) {
                            handled = true
                            onHapticStealth()
                            onOpenDrawer()
                        } else if (totalDy < -threshold && abs(totalDy) > abs(totalDx) * 1.3f) {
                            handled = true
                            onHapticStealth()
                            if (isToolsDrawerOpen) {
                                onCloseDrawer()
                            } else {
                                onOpenVikaSheet()
                            }
                        }
                    }
                )
            }
    )
}

internal fun shouldShowDocumentLevelOverlay(
    selectedCameraMode: String,
    isDocumentModeActive: Boolean,
    isRecordingVideo: Boolean
): Boolean = com.example.camera.CameraUiState(
    activeTab = com.example.camera.CameraTab.fromKey(selectedCameraMode),
    isDocumentModeActive = isDocumentModeActive,
    isRecordingVideo = isRecordingVideo
).showDocumentLevel

internal fun shouldShowTacticalReticle(
    selectedCameraMode: String,
    visorMode: VisorMode,
    isRecordingVideo: Boolean
): Boolean = com.example.camera.CameraUiState(
    activeTab = com.example.camera.CameraTab.fromKey(selectedCameraMode),
    visorMode = visorMode,
    isRecordingVideo = isRecordingVideo
).showTacticalReticle

@Composable
internal fun DocumentLevelOverlay(
    isDocumentModeActive: Boolean,
    telemetry: CameraTelemetryState
) {
    if (!isDocumentModeActive) return
    val isParallel = telemetry.isDocumentParallel
    val tiltDeg = telemetry.documentTiltDeg
    val crosshairColor = if (isParallel) CyberGreen else NeonYellow

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(160.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            drawCircle(color = crosshairColor, radius = 56.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = crosshairColor.copy(alpha = 0.35f), radius = 14.dp.toPx())
            drawLine(color = crosshairColor, start = Offset(cx - 64.dp.toPx(), cy), end = Offset(cx + 64.dp.toPx(), cy), strokeWidth = 2.dp.toPx())
            drawLine(color = crosshairColor, start = Offset(cx, cy - 64.dp.toPx()), end = Offset(cx, cy + 64.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
        Text(
            text = if (isParallel) {
                "ДОКУМЕНТ В ПЛОСКОСТИ: ${String.format("%.1f", tiltDeg)}° [ОК]"
            } else {
                "НАКЛОН: ${String.format("%.1f", tiltDeg)}°"
            },
            color = crosshairColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 180.dp)
        )
    }
}

@Composable
internal fun LiveOpticalOverlaysLayer(
    isFocusPeakingEnabled: Boolean,
    isFalseColorEnabled: Boolean,
    isZebraEnabled: Boolean,
    zebraThreshold: Int,
    telemetry: CameraTelemetryState
) {
    if (isFocusPeakingEnabled) {
        FocusPeakingOverlay(
            peakingPoints = telemetry.peakingPoints,
            sharpnessVariance = telemetry.liveLaplacianVariance,
            showBadge = false,
            modifier = Modifier.fillMaxSize()
        )
    }

    if (isFalseColorEnabled) {
        FalseColorExposureOverlay(
            overexposedPct = telemetry.overexposedPercent,
            underexposedPct = telemetry.underexposedPercent,
            showBadge = false,
            modifier = Modifier.fillMaxSize()
        )
    }

    if (isZebraEnabled) {
        ZebraExposureOverlay(
            zebraHotspots = telemetry.liveZebraPoints,
            overexposedPct = telemetry.overexposedPercent,
            threshold = zebraThreshold,
            showBadge = false,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
internal fun FocusTapRingOverlay(
    focusTapPoint: Offset?,
    onExpired: () -> Unit
) {
    val pt = focusTapPoint ?: return
    LaunchedEffect(pt) {
        delay(1800)
        onExpired()
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(color = CyberGreen, radius = 26.dp.toPx(), center = pt, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = CyberGreen.copy(alpha = 0.3f), radius = 6.dp.toPx(), center = pt)
    }
}

@Composable
internal fun ShutterFlashOverlay(visible: Boolean) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberGreen.copy(alpha = 0.25f))
            .border(4.dp, CyberGreen)
    )
}

@Composable
internal fun AssistPerimeterBorder(isAssistModeActive: Boolean) {
    if (!isAssistModeActive) return
    val infiniteTransition = rememberInfiniteTransition(label = "assistPulse")
    val redBorderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "redBorderAlpha"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(3.dp, CyberRed.copy(alpha = redBorderAlpha))
    )
}

@Composable
internal fun CountdownOverlay(countdownRemaining: Int?) {
    val count = countdownRemaining ?: return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = count.toString(),
            color = CyberGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 96.sp
        )
    }
}

@Composable
internal fun RetroReflectionOverlay(
    isScanning: Boolean,
    hotspots: List<com.example.camera.RetroReflectionDetector.RetroHotspot>,
    summaryLabel: String,
    modifier: Modifier = Modifier
) {
    if (!isScanning && hotspots.isEmpty() && summaryLabel.isBlank()) return
    val infiniteTransition = rememberInfiniteTransition(label = "retroPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "retroPulseScale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("retro_reflection_overlay")
    ) {
        if (hotspots.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (spot in hotspots) {
                    val cx = (spot.normX * size.width).coerceIn(16f, size.width - 16f)
                    val cy = (spot.normY * size.height).coerceIn(16f, size.height - 16f)
                    val baseR = max(18.dp.toPx(), spot.radiusPx * 3.2f)
                    val r = baseR * pulse
                    drawCircle(
                        color = CyberRed.copy(alpha = 0.25f),
                        radius = r * 1.15f,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = CyberRed,
                        radius = r,
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                    drawCircle(
                        color = NeonCyan,
                        radius = max(5.dp.toPx(), baseR * 0.35f),
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.6.dp.toPx())
                    )
                    val arm = r * 1.4f
                    drawLine(
                        color = NeonCyan,
                        start = Offset(cx - arm, cy),
                        end = Offset(cx + arm, cy),
                        strokeWidth = 1.4.dp.toPx()
                    )
                    drawLine(
                        color = NeonCyan,
                        start = Offset(cx, cy - arm),
                        end = Offset(cx, cy + arm),
                        strokeWidth = 1.4.dp.toPx()
                    )
                }
            }
        }
        val badgeText = when {
            isScanning -> "🔦 СКАН АНТИ-ОПТИКИ (ВСПЫШКА OFF/ON)..."
            summaryLabel.isNotBlank() -> summaryLabel
            else -> ""
        }
        if (badgeText.isNotBlank()) {
            Text(
                text = badgeText,
                color = if (hotspots.isNotEmpty()) CyberRed else NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 230.dp)
                    .background(CyberBlack.copy(alpha = 0.80f))
                    .border(
                        1.dp,
                        if (hotspots.isNotEmpty()) CyberRed else NeonCyan
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}
