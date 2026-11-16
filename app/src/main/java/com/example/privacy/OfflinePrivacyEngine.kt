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
                    val rw = rect.width()
                    val rh = rect.height()
                    val crop = Bitmap.createBitmap(source, rect.left, rect.top, rw, rh)
                    val downW = max(3, rw / max(10, rw / 12))
                    val downH = max(3, rh / max(10, rh / 12))
                    val tiny = Bitmap.createScaledBitmap(crop, downW, downH, false)
                    val mosaic = Bitmap.createScaledBitmap(tiny, rw, rh, false)
                    canvas.drawBitmap(mosaic, rect.left.toFloat(), rect.top.toFloat(), null)
                    crop.recycle()
                    tiny.recycle()
                    mosaic.recycle()
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
