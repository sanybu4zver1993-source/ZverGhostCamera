package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.assist.ByokManager
import com.example.assist.GeminiByokClient
import com.example.assist.GhostAssistService
import com.example.camera.AntiBlurSensorEngine
import com.example.camera.CaptureManager
import com.example.camera.Mp4Sanitizer
import com.example.crypto.GhostCryptoVault
import com.example.security.AntiForensicsGuard
import com.example.security.CamouflageManager
import com.example.security.SentinelEngine
import com.example.security.ZverPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs

private val CyberBlack = Color(0xFF070B0E)
private val CyberGreen = Color(0xFF00FF66)
private val CyberGreenDark = Color(0xFF008F39)
private val CyberRed = Color(0xFFFF1744)
private val CyberMuted = Color(0xFF8FA3B0)
private val CyberSurface = Color(0xFF111820)
private val CyberBorder = Color(0xFF223240)
private val NeonCyan = Color(0xFF00E5FF)
private val NeonYellow = Color(0xFFFFEA00)

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
@Composable
fun GhostCameraScreen(
    onQuickExitToCamouflage: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }

    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val antiBlurEngine = remember { AntiBlurSensorEngine(context) }

    var liveLaplacianVariance by remember { mutableStateOf(0.0) }
    var peakingPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var overexposedPercent by remember { mutableStateOf(0) }
    var underexposedPercent by remember { mutableStateOf(0) }
    var isProximityLocked by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var lastStatusMessage by remember { mutableStateOf<String?>(null) }
    var showShutterFlash by remember { mutableStateOf(false) }
    var vaultCount by remember { mutableStateOf(GhostCryptoVault.totalVaultItemsCount(context)) }
    var showVaultDialog by remember { mutableStateOf(false) }
    var forcePinSetupFlow by remember { mutableStateOf(false) }
    var showPanicConfirm by remember { mutableStateOf(false) }
    var showCamouflageDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showJellyTankScreen by remember { mutableStateOf(false) }
    var isToolsDrawerOpen by remember { mutableStateOf(false) }
    var showVikaSheet by remember { mutableStateOf(false) }
    var vikaSheetInitialTab by remember { mutableStateOf(VikaSheetTab.TASKS) }
    var isVoiceControlActive by remember { mutableStateOf(false) }
    var previousCameraMode by remember { mutableStateOf("PHOTO") }

    // Sentinel v5.0 ("КИБЕР-ДОЗОР") States
    var sentinelArmed by remember { mutableStateOf(SentinelEngine.isArmed()) }
    var sentinelConfig by remember { mutableStateOf(SentinelEngine.getConfig()) }
    var sentinelMotionScore by remember { mutableStateOf(0) }
    var sentinelAvgLuma by remember { mutableStateOf(128) }
    var sentinelIsNight by remember { mutableStateOf(false) }
    var sentinelStepLabel by remember { mutableStateOf("ОЖИДАНИЕ") }
    var sentinelEvents by remember { mutableStateOf(SentinelEngine.getEvents()) }
    var previousMotionGrid by remember { mutableStateOf<IntArray?>(null) }
    var sentinelJobActive by remember { mutableStateOf(false) }

    // MIUI-style Camera Mode Carousel
    var selectedCameraMode by remember { mutableStateOf("PHOTO") }

    // Video Recording States
    var videoCapture by remember { mutableStateOf<androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<androidx.camera.video.Recording?>(null) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var recordingDurationSeconds by remember { mutableStateOf(0) }

    LaunchedEffect(isRecordingVideo) {
        if (isRecordingVideo) {
            recordingDurationSeconds = 0
            while (isRecordingVideo) {
                delay(1000)
                recordingDurationSeconds++
            }
        }
    }

    val securityStatus = remember { AntiForensicsGuard.assessDeviceSecurity(context) }
    var currentProfile by remember { mutableStateOf(GhostCryptoVault.activeProfile) }
    var currentCamouflage by remember { mutableStateOf(CamouflageManager.getCurrentCamouflage(context)) }

    // Architecture v2.0/3.4: Process isolation toggle (:core offline vs :assist online)
    var isAssistModeActive by remember { mutableStateOf(false) }

    // Computational & Hardware Sensor States
    var timerSeconds by remember { mutableStateOf(0) }
    var burstCount by remember { mutableStateOf(1) }
    var countdownRemaining by remember { mutableStateOf<Int?>(null) }
    var focusTapPoint by remember { mutableStateOf<Offset?>(null) }
    var isFocusLocked by remember { mutableStateOf(false) }
    var exposureIndex by remember { mutableStateOf(0) }
    var zoomRatio by remember { mutableStateOf(1.0f) }

    // OSINT Engineering Modes
    var isFocusPeakingEnabled by remember { mutableStateOf(false) }
    var isFalseColorEnabled by remember { mutableStateOf(false) }
    var isAutoShutterEnabled by remember { mutableStateOf(false) }
    var isStealthBlackoutActive by remember { mutableStateOf(false) }
    var stealthCoverMode by remember { mutableStateOf(StealthCoverMode.BLACKOUT) }
    var isDcpDehazeActive by remember { mutableStateOf(false) }
    var isDocumentModeActive by remember { mutableStateOf(false) }
    var isFocusBracketingActive by remember { mutableStateOf(false) }
    var isTorchActive by remember { mutableStateOf(false) }

    LaunchedEffect(isTorchActive) {
        try { cameraControl?.enableTorch(isTorchActive) } catch (_: Exception) {}
    }

    // Live Sensor Telemetry
    var gyroAngularSpeed by remember { mutableStateOf(0f) }
    var isGyroStable by remember { mutableStateOf(true) }
    var isTripodMode by remember { mutableStateOf(false) }
    var documentTiltDeg by remember { mutableStateOf(0f) }
    var isDocumentParallel by remember { mutableStateOf(false) }

    // Helper: Haptic feedback for tactile confirmation
    fun triggerHapticFeedback(isStealth: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val duration = if (isStealth) 35L else 70L
                vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(if (isStealth) 35L else 70L)
            }
        } catch (_: Exception) {}
    }

    // Screen brightness control for Stealth Blackout Mode
    val activity = context as? Activity
    DisposableEffect(isStealthBlackoutActive) {
        if (isStealthBlackoutActive) {
            val lp = activity?.window?.attributes
            val originalBrightness = lp?.screenBrightness ?: -1f
            lp?.screenBrightness = 0.01f
            activity?.window?.attributes = lp
            onDispose {
                val restoreLp = activity?.window?.attributes
                restoreLp?.screenBrightness = originalBrightness
                activity?.window?.attributes = restoreLp
            }
        } else {
            onDispose {}
        }
    }

    // Pulsating warning animation for Assist Mode (Red Neon Perimeter)
    val infiniteTransition = rememberInfiniteTransition(label = "assistPulse")
    val redBorderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "redBorderAlpha"
    )

    // Sensor engine lifecycle (with Snatch Lock & Flip-to-Disguise hooks)
    DisposableEffect(Unit) {
        antiBlurEngine.onSnatchDetected = {
            if (ZverPrefs.isSnatchProtectionEnabled(context)) {
                scope.launch(Dispatchers.Main) {
                    GhostCryptoVault.lockSession()
                    ByokManager.clearSessionMemory()
                    SentinelEngine.clearSession()
                    sentinelArmed = false
                    sentinelEvents = emptyList()
                    showVaultDialog = false
                    showSettingsDialog = false
                    showJellyTankScreen = false
                    showVikaSheet = false
                    isToolsDrawerOpen = false
                    isStealthBlackoutActive = true
                    lastStatusMessage = "⚡ SNATCH LOCK: ДОСТУП ЗАБЛОКИРОВАН"
                    onQuickExitToCamouflage()
                }
            }
        }
        antiBlurEngine.onFaceDownDetected = {
            if (ZverPrefs.isFlipToDisguiseEnabled(context)) {
                scope.launch(Dispatchers.Main) {
                    if (!isStealthBlackoutActive) {
                        if (selectedCameraMode != "STEALTH") previousCameraMode = selectedCameraMode
                        isStealthBlackoutActive = true
                    }
                }
            }
        }
        antiBlurEngine.start()
        onDispose {
            antiBlurEngine.onSnatchDetected = null
            antiBlurEngine.onFaceDownDetected = null
            antiBlurEngine.onKnockDetected = null
            antiBlurEngine.stop()
            captureExecutor.shutdown()
            analysisExecutor.shutdown()
            MainActivity.onVolumeShutterTrigger = null
        }
    }

    fun launchSentinelCapture(reason: String, useNightClahe: Boolean) {
        val cap = imageCapture ?: return
        if (sentinelJobActive || isCapturing) return
        sentinelJobActive = true
        scope.launch {
            try {
                triggerHapticFeedback(isStealth = true)
                val manager = CaptureManager(
                    context = context,
                    imageCapture = cap,
                    captureExecutor = captureExecutor
                )
                val shotRes = manager.takeSecurePhoto(applyDehaze = useNightClahe)
                vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                lastStatusMessage = "🛡️ ДОЗОР: СНЯТ В ${if (shotRes.isRamOnly) "ОЗУ" else "СЕЙФ"} (${shotRes.encryptedSizeBytes / 1024}КБ)"

                val cfg = SentinelEngine.getConfig()
                if (cfg.twoStageFaceCropEnabled || (cfg.aiTriageEnabled && ByokManager.hasKey(context))) {
                    val twoStage = manager.captureTwoStageFrameForAi(
                        applyClahe = useNightClahe,
                        detectFaceCrop = cfg.twoStageFaceCropEnabled
                    )
                    try {
                        val sym = twoStage.symbiosisReport
                        if (twoStage.detectedFacesCount > 0 || (sym != null && (sym.objects.isNotEmpty() || sym.pose.detected))) {
                            val tagsStr = sym?.sceneTags?.take(3)?.joinToString { it.label }.orEmpty()
                            val poseStr = if (sym?.pose?.detected == true) " | ${sym.pose.postureRu}" else ""
                            SentinelEngine.logEvent(
                                SentinelEngine.EventKind.INFO,
                                "6-NN Дозор: лиц=${twoStage.detectedFacesCount}$poseStr${if (tagsStr.isNotBlank()) " [$tagsStr]" else ""}",
                                facesCount = twoStage.detectedFacesCount
                            )
                        }
                        if (cfg.aiTriageEnabled && ByokManager.hasKey(context) && GeminiByokClient.isNetworkOnline(context)) {
                            val keyChars = ByokManager.decryptKey(context)
                            if (keyChars != null && keyChars.isNotEmpty()) {
                                try {
                                    val extras = twoStage.faceCropBytes?.let {
                                        listOf(it to twoStage.faceCropMimeType)
                                    } ?: emptyList()
                                    val dossier = sym?.let { "\n${com.example.privacy.LocalSymbiosisCvEngine.buildCompactGeminiDossier(it)}" }.orEmpty()
                                    val aiRes = GeminiByokClient.generateContent(
                                        apiKeyChars = keyChars,
                                        modelId = ByokManager.getModelName(context),
                                        systemPrompt = ByokManager.PROMPT_SENTINEL,
                                        userPrompt = "Событие дозора: $reason. Лиц обнаружено локально: ${twoStage.detectedFacesCount}. Оцени сцену кратко и строго по фактам.$dossier",
                                        imageBytes = twoStage.fullBytes,
                                        imageMimeType = twoStage.fullMimeType,
                                        extraImages = extras
                                    )
                                    aiRes.onSuccess { ok ->
                                        SentinelEngine.logEvent(
                                            SentinelEngine.EventKind.AI_VERDICT,
                                            ok.text,
                                            facesCount = twoStage.detectedFacesCount
                                        )
                                        ByokManager.addChatEntry(
                                            ByokManager.AiChatEntry(
                                                modelId = ok.modelId,
                                                userTask = "[ДОЗОР] $reason",
                                                replyText = ok.text,
                                                hadImage = true,
                                                latencyMs = ok.latencyMs,
                                                totalTokens = ok.totalTokens,
                                                engineBadge = "GEMINI + 6-NN ДОЗОР"
                                            )
                                        )
                                    }.onFailure { err ->
                                        SentinelEngine.logEvent(
                                            SentinelEngine.EventKind.INFO,
                                            "Вика AI офлайн/лимит (${err.message}): сохранён разбор 6-NN"
                                        )
                                    }
                                } finally {
                                    Arrays.fill(keyChars, '0')
                                }
                            }
                        }
                    } finally {
                        Arrays.fill(twoStage.fullBytes, 0.toByte())
                        twoStage.faceCropBytes?.let { Arrays.fill(it, 0.toByte()) }
                    }
                }
            } catch (e: Exception) {
                SentinelEngine.logEvent(SentinelEngine.EventKind.INFO, "Сбой съёмки дозора: ${e.message}")
            } finally {
                sentinelEvents = SentinelEngine.getEvents()
                sentinelJobActive = false
            }
        }
    }

    LaunchedEffect(Unit) {
        antiBlurEngine.onKnockDetected = { mag ->
            if (SentinelEngine.evaluateKnockImpulse(System.currentTimeMillis(), mag)) {
                scope.launch(Dispatchers.Main) {
                    sentinelEvents = SentinelEngine.getEvents()
                    sentinelStepLabel = "⚡ СТУК / УДАР!"
                    val cfg = SentinelEngine.getConfig()
                    launchSentinelCapture(
                        reason = "Стук/вибрация (${String.format("%.1f", mag)} м/с²)",
                        useNightClahe = cfg.autoNightClaheEnabled && SentinelEngine.isNightProfileActive()
                    )
                }
            }
        }
    }

    // Asynchronous 1GB Monolithic Container initialization
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            com.example.crypto.MonoVaultEngine.ensureContainerInitialized(context)
        }
    }

    // Telemetry polling loop
    LaunchedEffect(Unit) {
        while (true) {
            gyroAngularSpeed = antiBlurEngine.currentAngularSpeed
            isGyroStable = antiBlurEngine.isStable
            isTripodMode = antiBlurEngine.isTripodMode
            documentTiltDeg = antiBlurEngine.currentTiltDeg
            isDocumentParallel = antiBlurEngine.isDocumentParallel
            isProximityLocked = isStealthBlackoutActive && antiBlurEngine.isProximityNear
            delay(100)
        }
    }

    // Handle :assist process service binding/lifecycle
    LaunchedEffect(isAssistModeActive) {
        val intent = Intent(context, GhostAssistService::class.java)
        if (isAssistModeActive) {
            context.startService(intent)
        } else {
            context.stopService(intent)
        }
    }

    // Execution of photo capture
    fun triggerPhotoCapture() {
        val cap = imageCapture ?: return
        if (isCapturing || countdownRemaining != null) return

        scope.launch {
            if (timerSeconds > 0) {
                var toneGen: ToneGenerator? = null
                try {
                    toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                } catch (_: Exception) {}

                for (s in timerSeconds downTo 1) {
                    countdownRemaining = s
                    toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    delay(1000)
                }
                countdownRemaining = null
                toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                toneGen?.release()
            }

            // Tactile feedback: buzz so user knows the shutter triggered
            triggerHapticFeedback(isStealth = isStealthBlackoutActive)

            // Visual shutter flash if not in total blackout
            if (!isStealthBlackoutActive) {
                showShutterFlash = true
                launch {
                    delay(120)
                    showShutterFlash = false
                }
            }

            isCapturing = true
            try {
                if (isFocusBracketingActive) {
                    lastStatusMessage = "СЕРИЯ БРЕКЕТИНГА: 3 КАДРА..."
                    for (step in 1..3) {
                        val manager = CaptureManager(
                            context = context,
                            imageCapture = cap,
                            captureExecutor = captureExecutor
                        )
                        manager.takeSecurePhoto(applyDehaze = isDcpDehazeActive)
                        vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                        delay(250)
                    }
                    lastStatusMessage = "📸 СЕРИЯ ИЗ 3 КАДРОВ СОХРАНЕНА В СЕЙФ!"
                } else {
                    for (shotIdx in 1..burstCount) {
                        val manager = CaptureManager(
                            context = context,
                            imageCapture = cap,
                            captureExecutor = captureExecutor
                        )
                        val result = manager.takeSecurePhoto(applyDehaze = isDcpDehazeActive)
                        vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                        val kb = result.encryptedSizeBytes / 1024
                        val contourTag = if (result.isRamOnly) " [ОЗУ]" else ""
                        val dehazeTag = if (isDcpDehazeActive) " [АНТИ-ТУМАН]" else ""
                        lastStatusMessage = "📸 ${result.formatLabel} (${kb}КБ, 0 EXIF) за ${result.durationMs}мс$contourTag$dehazeTag"

                        if (shotIdx < burstCount) {
                            delay(1000)
                        }
                    }
                }
            } catch (e: Exception) {
                lastStatusMessage = "ОШИБКА СЪЁМКИ: ${e.localizedMessage ?: "Сбой"}"
            } finally {
                isCapturing = false
                // Auto-clear notification after 3.5 seconds
                launch {
                    delay(3500)
                    if (!isCapturing && lastStatusMessage?.startsWith("📸") == true) {
                        lastStatusMessage = null
                    }
                }
            }
        }
    }

    fun startVideoRecordingInternal() {
        val vCap = videoCapture
        if (vCap == null) {
            lastStatusMessage = "ИНИЦИАЛИЗАЦИЯ ВИДЕО..."
            return
        }

        if (isRecordingVideo) {
            try {
                activeRecording?.stop()
                activeRecording = null
                isRecordingVideo = false
                triggerHapticFeedback(isStealthBlackoutActive)
                lastStatusMessage = "⏳ ШИФРОВАНИЕ ВИДЕО В СЕЙФ..."
            } catch (e: Exception) {
                lastStatusMessage = "СБОЙ ОСТАНОВКИ: ${e.message}"
            }
        } else {
            val tempVideoFile = File(context.cacheDir, "raw_video_${System.currentTimeMillis()}.mp4")
            val outputOptions = androidx.camera.video.FileOutputOptions.Builder(tempVideoFile).build()

            try {
                val pendingRec = vCap.output.prepareRecording(context, outputOptions)
                val muteAudio = ZverPrefs.isMuteVideoAudio(context)
                val hasAudio = !muteAudio && ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasAudio) {
                    pendingRec.withAudioEnabled()
                }

                val rec = pendingRec.start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is androidx.camera.video.VideoRecordEvent.Start -> {
                            isRecordingVideo = true
                            recordingDurationSeconds = 0
                            triggerHapticFeedback(isStealthBlackoutActive)
                            lastStatusMessage = if (hasAudio) "🔴 ИДЁТ ЗАПИСЬ ВИДЕО..." else "🔴 ЗАПИСЬ ВИДЕО (БЕЗ ЗВУКА)"
                        }
                        is androidx.camera.video.VideoRecordEvent.Finalize -> {
                            isRecordingVideo = false
                            if (!event.hasError()) {
                                scope.launch(Dispatchers.IO) {
                                    val cleanVideoFile = File(context.cacheDir, "clean_video_${System.currentTimeMillis()}.mp4")
                                    try {
                                        val sanitized = Mp4Sanitizer.sanitizeMp4File(tempVideoFile, cleanVideoFile)
                                        val targetInput = if (sanitized && cleanVideoFile.exists()) cleanVideoFile else tempVideoFile
                                        if (ZverPrefs.isRamOnlyMode(context)) {
                                            val bytes = targetInput.readBytes()
                                            GhostCryptoVault.addRamShot(bytes, isVideo = true)
                                            GhostCryptoVault.shredFile(cleanVideoFile)
                                        } else {
                                            GhostCryptoVault.encryptFileToVault(context, targetInput, prefix = GhostCryptoVault.MEDIA_VIDEO)
                                        }
                                        GhostCryptoVault.shredFile(tempVideoFile)
                                        GhostCryptoVault.shredFile(cleanVideoFile)
                                        vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                                        withContext(Dispatchers.Main) {
                                            lastStatusMessage = "🎥 MP4 ОЧИЩЕН (0 МЕТА) И ЗАШИФРОВАН [#$vaultCount]"
                                        }
                                    } catch (e: Exception) {
                                        GhostCryptoVault.shredFile(tempVideoFile)
                                        GhostCryptoVault.shredFile(cleanVideoFile)
                                        withContext(Dispatchers.Main) {
                                            lastStatusMessage = "ОШИБКА ШИФРОВАНИЯ: ${e.message}"
                                        }
                                    }
                                }
                            } else {
                                GhostCryptoVault.shredFile(tempVideoFile)
                                lastStatusMessage = "ОШИБКА: ${event.cause?.message ?: "Сбой"}"
                            }
                        }
                    }
                }
                activeRecording = rec
            } catch (e: Exception) {
                lastStatusMessage = "ОШИБКА СТАРТА: ${e.message}"
            }
        }
    }

    var pendingAudioAction by remember { mutableStateOf<String?>(null) }
    var hasAskedAudioForVideo by remember { mutableStateOf(false) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        when (pendingAudioAction) {
            "VOICE" -> {
                if (granted) {
                    isVoiceControlActive = true
                    lastStatusMessage = "РЕЖИМ: ГОЛОСОВОЕ УПРАВЛЕНИЕ"
                } else {
                    isVoiceControlActive = false
                    lastStatusMessage = "НЕТ ДОСТУПА К МИКРОФОНУ ДЛЯ ГОЛОСА"
                }
            }
            "VIDEO" -> {
                startVideoRecordingInternal()
            }
        }
        pendingAudioAction = null
    }

    fun requestVoiceControlToggle(forceEnable: Boolean? = null) {
        val target = forceEnable ?: !isVoiceControlActive
        if (!target) {
            isVoiceControlActive = false
            return
        }
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudio) {
            isVoiceControlActive = true
            lastStatusMessage = "РЕЖИМ: ГОЛОСОВОЕ УПРАВЛЕНИЕ"
        } else {
            pendingAudioAction = "VOICE"
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun triggerVideoRecordingToggle() {
        if (isRecordingVideo) {
            startVideoRecordingInternal()
            return
        }
        if (ZverPrefs.isMuteVideoAudio(context)) {
            startVideoRecordingInternal()
            return
        }
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudio || hasAskedAudioForVideo) {
            startVideoRecordingInternal()
        } else {
            hasAskedAudioForVideo = true
            pendingAudioAction = "VIDEO"
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Wire hardware volume keys and external Termux / MacroDroid Broadcast triggers
    LaunchedEffect(Unit) {
        MainActivity.onVolumeShutterTrigger = {
            if (selectedCameraMode == "VIDEO") triggerVideoRecordingToggle() else triggerPhotoCapture()
        }
        MainActivity.onVideoToggleTrigger = {
            triggerVideoRecordingToggle()
        }
        MainActivity.onTorchToggleTrigger = {
            isTorchActive = !isTorchActive
        }
        MainActivity.onBlackoutToggleTrigger = {
            isStealthBlackoutActive = !isStealthBlackoutActive
        }
    }

    // Dynamic zoom ratio controller
    LaunchedEffect(zoomRatio) {
        try { cameraControl?.setZoomRatio(zoomRatio) } catch (_: Exception) {}
    }

    val voiceControlManager = remember {
        com.example.camera.VoiceControlManager(
            context = context,
            onCommandRecognized = { cmd ->
                when (cmd) {
                    com.example.camera.VoiceControlManager.VoiceCommand.CAPTURE -> triggerPhotoCapture()
                    com.example.camera.VoiceControlManager.VoiceCommand.ZOOM_IN -> zoomRatio = 2.0f
                    com.example.camera.VoiceControlManager.VoiceCommand.ZOOM_MAX -> zoomRatio = 3.0f
                    com.example.camera.VoiceControlManager.VoiceCommand.ZOOM_RESET -> zoomRatio = 1.0f
                    com.example.camera.VoiceControlManager.VoiceCommand.TOGGLE_TORCH -> isTorchActive = !isTorchActive
                    com.example.camera.VoiceControlManager.VoiceCommand.TORCH_ON -> isTorchActive = true
                    com.example.camera.VoiceControlManager.VoiceCommand.TORCH_OFF -> isTorchActive = false
                    com.example.camera.VoiceControlManager.VoiceCommand.TIMER_5 -> {
                        timerSeconds = 5
                        lastStatusMessage = "ТАЙМЕР УСТАНОВЛЕН: 5 СЕК"
                    }
                    com.example.camera.VoiceControlManager.VoiceCommand.TIMER_OFF -> timerSeconds = 0
                    com.example.camera.VoiceControlManager.VoiceCommand.TOGGLE_STEALTH -> isStealthBlackoutActive = !isStealthBlackoutActive
                    com.example.camera.VoiceControlManager.VoiceCommand.OPEN_VAULT -> showVaultDialog = true
                    com.example.camera.VoiceControlManager.VoiceCommand.TOGGLE_VIDEO -> triggerVideoRecordingToggle()
                    com.example.camera.VoiceControlManager.VoiceCommand.FLIP_CAMERA -> {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    }
                }
            },
            onStatusUpdate = { msg ->
                lastStatusMessage = msg
            }
        )
    }

    DisposableEffect(isVoiceControlActive) {
        if (isVoiceControlActive) {
            voiceControlManager.startListening()
        } else {
            voiceControlManager.stopListening()
        }
        onDispose {
            voiceControlManager.stopListening()
        }
    }

    // Auto-shutter loop
    LaunchedEffect(isAutoShutterEnabled, isFocusLocked, isGyroStable, liveLaplacianVariance) {
        if (isAutoShutterEnabled && isFocusLocked && isGyroStable && (liveLaplacianVariance >= 110.0 || liveLaplacianVariance == 0.0) && !isCapturing) {
            triggerPhotoCapture()
            isFocusLocked = false
        }
    }

    // Bind CameraX
    LaunchedEffect(lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraProvider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }

        val preview = Preview.Builder().build()

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)

        val c2Extender = androidx.camera.camera2.interop.Camera2Interop.Extender(captureBuilder)
        c2Extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        )
        c2Extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

        val capture = captureBuilder.build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                val plane = imageProxy.planes[0]
                val variance = antiBlurEngine.calculateCenterRoiLaplacianVariance(
                    yBuffer = plane.buffer,
                    width = imageProxy.width,
                    height = imageProxy.height,
                    rowStride = plane.rowStride,
                    targetRoiSize = 400
                )
                val (overPct, underPct) = antiBlurEngine.calculateExposureMetrics(
                    yBuffer = plane.buffer,
                    width = imageProxy.width,
                    height = imageProxy.height,
                    rowStride = plane.rowStride
                )
                if (isFocusPeakingEnabled) {
                    peakingPoints = antiBlurEngine.computeFocusPeakingPoints(
                        yBuffer = plane.buffer,
                        width = imageProxy.width,
                        height = imageProxy.height,
                        rowStride = plane.rowStride,
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    )
                } else if (peakingPoints.isNotEmpty()) {
                    peakingPoints = emptyList()
                }
                val motion = antiBlurEngine.computeMotionAndLuma(
                    yBuffer = plane.buffer,
                    width = imageProxy.width,
                    height = imageProxy.height,
                    rowStride = plane.rowStride,
                    previousGrid = previousMotionGrid
                )
                previousMotionGrid = motion.grid
                sentinelMotionScore = motion.motionScore
                sentinelAvgLuma = motion.avgLuma
                sentinelIsNight = motion.isLowLight

                val sentinelRes = SentinelEngine.evaluateMotionFrame(
                    nowMs = System.currentTimeMillis(),
                    motionScore = motion.motionScore,
                    avgLuma = motion.avgLuma
                )
                when (sentinelRes) {
                    is SentinelEngine.SentinelStepResult.TriggerLingerCapture -> {
                        scope.launch(Dispatchers.Main) {
                            sentinelStepLabel = "🚨 ЗАВИСАНИЕ > ${sentinelRes.durationMs / 1000}с!"
                            sentinelEvents = SentinelEngine.getEvents()
                            launchSentinelCapture(
                                reason = "Объект у цели ${sentinelRes.durationMs / 1000}с (движ ${motion.motionScore}%)",
                                useNightClahe = sentinelRes.nightClahe
                            )
                        }
                    }
                    is SentinelEngine.SentinelStepResult.NeighborIgnored -> {
                        scope.launch(Dispatchers.Main) {
                            sentinelStepLabel = "ПРОХОЖИЙ [ИГНОР]"
                            sentinelEvents = SentinelEngine.getEvents()
                        }
                    }
                    is SentinelEngine.SentinelStepResult.Tracking -> {
                        val thr = SentinelEngine.getConfig().lingerThresholdSec
                        sentinelStepLabel = "ЦЕЛЬ: ${sentinelRes.elapsedMs / 1000}с / ${thr}с"
                    }
                    is SentinelEngine.SentinelStepResult.Cooldown -> {
                        sentinelStepLabel = "ПАУЗА: ${sentinelRes.remainingSec}с"
                    }
                    SentinelEngine.SentinelStepResult.Idle -> {
                        sentinelStepLabel = if (SentinelEngine.isArmed()) "ОХРАНА: ЧИСТО" else "ОЖИДАНИЕ"
                    }
                }

                liveLaplacianVariance = variance
                overexposedPercent = overPct
                underexposedPercent = underPct
            } finally {
                imageProxy.close()
            }
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val recorder = androidx.camera.video.Recorder.Builder()
            .setQualitySelector(androidx.camera.video.QualitySelector.from(androidx.camera.video.Quality.HD))
            .build()
        val vCapture = androidx.camera.video.VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                capture,
                imageAnalysis,
                vCapture
            )
            cameraControl = camera.cameraControl
            imageCapture = capture
            videoCapture = vCapture

            previewView?.let { pv ->
                preview.setSurfaceProvider(pv.surfaceProvider)
            }
        } catch (_: Exception) {
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    capture,
                    vCapture
                )
                cameraControl = camera.cameraControl
                imageCapture = capture
                videoCapture = vCapture

                previewView?.let { pv ->
                    preview.setSurfaceProvider(pv.surfaceProvider)
                }
            } catch (_: Exception) {
                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        capture
                    )
                    cameraControl = camera.cameraControl
                    imageCapture = capture

                    previewView?.let { pv ->
                        preview.setSurfaceProvider(pv.surfaceProvider)
                    }
                } catch (e: Exception) {
                    lastStatusMessage = "Ошибка камеры: ${e.localizedMessage}"
                }
            }
        }
    }

    LaunchedEffect(flashMode) { imageCapture?.flashMode = flashMode }
    LaunchedEffect(exposureIndex) {
        try { cameraControl?.setExposureCompensationIndex(exposureIndex) } catch (_: Exception) {}
    }
    LaunchedEffect(zoomRatio) {
        try { cameraControl?.setZoomRatio(zoomRatio) } catch (_: Exception) {}
    }

    // STEALTH BLACKOUT / FODCAM BACKGROUND MODE
    if (isStealthBlackoutActive) {
        StealthBlackoutScreen(
            mode = stealthCoverMode,
            isProximityLocked = isProximityLocked,
            onCycleMode = {
                stealthCoverMode = when (stealthCoverMode) {
                    StealthCoverMode.BLACKOUT -> StealthCoverMode.LOCKSCREEN_CLOCK
                    StealthCoverMode.LOCKSCREEN_CLOCK -> StealthCoverMode.BROWSER_MOCK
                    StealthCoverMode.BROWSER_MOCK -> StealthCoverMode.JELLY_TANK
                    StealthCoverMode.JELLY_TANK -> StealthCoverMode.BLACKOUT
                }
            },
            onCapture = { triggerPhotoCapture() },
            onExit = {
                isStealthBlackoutActive = false
                if (selectedCameraMode == "STEALTH") {
                    selectedCameraMode = previousCameraMode
                }
            }
        )
        return
    }

    val cameraModes = remember {
        listOf(
            "СТЕЛС" to "STEALTH",
            "ВИДЕО" to "VIDEO",
            "ФОТО" to "PHOTO",
            "НОЧЬ" to "NIGHT",
            "ДОЗОР" to "SENTINEL",
            "ДОК" to "DOCUMENTS",
            "ГОЛОС" to "VOICE"
        )
    }

    fun applyCameraModeSelection(key: String) {
        if (key == "STEALTH") {
            if (selectedCameraMode != "STEALTH") previousCameraMode = selectedCameraMode
            selectedCameraMode = key
            isToolsDrawerOpen = false
            isStealthBlackoutActive = true
            return
        }
        selectedCameraMode = key
        when (key) {
            "PHOTO" -> {
                exposureIndex = 0
                isDocumentModeActive = false
                isVoiceControlActive = false
                lastStatusMessage = "РЕЖИМ: ФОТОСЪЁМКА"
            }
            "VIDEO" -> {
                lastStatusMessage = "РЕЖИМ: ВИДЕО В СЕЙФ"
            }
            "NIGHT" -> {
                exposureIndex = 1
                lastStatusMessage = "РЕЖИМ: НОЧЬ (+1 СВЕТОСИЛА)"
            }
            "SENTINEL" -> {
                isDocumentModeActive = false
                lastStatusMessage = "РЕЖИМ: КИБЕР-ДОЗОР (SENTINEL v5)"
            }
            "DOCUMENTS" -> {
                isDocumentModeActive = true
                lastStatusMessage = "РЕЖИМ: ДОКУМЕНТЫ (УРОВЕНЬ)"
            }
            "VOICE" -> {
                requestVoiceControlToggle(forceEnable = true)
            }
        }
    }

    val activeToolsCount = listOf(
        isFocusPeakingEnabled,
        isFalseColorEnabled,
        isAutoShutterEnabled,
        isDocumentModeActive,
        isFocusBracketingActive,
        isDcpDehazeActive,
        timerSeconds > 0,
        isVoiceControlActive,
        isAssistModeActive
    ).count { it }

    val hasByokKey = remember(showVikaSheet, showSettingsDialog) { ByokManager.hasKey(context) }

    // MAIN CAMERA VIEWPORT
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // CameraX Live Viewfinder (touch gestures handled by Compose overlay below for clean swipes + focus)
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Fullscreen Gesture Layer: Tap-to-Focus + Horizontal Mode Swipe + Vertical Drawer/Vika AI Swipe
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(previewView, isToolsDrawerOpen) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            if (isToolsDrawerOpen) {
                                isToolsDrawerOpen = false
                                return@detectTapGestures
                            }
                            previewView?.let { pv ->
                                val point = pv.meteringPointFactory.createPoint(tapOffset.x, tapOffset.y)
                                val action = FocusMeteringAction.Builder(
                                    point,
                                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                )
                                    .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                cameraControl?.startFocusAndMetering(action)
                                focusTapPoint = tapOffset
                                isFocusLocked = true
                            }
                        }
                    )
                }
                .pointerInput(selectedCameraMode, isToolsDrawerOpen) {
                    var totalDx = 0f
                    var totalDy = 0f
                    var handled = false
                    detectDragGestures(
                        onDragStart = {
                            totalDx = 0f
                            totalDy = 0f
                            handled = false
                        },
                        onDrag = { _, dragAmount ->
                            if (handled) return@detectDragGestures
                            totalDx += dragAmount.x
                            totalDy += dragAmount.y
                            val threshold = 105f
                            if (abs(totalDx) > threshold && abs(totalDx) > abs(totalDy) * 1.3f) {
                                handled = true
                                val curIdx = cameraModes.indexOfFirst { it.second == selectedCameraMode }.coerceAtLeast(2)
                                val nextIdx = if (totalDx < 0f) {
                                    (curIdx + 1).coerceAtMost(cameraModes.lastIndex)
                                } else {
                                    (curIdx - 1).coerceAtLeast(1) // avoid accidental swipe straight into blackout
                                }
                                if (nextIdx != curIdx) {
                                    triggerHapticFeedback(isStealth = true)
                                    applyCameraModeSelection(cameraModes[nextIdx].second)
                                }
                            } else if (totalDy > threshold && abs(totalDy) > abs(totalDx) * 1.3f) {
                                handled = true
                                triggerHapticFeedback(isStealth = true)
                                isToolsDrawerOpen = true
                            } else if (totalDy < -threshold && abs(totalDy) > abs(totalDx) * 1.3f) {
                                handled = true
                                triggerHapticFeedback(isStealth = true)
                                if (isToolsDrawerOpen) {
                                    isToolsDrawerOpen = false
                                } else {
                                    vikaSheetInitialTab = VikaSheetTab.TASKS
                                    showVikaSheet = true
                                }
                            }
                        }
                    )
                }
        )

        // Center Tactical Reticle
        TacticalReticle(modifier = Modifier.fillMaxSize())

        // Document Level Overlay (Virtual Inclinometer)
        if (isDocumentModeActive) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val crosshairColor = if (isDocumentParallel) CyberGreen else NeonYellow
                Canvas(modifier = Modifier.size(160.dp)) {
                    val cx = size.width / 2
                    val cy = size.height / 2
                    drawCircle(color = crosshairColor, radius = 56.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
                    drawCircle(color = crosshairColor.copy(alpha = 0.35f), radius = 14.dp.toPx())
                    drawLine(color = crosshairColor, start = Offset(cx - 64.dp.toPx(), cy), end = Offset(cx + 64.dp.toPx(), cy), strokeWidth = 2.dp.toPx())
                    drawLine(color = crosshairColor, start = Offset(cx, cy - 64.dp.toPx()), end = Offset(cx, cy + 64.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
                Text(
                    text = if (isDocumentParallel) "ДОКУМЕНТ В ПЛОСКОСТИ: ${String.format("%.1f", documentTiltDeg)}° [ОК]" else "НАКЛОН: ${String.format("%.1f", documentTiltDeg)}°",
                    color = crosshairColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 180.dp)
                )
            }
        }

        // Focus Peaking Live Sharpness Overlay
        if (isFocusPeakingEnabled) {
            FocusPeakingOverlay(
                peakingPoints = peakingPoints,
                sharpnessVariance = liveLaplacianVariance,
                modifier = Modifier.fillMaxSize()
            )
        }

        // False-Color Exposure Overlay
        if (isFalseColorEnabled) {
            FalseColorExposureOverlay(
                overexposedPct = overexposedPercent,
                underexposedPct = underexposedPercent,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Tap-To-Focus Indicator
        focusTapPoint?.let { pt ->
            LaunchedEffect(pt) {
                delay(1800)
                focusTapPoint = null
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = CyberGreen, radius = 26.dp.toPx(), center = pt, style = Stroke(width = 2.dp.toPx()))
                drawCircle(color = CyberGreen.copy(alpha = 0.3f), radius = 6.dp.toPx(), center = pt)
            }
        }

        // SHUTTER FLASH ANIMATION
        if (showShutterFlash) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CyberGreen.copy(alpha = 0.25f))
                    .border(4.dp, CyberGreen)
            )
        }

        // RED NEON PERIMETER BORDER when :assist process is active
        if (isAssistModeActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(3.dp, CyberRed.copy(alpha = redBorderAlpha))
            )
        }

        // MINIMALIST TOP PILL + COLLAPSIBLE PULL-DOWN TACTICAL DRAWER
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            val ramMode = ZverPrefs.isRamOnlyMode(context)
            val presetShort = when (ZverPrefs.getCompressionPreset(context)) {
                com.example.camera.ExifStripper.CompressionPreset.ULTRA_TINY -> "WebP"
                com.example.camera.ExifStripper.CompressionPreset.BALANCED -> "WebP+"
                com.example.camera.ExifStripper.CompressionPreset.DOCUMENT_BW -> "Ч/Б"
                com.example.camera.ExifStripper.CompressionPreset.HIGH_QUALITY -> "JPEG"
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberBlack.copy(alpha = 0.58f))
                    .border(
                        1.dp,
                        if (isAssistModeActive) CyberRed.copy(alpha = 0.7f) else CyberGreen.copy(alpha = 0.3f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isAssistModeActive) CyberRed else if (hasByokKey) NeonCyan else CyberGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ZVER",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (ramMode) "ОЗУ • $presetShort" else "СЕЙФ • $presetShort",
                            color = if (ramMode) NeonYellow else CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(CyberSurface.copy(alpha = 0.8f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    val stabilityText = if (isTripodMode) "ШТАТИВ" else if (isGyroStable) "СТАБИЛЬНО" else "ТРЯСКА!"
                    Text(
                        text = "$stabilityText • РЕЗК:${liveLaplacianVariance.toInt()} • ↓ИНСТР / ↑ВИКА",
                        color = if (isGyroStable) CyberMuted else CyberRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }

                // Clean 3-button Top Cluster (44dp+ targets)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { isTorchActive = !isTorchActive },
                        modifier = Modifier.size(44.dp).testTag("torch_button")
                    ) {
                        Icon(
                            imageVector = if (isTorchActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Фонарик",
                            tint = if (isTorchActive) NeonYellow else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Box(contentAlignment = Alignment.TopEnd) {
                        IconButton(
                            onClick = { isToolsDrawerOpen = !isToolsDrawerOpen },
                            modifier = Modifier.size(44.dp).testTag("tools_drawer_button")
                        ) {
                            Icon(
                                imageVector = if (isToolsDrawerOpen) Icons.Default.KeyboardArrowUp else Icons.Default.Tune,
                                contentDescription = "Шторка инструментов",
                                tint = if (isToolsDrawerOpen || activeToolsCount > 0) CyberGreen else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        if (activeToolsCount > 0 && !isToolsDrawerOpen) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp, end = 6.dp)
                                    .size(15.dp)
                                    .clip(CircleShape)
                                    .background(CyberGreen),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = activeToolsCount.toString(),
                                    color = Color.Black,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier.size(44.dp).testTag("flip_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Перевернуть камеру",
                            tint = CyberGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // PULL-DOWN HIDDEN TACTICAL TOOLS DRAWER (12 buttons tucked neatly out of the way!)
            AnimatedVisibility(
                visible = isToolsDrawerOpen,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(CyberSurface.copy(alpha = 0.94f), CyberBlack.copy(alpha = 0.96f))
                            )
                        )
                        .border(1.dp, CyberGreen.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Row 1: Optics & Overlays
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DrawerTileButton(
                            icon = Icons.Default.CenterFocusStrong,
                            label = "Фокус-пик",
                            active = isFocusPeakingEnabled,
                            accent = NeonCyan,
                            modifier = Modifier.weight(1f)
                        ) { isFocusPeakingEnabled = !isFocusPeakingEnabled }

                        DrawerTileButton(
                            icon = Icons.Default.Exposure,
                            label = "Экспо-карта",
                            active = isFalseColorEnabled,
                            accent = NeonYellow,
                            modifier = Modifier.weight(1f)
                        ) { isFalseColorEnabled = !isFalseColorEnabled }

                        DrawerTileButton(
                            icon = Icons.Default.CropFree,
                            label = "Уровень док",
                            active = isDocumentModeActive,
                            accent = CyberGreen,
                            modifier = Modifier.weight(1f)
                        ) { isDocumentModeActive = !isDocumentModeActive }

                        DrawerTileButton(
                            icon = Icons.Default.AutoFixHigh,
                            label = "Анти-туман",
                            active = isDcpDehazeActive,
                            accent = CyberGreen,
                            modifier = Modifier.weight(1f)
                        ) { isDcpDehazeActive = !isDcpDehazeActive }
                    }

                    // Row 2: Shutter & Capture
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DrawerTileButton(
                            icon = Icons.Default.ShutterSpeed,
                            label = "Автоспуск",
                            active = isAutoShutterEnabled,
                            accent = CyberGreen,
                            modifier = Modifier.weight(1f)
                        ) { isAutoShutterEnabled = !isAutoShutterEnabled }

                        DrawerTileButton(
                            icon = Icons.Default.BurstMode,
                            label = "Брекетинг",
                            active = isFocusBracketingActive,
                            accent = NeonCyan,
                            modifier = Modifier.weight(1f)
                        ) { isFocusBracketingActive = !isFocusBracketingActive }

                        DrawerTileButton(
                            icon = Icons.Default.Timer,
                            label = if (timerSeconds == 0) "Таймер" else "Тайм ${timerSeconds}с",
                            active = timerSeconds > 0,
                            accent = NeonYellow,
                            modifier = Modifier.weight(1f)
                        ) {
                            timerSeconds = when (timerSeconds) {
                                0 -> 3
                                3 -> 5
                                5 -> 10
                                else -> 0
                            }
                        }

                        DrawerTileButton(
                            icon = Icons.Default.Mic,
                            label = "Голос",
                            active = isVoiceControlActive,
                            accent = NeonCyan,
                            modifier = Modifier.weight(1f)
                        ) { requestVoiceControlToggle() }
                    }

                    // Row 3: Stealth, Camo, AI & Settings
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DrawerTileButton(
                            icon = Icons.Default.DarkMode,
                            label = "Стелс",
                            active = false,
                            accent = NeonCyan,
                            modifier = Modifier.weight(1f).testTag("stealth_button")
                        ) {
                            isToolsDrawerOpen = false
                            if (selectedCameraMode != "STEALTH") previousCameraMode = selectedCameraMode
                            isStealthBlackoutActive = true
                        }

                        DrawerTileButton(
                            icon = Icons.Default.VisibilityOff,
                            label = "Камуфляж",
                            active = false,
                            accent = CyberGreen,
                            modifier = Modifier.weight(1f).testTag("camouflage_button")
                        ) {
                            isToolsDrawerOpen = false
                            showCamouflageDialog = true
                        }

                        DrawerTileButton(
                            icon = Icons.Default.Shield,
                            label = "Ассист :AI",
                            active = isAssistModeActive,
                            accent = CyberRed,
                            modifier = Modifier.weight(1f).testTag("assist_toggle_button")
                        ) { isAssistModeActive = !isAssistModeActive }

                        DrawerTileButton(
                            icon = Icons.Default.Settings,
                            label = "Настройки",
                            active = false,
                            accent = CyberGreen,
                            modifier = Modifier.weight(1f).testTag("settings_button")
                        ) {
                            isToolsDrawerOpen = false
                            showSettingsDialog = true
                        }
                    }

                    // Drawer collapse handle
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { isToolsDrawerOpen = false }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "▲ СПРЯТАТЬ ПАНЕЛЬ",
                            color = CyberMuted,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }

        // Live Countdown Overlay
        countdownRemaining?.let { count ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = count.toString(),
                    color = CyberGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 96.sp
                )
            }
        }

        // Notification Banner
        AnimatedVisibility(
            visible = isCapturing || lastStatusMessage != null || isRecordingVideo,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 86.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberBlack.copy(alpha = 0.85f))
                    .border(1.5.dp, if (isRecordingVideo) CyberRed else if (isCapturing) CyberGreen else CyberGreenDark, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRecordingVideo) {
                        val m = recordingDurationSeconds / 60
                        val s = recordingDurationSeconds % 60
                        val timerStr = String.format(Locale.ROOT, "%02d:%02d", m, s)
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(CyberRed))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🔴 ЗАПИСЬ В СЕЙФ: $timerStr", color = CyberRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    } else if (isCapturing) {
                        CircularProgressIndicator(color = CyberGreen, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ШИФРОВАНИЕ...", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    } else {
                        Text(lastStatusMessage ?: "", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }

        // BOTTOM ERGONOMIC CONTROL DECK
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // APEX SENTINEL v5.0 ("ДОЗОР") Live Control Card (shown only in SENTINEL mode)
            if (selectedCameraMode == "SENTINEL") {
                SentinelControlCard(
                    armed = sentinelArmed,
                    config = sentinelConfig,
                    motionScore = sentinelMotionScore,
                    avgLuma = sentinelAvgLuma,
                    isNight = sentinelIsNight,
                    stepLabel = sentinelStepLabel,
                    hasByokKey = hasByokKey,
                    events = sentinelEvents,
                    onToggleArmed = {
                        val next = !sentinelArmed
                        SentinelEngine.setArmed(next)
                        sentinelArmed = next
                        sentinelEvents = SentinelEngine.getEvents()
                        lastStatusMessage = if (next) "🛡️ ДОЗОР НА ПОСТУ (${sentinelConfig.lingerThresholdSec}с фильтр)" else "🛡️ ДОЗОР СНЯТ"
                    },
                    onCycleLinger = {
                        val nextSec = when (sentinelConfig.lingerThresholdSec) {
                            2 -> 4
                            4 -> 6
                            else -> 2
                        }
                        sentinelConfig = SentinelEngine.updateConfig { it.copy(lingerThresholdSec = nextSec) }
                    },
                    onToggleKnock = {
                        sentinelConfig = SentinelEngine.updateConfig { it.copy(knockTriggerEnabled = !it.knockTriggerEnabled) }
                    },
                    onToggleClaheNight = {
                        sentinelConfig = SentinelEngine.updateConfig { it.copy(autoNightClaheEnabled = !it.autoNightClaheEnabled) }
                    },
                    onToggleAiTriage = {
                        if (!hasByokKey) {
                            vikaSheetInitialTab = VikaSheetTab.KEYS_MODELS
                            showVikaSheet = true
                        } else {
                            sentinelConfig = SentinelEngine.updateConfig { it.copy(aiTriageEnabled = !it.aiTriageEnabled) }
                        }
                    },
                    onStealthGuard = {
                        if (selectedCameraMode != "STEALTH") previousCameraMode = "SENTINEL"
                        isStealthBlackoutActive = true
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Compact Zoom + EV Floating Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(CyberBlack.copy(alpha = 0.55f))
                        .border(1.dp, CyberBorder.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(1.0f, 2.0f, 4.0f, 6.0f, 10.0f).forEach { z ->
                        val sel = zoomRatio == z
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (sel) CyberGreen.copy(alpha = 0.25f) else Color.Transparent)
                                .clickable {
                                    zoomRatio = z
                                    lastStatusMessage = "ЗУМ: ${z.toInt()}x"
                                }
                                .padding(horizontal = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${z.toInt()}x",
                                color = if (sel) CyberGreen else CyberMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Compact EV cycle pill (tucks 3 buttons into 1 sleek pill)
                val evLabel = when (exposureIndex) {
                    1 -> "EV +1"
                    -1 -> "EV -1"
                    else -> "EV 0"
                }
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(CyberBlack.copy(alpha = 0.55f))
                        .border(1.dp, if (exposureIndex != 0) NeonYellow else CyberBorder, RoundedCornerShape(20.dp))
                        .clickable {
                            exposureIndex = when (exposureIndex) {
                                0 -> 1
                                1 -> -1
                                else -> 0
                            }
                            lastStatusMessage = when (exposureIndex) {
                                1 -> "ЯРКОСТЬ: +1 (СВЕТ)"
                                -1 -> "ЯРКОСТЬ: -1 (ТЕНЬ)"
                                else -> "ЯРКОСТЬ: АВТО"
                            }
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = evLabel,
                        color = if (exposureIndex != 0) NeonYellow else CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // MIUI-style Mode Carousel (synced with Left/Right swipes)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                cameraModes.forEach { (title, key) ->
                    val isSelected = selectedCameraMode == key
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { applyCameraModeSelection(key) }
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) Color.White else CyberMuted,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) NeonYellow else Color.Transparent)
                        )
                    }
                }
            }

            // Balanced 48dp+ Primary Action Deck
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(CyberBlack.copy(alpha = 0.65f))
                    .border(1.dp, CyberBorder.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vault Button
                Button(
                    onClick = { showVaultDialog = true },
                    modifier = Modifier.height(48.dp).testTag("vault_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSurface.copy(alpha = 0.9f)),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberGreen.copy(alpha = 0.55f)),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Сейф", tint = CyberGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$vaultCount",
                        color = CyberGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Center Shutter + Video Quick Control
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (selectedCameraMode == "VIDEO") {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("video_record_button")
                                .clip(CircleShape)
                                .border(3.dp, CyberRed, CircleShape)
                                .clickable { triggerVideoRecordingToggle() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isRecordingVideo) {
                                Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(CyberRed))
                            } else {
                                Box(modifier = Modifier.size(54.dp).clip(CircleShape).background(CyberRed))
                            }
                        }
                    } else {
                        ShutterButton(
                            isCapturing = isCapturing || countdownRemaining != null,
                            isAssistMode = isAssistModeActive,
                            onClick = { triggerPhotoCapture() }
                        )

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("video_record_button")
                                .clip(CircleShape)
                                .background(CyberRed.copy(alpha = 0.22f))
                                .border(1.5.dp, CyberRed, CircleShape)
                                .clickable {
                                    selectedCameraMode = "VIDEO"
                                    triggerVideoRecordingToggle()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = "Запись видео", tint = CyberRed, modifier = Modifier.size(22.dp))
                        }
                    }
                }

                // Right Cluster: Vika AI Sheet + Panic Shred (48dp each)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            vikaSheetInitialTab = VikaSheetTab.TASKS
                            showVikaSheet = true
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("vika_ai_button")
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (hasByokKey) NeonCyan.copy(alpha = 0.2f) else CyberSurface)
                            .border(1.dp, if (hasByokKey) NeonCyan else CyberBorder, RoundedCornerShape(14.dp))
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = "Вика AI и Аналитика",
                            tint = if (hasByokKey) NeonCyan else CyberMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = { showPanicConfirm = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("panic_shred_button")
                            .clip(RoundedCornerShape(14.dp))
                            .background(CyberRed.copy(alpha = 0.22f))
                            .border(1.dp, CyberRed.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = "Шредер", tint = CyberRed, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        // Vault Viewer Dialog
        if (showVaultDialog) {
            GhostVaultDialog(
                forceSetupFlow = forcePinSetupFlow,
                onDismiss = {
                    showVaultDialog = false
                    forcePinSetupFlow = false
                    currentProfile = GhostCryptoVault.activeProfile
                    vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                },
                onVaultUpdated = {
                    currentProfile = GhostCryptoVault.activeProfile
                    vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                }
            )
        }

        // Camouflage Selector Dialog
        if (showCamouflageDialog) {
            AlertDialog(
                onDismissRequest = { showCamouflageDialog = false },
                title = { Text("МАСКИРОВКА / КАМУФЛЯЖ", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp) },
                text = {
                    Column {
                        Text("Выбери фальшивую личину для лаунчера телефона:", color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        for (mode in CamouflageManager.CamouflageMode.values()) {
                            val ruName = when (mode) {
                                CamouflageManager.CamouflageMode.ZVER -> "Zver Camera (По умолчанию)"
                                CamouflageManager.CamouflageMode.CALCULATOR -> "Калькулятор"
                                CamouflageManager.CamouflageMode.SYSTEM_TOOLS -> "Системные утилиты"
                                CamouflageManager.CamouflageMode.JELLY_TRACKER -> "BioTracker (Жилистый танк)"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        CamouflageManager.setCamouflage(context, mode)
                                        currentCamouflage = mode
                                        showCamouflageDialog = false
                                        lastStatusMessage = "МАСКИРОВКА: $ruName"
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ruName, color = if (mode == currentCamouflage) CyberGreen else Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                if (mode == currentCamouflage) {
                                    Icon(Icons.Default.Check, contentDescription = "Активно", tint = CyberGreen, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showCamouflageDialog = false }) { Text("ЗАКРЫТЬ", color = CyberMuted, fontFamily = FontFamily.Monospace) } },
                containerColor = CyberSurface
            )
        }

        // Settings, Security, AI Prompts & Termux Center Dialog
        if (showSettingsDialog) {
            GhostSettingsDialog(
                onDismiss = { showSettingsDialog = false },
                onStatusMessage = { msg -> lastStatusMessage = msg },
                onOpenPinSetup = {
                    forcePinSetupFlow = true
                    showVaultDialog = true
                },
                onOpenBioTracker = {
                    showJellyTankScreen = true
                },
                onOpenVikaSheet = {
                    vikaSheetInitialTab = VikaSheetTab.TASKS
                    showVikaSheet = true
                },
                onCamouflageChanged = { mode ->
                    currentCamouflage = mode
                },
                onSelectMode = { modeKey ->
                    if (modeKey == "MACRO") {
                        zoomRatio = 2.0f
                        lastStatusMessage = "РЕЖИМ: МАКРО (2x ЗУМ)"
                    } else {
                        applyCameraModeSelection(modeKey)
                    }
                }
            )
        }

        if (showVikaSheet) {
            VikaAiSheet(
                initialTab = vikaSheetInitialTab,
                externalBitmap = null,
                externalVideoFile = null,
                onCaptureLiveFrame = {
                    val cap = imageCapture ?: return@VikaAiSheet null
                    val manager = CaptureManager(
                        context = context,
                        imageCapture = cap,
                        captureExecutor = captureExecutor
                    )
                    manager.captureCleanFrameForAi()
                },
                onCaptureTwoStageFrame = { detectCrop, isolateBg ->
                    val cap = imageCapture ?: return@VikaAiSheet null
                    val manager = CaptureManager(
                        context = context,
                        imageCapture = cap,
                        captureExecutor = captureExecutor
                    )
                    manager.captureTwoStageFrameForAi(
                        applyClahe = isDcpDehazeActive || sentinelIsNight,
                        detectFaceCrop = detectCrop,
                        isolateBackground = isolateBg
                    )
                },
                onCaptureOfflineBitmap = {
                    val cap = imageCapture ?: return@VikaAiSheet null
                    val manager = CaptureManager(
                        context = context,
                        imageCapture = cap,
                        captureExecutor = captureExecutor
                    )
                    manager.captureBitmapInRamForOfflineCv()
                },
                onStatusMessage = { msg -> lastStatusMessage = msg },
                onDismiss = { showVikaSheet = false }
            )
        }

        if (showJellyTankScreen) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showJellyTankScreen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                JellyTankScreen(
                    isCamouflageGate = false,
                    onClose = { showJellyTankScreen = false }
                )
            }
        }

        // Emergency Crypto-Shred Confirmation
        if (showPanicConfirm) {
            AlertDialog(
                onDismissRequest = { showPanicConfirm = false },
                title = { Text("ЭКСТРЕННЫЙ ШРЕДЕР СХРОНА", color = CyberRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = { Text("Мгновенно затирает весь контейнер случайным шумом и навсегда уничтожает аппаратные ключи процессора. Восстановление невозможно.", color = Color.White, fontSize = 12.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            GhostCryptoVault.cryptoShred(context)
                            com.example.crypto.MonoVaultEngine.cryptoShredMonolith(context)
                            ByokManager.clearSessionMemory()
                            SentinelEngine.clearSession()
                            sentinelArmed = false
                            sentinelEvents = emptyList()
                            vaultCount = 0
                            showPanicConfirm = false
                            lastStatusMessage = "СХРОН И КЛЮЧИ ПОЛНОСТЬЮ УНИЧТОЖЕНЫ"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberRed)
                    ) {
                        Text("СТЕРЕТЬ СЕЙЧАС", color = Color.White)
                    }
                },
                dismissButton = { TextButton(onClick = { showPanicConfirm = false }) { Text("ОТМЕНА", color = CyberMuted) } },
                containerColor = CyberSurface
            )
        }
    }
}

@Composable
private fun SentinelControlCard(
    armed: Boolean,
    config: SentinelEngine.SentinelConfig,
    motionScore: Int,
    avgLuma: Int,
    isNight: Boolean,
    stepLabel: String,
    hasByokKey: Boolean,
    events: List<SentinelEngine.SentinelEvent>,
    onToggleArmed: () -> Unit,
    onCycleLinger: () -> Unit,
    onToggleKnock: () -> Unit,
    onToggleClaheNight: () -> Unit,
    onToggleAiTriage: () -> Unit,
    onStealthGuard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CyberBlack.copy(alpha = 0.86f))
            .border(1.dp, if (armed) CyberRed.copy(alpha = 0.7f) else NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (armed) "🛡️ ДОЗОР НА ПОСТУ // $stepLabel" else "🛡️ SENTINEL v5.0 // $stepLabel",
                    color = if (armed) CyberRed else NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                Text(
                    text = "Движение: $motionScore% • ${if (isNight) "НОЧЬ-CLAHE" else "ДЕНЬ-HDR"} (L=$avgLuma)",
                    color = CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }
            Button(
                onClick = onToggleArmed,
                colors = ButtonDefaults.buttonColors(containerColor = if (armed) CyberRed else CyberGreenDark),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(40.dp).testTag("sentinel_arm_button")
            ) {
                Text(
                    text = if (armed) "СТОП" else "НА ПОСТ",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SentinelChip("Фильтр ${config.lingerThresholdSec}с", active = true, accent = NeonYellow, onClick = onCycleLinger)
            SentinelChip("Стук", active = config.knockTriggerEnabled, accent = CyberGreen, onClick = onToggleKnock)
            SentinelChip("CLAHE Ночь", active = config.autoNightClaheEnabled, accent = NeonCyan, onClick = onToggleClaheNight)
            SentinelChip(
                label = if (!hasByokKey) "ИИ (нет ключа)" else "ИИ-триаж",
                active = config.aiTriageEnabled && hasByokKey,
                accent = NeonCyan,
                onClick = onToggleAiTriage
            )
            SentinelChip("Стелс-пост", active = false, accent = CyberRed, onClick = onStealthGuard)
        }

        if (events.isNotEmpty()) {
            val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                events.take(3).forEach { ev ->
                    val col = when (ev.kind) {
                        SentinelEngine.EventKind.LINGER_ALARM, SentinelEngine.EventKind.KNOCK_ALARM -> CyberRed
                        SentinelEngine.EventKind.AI_VERDICT -> NeonCyan
                        SentinelEngine.EventKind.IGNORED -> CyberGreen
                        SentinelEngine.EventKind.INFO -> CyberMuted
                    }
                    Text(
                        text = "${timeFmt.format(Date(ev.timestampMs))} [${ev.kind.tagRu}] ${ev.message}",
                        color = col,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun SentinelChip(
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (active) Color.Black else CyberMuted,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) accent else CyberSurface)
            .border(1.dp, if (active) accent else CyberBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
    )
}

@Composable
private fun DrawerTileButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) accent.copy(alpha = 0.22f) else CyberBlack.copy(alpha = 0.55f))
            .border(1.dp, if (active) accent else CyberBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) accent else Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = if (active) accent else CyberMuted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            maxLines = 1
        )
    }
}
