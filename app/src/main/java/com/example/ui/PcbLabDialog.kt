package com.example.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.camera.ExifStripper
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
import com.example.security.SecureClipboard
import com.example.security.ZverPrefs
import com.example.visor.PcbInspectorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays

private val PcbBg = Color(0xFF070B0E)
private val PcbSurface = Color(0xFF111820)
private val PcbBorder = Color(0xFF223240)
private val PcbGreen = Color(0xFF00FF66)
private val PcbCyan = Color(0xFF00E5FF)
private val PcbAmber = Color(0xFFFFC107)
private val PcbRed = Color(0xFFFF3366)
private val PcbMuted = Color(0xFF8FA3B0)

private enum class PcbLabTab(val titleRu: String) {
    INSPECT("🔬 ПЛАТА"),
    SILKSCREEN("🔤 OCR ЧИПЫ"),
    DECODER("🎛️ SMD КОДЫ")
}

@Composable
fun PcbLabDialog(
    initialBitmap: Bitmap? = null,
    onCaptureFromCamera: (suspend () -> Bitmap?)? = null,
    onSavedToVault: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(if (initialBitmap != null) PcbLabTab.INSPECT else PcbLabTab.DECODER) }
    var workingBitmap by remember { mutableStateOf<Bitmap?>(initialBitmap?.copy(Bitmap.Config.ARGB_8888, false)) }
    var report by remember { mutableStateOf<PcbInspectorEngine.PcbInspectionReport?>(null) }
    var silkscreen by remember { mutableStateOf<PcbInspectorEngine.SilkscreenScanResult?>(null) }
    var showOverlay by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    // SMD & Color Calculator State
    var smdCodeInput by remember { mutableStateOf("103") }
    val smdResult = remember(smdCodeInput) { PcbInspectorEngine.decodeSmdCode(smdCodeInput) }
    var band1 by remember { mutableStateOf(PcbInspectorEngine.ColorBand.BROWN) }
    var band2 by remember { mutableStateOf(PcbInspectorEngine.ColorBand.BLACK) }
    var bandMult by remember { mutableStateOf(PcbInspectorEngine.ColorBand.RED) }
    val colorResult = remember(band1, band2, bandMult) {
        PcbInspectorEngine.decodeColorBands(band1, band2, bandMult)
    }

    fun runInspectOn(bmp: Bitmap) {
        isBusy = true
        scope.launch {
            try {
                report?.annotatedBitmap?.let { if (!it.isRecycled) it.recycle() }
                val res = withContext(Dispatchers.Default) {
                    PcbInspectorEngine.inspectBoard(bmp)
                }
                report = res
                val ocr = withContext(Dispatchers.Default) {
                    PcbInspectorEngine.analyzePcbSilkscreen(bmp)
                }
                silkscreen = ocr
                statusText = "ПЛАТА ПРОАНАЛИЗИРОВАНА: ${res.boardHealthScore}/100"
            } finally {
                isBusy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        workingBitmap?.let { runInspectOn(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            report?.annotatedBitmap?.let { if (!it.isRecycled) it.recycle() }
            workingBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PcbBg.copy(alpha = 0.97f))
                .zverDialogSafeBarsPadding()
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, PcbCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Memory, contentDescription = "PCB Lab", tint = PcbCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PCB ЛАБОРАТОРИЯ (ОФФЛАЙН)",
                            color = PcbCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp).testTag("pcb_close_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = PcbMuted)
                    }
                }

                // Tab bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PcbLabTab.values().forEach { tab ->
                        val sel = activeTab == tab
                        Text(
                            text = tab.titleRu,
                            color = if (sel) Color.Black else PcbMuted,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sel) PcbCyan else PcbSurface)
                                .border(1.dp, if (sel) PcbCyan else PcbBorder, RoundedCornerShape(6.dp))
                                .clickable { activeTab = tab }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                                .testTag("pcb_tab_${tab.name.lowercase()}")
                        )
                    }
                }

                statusText?.let { msg ->
                    Text(msg, color = PcbGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
                }

                when (activeTab) {
                    PcbLabTab.INSPECT -> {
                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (onCaptureFromCamera != null) {
                                Button(
                                    onClick = {
                                        if (!isBusy) {
                                            isBusy = true
                                            scope.launch {
                                                val bmp = onCaptureFromCamera()
                                                if (bmp != null) {
                                                    workingBitmap?.let { if (!it.isRecycled) it.recycle() }
                                                    workingBitmap = bmp
                                                    runInspectOn(bmp)
                                                } else {
                                                    isBusy = false
                                                    statusText = "КАМЕРА НЕ ГОТОВА"
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isBusy,
                                    colors = ButtonDefaults.buttonColors(containerColor = PcbCyan),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("pcb_capture_btn")
                                ) {
                                    Text(
                                        if (isBusy) "АНАЛИЗ ПЛАТЫ..." else "📸 СНЯТЬ ПЛАТУ С КАМЕРЫ (ОЗУ)",
                                        color = Color.Black,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            val previewBmp = if (showOverlay && report != null) report!!.annotatedBitmap else workingBitmap
                            if (previewBmp != null && !previewBmp.isRecycled) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(230.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black)
                                        .border(1.dp, PcbBorder, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = previewBmp.asImageBitmap(),
                                        contentDescription = "PCB Analysis",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PcbSurface)
                                        .border(1.dp, PcbBorder, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Наведи макро-камеру на плату (2x–4x) и нажми «СНЯТЬ ПЛАТУ»\nили открой снимок из Сейфа → «ПЛАТА».",
                                        color = PcbMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            report?.let { rep ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { showOverlay = !showOverlay },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).height(42.dp)
                                    ) {
                                        Text(
                                            if (showOverlay) "СЛОЙ: ДЕФЕКТЫ" else "СЛОЙ: ОРИГ",
                                            color = PcbCyan,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val bmpToSave = rep.annotatedBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                                val clean = withContext(Dispatchers.Default) {
                                                    ExifStripper.processBitmapClean(
                                                        sourceBitmap = bmpToSave,
                                                        recycleSource = true,
                                                        preset = ZverPrefs.getCompressionPreset(context),
                                                        antiPrnu = false
                                                    )
                                                }
                                                withContext(Dispatchers.IO) {
                                                    if (ZverPrefs.isRamOnlyMode(context)) {
                                                        GhostCryptoVault.addRamShot(clean.cleanBytes.clone(), isVideo = false)
                                                    } else {
                                                        val (_, cos) = GhostCryptoVault.openEncryptedOutputStream(context, GhostCryptoVault.MEDIA_PHOTO)
                                                        cos.use { it.write(clean.cleanBytes); it.flush() }
                                                        try {
                                                            val vol = if (GhostCryptoVault.activeProfile == GhostCryptoVault.VaultProfile.MAIN) {
                                                                MonoVaultEngine.VolumeType.MASTER
                                                            } else {
                                                                MonoVaultEngine.VolumeType.DECOY
                                                            }
                                                            MonoVaultEngine.appendRecord(context, vol, clean.cleanBytes)
                                                        } catch (_: Exception) {}
                                                    }
                                                }
                                                Arrays.fill(clean.cleanBytes, 0.toByte())
                                                onSavedToVault("🔬 ОТЧЁТ PCB СОХРАНЁН В СЕЙФ")
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PcbGreen),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).height(42.dp)
                                    ) {
                                        Text(
                                            "В СЕЙФ (КОПИЯ)",
                                            color = Color.Black,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(PcbSurface, RoundedCornerShape(8.dp))
                                        .border(1.dp, PcbBorder, RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "ОЦЕНКА ПЛАТЫ: ${rep.boardHealthScore}/100",
                                        color = if (rep.boardHealthScore >= 75) PcbGreen else PcbAmber,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        "Маска: ${rep.solderMaskPct}% • Припой/медь: ${rep.padCoveragePct}% • Мостики: ${rep.bridgeCandidates} • Тускл: ${rep.coldJointCandidates} • Нагар: ${rep.burnSpots}",
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    )
                                    Text(rep.summaryRu, color = PcbMuted, fontSize = 10.sp)
                                    rep.hotspots.take(8).forEach { hs ->
                                        Text(
                                            "• [${hs.kind.labelRu} ${hs.confidencePct}%] ${hs.detailRu}",
                                            color = Color(hs.kind.badgeColorHex),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    PcbLabTab.SILKSCREEN -> {
                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val ocr = silkscreen
                            if (ocr == null) {
                                Text("Загрузи фото платы во вкладке «ПЛАТА» для локального ML Kit OCR.", color = PcbMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            } else {
                                Text("ПОЗИЦИОННЫЕ МЕТКИ (${ocr.designators.size}):", color = PcbCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text(
                                    if (ocr.designators.isEmpty()) "Не найдено" else ocr.designators.joinToString(", "),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                                Text("ВЕРОЯТНЫЕ МИКРОСХЕМЫ (${ocr.icCandidates.size}):", color = PcbGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text(
                                    if (ocr.icCandidates.isEmpty()) "Не найдено" else ocr.icCandidates.joinToString("\n"),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                                Text("АВТО-РАСШИФРОВКА SMD (${ocr.decodedSmdCodes.size}):", color = PcbAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                ocr.decodedSmdCodes.forEach { dec ->
                                    Text(
                                        "• ${dec.code} → ${dec.primaryValueRu} (${dec.componentTypeRu})",
                                        color = PcbAmber,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    )
                                }
                                if (ocr.rawText.isNotBlank()) {
                                    OutlinedButton(onClick = {
                                        SecureClipboard.copySensitive(context, "PCB OCR", ocr.rawText)
                                        statusText = "ТЕКСТ ПЛАТЫ СКОПИРОВАН (45с)"
                                    }) {
                                        Text("КОПИРОВАТЬ ВСЁ OCR", color = PcbGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                    }
                                    Text(ocr.rawText, color = PcbMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                }
                            }
                        }
                    }

                    PcbLabTab.DECODER -> {
                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("ДЕКОДЕР SMD (EIA-3/4, RKM, EIA-96 1%)", color = PcbGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            OutlinedTextField(
                                value = smdCodeInput,
                                onValueChange = { smdCodeInput = it.take(10) },
                                label = { Text("Код (напр. 103, 4R7, 01C, 1002)", fontSize = 10.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("pcb_smd_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PcbCyan,
                                    unfocusedBorderColor = PcbBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("103", "472", "4R7", "R22", "01C", "68X", "1002", "104", "000").forEach { ex ->
                                    Text(
                                        text = ex,
                                        color = if (smdCodeInput.equals(ex, true)) Color.Black else PcbCyan,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (smdCodeInput.equals(ex, true)) PcbCyan else PcbSurface)
                                            .border(1.dp, PcbBorder, RoundedCornerShape(6.dp))
                                            .clickable { smdCodeInput = ex }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(PcbSurface, RoundedCornerShape(8.dp))
                                    .border(1.dp, if (smdResult.valid) PcbGreen else PcbAmber, RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Text(smdResult.componentTypeRu, color = PcbMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                Text(smdResult.primaryValueRu, color = PcbGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                smdResult.secondaryInterpretationRu?.let { sec ->
                                    Text(sec, color = PcbCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                Text("Допуск: ${smdResult.tolerance}", color = PcbMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }

                            HorizontalDivider(color = PcbBorder)
                            Text("4-ПОЛОСНЫЙ ЦВЕТОВОЙ РЕЗИСТОР:", color = PcbAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            val digitBands = PcbInspectorEngine.ColorBand.values().filter { it.digit != null }
                            val multBands = PcbInspectorEngine.ColorBand.values().filter { it.multiplier != null }
                            BandRowSelector("1-я цифра", band1, digitBands) { band1 = it }
                            BandRowSelector("2-я цифра", band2, digitBands) { band2 = it }
                            BandRowSelector("Множитель", bandMult, multBands) { bandMult = it }
                            Text(
                                "ИТОГ: ${colorResult.primaryValueRu} (${colorResult.tolerance})",
                                color = PcbAmber,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BandRowSelector(
    label: String,
    selected: PcbInspectorEngine.ColorBand,
    options: List<PcbInspectorEngine.ColorBand>,
    onSelect: (PcbInspectorEngine.ColorBand) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$label: ${selected.labelRu}", color = PcbMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { b ->
                val sel = b == selected
                Text(
                    text = b.labelRu,
                    color = if (sel) Color.Black else Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (sel) PcbAmber else PcbSurface)
                        .border(1.dp, if (sel) PcbAmber else PcbBorder, RoundedCornerShape(5.dp))
                        .clickable { onSelect(b) }
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                )
            }
        }
    }
}
