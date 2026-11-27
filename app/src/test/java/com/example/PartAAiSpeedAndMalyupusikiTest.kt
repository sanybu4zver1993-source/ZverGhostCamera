package com.example

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.assist.GeminiByokClient
import com.example.privacy.LocalSymbiosisCvEngine
import com.example.privacy.OfflinePrivacyEngine
import com.example.ui.VikaAiSheet
import com.example.visor.YcbcrSkinAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class PartAAiSpeedAndMalyupusikiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun geminiByokClient_buildGenerationConfig_capsThinkingBudgetForFastModels() {
        val hydrogenCfg = GeminiByokClient.buildGenerationConfig("gemini-4-hydrogen")
        assertTrue(hydrogenCfg.has("thinkingConfig"))
        assertEquals(0, hydrogenCfg.getJSONObject("thinkingConfig").getInt("thinkingBudget"))

        val flash25Cfg = GeminiByokClient.buildGenerationConfig("gemini-2.5-flash")
        assertEquals(0, flash25Cfg.getJSONObject("thinkingConfig").getInt("thinkingBudget"))

        val pro25Cfg = GeminiByokClient.buildGenerationConfig("gemini-2.5-pro")
        assertEquals(128, pro25Cfg.getJSONObject("thinkingConfig").getInt("thinkingBudget"))

        val fallbackNoThinking = GeminiByokClient.buildGenerationConfig("gemini-4-hydrogen", allowThinkingConfig = false)
        assertFalse(fallbackNoThinking.has("thinkingConfig"))

        val legacy15 = GeminiByokClient.buildGenerationConfig("gemini-1.5-flash")
        assertFalse(legacy15.has("thinkingConfig"))

        val gemma = GeminiByokClient.buildGenerationConfig("gemma-3-27b-it")
        assertFalse(gemma.has("thinkingConfig"))
    }

    @Test
    fun localSymbiosis_translatesSceneTags_and_producesSceneSpecificRussianReports() {
        assertEquals("Еда", LocalSymbiosisCvEngine.translateSceneTagRu("Food"))
        assertEquals("Игрушка/Мелкий предмет", LocalSymbiosisCvEngine.translateSceneTagRu("Toy"))
        assertEquals("Овощи", LocalSymbiosisCvEngine.translateSceneTagRu("Vegetable"))
        assertEquals("Фрукты", LocalSymbiosisCvEngine.translateSceneTagRu("Fruit"))
        assertEquals("Штора", LocalSymbiosisCvEngine.translateSceneTagRu("Curtain"))
        assertEquals("Комната", LocalSymbiosisCvEngine.translateSceneTagRu("Room"))
        assertEquals("Ноутбук", LocalSymbiosisCvEngine.translateSceneTagRu("Laptop"))
        assertEquals("деталь сцены", LocalSymbiosisCvEngine.translateSceneTagRu("SomeObscureTag"))

        val foodReport = LocalSymbiosisCvEngine.SymbiosisReport(
            faces = emptyList(),
            objects = listOf(LocalSymbiosisCvEngine.DetectedObjectSummary(RectF(0.2f, 0.2f, 0.8f, 0.7f), "еда", 0.82f)),
            sceneTags = listOf(
                LocalSymbiosisCvEngine.SceneTag("Еда", 0.80f),
                LocalSymbiosisCvEngine.SceneTag("Фрукты", 0.55f)
            ),
            pose = LocalSymbiosisCvEngine.PoseSummary(false, 0, "нет фигуры", null),
            extractedText = "MINTEX WILD BERRIES",
            osintReport = OfflinePrivacyEngine.OsintRiskReport(emptyList(), 0),
            quality = LocalSymbiosisCvEngine.FrameQuality(135, false, 30, false),
            foregroundCoveragePct = null,
            inferenceMs = 45L,
            skinMetrics = YcbcrSkinAnalyzer.SkinMetrics(
                totalSamples = 1000,
                skinSamples = 150,
                skinFraction = 0.15f,
                compactness = 0.50f,
                clippedFraction = 0.0f,
                meanCb = 105,
                meanCr = 150,
                normBboxLeft = 0.2f,
                normBboxTop = 0.2f,
                normBboxRight = 0.7f,
                normBboxBottom = 0.8f
            )
        )

        val techReport = LocalSymbiosisCvEngine.SymbiosisReport(
            faces = emptyList(),
            objects = listOf(LocalSymbiosisCvEngine.DetectedObjectSummary(RectF(0.1f, 0.1f, 0.9f, 0.9f), "Устройство", 0.88f)),
            sceneTags = listOf(
                LocalSymbiosisCvEngine.SceneTag("Ноутбук", 0.86f),
                LocalSymbiosisCvEngine.SceneTag("Экран", 0.72f)
            ),
            pose = LocalSymbiosisCvEngine.PoseSummary(false, 0, "нет фигуры", null),
            extractedText = "",
            osintReport = OfflinePrivacyEngine.OsintRiskReport(emptyList(), 0),
            quality = LocalSymbiosisCvEngine.FrameQuality(115, false, 44, false),
            foregroundCoveragePct = null,
            inferenceMs = 38L
        )

        val foodText = LocalSymbiosisCvEngine.buildOfflineVikaReport(
            foodReport,
            "Полный локальный разбор малипусиками",
            variantSeed = 100L
        )
        val techText = LocalSymbiosisCvEngine.buildOfflineVikaReport(
            techReport,
            "Полный локальный разбор малипусиками",
            variantSeed = 100L
        )

        assertNotEquals(foodText, techText)
        assertFalse(foodText.contains("YCbCr"))
        assertFalse(foodText.contains("SCENE_GENERAL"))
        assertTrue(foodText.contains("Разбор кадра"))
        assertTrue(foodText.contains("оттенок кожи 15%"))
        assertTrue(foodText.contains("Похоже на еду"))
        assertTrue(techText.contains("В фокусе техника"))
    }

    @Test
    fun vikaAiSheet_narrow360dp_wrapsQuickChipsAndTransportFlowWithoutClipping() {
        val dummyBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            composeTestRule.setContent {
                VikaAiSheet(
                    externalBitmap = dummyBmp,
                    onStatusMessage = {},
                    onDismiss = {}
                )
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("vika_photo_modes_flow").assertIsDisplayed()
            composeTestRule.onNodeWithTag("vika_quick_tasks_flow").assertIsDisplayed()
            composeTestRule.onNodeWithText("⚡ Малипусики Офлайн").assertIsDisplayed()
            composeTestRule.onNodeWithText("🛰️ Сканер угроз").assertIsDisplayed()
            composeTestRule.onNodeWithText("🚗 Поиск утечек").assertIsDisplayed()

            composeTestRule.onNodeWithText("🔑 Ключи").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("byok_transport_flow").assertIsDisplayed()
            composeTestRule.onNodeWithTag("byok_transport_tor_orbot_socks5").assertIsDisplayed()
        } finally {
            dummyBmp.recycle()
        }
    }
}
