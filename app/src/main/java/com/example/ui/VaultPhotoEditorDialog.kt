package com.example.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.camera.ExifStripper
import com.example.camera.PhotoIqEngine
import com.example.camera.ZoomIntelligence
import com.example.crypto.GhostCryptoVault
import com.example.security.ZverPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays
import kotlin.math.min

private val VpeBlack = Color(0xFF070B0E)
private val VpeSurface = Color(0xFF111820)
private val VpeBorder = Color(0xFF223240)
private val VpeGreen = Color(0xFF00FF66)
private val VpeCyan = Color(0xFF00E5FF)
private val VpeAmber = Color(0xFFFFC107)
private val VpeMuted = Color(0xFF8FA3B0)

private enum class EditorCropMode(val labelRu: String) {
    FULL("ПОЛНЫЙ"),
    SQUARE("1:1"),
    WIDE_16_9("16:9"),
    ZOOM_2X("2x ЦЕНТР")
}

/**
 * Non-destructive Encrypted Vault Photo Editor:
 * - Never overwrites the original .gcf file or RAM shot!
 * - Always saves edited output strictly as a NEW EXIF-scrubbed encrypted copy into Vault / RAM.
 * - Live 32-bin histogram, Brightness/Contrast/Saturation/Sharpness sliders, Dehaze CLAHE,
 *   Photo IQ quick profiles, Rotate 90°, Aspect/Zoom crop, and ephemeral Share.
 */
@Composable
fun VaultPhotoEditorDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onSavedCopyToVault: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var brightness by remember { mutableStateOf(0f) }      // -0.45f..0.45f
    var contrast by remember { mutableStateOf(1.0f) }       // 0.6f..1.6f
    var saturation by remember { mutableStateOf(1.0f) }     // 0.0f..1.8f
    var sharpness by remember { mutableStateOf(0f) }        // 0.0f..1.0f
    var dehazeClahe by remember { mutableStateOf(false) }
    var rotateQuarterTurns by remember { mutableStateOf(0) } // 0..3
    var cropMode by remember { mutableStateOf(EditorCropMode.FULL) }
    var iqPresetApplied by remember { mutableStateOf<PhotoIqEngine.PhotoIqProfile?>(null) }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var histBins32 by remember { mutableStateOf(FloatArray(32)) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Оригинал защищён: сохранение создаёт НОВУЮ чистую копию") }

    // Recompute preview + live histogram cleanly off main thread
    LaunchedEffect(
        sourceBitmap,
        brightness,
        contrast,
        saturation,
        sharpness,
        dehazeClahe,
        rotateQuarterTurns,
        cropMode,
        iqPresetApplied
    ) {
        val resultPair = withContext(Dispatchers.Default) {
            var transformed = transformGeometry(sourceBitmap, rotateQuarterTurns, cropMode)
            val iqProf = iqPresetApplied
            if (iqProf != null && iqProf != PhotoIqEngine.PhotoIqProfile.OFF) {
                val iqOut = PhotoIqEngine.enhanceBitmap(transformed, iqProf, recycleSource = transformed !== sourceBitmap)
                transformed = iqOut
            }
            val adj = PhotoIqEngine.PhotoAdjustments(
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                sharpness = sharpness,
                dehazeClahe = dehazeClahe
            )
            val graded = PhotoIqEngine.applyManualAdjustments(transformed, adj)
            if (transformed !== sourceBitmap && transformed !== graded && !transformed.isRecycled) {
                transformed.recycle()
            }
            val bins = PhotoIqEngine.computeBitmapHistogram32(graded)
            graded to bins
        }
        val old = previewBitmap
        previewBitmap = resultPair.first
        histBins32 = resultPair.second
        if (old != null && old !== sourceBitmap && old !== resultPair.first && !old.isRecycled) {
            old.recycle()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewBitmap?.let {
                if (it !== sourceBitmap && !it.isRecycled) it.recycle()
            }
        }
    }

    BackHandler { onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(VpeBlack)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(10.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = "Редактор", tint = VpeGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ФОТО-РЕДАКТОР СЕЙФА",
                        color = VpeGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            brightness = 0f
                            contrast = 1.0f
                            saturation = 1.0f
                            sharpness = 0f
                            dehazeClahe = false
                            rotateQuarterTurns = 0
                            cropMode = EditorCropMode.FULL
                            iqPresetApplied = null
                            statusText = "Параметры сброшены"
                        },
                        modifier = Modifier.testTag("editor_reset_btn")
                    ) {
                        Text("СБРОС", color = VpeAmber, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = VpeMuted)
                    }
                }
            }

            Text(
                text = statusText,
                color = VpeCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // Image Preview + Mini Histogram
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, VpeBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = previewBitmap ?: sourceBitmap
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Предпросмотр редактора",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                // Live 32-bin histogram badge
                Canvas(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(width = 96.dp, height = 34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .border(1.dp, VpeGreen.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(4.dp)
                ) {
                    val bw = size.width / 32f
                    for (i in 0 until 32) {
                        val bh = histBins32[i].coerceIn(0f, 1f) * size.height
                        drawRect(
                            color = VpeGreen.copy(alpha = 0.8f),
                            topLeft = Offset(i * bw, size.height - bh),
                            size = Size((bw - 1f).coerceAtLeast(1f), bh)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Controls Scroll Deck
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .background(VpeSurface, RoundedCornerShape(10.dp))
                    .border(1.dp, VpeBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Quick Geometry + Photo IQ Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EditorChip("↻ 90°", rotateQuarterTurns != 0, VpeCyan) {
                        rotateQuarterTurns = (rotateQuarterTurns + 1) % 4
                    }
                    EditorCropMode.values().forEach { cm ->
                        EditorChip(cm.labelRu, cropMode == cm, VpeGreen) {
                            cropMode = cm
                        }
                    }
                    EditorChip("CLAHE", dehazeClahe, VpeAmber) {
                        dehazeClahe = !dehazeClahe
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("IQ:", color = VpeMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    PhotoIqEngine.PhotoIqProfile.values().forEach { iq ->
                        EditorChip(iq.badgeRu, iqPresetApplied == iq, VpeCyan) {
                            iqPresetApplied = if (iqPresetApplied == iq) null else iq
                        }
                    }
                }

                EditorSliderRow(
                    label = "ЯРКОСТЬ",
                    value = brightness,
                    valueRange = -0.45f..0.45f,
                    displayVal = String.format(java.util.Locale.US, "%+.2f", brightness),
                    accent = VpeAmber
                ) { brightness = it }

                EditorSliderRow(
                    label = "КОНТРАСТ",
                    value = contrast,
                    valueRange = 0.6f..1.6f,
                    displayVal = String.format(java.util.Locale.US, "%.2fx", contrast),
                    accent = VpeGreen
                ) { contrast = it }

                EditorSliderRow(
                    label = "НАСЫЩЕН",
                    value = saturation,
                    valueRange = 0f..1.8f,
                    displayVal = String.format(java.util.Locale.US, "%.2fx", saturation),
                    accent = VpeCyan
                ) { saturation = it }

                EditorSliderRow(
                    label = "РЕЗКОСТЬ",
                    value = sharpness,
                    valueRange = 0f..1.0f,
                    displayVal = "${(sharpness * 100).toInt()}%",
                    accent = VpeGreen
                ) { sharpness = it }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Save Copy / Ephemeral Share Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val bmp = previewBitmap ?: sourceBitmap
                        isProcessing = true
                        scope.launch {
                            try {
                                val cleanRes = withContext(Dispatchers.Default) {
                                    ExifStripper.processBitmapClean(
                                        sourceBitmap = bmp,
                                        recycleSource = false,
                                        preset = ZverPrefs.getCompressionPreset(context),
                                        antiPrnu = ZverPrefs.isAntiPrnuEnabled(context)
                                    )
                                }
                                val ramOnly = ZverPrefs.isRamOnlyMode(context)
                                val savedMsg = withContext(Dispatchers.IO) {
                                    if (ramOnly) {
                                        val shot = GhostCryptoVault.addRamShot(cleanRes.cleanBytes.clone(), isVideo = false)
                                        "⚡ НОВАЯ КОПИЯ В ОЗУ (${shot.bytes.size / 1024} КБ)"
                                    } else {
                                        val (file, cos) = GhostCryptoVault.openEncryptedOutputStream(
                                            context,
                                            prefix = GhostCryptoVault.MEDIA_PHOTO
                                        )
                                        cos.use { it.write(cleanRes.cleanBytes); it.flush() }
                                        "💾 НОВАЯ КОПИЯ ${file.name} (${file.length() / 1024} КБ)"
                                    }
                                }
                                Arrays.fill(cleanRes.cleanBytes, 0.toByte())
                                isProcessing = false
                                onSavedCopyToVault(savedMsg)
                            } catch (e: Exception) {
                                isProcessing = false
                                statusText = "Ошибка сохранения: ${e.message}"
                            }
                        }
                    },
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = VpeGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("editor_save_copy_btn")
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Сохранить копию", tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isProcessing) "ОБРАБОТКА..." else "СОХРАНИТЬ КОПИЕЙ В СЕЙФ",
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }

                Button(
                    onClick = {
                        val bmp = previewBitmap ?: sourceBitmap
                        isProcessing = true
                        scope.launch {
                            try {
                                val cleanRes = withContext(Dispatchers.Default) {
                                    ExifStripper.processBitmapClean(
                                        sourceBitmap = bmp,
                                        recycleSource = false,
                                        preset = ZverPrefs.getCompressionPreset(context),
                                        antiPrnu = ZverPrefs.isAntiPrnuEnabled(context)
                                    )
                                }
                                val ext = if (cleanRes.isWebP) "webp" else "jpg"
                                val tmp = withContext(Dispatchers.IO) {
                                    GhostCryptoVault.createEphemeralShareBytes(context, cleanRes.cleanBytes, ext)
                                }
                                Arrays.fill(cleanRes.cleanBytes, 0.toByte())
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmp)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = if (cleanRes.isWebP) "image/webp" else "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Отправить чистую копию"))
                                isProcessing = false
                                statusText = "Эфемерный файл создан (0 EXIF)"
                            } catch (e: Exception) {
                                isProcessing = false
                                statusText = "Ошибка отправки: ${e.message}"
                            }
                        }
                    },
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = VpeBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("editor_share_copy_btn")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Отправить", tint = VpeCyan, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun transformGeometry(source: Bitmap, quarterTurns: Int, cropMode: EditorCropMode): Bitmap {
    var working = if (quarterTurns % 4 != 0) {
        val m = Matrix().apply { postRotate((quarterTurns % 4) * 90f) }
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
    } else {
        source
    }

    val cropped = when (cropMode) {
        EditorCropMode.FULL -> working
        EditorCropMode.SQUARE -> {
            val side = min(working.width, working.height)
            val x = (working.width - side) / 2
            val y = (working.height - side) / 2
            val out = Bitmap.createBitmap(working, x, y, side, side)
            if (working !== source && !working.isRecycled) working.recycle()
            out
        }
        EditorCropMode.WIDE_16_9 -> {
            val targetW = working.width
            val targetH = min(working.height, (working.width * 9) / 16).coerceAtLeast(32)
            val y = (working.height - targetH) / 2
            val out = Bitmap.createBitmap(working, 0, y, targetW, targetH)
            if (working !== source && !working.isRecycled) working.recycle()
            out
        }
        EditorCropMode.ZOOM_2X -> {
            val out = ZoomIntelligence.extractZoomDetailCrop(working, cropFraction = 0.5f, maxOutDim = 1600)
            if (working !== source && !working.isRecycled) working.recycle()
            out
        }
    }
    return cropped
}

@Composable
private fun EditorChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) accent.copy(alpha = 0.25f) else VpeBlack)
            .border(1.dp, if (selected) accent else VpeBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) accent else VpeMuted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun EditorSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayVal: String,
    accent: Color,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = VpeMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            modifier = Modifier.width(66.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.DarkGray
            ),
            modifier = Modifier.weight(1f).height(26.dp)
        )
        Text(
            text = displayVal,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            modifier = Modifier.width(44.dp)
        )
    }
}
