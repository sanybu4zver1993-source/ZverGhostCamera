package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.camera.AudioWatcherEngine
import com.example.visor.PcbInspectorEngine
import com.example.visor.VisorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P3PcbAndAudioTest {

    @Test
    fun testSmdAndColorBandDecoders() {
        val jumper = PcbInspectorEngine.decodeSmdCode("000")
        assertTrue(jumper.valid)
        assertTrue(jumper.primaryValueRu.contains("0 Ом"))

        val eia3 = PcbInspectorEngine.decodeSmdCode("103")
        assertTrue(eia3.valid)
        assertEquals("R: 10 кОм", eia3.primaryValueRu)
        assertTrue(eia3.secondaryInterpretationRu?.contains("10 нФ") == true)

        val eia3Frac = PcbInspectorEngine.decodeSmdCode("472")
        assertTrue(eia3Frac.valid)
        assertEquals("R: 4.7 кОм", eia3Frac.primaryValueRu)

        val rNotation = PcbInspectorEngine.decodeSmdCode("4R7")
        assertTrue(rNotation.valid)
        assertEquals("4.7 Ом", rNotation.primaryValueRu)

        val rSubOhm = PcbInspectorEngine.decodeSmdCode("R22")
        assertTrue(rSubOhm.valid)
        assertEquals("0.22 Ом", rSubOhm.primaryValueRu)

        val eia4 = PcbInspectorEngine.decodeSmdCode("1002")
        assertTrue(eia4.valid)
        assertEquals("10 кОм", eia4.primaryValueRu)
        assertEquals("±1%", eia4.tolerance)

        val eia96C = PcbInspectorEngine.decodeSmdCode("01C")
        assertTrue(eia96C.valid)
        assertEquals("10 кОм", eia96C.primaryValueRu)
        assertEquals("±1%", eia96C.tolerance)

        val eia96X = PcbInspectorEngine.decodeSmdCode("68X")
        assertTrue(eia96X.valid)
        assertEquals("49.9 Ом", eia96X.primaryValueRu)
        assertEquals("±1%", eia96X.tolerance)

        val invalid = PcbInspectorEngine.decodeSmdCode("ZZZ")
        assertFalse(invalid.valid)

        val color1k = PcbInspectorEngine.decodeColorBands(
            PcbInspectorEngine.ColorBand.BROWN,
            PcbInspectorEngine.ColorBand.BLACK,
            PcbInspectorEngine.ColorBand.RED,
            PcbInspectorEngine.ColorBand.GOLD
        )
        assertTrue(color1k.valid)
        assertEquals("1 кОм", color1k.primaryValueRu)
        assertEquals("±5%", color1k.tolerance)

        val color47k = PcbInspectorEngine.decodeColorBands(
            PcbInspectorEngine.ColorBand.YELLOW,
            PcbInspectorEngine.ColorBand.VIOLET,
            PcbInspectorEngine.ColorBand.ORANGE
        )
        assertTrue(color47k.valid)
        assertEquals("47 кОм", color47k.primaryValueRu)
    }

    @Test
    fun testPcbBoardInspectionDetectsMaskPadsBridgeAndBurn() {
        val w = 160
        val h = 160
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp.setPixel(x, y, Color.rgb(10, 140, 25)) // green solder mask
            }
        }
        // Bright silver solder bridge blob covering multiple 10x10 grid cells
        for (y in 20 until 50) {
            for (x in 20 until 50) {
                bmp.setPixel(x, y, Color.rgb(225, 225, 225))
            }
        }
        // Dark carbonized burn spot covering multiple 10x10 grid cells
        for (y in 90 until 120) {
            for (x in 90 until 120) {
                bmp.setPixel(x, y, Color.rgb(18, 12, 10))
            }
        }

        val report = PcbInspectorEngine.inspectBoard(bmp)
        assertTrue("Expected green solder mask > 20%, got ${report.solderMaskPct}", report.solderMaskPct > 20)
        assertTrue("Expected pad coverage > 0%, got ${report.padCoveragePct}", report.padCoveragePct > 0)
        assertTrue("Expected bridge candidate >= 1, got ${report.bridgeCandidates}", report.bridgeCandidates >= 1)
        assertTrue("Expected burn spot >= 1, got ${report.burnSpots}", report.burnSpots >= 1)
        assertTrue(report.hotspots.isNotEmpty())
        assertTrue(report.boardHealthScore < 100)
        assertFalse(report.annotatedBitmap.isRecycled)
    }

    @Test
    fun testAudioWatcherPcmAnalyzerQuietLoudAndImpulse() {
        val quiet = ShortArray(1600) { (it % 5).toShort() }
        val quietTel = AudioWatcherEngine.analyzePcmFrame(quiet, quiet.size, noiseFloorDbfs = -58.0, thresholdDbfs = -26.0)
        assertTrue("Quiet RMS expected < -70 dBFS, got ${quietTel.rmsDbfs}", quietTel.rmsDbfs < -70.0)
        assertFalse(quietTel.isAboveThreshold)
        assertFalse(quietTel.isTransientSpike)
        assertEquals(16, quietTel.waveformBars.size)

        val loudSine = ShortArray(1600) { i ->
            (16000.0 * sin(2.0 * PI * 1000.0 * i / 16000.0)).toInt().toShort()
        }
        val loudTel = AudioWatcherEngine.analyzePcmFrame(loudSine, loudSine.size, noiseFloorDbfs = -58.0, thresholdDbfs = -26.0)
        assertTrue("Loud sine RMS expected > -10 dBFS, got ${loudTel.rmsDbfs}", loudTel.rmsDbfs > -10.0)
        assertTrue(loudTel.isAboveThreshold)
        assertEquals(16, loudTel.waveformBars.size)
        assertTrue(loudTel.waveformBars.all { it > 0.5f })

        val impulse = ShortArray(1600) { 40 }
        impulse[100] = 30000
        impulse[101] = -28000
        val impTel = AudioWatcherEngine.analyzePcmFrame(impulse, impulse.size, noiseFloorDbfs = -58.0, thresholdDbfs = -26.0)
        assertTrue("Expected high crest factor, got ${impTel.crestFactorDb}", impTel.crestFactorDb >= 10.5)
        assertTrue(impTel.isTransientSpike)
    }

    @Test
    fun testP3CvVisorModesRegistered() {
        assertNotNull(VisorMode.EDGE_TRACE.badgeRu)
        assertNotNull(VisorMode.OCR_CONTRAST.badgeRu)
        assertTrue(VisorMode.EDGE_TRACE.title.isNotBlank())
        assertTrue(VisorMode.OCR_CONTRAST.hintRu.isNotBlank())
    }
}
