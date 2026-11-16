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
        val isBlurry: Boolean
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
        val skinMetrics: YcbcrSkinAnalyzer.SkinMetrics? = null
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
            val textDef = async { detectTextRich(bitmap) }
            val segDef = if (includeForegroundCoverage) {
                async { measureForegroundCoverage(bitmap) }
            } else null

            val faces = facesDef.await()
            val objects = objectsDef.await()
            val labels = labelsDef.await()
            val pose = poseDef.await()
            val (textRegions, rawText) = textDef.await()
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
                skinMetrics = skin
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

    /**
     * Builds an honest, concise, Vika-styled offline report when internet is unavailable,
     * no API key is configured, Gemini 429 quota is exhausted, or user runs "Малыши Офлайн".
     */
    fun buildOfflineVikaReport(
        report: SymbiosisReport,
        userTask: String,
        fallbackReason: String? = null
    ): String = buildString {
        if (!fallbackReason.isNullOrBlank()) {
            append("⚡ АВТО-ПЕРЕКЛЮЧЕНИЕ НА МАЛЫШЕЙ ОФЛАЙН ($fallbackReason)\n")
        } else {
            append("⚡ ЛОКАЛЬНЫЙ СИМБИОЗ 6-NN (0 БАЙТ В СЕТЬ, ${report.inferenceMs} мс)\n")
        }
        if (userTask.isNotBlank()) {
            append("Задача: ${userTask.take(90)}\n")
        }

        append("\n• [ЧЁ ПО КАДРУ]: ")
        val summaryParts = mutableListOf<String>()
        if (report.faces.isNotEmpty()) {
            val yaw = report.faces.first().yawDeg.toInt()
            summaryParts.add("лиц: ${report.faces.size} (ракурс ${yaw}°)")
        }
        if (report.pose.detected) {
            summaryParts.add("силуэт/поза: ${report.pose.postureRu}")
        }
        report.skinMetrics?.takeIf { it.isCoherentSkinPresent }?.let { sm ->
            val pct = (sm.skinFraction * 100f).toInt()
            val extra = if (report.faces.isEmpty()) " — открытая кожа/силуэт без лица в анфас" else ""
            summaryParts.add("кожа YCbCr $pct%$extra")
        }
        if (report.objects.isNotEmpty()) {
            summaryParts.add(
                "объекты: " + report.objects.take(4).joinToString {
                    "${it.label} ${(it.confidence * 100).toInt()}%"
                }
            )
        }
        if (summaryParts.isEmpty()) {
            append("крупных лиц/объектов не выделено")
        } else {
            append(summaryParts.joinToString(" | "))
        }
        append("\n")

        if (report.sceneTags.isNotEmpty()) {
            append("• [ТЕГИ СЦЕНЫ]: ")
            append(report.sceneTags.take(7).joinToString(", ") { "${it.label} ${(it.confidence * 100).toInt()}%" })
            append("\n")
        }

        append("• [ОПТИКА]: свет ${report.quality.avgLuma}/255")
        if (report.quality.isLowLight) append(" (темновато, включай CLAHE)")
        append(", резкость ${report.quality.sharpnessScore}")
        if (report.quality.isBlurry) append(" (есть смаз!)")
        append("\n")

        if (report.extractedText.isNotBlank()) {
            append("• [OCR ТЕКСТ]: ${report.extractedText.trim().take(280)}\n")
        }

        if (report.osintReport.warnings.isNotEmpty()) {
            append("• [ПАЛЕВО ${report.osintReport.riskScore}/100]: ${report.osintReport.warnings.joinToString("; ")}\n")
        } else {
            append("• [ПАЛЕВО]: явных лиц/номеров/координат локально не засвечено.\n")
        }

        append("• [ВЕРДИКТ ВИКИ]: ")
        when {
            report.quality.isBlurry && report.quality.isLowLight ->
                append("Темно и мыльновато — пересними ближе или вруби CLAHE, иначе даже облако гадать начнёт.")
            report.osintReport.riskScore >= 50 ->
                append("Жирное палево в кадре, перед выгрузкой прогони через «МАСКА» в Сейфе!")
            report.faces.isEmpty() && report.objects.isEmpty() && report.sceneTags.isEmpty() ->
                append("Локальные малыши в замешательстве — тут нужна тяжёлая артиллерия Gemini, когда появится сеть/лимит.")
            else ->
                append("Картина ясная: локальный конвейер всё разложил без единого байта трафика.")
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
        var samples = 0

        var y = stepY
        while (y < h - stepY) {
            var x = stepX
            while (x < w - stepX) {
                val p = bitmap.getPixel(x, y)
                val lum = (77 * Color.red(p) + 150 * Color.green(p) + 29 * Color.blue(p)) shr 8
                val pRight = bitmap.getPixel(x + stepX, y)
                val lumR = (77 * Color.red(pRight) + 150 * Color.green(pRight) + 29 * Color.blue(pRight)) shr 8
                val pDown = bitmap.getPixel(x, y + stepY)
                val lumD = (77 * Color.red(pDown) + 150 * Color.green(pDown) + 29 * Color.blue(pDown)) shr 8

                sumLuma += lum
                edgeSum += abs(lum - lumR) + abs(lum - lumD)
                samples++
                x += stepX
            }
            y += stepY
        }

        val avgLuma = if (samples > 0) (sumLuma / samples).toInt().coerceIn(0, 255) else 128
        val sharpness = if (samples > 0) (edgeSum / samples).toInt() else 0
        return FrameQuality(
            avgLuma = avgLuma,
            isLowLight = avgLuma < 60,
            sharpnessScore = sharpness,
            isBlurry = sharpness < 14
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
