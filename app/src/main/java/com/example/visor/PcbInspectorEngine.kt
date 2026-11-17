package com.example.visor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PcbInspectorEngine (P3):
 * Offline Hardware / PCB Laboratory for Redmi 13C (MT6769Z):
 * 1. Optical CV PCB & Solder Joint Analyzer:
 *    - Segments solder mask, metallic tin/gold pads & copper traces.
 *    - Detects suspected solder bridges (merged bright pad blobs), dull/cold joint regions,
 *      and dark carbonized/burnt hotspots with annotated visual overlay.
 * 2. Offline SMD & Component Decoder:
 *    - 3-digit / 4-digit EIA SMD resistors, R/K/M-notation, 96-entry EIA-96 (1%),
 *      MLCC capacitor codes, and 4-band color resistor calculation.
 * 3. Offline ML Kit TextRecognition v2 silkscreen & IC marking scanner (0% cloud).
 */
object PcbInspectorEngine {

    enum class DefectKind(val labelRu: String, val badgeColorHex: Long) {
        SOLDER_BRIDGE("МОСТИК ПРИПОЯ", 0xFFFF1744),
        COLD_JOINT("ТУСКЛАЯ ПАЙКА", 0xFFFFEA00),
        BURN_MARK("ПРОГАР / НАГАР", 0xFFFF6D00)
    }

    data class PcbHotspot(
        val kind: DefectKind,
        val rectNorm: RectF,
        val confidencePct: Int,
        val detailRu: String
    )

    data class PcbInspectionReport(
        val boardHealthScore: Int,
        val solderMaskPct: Int,
        val padCoveragePct: Int,
        val bridgeCandidates: Int,
        val coldJointCandidates: Int,
        val burnSpots: Int,
        val hotspots: List<PcbHotspot>,
        val annotatedBitmap: Bitmap,
        val summaryRu: String
    )

    data class SmdDecodeResult(
        val code: String,
        val componentTypeRu: String,
        val primaryValueRu: String,
        val secondaryInterpretationRu: String? = null,
        val tolerance: String = "±5%",
        val valid: Boolean = true
    )

    data class SilkscreenScanResult(
        val rawText: String,
        val designators: List<String>,
        val icCandidates: List<String>,
        val decodedSmdCodes: List<SmdDecodeResult>
    )

    enum class ColorBand(val labelRu: String, val digit: Int?, val multiplier: Double?, val tolerance: String?) {
        BLACK("Чёрный", 0, 1.0, null),
        BROWN("Коричневый", 1, 10.0, "±1%"),
        RED("Красный", 2, 100.0, "±2%"),
        ORANGE("Оранжевый", 3, 1_000.0, null),
        YELLOW("Жёлтый", 4, 10_000.0, null),
        GREEN("Зелёный", 5, 100_000.0, "±0.5%"),
        BLUE("Синий", 6, 1_000_000.0, "±0.25%"),
        VIOLET("Фиолетовый", 7, 10_000_000.0, "±0.1%"),
        GREY("Серый", 8, null, "±0.05%"),
        WHITE("Белый", 9, null, null),
        GOLD("Золотой", null, 0.1, "±5%"),
        SILVER("Серебряный", null, 0.01, "±10%")
    }

    // Complete 96-value EIA-96 1% SMD resistor lookup table (codes 01..96)
    private val EIA96_BASE = intArrayOf(
        100, 102, 105, 107, 110, 113, 115, 118, 121, 124, 127, 130,
        133, 137, 140, 143, 147, 150, 154, 158, 162, 165, 169, 174,
        178, 182, 187, 191, 196, 200, 205, 210, 215, 221, 226, 232,
        237, 243, 249, 255, 261, 267, 274, 280, 287, 294, 301, 309,
        316, 324, 332, 340, 348, 357, 365, 374, 383, 392, 402, 412,
        422, 432, 442, 453, 464, 475, 487, 499, 511, 523, 536, 549,
        562, 576, 590, 604, 619, 634, 649, 665, 681, 698, 715, 732,
        750, 768, 787, 806, 825, 845, 866, 887, 909, 931, 953, 976
    )

    private val EIA96_MULT = mapOf(
        'Z' to 0.001, 'Y' to 0.01, 'R' to 0.01, 'X' to 0.1, 'S' to 0.1,
        'A' to 1.0, 'B' to 10.0, 'H' to 10.0, 'C' to 100.0,
        'D' to 1_000.0, 'E' to 10_000.0, 'F' to 100_000.0
    )

    /**
     * Pure offline CV inspection of a PCB / electronic circuit image.
     * Note: optical heuristics highlight visual anomalies; confirm continuity with a multimeter.
     */
    fun inspectBoard(source: Bitmap): PcbInspectionReport {
        val maxSide = max(source.width, source.height)
        val scale = if (maxSide > 320) 320f / maxSide.toFloat() else 1f
        val w = max(32, (source.width * scale).toInt())
        val h = max(32, (source.height * scale).toInt())
        val work = if (w == source.width && h == source.height) {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            Bitmap.createScaledBitmap(source, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        }

        val pixels = IntArray(w * h)
        work.getPixels(pixels, 0, w, 0, 0, w, h)

        val isMask = BooleanArray(w * h)
        val isSolderPad = BooleanArray(w * h)
        val isBurnCandidate = BooleanArray(w * h)
        val luma = IntArray(w * h)

        var maskCount = 0
        var padCount = 0

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 77 + g * 150 + b * 29) ushr 8
            luma[i] = y

            val maxCh = max(r, max(g, b))
            val minCh = min(r, min(g, b))
            val chroma = maxCh - minCh

            val greenMask = g > r + 14 && g > b + 12 && y in 25..195
            val blueMask = b > r + 16 && b > g + 6 && y in 25..195
            val redMask = r > g + 22 && r > b + 22 && y in 30..190
            val maskHit = greenMask || blueMask || redMask
            isMask[i] = maskHit
            if (maskHit) maskCount++

            // Metallic solder/tin highlight (low saturation + bright) or gold ENIG pad
            val silverPad = chroma < 34 && y >= 158
            val goldPad = r >= 148 && g >= 112 && b <= 96 && (r - b) >= 40
            if (silverPad || goldPad) {
                isSolderPad[i] = true
                padCount++
            } else if (y < 42 && !maskHit && r >= b && (r - b) <= 32) {
                isBurnCandidate[i] = true
            }
        }

        // Grid-level anomaly scan (16x16 blocks across the working image)
        val gridCols = 16
        val gridRows = 16
        val cellW = max(2, w / gridCols)
        val cellH = max(2, h / gridRows)

        val hotspots = mutableListOf<PcbHotspot>()
        var bridgeCount = 0
        var coldCount = 0
        var burnCount = 0

        for (gy in 0 until gridRows) {
            for (gx in 0 until gridCols) {
                val x0 = gx * cellW
                val y0 = gy * cellH
                val x1 = min(w, x0 + cellW)
                val y1 = min(h, y0 + cellH)
                var cellPads = 0
                var cellMask = 0
                var cellBurn = 0
                var cellGrayDull = 0
                var lumaSum = 0L
                var lumaSq = 0L
                var n = 0

                for (yy in y0 until y1) {
                    val rowOff = yy * w
                    for (xx in x0 until x1) {
                        val idx = rowOff + xx
                        val y = luma[idx]
                        lumaSum += y
                        lumaSq += y.toLong() * y.toLong()
                        n++
                        if (isSolderPad[idx]) cellPads++
                        if (isMask[idx]) cellMask++
                        if (isBurnCandidate[idx]) cellBurn++

                        val c = pixels[idx]
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF
                        val chroma = max(r, max(g, b)) - min(r, min(g, b))
                        if (chroma < 26 && y in 85..152) cellGrayDull++
                    }
                }
                if (n <= 0) continue

                val padRatio = cellPads.toFloat() / n.toFloat()
                val maskRatio = cellMask.toFloat() / n.toFloat()
                val burnRatio = cellBurn.toFloat() / n.toFloat()
                val dullRatio = cellGrayDull.toFloat() / n.toFloat()
                val meanY = lumaSum.toDouble() / n.toDouble()
                val stdY = sqrt(max(0.0, (lumaSq.toDouble() / n.toDouble()) - meanY * meanY))

                val rect = RectF(
                    x0.toFloat() / w.toFloat(),
                    y0.toFloat() / h.toFloat(),
                    x1.toFloat() / w.toFloat(),
                    y1.toFloat() / h.toFloat()
                )

                // Heuristic 1: Excess connected solder coverage (>= 62% of cell) alongside nearby mask = bridge risk
                if (padRatio >= 0.62f && (maskRatio >= 0.05f || padRatio >= 0.78f) && maskCount > pixels.size / 20) {
                    bridgeCount++
                    if (hotspots.size < 18) {
                        hotspots.add(
                            PcbHotspot(
                                kind = DefectKind.SOLDER_BRIDGE,
                                rectNorm = rect,
                                confidencePct = (padRatio * 100f).toInt().coerceIn(55, 96),
                                detailRu = "Сплошное пятно припоя ${(padRatio * 100).toInt()}% — риск КЗ/перемычки"
                            )
                        )
                    }
                } else if (burnRatio >= 0.45f && maskCount > pixels.size / 16) {
                    // Heuristic 2: Dark carbonized spot on PCB mask
                    burnCount++
                    if (hotspots.size < 18) {
                        hotspots.add(
                            PcbHotspot(
                                kind = DefectKind.BURN_MARK,
                                rectNorm = rect,
                                confidencePct = (burnRatio * 100f).toInt().coerceIn(50, 95),
                                detailRu = "Тёмный очаг/нагар (Y<42, ${(burnRatio * 100).toInt()}%) — осмотр флюса/прогара"
                            )
                        )
                    }
                } else if (dullRatio >= 0.40f && stdY > 18.0 && cellPads >= 2) {
                    // Heuristic 3: Dull granular gray joint near bright pad
                    coldCount++
                    if (hotspots.size < 18) {
                        hotspots.add(
                            PcbHotspot(
                                kind = DefectKind.COLD_JOINT,
                                rectNorm = rect,
                                confidencePct = (dullRatio * 100f).toInt().coerceIn(45, 88),
                                detailRu = "Матовая зернистая область рядом с контактом (возможна холодная пайка)"
                            )
                        )
                    }
                }
            }
        }

        // Annotate overlay bitmap: faint cyan trace boost on pads + defect boxes
        for (i in pixels.indices) {
            if (isSolderPad[i]) {
                val c = pixels[i]
                val r = ((c ushr 16) and 0xFF) * 3 / 4
                val g = min(255, ((c ushr 8) and 0xFF) + 28)
                val b = min(255, (c and 0xFF) + 38)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        work.setPixels(pixels, 0, w, 0, 0, w, h)

        val canvas = Canvas(work)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, w / 160f)
        }
        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = max(9f, w / 28f)
            isFakeBoldText = true
        }
        for (spot in hotspots) {
            val col = spot.kind.badgeColorHex.toInt()
            strokePaint.color = col
            tagPaint.color = col
            val left = spot.rectNorm.left * w
            val top = spot.rectNorm.top * h
            val right = spot.rectNorm.right * w
            val bottom = spot.rectNorm.bottom * h
            canvas.drawRect(left, top, right, bottom, strokePaint)
            val shortTag = when (spot.kind) {
                DefectKind.SOLDER_BRIDGE -> "BR"
                DefectKind.COLD_JOINT -> "CJ"
                DefectKind.BURN_MARK -> "BN"
            }
            canvas.drawText(shortTag, left + 2f, max(10f, top - 2f), tagPaint)
        }

        val maskPct = ((maskCount * 100L) / pixels.size).toInt().coerceIn(0, 100)
        val padPct = ((padCount * 100L) / pixels.size).toInt().coerceIn(0, 100)
        val penalty = bridgeCount * 9 + burnCount * 11 + coldCount * 5
        val health = (100 - penalty).coerceIn(12, 100)

        val summary = buildString {
            append("Индекс платы: $health/100 • Маска $maskPct% • Контакты $padPct%. ")
            if (hotspots.isEmpty()) {
                append("Явных оптических перемычек/прогаров не найдено (проверка мультиметром обязательна).")
            } else {
                append("Найдено зон внимания: мостики=$bridgeCount, матовые=$coldCount, нагар=$burnCount.")
            }
        }

        return PcbInspectionReport(
            boardHealthScore = health,
            solderMaskPct = maskPct,
            padCoveragePct = padPct,
            bridgeCandidates = bridgeCount,
            coldJointCandidates = coldCount,
            burnSpots = burnCount,
            hotspots = hotspots,
            annotatedBitmap = work,
            summaryRu = summary
        )
    }

    /**
     * Decodes standard SMD marking codes:
     * - "000"/"0" -> 0 Ohm jumper
     * - 3-digit EIA ("103" -> 10 kOhm) & MLCC cap reading ("104" -> 100 nF)
     * - 4-digit precision EIA ("1002" -> 10 kOhm)
     * - R/K/M decimal codes ("4R7" -> 4.7 Ohm, "1K5" -> 1.5 kOhm)
     * - EIA-96 1% precision ("01C" -> 10 kOhm 1%, "68X" -> 49.9 Ohm 1%)
     */
    fun decodeSmdCode(rawCode: String): SmdDecodeResult {
        val code = rawCode.trim().uppercase().replace(" ", "")
        if (code.isEmpty()) {
            return SmdDecodeResult(rawCode, "Неизвестно", "Введи SMD-код (например 103, 4R7, 01C)", valid = false)
        }

        if (code == "0" || code == "00" || code == "000" || code == "0000") {
            return SmdDecodeResult(code, "Перемычка SMD", "0 Ом (перемычка)", tolerance = "≤0.05 Ом")
        }

        // EIA-96: 2 digits (01..96) + multiplier char
        if (code.length == 3 && code[0].isDigit() && code[1].isDigit() && EIA96_MULT.containsKey(code[2])) {
            val idx = code.substring(0, 2).toIntOrNull() ?: -1
            if (idx in 1..96) {
                val base = EIA96_BASE[idx - 1]
                val mult = EIA96_MULT[code[2]] ?: 1.0
                val ohms = base * mult
                return SmdDecodeResult(
                    code = code,
                    componentTypeRu = "Резистор EIA-96 (1%)",
                    primaryValueRu = formatOhms(ohms),
                    tolerance = "±1%"
                )
            }
        }

        // R / K / M notation (e.g., 4R7, R22, 1K5, 2M2)
        val rkmMatch = Regex("""^(\d*)([RKM])(\d+)$""").find(code)
        if (rkmMatch != null) {
            val intPart = rkmMatch.groupValues[1].ifEmpty { "0" }
            val unitChar = rkmMatch.groupValues[2][0]
            val fracPart = rkmMatch.groupValues[3]
            val baseVal = "$intPart.$fracPart".toDoubleOrNull()
            if (baseVal != null) {
                val mult = when (unitChar) {
                    'K' -> 1_000.0
                    'M' -> 1_000_000.0
                    else -> 1.0
                }
                return SmdDecodeResult(
                    code = code,
                    componentTypeRu = "SMD Резистор",
                    primaryValueRu = formatOhms(baseVal * mult),
                    tolerance = "±1% / ±5%"
                )
            }
        }

        // Standard 3-digit EIA (e.g. 103 -> 10 kOhm or 10 nF)
        if (code.length == 3 && code.all { it.isDigit() }) {
            val sig = code.substring(0, 2).toInt()
            val exp = code[2].digitToInt()
            val ohms = sig * 10.0.pow(exp)
            val pf = sig * 10.0.pow(exp)
            return SmdDecodeResult(
                code = code,
                componentTypeRu = "SMD 3-знач. код",
                primaryValueRu = "R: ${formatOhms(ohms)}",
                secondaryInterpretationRu = "Если MLCC/кондер: ${formatPicoFarads(pf)}",
                tolerance = "±5%"
            )
        }

        // Standard 4-digit precision EIA (e.g. 1002 -> 10 kOhm, 4991 -> 4.99 kOhm)
        if (code.length == 4 && code.all { it.isDigit() }) {
            val sig = code.substring(0, 3).toInt()
            val exp = code[3].digitToInt()
            val ohms = sig * 10.0.pow(exp)
            return SmdDecodeResult(
                code = code,
                componentTypeRu = "Точный SMD 4-знач.",
                primaryValueRu = formatOhms(ohms),
                tolerance = "±1%"
            )
        }

        return SmdDecodeResult(
            code = code,
            componentTypeRu = "Не распознано как EIA",
            primaryValueRu = "Проверь маркировку корпуса (SOT-23 / SOIC / диод)",
            valid = false
        )
    }

    fun decodeColorBands(
        band1: ColorBand,
        band2: ColorBand,
        multiplier: ColorBand,
        tolerance: ColorBand = ColorBand.GOLD
    ): SmdDecodeResult {
        val d1 = band1.digit
        val d2 = band2.digit
        val mult = multiplier.multiplier
        if (d1 == null || d2 == null || mult == null) {
            return SmdDecodeResult(
                code = "${band1.labelRu}/${band2.labelRu}",
                componentTypeRu = "4-полосный резистор",
                primaryValueRu = "Первые 2 полосы должны быть цифровыми, 3-я — множитель",
                valid = false
            )
        }
        val ohms = (d1 * 10 + d2) * mult
        val tol = tolerance.tolerance ?: "±5%"
        return SmdDecodeResult(
            code = "${d1}${d2}×${multiplier.labelRu}",
            componentTypeRu = "Выводной резистор (4 полосы)",
            primaryValueRu = formatOhms(ohms),
            tolerance = tol
        )
    }

    /**
     * Offline ML Kit OCR over a PCB photo: extracts reference designators (R/C/U/Q/L/D),
     * common IC families, and auto-decodes potential SMD resistor/cap tokens.
     */
    suspend fun analyzePcbSilkscreen(bitmap: Bitmap): SilkscreenScanResult {
        val text = runMlKitTextOcr(bitmap)
        if (text.isBlank()) {
            return SilkscreenScanResult("", emptyList(), emptyList(), emptyList())
        }
        val tokens = text.split(Regex("""[\s,;|/]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val desRegex = Regex("""^[RCUQLDJF]\d{1,4}[A-Z]?$""")
        val smdCandRegex = Regex("""^(?:\d{3,4}|\d*[RKM]\d+|\d{2}[ZYRXSAHBCDFE])$""")
        val icHints = listOf("STM", "ESP", "CH34", "CP21", "TP40", "AMS1", "LM", "NE55", "MT67", "RTL", "ATMEGA", "PIC", "74HC", "MAX", "INA", "BQ2", "SY8", "MP1")

        val designators = tokens.map { it.uppercase() }.filter { desRegex.matches(it) }.distinct().take(24)
        val ics = tokens.map { it.uppercase() }.filter { t ->
            t.length >= 4 && (icHints.any { t.startsWith(it) } || (t.any { it.isLetter() } && t.any { it.isDigit() } && !desRegex.matches(t)))
        }.distinct().take(16)

        val decodedSmd = tokens.map { it.uppercase() }
            .filter { smdCandRegex.matches(it) }
            .distinct()
            .map { decodeSmdCode(it) }
            .filter { it.valid }
            .take(12)

        return SilkscreenScanResult(
            rawText = text.trim(),
            designators = designators,
            icCandidates = ics,
            decodedSmdCodes = decodedSmd
        )
    }

    private suspend fun runMlKitTextOcr(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text ?: "")
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume("")
                }
        } catch (_: Exception) {
            if (cont.isActive) cont.resume("")
        }
    }

    fun formatOhms(ohms: Double): String {
        return when {
            ohms >= 1_000_000.0 -> "${trimTrailingZero(ohms / 1_000_000.0)} МОм"
            ohms >= 1_000.0 -> "${trimTrailingZero(ohms / 1_000.0)} кОм"
            else -> "${trimTrailingZero(ohms)} Ом"
        }
    }

    fun formatPicoFarads(pf: Double): String {
        return when {
            pf >= 1_000_000.0 -> "${trimTrailingZero(pf / 1_000_000.0)} мкФ"
            pf >= 1_000.0 -> "${trimTrailingZero(pf / 1_000.0)} нФ"
            else -> "${trimTrailingZero(pf)} пФ"
        }
    }

    private fun trimTrailingZero(v: Double): String {
        val rounded = (v * 100.0).toLong() / 100.0
        val asLong = rounded.toLong()
        return if (abs(rounded - asLong.toDouble()) < 0.005) asLong.toString() else rounded.toString()
    }
}
