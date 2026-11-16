package com.example.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.assist.ByokManager
import com.example.assist.GeminiByokClient
import com.example.camera.CaptureManager
import com.example.camera.ExifStripper
import com.example.camera.VideoKeyframeExtractor
import com.example.privacy.LocalSymbiosisCvEngine
import com.example.security.VaultAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale

private val SheetBg = Color(0xFF070B0E)
private val SheetCard = Color(0xFF10171E)
private val SheetBorder = Color(0xFF20303D)
private val CyberGreen = Color(0xFF00FF66)
private val CyberGreenDark = Color(0xFF008F39)
private val CyberCyan = Color(0xFF00E5FF)
private val CyberRed = Color(0xFFFF1744)
private val CyberAmber = Color(0xFFFFC107)
private val CyberMuted = Color(0xFF8FA3B0)

enum class VikaSheetTab(val labelRu: String) {
    TASKS("🧠 Задачи"),
    ANALYTICS("📊 Аналитика"),
    KEYS_MODELS("🔑 Ключ и Модели")
}

private enum class AiFrameMode {
    FULL_ONLY,      // Вся фотка целиком -> чистая Вика/Gemini
    SYMBIOSIS_CROP, // Вся фотка + ROI-кроп (лицо/объект) + досье 6-NN -> Gemini
    BG_ISOLATE,     // Selfie Segmentation вырезает/блюрит фон -> Gemini
    TEXT_ONLY       // Только текст / статистика
}

@Composable
fun VikaAiSheet(
    initialTab: VikaSheetTab = VikaSheetTab.TASKS,
    externalBitmap: Bitmap? = null,
    externalVideoFile: File? = null,
    onCaptureLiveFrame: (suspend () -> Pair<ByteArray, String>?)? = null,
    onCaptureTwoStageFrame: (suspend (detectCrop: Boolean, isolateBg: Boolean) -> CaptureManager.TwoStageAiFrame?)? = null,
    onCaptureOfflineBitmap: (suspend () -> Bitmap?)? = null,
    onStatusMessage: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    BackHandler { onDismiss() }

    var activeTab by remember { mutableStateOf(initialTab) }
    var hasKey by remember { mutableStateOf(ByokManager.hasKey(context)) }
    var selectedModel by remember { mutableStateOf(ByokManager.getModelName(context)) }
    var discoveredModels by remember { mutableStateOf(ByokManager.getDiscoveredModels(context)) }
    var customPrompt by remember { mutableStateOf(ByokManager.getCustomPrompt(context)) }

    var apiKeyInput by remember { mutableStateOf("") }
    var manualModelInput by remember { mutableStateOf("") }
    var modelFilter by remember { mutableStateOf("ALL") }
    var isFetchingModels by remember { mutableStateOf(false) }
    var modelStatusText by remember { mutableStateOf<String?>(null) }

    val hasMediaSource = externalBitmap != null || externalVideoFile != null || onCaptureTwoStageFrame != null || onCaptureLiveFrame != null
    var frameMode by remember {
        mutableStateOf(if (hasMediaSource) AiFrameMode.SYMBIOSIS_CROP else AiFrameMode.TEXT_ONLY)
    }
    var videoEnableCrop by remember { mutableStateOf(true) }
    var taskInput by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var chatFeed by remember { mutableStateOf(ByokManager.getChatHistory()) }
    var aiStats by remember { mutableStateOf(ByokManager.getStats()) }
    var localSnap by remember { mutableStateOf(VaultAnalytics.captureSnapshot(context)) }
    val isOnlineNow = remember(activeTab, isGenerating) { GeminiByokClient.isNetworkOnline(context) }

    fun refreshTelemetry() {
        aiStats = ByokManager.getStats()
        chatFeed = ByokManager.getChatHistory()
        localSnap = VaultAnalytics.captureSnapshot(context)
    }

    fun triggerModelDiscovery(explicitKey: CharArray? = null) {
        if (isFetchingModels) return
        isFetchingModels = true
        modelStatusText = "Запрос моделей к Gemini API..."
        scope.launch {
            val keyToUse = explicitKey ?: ByokManager.decryptKey(context)
            if (keyToUse == null || keyToUse.isEmpty()) {
                isFetchingModels = false
                modelStatusText = "Ключ не найден в KeyStore"
                return@launch
            }
            val result = try {
                GeminiByokClient.fetchAvailableModels(keyToUse)
            } finally {
                Arrays.fill(keyToUse, '0')
            }
            isFetchingModels = false
            result.onSuccess { list ->
                discoveredModels = list
                ByokManager.saveDiscoveredModels(context, list)
                if (list.isNotEmpty() && list.none { it.id == selectedModel }) {
                    val preferred = list.firstOrNull { it.id.contains("hydrogen", ignoreCase = true) }
                        ?: list.firstOrNull { it.id.contains("flash", ignoreCase = true) }
                        ?: list.first()
                    selectedModel = preferred.id
                    ByokManager.saveModelName(context, preferred.id)
                }
                modelStatusText = if (list.isEmpty()) {
                    "Ключ рабочий, но модели generateContent не вернулись"
                } else {
                    "Доступно моделей для ключа: ${list.size}"
                }
                onStatusMessage("МОДЕЛИ ЗАГРУЖЕНЫ: ${list.size}")
            }.onFailure { err ->
                modelStatusText = "Ошибка: ${err.message}"
                onStatusMessage("ОШИБКА КЛЮЧА: ${err.message}")
            }
        }
    }

    suspend fun runOfflineSymbiosisFallback(
        userTask: String,
        fallbackReason: String?,
        precomputedReport: LocalSymbiosisCvEngine.SymbiosisReport? = null
    ) {
        val t0 = System.currentTimeMillis()
        var capturedOfflineBmp: Bitmap? = null
        try {
            val report: LocalSymbiosisCvEngine.SymbiosisReport? = when {
                precomputedReport != null -> precomputedReport
                externalBitmap != null -> LocalSymbiosisCvEngine.analyzeSymbiosis(
                    bitmap = externalBitmap,
                    includeForegroundCoverage = true
                )
                externalVideoFile != null -> {
                    val pkg = VideoKeyframeExtractor.extractForAi(
                        videoFile = externalVideoFile,
                        extractBestRoiCrop = false,
                        applyAntiGlareIfDark = true
                    )
                    pkg.keyframes.forEach { Arrays.fill(it.bytes, 0.toByte()) }
                    pkg.focusCropBytes?.let { Arrays.fill(it, 0.toByte()) }
                    pkg.leadReport
                }
                onCaptureOfflineBitmap != null -> {
                    capturedOfflineBmp = onCaptureOfflineBitmap.invoke()
                    capturedOfflineBmp?.let {
                        LocalSymbiosisCvEngine.analyzeSymbiosis(bitmap = it, includeForegroundCoverage = true)
                    }
                }
                else -> null
            }

            val textOut = if (report != null) {
                LocalSymbiosisCvEngine.buildOfflineVikaReport(report, userTask, fallbackReason)
            } else {
                "⚡ РЕЖИМ МАЛЫШЕЙ ОФЛАЙН: кадра нет под рукой, а текстовый запрос без сети в облако не уйдёт.\n\n${VaultAnalytics.buildTelemetrySummaryForAi(context)}"
            }

            ByokManager.addChatEntry(
                ByokManager.AiChatEntry(
                    modelId = "6-NN ОФЛАЙН",
                    userTask = userTask.ifBlank { "Анализ малышами" },
                    replyText = textOut,
                    hadImage = report != null,
                    latencyMs = System.currentTimeMillis() - t0,
                    totalTokens = 0,
                    isError = false,
                    engineBadge = "⚡ МАЛЫШИ 6-NN ОФЛАЙН"
                )
            )
            refreshTelemetry()
        } catch (e: Exception) {
            ByokManager.addChatEntry(
                ByokManager.AiChatEntry(
                    modelId = "6-NN ОФЛАЙН",
                    userTask = userTask,
                    replyText = "Ошибка локальных малышей: ${e.message}",
                    hadImage = false,
                    latencyMs = System.currentTimeMillis() - t0,
                    totalTokens = 0,
                    isError = true,
                    engineBadge = "⚡ ОШИБКА"
                )
            )
            refreshTelemetry()
        } finally {
            capturedOfflineBmp?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    fun executeTask(
        userTask: String,
        includeImage: Boolean,
        includeAnalytics: Boolean = false,
        forceLocalOnly: Boolean = false,
        systemOverride: String? = null
    ) {
        if (isGenerating) return
        isGenerating = true
        scope.launch {
            var isolatedTempBmp: Bitmap? = null
            val allBuffers = mutableListOf<ByteArray>()
            var cachedLocalReport: LocalSymbiosisCvEngine.SymbiosisReport? = null

            try {
                if (forceLocalOnly) {
                    runOfflineSymbiosisFallback(userTask, fallbackReason = null)
                    return@launch
                }

                val keyChars = ByokManager.decryptKey(context)
                val online = GeminiByokClient.isNetworkOnline(context)
                if (keyChars == null || keyChars.isEmpty() || !online) {
                    keyChars?.let { Arrays.fill(it, '0') }
                    val why = if (!online) "нет сети" else "ключ не задан"
                    runOfflineSymbiosisFallback(userTask, fallbackReason = why)
                    return@launch
                }

                try {
                    var imageBytes: ByteArray? = null
                    var mimeType = "image/webp"
                    val extraCrops = mutableListOf<Pair<ByteArray, String>>()
                    var dossierNote = ""
                    var engineBadge = if (includeImage) "GEMINI ЧИСТЫЙ" else "GEMINI ТЕКСТ"
                    var labelSuffix = ""

                    if (includeImage) {
                        if (externalVideoFile != null) {
                            // Video pipeline from Encrypted Vault (capped at 2 min / 5 keyframes)
                            val vidPkg = VideoKeyframeExtractor.extractForAi(
                                videoFile = externalVideoFile,
                                extractBestRoiCrop = videoEnableCrop,
                                applyAntiGlareIfDark = true
                            )
                            vidPkg.keyframes.forEachIndexed { idx, kf ->
                                allBuffers.add(kf.bytes)
                                if (idx == 0) {
                                    imageBytes = kf.bytes
                                    mimeType = kf.mimeType
                                } else {
                                    extraCrops.add(kf.bytes to kf.mimeType)
                                }
                            }
                            vidPkg.focusCropBytes?.let { cb ->
                                allBuffers.add(cb)
                                extraCrops.add(cb to vidPkg.focusCropMimeType)
                            }
                            cachedLocalReport = vidPkg.leadReport
                            val cvDossier = vidPkg.leadReport?.let {
                                LocalSymbiosisCvEngine.buildCompactGeminiDossier(it)
                            }.orEmpty()
                            val trimNote = if (vidPkg.wasLongVideoTrimmed) {
                                " (длинный ролик ${vidPkg.durationSec}с -> взято ${vidPkg.keyframes.size} ключевых кадров до лимита)"
                            } else {
                                " (${vidPkg.durationSec}с, ${vidPkg.keyframes.size} ключевых кадров)"
                            }
                            dossierNote = "\n[ВИДЕО-АНАЛИЗ$trimNote]\n$cvDossier"
                            engineBadge = "GEMINI + 🎬 ВИДЕО (${vidPkg.keyframes.size}к)"
                            labelSuffix = " (видео ${vidPkg.durationSec}с)"
                        } else if (externalBitmap != null) {
                            when (frameMode) {
                                AiFrameMode.FULL_ONLY -> {
                                    val proc = withContext(Dispatchers.Default) {
                                        ExifStripper.processBitmapClean(
                                            sourceBitmap = externalBitmap,
                                            recycleSource = false,
                                            preset = ExifStripper.CompressionPreset.ULTRA_TINY,
                                            antiPrnu = false
                                        )
                                    }
                                    imageBytes = proc.cleanBytes.also { allBuffers.add(it) }
                                    mimeType = if (proc.isWebP) "image/webp" else "image/jpeg"
                                    engineBadge = "GEMINI ЧИСТЫЙ"
                                }
                                AiFrameMode.BG_ISOLATE -> {
                                    val iso = LocalSymbiosisCvEngine.isolateForegroundPersonClean(externalBitmap, blurBackground = true)
                                    isolatedTempBmp = iso.bitmap
                                    val proc = withContext(Dispatchers.Default) {
                                        ExifStripper.processBitmapClean(
                                            sourceBitmap = iso.bitmap,
                                            recycleSource = false,
                                            preset = ExifStripper.CompressionPreset.ULTRA_TINY,
                                            antiPrnu = false
                                        )
                                    }
                                    imageBytes = proc.cleanBytes.also { allBuffers.add(it) }
                                    mimeType = if (proc.isWebP) "image/webp" else "image/jpeg"
                                    val report = LocalSymbiosisCvEngine.analyzeSymbiosis(externalBitmap, false)
                                    cachedLocalReport = report
                                    dossierNote = "\n${LocalSymbiosisCvEngine.buildCompactGeminiDossier(report)}" +
                                        if (iso.hasForegroundSubject) "\n[Фон размыт локально (SelfieSeg)]" else "\n[Человек не выделен, оставлен полный кадр]"
                                    engineBadge = "GEMINI + ✂️ БЕЗ ФОНА"
                                    labelSuffix = " (без фона)"
                                }
                                AiFrameMode.SYMBIOSIS_CROP -> {
                                    val proc = withContext(Dispatchers.Default) {
                                        ExifStripper.processBitmapClean(
                                            sourceBitmap = externalBitmap,
                                            recycleSource = false,
                                            preset = ExifStripper.CompressionPreset.ULTRA_TINY,
                                            antiPrnu = false
                                        )
                                    }
                                    imageBytes = proc.cleanBytes.also { allBuffers.add(it) }
                                    mimeType = if (proc.isWebP) "image/webp" else "image/jpeg"
                                    val report = LocalSymbiosisCvEngine.analyzeSymbiosis(externalBitmap, false)
                                    cachedLocalReport = report
                                    dossierNote = "\n${LocalSymbiosisCvEngine.buildCompactGeminiDossier(report)}"
                                    val roi = report.bestRoiRegion()
                                    if (roi != null) {
                                        val cropRes = withContext(Dispatchers.Default) {
                                            ExifStripper.extractRegionCropClean(
                                                sourceBitmap = externalBitmap,
                                                normalizedRect = roi,
                                                paddingRatio = 0.40f,
                                                applyAntiGlare = true,
                                                maxCropDim = 640
                                            )
                                        }
                                        if (cropRes != null) {
                                            cropRes.cleanBytes.also {
                                                allBuffers.add(it)
                                                extraCrops.add(it to if (cropRes.isWebP) "image/webp" else "image/jpeg")
                                            }
                                            labelSuffix = " (+кроп: ${report.bestRoiKindLabel()})"
                                        }
                                    }
                                    engineBadge = "GEMINI + 6-NN СИМБИОЗ"
                                }
                                AiFrameMode.TEXT_ONLY -> Unit
                            }
                        } else if (onCaptureTwoStageFrame != null && frameMode != AiFrameMode.TEXT_ONLY) {
                            val needCrop = frameMode == AiFrameMode.SYMBIOSIS_CROP
                            val needIso = frameMode == AiFrameMode.BG_ISOLATE
                            val twoStage = onCaptureTwoStageFrame.invoke(needCrop, needIso)
                            if (twoStage != null) {
                                imageBytes = twoStage.fullBytes.also { allBuffers.add(it) }
                                mimeType = twoStage.fullMimeType
                                twoStage.faceCropBytes?.let { cb ->
                                    allBuffers.add(cb)
                                    extraCrops.add(cb to twoStage.faceCropMimeType)
                                }
                                cachedLocalReport = twoStage.symbiosisReport
                                when (frameMode) {
                                    AiFrameMode.FULL_ONLY -> engineBadge = "GEMINI ЧИСТЫЙ"
                                    AiFrameMode.BG_ISOLATE -> {
                                        engineBadge = "GEMINI + ✂️ БЕЗ ФОНА"
                                        labelSuffix = if (twoStage.backgroundIsolated) " (фон скрыт)" else ""
                                        dossierNote = twoStage.symbiosisReport?.let {
                                            "\n${LocalSymbiosisCvEngine.buildCompactGeminiDossier(it)}"
                                        }.orEmpty()
                                    }
                                    AiFrameMode.SYMBIOSIS_CROP -> {
                                        engineBadge = "GEMINI + 6-NN СИМБИОЗ"
                                        dossierNote = twoStage.symbiosisReport?.let {
                                            "\n${LocalSymbiosisCvEngine.buildCompactGeminiDossier(it)}"
                                        }.orEmpty()
                                        if (twoStage.cropKindLabel != null && extraCrops.isNotEmpty()) {
                                            labelSuffix = " (+кроп: ${twoStage.cropKindLabel})"
                                        }
                                    }
                                    AiFrameMode.TEXT_ONLY -> Unit
                                }
                            }
                        } else if (onCaptureLiveFrame != null && frameMode != AiFrameMode.TEXT_ONLY) {
                            val captured = onCaptureLiveFrame.invoke()
                            if (captured != null) {
                                imageBytes = captured.first.also { allBuffers.add(it) }
                                mimeType = captured.second
                            }
                        }
                    }

                    val fullTask = buildString {
                        append(userTask)
                        if (dossierNote.isNotEmpty()) append(dossierNote)
                        if (includeAnalytics) {
                            append("\n\n")
                            append(VaultAnalytics.buildTelemetrySummaryForAi(context))
                        }
                    }

                    val res = GeminiByokClient.generateContent(
                        apiKeyChars = keyChars,
                        modelId = selectedModel,
                        systemPrompt = systemOverride ?: customPrompt,
                        userPrompt = fullTask,
                        imageBytes = imageBytes,
                        imageMimeType = mimeType,
                        extraImages = extraCrops
                    )

                    res.onSuccess { ok ->
                        ByokManager.addChatEntry(
                            ByokManager.AiChatEntry(
                                modelId = ok.modelId,
                                userTask = "$userTask$labelSuffix",
                                replyText = ok.text,
                                hadImage = imageBytes != null,
                                latencyMs = ok.latencyMs,
                                totalTokens = ok.totalTokens,
                                isError = false,
                                engineBadge = engineBadge
                            )
                        )
                    }.onFailure { err ->
                        val msg = err.message.orEmpty()
                        if (GeminiByokClient.isQuotaOrNetworkFailure(msg) && includeImage) {
                            // Automatic failover to 6-NN local models!
                            runOfflineSymbiosisFallback(
                                userTask = userTask,
                                fallbackReason = "Gemini недоступен: ${msg.take(45)}",
                                precomputedReport = cachedLocalReport
                            )
                            return@launch
                        } else {
                            ByokManager.addChatEntry(
                                ByokManager.AiChatEntry(
                                    modelId = selectedModel,
                                    userTask = "$userTask$labelSuffix",
                                    replyText = "Облом: $msg",
                                    hadImage = imageBytes != null,
                                    latencyMs = 0L,
                                    totalTokens = 0,
                                    isError = true,
                                    engineBadge = "ОШИБКА"
                                )
                            )
                        }
                    }
                    refreshTelemetry()
                } finally {
                    Arrays.fill(keyChars, '0')
                }
            } finally {
                allBuffers.forEach { Arrays.fill(it, 0.toByte()) }
                isolatedTempBmp?.let { if (!it.isRecycled) it.recycle() }
                isGenerating = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SheetBg.copy(alpha = 0.96f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF0D141B), SheetBg)
                        )
                    )
                    .border(1.dp, if (hasKey) CyberCyan.copy(alpha = 0.45f) else SheetBorder, RoundedCornerShape(18.dp))
                    .padding(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(if (hasKey) CyberGreen else CyberAmber)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ВИКА AI // BYOK & АНАЛИТИКА",
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (hasKey) "МОДЕЛЬ: $selectedModel" else "КЛЮЧ НЕ ЗАДАН (ЛОКАЛЬНАЯ СТАТА ДОСТУПНА)",
                            color = if (hasKey) CyberCyan else CyberAmber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (chatFeed.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    ByokManager.clearSessionMemory()
                                    refreshTelemetry()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Очистить память", tint = CyberMuted)
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(48.dp).testTag("vika_sheet_close")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = CyberMuted)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tab bar (48dp touch height)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VikaSheetTab.values().forEach { tab ->
                        val selected = activeTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) CyberCyan else SheetCard)
                                .border(1.dp, if (selected) CyberCyan else SheetBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    activeTab = tab
                                    if (tab == VikaSheetTab.ANALYTICS) refreshTelemetry()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.labelRu,
                                color = if (selected) Color.Black else CyberMuted,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                when (activeTab) {
                    VikaSheetTab.TASKS -> {
                        if (!hasKey || !isOnlineNow) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SheetCard, RoundedCornerShape(12.dp))
                                    .border(1.dp, CyberAmber.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = if (!isOnlineNow) "🔴 ОФЛАЙН -> РАБОТАЮТ 6 МАЛЫШЕЙ" else "⚡ КЛЮЧ НЕ ЗАДАН (ОФЛАЙН-МАЛЫШИ АКТИВНЫ)",
                                    color = CyberAmber,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = if (!isOnlineNow) {
                                        "Интернета нет — жми «⚡ Малыши Офлайн» или любой чип: 6 локальных нейронок разберут кадр/видео без сети."
                                    } else {
                                        "Можно сразу гонять 6 локальных нейронок офлайн или вставить свой Gemini ключ во вкладке «Ключ и Модели»."
                                    },
                                    color = Color.White,
                                    fontSize = 10.sp
                                )
                                if (!hasKey) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Button(
                                        onClick = { activeTab = VikaSheetTab.KEYS_MODELS },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().height(40.dp)
                                    ) {
                                        Text("ВСТАВИТЬ GEMINI КЛЮЧ", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Simple 1-row Frame Mode selector (Video vs 4 Photo modes)
                        if (externalVideoFile != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val vidSel = frameMode != AiFrameMode.TEXT_ONLY
                                Box(
                                    modifier = Modifier
                                        .weight(1.3f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (vidSel) CyberGreen.copy(alpha = 0.2f) else SheetCard)
                                        .border(1.dp, if (vidSel) CyberGreen else SheetBorder, RoundedCornerShape(8.dp))
                                        .clickable { frameMode = AiFrameMode.SYMBIOSIS_CROP },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("🎬 ВИДЕО (≤2м / 5к)", color = if (vidSel) CyberGreen else CyberMuted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                }
                                if (vidSel) {
                                    Box(
                                        modifier = Modifier
                                            .weight(0.9f)
                                            .height(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (videoEnableCrop) CyberAmber.copy(alpha = 0.2f) else SheetCard)
                                            .border(1.dp, if (videoEnableCrop) CyberAmber else SheetBorder, RoundedCornerShape(8.dp))
                                            .clickable { videoEnableCrop = !videoEnableCrop },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(if (videoEnableCrop) "🎯 +КРОП" else "КРОП ВЫКЛ", color = if (videoEnableCrop) CyberAmber else CyberMuted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(0.8f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (!vidSel) CyberCyan.copy(alpha = 0.2f) else SheetCard)
                                        .border(1.dp, if (!vidSel) CyberCyan else SheetBorder, RoundedCornerShape(8.dp))
                                        .clickable { frameMode = AiFrameMode.TEXT_ONLY },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("💬 ТЕКСТ", color = if (!vidSel) CyberCyan else CyberMuted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        } else if (hasMediaSource) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                listOf(
                                    Triple(AiFrameMode.FULL_ONLY, "🖼️ ВСЯ", CyberGreen),
                                    Triple(AiFrameMode.SYMBIOSIS_CROP, "🎯 +КРОП", CyberAmber),
                                    Triple(AiFrameMode.BG_ISOLATE, "✂️ БЕЗ ФОНА", CyberCyan),
                                    Triple(AiFrameMode.TEXT_ONLY, "💬 ТЕКСТ", Color.White)
                                ).forEach { (m, label, accent) ->
                                    val sel = frameMode == m
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) accent.copy(alpha = 0.22f) else SheetCard)
                                            .border(1.dp, if (sel) accent else SheetBorder, RoundedCornerShape(8.dp))
                                            .clickable { frameMode = m },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (sel) accent else CyberMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Quick task chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            QuickTaskChip("⚡ Малыши Офлайн") {
                                executeTask("Полный локальный разбор малышами", includeImage = true, forceLocalOnly = true)
                            }
                            QuickTaskChip("👁️ Разбор кадра") {
                                executeTask("Разбери что на кадре: ключевые детали, риски и чё делать дальше.", includeImage = frameMode != AiFrameMode.TEXT_ONLY)
                            }
                            QuickTaskChip("🛡️ Дозор-триаж") {
                                executeTask("Оцени обстановку на кадре: кто в кадре, форма/гражданка, уровень угрозы.", includeImage = frameMode != AiFrameMode.TEXT_ONLY, systemOverride = ByokManager.PROMPT_SENTINEL)
                            }
                            QuickTaskChip("📝 OCR текста") {
                                executeTask("Вытащи весь текст, даты, цифры и печати без выдумок.", includeImage = frameMode != AiFrameMode.TEXT_ONLY, systemOverride = ByokManager.PROMPT_OCR)
                            }
                            QuickTaskChip("🚗 OSINT-палево") {
                                executeTask("Проверь на палево: номера, вывески, ориентиры, лица, отражения.", includeImage = frameMode != AiFrameMode.TEXT_ONLY, systemOverride = ByokManager.PROMPT_OSINT)
                            }
                            QuickTaskChip("📊 Аудит статы") {
                                executeTask("Сделай краткий аудит нашей текущей статистики и защиты.", includeImage = false, includeAnalytics = true, systemOverride = ByokManager.PROMPT_ANALYTICS)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Chat History Feed (RAM-only)
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (chatFeed.isEmpty()) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.Psychology, contentDescription = "Вика", tint = CyberCyan.copy(alpha = 0.7f), modifier = Modifier.size(44.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Выбери режим сверху:\n• «🖼️ ВСЯ» -> чистый полный разбор только в Gemini\n• «🎯 +КРОП» -> симбиоз 6-NN (кроп лица/объекта + теги) + Gemini\n• «✂️ БЕЗ ФОНА» -> прячет фон перед отправкой\n• Нет сети или сдох лимит -> авто-подхват 6 локальных малышей!",
                                            color = CyberMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            } else {
                                items(chatFeed, key = { it.id }) { msg ->
                                    ChatMessageCard(
                                        entry = msg,
                                        onCopy = {
                                            clipboard.setText(AnnotatedString(msg.replyText))
                                            onStatusMessage("ОТВЕТ СКОПИРОВАН")
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Input bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = taskInput,
                                onValueChange = { taskInput = it },
                                placeholder = { Text("Малая, посмотри чё тут...", color = CyberMuted, fontSize = 12.sp) },
                                maxLines = 3,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberCyan,
                                    unfocusedBorderColor = SheetBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Button(
                                onClick = {
                                    val txt = taskInput.trim()
                                    if (txt.isNotEmpty() && !isGenerating) {
                                        taskInput = ""
                                        executeTask(txt, includeImage = frameMode != AiFrameMode.TEXT_ONLY)
                                    }
                                },
                                enabled = !isGenerating && taskInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(52.dp).testTag("vika_send_btn")
                            ) {
                                if (isGenerating) {
                                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить", tint = Color.White)
                                }
                            }
                        }
                    }

                    VikaSheetTab.ANALYTICS -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Card 1: Vault Storage & Compression Savings
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SheetCard, RoundedCornerShape(12.dp))
                                    .border(1.dp, SheetBorder, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text("📦 СЕЙФ И СЖАТИЕ КОДЕКА", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatTile("ДИСК (.GCF)", "${localSnap.diskFilesCount} шт", VaultAnalytics.formatBytesHuman(localSnap.diskTotalBytes), CyberGreen, Modifier.weight(1f))
                                    StatTile("ОЗУ (RAM)", "${localSnap.ramShotsCount} шт", VaultAnalytics.formatBytesHuman(localSnap.ramTotalBytes), CyberCyan, Modifier.weight(1f))
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                val avgText = if (localSnap.avgPhotoKb > 0) "${localSnap.avgPhotoKb} КБ (экономия ~${localSnap.estimatedSavedPercent}%)" else "нет снятых фото"
                                Text("• Фото / Видео: ${localSnap.diskPhotosCount + localSnap.ramPhotosCount} фото / ${localSnap.diskVideosCount + localSnap.ramVideosCount} видео", color = Color.White, fontSize = 11.sp)
                                Text("• Пресет: ${localSnap.activePreset.labelRu} • Anti-PRNU: ${if (localSnap.antiPrnuActive) "ВКЛ" else "ВЫКЛ"}", color = CyberMuted, fontSize = 11.sp)
                                Text("• Средний кадр: $avgText", color = CyberCyan, fontSize = 11.sp)
                            }

                            // Card 2: Protection Readiness
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SheetCard, RoundedCornerShape(12.dp))
                                    .border(1.dp, SheetBorder, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🛡️ ГОТОВНОСТЬ ЗАЩИТЫ", color = CyberCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(
                                        "${localSnap.readinessScore} / ${localSnap.readinessMax}",
                                        color = if (localSnap.readinessScore >= 5) CyberGreen else CyberAmber,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { localSnap.readinessScore.toFloat() / localSnap.readinessMax.coerceAtLeast(1) },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = if (localSnap.readinessScore >= 5) CyberGreen else CyberAmber,
                                    trackColor = Color(0xFF1A2632)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                localSnap.protections.forEach { check ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(check.label, color = if (check.enabled) Color.White else CyberMuted, fontSize = 11.sp)
                                        Text(
                                            if (check.enabled) "ВКЛ" else "ВЫКЛ",
                                            color = if (check.enabled) CyberGreen else CyberAmber,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }

                            // Card 3: BYOK AI Session Stats
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SheetCard, RoundedCornerShape(12.dp))
                                    .border(1.dp, SheetBorder, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text("⚡ BYOK AI СЕССИЯ", color = CyberCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("• Модель: $selectedModel (доступно по ключу: ${discoveredModels.size})", color = Color.White, fontSize = 11.sp)
                                Text("• Запросы: ${aiStats.queriesCount} (успех: ${aiStats.successCount} / ошибки: ${aiStats.errorCount})", color = CyberMuted, fontSize = 11.sp)
                                Text("• Токены: ${aiStats.totalTokens} (вход ${aiStats.totalPromptTokens} + выход ${aiStats.totalOutputTokens})", color = CyberCyan, fontSize = 11.sp)
                                Text("• Последний пинг API: ${aiStats.lastLatencyMs} мс", color = CyberMuted, fontSize = 11.sp)

                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        activeTab = VikaSheetTab.TASKS
                                        executeTask(
                                            userTask = "Разбери текущую статистику сейфа и настроек защиты: где красавчик, а что стоит дожать?",
                                            includeImage = false,
                                            includeAnalytics = true,
                                            systemOverride = ByokManager.PROMPT_ANALYTICS
                                        )
                                    },
                                    enabled = hasKey && !isGenerating,
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Text("🧠 ВИКА, РАЗБЕРИ СТАТИСТИКУ", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    VikaSheetTab.KEYS_MODELS -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("1. GEMINI API КЛЮЧ (BYOK)", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Шифруется в KeyStore устройства (${if (ByokManager.isStrongBoxSupported(context)) "StrongBox" else "TEE"}). При сохранении сразу подтягивает живой список моделей твоего ключа.",
                                color = CyberMuted,
                                fontSize = 10.sp
                            )

                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text(if (hasKey) "Ключ в KeyStore (введи новый для замены)" else "Вставь Gemini API ключ (AIza...)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberCyan,
                                    unfocusedBorderColor = SheetBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val raw = apiKeyInput.trim()
                                        if (raw.isNotEmpty()) {
                                            val chars = raw.toCharArray()
                                            ByokManager.saveKey(context, chars.clone())
                                            hasKey = true
                                            apiKeyInput = ""
                                            triggerModelDiscovery(chars)
                                        }
                                    },
                                    enabled = !isFetchingModels && apiKeyInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("СОХРАНИТЬ + МОДЕЛИ", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                }

                                if (hasKey) {
                                    Button(
                                        onClick = { triggerModelDiscovery(null) },
                                        enabled = !isFetchingModels,
                                        colors = ButtonDefaults.buttonColors(containerColor = SheetCard),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        if (isFetchingModels) {
                                            CircularProgressIndicator(color = CyberCyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                        } else {
                                            Icon(Icons.Default.Refresh, contentDescription = "Обновить модели", tint = CyberCyan)
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            ByokManager.deleteKey(context)
                                            hasKey = false
                                            discoveredModels = emptyList()
                                            modelStatusText = "Ключ удалён из KeyStore"
                                            refreshTelemetry()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberRed.copy(alpha = 0.85f)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Удалить ключ", tint = Color.White)
                                    }
                                }
                            }

                            modelStatusText?.let { status ->
                                Text(status, color = CyberCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }

                            HorizontalDivider(color = SheetBorder)

                            Text("2. ДОСТУПНЫЕ МОДЕЛИ КЛЮЧА (${discoveredModels.size})", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)

                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("ALL" to "Все", "HYDROGEN" to "Hydrogen", "FLASH" to "Flash", "PRO" to "Pro").forEach { (k, lbl) ->
                                    val sel = modelFilter == k
                                    Text(
                                        text = lbl,
                                        color = if (sel) Color.Black else CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) CyberCyan else SheetCard)
                                            .border(1.dp, if (sel) CyberCyan else SheetBorder, RoundedCornerShape(8.dp))
                                            .clickable { modelFilter = k }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            val filteredModels = remember(discoveredModels, modelFilter) {
                                discoveredModels.filter { m ->
                                    when (modelFilter) {
                                        "HYDROGEN" -> m.id.contains("hydrogen", ignoreCase = true) || m.displayName.contains("hydrogen", ignoreCase = true)
                                        "FLASH" -> m.id.contains("flash", ignoreCase = true)
                                        "PRO" -> m.id.contains("pro", ignoreCase = true)
                                        else -> true
                                    }
                                }
                            }

                            if (filteredModels.isEmpty()) {
                                Text(
                                    text = if (hasKey) "Нажми кнопку обновления (↻) чтобы подтянуть модели ключа, либо впиши ID модели ниже вручную." else "Список подтянется сразу после ввода ключа.",
                                    color = CyberMuted,
                                    fontSize = 10.sp
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    filteredModels.forEach { m ->
                                        val isCur = selectedModel == m.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isCur) CyberGreen.copy(alpha = 0.16f) else SheetCard)
                                                .border(1.dp, if (isCur) CyberGreen else SheetBorder, RoundedCornerShape(10.dp))
                                                .clickable {
                                                    selectedModel = m.id
                                                    ByokManager.saveModelName(context, m.id)
                                                    onStatusMessage("МОДЕЛЬ: ${m.id}")
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = m.id,
                                                    color = if (isCur) CyberGreen else Color.White,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                                Text(
                                                    text = "${m.displayName} • in:${m.inputTokenLimit / 1000}k / out:${m.outputTokenLimit / 1000}k",
                                                    color = CyberMuted,
                                                    fontSize = 9.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (isCur) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = "Выбрано", tint = CyberGreen, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = manualModelInput,
                                    onValueChange = { manualModelInput = it },
                                    label = { Text("Или вписать ID вручную (напр. gemini-4-hydrogen)", fontSize = 10.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CyberCyan,
                                        unfocusedBorderColor = SheetBorder,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                                Button(
                                    onClick = {
                                        val clean = manualModelInput.trim()
                                        if (clean.isNotEmpty()) {
                                            selectedModel = clean
                                            ByokManager.saveModelName(context, clean)
                                            manualModelInput = ""
                                            onStatusMessage("МОДЕЛЬ ЗАДАНА: $clean")
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text("ОК", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                            }

                            HorizontalDivider(color = SheetBorder)

                            // System Prompt section
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("3. ПРОМПТ ВИКИ (ПО УМОЛЧАНИЮ)", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                TextButton(
                                    onClick = {
                                        customPrompt = ByokManager.DEFAULT_PROMPT
                                        ByokManager.saveCustomPrompt(context, customPrompt)
                                        onStatusMessage("ПРОМПТ СБРОШЕН НА БАЗУ ВИКИ")
                                    }
                                ) {
                                    Text("СБРОС", color = CyberCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "Вика База" to ByokManager.DEFAULT_PROMPT,
                                    "Дозор" to ByokManager.PROMPT_SENTINEL,
                                    "OSINT" to ByokManager.PROMPT_OSINT,
                                    "OCR" to ByokManager.PROMPT_OCR,
                                    "Форензик" to ByokManager.PROMPT_FORENSIC,
                                    "Аналитик" to ByokManager.PROMPT_ANALYTICS
                                ).forEach { (title, pText) ->
                                    Text(
                                        text = title,
                                        color = CyberCyan,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(CyberCyan.copy(alpha = 0.14f))
                                            .border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                            .clickable {
                                                customPrompt = pText
                                                ByokManager.saveCustomPrompt(context, pText)
                                                onStatusMessage("ПРОМПТ: $title")
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = customPrompt,
                                onValueChange = {
                                    customPrompt = it
                                    ByokManager.saveCustomPrompt(context, it)
                                },
                                modifier = Modifier.fillMaxWidth().height(130.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberGreen,
                                    unfocusedBorderColor = SheetBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTaskChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SheetCard)
            .border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = CyberCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun StatTile(
    title: String,
    value: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF0A1016), RoundedCornerShape(10.dp))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(title, color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(subtitle, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
private fun ChatMessageCard(
    entry: ByokManager.AiChatEntry,
    onCopy: () -> Unit
) {
    val timeStr = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SheetCard, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (entry.isError) CyberRed.copy(alpha = 0.6f) else CyberGreen.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${if (entry.hadImage) "📸 " else ""}${entry.userTask}",
                color = CyberCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = timeStr,
                color = CyberMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = entry.replyText,
            color = if (entry.isError) CyberRed else Color.White,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[${entry.engineBadge}] • ${entry.modelId} • ${entry.latencyMs}мс • ${entry.totalTokens}t",
                color = CyberMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "КОПИРОВАТЬ",
                color = CyberGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}
