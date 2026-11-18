package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import java.util.Arrays

private val SheetBg = Color(0xFF070B0E)
private val SheetCard = Color(0xFF10171E)
private val SheetBorder = Color(0xFF20303D)
private val CyberGreen = Color(0xFF00FF66)
private val CyberCyan = Color(0xFF00E5FF)
private val CyberRed = Color(0xFFFF1744)
private val CyberAmber = Color(0xFFFFC107)
private val CyberMuted = Color(0xFF8FA3B0)

enum class VikaSheetTab(val labelRu: String) {
    TASKS("🧠 Задачи"),
    ANALYTICS("📊 Аналитика"),
    KEYS_MODELS("🔑 Ключ и Модели")
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
    val scope = rememberCoroutineScope()

    BackHandler { onDismiss() }

    var activeTab by remember { mutableStateOf(initialTab) }
    val hasKey by ByokManager.hasKeyFlow.collectAsState()
    val selectedModel by ByokManager.selectedModelFlow.collectAsState()
    val discoveredModels by ByokManager.discoveredModelsFlow.collectAsState()
    val customPrompt by ByokManager.customPromptFlow.collectAsState()
    val isFetchingModels by ByokManager.isFetchingModelsFlow.collectAsState()
    val modelStatusText by ByokManager.modelFetchStatusFlow.collectAsState()
    val chatFeed by ByokManager.chatHistoryFlow.collectAsState()
    val aiStats by ByokManager.sessionStatsFlow.collectAsState()
    val httpLogs by ByokManager.httpLogsFlow.collectAsState()

    val hasMediaSource = externalBitmap != null || externalVideoFile != null || onCaptureTwoStageFrame != null || onCaptureLiveFrame != null
    var frameMode by remember {
        mutableStateOf(if (hasMediaSource) AiFrameMode.SYMBIOSIS_CROP else AiFrameMode.TEXT_ONLY)
    }
    var videoEnableCrop by remember { mutableStateOf(true) }
    var taskInput by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var isCapturingPreview by remember { mutableStateOf(false) }
    var stagedPreview by remember { mutableStateOf(VikaStagedPreviewState()) }

    var isStrongBoxSupported by remember { mutableStateOf(false) }
    var localSnap by remember {
        mutableStateOf(
            VaultAnalytics.LocalAnalyticsSnapshot(
                diskFilesCount = 0,
                diskPhotosCount = 0,
                diskVideosCount = 0,
                diskTotalBytes = 0L,
                ramShotsCount = 0,
                ramPhotosCount = 0,
                ramVideosCount = 0,
                ramTotalBytes = 0L,
                avgPhotoKb = 0,
                estimatedSavedPercent = 0,
                activePreset = com.example.security.ZverPrefs.getCompressionPreset(context),
                ramOnlyActive = false,
                antiPrnuActive = false,
                protections = emptyList(),
                readinessScore = 0,
                readinessMax = 7
            )
        )
    }
    val isOnlineNow = remember(activeTab, isGenerating, hasKey) { GeminiByokClient.isNetworkOnline(context) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ByokManager.syncFromPrefs(context)
            val snap = VaultAnalytics.captureSnapshot(context)
            val sb = ByokManager.isStrongBoxSupported(context)
            withContext(Dispatchers.Main) {
                localSnap = snap
                isStrongBoxSupported = sb
            }
        }
    }

    fun clearStagedPreview() {
        val current = stagedPreview
        current.bitmap?.let { if (!it.isRecycled) it.recycle() }
        current.bytes?.let { Arrays.fill(it, 0.toByte()) }
        current.cropBytes?.let { Arrays.fill(it, 0.toByte()) }
        stagedPreview = VikaStagedPreviewState()
    }

    DisposableEffect(Unit) {
        onDispose {
            clearStagedPreview()
        }
    }

    fun refreshTelemetryAsync() {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                VaultAnalytics.captureSnapshot(context)
            }
            localSnap = snap
        }
    }

    fun triggerModelDiscovery(explicitKey: CharArray? = null) {
        ByokManager.refreshModelsInBackground(context, explicitKey) { res ->
            res.onSuccess { list ->
                onStatusMessage("МОДЕЛИ ЗАГРУЖЕНЫ: ${list.size}")
            }.onFailure { err ->
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
                externalBitmap != null -> withContext(Dispatchers.Default) {
                    LocalSymbiosisCvEngine.analyzeSymbiosis(
                        bitmap = externalBitmap,
                        includeForegroundCoverage = true
                    )
                }
                externalVideoFile != null -> withContext(Dispatchers.IO) {
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
                    capturedOfflineBmp?.let { bmp ->
                        withContext(Dispatchers.Default) {
                            LocalSymbiosisCvEngine.analyzeSymbiosis(bitmap = bmp, includeForegroundCoverage = true)
                        }
                    }
                }
                else -> null
            }

            val textOut = withContext(Dispatchers.Default) {
                if (report != null) {
                    LocalSymbiosisCvEngine.buildOfflineVikaReport(report, userTask, fallbackReason)
                } else {
                    "⚡ РЕЖИМ МАЛЫШЕЙ ОФЛАЙН: кадра нет под рукой, а текстовый запрос без сети в облако не уйдёт.\n\n${VaultAnalytics.buildTelemetrySummaryForAi(context)}"
                }
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
            refreshTelemetryAsync()
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
            refreshTelemetryAsync()
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

                val keyChars = withContext(Dispatchers.IO) { ByokManager.decryptKey(context) }
                val online = withContext(Dispatchers.IO) { GeminiByokClient.isNetworkOnline(context) }
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
                            val vidPkg = withContext(Dispatchers.IO) {
                                VideoKeyframeExtractor.extractForAi(
                                    videoFile = externalVideoFile,
                                    extractBestRoiCrop = videoEnableCrop,
                                    applyAntiGlareIfDark = true
                                )
                            }
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
                                    val iso = withContext(Dispatchers.Default) {
                                        LocalSymbiosisCvEngine.isolateForegroundPersonClean(externalBitmap, blurBackground = true)
                                    }
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
                                    val report = withContext(Dispatchers.Default) {
                                        LocalSymbiosisCvEngine.analyzeSymbiosis(externalBitmap, false)
                                    }
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
                                    val report = withContext(Dispatchers.Default) {
                                        LocalSymbiosisCvEngine.analyzeSymbiosis(externalBitmap, false)
                                    }
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
                        } else if (stagedPreview.bytes != null && stagedPreview.mode == frameMode && frameMode != AiFrameMode.TEXT_ONLY) {
                            val bytesCopy = stagedPreview.bytes!!.clone()
                            imageBytes = bytesCopy.also { allBuffers.add(it) }
                            mimeType = stagedPreview.mime
                            stagedPreview.cropBytes?.let { cb ->
                                val cCopy = cb.clone()
                                allBuffers.add(cCopy)
                                extraCrops.add(cCopy to stagedPreview.cropMime)
                            }
                            cachedLocalReport = stagedPreview.report
                            dossierNote = stagedPreview.report?.let {
                                "\n${LocalSymbiosisCvEngine.buildCompactGeminiDossier(it)}"
                            }.orEmpty()
                            engineBadge = when (frameMode) {
                                AiFrameMode.FULL_ONLY -> "GEMINI ЧИСТЫЙ (ПРЕВЬЮ)"
                                AiFrameMode.BG_ISOLATE -> "GEMINI + ✂️ БЕЗ ФОНА (ПРЕВЬЮ)"
                                AiFrameMode.SYMBIOSIS_CROP -> "GEMINI + 6-NN СИМБИОЗ (ПРЕВЬЮ)"
                                AiFrameMode.TEXT_ONLY -> "GEMINI ТЕКСТ"
                            }
                            labelSuffix = stagedPreview.note
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

                    val analyticsText = if (includeAnalytics) {
                        withContext(Dispatchers.IO) { VaultAnalytics.buildTelemetrySummaryForAi(context) }
                    } else {
                        ""
                    }
                    val fullTask = buildString {
                        append(userTask)
                        if (dossierNote.isNotEmpty()) append(dossierNote)
                        if (analyticsText.isNotEmpty()) {
                            append("\n\n")
                            append(analyticsText)
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
                    refreshTelemetryAsync()
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

    fun capturePreviewFrame() {
        if (isCapturingPreview || isGenerating) return
        isCapturingPreview = true
        scope.launch {
            try {
                clearStagedPreview()
                if (onCaptureTwoStageFrame != null) {
                    val needCrop = frameMode == AiFrameMode.SYMBIOSIS_CROP
                    val needIso = frameMode == AiFrameMode.BG_ISOLATE
                    val two = onCaptureTwoStageFrame.invoke(needCrop, needIso)
                    if (two != null) {
                        val bmp = withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(two.fullBytes, 0, two.fullBytes.size)
                        }
                        stagedPreview = VikaStagedPreviewState(
                            bytes = two.fullBytes,
                            mime = two.fullMimeType,
                            cropBytes = two.faceCropBytes,
                            cropMime = two.faceCropMimeType,
                            bitmap = bmp,
                            report = two.symbiosisReport,
                            note = if (two.cropKindLabel != null && two.faceCropBytes != null) " • +кроп: ${two.cropKindLabel}" else "",
                            mode = frameMode
                        )
                    }
                } else if (onCaptureLiveFrame != null) {
                    val cap = onCaptureLiveFrame.invoke()
                    if (cap != null) {
                        val bmp = withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(cap.first, 0, cap.first.size)
                        }
                        stagedPreview = VikaStagedPreviewState(
                            bytes = cap.first,
                            mime = cap.second,
                            bitmap = bmp,
                            mode = frameMode
                        )
                    }
                }
            } finally {
                isCapturingPreview = false
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
                                    refreshTelemetryAsync()
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
                                    if (tab == VikaSheetTab.ANALYTICS) refreshTelemetryAsync()
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
                        VikaTasksTabContent(
                            context = context,
                            hasKey = hasKey,
                            isOnlineNow = isOnlineNow,
                            hasMediaSource = hasMediaSource,
                            externalBitmap = externalBitmap,
                            externalVideoFile = externalVideoFile,
                            frameMode = frameMode,
                            onFrameModeChange = { frameMode = it },
                            videoEnableCrop = videoEnableCrop,
                            onVideoCropToggle = { videoEnableCrop = !videoEnableCrop },
                            stagedPreview = stagedPreview,
                            isCapturingPreview = isCapturingPreview,
                            isGenerating = isGenerating,
                            chatFeed = chatFeed,
                            taskInput = taskInput,
                            onTaskInputChange = { taskInput = it },
                            onClearStagedPreview = { clearStagedPreview() },
                            onCapturePreview = { capturePreviewFrame() },
                            onSwitchToKeysTab = { activeTab = VikaSheetTab.KEYS_MODELS },
                            onExecuteTask = { task, img, stats, localOnly, sys ->
                                executeTask(
                                    userTask = task,
                                    includeImage = img,
                                    includeAnalytics = stats,
                                    forceLocalOnly = localOnly,
                                    systemOverride = sys
                                )
                            },
                            onStatusMessage = onStatusMessage
                        )
                    }

                    VikaSheetTab.ANALYTICS -> {
                        VikaAnalyticsTabContent(
                            localSnap = localSnap,
                            selectedModel = selectedModel,
                            discoveredModelsCount = discoveredModels.size,
                            aiStats = aiStats,
                            hasKey = hasKey,
                            isOnlineNow = isOnlineNow,
                            isGenerating = isGenerating,
                            onAnalyzeStatsClick = {
                                activeTab = VikaSheetTab.TASKS
                                if (hasKey && isOnlineNow) {
                                    executeTask(
                                        userTask = "Разбери текущую статистику сейфа и настроек защиты: где красавчик, а что стоит дожать?",
                                        includeImage = false,
                                        includeAnalytics = true,
                                        systemOverride = ByokManager.PROMPT_ANALYTICS
                                    )
                                } else {
                                    scope.launch {
                                        val offlineSummary = withContext(Dispatchers.IO) {
                                            VaultAnalytics.buildTelemetrySummaryForAi(context)
                                        }
                                        ByokManager.addChatEntry(
                                            ByokManager.AiChatEntry(
                                                modelId = "ОФЛАЙН ТЕЛЕМЕТРИЯ",
                                                userTask = "Аудит статистики сейфа и защиты (без сети/ключа)",
                                                replyText = offlineSummary,
                                                hadImage = false,
                                                latencyMs = 0L,
                                                totalTokens = 0,
                                                isError = false,
                                                engineBadge = "📊 ЛОКАЛЬНАЯ СТАТИСТИКА"
                                            )
                                        )
                                        refreshTelemetryAsync()
                                    }
                                }
                            }
                        )
                    }

                    VikaSheetTab.KEYS_MODELS -> {
                        VikaKeysModelsTabContent(
                            context = context,
                            hasKey = hasKey,
                            isStrongBoxSupported = isStrongBoxSupported,
                            selectedModel = selectedModel,
                            discoveredModels = discoveredModels,
                            customPrompt = customPrompt,
                            isFetchingModels = isFetchingModels,
                            modelStatusText = modelStatusText,
                            httpLogs = httpLogs,
                            onTriggerModelDiscovery = { explicit -> triggerModelDiscovery(explicit) },
                            onDeleteKey = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        ByokManager.deleteKey(context)
                                    }
                                    refreshTelemetryAsync()
                                }
                            },
                            onStatusMessage = onStatusMessage
                        )
                    }
                }
            }
        }
    }
}
