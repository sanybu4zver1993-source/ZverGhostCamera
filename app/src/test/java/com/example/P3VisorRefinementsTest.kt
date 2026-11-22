package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.visor.CyberVisorEngine
import com.example.visor.PosePointNorm
import com.example.visor.PoseTemporalSmoother
import com.example.visor.VisorMode
import com.example.visor.VisorYuvPacker
import com.google.mlkit.vision.pose.PoseLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P3VisorRefinementsTest {

    @Test
    fun testApplyVisorEffectToBitmapProducesFilteredPhotoWithoutMutatingSource() {
        val w = 32
        val h = 32
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                src.setPixel(x, y, Color.rgb((x * 7).coerceIn(20, 220), 110, (y * 6).coerceIn(20, 200)))
            }
        }
        val origCenter = src.getPixel(16, 16)

        val thermal = CyberVisorEngine().applyVisorEffectToBitmap(src, VisorMode.THERMAL_LUT)
        assertFalse(src.isRecycled)
        assertEquals(w, thermal.width)
        assertEquals(h, thermal.height)
        assertEquals(origCenter, src.getPixel(16, 16))
        assertNotEquals("Thermal LUT must transform pixel colors", origCenter, thermal.getPixel(16, 16))

        val vein = CyberVisorEngine().applyVisorEffectToBitmap(src, VisorMode.VEIN_HEMO)
        assertNotEquals("Vein mode must transform pixel colors", origCenter, vein.getPixel(16, 16))

        thermal.recycle()
        vein.recycle()
        src.recycle()
    }

    @Test
    fun testChlorophyllDistinguishesOrganicFoliageFromPlasticAndTeal() {
        val w = 24
        val h = 24
        val total = w * h

        // 1) Textured natural leaf green (organic venation variation, warm leaf R > B or absorbed B)
        val leafSrc = IntArray(total)
        val leafClass = ByteArray(total)
        val leafOut = IntArray(total)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val veinWave = ((x * 7 + y * 5) % 18)
                val r = 46 + (veinWave / 2)
                val g = 112 + veinWave
                val b = 34 + (veinWave / 3)
                val idx = y * w + x
                leafSrc[idx] = Color.rgb(r, g, b)
                leafClass[idx] = CyberVisorEngine.classifyChlorophyllPixel(r, g, b)
            }
        }
        val (leafLive, _) = CyberVisorEngine.renderChlorophyllCoherencePass(leafSrc, leafClass, leafOut, w, h)
        assertTrue("Organic textured leaf must detect significant live chlorophyll ($leafLive)", leafLive > total / 3)

        // 2) Uniform flat green plastic sheet (zero micro-variation)
        val plasticSrc = IntArray(total) { Color.rgb(55, 145, 50) }
        val plasticClass = ByteArray(total) { CyberVisorEngine.classifyChlorophyllPixel(55, 145, 50) }
        val plasticOut = IntArray(total)
        val (plasticLive, plasticSynth) = CyberVisorEngine.renderChlorophyllCoherencePass(plasticSrc, plasticClass, plasticOut, w, h)
        assertEquals("Uniform flat plastic must not register as live foliage", 0, plasticLive)
        assertTrue("Uniform green plastic must be flagged as synthetic", plasticSynth > total / 2)

        // 3) Teal/cyan synthetic fabric & neon dye
        assertEquals(2.toByte(), CyberVisorEngine.classifyChlorophyllPixel(40, 175, 165))
        assertEquals(2.toByte(), CyberVisorEngine.classifyChlorophyllPixel(5, 245, 5))
    }

    @Test
    fun testPoseTemporalSmootherReducesCloseHandJitterAndOutlierSpikes() {
        val smoother = PoseTemporalSmoother()

        fun frame(wristX: Float, wristY: Float, wristConf: Float): Map<Int, Pair<PosePointNorm, Float>> {
            val map = hashMapOf(
                PoseLandmark.LEFT_SHOULDER to (PosePointNorm(0.35f, 0.30f) to 0.95f),
                PoseLandmark.RIGHT_SHOULDER to (PosePointNorm(0.65f, 0.30f) to 0.95f),
                PoseLandmark.LEFT_ELBOW to (PosePointNorm(0.32f, 0.48f) to 0.90f),
                PoseLandmark.LEFT_WRIST to (PosePointNorm(wristX, wristY) to wristConf)
            )
            return map
        }

        // Initial lock
        smoother.update(frame(0.50f, 0.50f, 0.85f))

        // Oscillating close-hand micro-jitter around 0.50
        var maxDeviation = 0f
        for (step in 1..10) {
            val rawX = if (step % 2 == 0) 0.506f else 0.494f
            val wf = smoother.update(frame(rawX, 0.50f, 0.70f))
            // Wrist is in joints list; find the joint closest to x ~ 0.50
            val wristJoint = wf.joints.minByOrNull { abs(it.x - 0.50f) }!!
            maxDeviation = maxOf(maxDeviation, abs(wristJoint.x - 0.50f))
        }
        assertTrue("Stationary jitter must stay tightly damped (< 0.004), was $maxDeviation", maxDeviation < 0.004f)

        // Sudden 1-frame close-hand outlier spike (+0.32 jump with medium confidence)
        val spikedWf = smoother.update(frame(0.82f, 0.50f, 0.62f))
        val wristAfterSpike = spikedWf.joints.minByOrNull { abs(it.y - 0.50f) + abs(it.x - 0.54f) }!!
        assertTrue("Outlier spike must be clamped cleanly (< 0.57), was ${wristAfterSpike.x}", wristAfterSpike.x < 0.57f)

        // Brief 1-frame occlusion should keep 4 joints via hysteresis coasting
        val occludedMap = frame(0.50f, 0.50f, 0.15f)
        val coastedWf = smoother.update(occludedMap)
        assertEquals("Coasting should preserve all 4 tracked joints across brief dropout", 4, coastedWf.visibleCount)
    }

    @Test
    fun testSobelPcbTracePassHighlightsTracesAndSolderPads() {
        val w = 24
        val h = 24
        val src = IntArray(w * h)
        val dst = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = when {
                    x in 10..13 && y in 4..10 -> Color.rgb(225, 225, 220) // bright solder pad
                    y == 16 && x in 3..20 -> Color.rgb(45, 165, 95)      // bright copper trace under mask
                    else -> Color.rgb(12, 48, 22)                        // dark green PCB substrate
                }
                src[y * w + x] = c
            }
        }
        val tracePixels = CyberVisorEngine.renderSobelPcbTracePass(src, dst, w, h)
        assertTrue("Sobel pass should detect PCB traces and pad borders", tracePixels > 12)
        // Flat substrate interior should be dimmed cleanly
        val flatSubstrate = dst[2 * w + 2]
        assertTrue(((flatSubstrate ushr 8) and 0xFF) < 30)
    }

    @Test
    fun testWarmSkinRgbGuardAndYuvPackerAlignment() {
        assertTrue(CyberVisorEngine.isWarmHumanSkinRgbGuard(195, 145, 118))
        assertFalse("Neutral gray wall must be rejected", CyberVisorEngine.isWarmHumanSkinRgbGuard(130, 130, 132))
        assertFalse("Green wall must be rejected", CyberVisorEngine.isWarmHumanSkinRgbGuard(70, 160, 80))

        val (tw, th) = VisorYuvPacker.computeTargetDimensions(1080, 1920, maxLongSide = 640)
        assertEquals(0, tw % 16)
        assertEquals(0, th % 16)
        assertTrue(th <= 640 && tw <= 640)

        val argb = IntArray(32 * 32) { Color.rgb(200, 100, 50) }
        val nv12 = ByteArray((32 * 32 * 3) / 2)
        val i420 = ByteArray((32 * 32 * 3) / 2)
        VisorYuvPacker.argbToYuv420SemiPlanar(argb, 32, 32, nv12)
        VisorYuvPacker.argbToYuv420Planar(argb, 32, 32, i420)
        assertTrue((nv12[0].toInt() and 0xFF) in 100..160)
        assertEquals(nv12[0], i420[0])
    }
}
