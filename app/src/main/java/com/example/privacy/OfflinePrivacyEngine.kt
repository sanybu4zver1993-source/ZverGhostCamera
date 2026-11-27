package com.example.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.roundToInt

/**
 * OfflinePrivacyEngine (ObscuraCam Core, 100% On-Device):
 * - Runs bundled ML Kit Face Detection (with head roll angle) & Text Recognition strictly offline.
 * - Normalizes all regions into 0..1 coordinates for responsive Compose touch editing.
 * - Supports BLACKOUT, PIXELATE, BLUR, and stylized full-face MASK presets
 *   (Clown, Wolf, Fox, Cat, Bear, Ghost Skull, Custom Transparent PNG) with adjustable scale.
 */
object OfflinePrivacyEngine {

    enum class RedactionStyle(val labelRu: String) {
        BLACKOUT("ЗАЛИВКА"),
        PIXELATE("МОЗАИКА"),
        BLUR("РАЗМЫТИЕ"),
        MASK("МАСКА")
    }

    enum class MaskPreset(val labelRu: String, val shortBadge: String) {
        CLOWN("🤡 КЛОУН", "🤡"),
        WOLF("🐺 ВОЛК", "🐺"),
        FOX("🦊 ЛИСА", "🦊"),
        CAT("🐱 КОТ", "🐱"),
        BEAR("🐻 МЕДВЕДЬ", "🐻"),
        SKULL("💀 ЧЕРЕП", "💀"),
        CUSTOM_PNG("🖼 СВОЯ КАРТИНКА", "🖼")
    }

    enum class RegionKind {
        FACE,
        TEXT,
        MANUAL
    }

    data class NormalizedRegion(
        val id: String,
        val rect: RectF, // normalized [0f..1f]
        val kind: RegionKind,
        val rollDeg: Float = 0f
    )

    data class DetectionResult(
        val faces: List<NormalizedRegion>,
        val textBlocks: List<NormalizedRegion>,
        val extractedText: String
    )

    data class OsintTextHit(
        val region: NormalizedRegion,
        val matchedLabels: List<String>,
        val riskWeight: Int
    )

    fun moveRegionClamped(rect: RectF, dxNorm: Float, dyNorm: Float): RectF {
        val w = absRectWidth(rect).coerceIn(0.02f, 1f)
        val h = absRectHeight(rect).coerceIn(0.02f, 1f)
        val left0 = min(rect.left, rect.right)
        val top0 = min(rect.top, rect.bottom)
        val newLeft = (left0 + dxNorm).coerceIn(0f, 1f - w)
        val newTop = (top0 + dyNorm).coerceIn(0f, 1f - h)
        return RectF(newLeft, newTop, newLeft + w, newTop + h)
    }

    /**
     * Computes a centered, clamped rectangle scaled by [scale] (for live Mask size adjustments).
     */
    fun computeScaledMaskBounds(rect: Rect, scale: Float, maxW: Int, maxH: Int): RectF {
        val clampedScale = scale.coerceIn(0.6f, 2.2f)
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val halfW = (rect.width() * 0.5f) * clampedScale
        val halfH = (rect.height() * 0.5f) * clampedScale
        return RectF(
            (cx - halfW).coerceIn(-maxW * 0.15f, maxW.toFloat()),
            (cy - halfH).coerceIn(-maxH * 0.15f, maxH.toFloat()),
            (cx + halfW).coerceIn(0f, maxW * 1.15f),
            (cy + halfH).coerceIn(0f, maxH * 1.15f)
        )
    }

    /**
     * Returns a lightweight preview bitmap bounded by [maxDim] so live slider drags & box edits
     * render smoothly without stalling on multi-megapixel camera bitmaps.
     */
    fun createPreviewBitmap(source: Bitmap, maxDim: Int = 1080): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxDim && h <= maxDim) {
            return if (source.config == Bitmap.Config.ARGB_8888) {
                source
            } else {
                source.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
        val scale = maxDim.toFloat() / max(w, h).toFloat()
        val pw = (w * scale).roundToInt().coerceAtLeast(1)
        val ph = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, pw, ph, true)
    }

    /**
     * Computes Intersection-over-Union (0f..1f) between two normalized rectangles.
     */
    fun computeRectIoU(a: RectF, b: RectF): Float {
        val al = min(a.left, a.right)
        val at = min(a.top, a.bottom)
        val ar = max(a.left, a.right)
        val ab = max(a.top, a.bottom)
        val bl = min(b.left, b.right)
        val bt = min(b.top, b.bottom)
        val br = max(b.left, b.right)
        val bb = max(b.top, b.bottom)

        val interW = (min(ar, br) - max(al, bl)).coerceAtLeast(0f)
        val interH = (min(ab, bb) - max(at, bt)).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f
        val unionArea = ((ar - al) * (ab - at)) + ((br - bl) * (bb - bt)) - interArea
        return if (unionArea <= 0f) 0f else (interArea / unionArea).coerceIn(0f, 1f)
    }

    /**
     * Merges [incoming] detected regions into [existing] without duplicating overlapping zones
     * or reusing colliding sequential IDs ("face_0", "txt_0").
     */
    fun mergeUniqueRegions(
        existing: List<NormalizedRegion>,
        incoming: List<NormalizedRegion>,
        iouThreshold: Float = 0.55f
    ): List<NormalizedRegion> {
        if (incoming.isEmpty()) return existing
        val result = existing.toMutableList()
        val usedIds = existing.map { it.id }.toMutableSet()
        var seq = 1
        for (cand in incoming) {
            val isDuplicate = result.any { prev ->
                prev.kind == cand.kind && computeRectIoU(prev.rect, cand.rect) >= iouThreshold
            }
            if (isDuplicate) continue
            var uniqueId = cand.id
            while (uniqueId in usedIds) {
                uniqueId = "${cand.id}_$seq"
                seq++
            }
            usedIds.add(uniqueId)
            result.add(cand.copy(id = uniqueId))
        }
        return result
    }

    private fun absRectWidth(r: RectF): Float = max(r.left, r.right) - min(r.left, r.right)
    private fun absRectHeight(r: RectF): Float = max(r.top, r.bottom) - min(r.top, r.bottom)

    suspend fun detectFacesOffline(bitmap: Bitmap): List<NormalizedRegion> =
        suspendCancellableCoroutine { cont ->
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
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
                            kind = RegionKind.FACE,
                            rollDeg = f.headEulerAngleZ
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

    suspend fun detectTextWithContentOffline(bitmap: Bitmap): Pair<List<Pair<NormalizedRegion, String>>, String> =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val input = InputImage.fromBitmap(bitmap, 0)
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)

            recognizer.process(input)
                .addOnSuccessListener { visionText ->
                    val entries = visionText.textBlocks.mapIndexedNotNull { idx, block ->
                        val b = block.boundingBox ?: return@mapIndexedNotNull null
                        val region = NormalizedRegion(
                            id = "txt_$idx",
                            rect = RectF(
                                (b.left / w).coerceIn(0f, 1f),
                                (b.top / h).coerceIn(0f, 1f),
                                (b.right / w).coerceIn(0f, 1f),
                                (b.bottom / h).coerceIn(0f, 1f)
                            ),
                            kind = RegionKind.TEXT
                        )
                        region to block.text
                    }
                    recognizer.close()
                    if (cont.isActive) cont.resume(Pair(entries, visionText.text))
                }
                .addOnFailureListener {
                    recognizer.close()
                    if (cont.isActive) cont.resume(Pair(emptyList(), ""))
                }
        }

    suspend fun detectTextOffline(bitmap: Bitmap): Pair<List<NormalizedRegion>, String> {
        val (entries, raw) = detectTextWithContentOffline(bitmap)
        return Pair(entries.map { it.first }, raw)
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

    fun classifySensitiveText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val matched = mutableListOf<String>()
        if (SECRET_KEY_REGEX.containsMatchIn(text)) matched.add("Секрет/пароль")
        val hasValidCard = CARD_CANDIDATE_REGEX.findAll(text).any { m ->
            passesLuhn(m.value.filter { it.isDigit() })
        }
        if (hasValidCard) matched.add("Карта (Luhn)")
        if (PLATE_REGEX.containsMatchIn(text)) matched.add("Госномер")
        if (PHONE_REGEX.containsMatchIn(text)) matched.add("Телефон")
        if (COORD_REGEX.containsMatchIn(text)) matched.add("Координаты")
        if (EMAIL_REGEX.containsMatchIn(text)) matched.add("E-mail")
        return matched
    }

    fun analyzeSensitiveOsintBlocksFromEntries(
        entries: List<Pair<NormalizedRegion, String>>
    ): List<OsintTextHit> {
        return entries.mapNotNull { (region, text) ->
            val labels = classifySensitiveText(text)
            if (labels.isEmpty()) {
                null
            } else {
                val weight = labels.sumOf { label ->
                    when (label) {
                        "Секрет/пароль" -> 45
                        "Карта (Luhn)" -> 40
                        "Госномер" -> 35
                        "Телефон" -> 25
                        else -> 20
                    }
                }.coerceAtMost(100)
                OsintTextHit(
                    region = region,
                    matchedLabels = labels,
                    riskWeight = weight
                )
            }
        }
    }

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
     * When [style] == [RedactionStyle.MASK], renders stylized full-face animal/clown/skull masks
     * (or user-imported transparent PNG) scaled by [maskScale] and rotated by detected head roll.
     */
    fun applyRedactions(
        source: Bitmap,
        regions: List<NormalizedRegion>,
        style: RedactionStyle,
        invertMode: Boolean,
        maskPreset: MaskPreset = MaskPreset.CLOWN,
        maskScale: Float = 1.15f,
        customMaskBitmap: Bitmap? = null
    ): Bitmap {
        val w = source.width
        val h = source.height
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        if (regions.isEmpty()) return out

        val pixelRegions = regions.mapNotNull { r ->
            val left = (min(r.rect.left, r.rect.right) * w).toInt().coerceIn(0, w - 1)
            val top = (min(r.rect.top, r.rect.bottom) * h).toInt().coerceIn(0, h - 1)
            val right = (max(r.rect.left, r.rect.right) * w).toInt().coerceIn(1, w)
            val bottom = (max(r.rect.top, r.rect.bottom) * h).toInt().coerceIn(1, h)
            if (right - left >= 4 && bottom - top >= 4) Pair(Rect(left, top, right, bottom), r.rollDeg) else null
        }
        if (pixelRegions.isEmpty()) return out

        val canvas = Canvas(out)

        if (invertMode) {
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
            for ((rect, _) in pixelRegions) {
                canvas.drawBitmap(source, rect, rect, null)
            }
            return out
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((rect, rollDeg) in pixelRegions) {
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
                    val scaledBounds = computeScaledMaskBounds(rect, maskScale, w, h)
                    drawStylizedMask(
                        canvas = canvas,
                        bounds = scaledBounds,
                        rollDeg = rollDeg,
                        preset = maskPreset,
                        customMaskBitmap = customMaskBitmap
                    )
                }
            }
        }
        return out
    }

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

    private fun drawStylizedMask(
        canvas: Canvas,
        bounds: RectF,
        rollDeg: Float,
        preset: MaskPreset,
        customMaskBitmap: Bitmap?
    ) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val w = bounds.width().coerceAtLeast(16f)
        val h = bounds.height().coerceAtLeast(16f)

        canvas.save()
        if (kotlin.math.abs(rollDeg) in 1f..45f) {
            canvas.rotate(-rollDeg, cx, cy)
        }

        if (preset == MaskPreset.CUSTOM_PNG && customMaskBitmap != null && !customMaskBitmap.isRecycled) {
            val bmpW = customMaskBitmap.width.toFloat().coerceAtLeast(1f)
            val bmpH = customMaskBitmap.height.toFloat().coerceAtLeast(1f)
            val aspect = bmpW / bmpH
            val dst = if (w / h > aspect) {
                val drawW = h * aspect
                RectF(cx - drawW * 0.5f, bounds.top, cx + drawW * 0.5f, bounds.bottom)
            } else {
                val drawH = w / aspect
                RectF(bounds.left, cy - drawH * 0.5f, bounds.right, cy + drawH * 0.5f)
            }
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(customMaskBitmap, null, dst, bmpPaint)
            canvas.restore()
            return
        }

        val effectivePreset = if (preset == MaskPreset.CUSTOM_PNG) MaskPreset.CLOWN else preset
        when (effectivePreset) {
            MaskPreset.CLOWN -> drawClownMask(canvas, cx, cy, w, h)
            MaskPreset.WOLF -> drawWolfMask(canvas, cx, cy, w, h)
            MaskPreset.FOX -> drawFoxMask(canvas, cx, cy, w, h)
            MaskPreset.CAT -> drawCatMask(canvas, cx, cy, w, h)
            MaskPreset.BEAR -> drawBearMask(canvas, cx, cy, w, h)
            MaskPreset.SKULL -> drawSkullMask(canvas, cx, cy, w, h)
            MaskPreset.CUSTOM_PNG -> drawClownMask(canvas, cx, cy, w, h)
        }
        canvas.restore()
    }

    private fun drawClownMask(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        // Colorful curly side wigs
        val puffColors = intArrayOf(
            Color.rgb(255, 61, 0),
            Color.rgb(255, 196, 0),
            Color.rgb(0, 176, 255),
            Color.rgb(170, 0, 255)
        )
        val puffR = w * 0.14f
        val leftX = cx - w * 0.44f
        val rightX = cx + w * 0.44f
        for (i in 0 until 4) {
            fill.color = puffColors[i % puffColors.size]
            val py = cy - h * 0.28f + i * (h * 0.13f)
            canvas.drawCircle(leftX - (if (i % 2 == 0) w * 0.04f else 0f), py, puffR, fill)
            canvas.drawCircle(rightX + (if (i % 2 == 0) w * 0.04f else 0f), py, puffR, fill)
        }

        // White clown face base covering full identity
        val faceOval = RectF(cx - w * 0.46f, cy - h * 0.46f, cx + w * 0.46f, cy + h * 0.48f)
        fill.color = Color.rgb(248, 249, 252)
        canvas.drawOval(faceOval, fill)
        stroke.color = Color.rgb(30, 34, 42)
        stroke.strokeWidth = max(2f, w * 0.025f)
        canvas.drawOval(faceOval, stroke)

        // Rosy cheeks
        fill.color = Color.argb(170, 255, 82, 120)
        canvas.drawCircle(cx - w * 0.27f, cy + h * 0.06f, w * 0.10f, fill)
        canvas.drawCircle(cx + w * 0.27f, cy + h * 0.06f, w * 0.10f, fill)

        // Arched blue eyebrows
        stroke.color = Color.rgb(25, 118, 210)
        stroke.strokeWidth = max(3f, w * 0.045f)
        val browW = w * 0.16f
        canvas.drawArc(
            RectF(cx - w * 0.21f - browW * 0.5f, cy - h * 0.30f, cx - w * 0.21f + browW * 0.5f, cy - h * 0.18f),
            195f, 150f, false, stroke
        )
        canvas.drawArc(
            RectF(cx + w * 0.21f - browW * 0.5f, cy - h * 0.30f, cx + w * 0.21f + browW * 0.5f, cy - h * 0.18f),
            195f, 150f, false, stroke
        )

        // Painted diamond eye accents + dark eyes
        val eyeY = cy - h * 0.11f
        for (dir in intArrayOf(-1, 1)) {
            val ex = cx + dir * w * 0.19f
            val diamond = Path().apply {
                moveTo(ex, eyeY - h * 0.11f)
                lineTo(ex + w * 0.065f, eyeY)
                lineTo(ex, eyeY + h * 0.10f)
                lineTo(ex - w * 0.065f, eyeY)
                close()
            }
            fill.color = Color.rgb(0, 176, 255)
            canvas.drawPath(diamond, fill)
            fill.color = Color.rgb(18, 22, 28)
            canvas.drawCircle(ex, eyeY, w * 0.048f, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(ex - w * 0.015f, eyeY - h * 0.015f, w * 0.016f, fill)
        }

        // Giant red clown mouth
        val smileRect = RectF(cx - w * 0.28f, cy + h * 0.06f, cx + w * 0.28f, cy + h * 0.36f)
        fill.color = Color.rgb(229, 28, 35)
        canvas.drawArc(smileRect, 10f, 160f, true, fill)
        fill.color = Color.WHITE
        val teethRect = RectF(cx - w * 0.20f, cy + h * 0.14f, cx + w * 0.20f, cy + h * 0.26f)
        canvas.drawArc(teethRect, 12f, 156f, true, fill)

        // Shiny round red nose
        val noseY = cy + h * 0.01f
        val noseR = min(w, h) * 0.135f
        fill.color = Color.rgb(255, 23, 68)
        canvas.drawCircle(cx, noseY, noseR, fill)
        stroke.color = Color.rgb(150, 0, 28)
        stroke.strokeWidth = max(2f, w * 0.02f)
        canvas.drawCircle(cx, noseY, noseR, stroke)
        fill.color = Color.argb(210, 255, 255, 255)
        canvas.drawCircle(cx - noseR * 0.32f, noseY - noseR * 0.32f, noseR * 0.24f, fill)
    }

    private fun drawWolfMask(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

        // Sharp upright wolf ears
        for (dir in intArrayOf(-1, 1)) {
            val outerEar = Path().apply {
                moveTo(cx + dir * w * 0.16f, cy - h * 0.32f)
                lineTo(cx + dir * w * 0.44f, cy - h * 0.60f)
                lineTo(cx + dir * w * 0.46f, cy - h * 0.16f)
                close()
            }
            fill.color = Color.rgb(69, 80, 92)
            canvas.drawPath(outerEar, fill)

            val innerEar = Path().apply {
                moveTo(cx + dir * w * 0.22f, cy - h * 0.31f)
                lineTo(cx + dir * w * 0.39f, cy - h * 0.50f)
                lineTo(cx + dir * w * 0.40f, cy - h * 0.22f)
                close()
            }
            fill.color = Color.rgb(168, 125, 134)
            canvas.drawPath(innerEar, fill)
        }

        // Wolf head fur silhouette with jagged cheek tufts
        val headPath = Path().apply {
            moveTo(cx, cy - h * 0.44f)
            lineTo(cx + w * 0.36f, cy - h * 0.32f)
            lineTo(cx + w * 0.52f, cy - h * 0.02f)
            lineTo(cx + w * 0.42f, cy + h * 0.08f)
            lineTo(cx + w * 0.48f, cy + h * 0.22f)
            lineTo(cx + w * 0.25f, cy + h * 0.42f)
            lineTo(cx, cy + h * 0.50f)
            lineTo(cx - w * 0.25f, cy + h * 0.42f)
            lineTo(cx - w * 0.48f, cy + h * 0.22f)
            lineTo(cx - w * 0.42f, cy + h * 0.08f)
            lineTo(cx - w * 0.52f, cy - h * 0.02f)
            lineTo(cx - w * 0.36f, cy - h * 0.32f)
            close()
        }
        fill.color = Color.rgb(84, 96, 110)
        canvas.drawPath(headPath, fill)

        // Silver/white lower cheek fur & snout
        val snout = Path().apply {
            moveTo(cx, cy - h * 0.04f)
            lineTo(cx + w * 0.38f, cy + h * 0.14f)
            lineTo(cx + w * 0.20f, cy + h * 0.40f)
            lineTo(cx, cy + h * 0.48f)
            lineTo(cx - w * 0.20f, cy + h * 0.40f)
            lineTo(cx - w * 0.38f, cy + h * 0.14f)
            close()
        }
        fill.color = Color.rgb(218, 224, 232)
        canvas.drawPath(snout, fill)

        // Dark fur brow ridge
        val brow = Path().apply {
            moveTo(cx - w * 0.32f, cy - h * 0.18f)
            lineTo(cx, cy - h * 0.04f)
            lineTo(cx + w * 0.32f, cy - h * 0.18f)
            lineTo(cx + w * 0.18f, cy - h * 0.28f)
            lineTo(cx, cy - h * 0.18f)
            lineTo(cx - w * 0.18f, cy - h * 0.28f)
            close()
        }
        fill.color = Color.rgb(44, 52, 62)
        canvas.drawPath(brow, fill)

        // Fierce amber eyes
        for (dir in intArrayOf(-1, 1)) {
            val eyePath = Path().apply {
                moveTo(cx + dir * w * 0.09f, cy - h * 0.06f)
                lineTo(cx + dir * w * 0.27f, cy - h * 0.15f)
                lineTo(cx + dir * w * 0.23f, cy - h * 0.03f)
                close()
            }
            fill.color = Color.rgb(255, 193, 7)
            canvas.drawPath(eyePath, fill)
            fill.color = Color.BLACK
            canvas.drawCircle(cx + dir * w * 0.19f, cy - h * 0.08f, w * 0.025f, fill)
        }

        // Wolf black nose
        val nose = Path().apply {
            moveTo(cx - w * 0.11f, cy + h * 0.14f)
            lineTo(cx + w * 0.11f, cy + h * 0.14f)
            lineTo(cx, cy + h * 0.26f)
            close()
        }
        fill.color = Color.rgb(20, 22, 26)
        canvas.drawPath(nose, fill)

        // Fang grin line
        stroke.color = Color.rgb(30, 34, 40)
        stroke.strokeWidth = max(2f, w * 0.025f)
        canvas.drawLine(cx, cy + h * 0.26f, cx, cy + h * 0.35f, stroke)
        canvas.drawLine(cx - w * 0.14f, cy + h * 0.34f, cx + w * 0.14f, cy + h * 0.34f, stroke)
    }

    private fun drawFoxMask(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        // Pointed fox ears with dark tips
        for (dir in intArrayOf(-1, 1)) {
            val ear = Path().apply {
                moveTo(cx + dir * w * 0.14f, cy - h * 0.30f)
                lineTo(cx + dir * w * 0.44f, cy - h * 0.58f)
                lineTo(cx + dir * w * 0.45f, cy - h * 0.12f)
                close()
            }
            fill.color = Color.rgb(245, 108, 18)
            canvas.drawPath(ear, fill)

            val tip = Path().apply {
                moveTo(cx + dir * w * 0.32f, cy - h * 0.46f)
                lineTo(cx + dir * w * 0.44f, cy - h * 0.58f)
                lineTo(cx + dir * w * 0.44f, cy - h * 0.39f)
                close()
            }
            fill.color = Color.rgb(35, 32, 34)
            canvas.drawPath(tip, fill)

            val inner = Path().apply {
                moveTo(cx + dir * w * 0.21f, cy - h * 0.29f)
                lineTo(cx + dir * w * 0.37f, cy - h * 0.46f)
                lineTo(cx + dir * w * 0.38f, cy - h * 0.19f)
                close()
            }
            fill.color = Color.rgb(255, 240, 224)
            canvas.drawPath(inner, fill)
        }

        // Fox head base
        val head = Path().apply {
            moveTo(cx, cy - h * 0.44f)
            lineTo(cx + w * 0.38f, cy - h * 0.28f)
            lineTo(cx + w * 0.52f, cy + h * 0.04f)
            lineTo(cx + w * 0.22f, cy + h * 0.38f)
            lineTo(cx, cy + h * 0.48f)
            lineTo(cx - w * 0.22f, cy + h * 0.38f)
            lineTo(cx - w * 0.52f, cy + h * 0.04f)
            lineTo(cx - w * 0.38f, cy - h * 0.28f)
            close()
        }
        fill.color = Color.rgb(255, 118, 22)
        canvas.drawPath(head, fill)

        // Creamy white cheek halves
        for (dir in intArrayOf(-1, 1)) {
            val cheek = Path().apply {
                moveTo(cx, cy + h * 0.08f)
                lineTo(cx + dir * w * 0.49f, cy + h * 0.05f)
                lineTo(cx + dir * w * 0.22f, cy + h * 0.38f)
                lineTo(cx, cy + h * 0.48f)
                close()
            }
            fill.color = Color.rgb(255, 247, 238)
            canvas.drawPath(cheek, fill)
        }

        // Sly fox eyes
        for (dir in intArrayOf(-1, 1)) {
            fill.color = Color.rgb(24, 24, 28)
            canvas.drawOval(
                RectF(cx + dir * w * 0.18f - w * 0.07f, cy - h * 0.12f, cx + dir * w * 0.18f + w * 0.07f, cy - h * 0.02f),
                fill
            )
            fill.color = Color.rgb(255, 214, 0)
            canvas.drawCircle(cx + dir * w * 0.18f, cy - h * 0.07f, w * 0.025f, fill)
        }

        // Shiny black fox nose
        fill.color = Color.rgb(22, 22, 26)
        canvas.drawOval(RectF(cx - w * 0.085f, cy + h * 0.20f, cx + w * 0.085f, cy + h * 0.32f), fill)

        // Whiskers
        stroke.color = Color.rgb(65, 55, 50)
        stroke.strokeWidth = max(1.5f, w * 0.016f)
        for (dir in intArrayOf(-1, 1)) {
            canvas.drawLine(cx + dir * w * 0.20f, cy + h * 0.18f, cx + dir * w * 0.44f, cy + h * 0.14f, stroke)
            canvas.drawLine(cx + dir * w * 0.20f, cy + h * 0.24f, cx + dir * w * 0.44f, cy + h * 0.26f, stroke)
        }
    }

    private fun drawCatMask(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        // Cat ears
        for (dir in intArrayOf(-1, 1)) {
            val ear = Path().apply {
                moveTo(cx + dir * w * 0.16f, cy - h * 0.34f)
                lineTo(cx + dir * w * 0.44f, cy - h * 0.56f)
                lineTo(cx + dir * w * 0.45f, cy - h * 0.12f)
                close()
            }
            fill.color = Color.rgb(52, 56, 66)
            canvas.drawPath(ear, fill)

            val inner = Path().apply {
                moveTo(cx + dir * w * 0.22f, cy - h * 0.32f)
                lineTo(cx + dir * w * 0.38f, cy - h * 0.46f)
                lineTo(cx + dir * w * 0.39f, cy - h * 0.18f)
                close()
            }
            fill.color = Color.rgb(255, 148, 178)
            canvas.drawPath(inner, fill)
        }

        // Rounded cat face
        val headRect = RectF(cx - w * 0.47f, cy - h * 0.42f, cx + w * 0.47f, cy + h * 0.44f)
        fill.color = Color.rgb(58, 62, 74)
        canvas.drawOval(headRect, fill)

        // Cream muzzle pads
        fill.color = Color.rgb(245, 240, 235)
        canvas.drawCircle(cx - w * 0.08f, cy + h * 0.14f, w * 0.11f, fill)
        canvas.drawCircle(cx + w * 0.08f, cy + h * 0.14f, w * 0.11f, fill)

        // Emerald eyes + vertical slit pupils
        for (dir in intArrayOf(-1, 1)) {
            val ex = cx + dir * w * 0.20f
            val ey = cy - h * 0.08f
            fill.color = Color.rgb(0, 230, 118)
            canvas.drawOval(RectF(ex - w * 0.085f, ey - h * 0.065f, ex + w * 0.085f, ey + h * 0.065f), fill)
            fill.color = Color.BLACK
            canvas.drawOval(RectF(ex - w * 0.022f, ey - h * 0.055f, ex + w * 0.022f, ey + h * 0.055f), fill)
            fill.color = Color.WHITE
            canvas.drawCircle(ex - w * 0.02f, ey - h * 0.02f, w * 0.015f, fill)
        }

        // Pink triangle nose
        val nose = Path().apply {
            moveTo(cx - w * 0.065f, cy + h * 0.05f)
            lineTo(cx + w * 0.065f, cy + h * 0.05f)
            lineTo(cx, cy + h * 0.13f)
            close()
        }
        fill.color = Color.rgb(255, 110, 155)
        canvas.drawPath(nose, fill)

        // Cute cat "w" mouth + whiskers
        stroke.color = Color.rgb(30, 32, 38)
        stroke.strokeWidth = max(2f, w * 0.022f)
        canvas.drawArc(RectF(cx - w * 0.14f, cy + h * 0.12f, cx, cy + h * 0.22f), 0f, 180f, false, stroke)
        canvas.drawArc(RectF(cx, cy + h * 0.12f, cx + w * 0.14f, cy + h * 0.22f), 0f, 180f, false, stroke)

        stroke.color = Color.rgb(235, 240, 245)
        stroke.strokeWidth = max(1.5f, w * 0.015f)
        for (dir in intArrayOf(-1, 1)) {
            canvas.drawLine(cx + dir * w * 0.18f, cy + h * 0.10f, cx + dir * w * 0.45f, cy + h * 0.04f, stroke)
            canvas.drawLine(cx + dir * w * 0.19f, cy + h * 0.15f, cx + dir * w * 0.47f, cy + h * 0.16f, stroke)
            canvas.drawLine(cx + dir * w * 0.18f, cy + h * 0.20f, cx + dir * w * 0.44f, cy + h * 0.27f, stroke)
        }
    }

    private fun drawBearMask(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        // Round bear ears
        val earR = w * 0.17f
        for (dir in intArrayOf(-1, 1)) {
            val ex = cx + dir * w * 0.34f
            val ey = cy - h * 0.36f
            fill.color = Color.rgb(109, 76, 65)
            canvas.drawCircle(ex, ey, earR, fill)
            fill.color = Color.rgb(215, 204, 200)
            canvas.drawCircle(ex, ey, earR * 0.56f, fill)
        }

        // Bear face
        fill.color = Color.rgb(121, 85, 72)
        canvas.drawOval(RectF(cx - w * 0.46f, cy - h * 0.44f, cx + w * 0.46f, cy + h * 0.46f), fill)

        // Tan muzzle
        fill.color = Color.rgb(225, 198, 174)
        canvas.drawOval(RectF(cx - w * 0.25f, cy - h * 0.01f, cx + w * 0.25f, cy + h * 0.38f), fill)

        // Bear eyes + brows
        for (dir in intArrayOf(-1, 1)) {
            val ex = cx + dir * w * 0.19f
            val ey = cy - h * 0.12f
            fill.color = Color.rgb(28, 22, 20)
            canvas.drawCircle(ex, ey, w * 0.055f, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(ex - w * 0.016f, ey - h * 0.016f, w * 0.016f, fill)
        }

        // Big dark bear nose
        fill.color = Color.rgb(34, 26, 24)
        canvas.drawOval(RectF(cx - w * 0.11f, cy + h * 0.04f, cx + w * 0.11f, cy + h * 0.18f), fill)

        // Smile
        stroke.color = Color.rgb(42, 30, 26)
        stroke.strokeWidth = max(2.5f, w * 0.026f)
        canvas.drawLine(cx, cy + h * 0.18f, cx, cy + h * 0.26f, stroke)
        canvas.drawArc(RectF(cx - w * 0.15f, cy + h * 0.18f, cx + w * 0.15f, cy + h * 0.30f), 15f, 150f, false, stroke)
    }

    private fun drawSkullMask(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        // Dark tactical balaclava base
        fill.color = Color.rgb(20, 24, 30)
        canvas.drawOval(RectF(cx - w * 0.48f, cy - h * 0.48f, cx + w * 0.48f, cy + h * 0.50f), fill)

        // Bone skull cranium + jaw
        val skull = Path().apply {
            addOval(RectF(cx - w * 0.42f, cy - h * 0.42f, cx + w * 0.42f, cy + h * 0.20f), Path.Direction.CW)
        }
        fill.color = Color.rgb(232, 232, 224)
        canvas.drawPath(skull, fill)
        val jawRect = RectF(cx - w * 0.26f, cy + h * 0.02f, cx + w * 0.26f, cy + h * 0.44f)
        canvas.drawRoundRect(jawRect, w * 0.09f, w * 0.09f, fill)

        // Hollow eye sockets + subtle neon glint
        for (dir in intArrayOf(-1, 1)) {
            val ex = cx + dir * w * 0.18f
            val ey = cy - h * 0.08f
            fill.color = Color.rgb(18, 20, 24)
            canvas.drawOval(RectF(ex - w * 0.11f, ey - h * 0.10f, ex + w * 0.11f, ey + h * 0.10f), fill)
            fill.color = Color.rgb(0, 255, 102)
            canvas.drawCircle(ex, ey, w * 0.026f, fill)
        }

        // Nasal triangle cavity
        val nose = Path().apply {
            moveTo(cx, cy + h * 0.02f)
            lineTo(cx + w * 0.065f, cy + h * 0.16f)
            lineTo(cx - w * 0.065f, cy + h * 0.16f)
            close()
        }
        fill.color = Color.rgb(18, 20, 24)
        canvas.drawPath(nose, fill)

        // Stitched teeth bars
        stroke.color = Color.rgb(22, 24, 28)
        stroke.strokeWidth = max(2.5f, w * 0.025f)
        val teethY = cy + h * 0.29f
        canvas.drawLine(cx - w * 0.19f, teethY, cx + w * 0.19f, teethY, stroke)
        for (i in -2..2) {
            val tx = cx + i * w * 0.075f
            canvas.drawLine(tx, teethY - h * 0.06f, tx, teethY + h * 0.07f, stroke)
        }
    }
}
