package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
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
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.example.camera.AudioWatcherEngine
import com.example.camera.CameraCapabilityProfiler
import com.example.camera.CaptureManager
import com.example.camera.ExifStripper
import com.example.camera.Mp4Sanitizer
import com.example.camera.MultiFrameStacker
import com.example.camera.PhotoIqEngine
import com.example.camera.ZoomIntelligence
import com.example.crypto.GhostCryptoVault
import com.example.security.AntiForensicsGuard
import com.example.security.CamouflageManager
import com.example.security.SecureClipboard
import com.example.security.SentinelEngine
import com.example.security.ZverPrefs
import com.example.visor.CyberVisorEngine
import com.example.visor.VisorMode
import com.example.visor.VisorTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
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
    var activeCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var activeImageAnalysis by remember { mutableStateOf<ImageAnalysis?>(null) }

    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val antiBlurEngine = remember { AntiBlurSensorEngine(context) }

    var liveLaplacianVariance by remember { mutableStateOf(0.0) }
    var peakingPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var overexposedPercent by remember { mutableStateOf(0) }
    var underexposedPercent by remember { mutableStateOf(0) }
    var liveHistogramBins32 by remember { mutableStateOf(FloatArray(32)) }
    var liveMeanLuma by remember { mutableStateOf(128) }
    var liveZebraPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var isProximityLocked by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var lastStatusMessage by remember { mutableStateOf<String?>(null) }
    var showShutterFlash by remember { mutableStateOf(false) }
    var vaultCount by remember { mutableStateOf(0) }
    var showVaultDialog by remember { mutableStateOf(false) }
    var forcePinSetupFlow by remember { mutableStateOf(false) }
    var showPanicConfirm by remember { mutableStateOf(false) }
    var showCamouflageDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isToolsDrawerOpen by remember { mutableStateOf(false) }
    var showVikaSheet by remember { mutableStateOf(false) }
    var vikaSheetInitialTab by remember { mutableStateOf(VikaSheetTab.TASKS) }
    var isVoiceControlActive by remember { mutableStateOf(false) }
    var previousCameraMode by remember { mutableStateOf("PHOTO") }

    // Pro Camera2 & Photo IQ (P1) States
    var proProfile by remember { mutableStateOf(CameraCapabilityProfiler.CameraProProfile.fallback(lensFacing)) }
    LaunchedEffect(lensFacing) {
        proProfile = withContext(Dispatchers.IO) {
            CameraCapabilityProfiler.profileCamera(context, lensFacing)
        }
    }
    var isProPanelExpanded by remember { mutableStateOf(false) }
    var awbPreset by remember { mutableStateOf(CameraCapabilityProfiler.AwbPreset.AUTO) }
    var manualFocusNorm by remember { mutableStateOf<Float?>(null) }
    var isoOverride by remember { mutableStateOf<Int?>(null) }
    var shutterNsOverride by remember { mutableStateOf<Long?>(null) }
    var isAeLocked by remember { mutableStateOf(false) }
    var isHistogramEnabled by remember { mutableStateOf(false) }
    var isZebraEnabled by remember { mutableStateOf(false) }
    var photoIqProfile by remember { mutableStateOf(ZverPrefs.getPhotoIqProfile(context)) }
    var isZoomIqEnabled by remember { mutableStateOf(ZverPrefs.isZoomIqEnabled(context)) }
    var multiFrameMode by remember { mutableStateOf(ZverPrefs.getMultiFrameMode(context)) }
    var showPcbLabDialog by remember { mutableStateOf(false) }

    // P3 Audio Watcher states
    var isAudioWatcherActive by remember { mutableStateOf(false) }
    var audioTelemetry by remember { mutableStateOf(AudioWatcherEngine.AcousticTelemetry()) }
    var audioThresholdDbfs by remember { mutableStateOf(-26.0) }

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

    // CyberVisorEngine ("БИО-ВИЗОР / КИБЕР-СКАНЕР")
    val cyberVisorEngine = remember { CyberVisorEngine() }
    val visorTickCounter = remember { AtomicLong(0L) }
    var visorMode by remember { mutableStateOf(VisorMode.NORMAL) }
    var visorDisplayState by remember { mutableStateOf<Triple<android.graphics.Bitmap, VisorTelemetry, Long>?>(null) }

    LaunchedEffect(visorMode) {
        cyberVisorEngine.currentMode = visorMode
        if (visorMode == VisorMode.NORMAL) {
            visorDisplayState = null
        }
    }

    // Camera2 Interop: BIO_RPPG AE/AWB lock takes priority; otherwise apply Pro Camera2 overrides (WB / MF / AE-L / ISO+S)
    DisposableEffect(visorMode, cameraControl, awbPreset, manualFocusNorm, isoOverride, shutterNsOverride, isAeLocked, proProfile) {
        val ctrl = cameraControl
        if (ctrl == null) {
            onDispose {}
        } else if (visorMode == VisorMode.BIO_RPPG) {
            try {
                val c2Ctrl = Camera2CameraControl.from(ctrl)
                val lockOpts = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    .build()
                c2Ctrl.setCaptureRequestOptions(lockOpts)
            } catch (_: Exception) {}
            onDispose {
                try { Camera2CameraControl.from(ctrl).clearCaptureRequestOptions() } catch (_: Exception) {}
            }
        } else {
            val hasProOverride = (awbPreset != CameraCapabilityProfiler.AwbPreset.AUTO) ||
                (manualFocusNorm != null && proProfile.isManualFocusSupported) ||
                isAeLocked ||
                (proProfile.supportsManualExposure && (isoOverride != null || shutterNsOverride != null))
            if (hasProOverride) {
                try {
                    val builder = CaptureRequestOptions.Builder()
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbPreset.camera2Mode)
                    if (isAeLocked && proProfile.supportsAeLock) {
                        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    }
                    val mf = manualFocusNorm
                    if (mf != null && proProfile.isManualFocusSupported) {
                        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        builder.setCaptureRequestOption(
                            CaptureRequest.LENS_FOCUS_DISTANCE,
                            (mf.coerceIn(0f, 1f) * proProfile.minFocusDiopters)
                        )
                    }
                    if (proProfile.supportsManualExposure && (isoOverride != null || shutterNsOverride != null)) {
                        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        val targetIso = isoOverride ?: (proProfile.isoRange?.first ?: 200)
                        val targetNs = shutterNsOverride ?: 16_666_666L
                        builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
                        builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, targetNs)
                    }
                    Camera2CameraControl.from(ctrl).setCaptureRequestOptions(builder.build())
                } catch (_: Exception) {}
            } else {
                try { Camera2CameraControl.from(ctrl).clearCaptureRequestOptions() } catch (_: Exception) {}
            }
            onDispose {
                try { Camera2CameraControl.from(ctrl).clearCaptureRequestOptions() } catch (_: Exception) {}
            }
        }
    }

    // MIUI-style Camera Mode Carousel
    var selectedCameraMode by remember { mutableStateOf("PHOTO") }
    var boundUseCasesSummary by remember { mutableStateOf("ОЖИДАНИЕ КАМЕРЫ...") }
    var pendingAutoStartVideo by remember { mutableStateOf(false) }

    // Video Recording States
    var videoCapture by remember { mutableStateOf<androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<androidx.camera.video.Recording?>(null) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var recordingDurationSeconds by remember { mutableStateOf(0) }
    var recordingBytesWritten by remember { mutableStateOf(0L) }

    LaunchedEffect(isRecordingVideo) {
        if (isRecordingVideo) {
            recordingDurationSeconds = 0
            while (isRecordingVideo) {
                delay(1000)
                recordingDurationSeconds++
            }
        }
    }

    var securityStatus by remember { mutableStateOf<AntiForensicsGuard.SecurityStatus?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val count = GhostCryptoVault.totalVaultItemsCount(context)
            val sec = AntiForensicsGuard.assessDeviceSecurity(context)
            withContext(Dispatchers.Main) {
                vaultCount = count
                securityStatus = sec
            }
        }
    }
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
                    SecureClipboard.clearIfOwned(context)
                    sentinelArmed = false
                    sentinelEvents = emptyList()
                    showVaultDialog = false
                    showSettingsDialog = false
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
            try { activeRecording?.stop() } catch (_: Exception) {}
            try { activeImageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
            try { activeCameraProvider?.unbindAll() } catch (_: Exception) {}
            cyberVisorEngine.release()
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
                val activeStackMode = multiFrameMode
                if (activeStackMode != null && !isFocusBracketingActive) {
                    lastStatusMessage = "⚡ СТЕК ${activeStackMode.badgeRu} (3 КАДРА)..."
                    val manager = CaptureManager(
                        context = context,
                        imageCapture = cap,
                        captureExecutor = captureExecutor
                    )
                    val stacked = manager.captureMultiFrameStack(
                        mode = activeStackMode,
                        frameCount = 3,
                        applyDehaze = isDcpDehazeActive,
                        zoomRatio = zoomRatio,
                        photoIqOverride = photoIqProfile
                    )
                    val res = stacked.captureResult
                    vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                    val kb = res.encryptedSizeBytes / 1024
                    val contourTag = if (res.isRamOnly) " [ОЗУ]" else ""
                    val ghostNote = if (stacked.stackStats.rejectedGhostPercent > 0) " G-${stacked.stackStats.rejectedGhostPercent}%" else ""
                    lastStatusMessage = "📸 ${res.formatLabel} (${kb}КБ, 0 EXIF) [${res.iqLabel}$ghostNote] за ${res.durationMs}мс$contourTag"
                } else if (isFocusBracketingActive) {
                    lastStatusMessage = "СЕРИЯ БРЕКЕТИНГА: 3 КАДРА..."
                    for (step in 1..3) {
                        val manager = CaptureManager(
                            context = context,
                            imageCapture = cap,
                            captureExecutor = captureExecutor
                        )
                        manager.takeSecurePhoto(
                            applyDehaze = isDcpDehazeActive,
                            zoomRatio = zoomRatio,
                            photoIqOverride = photoIqProfile
                        )
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
                        val result = manager.takeSecurePhoto(
                            applyDehaze = isDcpDehazeActive,
                            zoomRatio = zoomRatio,
                            photoIqOverride = photoIqProfile
                        )
                        vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                        val kb = result.encryptedSizeBytes / 1024
                        val contourTag = if (result.isRamOnly) " [ОЗУ]" else ""
                        val dehazeTag = if (isDcpDehazeActive) " [АНТИ-ТУМАН]" else ""
                        val iqTag = if (result.iqLabel.isNotBlank()) " [${result.iqLabel}]" else ""
                        lastStatusMessage = "📸 ${result.formatLabel} (${kb}КБ, 0 EXIF) за ${result.durationMs}мс$contourTag$dehazeTag$iqTag"

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
            val videoStageDir = GhostCryptoVault.getVideoStageDir(context)
            val tempVideoFile = File(videoStageDir, "raw_video_${System.currentTimeMillis()}.mp4")
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
                            recordingBytesWritten = 0L
                            triggerHapticFeedback(isStealthBlackoutActive)
                            lastStatusMessage = if (hasAudio) "🔴 ИДЁТ ЗАПИСЬ ВИДЕО..." else "🔴 ЗАПИСЬ ВИДЕО (БЕЗ ЗВУКА)"
                        }
                        is androidx.camera.video.VideoRecordEvent.Status -> {
                            recordingBytesWritten = event.recordingStats.numBytesRecorded
                        }
                        is androidx.camera.video.VideoRecordEvent.Finalize -> {
                            val finalBytes = event.recordingStats.numBytesRecorded
                            isRecordingVideo = false
                            activeRecording = null
                            recordingBytesWritten = 0L
                            val savedEvenWithLimit = event.error == androidx.camera.video.VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                                event.error == androidx.camera.video.VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                            if ((!event.hasError() || savedEvenWithLimit) && tempVideoFile.exists() && tempVideoFile.length() > 0L) {
                                scope.launch(Dispatchers.IO) {
                                    val cleanVideoFile = File(videoStageDir, "clean_video_${System.currentTimeMillis()}.mp4")
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
                                lastStatusMessage = "СБОЙ ЗАПИСИ HAL (код ${event.error}, ${finalBytes}B): ${event.cause?.message ?: "нет кадров от кодека"}"
                            }
                        }
                    }
                }
                activeRecording = rec
            } catch (e: Exception) {
                isRecordingVideo = false
                activeRecording = null
                recordingBytesWritten = 0L
                GhostCryptoVault.shredFile(tempVideoFile)
                lastStatusMessage = "ОШИБКА СТАРТА: ${e.message}"
            }
        }
    }

    val audioWatcherEngine = remember {
        AudioWatcherEngine(
            context = context,
            onTelemetry = { telemetry ->
                scope.launch(Dispatchers.Main) {
                    audioTelemetry = telemetry
                }
            },
            onAcousticTrigger = { _, reason ->
                scope.launch(Dispatchers.Main) {
                    SentinelEngine.logEvent(SentinelEngine.EventKind.INFO, "Акустик: $reason")
                    sentinelEvents = SentinelEngine.getEvents()
                    lastStatusMessage = "🔊 АКУСТИК: $reason"
                    if (SentinelEngine.isArmed()) {
                        val cfg = SentinelEngine.getConfig()
                        launchSentinelCapture(
                            reason = reason,
                            useNightClahe = cfg.autoNightClaheEnabled && SentinelEngine.isNightProfileActive()
                        )
                    }
                }
            },
            onStatusError = { err ->
                scope.launch(Dispatchers.Main) {
                    isAudioWatcherActive = false
                    lastStatusMessage = err
                }
            }
        )
    }

    LaunchedEffect(audioThresholdDbfs) {
        audioWatcherEngine.thresholdDbfs = audioThresholdDbfs
    }

    DisposableEffect(isAudioWatcherActive) {
        if (isAudioWatcherActive) {
            audioWatcherEngine.start(scope)
        } else {
            audioWatcherEngine.stop()
        }
        onDispose {
            audioWatcherEngine.stop()
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
            "AUDIO_WATCHER" -> {
                if (granted) {
                    isAudioWatcherActive = true
                    lastStatusMessage = "🔊 АКУСТИК-СТОРОЖ ВКЛ (${audioThresholdDbfs.toInt()} dBFS)"
                } else {
                    isAudioWatcherActive = false
                    lastStatusMessage = "НЕТ ДОСТУПА К МИКРОФОНУ ДЛЯ АКУСТИКА"
                }
            }
            "VIDEO" -> {
                startVideoRecordingInternal()
            }
        }
        pendingAudioAction = null
    }

    fun requestAudioWatcherToggle() {
        if (isAudioWatcherActive) {
            isAudioWatcherActive = false
            lastStatusMessage = "🔇 АКУСТИК-СТОРОЖ ВЫКЛ"
            return
        }
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudio) {
            isAudioWatcherActive = true
            lastStatusMessage = "🔊 АКУСТИК-СТОРОЖ ВКЛ (${audioThresholdDbfs.toInt()} dBFS)"
        } else {
            pendingAudioAction = "AUDIO_WATCHER"
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
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
        if (selectedCameraMode != "VIDEO" || videoCapture == null) {
            pendingAutoStartVideo = true
            selectedCameraMode = "VIDEO"
            lastStatusMessage = "ПЕРЕКЛЮЧЕНИЕ HAL В ВИДЕО-РЕЖИМ..."
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

    // Dynamic zoom ratio controller (clamped safely to Camera2 HAL maxDigitalZoom)
    LaunchedEffect(zoomRatio, proProfile) {
        try {
            cameraControl?.setZoomRatio(zoomRatio.coerceIn(1.0f, proProfile.maxZoomRatio))
        } catch (_: Exception) {}
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

    val isVideoCameraMode = (selectedCameraMode == "VIDEO")

    // Bind CameraX split by mode: VIDEO -> Preview + VideoCapture; PHOTO/OPTIC/VISOR -> Preview + ImageCapture + ImageAnalysis
    LaunchedEffect(lensFacing, isVideoCameraMode, previewView) {
        val pv = previewView ?: return@LaunchedEffect
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraProvider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }
        activeCameraProvider = cameraProvider

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(pv.surfaceProvider)
        }
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        if (isRecordingVideo && activeRecording != null) {
            try {
                activeRecording?.stop()
            } catch (_: Exception) {}
            activeRecording = null
            isRecordingVideo = false
        }

        try {
            activeImageAnalysis?.clearAnalyzer()
        } catch (_: Exception) {}
        activeImageAnalysis = null

        if (isVideoCameraMode) {
            imageCapture = null
            val qualitySelector = androidx.camera.video.QualitySelector.from(
                androidx.camera.video.Quality.HD,
                androidx.camera.video.FallbackStrategy.higherQualityOrLowerThan(androidx.camera.video.Quality.SD)
            )
            val recorder = androidx.camera.video.Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            val vCapture = androidx.camera.video.VideoCapture.withOutput(recorder)
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    vCapture
                )
                cameraControl = camera.cameraControl
                videoCapture = vCapture
                boundUseCasesSummary = "VIDEO: Preview + VideoCapture (HD/SD fallback)"
                if (pendingAutoStartVideo) {
                    pendingAutoStartVideo = false
                    delay(350)
                    triggerVideoRecordingToggle()
                }
            } catch (e: Exception) {
                videoCapture = null
                boundUseCasesSummary = "VIDEO FALLBACK ERROR: ${e.javaClass.simpleName}"
                lastStatusMessage = "Ошибка видео-потока HAL: ${e.localizedMessage}"
                pendingAutoStartVideo = false
                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                    cameraControl = camera.cameraControl
                } catch (_: Exception) {}
            }
        } else {
            videoCapture = null
            pendingAutoStartVideo = false

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
                    val expoTelemetry = antiBlurEngine.computeHistogramAndZebra(
                        yBuffer = plane.buffer,
                        width = imageProxy.width,
                        height = imageProxy.height,
                        rowStride = plane.rowStride,
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        zebraThreshold = ZverPrefs.getZebraThreshold(context),
                        collectZebra = isZebraEnabled
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
                    overexposedPercent = expoTelemetry.overexposedPct
                    underexposedPercent = expoTelemetry.underexposedPct
                    if (isHistogramEnabled) {
                        liveHistogramBins32 = expoTelemetry.bins32
                        liveMeanLuma = expoTelemetry.meanLuma
                    }
                    if (isZebraEnabled) {
                        liveZebraPoints = expoTelemetry.zebraHotspots
                    } else if (liveZebraPoints.isNotEmpty()) {
                        liveZebraPoints = emptyList()
                    }

                    if (visorMode != VisorMode.NORMAL && !isStealthBlackoutActive) {
                        val res = cyberVisorEngine.processYuv420Frame(
                            imageProxy = imageProxy,
                            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                            isFrontCamera = (lensFacing == CameraSelector.LENS_FACING_FRONT)
                        )
                        if (res != null) {
                            val tick = visorTickCounter.incrementAndGet()
                            visorDisplayState = Triple(res.bitmap, res.telemetry, tick)
                        }
                    }
                } finally {
                    imageProxy.close()
                }
            }

            activeImageAnalysis = imageAnalysis
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    capture,
                    imageAnalysis
                )
                cameraControl = camera.cameraControl
                imageCapture = capture
                boundUseCasesSummary = "PHOTO/OPTIC: Preview + ImageCapture + ImageAnalysis (YUV)"
            } catch (eAnalysis: Exception) {
                try {
                    activeImageAnalysis = null
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        capture
                    )
                    cameraControl = camera.cameraControl
                    imageCapture = capture
                    boundUseCasesSummary = "FALLBACK (без ImageAnalysis): Preview + ImageCapture (${eAnalysis.javaClass.simpleName})"
                    lastStatusMessage = "ВНИМАНИЕ HAL: ImageAnalysis отключён (только Фото)"
                } catch (e: Exception) {
                    boundUseCasesSummary = "CAMERA BIND ERROR: ${e.javaClass.simpleName}"
                    lastStatusMessage = "Ошибка камеры: ${e.localizedMessage}"
                }
            }
        }
    }

    LaunchedEffect(flashMode) { imageCapture?.flashMode = flashMode }
    LaunchedEffect(exposureIndex, proProfile) {
        try {
            cameraControl?.setExposureCompensationIndex(exposureIndex.coerceIn(proProfile.evRange.first, proProfile.evRange.last))
        } catch (_: Exception) {}
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
                    StealthCoverMode.BROWSER_MOCK -> StealthCoverMode.BLACKOUT
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
            "ВИЗОР" to "VISOR",
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
                visorMode = VisorMode.NORMAL
                lastStatusMessage = "РЕЖИМ: ФОТОСЪЁМКА"
            }
            "VIDEO" -> {
                visorMode = VisorMode.NORMAL
                lastStatusMessage = "РЕЖИМ: ВИДЕО В СЕЙФ"
            }
            "NIGHT" -> {
                exposureIndex = 1
                lastStatusMessage = "РЕЖИМ: НОЧЬ (+1 СВЕТОСИЛА)"
            }
            "VISOR" -> {
                if (visorMode == VisorMode.NORMAL) {
                    visorMode = VisorMode.VEIN_HEMO
                }
                lastStatusMessage = "РЕЖИМ: БИО-ВИЗОР (${visorMode.title})"
            }
            "SENTINEL" -> {
                isDocumentModeActive = false
                lastStatusMessage = "РЕЖИМ: КИБЕР-ДОЗОР (SENTINEL v5)"
            }
            "DOCUMENTS" -> {
                isDocumentModeActive = true
                visorMode = VisorMode.NORMAL
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
        isHistogramEnabled,
        isZebraEnabled,
        isProPanelExpanded,
        photoIqProfile != PhotoIqEngine.PhotoIqProfile.OFF,
        multiFrameMode != null,
        isAudioWatcherActive,
        isAutoShutterEnabled,
        isDocumentModeActive,
        isFocusBracketingActive,
        isDcpDehazeActive,
        visorMode != VisorMode.NORMAL,
        timerSeconds > 0,
        isVoiceControlActive,
        isAssistModeActive
    ).count { it }

    LaunchedEffect(Unit) {
        ByokManager.syncFromPrefs(context)
    }
    val hasByokKey by ByokManager.hasKeyFlow.collectAsState()

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
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Live CyberVisorEngine Biophysical / Optical Overlay (rendered only when visorMode != NORMAL)
        val activeVisorFrame = if (visorMode != VisorMode.NORMAL) visorDisplayState else null
        if (activeVisorFrame != null && !activeVisorFrame.first.isRecycled) {
            Image(
                bitmap = activeVisorFrame.first.asImageBitmap(),
                contentDescription = "Био-Визор (${visorMode.title})",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (visorMode == VisorMode.WIREFRAME_POSE) {
                val wf = activeVisorFrame.second.poseWireframe
                if (wf != null && (wf.bones.isNotEmpty() || wf.joints.isNotEmpty())) {
                    val bmpW = activeVisorFrame.first.width.toFloat().coerceAtLeast(1f)
                    val bmpH = activeVisorFrame.first.height.toFloat().coerceAtLeast(1f)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scale = kotlin.math.max(size.width / bmpW, size.height / bmpH)
                        val drawW = bmpW * scale
                        val drawH = bmpH * scale
                        val offX = (size.width - drawW) * 0.5f
                        val offY = (size.height - drawH) * 0.5f
                        val boneStroke = 2.5.dp.toPx()
                        val jointRadius = 4.5.dp.toPx()
                        for ((a, b) in wf.bones) {
                            drawLine(
                                color = NeonCyan,
                                start = Offset(offX + a.x * drawW, offY + a.y * drawH),
                                end = Offset(offX + b.x * drawW, offY + b.y * drawH),
                                strokeWidth = boneStroke
                            )
                        }
                        for (j in wf.joints) {
                            drawCircle(
                                color = CyberGreen,
                                radius = jointRadius,
                                center = Offset(offX + j.x * drawW, offY + j.y * drawH)
                            )
                        }
                    }
                }
            }
        }

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

        // Zebra Highlights Overlay (P1)
        if (isZebraEnabled) {
            ZebraExposureOverlay(
                zebraHotspots = liveZebraPoints,
                overexposedPct = overexposedPercent,
                threshold = ZverPrefs.getZebraThreshold(context),
                modifier = Modifier.fillMaxSize()
            )
        }

        // Live Luma Histogram HUD (P1)
        if (isHistogramEnabled) {
            LiveHistogramOverlay(
                bins32 = liveHistogramBins32,
                overexposedPct = overexposedPercent,
                underexposedPct = underexposedPercent,
                meanLuma = liveMeanLuma,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 74.dp)
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

                    // Row 4: P1 Pro Camera2, Histogram, Zebra & Photo IQ
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DrawerTileButton(
                            icon = Icons.Default.Tune,
                            label = "PRO HAL",
                            active = isProPanelExpanded,
                            accent = NeonYellow,
                            modifier = Modifier.weight(1f).testTag("drawer_pro_toggle")
                        ) {
                            isProPanelExpanded = !isProPanelExpanded
                            isToolsDrawerOpen = false
                        }

                        DrawerTileButton(
                            icon = Icons.Default.BarChart,
                            label = "Гистогр",
                            active = isHistogramEnabled,
                            accent = CyberGreen,
                            modifier = Modifier.weight(1f).testTag("drawer_histogram_toggle")
                        ) { isHistogramEnabled = !isHistogramEnabled }

                        DrawerTileButton(
                            icon = Icons.Default.Grain,
                            label = "Zebra",
                            active = isZebraEnabled,
                            accent = NeonYellow,
                            modifier = Modifier.weight(1f).testTag("drawer_zebra_toggle")
                        ) { isZebraEnabled = !isZebraEnabled }

                        DrawerTileButton(
                            icon = Icons.Default.AutoAwesome,
                            label = "IQ:${photoIqProfile.badgeRu}",
                            active = photoIqProfile != PhotoIqEngine.PhotoIqProfile.OFF,
                            accent = NeonCyan,
                            modifier = Modifier.weight(1f).testTag("drawer_iq_cycle")
                        ) {
                            val vals = PhotoIqEngine.PhotoIqProfile.values()
                            val next = vals[(vals.indexOf(photoIqProfile) + 1) % vals.size]
                            photoIqProfile = next
                            ZverPrefs.setPhotoIqProfile(context, next)
                            lastStatusMessage = "PHOTO IQ: ${next.labelRu}"
                        }
                    }

                    // Row 5: P2 Multi-Frame Stack + P3 PCB Lab & Audio Watcher
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DrawerTileButton(
                            icon = Icons.Default.FilterHdr,
                            label = "Стек:${multiFrameMode?.badgeRu ?: "OFF"}",
                            active = multiFrameMode != null,
                            accent = NeonCyan,
                            modifier = Modifier.weight(1f).testTag("drawer_stack_cycle")
                        ) {
                            val options = listOf<MultiFrameStacker.StackMode?>(null) + MultiFrameStacker.StackMode.values().toList()
                            val next = options[(options.indexOf(multiFrameMode) + 1) % options.size]
                            multiFrameMode = next
                            ZverPrefs.setMultiFrameMode(context, next)
                            lastStatusMessage = if (next == null) "МУЛЬТИ-КАДР: ВЫКЛ" else "МУЛЬТИ-КАДР: ${next.labelRu}"
                        }

                        DrawerTileButton(
                            icon = Icons.Default.Memory,
                            label = "Плата PCB",
                            active = showPcbLabDialog,
                            accent = NeonCyan,
                            modifier = Modifier.weight(1f).testTag("drawer_pcb_lab")
                        ) {
                            isToolsDrawerOpen = false
                            showPcbLabDialog = true
                        }

                        DrawerTileButton(
                            icon = Icons.Default.GraphicEq,
                            label = if (isAudioWatcherActive) "Акуст:${audioThresholdDbfs.toInt()}" else "Акустик",
                            active = isAudioWatcherActive,
                            accent = NeonYellow,
                            modifier = Modifier.weight(1f).testTag("drawer_audio_watcher")
                        ) {
                            requestAudioWatcherToggle()
                        }

                        DrawerTileButton(
                            icon = Icons.Default.DeleteForever,
                            label = "Шредер",
                            active = false,
                            accent = CyberRed,
                            modifier = Modifier.weight(1f).testTag("panic_shred_button")
                        ) {
                            isToolsDrawerOpen = false
                            showPanicConfirm = true
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

            // Notification Banner docked cleanly below Top HUD Pill & Drawer (zero status-bar / pill overlap)
            AnimatedVisibility(
                visible = isCapturing || lastStatusMessage != null || isRecordingVideo,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp)
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
                            val mb = recordingBytesWritten / (1024f * 1024f)
                            val statusText = if (recordingDurationSeconds >= 2 && recordingBytesWritten == 0L) {
                                "🔴 ОЖИДАНИЕ КАДРОВ HAL: $timerStr"
                            } else if (recordingBytesWritten > 0L) {
                                String.format(Locale.US, "🔴 ЗАПИСЬ: %s • %.1f МБ", timerStr, mb)
                            } else {
                                "🔴 ЗАПИСЬ В СЕЙФ: $timerStr"
                            }
                            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(CyberRed))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(statusText, color = CyberRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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

        // BOTTOM ERGONOMIC CONTROL DECK
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // P3 Live Audio Watcher Meter Strip (when active)
            if (isAudioWatcherActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CyberBlack.copy(alpha = 0.85f))
                        .border(
                            1.dp,
                            if (audioTelemetry.isAboveThreshold) CyberRed else NeonYellow.copy(alpha = 0.55f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("audio_watcher_hud"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🔊 ${audioTelemetry.rmsDbfs.toInt()} dBFS (ПИК ${audioTelemetry.peakDbfs.toInt()}) • ПОРОГ ${audioThresholdDbfs.toInt()}",
                            color = if (audioTelemetry.isAboveThreshold) CyberRed else NeonYellow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.height(14.dp)
                        ) {
                            audioTelemetry.waveformBars.forEach { bar ->
                                val hDp = (2f + bar * 12f).dp
                                Box(
                                    modifier = Modifier
                                        .width(5.dp)
                                        .height(hDp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (audioTelemetry.isAboveThreshold) CyberRed else CyberGreen)
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(-34.0, -26.0, -18.0).forEach { thr ->
                            val sel = audioThresholdDbfs == thr
                            Text(
                                text = "${thr.toInt()}",
                                color = if (sel) Color.Black else NeonYellow,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) NeonYellow else CyberSurface)
                                    .clickable {
                                        audioThresholdDbfs = thr
                                        lastStatusMessage = "АКУСТИК ПОРОГ: ${thr.toInt()} dBFS"
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

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
            // CYBER-VISOR / BIO-SCANNER LIVE CONTROL DECK
            if (selectedCameraMode == "VISOR" || visorMode != VisorMode.NORMAL) {
                CyberVisorControlCard(
                    activeMode = visorMode,
                    telemetry = visorDisplayState?.second ?: VisorTelemetry(mode = visorMode),
                    hasVisorFrame = (visorDisplayState != null),
                    onSelectVisorMode = { m ->
                        visorMode = m
                        if (m == VisorMode.NORMAL && selectedCameraMode == "VISOR") {
                            selectedCameraMode = "PHOTO"
                        }
                        lastStatusMessage = "ВИЗОР: ${m.title}"
                    },
                    onSavePulseMetrics = { bpm, hrv ->
                        ZverPrefs.saveLastPulseBpm(context, bpm, hrvMs = hrv ?: 0)
                        val clipText = if (hrv != null) "ЧСС: $bpm BPM | HRV (~6-8с): $hrv мс" else "ЧСС: $bpm BPM"
                        SecureClipboard.copySensitive(context, "Zver BioVisor", clipText)
                        triggerHapticFeedback(isStealth = true)
                        val hrvMsg = hrv?.let { " / HRV ~${it}мс" } ?: ""
                        lastStatusMessage = "❤️ ЗАМЕР: $bpm BPM$hrvMsg (СКОПИРОВАНО 45с)"
                    },
                    onSaveVisorSnapshot = {
                        val snapBmp = cyberVisorEngine.snapshotLatestBitmapCopy()
                        if (snapBmp != null) {
                            scope.launch {
                                try {
                                    val bytes = withContext(Dispatchers.Default) {
                                        ExifStripper.processBitmapClean(
                                            sourceBitmap = snapBmp,
                                            recycleSource = true,
                                            preset = ZverPrefs.getCompressionPreset(context),
                                            antiPrnu = false
                                        ).cleanBytes
                                    }
                                    val savedRam = withContext(Dispatchers.IO) {
                                        if (ZverPrefs.isRamOnlyMode(context)) {
                                            GhostCryptoVault.addRamShot(bytes.clone(), isVideo = false)
                                            true
                                        } else {
                                            val (file, cos) = GhostCryptoVault.openEncryptedOutputStream(
                                                context,
                                                prefix = GhostCryptoVault.MEDIA_PHOTO
                                            )
                                            cos.use { it.write(bytes); it.flush() }
                                            false
                                        }
                                    }
                                    Arrays.fill(bytes, 0.toByte())
                                    vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                                    triggerHapticFeedback(isStealth = true)
                                    lastStatusMessage = "🔬 КАДР ВИЗОРА (${visorMode.badgeRu}) СОХРАНЁН В ${if (savedRam) "ОЗУ" else "СЕЙФ"}"
                                } catch (e: Exception) {
                                    lastStatusMessage = "ОШИБКА ВИЗОРА: ${e.message}"
                                }
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // P1 Collapsible Pro Camera2 Panel
            AnimatedVisibility(visible = isProPanelExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ProCameraControlPanel(
                        profile = proProfile,
                        exposureIndex = exposureIndex,
                        onExposureIndexChange = { exposureIndex = it },
                        awbPreset = awbPreset,
                        onAwbPresetChange = { awbPreset = it },
                        manualFocusNorm = manualFocusNorm,
                        onManualFocusChange = { manualFocusNorm = it },
                        isoOverride = isoOverride,
                        onIsoChange = { isoOverride = it },
                        shutterNsOverride = shutterNsOverride,
                        onShutterNsChange = { shutterNsOverride = it },
                        isAeLocked = isAeLocked,
                        onAeLockChange = { isAeLocked = it },
                        photoIqProfile = photoIqProfile,
                        onPhotoIqChange = { iq ->
                            photoIqProfile = iq
                            ZverPrefs.setPhotoIqProfile(context, iq)
                            lastStatusMessage = "PHOTO IQ: ${iq.labelRu}"
                        },
                        isHistogramEnabled = isHistogramEnabled,
                        onToggleHistogram = { isHistogramEnabled = !isHistogramEnabled },
                        isZebraEnabled = isZebraEnabled,
                        onToggleZebra = { isZebraEnabled = !isZebraEnabled }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // Zoom Intelligence Banner when zoomed >= 1.8x
            if (isZoomIqEnabled && zoomRatio >= 1.8f) {
                val zoomAdvice = ZoomIntelligence.evaluateZoomTier(zoomRatio)
                Text(
                    text = if (zoomAdvice.requiresSteadyHand && !isGyroStable) "${zoomAdvice.badgeText} • ДЕРЖИ РОВНЕЕ!" else zoomAdvice.badgeText,
                    color = if (zoomAdvice.requiresSteadyHand && !isGyroStable) NeonYellow else NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CyberBlack.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            // Compact Zoom + PRO + EV Floating Strip
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
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
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
                                    val advice = ZoomIntelligence.evaluateZoomTier(z)
                                    lastStatusMessage = if (isZoomIqEnabled && z >= 1.8f) advice.badgeText else "ЗУМ: ${z.toInt()}x"
                                }
                                .padding(horizontal = 8.dp),
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

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (multiFrameMode != null) {
                        Box(
                            modifier = Modifier
                                .height(38.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(NeonCyan.copy(alpha = 0.22f))
                                .border(1.dp, NeonCyan, RoundedCornerShape(20.dp))
                                .clickable {
                                    val options = listOf<MultiFrameStacker.StackMode?>(null) + MultiFrameStacker.StackMode.values().toList()
                                    val next = options[(options.indexOf(multiFrameMode) + 1) % options.size]
                                    multiFrameMode = next
                                    ZverPrefs.setMultiFrameMode(context, next)
                                    lastStatusMessage = if (next == null) "МУЛЬТИ-КАДР: ВЫКЛ" else "МУЛЬТИ-КАДР: ${next.labelRu}"
                                }
                                .padding(horizontal = 8.dp)
                                .testTag("stack_mode_pill"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${multiFrameMode!!.badgeRu}×3",
                                color = NeonCyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // PRO panel quick pill
                    Box(
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isProPanelExpanded) NeonCyan.copy(alpha = 0.22f) else CyberBlack.copy(alpha = 0.55f))
                            .border(1.dp, if (isProPanelExpanded) NeonCyan else CyberBorder, RoundedCornerShape(20.dp))
                            .clickable { isProPanelExpanded = !isProPanelExpanded }
                            .padding(horizontal = 10.dp)
                            .testTag("pro_panel_pill"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "PRO",
                            color = if (isProPanelExpanded) NeonCyan else CyberMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Compact EV cycle pill
                    val evLabel = when {
                        exposureIndex > 0 -> "EV+$exposureIndex"
                        exposureIndex < 0 -> "EV$exposureIndex"
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
                            .padding(horizontal = 10.dp),
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
                                    triggerVideoRecordingToggle()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = "Запись видео", tint = CyberRed, modifier = Modifier.size(22.dp))
                        }
                    }
                }

                // Right Cluster: Vika AI Sheet (Shredder moved safely into Tools Drawer)
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
                onDismiss = {
                    showSettingsDialog = false
                    photoIqProfile = ZverPrefs.getPhotoIqProfile(context)
                    isZoomIqEnabled = ZverPrefs.isZoomIqEnabled(context)
                },
                onStatusMessage = { msg -> lastStatusMessage = msg },
                onOpenPinSetup = {
                    forcePinSetupFlow = true
                    showVaultDialog = true
                },
                onOpenVikaSheet = {
                    vikaSheetInitialTab = VikaSheetTab.TASKS
                    showVikaSheet = true
                },
                onCamouflageChanged = { mode ->
                    currentCamouflage = mode
                },
                boundUseCasesSummary = boundUseCasesSummary,
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

        if (showPcbLabDialog) {
            PcbLabDialog(
                initialBitmap = null,
                onCaptureFromCamera = {
                    val cap = imageCapture ?: return@PcbLabDialog null
                    val manager = CaptureManager(
                        context = context,
                        imageCapture = cap,
                        captureExecutor = captureExecutor
                    )
                    manager.captureBitmapInRamForOfflineCv()
                },
                onSavedToVault = { msg ->
                    vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                    lastStatusMessage = msg
                    showPcbLabDialog = false
                },
                onDismiss = { showPcbLabDialog = false }
            )
        }

        // Emergency Crypto-Shred Confirmation (requires typing 999)
        if (showPanicConfirm) {
            var shredConfirmCode by remember { mutableStateOf("") }
            val canShred = shredConfirmCode.trim() == "999"
            AlertDialog(
                onDismissRequest = {
                    shredConfirmCode = ""
                    showPanicConfirm = false
                },
                title = { Text("ЭКСТРЕННЫЙ ШРЕДЕР СХРОНА", color = CyberRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Мгновенно затирает весь контейнер случайным шумом и навсегда уничтожает аппаратные ключи процессора. Восстановление невозможно.",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = shredConfirmCode,
                            onValueChange = { if (it.length <= 6) shredConfirmCode = it },
                            label = { Text("Введи 999 для подтверждения", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("shred_confirm_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberRed,
                                unfocusedBorderColor = CyberBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            GhostCryptoVault.cryptoShred(context)
                            com.example.crypto.MonoVaultEngine.cryptoShredMonolith(context)
                            ByokManager.clearSessionMemory()
                            SentinelEngine.clearSession()
                            SecureClipboard.clearIfOwned(context)
                            sentinelArmed = false
                            sentinelEvents = emptyList()
                            vaultCount = 0
                            shredConfirmCode = ""
                            showPanicConfirm = false
                            lastStatusMessage = "СХРОН И КЛЮЧИ ПОЛНОСТЬЮ УНИЧТОЖЕНЫ"
                        },
                        enabled = canShred,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberRed,
                            disabledContainerColor = CyberRed.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.testTag("shred_confirm_execute_button")
                    ) {
                        Text("СТЕРЕТЬ СЕЙЧАС", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            shredConfirmCode = ""
                            showPanicConfirm = false
                        }
                    ) {
                        Text("ОТМЕНА", color = CyberMuted)
                    }
                },
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

@Composable
private fun CyberVisorControlCard(
    activeMode: VisorMode,
    telemetry: VisorTelemetry,
    hasVisorFrame: Boolean,
    onSelectVisorMode: (VisorMode) -> Unit,
    onSavePulseMetrics: (Int, Int?) -> Unit,
    onSaveVisorSnapshot: () -> Unit
) {
    var isCollapsed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CyberBlack.copy(alpha = 0.86f))
            .border(1.dp, NeonCyan.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🔬 БИО-ВИЗОР // ${activeMode.badgeRu}",
                color = NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeMode != VisorMode.NORMAL && hasVisorFrame) {
                    Text(
                        text = "📸 В СЕЙФ",
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberGreen)
                            .clickable { onSaveVisorSnapshot() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("visor_snapshot_button")
                    )
                }
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberSurface)
                        .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .clickable { isCollapsed = !isCollapsed }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("visor_card_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isCollapsed) "▲ РАЗВЕРНУТЬ" else "▼ СВЕРНУТЬ",
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }
        }

        if (!isCollapsed) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VisorMode.values().forEach { m ->
                val sel = m == activeMode
                Text(
                    text = m.title,
                    color = if (sel) Color.Black else CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) NeonCyan else CyberSurface)
                        .border(1.dp, if (sel) NeonCyan else CyberBorder, RoundedCornerShape(8.dp))
                        .clickable { onSelectVisorMode(m) }
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                        .testTag("visor_mode_${m.name.lowercase()}")
                )
            }
        }

        when (activeMode) {
            VisorMode.BIO_RPPG -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val bpmText = telemetry.estimatedBpm?.let { "$it BPM" } ?: "Калибровка..."
                        val hrvText = telemetry.estimatedHrvMs?.let { " • HRV ~${it}мс (6-8с)" } ?: ""
                        val qualPct = (telemetry.signalQuality * 100f).toInt()
                        Text(
                            text = "❤️ ЧСС: $bpmText$hrvText • Сигнал $qualPct%",
                            color = if (telemetry.estimatedBpm != null) CyberGreen else NeonYellow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                        if (telemetry.rppgSourceLabel.isNotBlank()) {
                            Text(
                                text = "🔒 AE/AWB Lock • ${telemetry.rppgSourceLabel}",
                                color = NeonCyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = { telemetry.signalQuality.coerceIn(0f, 1f) },
                            color = if (telemetry.signalQuality >= 0.35f) CyberGreen else NeonYellow,
                            trackColor = CyberBorder,
                            modifier = Modifier.fillMaxWidth(0.9f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                    }
                    telemetry.estimatedBpm?.let { bpm ->
                        Text(
                            text = "📋 ФИКС ЧСС",
                            color = Color.Black,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyberGreen)
                                .clickable { onSavePulseMetrics(bpm, telemetry.estimatedHrvMs) }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("visor_save_bpm_button")
                        )
                    }
                }
                Text(
                    text = activeMode.hintRu,
                    color = CyberMuted,
                    fontSize = 9.sp
                )
            }
            VisorMode.SKIN_CHROMA -> {
                val compactPct = (telemetry.skinCompactness * 100f).toInt()
                Text(
                    text = "👤 Кожа YCbCr: ${telemetry.skinPercent}% • Сплочён. $compactPct% • Движ. ${telemetry.movingSkinPercent}%",
                    color = if (telemetry.skinPercent >= 2 && compactPct >= 18) CyberGreen else NeonYellow,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                Text(
                    text = activeMode.hintRu,
                    color = CyberMuted,
                    fontSize = 9.sp
                )
            }
            VisorMode.EULER_AMP -> {
                val stabPct = (telemetry.eulerStability * 100f).toInt()
                Text(
                    text = "〰 1D-стабилизация сдвига: $stabPct% • Демпфер краёв активен",
                    color = if (stabPct >= 65) CyberGreen else NeonYellow,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                Text(
                    text = activeMode.hintRu,
                    color = CyberMuted,
                    fontSize = 9.sp
                )
            }
            VisorMode.WIREFRAME_POSE -> {
                val count = telemetry.poseWireframe?.visibleCount ?: 0
                Text(
                    text = if (count >= 3) {
                        "🦴 Скелет: $count/33 точек в реальном времени"
                    } else {
                        "🦴 Скелет: поиск опорных точек ($count/33)..."
                    },
                    color = if (count >= 3) CyberGreen else NeonYellow,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                Text(
                    text = activeMode.hintRu,
                    color = CyberMuted,
                    fontSize = 9.sp
                )
            }
            VisorMode.IR_BEACON -> {
                val statusTxt = telemetry.irStatusLabel.ifBlank {
                    if (telemetry.irPeakDetected) {
                        "🚨 ИК-ИЗЛУЧЕНИЕ ОБНАРУЖЕНО (${telemetry.irHotPixels} пикс.)"
                    } else {
                        activeMode.hintRu
                    }
                }
                Text(
                    text = statusTxt,
                    color = when {
                        telemetry.irPeakDetected -> CyberRed
                        telemetry.irHotPixels > 0 -> NeonYellow
                        else -> CyberMuted
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (telemetry.irPeakDetected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 9.sp
                )
            }
            else -> {
                Text(
                    text = activeMode.hintRu,
                    color = CyberMuted,
                    fontSize = 9.sp
                )
            }
        }
        }
    }
}

