package com.example.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * OfflinePrivacyEngine (ObscuraCam Core, 100% On-Device):
 * - Runs bundled ML Kit Face Detection & Text Recognition strictly offline (0% network).
 * - Normalizes all regions into 0..1 coordinates for responsive Compose touch editing.
 * - Supports 4 redaction styles: BLACKOUT, PIXELATE, BLUR, MASK (cyber visor) + Invert Mode.
 */
object OfflinePrivacyEngine {

    enum class RedactionStyle(val labelRu: String) {
        BLACKOUT("БЛЭКАУТ"),
        PIXELATE("МОЗАИКА"),
        BLUR("БЛЮР"),
        MASK("МАСКА")
    }

    enum class RegionKind {
        FACE,
        TEXT,
        MANUAL
    }

    data class NormalizedRegion(
        val id: String,
        val rect: RectF, // normalized [0f..1f]
        val kind: RegionKind
    )

    data class DetectionResult(
        val faces: List<NormalizedRegion>,
        val textBlocks: List<NormalizedRegion>,
        val extractedText: String
    )

    suspend fun detectFacesOffline(bitmap: Bitmap): List<NormalizedRegion> =
        suspendCancellableCoroutine { cont ->
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .enableTracking()
                .build()
            val detector = FaceDetection.getClient(options)
            val input = InputImage.fromBitmap(bitmap, 0)
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)

            detector.process(input)
                .addOnSuccessListener { faces ->
                    val list = faces.mapIndexed { idx, f ->
                        val b = f.boundingBox
                        NormalizedRegion(
                            id = "face_$idx",
                            rect = RectF(
                                (b.left / w).coerceIn(0f, 1f),
                                (b.top / h).coerceIn(0f, 1f),
                                (b.right / w).coerceIn(0f, 1f),
                                (b.bottom / h).coerceIn(0f, 1f)
                            ),
                            kind = RegionKind.FACE
                        )
                    }
                    detector.close()
                    if (cont.isActive) cont.resume(list)
                }
                .addOnFailureListener {
                    detector.close()
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    suspend fun detectTextOffline(bitmap: Bitmap): Pair<List<NormalizedRegion>, String> =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val input = InputImage.fromBitmap(bitmap, 0)
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)

            recognizer.process(input)
                .addOnSuccessListener { visionText ->
                    val blocks = visionText.textBlocks.mapIndexedNotNull { idx, block ->
                        val b = block.boundingBox ?: return@mapIndexedNotNull null
                        NormalizedRegion(
                            id = "txt_$idx",
                            rect = RectF(
                                (b.left / w).coerceIn(0f, 1f),
                                (b.top / h).coerceIn(0f, 1f),
                                (b.right / w).coerceIn(0f, 1f),
                                (b.bottom / h).coerceIn(0f, 1f)
                            ),
                            kind = RegionKind.TEXT
                        )
                    }
                    recognizer.close()
                    if (cont.isActive) cont.resume(Pair(blocks, visionText.text))
                }
                .addOnFailureListener {
                    recognizer.close()
                    if (cont.isActive) cont.resume(Pair(emptyList(), ""))
                }
        }

    suspend fun analyzeFullOffline(bitmap: Bitmap): DetectionResult {
        val faces = detectFacesOffline(bitmap)
        val (texts, rawText) = detectTextOffline(bitmap)
        return DetectionResult(faces = faces, textBlocks = texts, extractedText = rawText)
    }

    data class OsintRiskReport(
        val warnings: List<String>,
        val riskScore: Int
    )

    private val PLATE_REGEX = Regex("\\b[A-ZАВЕІКМНОРСТХ]{2}\\s*\\d{4}\\s*[A-ZАВЕІКМНОРСТХ]{2}\\b", RegexOption.IGNORE_CASE)
    private val PHONE_REGEX = Regex("(?:\\+?\\d[\\d\\s\\-()]{7,}\\d)")
    private val COORD_REGEX = Regex("\\b\\d{2}\\.\\d{4,}\\b")
    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val CARD_CANDIDATE_REGEX = Regex("\\b(?:\\d[\\s-]?){15}\\d\\b")
    private val SECRET_KEY_REGEX = Regex(
        "(?:-----BEGIN [A-Z ]*PRIVATE KEY-----|\\b(?:seed|mnemonic|passphrase|password|пароль|пин|pin|api_?key|secret|token)\\b\\s*[:=]|\\b(?:AIza[0-9A-Za-z\\-_]{20,}|ghp_[0-9A-Za-z]{20,}|xox[baprs]-[0-9A-Za-z\\-]{10,}))",
        RegexOption.IGNORE_CASE
    )

    private fun passesLuhn(digits: String): Boolean {
        if (digits.length != 16 || digits.all { it == digits[0] }) return false
        var sum = 0
        var alt = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }

    /**
     * Offline OSINT Risk Linter: scans detected faces & ML Kit OCR text for accidental leaks
     * before sharing or saving an unredacted frame.
     */
    fun assessOsintRisks(detection: DetectionResult): OsintRiskReport {
        val warnings = mutableListOf<String>()
        var score = 0

        if (detection.faces.isNotEmpty()) {
            warnings.add("Лица в кадре: ${detection.faces.size} (рекомендуется маска/блюр)")
            score += 40
        }

        val text = detection.extractedText
        if (text.isNotBlank()) {
            if (SECRET_KEY_REGEX.containsMatchIn(text)) {
                warnings.add("КРИТИЧНО: пароль, токен или приватный ключ в кадре")
                score += 45
            }
            val hasValidCard = CARD_CANDIDATE_REGEX.findAll(text).any { m ->
                val digits = m.value.filter { it.isDigit() }
                passesLuhn(digits)
            }
            if (hasValidCard) {
                warnings.add("Обнаружен номер банковской карты (проверка Luhn)")
                score += 40
            }
            if (PLATE_REGEX.containsMatchIn(text)) {
                warnings.add("Возможный госномер авто в тексте")
                score += 35
            }
            if (PHONE_REGEX.containsMatchIn(text)) {
                warnings.add("Обнаружен телефонный номер / длинный цифровой код")
                score += 25
            }
            if (COORD_REGEX.containsMatchIn(text)) {
                warnings.add("Похоже на координаты / точные числа")
                score += 20
            }
            if (EMAIL_REGEX.containsMatchIn(text)) {
                warnings.add("Обнаружен e-mail / учётная строка")
                score += 20
            }
            if (warnings.none {
                    it.contains("текст") || it.contains("номер") || it.contains("e-mail") ||
                        it.contains("карт") || it.contains("ключ")
                } && detection.textBlocks.isNotEmpty()
            ) {
                warnings.add("Читаемый текст / вывески (${detection.textBlocks.size} блоков)")
                score += 15
            }
        }

        return OsintRiskReport(warnings = warnings, riskScore = score.coerceAtMost(100))
    }

    /**
     * Applies selected redactions onto a fresh ARGB_8888 bitmap without mutating [source].
     * When [invertMode] is true, blurs/pixelates/blackouts the ENTIRE background while keeping
     * ONLY the selected [regions] visible (ObscuraCam Crowd/Privacy Spotlight mode).
     */
    fun applyRedactions(
        source: Bitmap,
        regions: List<NormalizedRegion>,
        style: RedactionStyle,
        invertMode: Boolean
    ): Bitmap {
        val w = source.width
        val h = source.height
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        if (regions.isEmpty()) return out

        val pixelRects = regions.mapNotNull { r ->
            val left = (min(r.rect.left, r.rect.right) * w).toInt().coerceIn(0, w - 1)
            val top = (min(r.rect.top, r.rect.bottom) * h).toInt().coerceIn(0, h - 1)
            val right = (max(r.rect.left, r.rect.right) * w).toInt().coerceIn(1, w)
            val bottom = (max(r.rect.top, r.rect.bottom) * h).toInt().coerceIn(1, h)
            if (right - left >= 4 && bottom - top >= 4) Rect(left, top, right, bottom) else null
        }
        if (pixelRects.isEmpty()) return out

        val canvas = Canvas(out)

        if (invertMode) {
            // Redact whole frame first, then punch original pixels back inside selected regions
            when (style) {
                RedactionStyle.BLACKOUT, RedactionStyle.MASK -> {
                    canvas.drawColor(Color.BLACK)
                }
                RedactionStyle.PIXELATE -> {
                    val pix = createPixelatedCopy(source, blockSize = max(14, min(w, h) / 28))
                    canvas.drawBitmap(pix, 0f, 0f, null)
                    pix.recycle()
                }
                RedactionStyle.BLUR -> {
                    val blurred = createFastBoxBlurCopy(source, scaleFactor = 18)
                    canvas.drawBitmap(blurred, 0f, 0f, null)
                    blurred.recycle()
                }
            }
            for (rect in pixelRects) {
                canvas.drawBitmap(source, rect, rect, null)
            }
            return out
        }

        // Standard mode: redact each region
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (rect in pixelRects) {
            when (style) {
                RedactionStyle.BLACKOUT -> {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.BLACK
                    canvas.drawRect(rect, paint)
                }
                RedactionStyle.PIXELATE -> {
                    drawJitteredMosaic(
                        canvas = canvas,
                        source = source,
                        rect = rect,
                        blockSize = max(10, min(rect.width(), rect.height()) / 10)
                    )
                }
                RedactionStyle.BLUR -> {
                    val rw = rect.width()
                    val rh = rect.height()
                    val crop = Bitmap.createBitmap(source, rect.left, rect.top, rw, rh)
                    val blurred = createFastBoxBlurCopy(crop, scaleFactor = 14)
                    canvas.drawBitmap(blurred, rect.left.toFloat(), rect.top.toFloat(), null)
                    crop.recycle()
                    blurred.recycle()
                }
                RedactionStyle.MASK -> {
                    drawCyberVisorMask(canvas, rect)
                }
            }
        }
        return out
    }

    /**
     * Draws non-linear block mosaic with per-block jittered sampling + slight brightness noise
     * so faces/plates resist trivial linear pixelation deconvolution.
     */
    private fun drawJitteredMosaic(canvas: Canvas, source: Bitmap, rect: Rect, blockSize: Int) {
        val blockPaint = Paint().apply { style = Paint.Style.FILL }
        val step = max(6, blockSize)
        val w = source.width
        val h = source.height
        var by = rect.top
        var rowIdx = 0
        while (by < rect.bottom) {
            val cellBottom = min(rect.bottom, by + step)
            var bx = rect.left
            var colIdx = 0
            while (bx < rect.right) {
                val cellRight = min(rect.right, bx + step)
                val hash = (colIdx * 73856093) xor (rowIdx * 19349663) xor (w * 83492791)
                val jx = ((hash and 3) - 1) * max(1, step / 3)
                val jy = (((hash ushr 2) and 3) - 1) * max(1, step / 3)
                val sx = ((bx + cellRight) / 2 + jx).coerceIn(0, w - 1)
                val sy = ((by + cellBottom) / 2 + jy).coerceIn(0, h - 1)
                val c = source.getPixel(sx, sy)
                val delta = (((hash ushr 4) and 0x1F) - 15)
                val r = (Color.red(c) + delta).coerceIn(0, 255)
                val g = (Color.green(c) + delta).coerceIn(0, 255)
                val b = (Color.blue(c) + delta).coerceIn(0, 255)
                blockPaint.color = Color.rgb(r, g, b)
                canvas.drawRect(bx.toFloat(), by.toFloat(), cellRight.toFloat(), cellBottom.toFloat(), blockPaint)
                bx += step
                colIdx++
            }
            by += step
            rowIdx++
        }
    }

    private fun createPixelatedCopy(src: Bitmap, blockSize: Int): Bitmap {
        val dw = max(6, src.width / blockSize)
        val dh = max(6, src.height / blockSize)
        val tiny = Bitmap.createScaledBitmap(src, dw, dh, false)
        val up = Bitmap.createScaledBitmap(tiny, src.width, src.height, false)
        tiny.recycle()
        return up
    }

    private fun createFastBoxBlurCopy(src: Bitmap, scaleFactor: Int): Bitmap {
        val dw = max(4, src.width / scaleFactor)
        val dh = max(4, src.height / scaleFactor)
        val down = Bitmap.createScaledBitmap(src, dw, dh, true)
        val up = Bitmap.createScaledBitmap(down, src.width, src.height, true)
        down.recycle()
        return up
    }

    private fun drawCyberVisorMask(canvas: Canvas, rect: Rect) {
        val w = rect.width().toFloat()
        val h = rect.height().toFloat()
        val visorTop = rect.top + h * 0.22f
        val visorBottom = rect.top + h * 0.56f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A0F14")
            style = Paint.Style.FILL
        }
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00FF66")
            style = Paint.Style.STROKE
            strokeWidth = max(3f, w * 0.03f)
        }
        val lensRect = RectF(
            rect.left + w * 0.06f,
            visorTop,
            rect.right - w * 0.06f,
            visorBottom
        )
        val radius = h * 0.08f
        canvas.drawRoundRect(lensRect, radius, radius, bgPaint)
        canvas.drawRoundRect(lensRect, radius, radius, framePaint)

        // Subtle reflection stripe across cyber visor
        val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6600E5FF")
            strokeWidth = max(2f, h * 0.04f)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }
        canvas.drawLine(
            lensRect.left + w * 0.12f,
            visorTop + h * 0.08f,
            lensRect.left + w * 0.35f,
            visorBottom - h * 0.08f,
            glintPaint
        )
    }
}
