package com.example.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.camera.ExifStripper
import com.example.crypto.GhostCryptoVault
import com.example.privacy.OfflinePrivacyEngine
import com.example.security.ZverPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

private val EdBlack = Color(0xFF070B0E)
private val EdSurface = Color(0xFF111820)
private val EdBorder = Color(0xFF223240)
private val EdGreen = Color(0xFF00FF66)
private val EdCyan = Color(0xFF00E5FF)
private val EdRed = Color(0xFFFF1744)
private val EdMuted = Color(0xFF8FA3B0)

/**
 * PrivacyEditorDialog (ObscuraCam Core):
 * - Offline ML Kit Face & Text Auto-Detection.
 * - Drag on image to draw manual boxes; tap a box to remove it.
 * - 4 Styles (Blackout, Pixelate, Blur, Cyber Mask) + Invert Mode (blur everything EXCEPT boxes).
 * - Saves scrubbed WebP/JPEG directly into Encrypted Vault or RAM-Only, or shares ephemerally.
 */
@Composable
fun PrivacyEditorDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onSavedToVault: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var regions by remember { mutableStateOf<List<OfflinePrivacyEngine.NormalizedRegion>>(emptyList()) }
    var style by remember { mutableStateOf(OfflinePrivacyEngine.RedactionStyle.PIXELATE) }
    var maskPreset by remember { mutableStateOf(OfflinePrivacyEngine.MaskPreset.CLOWN) }
    var maskScale by remember { mutableStateOf(1.15f) }
    var customMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var invertMode by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(ZverPrefs.getCompressionPreset(context)) }
    var antiPrnu by remember { mutableStateOf(ZverPrefs.isAntiPrnuEnabled(context)) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Обнаружь лица/текст или обведи область пальцем") }
    var osintReport by remember { mutableStateOf<OfflinePrivacyEngine.OsintRiskReport?>(null) }

    val customMaskPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isProcessing = true
            val (loadedBmp, hasAlpha) = withContext(Dispatchers.IO) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    var sample = 1
                    val maxDim = max(bounds.outWidth, bounds.outHeight)
                    while (maxDim / sample > 1024) sample *= 2
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bmp = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                    Pair(bmp, bmp?.hasAlpha() == true)
                } catch (_: Exception) {
                    Pair(null, false)
                }
            }
            if (loadedBmp != null) {
                val old = customMaskBitmap
                customMaskBitmap = loadedBmp
                if (old != null && !old.isRecycled) old.recycle()
                maskPreset = OfflinePrivacyEngine.MaskPreset.CUSTOM_PNG
                style = OfflinePrivacyEngine.RedactionStyle.MASK
                statusText = if (hasAlpha) {
                    "Своя PNG-маска загружена (${loadedBmp.width}x${loadedBmp.height})"
                } else {
                    "Картинка загружена (без прозрачного фона — лучше PNG с альфой)"
                }
            } else {
                statusText = "Не удалось загрузить свою маску"
            }
            isProcessing = false
        }
    }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var movingRegionId by remember { mutableStateOf<String?>(null) }
    var movingOriginalRect by remember { mutableStateOf<RectF?>(null) }
    var movingLiveRect by remember { mutableStateOf<RectF?>(null) }
    val latestRegions by rememberUpdatedState(regions)

    // Re-render redacted preview cleanly in background whenever regions/style/mask/invert change
    LaunchedEffect(regions, style, invertMode, maskPreset, maskScale, customMaskBitmap, sourceBitmap) {
        if (regions.isEmpty()) {
            previewBitmap?.let { if (it != sourceBitmap) it.recycle() }
            previewBitmap = null
        } else {
            val rendered = withContext(Dispatchers.Default) {
                OfflinePrivacyEngine.applyRedactions(
                    source = sourceBitmap,
                    regions = regions,
                    style = style,
                    invertMode = invertMode,
                    maskPreset = maskPreset,
                    maskScale = maskScale,
                    customMaskBitmap = customMaskBitmap
                )
            }
            val prev = previewBitmap
            previewBitmap = rendered
            if (prev != null && prev != sourceBitmap && !prev.isRecycled) {
                prev.recycle()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewBitmap?.let {
                if (it != sourceBitmap && !it.isRecycled) it.recycle()
            }
            customMaskBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    fun getDisplayBoxBounds(): RectF {
        if (canvasSize.width == 0 || canvasSize.height == 0) return RectF(0f, 0f, 1f, 1f)
        val cw = canvasSize.width.toFloat()
        val ch = canvasSize.height.toFloat()
        val bw = sourceBitmap.width.toFloat()
        val bh = sourceBitmap.height.toFloat()
        val scale = min(cw / bw, ch / bh)
        val drawnW = bw * scale
        val drawnH = bh * scale
        val left = (cw - drawnW) / 2f
        val top = (ch - drawnH) / 2f
        return RectF(left, top, left + drawnW, top + drawnH)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(EdBlack)
                .zverDialogSafeBarsPadding()
                .padding(12.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🎭 OBSCURA-РЕДАКТОР (ОФФЛАЙН)",
                    color = EdGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).testTag("obscura_close_btn")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = EdMuted)
                }
            }

            Text(
                text = statusText,
                color = EdCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            osintReport?.let { rep ->
                if (rep.warnings.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .background(EdRed.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                            .border(1.dp, EdRed.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "⚠ OSINT-РИСК (${rep.riskScore}%): ${rep.warnings.joinToString(" • ")}",
                            color = Color(0xFFFFB74D),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            lineHeight = 12.sp
                        )
                    }
                }
            }

            // Detection Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            val (faces, sensitiveHits, rawText, allBlocks) = withContext(Dispatchers.Default) {
                                val f = OfflinePrivacyEngine.detectFacesOffline(sourceBitmap)
                                val (entries, raw) = OfflinePrivacyEngine.detectTextWithContentOffline(sourceBitmap)
                                val hits = OfflinePrivacyEngine.analyzeSensitiveOsintBlocksFromEntries(entries)
                                listOf(f, hits, raw, entries.map { it.first })
                            }
                            @Suppress("UNCHECKED_CAST")
                            val foundFaces = faces as List<OfflinePrivacyEngine.NormalizedRegion>
                            @Suppress("UNCHECKED_CAST")
                            val osintHits = sensitiveHits as List<OfflinePrivacyEngine.OsintTextHit>
                            @Suppress("UNCHECKED_CAST")
                            val textRegions = allBlocks as List<OfflinePrivacyEngine.NormalizedRegion>
                            val raw = rawText as String

                            val existingIds = regions.map { it.id }.toSet()
                            val toAdd = (foundFaces + osintHits.map { it.region }).filter { it.id !in existingIds }
                            regions = regions + toAdd
                            osintReport = OfflinePrivacyEngine.assessOsintRisks(
                                OfflinePrivacyEngine.DetectionResult(
                                    faces = foundFaces,
                                    textBlocks = textRegions,
                                    extractedText = raw
                                )
                            )
                            isProcessing = false
                            statusText = "Авто-OSINT: лиц ${foundFaces.size}, опасного текста ${osintHits.size}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EdGreen.copy(alpha = 0.22f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("obscura_auto_osint_btn")
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = "Авто-OSINT", tint = EdGreen, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("АВТО-OSINT", color = EdGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }

                Button(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            val found = withContext(Dispatchers.Default) {
                                OfflinePrivacyEngine.detectFacesOffline(sourceBitmap)
                            }
                            regions = regions + found
                            isProcessing = false
                            statusText = if (found.isEmpty()) "Лица не найдены — обведи пальцем" else "Найдено лиц: ${found.size}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EdSurface),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("obscura_detect_faces")
                ) {
                    Icon(Icons.Default.Face, contentDescription = "Лица", tint = EdGreen, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("АВТО-ЛИЦА", color = EdGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }

                Button(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            val (found, rawText) = withContext(Dispatchers.Default) {
                                OfflinePrivacyEngine.detectTextOffline(sourceBitmap)
                            }
                            regions = regions + found
                            val det = OfflinePrivacyEngine.DetectionResult(
                                faces = regions.filter { it.kind == OfflinePrivacyEngine.RegionKind.FACE },
                                textBlocks = found,
                                extractedText = rawText
                            )
                            osintReport = OfflinePrivacyEngine.assessOsintRisks(det)
                            isProcessing = false
                            statusText = if (found.isEmpty()) "Блоки текста не найдены" else "Найдено текстовых зон: ${found.size}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EdSurface),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("obscura_detect_text")
                ) {
                    Icon(Icons.Default.DocumentScanner, contentDescription = "Текст", tint = EdCyan, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("АВТО-ТЕКСТ", color = EdCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }

                Button(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            val targetBmp = previewBitmap ?: sourceBitmap
                            val full = withContext(Dispatchers.Default) {
                                OfflinePrivacyEngine.analyzeFullOffline(targetBmp)
                            }
                            osintReport = OfflinePrivacyEngine.assessOsintRisks(full)
                            isProcessing = false
                            statusText = if ((osintReport?.warnings ?: emptyList()).isEmpty()) {
                                "OSINT-линтер: чистый кадр (утечек не найдено)"
                            } else {
                                "OSINT-линтер: найдено угроз ${osintReport!!.warnings.size}"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EdSurface),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("obscura_osint_lint")
                ) {
                    Icon(Icons.Default.Security, contentDescription = "OSINT", tint = Color(0xFFFFB74D), modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("OSINT-ЛИНТ", color = Color(0xFFFFB74D), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }

                Button(
                    onClick = {
                        invertMode = !invertMode
                        statusText = if (invertMode) "ИНВЕРСИЯ: скрыт весь фон кроме выделенных зон" else "Обычный режим: скрываются только зоны"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (invertMode) EdCyan else EdSurface),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("obscura_invert_btn")
                ) {
                    Text(
                        if (invertMode) "ИНВЕРСИЯ: ВКЛ" else "ИНВЕРСИЯ",
                        color = if (invertMode) Color.Black else Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        regions = emptyList()
                        statusText = "Все рамки сброшены"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EdSurface),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("obscura_reset_btn")
                ) {
                    Text("СБРОС (${regions.size})", color = EdRed, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Interactive Canvas over Image
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
                    .border(1.dp, EdBorder, RoundedCornerShape(10.dp))
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(regions, canvasSize) {
                        detectTapGestures { tap ->
                            val bounds = getDisplayBoxBounds()
                            val nx = ((tap.x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                            val ny = ((tap.y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
                            val hit = regions.lastOrNull { r ->
                                nx in min(r.rect.left, r.rect.right)..max(r.rect.left, r.rect.right) &&
                                    ny in min(r.rect.top, r.rect.bottom)..max(r.rect.top, r.rect.bottom)
                            }
                            if (hit != null) {
                                regions = regions - hit
                                statusText = "Удалена 1 зона (осталось ${regions.size})"
                            }
                        }
                    }
                    .pointerInput(canvasSize) {
                        detectDragGestures(
                            onDragStart = { start ->
                                dragStart = start
                                dragEnd = start
                                val bounds = getDisplayBoxBounds()
                                val bw = bounds.width().coerceAtLeast(1f)
                                val bh = bounds.height().coerceAtLeast(1f)
                                val nx = ((start.x - bounds.left) / bw).coerceIn(0f, 1f)
                                val ny = ((start.y - bounds.top) / bh).coerceIn(0f, 1f)
                                val hit = latestRegions.lastOrNull { r ->
                                    nx in min(r.rect.left, r.rect.right)..max(r.rect.left, r.rect.right) &&
                                        ny in min(r.rect.top, r.rect.bottom)..max(r.rect.top, r.rect.bottom)
                                }
                                if (hit != null) {
                                    movingRegionId = hit.id
                                    movingOriginalRect = RectF(hit.rect)
                                    movingLiveRect = RectF(hit.rect)
                                    statusText = "Перемещение зоны..."
                                } else {
                                    movingRegionId = null
                                    movingOriginalRect = null
                                    movingLiveRect = null
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragEnd = change.position
                                val s = dragStart
                                val orig = movingOriginalRect
                                if (s != null && orig != null) {
                                    val bounds = getDisplayBoxBounds()
                                    val dxNorm = (change.position.x - s.x) / bounds.width().coerceAtLeast(1f)
                                    val dyNorm = (change.position.y - s.y) / bounds.height().coerceAtLeast(1f)
                                    movingLiveRect = OfflinePrivacyEngine.moveRegionClamped(orig, dxNorm, dyNorm)
                                }
                            },
                            onDragEnd = {
                                val targetMoveId = movingRegionId
                                val finalMovedRect = movingLiveRect
                                val s = dragStart
                                val e = dragEnd
                                dragStart = null
                                dragEnd = null
                                movingRegionId = null
                                movingOriginalRect = null
                                movingLiveRect = null

                                if (targetMoveId != null && finalMovedRect != null) {
                                    regions = regions.map { reg ->
                                        if (reg.id == targetMoveId) reg.copy(rect = finalMovedRect) else reg
                                    }
                                    statusText = "Зона перемещена (всего ${regions.size}; тап — удалить)"
                                } else if (s != null && e != null) {
                                    val bounds = getDisplayBoxBounds()
                                    val l = ((min(s.x, e.x) - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                                    val t = ((min(s.y, e.y) - bounds.top) / bounds.height()).coerceIn(0f, 1f)
                                    val r = ((max(s.x, e.x) - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                                    val b = ((max(s.y, e.y) - bounds.top) / bounds.height()).coerceIn(0f, 1f)
                                    if (r - l > 0.03f && b - t > 0.03f) {
                                        val newRegion = OfflinePrivacyEngine.NormalizedRegion(
                                            id = "man_${UUID.randomUUID().toString().take(6)}",
                                            rect = RectF(l, t, r, b),
                                            kind = OfflinePrivacyEngine.RegionKind.MANUAL
                                        )
                                        regions = regions + newRegion
                                        statusText = "Добавлена зона (всего ${regions.size}; тап — удалить, драг — сдвиг)"
                                    }
                                }
                            },
                            onDragCancel = {
                                dragStart = null
                                dragEnd = null
                                movingRegionId = null
                                movingOriginalRect = null
                                movingLiveRect = null
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val bmpToDraw = previewBitmap ?: sourceBitmap
                Image(
                    bitmap = bmpToDraw.asImageBitmap(),
                    contentDescription = "Редактируемое изображение",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                // Draw region outlines + active move preview + active new marquee
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val bounds = getDisplayBoxBounds()
                    for (reg in regions) {
                        val isMovingThis = reg.id == movingRegionId
                        val rRect = if (isMovingThis && movingLiveRect != null) movingLiveRect!! else reg.rect
                        val rx = bounds.left + rRect.left * bounds.width()
                        val ry = bounds.top + rRect.top * bounds.height()
                        val rw = (rRect.right - rRect.left) * bounds.width()
                        val rh = (rRect.bottom - rRect.top) * bounds.height()
                        drawRect(
                            color = if (isMovingThis) Color(0xFFFFC107) else if (reg.kind == OfflinePrivacyEngine.RegionKind.FACE) EdGreen else EdCyan,
                            topLeft = Offset(rx, ry),
                            size = Size(rw, rh),
                            style = Stroke(width = if (isMovingThis) 2.5.dp.toPx() else 1.5.dp.toPx())
                        )
                    }

                    val ds = dragStart
                    val de = dragEnd
                    if (movingRegionId == null && ds != null && de != null) {
                        drawRect(
                            color = EdRed,
                            topLeft = Offset(min(ds.x, de.x), min(ds.y, de.y)),
                            size = Size(kotlin.math.abs(de.x - ds.x), kotlin.math.abs(de.y - ds.y)),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                if (isProcessing) {
                    CircularProgressIndicator(color = EdGreen)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Style Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OfflinePrivacyEngine.RedactionStyle.values().forEach { s ->
                    val isSel = style == s
                    Text(
                        text = s.labelRu,
                        color = if (isSel) Color.Black else EdMuted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSel) EdGreen else EdSurface)
                            .border(1.dp, if (isSel) EdGreen else EdBorder, RoundedCornerShape(6.dp))
                            .clickable {
                                style = s
                                if (s == OfflinePrivacyEngine.RedactionStyle.MASK && regions.isEmpty()) {
                                    statusText = "Выбери маску и размер! Для авто-наложения нажми АВТО-ЛИЦА или обведи пальцем"
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("obscura_style_${s.name.lowercase()}")
                    )
                }

                Text(
                    text = if (antiPrnu) "ANTI-PRNU: ВКЛ" else "ANTI-PRNU: ВЫКЛ",
                    color = if (antiPrnu) Color.Black else EdMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (antiPrnu) EdCyan else EdSurface)
                        .border(1.dp, if (antiPrnu) EdCyan else EdBorder, RoundedCornerShape(6.dp))
                        .clickable { antiPrnu = !antiPrnu }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            if (style == OfflinePrivacyEngine.RedactionStyle.MASK) {
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(EdSurface)
                        .border(1.dp, EdBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OfflinePrivacyEngine.MaskPreset.values().forEach { mp ->
                            val isMpSel = maskPreset == mp
                            Text(
                                text = mp.labelRu,
                                color = if (isMpSel) Color.Black else Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isMpSel) EdCyan else EdBlack)
                                    .border(1.dp, if (isMpSel) EdCyan else EdBorder, RoundedCornerShape(6.dp))
                                    .clickable {
                                        if (mp == OfflinePrivacyEngine.MaskPreset.CUSTOM_PNG) {
                                            customMaskPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        } else {
                                            maskPreset = mp
                                        }
                                    }
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                                    .testTag("obscura_mask_${mp.name.lowercase()}")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "РАЗМЕР: ${(maskScale * 100).toInt()}%",
                            color = EdCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.width(88.dp)
                        )
                        FilledTonalButton(
                            onClick = { maskScale = (maskScale - 0.10f).coerceIn(0.6f, 2.2f) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("−", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Slider(
                            value = maskScale,
                            onValueChange = { maskScale = it },
                            valueRange = 0.6f..2.2f,
                            colors = SliderDefaults.colors(
                                thumbColor = EdGreen,
                                activeTrackColor = EdGreen,
                                inactiveTrackColor = EdBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("obscura_mask_scale_slider")
                        )
                        FilledTonalButton(
                            onClick = { maskScale = (maskScale + 0.10f).coerceIn(0.6f, 2.2f) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("+", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Compression Presets Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExifStripper.CompressionPreset.values().forEach { cp ->
                    val isSel = preset == cp
                    Text(
                        text = cp.labelRu,
                        color = if (isSel) Color.Black else EdMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSel) EdGreen else EdSurface)
                            .border(1.dp, if (isSel) EdGreen else EdBorder, RoundedCornerShape(6.dp))
                            .clickable { preset = cp }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Save & Share Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            try {
                                val targetBmp = previewBitmap ?: sourceBitmap
                                val processed = withContext(Dispatchers.IO) {
                                    ExifStripper.processBitmapClean(
                                        sourceBitmap = targetBmp,
                                        recycleSource = false,
                                        preset = preset,
                                        antiPrnu = antiPrnu
                                    )
                                }
                                try {
                                    val kb = processed.cleanBytes.size / 1024
                                    if (ZverPrefs.isRamOnlyMode(context)) {
                                        GhostCryptoVault.addRamShot(processed.cleanBytes.clone(), isVideo = false)
                                        onSavedToVault("Анонимный кадр (${kb} КБ) сохранён в ОЗУ!")
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            GhostCryptoVault.encryptBytesToVault(context, processed.cleanBytes, GhostCryptoVault.MEDIA_PHOTO)
                                        }
                                        onSavedToVault("Анонимный кадр (${kb} КБ, 0 EXIF) зашифрован в Сейф!")
                                    }
                                    onDismiss()
                                } finally {
                                    Arrays.fill(processed.cleanBytes, 0.toByte())
                                }
                            } catch (e: Exception) {
                                statusText = "Ошибка: ${e.message}"
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EdGreen),
                    modifier = Modifier.weight(1f).testTag("obscura_save_btn")
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Сохранить", tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("В СЕЙФ (0 EXIF)", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            try {
                                val targetBmp = previewBitmap ?: sourceBitmap
                                val processed = withContext(Dispatchers.IO) {
                                    ExifStripper.processBitmapClean(
                                        sourceBitmap = targetBmp,
                                        recycleSource = false,
                                        preset = preset,
                                        antiPrnu = antiPrnu
                                    )
                                }
                                try {
                                    val ext = if (processed.isWebP) "webp" else "jpg"
                                    val mime = if (processed.isWebP) "image/webp" else "image/jpeg"
                                    val tmp = GhostCryptoVault.createEphemeralShareBytes(context, processed.cleanBytes, ext)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmp)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = mime
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Отправить чистое фото"))
                                } finally {
                                    Arrays.fill(processed.cleanBytes, 0.toByte())
                                }
                            } catch (e: Exception) {
                                statusText = "Ошибка отправки: ${e.message}"
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EdBorder),
                    modifier = Modifier.weight(0.8f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = EdGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ОТПРАВИТЬ", color = EdGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }
    }
}
