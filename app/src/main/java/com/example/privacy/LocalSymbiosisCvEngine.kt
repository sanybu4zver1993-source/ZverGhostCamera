package com.example.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.visor.YcbcrSkinAnalyzer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * LocalSymbiosisCvEngine (100% Offline, 0 GMS, Bundled Multi-NN Pipeline):
 * Runs up to 6 complementary bundled on-device neural networks in parallel:
 * 1) Face Detection (+ Euler angles, smile/eyes)
 * 2) Object Detection & Classification (prominent object bounding boxes + categories)
 * 3) Image Labeling (400+ general scene/object/animal/foliage tags)
 * 4) Pose Detection (33 skeletal landmarks for posture even from behind)
 * 5) Selfie Segmentation (foreground person silhouette isolation / privacy background blur)
 * 6) Text Recognition + Offline OSINT Leak Linter
 *
 * Designed for low thermal load on MediaTek Helio G85: runs ONLY on captured/selected frames,
 * keyframe bursts, or Sentinel triggers — never on idle preview frames.
 */
object LocalSymbiosisCvEngine {

    data class FaceSummary(
        val rect: RectF, // normalized [0..1]
        val yawDeg: Float,
        val rollDeg: Float
    )

    data class DetectedObjectSummary(
        val rect: RectF, // normalized [0..1]
        val label: String,
        val confidence: Float
    )

    data class SceneTag(
        val label: String,
        val confidence: Float
    )

    data class PoseSummary(
        val detected: Boolean,
        val visibleLandmarks: Int,
        val postureRu: String,
        val torsoRect: RectF?
    )

    data class FrameQuality(
        val avgLuma: Int,
        val isLowLight: Boolean,
        val sharpnessScore: Int,
        val isBlurry: Boolean,
        val clippedHighlightsPct: Int = 0,
        val crushedShadowsPct: Int = 0,
        val contrastSpan: Int = 0,
        val dominantTintRu: String = "нейтральный"
    )

    data class IsolationResult(
        val bitmap: Bitmap,
        val foregroundCoveragePct: Float,
        val hasForegroundSubject: Boolean
    )

    data class SymbiosisReport(
        val faces: List<FaceSummary>,
        val objects: List<DetectedObjectSummary>,
        val sceneTags: List<SceneTag>,
        val pose: PoseSummary,
        val extractedText: String,
        val osintReport: OfflinePrivacyEngine.OsintRiskReport,
        val quality: FrameQuality,
        val foregroundCoveragePct: Float?,
        val inferenceMs: Long,
        val skinMetrics: YcbcrSkinAnalyzer.SkinMetrics? = null,
        val osintBlockHits: List<OfflinePrivacyEngine.OsintTextHit> = emptyList()
    ) {
        fun bestRoiRegion(): RectF? {
            faces.maxByOrNull { it.rect.width() * it.rect.height() }?.let { return it.rect }
            objects.maxByOrNull { it.rect.width() * it.rect.height() }?.let { return it.rect }
            pose.torsoRect?.let { return it }
            skinMetrics?.takeIf { it.isCoherentSkinPresent }?.let { sm ->
                val r = RectF(sm.normBboxLeft, sm.normBboxTop, sm.normBboxRight, sm.normBboxBottom)
                if (r.width() > 0.04f && r.height() > 0.04f) return r
            }
            return null
        }

        fun bestRoiKindLabel(): String = when {
            faces.isNotEmpty() -> "лицо"
            objects.isNotEmpty() -> objects.first().label
            pose.torsoRect != null -> "силуэт"
            skinMetrics?.isCoherentSkinPresent == true -> "кожа(YCbCr)"
            else -> "нет цели"
        }
    }

    /**
     * Runs all bundled offline models concurrently on a single Bitmap.
     */
    suspend fun analyzeSymbiosis(
        bitmap: Bitmap,
        includeForegroundCoverage: Boolean = false
    ): SymbiosisReport = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()
        val quality = assessFrameQuality(bitmap)
        val skin = YcbcrSkinAnalyzer.analyzeBitmap(bitmap)

        coroutineScope {
            val facesDef = async { detectFacesRich(bitmap) }
            val objectsDef = async { detectObjectsOffline(bitmap) }
            val labelsDef = async { detectSceneLabelsOffline(bitmap) }
            val poseDef = async { detectPoseOffline(bitmap) }
            val textDef = async { OfflinePrivacyEngine.detectTextWithContentOffline(bitmap) }
            val segDef = if (includeForegroundCoverage) {
                async { measureForegroundCoverage(bitmap) }
            } else null

            val faces = facesDef.await()
            val objects = objectsDef.await()
            val labels = labelsDef.await()
            val pose = poseDef.await()
            val (textEntries, rawText) = textDef.await()
            val textRegions = textEntries.map { it.first }
            val blockHits = OfflinePrivacyEngine.analyzeSensitiveOsintBlocksFromEntries(textEntries)
            val segPct = segDef?.await()

            val osintFaces = faces.mapIndexed { idx, f ->
                OfflinePrivacyEngine.NormalizedRegion("f_$idx", f.rect, OfflinePrivacyEngine.RegionKind.FACE)
            }
            val osint = OfflinePrivacyEngine.assessOsintRisks(
                OfflinePrivacyEngine.DetectionResult(
                    faces = osintFaces,
                    textBlocks = textRegions,
                    extractedText = rawText
                )
            )

            SymbiosisReport(
                faces = faces,
                objects = objects,
                sceneTags = labels,
                pose = pose,
                extractedText = rawText,
                osintReport = osint,
                quality = quality,
                foregroundCoveragePct = segPct,
                inferenceMs = System.currentTimeMillis() - startMs,
                skinMetrics = skin,
                osintBlockHits = blockHits
            )
        }
    }

    /**
     * Builds a compact factual dossier for Gemini BYOK when Symbiosis mode is active.
     */
    fun buildCompactGeminiDossier(report: SymbiosisReport): String = buildString {
        append("[ЛОКАЛЬНЫЙ СИМБИОЗ 6-NN (${report.inferenceMs} мс)]: ")
        append("Свет=${if (report.quality.isLowLight) "ТЕМНО(${report.quality.avgLuma})" else "ОК(${report.quality.avgLuma})"}, ")
        append("Резкость=${if (report.quality.isBlurry) "СМАЗ" else "ОК"}(${report.quality.sharpnessScore}). ")
        if (report.faces.isNotEmpty()) {
            val f0 = report.faces.first()
            append("Лиц=${report.faces.size} (поворот yaw=${f0.yawDeg.toInt()}°). ")
        }
        if (report.pose.detected) {
            append("Поза: ${report.pose.postureRu}. ")
        }
        report.skinMetrics?.takeIf { it.isCoherentSkinPresent }?.let { sm ->
            val pct = (sm.skinFraction * 100f).toInt()
            append("Кожа(YCbCr)=$pct% (сплочённость=${(sm.compactness * 100f).toInt()}%")
            if (report.faces.isEmpty()) append(", лицо не в анфас/скрыто")
            append("). ")
        }
        if (report.objects.isNotEmpty()) {
            append("Объекты: ${report.objects.take(3).joinToString { "${it.label}(${(it.confidence * 100).toInt()}%)" }}. ")
        }
        if (report.sceneTags.isNotEmpty()) {
            append("Теги: ${report.sceneTags.take(6).joinToString { "${it.label}(${(it.confidence * 100).toInt()}%)" }}. ")
        }
        if (report.extractedText.isNotBlank()) {
            val cleanTxt = report.extractedText.replace('\n', ' ').trim().take(180)
            append("OCR: \"$cleanTxt\". ")
        }
        if (report.osintReport.warnings.isNotEmpty()) {
            append("OSINT-риски: ${report.osintReport.warnings.joinToString("; ")}.")
        }
    }

    enum class OfflineTaskIntent {
        OCR_TEXT,
        OSINT_AUDIT,
        SENTINEL_DOZOR,
        OPTICS_FORENSIC,
        STATS_AUDIT,
        BYOK_CHANNEL,
        VAULT_BACKUP,
        AUTOMATION_TERMUX,
        CAM_PRO_HAL,
        CAMOUFLAGE_PANIC,
        OPSEC_SCAN,
        SCENE_GENERAL
    }

    fun isSystemKnowledgeIntent(intent: OfflineTaskIntent): Boolean = when (intent) {
        OfflineTaskIntent.STATS_AUDIT,
        OfflineTaskIntent.BYOK_CHANNEL,
        OfflineTaskIntent.VAULT_BACKUP,
        OfflineTaskIntent.AUTOMATION_TERMUX,
        OfflineTaskIntent.CAM_PRO_HAL,
        OfflineTaskIntent.CAMOUFLAGE_PANIC,
        OfflineTaskIntent.OPSEC_SCAN -> true
        else -> false
    }

    fun classifyOfflineIntent(userTask: String, systemPrompt: String? = null): OfflineTaskIntent {
        val t = userTask.lowercase()
        val s = systemPrompt.orEmpty().lowercase()
        return when {
            t.contains("ocr") || t.contains("текст") || t.contains("прочит") || t.contains("бумаг") ||
                t.contains("документ") || t.contains("печат") || t.contains("таблиц") || t.contains("сумм") ||
                s.contains("строгий ocr") -> OfflineTaskIntent.OCR_TEXT

            t.contains("osint") || t.contains("палев") || t.contains("утеч") || t.contains("риск") ||
                t.contains("номер") || t.contains("вывеск") || t.contains("отражен") || t.contains("карт") ||
                s.contains("режим osint") -> OfflineTaskIntent.OSINT_AUDIT

            t.contains("опсек") || t.contains("opsec") || t.contains("сканер угроз") ||
                t.contains("дисплей") || t.contains("accessibility") -> OfflineTaskIntent.OPSEC_SCAN

            t.contains("дозор") || t.contains("угроз") || t.contains("опасн") || t.contains("форма") ||
                t.contains("шеврон") || t.contains("кто в") || t.contains("порог") || t.contains("sentinel") ||
                s.contains("кибер-дозор") -> OfflineTaskIntent.SENTINEL_DOZOR

            t.contains("форенз") || t.contains("резкост") || t.contains("смаз") || t.contains("мыл") ||
                t.contains("пересвет") || t.contains("экспозиц") || t.contains("шум") ||
                s.contains("форензик") -> OfflineTaskIntent.OPTICS_FORENSIC

            t.contains("статист") || t.contains("статы") || t.contains("стата") ||
                t.contains("телеметр") || t.contains("готовност") || t.contains("аудит") ||
                s.contains("аналитик телеметрии") -> OfflineTaskIntent.STATS_AUDIT

            t.contains("ключ") || t.contains("tor") || t.contains("orbot") || t.contains("прокси") ||
                t.contains("socks") || t.contains("tls") || t.contains("api") || t.contains("byok") ||
                t.contains("контекст") -> OfflineTaskIntent.BYOK_CHANNEL

            t.contains("бэкап") || t.contains("zvb") || t.contains("перенос") || t.contains("сейф") ||
                t.contains("gcf") || t.contains("монолит") || t.contains("ram") || t.contains("озу") -> OfflineTaskIntent.VAULT_BACKUP

            t.contains("termux") || t.contains("термукс") || t.contains("shizuku") || t.contains("шизук") ||
                t.contains("zver://") || t.contains("macrodroid") || t.contains("intent") || t.contains("интент") ||
                t.contains("скрипт") || t.contains("adb") -> OfflineTaskIntent.AUTOMATION_TERMUX

            t.contains("паник") || t.contains("duress") || t.contains("калькулятор") || t.contains("маскир") ||
                t.contains("громк") || t.contains("стелс") || t.contains("шред") -> OfflineTaskIntent.CAMOUFLAGE_PANIC

            t.contains("камер") || t.contains("hal") || t.contains("camera2") || t.contains("iso") ||
                t.contains("выдерж") || t.contains("фокус") || t.contains("пульс") || t.contains("визор") ||
                t.contains("плата") || t.contains("pcb") || t.contains("маск") -> OfflineTaskIntent.CAM_PRO_HAL

            else -> OfflineTaskIntent.SCENE_GENERAL
        }
    }

    /**
     * Builds an honest, task-specific report from all 6 local bundled ML Kit + CV analyzers («Малипусики»)
     * when internet is unavailable, API key is unset, quota is exhausted, or offline mode is chosen.
     * Combines strict factual telemetry with a procedural, dynamic cheeky/humorous Vika remark.
     */
    fun buildOfflineVikaReport(
        report: SymbiosisReport,
        userTask: String,
        fallbackReason: String? = null,
        systemPrompt: String? = null,
        variantSeed: Long = System.nanoTime()
    ): String = buildString {
        val intent = classifyOfflineIntent(userTask, systemPrompt)
        val headerPrefix = if (!fallbackReason.isNullOrBlank()) {
            "⚡ 6 МАЛИПУСИКОВ ОФЛАЙН (АВТО-ПОДХВАТ: $fallbackReason)"
        } else {
            "⚡ 6 МАЛИПУСИКОВ ОФЛАЙН (0 БАЙТ В СЕТЬ • ${report.inferenceMs} мс)"
        }
        append(headerPrefix)
        append(" • [${intent.name}]\n")
        append("💋 Вика: ${generateImageQuip(report, intent, variantSeed)}\n")
        if (userTask.isNotBlank()) {
            append("Запрос: «${userTask.trim().take(90)}»\n")
        }

        when (intent) {
            OfflineTaskIntent.OCR_TEXT -> {
                val linesCount = report.extractedText.lines().count { it.isNotBlank() }
                append("\n📝 [OCR ЭКСТРАКТОР • строк: $linesCount]:\n")
                if (report.extractedText.isNotBlank()) {
                    append(report.extractedText.trim().take(750))
                    append("\n")
                } else {
                    append("Читаемый текст в кадре не найден.\n")
                }
                append("\n• [ЧИТАЕМОСТЬ]: свет ${report.quality.avgLuma}/255 (${report.quality.dominantTintRu}), резкость ${report.quality.sharpnessScore}, пересвет ${report.quality.clippedHighlightsPct}%")
                if (report.quality.isBlurry) append(" — смаз, включи режим «Документы» или ручной фокус PRO")
                if (report.quality.isLowLight) append(" — темно, включи фонарик или Ч/Б Документ")
                if (report.quality.clippedHighlightsPct >= 15) append(" — сильный блик на бумаге, убавь EV на -0.5")
                append("\n")
                if (report.osintBlockHits.isNotEmpty()) {
                    val tags = report.osintBlockHits.flatMap { it.matchedLabels }.distinct().joinToString(", ")
                    append("• [ЧУВСТВИТЕЛЬНЫЕ ПОЛЯ (${report.osintBlockHits.size} бл.)]: $tags — можно скрыть кнопкой «МАСКА» в Сейфе.\n")
                } else if (report.osintReport.warnings.isNotEmpty()) {
                    append("• [НАЙДЕННЫЕ ДАННЫЕ В ТЕКСТЕ]: ${report.osintReport.warnings.joinToString("; ")}\n")
                }
            }

            OfflineTaskIntent.OSINT_AUDIT -> {
                append("\n🕵️ [OSINT-ПАЛЕВО: ${report.osintReport.riskScore}/100]:\n")
                if (report.osintBlockHits.isNotEmpty()) {
                    val grouped = report.osintBlockHits.flatMap { it.matchedLabels }.groupingBy { it }.eachCount()
                    append("• Прицельные совпадения в блоках: ${grouped.entries.joinToString { "${it.key} (${it.value})" }}\n")
                }
                if (report.osintReport.warnings.isNotEmpty()) {
                    report.osintReport.warnings.forEach { w -> append("• ⚠ $w\n") }
                } else if (report.osintBlockHits.isEmpty()) {
                    append("• Явных карт, номеров авто, координат или паролей в тексте не найдено.\n")
                }
                if (report.faces.isNotEmpty()) {
                    append("• Лиц в кадре: ${report.faces.size} (углы: ${report.faces.joinToString { "${it.yawDeg.toInt()}°" }}) — рекомендуется маска.\n")
                }
                if (report.extractedText.isNotBlank()) {
                    append("• Засвеченный текст: «${report.extractedText.replace('\n', ' ').trim().take(200)}»\n")
                }
                if (report.sceneTags.isNotEmpty()) {
                    append("• Окружение по тегам: ${report.sceneTags.take(5).joinToString { it.label }}\n")
                }
                append("• [ДЕЙСТВИЕ]: ")
                append(
                    if (report.osintReport.riskScore >= 40 || report.faces.isNotEmpty() || report.osintBlockHits.isNotEmpty()) {
                        "Открой снимок в Сейфе -> «МАСКА» (выборочно затри OSINT-поля и лица) и очисти EXIF."
                    } else {
                        "Критичных зацепок малипусики не увидели; перед публикацией проверь фон глазами."
                    }
                )
            }

            OfflineTaskIntent.SENTINEL_DOZOR -> {
                append("\n🛡️ [КИБЕР-ДОЗОР 6-NN]:\n")
                val peopleHint = buildList {
                    if (report.faces.isNotEmpty()) add("лиц: ${report.faces.size}")
                    if (report.pose.detected) add("скелет: ${report.pose.postureRu}")
                    report.foregroundCoveragePct?.let { add("фигура в кадре ~${it.toInt()}%") }
                    report.skinMetrics?.takeIf { it.isCoherentSkinPresent && report.faces.isEmpty() }?.let {
                        add("кожа YCbCr ${(it.skinFraction * 100f).toInt()}% (лицо отвёрнуто/скрыто)")
                    }
                }
                if (peopleHint.isEmpty()) {
                    append("• В кадре людей, поз и лиц не обнаружено.\n")
                } else {
                    append("• Обнаружено: ${peopleHint.joinToString(" • ")}\n")
                }
                if (report.objects.isNotEmpty()) {
                    append("• Объекты рядом: ${report.objects.take(4).joinToString { "${it.label} (${(it.confidence * 100).toInt()}%)" }}\n")
                }
                append("• Освещённость: ${report.quality.avgLuma}/255, контраст ${report.quality.contrastSpan}")
                append(if (report.quality.isLowLight) " (ТЕМНО — рекомендуется CLAHE анти-блик)\n" else " (норма)\n")
                append("• [ЧЕСТНЫЙ СТАТУС]: Локальные модели не угадывают цвет/тип формы по размытым силуэтам — для точного опознания включи BYOK-триаж с кропом.")
            }

            OfflineTaskIntent.OPTICS_FORENSIC -> {
                append("\n🔬 [ОПТИКА И ФОРЕНЗИК]:\n")
                append("• Яркость кадра: ${report.quality.avgLuma}/255 (контраст ${report.quality.contrastSpan}, оттенок: ${report.quality.dominantTintRu})\n")
                append("• Динамика: пересвет ${report.quality.clippedHighlightsPct}% | провал теней ${report.quality.crushedShadowsPct}%\n")
                append("• Резкость (edge-градиент): ${report.quality.sharpnessScore} ")
                append(if (report.quality.isBlurry) "(МЫЛО/СМАЗ — ниже порога 14)\n" else "(резко)\n")
                report.foregroundCoveragePct?.let {
                    append("• Заполнение передним планом: ${it.toInt()}%\n")
                }
                append("• [СОВЕТ КАМЕРЕ]: ")
                when {
                    report.quality.isLowLight && report.quality.isBlurry ->
                        append("Увеличь свет/фонарик, включи Anti-Blur или профиль NIGHT + CLAHE.")
                    report.quality.clippedHighlightsPct >= 18 ->
                        append("В кадре пересвет (${report.quality.clippedHighlightsPct}%) — сдвинь EV в минус (-0.5..-1.0) и зафиксируй AE-L.")
                    report.quality.isBlurry ->
                        append("Тапни по экрану для фокуса, держи телефон двумя руками или крути MF в PRO-панели.")
                    report.quality.isLowLight ->
                        append("Подними +0.5/+1.0 EV в PRO-панели или сними через NIGHT/Мульти-стек.")
                    else ->
                        append("Экспозиция и резкость в норме, кадр пригоден для OCR/OSINT и архива.")
                }
            }

            else -> {
                val summaryParts = buildList {
                    if (report.faces.isNotEmpty()) {
                        add("лиц: ${report.faces.size} (ракурс ${report.faces.first().yawDeg.toInt()}°)")
                    }
                    if (report.pose.detected) add("поза: ${report.pose.postureRu}")
                    report.foregroundCoveragePct?.takeIf { it >= 3f }?.let { add("силуэт: ${it.toInt()}%") }
                    report.skinMetrics?.takeIf { it.isCoherentSkinPresent && report.faces.isEmpty() }?.let { sm ->
                        add("кожа YCbCr ${(sm.skinFraction * 100f).toInt()}%")
                    }
                    if (report.objects.isNotEmpty()) {
                        add("объекты: ${report.objects.take(4).joinToString { "${it.label} ${(it.confidence * 100).toInt()}%" }}")
                    }
                }
                append("\n• [ЧЁ ПО КАДРУ]: ")
                append(if (summaryParts.isEmpty()) "крупных лиц/объектов не выделено" else summaryParts.joinToString(" | "))
                append("\n")

                if (report.sceneTags.isNotEmpty()) {
                    append("• [ТЕГИ 400-NN]: ${report.sceneTags.take(7).joinToString(", ") { "${it.label} ${(it.confidence * 100).toInt()}%" }}\n")
                }
                append("• [ОПТИКА]: свет ${report.quality.avgLuma}/255 (${report.quality.dominantTintRu}), резкость ${report.quality.sharpnessScore}")
                if (report.quality.clippedHighlightsPct >= 15) append(", пересвет ${report.quality.clippedHighlightsPct}%")
                if (report.quality.isLowLight) append(" (темновато)")
                if (report.quality.isBlurry) append(" (смаз!)")
                append("\n")

                if (report.extractedText.isNotBlank()) {
                    append("• [OCR]: ${report.extractedText.trim().take(280)}\n")
                }
                if (report.osintReport.warnings.isNotEmpty()) {
                    append("• [ПАЛЕВО ${report.osintReport.riskScore}/100]: ${report.osintReport.warnings.joinToString("; ")}\n")
                }
                append("• [ВЕРДИКТ]: ")
                when {
                    report.quality.isBlurry && report.quality.isLowLight ->
                        append("Темно и мыльно — сними ближе со светом или CLAHE.")
                    report.osintReport.riskScore >= 50 || report.osintBlockHits.isNotEmpty() ->
                        append("Есть риск засвета (палево ${report.osintReport.riskScore}/100) — прогони «МАСКА» в Сейфе.")
                    summaryParts.isEmpty() && report.sceneTags.isEmpty() && report.extractedText.isBlank() ->
                        append("Сцена без явных объектов для локальных ML Kit весов; для детального разбора подключи BYOK ключ + сеть.")
                    else ->
                        append("Все 6 малипусиков отработали чисто (0 байт в сеть).")
                }
            }
        }
    }

    /**
     * Text-only offline fallback when no image is attached or a system/how-to question is asked offline:
     * produces focused, live-state answers across 10 distinct intent domains with procedural Vika humor.
     */
    fun buildOfflineTextOnlyReply(
        context: android.content.Context,
        userTask: String,
        systemPrompt: String? = null,
        variantSeed: Long = System.nanoTime()
    ): String {
        val intent = classifyOfflineIntent(userTask, systemPrompt)
        val spice = generateTextQuip(intent, variantSeed)
        return when (intent) {
            OfflineTaskIntent.STATS_AUDIT ->
                "📊 ЛОКАЛЬНЫЙ АУДИТ (ОФЛАЙН)\n💋 Вика: $spice\n\n${com.example.security.VaultAnalytics.buildTelemetrySummaryForAi(context)}"

            OfflineTaskIntent.BYOK_CHANNEL -> {
                val cfg = com.example.assist.ByokManager.getTransportConfig(context)
                val hasKey = com.example.assist.ByokManager.hasKey(context)
                val strongBox = com.example.assist.ByokManager.isStrongBoxSupported(context)
                val orbotInstalled = com.example.assist.ByokManager.hasOrbotInstalled(context)
                val memOn = com.example.assist.ByokManager.isContextMemoryEnabled(context)
                buildString {
                    appendLine("🔐 BYOK-КАНАЛ ВИКИ (ОФЛАЙН-СПРАВКА):")
                    appendLine("💋 Вика: $spice")
                    appendLine("• Ключ в KeyStore: ${if (hasKey) "СОХРАНЁН" else "НЕ ЗАДАН"} (${if (strongBox) "StrongBox" else "Hardware TEE"})")
                    appendLine("• Канал: ${cfg.describeShort()} • Orbot в системе: ${if (orbotInstalled) "НАЙДЕН" else "не установлен"}")
                    appendLine("• Контекст диалога (4 хода в ОЗУ): ${if (memOn) "ВКЛ" else "ВЫКЛ"} • HTTP-кеш = 0 • редиректы отключены")
                    append("• Для Tor/Orbot включи SOCKS5 (127.0.0.1:9050) и жми «🔌 ПРОВЕРИТЬ ПРОКСИ» во вкладке «🔑 Ключ и Модели».")
                }
            }

            OfflineTaskIntent.VAULT_BACKUP -> {
                val filesCount = com.example.crypto.GhostCryptoVault.listVaultFiles(context).size
                val ramCount = com.example.crypto.GhostCryptoVault.listRamShots().size
                val ramMode = com.example.security.ZverPrefs.isRamOnlyMode(context)
                val masterStats = com.example.crypto.MonoVaultEngine.getZoneStats(
                    context,
                    com.example.crypto.MonoVaultEngine.VolumeType.MASTER
                )
                val decoyStats = com.example.crypto.MonoVaultEngine.getZoneStats(
                    context,
                    com.example.crypto.MonoVaultEngine.VolumeType.DECOY
                )
                buildString {
                    appendLine("📦 СЕЙФ, МОНОЛИТ И ПЕРЕНОС ДАННЫХ:")
                    appendLine("💋 Вика: $spice")
                    appendLine("• Активный профиль (${com.example.crypto.GhostCryptoVault.activeProfile.name}): $filesCount файлов (.gcf) + $ramCount в ОЗУ (RAM-Only: ${if (ramMode) "ВКЛ" else "ВЫКЛ"}).")
                    appendLine("• Видео GCF2 (256 КБ чанки) • Монолит: MASTER=${masterStats.recordCount} зап. | DECOY=${decoyStats.recordCount} зап.")
                    append("• Для переноса на новый телефон или после пересборки открой Сейф → «.ZVB ЭКСПОРТ», «🕵️ СТЕГО» или «Восстановить из Монолита».")
                }
            }

            OfflineTaskIntent.SENTINEL_DOZOR -> {
                val armed = com.example.security.SentinelEngine.isArmed()
                val cfg = com.example.security.SentinelEngine.getConfig()
                val events = com.example.security.SentinelEngine.getEvents().size
                buildString {
                    appendLine("🛡️ ДОЗОР (SENTINEL v5.0 — ЖИВОЙ СТАТУС):")
                    appendLine("💋 Вика: $spice")
                    appendLine("• Статус: ${if (armed) "НА ПОСТУ" else "СНЯТ"} • Событий в журнале: $events")
                    appendLine("• Фильтр прохожих: ${cfg.lingerThresholdSec} сек | Датчик стука: ${if (cfg.knockTriggerEnabled) "ВКЛ" else "ВЫКЛ"} | Авто-CLAHE ночь: ${if (cfg.autoNightClaheEnabled) "ВКЛ" else "ВЫКЛ"}")
                    append("• Можно ставить на охрану прямо из Termux командой: am start -d \"zver://sentinel?arm=1\"")
                }
            }

            OfflineTaskIntent.AUTOMATION_TERMUX -> {
                val eco = com.example.assist.AutomationRouter.inspectEcosystem(context)
                buildString {
                    appendLine("🤖 АВТОМАТИЗАЦИЯ TERMUX / SHIZUKU / zver://:")
                    appendLine("💋 Вика: $spice")
                    appendLine("• В системе: Termux=${if (eco.termux) "ДА" else "нет"}, Termux:API=${if (eco.termuxApi) "ДА" else "нет"}, Shizuku=${if (eco.shizuku) "ДА(${eco.shizukuVersion})" else "нет"}, MacroDroid=${if (eco.macroDroid) "ДА" else "нет"}")
                    appendLine("• Рабочие URI (без root): zver://capture | zver://torch?on=1 | zver://sentinel?arm=1 | zver://mode?name=NIGHT | zver://ram?on=1 | zver://lock")
                    append("• Все готовые команды и тестовый импульс лежат в Настройках → вкладка «⚡ Termux & Скрипты».")
                }
            }

            OfflineTaskIntent.CAM_PRO_HAL -> {
                val profile = com.example.camera.CameraCapabilityProfiler.profileCamera(
                    context,
                    androidx.camera.core.CameraSelector.LENS_FACING_BACK
                )
                val iq = com.example.security.ZverPrefs.getPhotoIqProfile(context)
                buildString {
                    appendLine("📸 ОПТИКА, HAL И РЕЖИМЫ СЪЁМКИ:")
                    appendLine("💋 Вика: $spice")
                    appendLine("• Задняя камера #${profile.cameraId}: HAL=${profile.hardwareLevelName} | макс JPEG=${profile.maxJpegRes} | RAW=${if (profile.hasRawCapability) "Да" else "Нет"} | EV=${profile.evRange} | AF=${if (profile.isManualFocusSupported) "ручной" else "авто"}")
                    appendLine("• Текущее улучшение фото: ${iq.labelRu} • ${profile.halSafetyNoteRu}")
                    append("• В PRO-панели доступны EV, баланс белого, Фикс AE/WB, гистограмма/зебра и FPS; в Визоре — 13 режимов анализа (пульс, PCB, Sobel, OCR).")
                }
            }

            OfflineTaskIntent.CAMOUFLAGE_PANIC -> {
                val camo = com.example.security.CamouflageManager.getCurrentCamouflage(context)
                val vol = com.example.security.ZverPrefs.getVolumeKeyAction(context)
                val duress = com.example.crypto.GhostCryptoVault.hasDuressConfigured(context)
                val dms = com.example.security.ZverPrefs.getDeadManDays(context)
                buildString {
                    appendLine("🕶️ МАСКИРОВКА, ПРОТИВОДЕЙСТВИЕ И ЭКСТРЕННЫЙ СБРОС:")
                    appendLine("💋 Вика: $spice")
                    appendLine("• Активная маска иконки: ${camo.name} | Кнопка громкости: ${vol.labelRu}")
                    appendLine("• Duress-PIN (ложный профиль + приманки): ${if (duress) "НАСТРОЕН" else "не задан"} | Dead-Man Switch: ${if (dms > 0) "$dms дн." else "ВЫКЛ"}")
                    append("• Для быстрого ухода в блокировку/фейк-обновление/чёрный экран поддерживается zver://lock и 5 стилей стелс-ширмы.")
                }
            }

            OfflineTaskIntent.OPSEC_SCAN -> {
                val opsec = com.example.security.OpsecSurfaceScanner.scanSurface(context)
                buildString {
                    appendLine("💋 Вика: $spice")
                    append(opsec.formatCompactSummaryRu())
                }
            }

            OfflineTaskIntent.OCR_TEXT,
            OfflineTaskIntent.OSINT_AUDIT,
            OfflineTaskIntent.OPTICS_FORENSIC -> {
                val actionHint = when (intent) {
                    OfflineTaskIntent.OCR_TEXT -> "распознать текст, даты, суммы и печати офлайн (ML Kit Text)"
                    OfflineTaskIntent.OSINT_AUDIT -> "прогнать поиск карт (Luhn), телефонов, госномеров, координат, e-mail и лиц"
                    else -> "оценить резкость, пересвет, провал теней, цветовой сдвиг и смаз"
                }
                buildString {
                    appendLine("📷 НУЖЕН КАДР ДЛЯ АНАЛИЗА (${intent.name}):")
                    appendLine("💋 Вика: $spice")
                    appendLine("• Ты выбрал текстовый режим («💬 ТЕКСТ»), поэтому картинка сейчас не прикреплена.")
                    append("• Переключи сверху чип на «🎯 +КРОП» или «🖼️ ВСЯ» и жми «⚡ Малипусики Офлайн» — 6 нейронок смогут $actionHint без байта в сеть.")
                }
            }

            OfflineTaskIntent.SCENE_GENERAL -> {
                val filesCount = com.example.crypto.GhostCryptoVault.listVaultFiles(context).size
                val armed = com.example.security.SentinelEngine.isArmed()
                val hasKey = com.example.assist.ByokManager.hasKey(context)
                val model = com.example.assist.ByokManager.getModelName(context)
                buildString {
                    appendLine("🐾 ВИКА И 6 МАЛИПУСИКОВ ОФЛАЙН:")
                    appendLine("💋 Вика: $spice")
                    appendLine("• Свободный запрос «${userTask.trim().take(65)}» остался при тебе — 0 байт в облако.")
                    appendLine("• По борту сейчас: в сейфе $filesCount файл(ов), Дозор=${if (armed) "АКТИВЕН" else "выкл"}, BYOK-ключ=${if (hasKey) "в KeyStore ($model)" else "не задан"}.")
                    append("• Спроси про Дозор, OPSEC-сканер, Сплит-ключ, Termux/zver://, PRO HAL, Сейф/Стего, Tor/BYOK — распишу с огоньком офлайн, или кидай кадр малипусикам!")
                }
            }
        }
    }

    private fun pickBySeed(pool: List<String>, seed: Long, salt: Int): String {
        val mixed = (seed xor (salt.toLong() * 2654435761L)) and 0x7FFFFFFFL
        val idx = (mixed % pool.size.toLong()).toInt()
        return pool[idx]
    }

    private fun generateImageQuip(report: SymbiosisReport, intent: OfflineTaskIntent, seed: Long): String {
        val telemetrySalt = report.quality.avgLuma * 31 + report.quality.sharpnessScore * 17 +
            report.faces.size * 13 + report.osintReport.riskScore * 7 + intent.ordinal * 41
        return when {
            report.osintReport.riskScore >= 40 || report.osintBlockHits.isNotEmpty() -> pickBySeed(
                listOf(
                    "Опачки, Саня, тут палева на весь квартал — малипусики аж свистнули! Быстро замазывай, пока не утекло! 😈🔥👀",
                    "Ррр 🐾! Мега Пупсик, в кадре светятся лишние данные — прикрой это дело «МАСКОЙ» в Сейфе, не свети тылы! 🍑⚡",
                    "Ай-ай, шпион наш любимый, чуть лишнего не спалил! Жми «МАСКА» и прячь улики под замок! 💋🕵️‍♂️🔥"
                ),
                seed,
                telemetrySalt
            )
            report.quality.isBlurry && report.quality.isLowLight -> pickBySeed(
                listOf(
                    "Зай, темно как в бункере без свечки и руки дрожат, будто после ночной смены — давай свет и держи крепче! 😈🔦🐾",
                    "Ррр 🐾, тут даже наши 6 малипусиков глаза сломали: темнота и мыло! Врубай NIGHT/CLAHE, Мега Пупсик! 🔥👀",
                    "Ну ты дал, пупсик — снял впотьмах на бегу 💋😂 Подсвети и замри на секунду, тогда разнесу по красоте! ⚡"
                ),
                seed,
                telemetrySalt
            )
            report.quality.isBlurry -> pickBySeed(
                listOf(
                    "Ручки шаловливые дёрнулись, Саня? 😈 Кадр мыльноват — зафиксируй локти или крутани ручной фокус, зай! 🐾🎯",
                    "Эй, Мега Пупсик, это ж не гонки — смазал картинку! Щёлкни ещё раз порезче, малипусики ждут чёткости! 🔥💋",
                    "Малипусики прищурились, но тут лёгкое мыло 👀💦 Тапни фокус по объекту, будет сок! ⚡"
                ),
                seed,
                telemetrySalt
            )
            report.quality.clippedHighlightsPct >= 18 -> pickBySeed(
                listOf(
                    "Ослепил меня напрочь, горячий ты наш! 🔥😎 Пересвет жарит матрицу — крутни EV в минус! 😈📸",
                    "Света навалил столько, что аж пиксели вспотели 💦🌞 Убавь экспозицию, пупсик, а то детали выгорели! 🐾"
                ),
                seed,
                telemetrySalt
            )
            report.faces.isNotEmpty() -> pickBySeed(
                listOf(
                    "Вижу мордашку в прицеле, Саня! 👀🎯 Малипусики уже обвели контур — прячем под весёлую маску или оставляем? 😈🐾",
                    "Опа, фейс пойман чётко! 💋🔥 Если кадр на вынос — прогони маску/кроп, не оставляй лишних следов! 🍑⚡"
                ),
                seed,
                telemetrySalt
            )
            else -> pickBySeed(
                listOf(
                    "Ррр 🐾, Мега Пупсик, малипусики всё перерыли за ${report.inferenceMs} мс и ни байта не слили в сеть! Красота же! 😈🔥💋",
                    "Чистенько отработали, зай! 6 малипусиков отчитались как по часам — теперь твоё слово, босс! 🐾⚡🍑",
                    "Всё под контролем, солнышко! Локальный отряд малипусиков бодр, злой и пашет только на тебя! 😈🔥👀"
                ),
                seed,
                telemetrySalt
            )
        }
    }

    private fun generateTextQuip(intent: OfflineTaskIntent, seed: Long): String {
        val salt = intent.ordinal * 97 + 13
        return when (intent) {
            OfflineTaskIntent.SENTINEL_DOZOR -> pickBySeed(
                listOf(
                    "Дозор бдит, ушки на макушке — ни один хитрый тип мимо порога не проскочит! 🐾🛡️😈",
                    "Ррр 🐾, я на стрёме вместе с малипусиками! Порог под присмотром, спи спокойно, Мега Пупсик! 🔥👀",
                    "Система в боевой стойке: если кто-то замаячит под дверью — сразу щёлкнем и запишем! 😈⚡"
                ),
                seed,
                salt
            )
            OfflineTaskIntent.BYOK_CHANNEL -> pickBySeed(
                listOf(
                    "Труба зашифрована так, что даже самый любопытный нос обломается! 🔐😈🔥",
                    "Ключик спрятан глубоко в KeyStore, редиректы отрезаны, канал чистый как твоя совесть, пупсик! 💋🧅⚡",
                    "Если хочешь максимальный инкогнито — подрубай Orbot SOCKS5 и летим по луковой сети! 🧅🐾😈"
                ),
                seed,
                salt
            )
            OfflineTaskIntent.VAULT_BACKUP -> pickBySeed(
                listOf(
                    "Схрон заперт наглухо, Монолит на месте, а ещё умеем прятать секреты в PNG и дробить ключ по Шамиру 2-из-3! 🕵️‍♂️🔥🍑",
                    "Всё добро под AES-256-GCM: хоть в .zvb пакуй, хоть в стего-картинку зашивай, хоть сплит-ключом дели! 😈💋📦"
                ),
                seed,
                salt
            )
            OfflineTaskIntent.AUTOMATION_TERMUX -> pickBySeed(
                listOf(
                    "О, наш любимый киберпанк из говна и скриптов подъехал! ⚡😈 Бери URI-команды и рули камерой из Termux! 🐾",
                    "Без рута, по-царски, через zver:// и Shizuku — ты реально гений оптимизации, зай! 🔥🤖💋"
                ),
                seed,
                salt
            )
            OfflineTaskIntent.CAM_PRO_HAL -> pickBySeed(
                listOf(
                    "Крутим выдержку, ISO, фокус и FPS как профи — Redmi выжимаем до последней капли! 📸😈🔥",
                    "PRO-режим заряжен, AWB-L под рукой: настраивай картинку сочно, пупсик! 💋🎯⚡"
                ),
                seed,
                salt
            )
            OfflineTaskIntent.CAMOUFLAGE_PANIC -> pickBySeed(
                listOf(
                    "Ширма наготове: хочешь калькулятор, хочешь фейк-обновление Android — пусть глядят и обламываются! 🎭😈🍑",
                    "Приманки, Duress-PIN и фейковый разряд батареи — чистая магия прикрытия для моего шпиона! 🐾🕶️🔥"
                ),
                seed,
                salt
            )
            OfflineTaskIntent.OPSEC_SCAN -> pickBySeed(
                listOf(
                    "Ррр 🐾, просветила твой телефон рентгеном: внешние экраны, ADB и DNS под моим колпаком! 🕵️‍♀️🔥😈",
                    "Проверка чистоты контура пошла — сейчас посмотрим, не подглядывает ли кто в твой экран, зай! 👀⚡💋"
                ),
                seed,
                salt
            )
            else -> pickBySeed(
                listOf(
                    "Ррр 🐾, я на связи даже в глухом бункере без интернета! 😈🔥💋",
                    "Малипусики уже размяли лапки и готовы пахать за 0 байт трафика, Мега Пупсик! 🐾⚡🍑",
                    "Скучно без сети? Не с Викой! Кидай кадр или жми команды — разберём всё по полочкам! 😈👀🔥"
                ),
                seed,
                salt
            )
        }
    }

    /**
     * Uses bundled ML Kit Selfie Segmentation to keep ONLY the foreground person crisp
     * while strongly blurring/dimming the room, walls, or foliage behind (OPSEC cutout).
     */
    suspend fun isolateForegroundPersonClean(
        bitmap: Bitmap,
        blurBackground: Boolean = true
    ): IsolationResult = suspendCancellableCoroutine { cont ->
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        val input = InputImage.fromBitmap(bitmap, 0)

        segmenter.process(input)
            .addOnSuccessListener { mask ->
                try {
                    val mw = mask.width
                    val mh = mask.height
                    val floatBuf = mask.buffer.apply { rewind() }
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                    val count = min(floatBuf.remaining(), mw * mh)
                    val probs = FloatArray(mw * mh)
                    floatBuf.get(probs, 0, count)

                    var fgPixels = 0
                    for (i in probs.indices) {
                        if (probs[i] >= 0.55f) fgPixels++
                    }
                    val coveragePct = (fgPixels * 100f) / max(1, probs.size)
                    if (coveragePct < 3.0f) {
                        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        segmenter.close()
                        if (cont.isActive) cont.resume(IsolationResult(copy, coveragePct, false))
                        return@addOnSuccessListener
                    }

                    val w = bitmap.width
                    val h = bitmap.height
                    val srcScaled = if (w == mw && h == mh) bitmap else Bitmap.createScaledBitmap(bitmap, mw, mh, true)
                    val bgBmp = if (blurBackground) {
                        val tinyW = max(6, mw / 14)
                        val tinyH = max(6, mh / 14)
                        val tiny = Bitmap.createScaledBitmap(srcScaled, tinyW, tinyH, true)
                        val blurred = Bitmap.createScaledBitmap(tiny, mw, mh, true)
                        tiny.recycle()
                        blurred
                    } else null

                    val srcPixels = IntArray(mw * mh)
                    srcScaled.getPixels(srcPixels, 0, mw, 0, 0, mw, mh)
                    val bgPixels = if (bgBmp != null) {
                        IntArray(mw * mh).also { bgBmp.getPixels(it, 0, mw, 0, 0, mw, mh) }
                    } else null
                    bgBmp?.recycle()

                    val outPixels = IntArray(mw * mh)
                    for (i in outPixels.indices) {
                        val alpha = ((probs[i] - 0.35f) / 0.35f).coerceIn(0f, 1f)
                        val fg = srcPixels[i]
                        val bg = if (bgPixels != null) {
                            val bp = bgPixels[i]
                            // Dim blurred background to 35% so surroundings lose details
                            val br = ((Color.red(bp) * 35) / 100)
                            val bgG = ((Color.green(bp) * 35) / 100)
                            val bb = ((Color.blue(bp) * 35) / 100)
                            Color.rgb(br, bgG, bb)
                        } else {
                            Color.rgb(8, 12, 16)
                        }
                        if (alpha >= 0.98f) {
                            outPixels[i] = fg
                        } else if (alpha <= 0.02f) {
                            outPixels[i] = bg
                        } else {
                            val inv = 1f - alpha
                            val r = (Color.red(fg) * alpha + Color.red(bg) * inv).toInt().coerceIn(0, 255)
                            val g = (Color.green(fg) * alpha + Color.green(bg) * inv).toInt().coerceIn(0, 255)
                            val b = (Color.blue(fg) * alpha + Color.blue(bg) * inv).toInt().coerceIn(0, 255)
                            outPixels[i] = Color.rgb(r, g, b)
                        }
                    }

                    val maskedSmall = Bitmap.createBitmap(outPixels, mw, mh, Bitmap.Config.ARGB_8888)
                    val finalBmp = if (w == mw && h == mh) {
                        maskedSmall
                    } else {
                        Bitmap.createScaledBitmap(maskedSmall, w, h, true).also { maskedSmall.recycle() }
                    }
                    if (srcScaled !== bitmap) srcScaled.recycle()

                    segmenter.close()
                    if (cont.isActive) cont.resume(IsolationResult(finalBmp, coveragePct, true))
                } catch (_: Exception) {
                    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    segmenter.close()
                    if (cont.isActive) cont.resume(IsolationResult(copy, 0f, false))
                }
            }
            .addOnFailureListener {
                segmenter.close()
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                if (cont.isActive) cont.resume(IsolationResult(copy, 0f, false))
            }
    }

    private suspend fun measureForegroundCoverage(bitmap: Bitmap): Float? =
        suspendCancellableCoroutine { cont ->
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
            val segmenter = Segmentation.getClient(options)
            val input = InputImage.fromBitmap(bitmap, 0)
            segmenter.process(input)
                .addOnSuccessListener { mask ->
                    try {
                        val floatBuf = mask.buffer.apply { rewind() }
                            .order(ByteOrder.nativeOrder())
                            .asFloatBuffer()
                        val total = max(1, mask.width * mask.height)
                        var fg = 0
                        val n = min(floatBuf.remaining(), total)
                        for (i in 0 until n) {
                            if (floatBuf.get() >= 0.55f) fg++
                        }
                        segmenter.close()
                        if (cont.isActive) cont.resume((fg * 100f) / total)
                    } catch (_: Exception) {
                        segmenter.close()
                        if (cont.isActive) cont.resume(null)
                    }
                }
                .addOnFailureListener {
                    segmenter.close()
                    if (cont.isActive) cont.resume(null)
                }
        }

    suspend fun detectFacesRich(bitmap: Bitmap): List<FaceSummary> =
        suspendCancellableCoroutine { cont ->
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
            val detector = FaceDetection.getClient(options)
            val input = InputImage.fromBitmap(bitmap, 0)
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)

            detector.process(input)
                .addOnSuccessListener { faces ->
                    val res = faces.map { f ->
                        val b = f.boundingBox
                        FaceSummary(
                            rect = RectF(
                                (b.left / w).coerceIn(0f, 1f),
                                (b.top / h).coerceIn(0f, 1f),
                                (b.right / w).coerceIn(0f, 1f),
                                (b.bottom / h).coerceIn(0f, 1f)
                            ),
                            yawDeg = f.headEulerAngleY,
                            rollDeg = f.headEulerAngleZ
                        )
                    }
                    detector.close()
                    if (cont.isActive) cont.resume(res)
                }
                .addOnFailureListener {
                    detector.close()
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    suspend fun detectObjectsOffline(bitmap: Bitmap): List<DetectedObjectSummary> =
        suspendCancellableCoroutine { cont ->
            val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
            val detector = ObjectDetection.getClient(options)
            val input = InputImage.fromBitmap(bitmap, 0)
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)

            detector.process(input)
                .addOnSuccessListener { objs ->
                    val list = objs.map { obj ->
                        val b = obj.boundingBox
                        val bestLabel = obj.labels.maxByOrNull { it.confidence }
                        DetectedObjectSummary(
                            rect = RectF(
                                (b.left / w).coerceIn(0f, 1f),
                                (b.top / h).coerceIn(0f, 1f),
                                (b.right / w).coerceIn(0f, 1f),
                                (b.bottom / h).coerceIn(0f, 1f)
                            ),
                            label = translateObjectCategory(bestLabel?.text),
                            confidence = bestLabel?.confidence ?: 0.5f
                        )
                    }.sortedByDescending { it.rect.width() * it.rect.height() }
                    detector.close()
                    if (cont.isActive) cont.resume(list)
                }
                .addOnFailureListener {
                    detector.close()
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    suspend fun detectSceneLabelsOffline(bitmap: Bitmap): List<SceneTag> =
        suspendCancellableCoroutine { cont ->
            val options = ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.50f)
                .build()
            val labeler = ImageLabeling.getClient(options)
            val input = InputImage.fromBitmap(bitmap, 0)
            labeler.process(input)
                .addOnSuccessListener { labels ->
                    val list = labels.map { SceneTag(it.text, it.confidence) }
                        .sortedByDescending { it.confidence }
                        .take(10)
                    labeler.close()
                    if (cont.isActive) cont.resume(list)
                }
                .addOnFailureListener {
                    labeler.close()
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    suspend fun detectPoseOffline(bitmap: Bitmap): PoseSummary =
        suspendCancellableCoroutine { cont ->
            val options = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
                .build()
            val detector = PoseDetection.getClient(options)
            val input = InputImage.fromBitmap(bitmap, 0)
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)

            detector.process(input)
                .addOnSuccessListener { pose ->
                    val visible = pose.allPoseLandmarks.filter { it.inFrameLikelihood >= 0.45f }
                    if (visible.size < 4) {
                        detector.close()
                        if (cont.isActive) cont.resume(PoseSummary(false, visible.size, "нет фигуры", null))
                        return@addOnSuccessListener
                    }

                    val lShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                    val rShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                    val lWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
                    val rWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
                    val lHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
                    val lKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)

                    val handsUp = (lWrist != null && lShoulder != null && lWrist.position.y < lShoulder.position.y) ||
                        (rWrist != null && rShoulder != null && rWrist.position.y < rShoulder.position.y)
                    val crouching = lHip != null && lKnee != null && abs(lHip.position.y - lKnee.position.y) < h * 0.12f

                    val posture = when {
                        handsUp -> "человек (${visible.size} точек, рука поднята)"
                        crouching -> "человек (${visible.size} точек, сидит/присел)"
                        else -> "фигура человека (${visible.size}/33 точек)"
                    }

                    var minX = Float.MAX_VALUE
                    var minY = Float.MAX_VALUE
                    var maxX = 0f
                    var maxY = 0f
                    for (lm in visible) {
                        minX = min(minX, lm.position.x / w)
                        minY = min(minY, lm.position.y / h)
                        maxX = max(maxX, lm.position.x / w)
                        maxY = max(maxY, lm.position.y / h)
                    }
                    val torsoRect = RectF(
                        minX.coerceIn(0f, 1f),
                        minY.coerceIn(0f, 1f),
                        maxX.coerceIn(0f, 1f),
                        maxY.coerceIn(0f, 1f)
                    ).takeIf { it.width() > 0.04f && it.height() > 0.04f }

                    detector.close()
                    if (cont.isActive) cont.resume(PoseSummary(true, visible.size, posture, torsoRect))
                }
                .addOnFailureListener {
                    detector.close()
                    if (cont.isActive) cont.resume(PoseSummary(false, 0, "ошибка", null))
                }
        }

    private suspend fun detectTextRich(bitmap: Bitmap): Pair<List<OfflinePrivacyEngine.NormalizedRegion>, String> =
        OfflinePrivacyEngine.detectTextOffline(bitmap)

    fun assessFrameQuality(bitmap: Bitmap): FrameQuality {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return FrameQuality(128, false, 0, true)

        val stepX = max(1, w / 48)
        val stepY = max(1, h / 48)
        var sumLuma = 0L
        var edgeSum = 0L
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var clippedCount = 0
        var crushedCount = 0
        var minLum = 255
        var maxLum = 0
        var samples = 0

        var y = stepY
        while (y < h - stepY) {
            var x = stepX
            while (x < w - stepX) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val lum = (77 * r + 150 * g + 29 * b) shr 8
                val pRight = bitmap.getPixel(x + stepX, y)
                val lumR = (77 * Color.red(pRight) + 150 * Color.green(pRight) + 29 * Color.blue(pRight)) shr 8
                val pDown = bitmap.getPixel(x, y + stepY)
                val lumD = (77 * Color.red(pDown) + 150 * Color.green(pDown) + 29 * Color.blue(pDown)) shr 8

                sumLuma += lum
                sumR += r
                sumG += g
                sumB += b
                if (lum >= 245) clippedCount++
                if (lum <= 18) crushedCount++
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
                edgeSum += abs(lum - lumR) + abs(lum - lumD)
                samples++
                x += stepX
            }
            y += stepY
        }

        val safeSamples = max(1, samples)
        val avgLuma = (sumLuma / safeSamples).toInt().coerceIn(0, 255)
        val sharpness = (edgeSum / safeSamples).toInt()
        val clippedPct = (clippedCount * 100) / safeSamples
        val crushedPct = (crushedCount * 100) / safeSamples
        val span = (maxLum - minLum).coerceIn(0, 255)
        val avgR = sumR / safeSamples
        val avgG = sumG / safeSamples
        val avgB = sumB / safeSamples
        val tint = when {
            avgR > avgB + 24 && avgR > avgG + 10 -> "тёплый/лампы"
            avgB > avgR + 24 && avgB > avgG + 10 -> "холодный/синий"
            avgG > avgR + 18 && avgG > avgB + 18 -> "зелёный/листва"
            else -> "нейтральный"
        }
        return FrameQuality(
            avgLuma = avgLuma,
            isLowLight = avgLuma < 60,
            sharpnessScore = sharpness,
            isBlurry = sharpness < 14,
            clippedHighlightsPct = clippedPct,
            crushedShadowsPct = crushedPct,
            contrastSpan = span,
            dominantTintRu = tint
        )
    }

    private fun translateObjectCategory(raw: String?): String = when (raw?.lowercase()) {
        "fashion good" -> "одежда/аксессуар"
        "food" -> "еда"
        "home good" -> "предмет быта"
        "place" -> "место/здание"
        "plant" -> "растение/листва"
        null, "" -> "объект"
        else -> raw
    }
}
