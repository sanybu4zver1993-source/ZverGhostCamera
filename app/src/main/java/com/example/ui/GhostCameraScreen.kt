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
import android.util.Size
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.MainActivity
import com.example.assist.ByokManager
import com.example.assist.GeminiByokClient
import com.example.assist.GhostAssistService
import com.example.camera.AnalysisScheduler
import com.example.camera.AntiBlurSensorEngine
import com.example.camera.AudioWatcherEngine
import com.example.camera.CameraBindingCoordinator
import com.example.camera.CameraCapabilityProfiler
import com.example.camera.CameraTab
import com.example.camera.CameraUiState
import com.example.camera.CaptureManager
import com.example.camera.ExifStripper
import com.example.camera.Mp4Sanitizer
import com.example.camera.MultiFrameStacker
import com.example.camera.PhotoIqEngine
import com.example.camera.RetroReflectionDetector
import com.example.camera.StorageWatchdog
import com.example.camera.ThermalThrottleController
import com.example.camera.TimeLapseRecorder
import com.example.camera.VoiceControlManager
import com.example.camera.ZoomIntelligence
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
import com.example.privacy.LocalSymbiosisCvEngine
import com.example.security.AntiForensicsGuard
import com.example.security.CamouflageManager
import com.example.security.SecureClipboard
import com.example.security.SentinelEngine
import com.example.security.ZverPrefs
import com.example.visor.ClaheLutBuilder
import com.example.visor.CyberVisorEngine
import com.example.visor.GlVisorDualRenderer
import com.example.visor.VisorMode
import com.example.visor.VisorVideoRecorder
import com.example.visor.shaderModeId
import com.example.visor.supportsGpuShader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Arrays
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private class AnalyzerRuntimeFlags {
    @Volatile var histogramEnabled: Boolean = false
    @Volatile var zebraEnabled: Boolean = false
    @Volatile var falseColorEnabled: Boolean = false
    @Volatile var focusPeakingEnabled: Boolean = false
    @Volatile var sentinelArmed: Boolean = false
    @Volatile var selectedCameraMode: String = "PHOTO"
    @Volatile var visorMode: VisorMode = VisorMode.NORMAL
    @Volatile var useGpuShader: Boolean = false
    @Volatile var stealthBlackoutActive: Boolean = false
    @Volatile var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    @Volatile var zebraThreshold: Int = 240
}

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
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var activeCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var activePreview by remember { mutableStateOf<Preview?>(null) }
    var activeImageAnalysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var resumeBindToken by remember { mutableIntStateOf(0) }
    var pinSessionLockedWarning by remember { mutableStateOf(false) }

    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val antiBlurEngine = remember { AntiBlurSensorEngine(context) }
    val telemetry = remember { CameraTelemetryState() }
    val analyzerFlags = remember { AnalyzerRuntimeFlags() }

    var isCapturing by remember { mutableStateOf(false) }
    var lastStatusMessage by remember { mutableStateOf<String?>(null) }
    var showShutterFlash by remember { mutableStateOf(false) }
    var vaultCount by remember { mutableIntStateOf(0) }
    var showVaultDialog by remember { mutableStateOf(false) }
    var forcePinSetupFlow by remember { mutableStateOf(false) }
    var showPanicConfirm by remember { mutableStateOf(false) }
    var showCamouflageDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSelfTestDialog by remember { mutableStateOf(false) }
    var isToolsDrawerOpen by remember { mutableStateOf(false) }
    var isTopOverflowMenuOpen by remember { mutableStateOf(false) }
    var showVikaSheet by remember { mutableStateOf(false) }
    var vikaSheetInitialTab by remember { mutableStateOf(VikaSheetTab.TASKS) }
    var isVoiceControlActive by remember { mutableStateOf(false) }
    var previousCameraMode by remember { mutableStateOf("PHOTO") }

    // Pro Camera2 & Photo IQ (P1) States
    var proProfile by remember { mutableStateOf(CameraCapabilityProfiler.CameraProProfile.fallback(lensFacing)) }
    var isProPanelExpanded by remember { mutableStateOf(false) }
    var awbPreset by remember { mutableStateOf(CameraCapabilityProfiler.AwbPreset.AUTO) }
    var manualFocusNorm by remember { mutableStateOf<Float?>(null) }
    var isoOverride by remember { mutableStateOf<Int?>(null) }
    var shutterNsOverride by remember { mutableStateOf<Long?>(null) }
    var isAeLocked by remember { mutableStateOf(false) }
    var isAwbLocked by remember { mutableStateOf(false) }
    var targetFpsRange by remember { mutableStateOf<IntRange?>(null) }
    LaunchedEffect(lensFacing) {
        targetFpsRange = null
        isAwbLocked = false
        proProfile = withContext(Dispatchers.IO) {
            CameraCapabilityProfiler.profileCamera(context, lensFacing)
        }
    }
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
    var sentinelConfig by remember { mutableStateOf(SentinelEngine.loadPersistedConfig(context)) }
    var sentinelEvents by remember { mutableStateOf(SentinelEngine.getEvents()) }
    var sentinelJobActive by remember { mutableStateOf(false) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    // CyberVisorEngine ("БИО-ВИЗОР / КИБЕР-СКАНЕР") + Dual-Output OpenGL ES 3.0 Renderer
    val cyberVisorEngine = remember { CyberVisorEngine() }
    val analysisScheduler = remember { AnalysisScheduler() }
    val visorTickCounter = remember { AtomicLong(0L) }
    var visorMode by remember { mutableStateOf(VisorMode.NORMAL) }
    var gpuVisorFailed by remember { mutableStateOf(false) }
    val bindingCoordinator = remember { CameraBindingCoordinator(context, scope) }
    val thermalMonitor = remember { ThermalThrottleController.ThermalMonitor(context) }
    val glRenderer = remember {
        GlVisorDualRenderer { err ->
            scope.launch(Dispatchers.Main) {
                gpuVisorFailed = true
                lastStatusMessage = "ВНИМАНИЕ GPU: $err (CPU режим)"
            }
        }.apply {
            onFirstFrameRendered = { bindingCoordinator.markFirstFrameReceived() }
        }
    }
    val useGpuVisorPipeline = (visorMode != VisorMode.NORMAL && visorMode.supportsGpuShader() && !gpuVisorFailed)

    LaunchedEffect(visorMode, useGpuVisorPipeline) {
        cyberVisorEngine.currentMode = visorMode
        glRenderer.shaderModeId = visorMode.shaderModeId()
        glRenderer.poseWireframe = null
        if (visorMode == VisorMode.NORMAL) {
            telemetry.visorDisplayState = null
            telemetry.gpuVisorTelemetry = null
        } else if (useGpuVisorPipeline) {
            telemetry.visorDisplayState = null
            telemetry.gpuVisorTelemetry = com.example.visor.VisorTelemetry(mode = visorMode)
        } else {
            telemetry.gpuVisorTelemetry = null
        }
    }

    // MIUI-style Camera Mode Carousel
    var selectedCameraMode by remember { mutableStateOf("PHOTO") }
    var boundUseCasesSummary by remember { mutableStateOf("ОЖИДАНИЕ КАМЕРЫ...") }
    var pendingAutoStartVideo by remember { mutableStateOf(false) }

    LaunchedEffect(visorMode, lensFacing, selectedCameraMode) {
        analysisScheduler.bumpGeneration()
    }

    // Video Recording + Part B TimeLapse & Anti-Optics States
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    val pendingStopVideoKey = remember { AtomicReference<Pair<javax.crypto.SecretKey, GhostCryptoVault.VaultProfile>?>(null) }
    val visorVideoRecorder = remember { VisorVideoRecorder() }
    var isRecordingVideo by remember { mutableStateOf(false) }

    var isGyroEisEnabled by remember { mutableStateOf(ZverPrefs.isGyroEisEnabled(context)) }
    val timeLapseRecorder = remember { TimeLapseRecorder() }
    var isTimeLapseRecording by remember { mutableStateOf(false) }
    var timeLapseIntervalMs by remember { mutableLongStateOf(ZverPrefs.getTimeLapseIntervalMs(context)) }
    var timeLapseJob by remember { mutableStateOf<Job?>(null) }
    var timeLapseTempFile by remember { mutableStateOf<File?>(null) }

    data class CapturedYSample(
        val yData: ByteArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val isFront: Boolean
    )
    var isRetroScanning by remember { mutableStateOf(false) }
    val pendingRetroSample = remember { AtomicReference<CompletableDeferred<CapturedYSample>?>(null) }

    LaunchedEffect(isRecordingVideo) {
        if (isRecordingVideo) {
            telemetry.recordingDurationSeconds = 0
            var tick = 0
            while (isRecordingVideo) {
                delay(1000)
                telemetry.recordingDurationSeconds++
                tick++
                if (tick % 2 == 0) {
                    val storage = withContext(Dispatchers.IO) {
                        com.example.camera.StorageWatchdog.evaluateAppStorage(context)
                    }
                    if (storage.shouldStopRecording) {
                        if (visorVideoRecorder.isRecording) {
                            isRecordingVideo = false
                            val stoppedMsg = withContext(Dispatchers.IO) {
                                val rawMp4 = visorVideoRecorder.stopAndFinalize(
                                    onBeforeSignalEos = { glRenderer.detachEncoderSurfaceBlocking() }
                                )
                                telemetry.recordingBytesWritten = 0L
                                if (rawMp4 != null && rawMp4.exists() && rawMp4.length() > 0L) {
                                    val safeHeadroom = rawMp4.length() + 16L * 1024L * 1024L
                                    if (storage.availableBytes > safeHeadroom) {
                                        try {
                                            if (ZverPrefs.isRamOnlyMode(context)) {
                                                GhostCryptoVault.addRamShot(rawMp4.readBytes(), isVideo = true)
                                            } else {
                                                GhostCryptoVault.encryptFileToVault(
                                                    context,
                                                    rawMp4,
                                                    prefix = GhostCryptoVault.MEDIA_VIDEO
                                                )
                                            }
                                            GhostCryptoVault.shredFile(rawMp4)
                                            "⛔ ДИСК < 250 МБ (${storage.availableMb} МБ) — ЗАПИСЬ ОСТАНОВЛЕНА И СОХРАНЕНА"
                                        } catch (_: Exception) {
                                            GhostCryptoVault.shredFile(rawMp4)
                                            "⛔ ДИСК < 250 МБ (${storage.availableMb} МБ) — ЗАПИСЬ ОСТАНОВЛЕНА"
                                        }
                                    } else {
                                        GhostCryptoVault.shredFile(rawMp4)
                                        "⛔ ДИСК КРИТИЧЕСКИ МАЛ (${storage.availableMb} МБ) — БУФЕР ОЧИЩЕН"
                                    }
                                } else {
                                    "⛔ ДИСК < 250 МБ (${storage.availableMb} МБ) — ЗАПИСЬ ОСТАНОВЛЕНА"
                                }
                            }
                            vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                            lastStatusMessage = stoppedMsg
                        } else if (activeRecording != null) {
                            try { activeRecording?.stop() } catch (_: Exception) {}
                            activeRecording = null
                            isRecordingVideo = false
                            lastStatusMessage = "⛔ ДИСК < 250 МБ (${storage.availableMb} МБ) — ЗАПИСЬ ОСТАНОВЛЕНА"
                        }
                    }
                }
            }
        }
    }

    var currentCamouflage by remember { mutableStateOf(CamouflageManager.getCurrentCamouflage(context)) }
    var isAssistModeActive by remember { mutableStateOf(false) }

    // Computational & Hardware Sensor States
    var timerSeconds by remember { mutableIntStateOf(0) }
    var burstCount by remember { mutableIntStateOf(1) }
    var countdownRemaining by remember { mutableStateOf<Int?>(null) }
    var focusTapPoint by remember { mutableStateOf<Offset?>(null) }
    var isFocusLocked by remember { mutableStateOf(false) }
    var exposureIndex by remember { mutableIntStateOf(0) }
    var zoomRatio by remember { mutableFloatStateOf(1.0f) }

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

    SideEffect {
        analyzerFlags.histogramEnabled = isHistogramEnabled
        analyzerFlags.zebraEnabled = isZebraEnabled
        analyzerFlags.falseColorEnabled = isFalseColorEnabled
        analyzerFlags.focusPeakingEnabled = isFocusPeakingEnabled
        analyzerFlags.sentinelArmed = sentinelArmed
        analyzerFlags.selectedCameraMode = selectedCameraMode
        analyzerFlags.visorMode = visorMode
        analyzerFlags.useGpuShader = useGpuVisorPipeline
        analyzerFlags.stealthBlackoutActive = isStealthBlackoutActive
        analyzerFlags.lensFacing = lensFacing
        val zThr = ZverPrefs.getZebraThreshold(context)
        analyzerFlags.zebraThreshold = zThr

        glRenderer.peakingEnabled = isFocusPeakingEnabled
        glRenderer.zebraEnabled = isZebraEnabled
        glRenderer.zebraThresholdNorm = (zThr / 255f).coerceIn(0.5f, 0.99f)
        glRenderer.eisEnabled = isGyroEisEnabled
        if (!isGyroEisEnabled) {
            glRenderer.eisOffsetX = 0f
            glRenderer.eisOffsetY = 0f
        }
    }

    LaunchedEffect(isGyroEisEnabled, useGpuVisorPipeline, lensFacing) {
        if (!isGyroEisEnabled || !useGpuVisorPipeline) {
            antiBlurEngine.resetEisIntegrator()
            glRenderer.eisOffsetX = 0f
            glRenderer.eisOffsetY = 0f
            return@LaunchedEffect
        }
        antiBlurEngine.resetEisIntegrator()
        while (isGyroEisEnabled && useGpuVisorPipeline) {
            val (ox, oy) = antiBlurEngine.getStabilizedEisOffset()
            glRenderer.eisOffsetX = ox
            glRenderer.eisOffsetY = oy
            delay(16)
        }
    }

    LaunchedEffect(lastStatusMessage, isCapturing, isRecordingVideo) {
        val msg = lastStatusMessage ?: return@LaunchedEffect
        val upper = msg.uppercase()
        val persistent = isCapturing || isRecordingVideo ||
            msg.startsWith("⏳") ||
            upper.contains("ОШИБКА") || upper.contains("СБОЙ") ||
            upper.contains("НЕТ ДОСТУПА") || upper.contains("SNATCH")
        if (!persistent) {
            delay(4000)
            if (lastStatusMessage == msg) {
                lastStatusMessage = null
            }
        }
    }

    BackHandler(enabled = isToolsDrawerOpen || isProPanelExpanded) {
        when {
            isToolsDrawerOpen -> isToolsDrawerOpen = false
            isProPanelExpanded -> isProPanelExpanded = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val count = GhostCryptoVault.totalVaultItemsCount(context)
            AntiForensicsGuard.assessDeviceSecurity(context)
            MonoVaultEngine.ensureContainerInitialized(context)
            val lockedWarn = GhostCryptoVault.isConfigured(context) && !GhostCryptoVault.hasUnlockedPinSession()
            withContext(Dispatchers.Main) {
                vaultCount = count
                pinSessionLockedWarning = lockedWarn
            }
        }
    }

    LaunchedEffect(Unit) {
        ByokManager.syncFromPrefs(context)
    }
    val hasByokKey by ByokManager.hasKeyFlow.collectAsState()

    // Camera2 Interop: BIO_RPPG AE/AWB lock takes priority; otherwise apply Pro Camera2 overrides
    DisposableEffect(visorMode, cameraControl, awbPreset, manualFocusNorm, isoOverride, shutterNsOverride, isAeLocked, isAwbLocked, targetFpsRange, proProfile) {
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
                (isAwbLocked && proProfile.supportsAwbLock) ||
                targetFpsRange != null ||
                (proProfile.supportsManualExposure && (isoOverride != null || shutterNsOverride != null))
            if (hasProOverride) {
                try {
                    val builder = CaptureRequestOptions.Builder()
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbPreset.camera2Mode)
                    if (isAeLocked && proProfile.supportsAeLock) {
                        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    }
                    if (isAwbLocked && proProfile.supportsAwbLock) {
                        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    }
                    targetFpsRange?.let { fps ->
                        builder.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            android.util.Range(fps.first, fps.last)
                        )
                    }
                    val mf = manualFocusNorm
                    if (mf != null && proProfile.isManualFocusSupported) {
                        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        builder.setCaptureRequestOption(
                            CaptureRequest.LENS_FOCUS_DISTANCE,
                            (mf.coerceIn(0f, 1f) * proProfile.minFocusDiopters)
                        )
                    }
                    val isoRange = proProfile.isoRange
                    val expRange = proProfile.exposureTimeNsRange
                    if (proProfile.supportsManualExposure && isoRange != null && expRange != null && (isoOverride != null || shutterNsOverride != null)) {
                        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        val targetIso = (isoOverride ?: isoRange.first).coerceIn(isoRange.first, isoRange.last)
                        val targetNs = (shutterNsOverride ?: 16_666_666L).coerceIn(expRange.first, expRange.last)
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

    LaunchedEffect(isTorchActive) {
        try { cameraControl?.enableTorch(isTorchActive) } catch (_: Exception) {}
    }

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

    // Clean shutdown before SurfaceView teardown strictly on ON_STOP (preserves active recordings during transient ON_PAUSE dialogs)
    DisposableEffect(lifecycleOwner) {
        var wasStopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasStopped = true
                    val capturedKey = try { GhostCryptoVault.getActiveVaultKey(context) } catch (_: Exception) { null }
                    val capturedProfile = GhostCryptoVault.activeProfile
                    val ramOnlySnapshot = ZverPrefs.isRamOnlyMode(context)

                    if (isRecordingVideo && activeRecording != null) {
                        if (capturedKey != null) {
                            pendingStopVideoKey.set(capturedKey to capturedProfile)
                        }
                        try { activeRecording?.stop() } catch (_: Exception) {}
                        activeRecording = null
                        isRecordingVideo = false
                    }
                    if (visorVideoRecorder.isRecording) {
                        isRecordingVideo = false
                        CoroutineScope(Dispatchers.IO).launch {
                            var rawMp4: File? = null
                            var cleanMp4: File? = null
                            try {
                                rawMp4 = visorVideoRecorder.stopAndFinalize(
                                    onBeforeSignalEos = { glRenderer.detachEncoderSurfaceBlocking() }
                                )
                                if (rawMp4 != null && rawMp4.exists() && rawMp4.length() > 0L) {
                                    val stageDir = GhostCryptoVault.getVideoStageDir(context)
                                    cleanMp4 = File(stageDir, "visor_bg_${System.currentTimeMillis()}.mp4")
                                    val sanitized = Mp4Sanitizer.sanitizeMp4File(rawMp4, cleanMp4)
                                    val targetInput = if (sanitized && cleanMp4.exists()) cleanMp4 else rawMp4
                                    if (ramOnlySnapshot) {
                                        GhostCryptoVault.addRamShot(targetInput.readBytes(), isVideo = true)
                                    } else if (capturedKey != null) {
                                        GhostCryptoVault.encryptFileToVaultWithKey(
                                            context = context,
                                            sourceFile = targetInput,
                                            explicitKey = capturedKey,
                                            explicitProfile = capturedProfile,
                                            prefix = GhostCryptoVault.MEDIA_VIDEO
                                        )
                                    }
                                    GhostCryptoVault.shredFile(rawMp4)
                                    GhostCryptoVault.shredFile(cleanMp4)
                                    val newCount = GhostCryptoVault.totalVaultItemsCount(context)
                                    withContext(Dispatchers.Main) {
                                        vaultCount = newCount
                                        lastStatusMessage = "🎥 ВИЗОР MP4 СОХРАНЁН ПЕРЕД УХОДОМ [#$newCount]"
                                    }
                                } else {
                                    rawMp4?.let { GhostCryptoVault.shredFile(it) }
                                }
                            } catch (_: Exception) {
                                rawMp4?.let { GhostCryptoVault.shredFile(it) }
                                cleanMp4?.let { GhostCryptoVault.shredFile(it) }
                            }
                        }
                    }
                    if (isTimeLapseRecording || timeLapseRecorder.isRecording) {
                        isTimeLapseRecording = false
                        telemetry.isTimeLapseRecording = false
                        timeLapseJob?.cancel()
                        timeLapseJob = null
                        val staleTemp = timeLapseTempFile
                        timeLapseTempFile = null
                        CoroutineScope(Dispatchers.IO).launch {
                            var cleanMp4: File? = null
                            try {
                                val finalized = try { timeLapseRecorder.stopAndFinalize() } catch (_: Exception) { null }
                                val rawMp4 = finalized ?: staleTemp?.takeIf { it.exists() && it.length() > 0L }
                                if (rawMp4 != null && rawMp4.exists() && rawMp4.length() > 0L) {
                                    val stageDir = GhostCryptoVault.getVideoStageDir(context)
                                    cleanMp4 = File(stageDir, "tl_bg_${System.currentTimeMillis()}.mp4")
                                    val sanitized = Mp4Sanitizer.sanitizeMp4File(rawMp4, cleanMp4)
                                    val targetInput = if (sanitized && cleanMp4.exists()) cleanMp4 else rawMp4
                                    if (ramOnlySnapshot) {
                                        GhostCryptoVault.addRamShot(targetInput.readBytes(), isVideo = true)
                                    } else if (capturedKey != null) {
                                        GhostCryptoVault.encryptFileToVaultWithKey(
                                            context = context,
                                            sourceFile = targetInput,
                                            explicitKey = capturedKey,
                                            explicitProfile = capturedProfile,
                                            prefix = GhostCryptoVault.MEDIA_VIDEO
                                        )
                                    }
                                    GhostCryptoVault.shredFile(rawMp4)
                                    GhostCryptoVault.shredFile(cleanMp4)
                                } else {
                                    staleTemp?.let { GhostCryptoVault.shredFile(it) }
                                }
                            } catch (_: Exception) {
                                staleTemp?.let { GhostCryptoVault.shredFile(it) }
                                cleanMp4?.let { GhostCryptoVault.shredFile(it) }
                            }
                        }
                    }

                    bindingCoordinator.cancelPendingWatchdog()
                    glRenderer.detachEncoderSurfaceBlocking()
                    try { activePreview?.setSurfaceProvider(null) } catch (_: Exception) {}
                    activePreview = null
                    try { activeImageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
                    activeImageAnalysis = null
                    try { activeCameraProvider?.unbindAll() } catch (_: Exception) {}
                }
                Lifecycle.Event.ON_START -> {
                    if (wasStopped) {
                        wasStopped = false
                        resumeBindToken++
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    pinSessionLockedWarning =
                        GhostCryptoVault.isConfigured(context) && !GhostCryptoVault.hasUnlockedPinSession()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
        thermalMonitor.start()
        onDispose {
            thermalMonitor.stop()
            bindingCoordinator.cancelPendingWatchdog()
            antiBlurEngine.onSnatchDetected = null
            antiBlurEngine.onFaceDownDetected = null
            antiBlurEngine.onKnockDetected = null
            antiBlurEngine.stop()
            try { activeRecording?.stop() } catch (_: Exception) {}
            if (visorVideoRecorder.isRecording) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        visorVideoRecorder.stopAndFinalize(
                            onBeforeSignalEos = { glRenderer.detachEncoderSurfaceBlocking() }
                        )?.let { GhostCryptoVault.shredFile(it) }
                    } catch (_: Exception) {}
                }
            }
            if (isTimeLapseRecording || timeLapseRecorder.isRecording) {
                timeLapseJob?.cancel()
                timeLapseJob = null
                val staleTemp = timeLapseTempFile
                timeLapseTempFile = null
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        timeLapseRecorder.stopAndFinalize()?.let { GhostCryptoVault.shredFile(it) }
                        staleTemp?.let { GhostCryptoVault.shredFile(it) }
                    } catch (_: Exception) {}
                }
            }
            try { activeImageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
            try { activeCameraProvider?.unbindAll() } catch (_: Exception) {}
            glRenderer.release()
            cyberVisorEngine.release()
            captureExecutor.shutdown()
            analysisExecutor.shutdown()
            MainActivity.onVolumeShutterTrigger = null
            MainActivity.onVideoToggleTrigger = null
            MainActivity.onTorchToggleTrigger = null
            MainActivity.onBlackoutToggleTrigger = null
            MainActivity.onSentinelArmTrigger = null
            MainActivity.onModeSwitchTrigger = null
            MainActivity.onRamOnlyTrigger = null
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
                                    val dossier = sym?.let { "\n${LocalSymbiosisCvEngine.buildCompactGeminiDossier(it)}" }.orEmpty()
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
                    telemetry.sentinelStepLabel = "⚡ СТУК / УДАР!"
                    val cfg = SentinelEngine.getConfig()
                    launchSentinelCapture(
                        reason = "Стук/вибрация (${String.format("%.1f", mag)} м/с²)",
                        useNightClahe = cfg.autoNightClaheEnabled && SentinelEngine.isNightProfileActive()
                    )
                }
            }
        }
    }

    // Telemetry polling loop writing only to CameraTelemetryState
    LaunchedEffect(Unit) {
        while (true) {
            telemetry.gyroAngularSpeed = antiBlurEngine.currentAngularSpeed
            telemetry.isGyroStable = antiBlurEngine.isStable
            telemetry.isTripodMode = antiBlurEngine.isTripodMode
            telemetry.documentTiltDeg = antiBlurEngine.currentTiltDeg
            telemetry.isDocumentParallel = antiBlurEngine.isDocumentParallel
            telemetry.isProximityLocked = isStealthBlackoutActive && antiBlurEngine.isProximityNear
            delay(120)
        }
    }

    LaunchedEffect(isAssistModeActive) {
        val intent = Intent(context, GhostAssistService::class.java)
        if (isAssistModeActive) {
            context.startService(intent)
        } else {
            context.stopService(intent)
        }
    }

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

            triggerHapticFeedback(isStealth = isStealthBlackoutActive)

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
                        photoIqOverride = photoIqProfile,
                        visorMode = visorMode,
                        visorEngine = cyberVisorEngine
                    )
                    val res = stacked.captureResult
                    vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                    val kb = res.encryptedSizeBytes / 1024
                    val contourTag = if (res.isRamOnly) " [ОЗУ]" else ""
                    val ghostNote = if (stacked.stackStats.rejectedGhostPercent > 0) " G-${stacked.stackStats.rejectedGhostPercent}%" else ""
                    lastStatusMessage = "📸 ${res.formatLabel} (${kb}КБ, 0 EXIF) [${res.iqLabel}$ghostNote] за ${res.durationMs}мс$contourTag"
                } else if (isFocusBracketingActive) {
                    lastStatusMessage = "🔬 ФОКУС-СТЕК (3 ДИОПТРИИ + СЛИЯНИЕ)..."
                    val manager = CaptureManager(
                        context = context,
                        imageCapture = cap,
                        captureExecutor = captureExecutor
                    )
                    val stacked = manager.captureFocusBracketStack(
                        cameraControl = cameraControl,
                        proProfile = proProfile,
                        steps = 3,
                        applyDehaze = isDcpDehazeActive,
                        zoomRatio = zoomRatio,
                        visorMode = visorMode,
                        visorEngine = cyberVisorEngine
                    )
                    val res = stacked.captureResult
                    vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                    val kb = res.encryptedSizeBytes / 1024
                    val contourTag = if (res.isRamOnly) " [ОЗУ]" else ""
                    val gainTag = if (stacked.stackOutput.sharpnessGainPct > 0) " +${stacked.stackOutput.sharpnessGainPct}%" else ""
                    lastStatusMessage = "🔬 ФОКУС-СТЕК (${kb}КБ, 0 EXIF) [${res.iqLabel}$gainTag] за ${res.durationMs}мс$contourTag"
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
                            photoIqOverride = photoIqProfile,
                            visorMode = visorMode,
                            visorEngine = cyberVisorEngine,
                            gpuSnapshotProvider = if (useGpuVisorPipeline) {
                                { glRenderer.captureSnapshotBitmap() }
                            } else {
                                null
                            }
                        )
                        vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                        val kb = result.encryptedSizeBytes / 1024
                        val contourTag = if (result.isRamOnly) " [ОЗУ]" else ""
                        val dehazeTag = if (isDcpDehazeActive && visorMode == VisorMode.NORMAL) " [АНТИ-ТУМАН]" else ""
                        val iqTag = if (result.iqLabel.isNotBlank()) " [${result.iqLabel}]" else ""
                        val stageTag = if (result.durationMs >= 500L) " (${result.stageTimings.formatCompactRu()})" else ""
                        lastStatusMessage = "📸 ${result.formatLabel} (${kb}КБ, 0 EXIF) за ${result.durationMs}мс$contourTag$dehazeTag$iqTag$stageTag"

                        if (shotIdx < burstCount) {
                            delay(1000)
                        }
                    }
                }
            } catch (e: Exception) {
                lastStatusMessage = "ОШИБКА СЪЁМКИ: ${e.localizedMessage ?: "Сбой"}"
            } finally {
                isCapturing = false
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
            val storage = com.example.camera.StorageWatchdog.evaluateAppStorage(context)
            if (!storage.canRecord) {
                lastStatusMessage = "⛔ МАЛО ПАМЯТИ (${storage.availableMb} МБ < 250 МБ) — ЗАПИСЬ ОТМЕНЕНА"
                return
            }
            pendingStopVideoKey.set(null)
            val videoStageDir = GhostCryptoVault.getVideoStageDir(context)
            val tempVideoFile = File(videoStageDir, "raw_video_${System.currentTimeMillis()}.mp4")
            val outputOptions = FileOutputOptions.Builder(tempVideoFile).build()

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
                        is VideoRecordEvent.Start -> {
                            isRecordingVideo = true
                            telemetry.recordingDurationSeconds = 0
                            telemetry.recordingBytesWritten = 0L
                            triggerHapticFeedback(isStealthBlackoutActive)
                            lastStatusMessage = if (hasAudio) "🔴 ИДЁТ ЗАПИСЬ ВИДЕО..." else "🔴 ЗАПИСЬ ВИДЕО (БЕЗ ЗВУКА)"
                        }
                        is VideoRecordEvent.Status -> {
                            telemetry.recordingBytesWritten = event.recordingStats.numBytesRecorded
                        }
                        is VideoRecordEvent.Finalize -> {
                            val finalBytes = event.recordingStats.numBytesRecorded
                            isRecordingVideo = false
                            activeRecording = null
                            telemetry.recordingBytesWritten = 0L
                            val savedEvenWithLimit = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                                event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                            if ((!event.hasError() || savedEvenWithLimit) && tempVideoFile.exists() && tempVideoFile.length() > 0L) {
                                val pinnedKeyPair = pendingStopVideoKey.getAndSet(null)
                                scope.launch(Dispatchers.IO) {
                                    val cleanVideoFile = File(videoStageDir, "clean_video_${System.currentTimeMillis()}.mp4")
                                    try {
                                        val sanitized = Mp4Sanitizer.sanitizeMp4File(tempVideoFile, cleanVideoFile)
                                        val targetInput = if (sanitized && cleanVideoFile.exists()) cleanVideoFile else tempVideoFile
                                        if (ZverPrefs.isRamOnlyMode(context)) {
                                            val bytes = targetInput.readBytes()
                                            GhostCryptoVault.addRamShot(bytes, isVideo = true)
                                            GhostCryptoVault.shredFile(cleanVideoFile)
                                        } else if (pinnedKeyPair != null) {
                                            GhostCryptoVault.encryptFileToVaultWithKey(
                                                context = context,
                                                sourceFile = targetInput,
                                                explicitKey = pinnedKeyPair.first,
                                                explicitProfile = pinnedKeyPair.second,
                                                prefix = GhostCryptoVault.MEDIA_VIDEO
                                            )
                                        } else {
                                            GhostCryptoVault.encryptFileToVault(context, targetInput, prefix = GhostCryptoVault.MEDIA_VIDEO)
                                        }
                                        GhostCryptoVault.shredFile(tempVideoFile)
                                        GhostCryptoVault.shredFile(cleanVideoFile)
                                        val newCount = GhostCryptoVault.totalVaultItemsCount(context)
                                        withContext(Dispatchers.Main) {
                                            vaultCount = newCount
                                            lastStatusMessage = "🎥 MP4 ОЧИЩЕН (0 МЕТА) И ЗАШИФРОВАН [#$newCount]"
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
                telemetry.recordingBytesWritten = 0L
                GhostCryptoVault.shredFile(tempVideoFile)
                lastStatusMessage = "ОШИБКА СТАРТА: ${e.message}"
            }
        }
    }

    fun startVisorVideoRecordingInternal() {
        if (visorVideoRecorder.isRecording) {
            isRecordingVideo = false
            triggerHapticFeedback(isStealthBlackoutActive)
            lastStatusMessage = "⏳ ШИФРОВАНИЕ ВИЗОР-ВИДЕО..."
            val recordedBadge = visorMode.badgeRu
            scope.launch(Dispatchers.IO) {
                val rawMp4 = visorVideoRecorder.stopAndFinalize(
                    onBeforeSignalEos = { glRenderer.detachEncoderSurfaceBlocking() }
                )
                withContext(Dispatchers.Main) { telemetry.recordingBytesWritten = 0L }
                if (rawMp4 != null && rawMp4.exists() && rawMp4.length() > 0L) {
                    val stageDir = GhostCryptoVault.getVideoStageDir(context)
                    val cleanMp4 = File(stageDir, "visor_clean_${System.currentTimeMillis()}.mp4")
                    try {
                        val sanitized = Mp4Sanitizer.sanitizeMp4File(rawMp4, cleanMp4)
                        val targetInput = if (sanitized && cleanMp4.exists()) cleanMp4 else rawMp4
                        if (ZverPrefs.isRamOnlyMode(context)) {
                            val bytes = targetInput.readBytes()
                            GhostCryptoVault.addRamShot(bytes, isVideo = true)
                        } else {
                            GhostCryptoVault.encryptFileToVault(context, targetInput, prefix = GhostCryptoVault.MEDIA_VIDEO)
                        }
                        GhostCryptoVault.shredFile(rawMp4)
                        GhostCryptoVault.shredFile(cleanMp4)
                        val newCount = GhostCryptoVault.totalVaultItemsCount(context)
                        withContext(Dispatchers.Main) {
                            vaultCount = newCount
                            lastStatusMessage = "🎥 ВИЗОР MP4 ($recordedBadge, 0 МЕТА) В СЕЙФЕ [#$newCount]"
                        }
                    } catch (e: Exception) {
                        GhostCryptoVault.shredFile(rawMp4)
                        GhostCryptoVault.shredFile(cleanMp4)
                        withContext(Dispatchers.Main) {
                            lastStatusMessage = "ОШИБКА ШИФРОВАНИЯ ВИЗОРА: ${e.message}"
                        }
                    }
                } else {
                    rawMp4?.let { GhostCryptoVault.shredFile(it) }
                    withContext(Dispatchers.Main) {
                        lastStatusMessage = "СБОЙ ВИЗОР-ВИДЕО: нет кадров"
                    }
                }
            }
            return
        }

        val storage = com.example.camera.StorageWatchdog.evaluateAppStorage(context)
        if (!storage.canRecord) {
            lastStatusMessage = "⛔ МАЛО ПАМЯТИ (${storage.availableMb} МБ < 250 МБ) — ВИЗОР НЕ ЗАПИСАТЬ"
            return
        }

        val stageDir = GhostCryptoVault.getVideoStageDir(context)
        val rawMp4 = File(stageDir, "visor_raw_${System.currentTimeMillis()}.mp4")
        val muteAudio = ZverPrefs.isMuteVideoAudio(context)
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val enableAudioTrack = !muteAudio && !isAudioWatcherActive && hasAudio

        if (useGpuVisorPipeline) {
            val tvW = textureView?.width?.takeIf { it > 64 } ?: 720
            val tvH = textureView?.height?.takeIf { it > 64 } ?: 960
            val session = visorVideoRecorder.startForExternalSurface(
                outputFile = rawMp4,
                sampleWidth = tvW,
                sampleHeight = tvH,
                enableAudio = enableAudioTrack,
                hasAudioPermission = hasAudio,
                onBytesWritten = { bytes -> telemetry.recordingBytesWritten = bytes },
                onError = { err ->
                    scope.launch(Dispatchers.Main) {
                        lastStatusMessage = "GPU ВИДЕО-КОДЕК: $err"
                    }
                }
            )
            if (session != null) {
                glRenderer.attachEncoderSurface(
                    surface = session.inputSurface,
                    width = session.width,
                    height = session.height,
                    sessionStartNanoTime = session.sessionStartNanoTime
                ) {
                    visorVideoRecorder.notifyExternalFramePushed()
                }
                isRecordingVideo = true
                telemetry.recordingDurationSeconds = 0
                telemetry.recordingBytesWritten = 0L
                triggerHapticFeedback(isStealthBlackoutActive)
                lastStatusMessage = "🔴 GPU ЗАПИСЬ ВИЗОРА (${visorMode.badgeRu})..."
                return
            }
        }

        val sample = telemetry.visorDisplayState?.first
        val sampleW = sample?.width ?: 480
        val sampleH = sample?.height ?: 640
        val started = visorVideoRecorder.start(
            outputFile = rawMp4,
            sampleWidth = sampleW,
            sampleHeight = sampleH,
            enableAudio = enableAudioTrack,
            hasAudioPermission = hasAudio,
            onBytesWritten = { bytes -> telemetry.recordingBytesWritten = bytes },
            onError = { err ->
                scope.launch(Dispatchers.Main) {
                    lastStatusMessage = "ВИДЕО-КОДЕК ВИЗОРА: $err"
                }
            }
        )
        if (started) {
            isRecordingVideo = true
            telemetry.recordingDurationSeconds = 0
            telemetry.recordingBytesWritten = 0L
            triggerHapticFeedback(isStealthBlackoutActive)
            lastStatusMessage = "🔴 ЗАПИСЬ ВИЗОРА (${visorMode.badgeRu})..."
        } else {
            GhostCryptoVault.shredFile(rawMp4)
            visorMode = VisorMode.NORMAL
            pendingAutoStartVideo = true
            selectedCameraMode = "VIDEO"
            lastStatusMessage = "ПЕРЕХОД НА HAL-ВИДЕО..."
        }
    }

    fun launchRetroReflectionScan() {
        if (isRetroScanning || isRecordingVideo || isTimeLapseRecording || isCapturing) return
        if (activeImageAnalysis == null) {
            lastStatusMessage = "АНТИ-ОПТИКА: В ЭТОМ РЕЖИМЕ НЕТ ПОТОКА АНАЛИЗА"
            return
        }
        isToolsDrawerOpen = false
        isRetroScanning = true
        telemetry.isRetroScanning = true
        telemetry.retroHotspots = emptyList()
        telemetry.retroSummaryLabel = "🔦 СКАН АНТИ-ОПТИКИ..."
        lastStatusMessage = "🔦 СКАН АНТИ-ОПТИКИ (ВСПЫШКА OFF/ON)..."

        scope.launch {
            val prevTorch = isTorchActive
            try {
                try { cameraControl?.enableTorch(false) } catch (_: Exception) {}
                delay(180)
                val offDeferred = CompletableDeferred<CapturedYSample>()
                pendingRetroSample.set(offDeferred)
                val offSample = withTimeoutOrNull(1800) { offDeferred.await() }
                pendingRetroSample.compareAndSet(offDeferred, null)

                try { cameraControl?.enableTorch(true) } catch (_: Exception) {}
                delay(260)
                val onDeferred = CompletableDeferred<CapturedYSample>()
                pendingRetroSample.set(onDeferred)
                val onSample = withTimeoutOrNull(1800) { onDeferred.await() }
                pendingRetroSample.compareAndSet(onDeferred, null)

                if (offSample != null && onSample != null &&
                    offSample.width == onSample.width && offSample.height == onSample.height
                ) {
                    val scanRes = withContext(Dispatchers.Default) {
                        RetroReflectionDetector.analyzePairedYuv(
                            onYPlane = onSample.yData,
                            offYPlane = offSample.yData,
                            width = offSample.width,
                            height = offSample.height,
                            rotationDegrees = onSample.rotationDegrees,
                            isFrontCamera = onSample.isFront
                        )
                    }
                    telemetry.retroHotspots = scanRes.hotspots
                    telemetry.retroSummaryLabel = scanRes.summaryRu
                    lastStatusMessage = scanRes.summaryRu
                    triggerHapticFeedback(isStealth = scanRes.hotspots.isEmpty())
                } else {
                    telemetry.retroHotspots = emptyList()
                    telemetry.retroSummaryLabel = ""
                    lastStatusMessage = "АНТИ-ОПТИКА: НЕ УДАЛОСЬ СНЯТЬ ПАРУ КАДРОВ"
                }
            } catch (e: Exception) {
                telemetry.retroHotspots = emptyList()
                telemetry.retroSummaryLabel = ""
                lastStatusMessage = "СБОЙ АНТИ-ОПТИКИ: ${e.message}"
            } finally {
                try { cameraControl?.enableTorch(prevTorch) } catch (_: Exception) {}
                isRetroScanning = false
                telemetry.isRetroScanning = false
                launch {
                    delay(7000)
                    if (!isRetroScanning) {
                        telemetry.retroHotspots = emptyList()
                        telemetry.retroSummaryLabel = ""
                    }
                }
            }
        }
    }

    fun stopAndEncryptTimeLapse(reasonBanner: String? = null) {
        if (!isTimeLapseRecording && !timeLapseRecorder.isRecording) return
        isTimeLapseRecording = false
        telemetry.isTimeLapseRecording = false
        timeLapseJob?.cancel()
        timeLapseJob = null
        val stagedRaw = timeLapseTempFile
        timeLapseTempFile = null
        triggerHapticFeedback(isStealthBlackoutActive)
        lastStatusMessage = "⏳ ШИФРОВАНИЕ ТАЙМЛАПСА..."

        scope.launch(Dispatchers.IO) {
            val finalized = try { timeLapseRecorder.stopAndFinalize() } catch (_: Exception) { null }
            val rawMp4 = finalized ?: stagedRaw?.takeIf { it.exists() && it.length() > 0L }
            if (rawMp4 != null && rawMp4.exists() && rawMp4.length() > 0L) {
                val stageDir = GhostCryptoVault.getVideoStageDir(context)
                val cleanMp4 = File(stageDir, "tl_clean_${System.currentTimeMillis()}.mp4")
                try {
                    val sanitized = Mp4Sanitizer.sanitizeMp4File(rawMp4, cleanMp4)
                    val targetInput = if (sanitized && cleanMp4.exists()) cleanMp4 else rawMp4
                    if (ZverPrefs.isRamOnlyMode(context)) {
                        GhostCryptoVault.addRamShot(targetInput.readBytes(), isVideo = true)
                    } else {
                        GhostCryptoVault.encryptFileToVault(context, targetInput, prefix = GhostCryptoVault.MEDIA_VIDEO)
                    }
                    GhostCryptoVault.shredFile(rawMp4)
                    GhostCryptoVault.shredFile(cleanMp4)
                    val newCount = GhostCryptoVault.totalVaultItemsCount(context)
                    withContext(Dispatchers.Main) {
                        vaultCount = newCount
                        telemetry.timeLapseFrames = 0
                        telemetry.timeLapseElapsedSec = 0
                        lastStatusMessage = reasonBanner ?: "⏱ ТАЙМЛАПС (0 МЕТА) В СЕЙФЕ [#$newCount]"
                    }
                } catch (e: Exception) {
                    GhostCryptoVault.shredFile(rawMp4)
                    GhostCryptoVault.shredFile(cleanMp4)
                    withContext(Dispatchers.Main) {
                        telemetry.timeLapseFrames = 0
                        telemetry.timeLapseElapsedSec = 0
                        lastStatusMessage = "ОШИБКА ТАЙМЛАПСА: ${e.message}"
                    }
                }
            } else {
                stagedRaw?.let { GhostCryptoVault.shredFile(it) }
                withContext(Dispatchers.Main) {
                    telemetry.timeLapseFrames = 0
                    telemetry.timeLapseElapsedSec = 0
                    lastStatusMessage = reasonBanner ?: "ТАЙМЛАПС: НЕТ ЗАХВАЧЕННЫХ КАДРОВ"
                }
            }
        }
    }

    fun toggleTimeLapseRecording() {
        if (isTimeLapseRecording || timeLapseRecorder.isRecording) {
            stopAndEncryptTimeLapse()
            return
        }
        if (isRecordingVideo || visorVideoRecorder.isRecording) {
            lastStatusMessage = "СНАЧАЛА ОСТАНОВИТЕ ТЕКУЩУЮ ЗАПИСЬ ВИДЕО"
            return
        }
        val storage = StorageWatchdog.evaluateAppStorage(context)
        if (!storage.canRecord) {
            lastStatusMessage = "⛔ МАЛО ПАМЯТИ (${storage.availableMb} МБ < 250 МБ) — ТАЙМЛАПС ОТМЕНЁН"
            return
        }
        val stageDir = GhostCryptoVault.getVideoStageDir(context)
        val rawMp4 = File(stageDir, "tl_raw_${System.currentTimeMillis()}.mp4")
        val sampleW = if (useGpuVisorPipeline) {
            textureView?.width?.takeIf { it > 64 } ?: 720
        } else {
            previewView?.width?.takeIf { it > 64 } ?: 720
        }
        val sampleH = if (useGpuVisorPipeline) {
            textureView?.height?.takeIf { it > 64 } ?: 960
        } else {
            previewView?.height?.takeIf { it > 64 } ?: 960
        }
        if (!timeLapseRecorder.start(rawMp4, sampleW, sampleH)) {
            GhostCryptoVault.shredFile(rawMp4)
            lastStatusMessage = "ОШИБКА СТАРТА КОДЕКА ТАЙМЛАПСА"
            return
        }
        isToolsDrawerOpen = false
        timeLapseTempFile = rawMp4
        isTimeLapseRecording = true
        telemetry.isTimeLapseRecording = true
        telemetry.timeLapseFrames = 0
        telemetry.timeLapseElapsedSec = 0
        triggerHapticFeedback(isStealthBlackoutActive)
        val intervalLabel = timeLapseRecorder.formatIntervalLabel(timeLapseIntervalMs)
        lastStatusMessage = "⏱ ТАЙМЛАПС ЗАПУЩЕН (ИНТЕРВАЛ $intervalLabel)"

        timeLapseJob = scope.launch {
            var tick = 0
            while (isTimeLapseRecording && timeLapseRecorder.isRecording) {
                val snapBmp = if (useGpuVisorPipeline) {
                    withContext(Dispatchers.Default) { glRenderer.captureSnapshotBitmap(960) }
                } else if (visorMode != VisorMode.NORMAL) {
                    withContext(Dispatchers.Default) { cyberVisorEngine.snapshotLatestBitmapCopy() }
                } else {
                    withContext(Dispatchers.Main) { previewView?.bitmap }
                }
                if (snapBmp != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            timeLapseRecorder.appendBitmapFrame(snapBmp)
                        } finally {
                            if (!snapBmp.isRecycled) snapBmp.recycle()
                        }
                    }
                }
                telemetry.timeLapseFrames = timeLapseRecorder.framesCaptured
                telemetry.timeLapseElapsedSec = timeLapseRecorder.elapsedSeconds
                tick++
                if (tick % 3 == 0) {
                    val watchdog = withContext(Dispatchers.IO) {
                        StorageWatchdog.evaluateAppStorage(context)
                    }
                    if (watchdog.shouldStopRecording) {
                        stopAndEncryptTimeLapse("⛔ ДИСК < 250 МБ (${watchdog.availableMb} МБ) — ТАЙМЛАПС СОХРАНЁН")
                        break
                    }
                }
                delay(timeLapseIntervalMs)
            }
        }
    }

    val audioWatcherEngine = remember {
        AudioWatcherEngine(
            context = context,
            onTelemetry = { aTelemetry ->
                scope.launch(Dispatchers.Main) {
                    audioTelemetry = aTelemetry
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
            "VIDEO" -> startVideoRecordingInternal()
            "VISOR_VIDEO" -> startVisorVideoRecordingInternal()
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
        if (visorVideoRecorder.isRecording) {
            startVisorVideoRecordingInternal()
            return
        }
        if (isRecordingVideo) {
            startVideoRecordingInternal()
            return
        }
        if (visorMode != VisorMode.NORMAL && (activeImageAnalysis != null || useGpuVisorPipeline)) {
            if (ZverPrefs.isMuteVideoAudio(context) || isAudioWatcherActive) {
                startVisorVideoRecordingInternal()
                return
            }
            val hasAudio = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasAudio || hasAskedAudioForVideo) {
                startVisorVideoRecordingInternal()
            } else {
                hasAskedAudioForVideo = true
                pendingAudioAction = "VISOR_VIDEO"
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
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

    LaunchedEffect(zoomRatio, proProfile) {
        try {
            cameraControl?.setZoomRatio(zoomRatio.coerceIn(1.0f, proProfile.maxZoomRatio))
        } catch (_: Exception) {}
    }

    val voiceControlManager = remember {
        VoiceControlManager(
            context = context,
            onCommandRecognized = { cmd ->
                when (cmd) {
                    VoiceControlManager.VoiceCommand.CAPTURE -> triggerPhotoCapture()
                    VoiceControlManager.VoiceCommand.ZOOM_IN -> zoomRatio = 2.0f
                    VoiceControlManager.VoiceCommand.ZOOM_MAX -> zoomRatio = 3.0f
                    VoiceControlManager.VoiceCommand.ZOOM_RESET -> zoomRatio = 1.0f
                    VoiceControlManager.VoiceCommand.TOGGLE_TORCH -> isTorchActive = !isTorchActive
                    VoiceControlManager.VoiceCommand.TORCH_ON -> isTorchActive = true
                    VoiceControlManager.VoiceCommand.TORCH_OFF -> isTorchActive = false
                    VoiceControlManager.VoiceCommand.TIMER_5 -> {
                        timerSeconds = 5
                        lastStatusMessage = "ТАЙМЕР УСТАНОВЛЕН: 5 СЕК"
                    }
                    VoiceControlManager.VoiceCommand.TIMER_OFF -> timerSeconds = 0
                    VoiceControlManager.VoiceCommand.TOGGLE_STEALTH -> isStealthBlackoutActive = !isStealthBlackoutActive
                    VoiceControlManager.VoiceCommand.OPEN_VAULT -> showVaultDialog = true
                    VoiceControlManager.VoiceCommand.TOGGLE_VIDEO -> triggerVideoRecordingToggle()
                    VoiceControlManager.VoiceCommand.FLIP_CAMERA -> {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    }
                }
            },
            onStatusUpdate = { msg -> lastStatusMessage = msg }
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

    // Auto-shutter loop using snapshotFlow so GhostCameraScreen doesn't recompose on every sharpness tick
    LaunchedEffect(isAutoShutterEnabled) {
        if (!isAutoShutterEnabled) return@LaunchedEffect
        snapshotFlow {
            Triple(isFocusLocked, telemetry.isGyroStable, telemetry.liveLaplacianVariance)
        }.collectLatest { (focused, stable, sharpness) ->
            if (focused && stable && (sharpness >= 110.0 || sharpness == 0.0) && !isCapturing) {
                triggerPhotoCapture()
                isFocusLocked = false
            }
        }
    }

    val isVideoCameraMode = (selectedCameraMode == "VIDEO")
    val keepVisorStreamInVideo = isVideoCameraMode && (visorMode != VisorMode.NORMAL)

    fun createConfiguredImageAnalysis(): ImageAnalysis {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        var previousMotionGrid: IntArray? = null
        val claheLutScratch = ByteArray(ClaheLutBuilder.LUT_BYTE_SIZE)

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            val retroWaiter = pendingRetroSample.getAndSet(null)
            if (retroWaiter != null) {
                try {
                    val yPlane = imageProxy.planes[0]
                    val (arr, w, h) = RetroReflectionDetector.downsampleYPlane(
                        yBuffer = yPlane.buffer,
                        width = imageProxy.width,
                        height = imageProxy.height,
                        rowStride = yPlane.rowStride,
                        targetLongSide = 240
                    )
                    retroWaiter.complete(
                        CapturedYSample(
                            yData = arr,
                            width = w,
                            height = h,
                            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                            isFront = (analyzerFlags.lensFacing == CameraSelector.LENS_FACING_FRONT)
                        )
                    )
                } catch (_: Throwable) {
                    retroWaiter.cancel()
                }
            }

            val dispatchGen = analysisScheduler.tryBeginFrame()
            if (dispatchGen == null) {
                imageProxy.close()
                return@setAnalyzer
            }
            try {
                val nowMs = System.currentTimeMillis()
                val needExpo = analyzerFlags.histogramEnabled || analyzerFlags.zebraEnabled || analyzerFlags.falseColorEnabled
                val needPeaking = analyzerFlags.focusPeakingEnabled
                val needSentinel = analyzerFlags.sentinelArmed || (analyzerFlags.selectedCameraMode == "SENTINEL")
                val plan = analysisScheduler.planFrameWork(
                    nowMs = nowMs,
                    thermalIntervalMs = thermalMonitor.currentIntervalMs,
                    visorMode = analyzerFlags.visorMode,
                    useGpuShader = analyzerFlags.useGpuShader,
                    needExpo = needExpo,
                    needPeaking = needPeaking,
                    needSentinel = needSentinel,
                    stealthBlackoutActive = analyzerFlags.stealthBlackoutActive
                )
                val plane = imageProxy.planes[0]

                var nextLaplacian: Double? = null
                var nextExpoTelemetry: AntiBlurSensorEngine.LiveExposureTelemetry? = null
                var clearZebra = false
                var nextPeakingPoints: List<Offset>? = null
                var clearPeaking = false
                var nextMotion: AntiBlurSensorEngine.MotionTelemetry? = null
                var nextSentinelStepLabel: String? = null
                var nextGpuVisorTel: com.example.visor.VisorTelemetry? = null
                var nextCpuVisorState: Triple<android.graphics.Bitmap, com.example.visor.VisorTelemetry, Long>? = null

                if (plan.runSharpnessAndExpo) {
                    nextLaplacian = antiBlurEngine.calculateCenterRoiLaplacianVariance(
                        yBuffer = plane.buffer,
                        width = imageProxy.width,
                        height = imageProxy.height,
                        rowStride = plane.rowStride,
                        targetRoiSize = 400
                    )

                    if (analyzerFlags.useGpuShader &&
                        (analyzerFlags.visorMode == VisorMode.THERMAL_LUT || analyzerFlags.visorMode == VisorMode.NIGHT_GAMMA)
                    ) {
                        val (lMin, lMax) = antiBlurEngine.computeSmoothedLumaBounds(
                            yBuffer = plane.buffer,
                            width = imageProxy.width,
                            height = imageProxy.height,
                            rowStride = plane.rowStride
                        )
                        glRenderer.lumaMinNorm = lMin
                        glRenderer.lumaMaxNorm = lMax
                    }

                    if (needExpo) {
                        nextExpoTelemetry = antiBlurEngine.computeHistogramAndZebra(
                            yBuffer = plane.buffer,
                            width = imageProxy.width,
                            height = imageProxy.height,
                            rowStride = plane.rowStride,
                            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                            zebraThreshold = analyzerFlags.zebraThreshold,
                            collectZebra = analyzerFlags.zebraEnabled && !analyzerFlags.useGpuShader
                        )
                    } else if (telemetry.liveZebraPoints.isNotEmpty()) {
                        clearZebra = true
                    }
                }

                if (plan.runCpuPeaking) {
                    nextPeakingPoints = antiBlurEngine.computeFocusPeakingPoints(
                        yBuffer = plane.buffer,
                        width = imageProxy.width,
                        height = imageProxy.height,
                        rowStride = plane.rowStride,
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    )
                } else if ((!needPeaking || analyzerFlags.useGpuShader) && telemetry.peakingPoints.isNotEmpty()) {
                    clearPeaking = true
                }

                if (plan.runSentinel) {
                    val motion = antiBlurEngine.computeMotionAndLuma(
                        yBuffer = plane.buffer,
                        width = imageProxy.width,
                        height = imageProxy.height,
                        rowStride = plane.rowStride,
                        previousGrid = previousMotionGrid
                    )
                    previousMotionGrid = motion.grid
                    nextMotion = motion

                    val sentinelRes = SentinelEngine.evaluateMotionFrame(
                        nowMs = nowMs,
                        motionScore = motion.motionScore,
                        avgLuma = motion.avgLuma
                    )
                    when (sentinelRes) {
                        is SentinelEngine.SentinelStepResult.TriggerLingerCapture -> {
                            scope.launch(Dispatchers.Main) {
                                telemetry.sentinelStepLabel = "🚨 ЗАВИСАНИЕ > ${sentinelRes.durationMs / 1000}с!"
                                sentinelEvents = SentinelEngine.getEvents()
                                launchSentinelCapture(
                                    reason = "Объект у цели ${sentinelRes.durationMs / 1000}с (движ ${motion.motionScore}%)",
                                    useNightClahe = sentinelRes.nightClahe
                                )
                            }
                        }
                        is SentinelEngine.SentinelStepResult.NeighborIgnored -> {
                            scope.launch(Dispatchers.Main) {
                                telemetry.sentinelStepLabel = "ПРОХОЖИЙ [ИГНОР]"
                                sentinelEvents = SentinelEngine.getEvents()
                            }
                        }
                        is SentinelEngine.SentinelStepResult.Tracking -> {
                            val thr = SentinelEngine.getConfig().lingerThresholdSec
                            nextSentinelStepLabel = "ЦЕЛЬ: ${sentinelRes.elapsedMs / 1000}с / ${thr}с"
                        }
                        is SentinelEngine.SentinelStepResult.Cooldown -> {
                            nextSentinelStepLabel = "ПАУЗА: ${sentinelRes.remainingSec}с"
                        }
                        SentinelEngine.SentinelStepResult.Idle -> {
                            nextSentinelStepLabel = if (SentinelEngine.isArmed()) "ОХРАНА: ЧИСТО" else "ОЖИДАНИЕ"
                        }
                    }
                } else if (!needSentinel) {
                    previousMotionGrid = null
                }

                if (analyzerFlags.visorMode != VisorMode.NORMAL && !analyzerFlags.stealthBlackoutActive) {
                    if (analyzerFlags.useGpuShader) {
                        if (plan.runVisorDehazeLut && analysisScheduler.isGenerationCurrent(dispatchGen)) {
                            val lut = ClaheLutBuilder.build4x4TileLuts(
                                yBuffer = plane.buffer,
                                width = imageProxy.width,
                                height = imageProxy.height,
                                rowStride = plane.rowStride,
                                outLut = claheLutScratch
                            )
                            glRenderer.updateDehazeClaheLut(lut)
                        }
                        if (plan.runVisorTelemetry) {
                            val tel = cyberVisorEngine.analyzeTelemetryOnly(
                                imageProxy = imageProxy,
                                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                                isFrontCamera = (analyzerFlags.lensFacing == CameraSelector.LENS_FACING_FRONT)
                            )
                            if (tel != null && analysisScheduler.isGenerationCurrent(dispatchGen)) {
                                glRenderer.poseWireframe = tel.poseWireframe
                                nextGpuVisorTel = tel
                            }
                        }
                    } else {
                        val res = cyberVisorEngine.processYuv420Frame(
                            imageProxy = imageProxy,
                            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                            isFrontCamera = (analyzerFlags.lensFacing == CameraSelector.LENS_FACING_FRONT)
                        )
                        if (res != null && analysisScheduler.isGenerationCurrent(dispatchGen)) {
                            if (visorVideoRecorder.isRecording) {
                                val wf = if (analyzerFlags.visorMode == VisorMode.WIREFRAME_POSE) res.telemetry.poseWireframe else null
                                visorVideoRecorder.enqueueFrame(res.bitmap, wf)
                            }
                            val tick = visorTickCounter.incrementAndGet()
                            nextCpuVisorState = Triple(res.bitmap, res.telemetry, tick)
                        }
                    }
                }

                if (nextLaplacian != null || nextExpoTelemetry != null || clearZebra ||
                    nextPeakingPoints != null || clearPeaking || nextMotion != null ||
                    nextSentinelStepLabel != null || nextGpuVisorTel != null || nextCpuVisorState != null
                ) {
                    val histFlag = analyzerFlags.histogramEnabled
                    val zebraCpuFlag = analyzerFlags.zebraEnabled && !analyzerFlags.useGpuShader
                    mainHandler.post {
                        nextLaplacian?.let { telemetry.liveLaplacianVariance = it }
                        nextExpoTelemetry?.let { expo ->
                            telemetry.overexposedPercent = expo.overexposedPct
                            telemetry.underexposedPercent = expo.underexposedPct
                            if (histFlag) {
                                telemetry.liveHistogramBins32 = expo.bins32
                                telemetry.liveMeanLuma = expo.meanLuma
                            }
                            if (zebraCpuFlag) {
                                telemetry.liveZebraPoints = expo.zebraHotspots
                            } else if (telemetry.liveZebraPoints.isNotEmpty()) {
                                telemetry.liveZebraPoints = emptyList()
                            }
                        }
                        if (clearZebra && telemetry.liveZebraPoints.isNotEmpty()) {
                            telemetry.liveZebraPoints = emptyList()
                        }
                        nextPeakingPoints?.let { telemetry.peakingPoints = it }
                        if (clearPeaking && telemetry.peakingPoints.isNotEmpty()) {
                            telemetry.peakingPoints = emptyList()
                        }
                        nextMotion?.let { m ->
                            telemetry.sentinelMotionScore = m.motionScore
                            telemetry.sentinelAvgLuma = m.avgLuma
                            telemetry.sentinelIsNight = m.isLowLight
                        }
                        nextSentinelStepLabel?.let { telemetry.sentinelStepLabel = it }
                        nextGpuVisorTel?.let { telemetry.gpuVisorTelemetry = it }
                        nextCpuVisorState?.let { telemetry.visorDisplayState = it }
                    }
                }
            } finally {
                analysisScheduler.endFrame()
                imageProxy.close()
            }
        }
        return imageAnalysis
    }

    val isNightExtensionTab = (selectedCameraMode == "NIGHT" && !useGpuVisorPipeline && visorMode == VisorMode.NORMAL)

    LaunchedEffect(lensFacing, isVideoCameraMode, keepVisorStreamInVideo, useGpuVisorPipeline, isNightExtensionTab, previewView, resumeBindToken) {
        if (!useGpuVisorPipeline && previewView == null) return@LaunchedEffect
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraProvider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }
        activeCameraProvider = cameraProvider

        try { activePreview?.setSurfaceProvider(null) } catch (_: Exception) {}
        activePreview = null
        glRenderer.resetFirstFrameSignal()
        val preview = Preview.Builder().build().apply {
            if (useGpuVisorPipeline) {
                setSurfaceProvider(glRenderer.previewSurfaceProvider)
            } else {
                setSurfaceProvider(previewView!!.surfaceProvider)
            }
        }
        activePreview = preview
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        if (isRecordingVideo && activeRecording != null) {
            try { activeRecording?.stop() } catch (_: Exception) {}
            activeRecording = null
            isRecordingVideo = false
        }

        try { activeImageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
        activeImageAnalysis = null

        if (isVideoCameraMode) {
            bindingCoordinator.cancelPendingWatchdog()
            imageCapture = null
            try {
                cameraProvider.unbindAll()
                if (keepVisorStreamInVideo) {
                    videoCapture = null
                    val analysis = createConfiguredImageAnalysis()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        analysis
                    )
                    activeImageAnalysis = analysis
                    boundUseCasesSummary = if (useGpuVisorPipeline) {
                        "GPU VIDEO+VISOR: Preview(EGL) + ImageAnalysis"
                    } else {
                        "CPU VIDEO+VISOR: Preview + ImageAnalysis"
                    }
                    cameraControl = camera.cameraControl
                    if (pendingAutoStartVideo) {
                        pendingAutoStartVideo = false
                        delay(300)
                        triggerVideoRecordingToggle()
                    }
                } else {
                    val qualitySelector = QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                    )
                    val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
                    val vCapture = VideoCapture.withOutput(recorder)
                    val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, vCapture)
                    boundUseCasesSummary = "VIDEO: Preview + VideoCapture (HD/SD)"
                    cameraControl = camera.cameraControl
                    videoCapture = vCapture
                    if (pendingAutoStartVideo) {
                        pendingAutoStartVideo = false
                        delay(350)
                        triggerVideoRecordingToggle()
                    }
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
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
            )
            c2Extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            val capture = captureBuilder.build()
            val imageAnalysis = createConfiguredImageAnalysis()
            val tabForBind = if (isNightExtensionTab) CameraTab.NIGHT else CameraTab.fromKey(selectedCameraMode)
            val outcome = bindingCoordinator.bindPhotoOrMode(
                cameraProvider = cameraProvider,
                lifecycleOwner = lifecycleOwner,
                lensFacing = lensFacing,
                tab = tabForBind,
                visorMode = visorMode,
                useGpuVisorPipeline = useGpuVisorPipeline,
                preview = preview,
                imageCapture = capture,
                imageAnalysis = imageAnalysis,
                onFirstFrameWatchdogFallback = { fallback ->
                    cameraControl = fallback.camera.cameraControl
                    imageCapture = capture
                    activeImageAnalysis = fallback.activeImageAnalysis
                    boundUseCasesSummary = fallback.activeUseCasesSummary
                    lastStatusMessage = "Night Extension: откат на базовый поток HAL"
                },
                onFatalBindError = { e ->
                    boundUseCasesSummary = "CAMERA BIND ERROR: ${e.javaClass.simpleName}"
                    lastStatusMessage = "Ошибка камеры: ${e.localizedMessage}"
                }
            )
            if (outcome != null) {
                cameraControl = outcome.camera.cameraControl
                imageCapture = capture
                activeImageAnalysis = outcome.activeImageAnalysis
                boundUseCasesSummary = outcome.activeUseCasesSummary
                if (outcome.activeImageAnalysis == null) {
                    lastStatusMessage = "ВНИМАНИЕ HAL: ImageAnalysis отключён (только Фото)"
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

    val cameraModes = remember {
        listOf(
            "Видео" to "VIDEO",
            "Фото" to "PHOTO",
            "Ночь" to "NIGHT",
            "Визор" to "VISOR",
            "Дозор" to "SENTINEL",
            "Документы" to "DOCUMENTS"
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
        if (key != "DOCUMENTS") {
            isDocumentModeActive = false
        }
        when (key) {
            "PHOTO" -> {
                exposureIndex = 0
                isVoiceControlActive = false
                visorMode = VisorMode.NORMAL
                lastStatusMessage = "Режим: Фотосъёмка"
            }
            "VIDEO" -> {
                lastStatusMessage = if (visorMode != VisorMode.NORMAL) {
                    "Режим: Видео + Визор (${visorMode.title})"
                } else {
                    "Режим: Видео в сейф"
                }
            }
            "NIGHT" -> {
                val nightIdx = CameraCapabilityProfiler.evIndexForStops(0.5f, proProfile.evStep, proProfile.evRange)
                exposureIndex = nightIdx
                val evTxt = CameraCapabilityProfiler.formatEvStops(nightIdx, proProfile.evStep)
                lastStatusMessage = "Режим: Ночь ($evTxt)"
            }
            "VISOR" -> {
                if (visorMode == VisorMode.NORMAL) {
                    visorMode = VisorMode.VEIN_HEMO
                }
                lastStatusMessage = "Режим: Био-Визор (${visorMode.title})"
            }
            "SENTINEL" -> {
                lastStatusMessage = "Режим: Кибер-Дозор"
            }
            "DOCUMENTS" -> {
                isDocumentModeActive = true
                visorMode = VisorMode.NORMAL
                lastStatusMessage = "Режим: Документы (уровень)"
            }
            "VOICE" -> {
                requestVoiceControlToggle(forceEnable = true)
            }
        }
    }

    LaunchedEffect(Unit) {
        MainActivity.onVolumeShutterTrigger = {
            if (selectedCameraMode == "VIDEO") triggerVideoRecordingToggle() else triggerPhotoCapture()
        }
        MainActivity.onVideoToggleTrigger = { triggerVideoRecordingToggle() }
        MainActivity.onTorchToggleTrigger = { explicit ->
            isTorchActive = explicit ?: !isTorchActive
        }
        MainActivity.onBlackoutToggleTrigger = { isStealthBlackoutActive = !isStealthBlackoutActive }
        MainActivity.onSentinelArmTrigger = { explicit ->
            val next = explicit ?: !sentinelArmed
            if (next && selectedCameraMode != "SENTINEL") {
                applyCameraModeSelection("SENTINEL")
            }
            SentinelEngine.setArmed(next)
            sentinelArmed = next
            sentinelEvents = SentinelEngine.getEvents()
            lastStatusMessage = if (next) "🛡️ ДОЗОР ВКЛ (URI/Intent)" else "🛡️ ДОЗОР СНЯТ (URI/Intent)"
        }
        MainActivity.onModeSwitchTrigger = { modeKey ->
            applyCameraModeSelection(modeKey)
        }
        MainActivity.onRamOnlyTrigger = { explicit ->
            val next = explicit ?: !ZverPrefs.isRamOnlyMode(context)
            ZverPrefs.setRamOnlyMode(context, next)
            lastStatusMessage = if (next) "🧠 RAM-ONLY ВКЛ (URI/Intent)" else "💾 ЗАПИСЬ В СЕЙФ ВКЛ"
        }
        MainActivity.flushPendingAction(context)
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
        isAssistModeActive,
        isGyroEisEnabled,
        isTimeLapseRecording,
        isRetroScanning
    ).count { it }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val cameraUiState = CameraUiState(
            activeTab = CameraTab.fromKey(selectedCameraMode),
            visorMode = visorMode,
            isDocumentModeActive = isDocumentModeActive,
            isRecordingVideo = isRecordingVideo
        )

        CameraViewportSurface(
            useGpuVisorPipeline = useGpuVisorPipeline,
            glRenderer = glRenderer,
            onPreviewViewReady = { previewView = it },
            onTextureViewReady = { textureView = it },
            onPreviewStreaming = { bindingCoordinator.markFirstFrameReceived() }
        )

        if (!isStealthBlackoutActive) {
            VisorLiveOverlayLayer(
                visorMode = visorMode,
                useGpuVisorPipeline = useGpuVisorPipeline,
                telemetry = telemetry
            )

        CameraGestureLayer(
            previewView = previewView,
            textureView = textureView,
            useGpuVisorPipeline = useGpuVisorPipeline,
            isToolsDrawerOpen = isToolsDrawerOpen,
            selectedCameraMode = selectedCameraMode,
            cameraModes = cameraModes,
            onCloseDrawer = { isToolsDrawerOpen = false },
            onTapFocus = { pt ->
                focusTapPoint = pt
                isFocusLocked = true
            },
            onTriggerFocusAndMetering = { action -> cameraControl?.startFocusAndMetering(action) },
            onHapticStealth = { triggerHapticFeedback(isStealth = true) },
            onSelectMode = { key -> applyCameraModeSelection(key) },
            onOpenDrawer = { isToolsDrawerOpen = true },
            onOpenVikaSheet = {
                vikaSheetInitialTab = VikaSheetTab.TASKS
                showVikaSheet = true
            }
        )

        if (cameraUiState.showTacticalReticle) {
            TacticalReticle(modifier = Modifier.fillMaxSize())
        }

        DocumentLevelOverlay(isDocumentModeActive = cameraUiState.showDocumentLevel, telemetry = telemetry)

        LiveOpticalOverlaysLayer(
            isFocusPeakingEnabled = isFocusPeakingEnabled && !useGpuVisorPipeline,
            isFalseColorEnabled = isFalseColorEnabled,
            isZebraEnabled = isZebraEnabled && !useGpuVisorPipeline,
            zebraThreshold = ZverPrefs.getZebraThreshold(context),
            telemetry = telemetry
        )

        RetroReflectionOverlay(
            isScanning = telemetry.isRetroScanning,
            hotspots = telemetry.retroHotspots,
            summaryLabel = telemetry.retroSummaryLabel
        )

        FocusTapRingOverlay(
            focusTapPoint = focusTapPoint,
            onExpired = { focusTapPoint = null }
        )

        ShutterFlashOverlay(visible = showShutterFlash)
        AssistPerimeterBorder(isAssistModeActive = isAssistModeActive)

        CameraTopHudAndDrawer(
            ramMode = ZverPrefs.isRamOnlyMode(context),
            compressionPreset = ZverPrefs.getCompressionPreset(context),
            isAssistModeActive = isAssistModeActive,
            hasByokKey = hasByokKey,
            isTorchActive = isTorchActive,
            isToolsDrawerOpen = isToolsDrawerOpen,
            activeToolsCount = activeToolsCount,
            isTopOverflowMenuOpen = isTopOverflowMenuOpen,
            isProPanelExpanded = isProPanelExpanded,
            isHistogramEnabled = isHistogramEnabled,
            isZebraEnabled = isZebraEnabled,
            zebraThreshold = ZverPrefs.getZebraThreshold(context),
            isFocusPeakingEnabled = isFocusPeakingEnabled,
            isFalseColorEnabled = isFalseColorEnabled,
            isDocumentModeActive = isDocumentModeActive,
            isDcpDehazeActive = isDcpDehazeActive,
            isAutoShutterEnabled = isAutoShutterEnabled,
            isFocusBracketingActive = isFocusBracketingActive,
            timerSeconds = timerSeconds,
            isVoiceControlActive = isVoiceControlActive,
            photoIqProfile = photoIqProfile,
            multiFrameMode = multiFrameMode,
            showPcbLabDialog = showPcbLabDialog,
            isAudioWatcherActive = isAudioWatcherActive,
            audioThresholdDbfs = audioThresholdDbfs,
            isGyroEisEnabled = isGyroEisEnabled,
            isRetroScanning = isRetroScanning,
            isTimeLapseRecording = isTimeLapseRecording,
            timeLapseIntervalMs = timeLapseIntervalMs,
            isCapturing = isCapturing,
            isRecordingVideo = isRecordingVideo,
            lastStatusMessage = lastStatusMessage,
            telemetry = telemetry,
            selectedCameraMode = selectedCameraMode,
            zoomRatio = zoomRatio,
            onToggleTorch = { isTorchActive = !isTorchActive },
            onToggleDrawer = { isToolsDrawerOpen = !isToolsDrawerOpen },
            onCloseDrawer = { isToolsDrawerOpen = false },
            onFlipCamera = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            },
            onOpenOverflowMenu = { isTopOverflowMenuOpen = true },
            onDismissOverflowMenu = { isTopOverflowMenuOpen = false },
            onOpenSettings = {
                isToolsDrawerOpen = false
                showSettingsDialog = true
            },
            onToggleProPanel = {
                isProPanelExpanded = !isProPanelExpanded
                isToolsDrawerOpen = false
            },
            onToggleHistogram = { isHistogramEnabled = !isHistogramEnabled },
            onToggleZebra = { isZebraEnabled = !isZebraEnabled },
            onToggleFocusPeaking = { isFocusPeakingEnabled = !isFocusPeakingEnabled },
            onToggleFalseColor = { isFalseColorEnabled = !isFalseColorEnabled },
            onToggleDocumentMode = {
                val enableDoc = !isDocumentModeActive
                isDocumentModeActive = enableDoc
                if (enableDoc) {
                    selectedCameraMode = "DOCUMENTS"
                    visorMode = VisorMode.NORMAL
                    lastStatusMessage = "Режим: Документы (уровень)"
                }
            },
            onToggleDcpDehaze = { isDcpDehazeActive = !isDcpDehazeActive },
            onToggleAutoShutter = { isAutoShutterEnabled = !isAutoShutterEnabled },
            onToggleFocusBracketing = { isFocusBracketingActive = !isFocusBracketingActive },
            onCycleTimer = {
                timerSeconds = when (timerSeconds) {
                    0 -> 3
                    3 -> 5
                    5 -> 10
                    else -> 0
                }
            },
            onToggleVoiceControl = { requestVoiceControlToggle() },
            onActivateStealth = {
                isToolsDrawerOpen = false
                if (selectedCameraMode != "STEALTH") previousCameraMode = selectedCameraMode
                isStealthBlackoutActive = true
            },
            onOpenCamouflage = {
                isToolsDrawerOpen = false
                showCamouflageDialog = true
            },
            onToggleAssistMode = { isAssistModeActive = !isAssistModeActive },
            onCyclePhotoIq = {
                val vals = PhotoIqEngine.PhotoIqProfile.values()
                val next = vals[(vals.indexOf(photoIqProfile) + 1) % vals.size]
                photoIqProfile = next
                ZverPrefs.setPhotoIqProfile(context, next)
                lastStatusMessage = "Улучшение фото: ${next.labelRu}"
            },
            onCycleMultiFrame = {
                val options = listOf<MultiFrameStacker.StackMode?>(null) + MultiFrameStacker.StackMode.values().toList()
                val next = options[(options.indexOf(multiFrameMode) + 1) % options.size]
                multiFrameMode = next
                ZverPrefs.setMultiFrameMode(context, next)
                lastStatusMessage = if (next == null) "Мульти-кадр: Выкл" else "Мульти-кадр: ${next.labelRu}"
            },
            onOpenPcbLab = {
                isToolsDrawerOpen = false
                showPcbLabDialog = true
            },
            onToggleAudioWatcher = { requestAudioWatcherToggle() },
            onToggleGyroEis = {
                val next = !isGyroEisEnabled
                isGyroEisEnabled = next
                ZverPrefs.setGyroEisEnabled(context, next)
                if (!next) {
                    antiBlurEngine.resetEisIntegrator()
                    glRenderer.eisOffsetX = 0f
                    glRenderer.eisOffsetY = 0f
                }
                lastStatusMessage = if (next) "GYRO-EIS LITE: ВКЛ (ВИЗОР)" else "GYRO-EIS LITE: ВЫКЛ"
            },
            onTriggerRetroScan = { launchRetroReflectionScan() },
            onToggleTimeLapse = { toggleTimeLapseRecording() },
            onOpenSelfTest = {
                isToolsDrawerOpen = false
                showSelfTestDialog = true
            },
            onOpenVikaSheet = {
                vikaSheetInitialTab = VikaSheetTab.TASKS
                showVikaSheet = true
            },
            onOpenPanicShred = {
                isToolsDrawerOpen = false
                showPanicConfirm = true
            },
            onDismissStatus = { lastStatusMessage = null },
            pinSessionLockedWarning = pinSessionLockedWarning,
            onUnlockPinRequested = {
                forcePinSetupFlow = false
                showVaultDialog = true
            }
        )

        CountdownOverlay(countdownRemaining = countdownRemaining)

        CameraBottomControlDeck(
            modifier = Modifier.align(Alignment.BottomCenter),
            telemetry = telemetry,
            isAudioWatcherActive = isAudioWatcherActive,
            audioTelemetry = audioTelemetry,
            audioThresholdDbfs = audioThresholdDbfs,
            selectedCameraMode = selectedCameraMode,
            cameraModes = cameraModes,
            sentinelArmed = sentinelArmed,
            sentinelConfig = sentinelConfig,
            sentinelEvents = sentinelEvents,
            hasByokKey = hasByokKey,
            visorMode = visorMode,
            isProPanelExpanded = isProPanelExpanded,
            proProfile = proProfile,
            exposureIndex = exposureIndex,
            awbPreset = awbPreset,
            manualFocusNorm = manualFocusNorm,
            isoOverride = isoOverride,
            shutterNsOverride = shutterNsOverride,
            isAeLocked = isAeLocked,
            isAwbLocked = isAwbLocked,
            targetFpsRange = targetFpsRange,
            photoIqProfile = photoIqProfile,
            isHistogramEnabled = isHistogramEnabled,
            isZebraEnabled = isZebraEnabled,
            isZoomIqEnabled = isZoomIqEnabled,
            zoomRatio = zoomRatio,
            multiFrameMode = multiFrameMode,
            vaultCount = vaultCount,
            isCapturing = isCapturing,
            countdownRemaining = countdownRemaining,
            isAssistModeActive = isAssistModeActive,
            isRecordingVideo = isRecordingVideo,
            timerSeconds = timerSeconds,
            onSelectAudioThreshold = { thr ->
                audioThresholdDbfs = thr
                lastStatusMessage = "АКУСТИК ПОРОГ: ${thr.toInt()} dBFS"
            },
            onToggleSentinelArmed = {
                val next = !sentinelArmed
                SentinelEngine.setArmed(next)
                sentinelArmed = next
                sentinelEvents = SentinelEngine.getEvents()
                lastStatusMessage = if (next) "🛡️ ДОЗОР НА ПОСТУ (${sentinelConfig.lingerThresholdSec}с фильтр)" else "🛡️ ДОЗОР СНЯТ"
            },
            onCycleSentinelLinger = {
                val nextSec = when (sentinelConfig.lingerThresholdSec) {
                    2 -> 4
                    4 -> 6
                    else -> 2
                }
                sentinelConfig = SentinelEngine.updateAndPersistConfig(context) { it.copy(lingerThresholdSec = nextSec) }
            },
            onToggleSentinelKnock = {
                sentinelConfig = SentinelEngine.updateAndPersistConfig(context) { it.copy(knockTriggerEnabled = !it.knockTriggerEnabled) }
            },
            onToggleSentinelClaheNight = {
                sentinelConfig = SentinelEngine.updateAndPersistConfig(context) { it.copy(autoNightClaheEnabled = !it.autoNightClaheEnabled) }
            },
            onToggleSentinelTwoStageCrop = {
                sentinelConfig = SentinelEngine.updateAndPersistConfig(context) { it.copy(twoStageFaceCropEnabled = !it.twoStageFaceCropEnabled) }
            },
            onToggleSentinelAiTriage = {
                if (!hasByokKey) {
                    vikaSheetInitialTab = VikaSheetTab.KEYS_MODELS
                    showVikaSheet = true
                } else {
                    sentinelConfig = SentinelEngine.updateAndPersistConfig(context) { it.copy(aiTriageEnabled = !it.aiTriageEnabled) }
                }
            },
            onClearSentinelEvents = {
                SentinelEngine.clearEvents()
                sentinelEvents = SentinelEngine.getEvents()
            },
            onStealthGuard = {
                if (selectedCameraMode != "STEALTH") previousCameraMode = "SENTINEL"
                isStealthBlackoutActive = true
            },
            onSelectVisorMode = { m ->
                if (visorVideoRecorder.isRecording && m.supportsGpuShader() != useGpuVisorPipeline) {
                    lastStatusMessage = "СНАЧАЛА ОСТАНОВИТЕ ЗАПИСЬ ВИЗОРА"
                } else {
                    visorMode = m
                    if (m == VisorMode.NORMAL && selectedCameraMode == "VISOR") {
                        selectedCameraMode = "PHOTO"
                    }
                    lastStatusMessage = "ВИЗОР: ${m.title}"
                }
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
                val savedBadge = visorMode.badgeRu
                val snapGpuMode = useGpuVisorPipeline
                scope.launch {
                    val snapBmp = if (snapGpuMode) {
                        withContext(Dispatchers.Default) { glRenderer.captureSnapshotBitmap() }
                    } else {
                        cyberVisorEngine.snapshotLatestBitmapCopy()
                    }
                    if (snapBmp != null) {
                        try {
                            val savedRam = withContext(Dispatchers.IO) {
                                val cap = imageCapture
                                if (cap != null) {
                                    CaptureManager(
                                        context = context,
                                        imageCapture = cap,
                                        captureExecutor = captureExecutor
                                    ).saveExternalBitmapToVault(
                                        bitmap = snapBmp,
                                        recycleBitmap = true,
                                        iqTag = "ВИЗОР:$savedBadge"
                                    ).isRamOnly
                                } else {
                                    val bytes = ExifStripper.processBitmapClean(
                                        sourceBitmap = snapBmp,
                                        recycleSource = true,
                                        preset = ZverPrefs.getCompressionPreset(context),
                                        antiPrnu = ZverPrefs.isAntiPrnuEnabled(context)
                                    ).cleanBytes
                                    val ram = ZverPrefs.isRamOnlyMode(context)
                                    if (ram) {
                                        GhostCryptoVault.addRamShot(bytes.clone(), isVideo = false)
                                    } else {
                                        val (_, cos) = GhostCryptoVault.openEncryptedOutputStream(
                                            context,
                                            prefix = GhostCryptoVault.MEDIA_PHOTO
                                        )
                                        cos.use { it.write(bytes); it.flush() }
                                        val vol = if (GhostCryptoVault.activeProfile == GhostCryptoVault.VaultProfile.MAIN) {
                                            MonoVaultEngine.VolumeType.MASTER
                                        } else {
                                            MonoVaultEngine.VolumeType.DECOY
                                        }
                                        CaptureManager.enqueueMonoVaultAppendAsync(context, vol, bytes.clone())
                                    }
                                    Arrays.fill(bytes, 0.toByte())
                                    ram
                                }
                            }
                            vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                            triggerHapticFeedback(isStealth = true)
                            lastStatusMessage = "🔬 КАДР ВИЗОРА ($savedBadge) СОХРАНЁН В ${if (savedRam) "ОЗУ" else "СЕЙФ"}"
                        } catch (e: Exception) {
                            lastStatusMessage = "ОШИБКА ВИЗОРА: ${e.message}"
                        }
                    } else {
                        lastStatusMessage = "КАДР ВИЗОРА НЕ ГОТОВ"
                    }
                }
            },
            onExposureIndexChange = { exposureIndex = it },
            onAwbPresetChange = { awbPreset = it },
            onManualFocusChange = { manualFocusNorm = it },
            onIsoChange = { isoOverride = it },
            onShutterNsChange = { shutterNsOverride = it },
            onAeLockChange = { isAeLocked = it },
            onAwbLockChange = { isAwbLocked = it },
            onTargetFpsRangeChange = { targetFpsRange = it },
            onResetPro = {
                exposureIndex = 0
                awbPreset = CameraCapabilityProfiler.AwbPreset.AUTO
                manualFocusNorm = null
                isoOverride = null
                shutterNsOverride = null
                isAeLocked = false
                isAwbLocked = false
                targetFpsRange = null
                lastStatusMessage = "PRO-параметры сброшены на Авто"
            },
            onPhotoIqChange = { iq ->
                photoIqProfile = iq
                ZverPrefs.setPhotoIqProfile(context, iq)
                lastStatusMessage = "Улучшение фото: ${iq.labelRu}"
            },
            onToggleHistogram = { isHistogramEnabled = !isHistogramEnabled },
            onToggleZebra = { isZebraEnabled = !isZebraEnabled },
            onCloseProPanel = { isProPanelExpanded = false },
            onSelectZoom = { z ->
                zoomRatio = z
                val advice = ZoomIntelligence.evaluateZoomTier(z)
                lastStatusMessage = if (isZoomIqEnabled && z >= 1.8f) advice.badgeText else "ЗУМ: ${z.toInt()}x"
            },
            onCycleMultiFrame = {
                val options = listOf<MultiFrameStacker.StackMode?>(null) + MultiFrameStacker.StackMode.values().toList()
                val next = options[(options.indexOf(multiFrameMode) + 1) % options.size]
                multiFrameMode = next
                ZverPrefs.setMultiFrameMode(context, next)
                lastStatusMessage = if (next == null) "МУЛЬТИ-КАДР: ВЫКЛ" else "МУЛЬТИ-КАДР: ${next.labelRu}"
            },
            onToggleProPanel = { isProPanelExpanded = !isProPanelExpanded },
            onCycleEvQuick = {
                val currentStops = exposureIndex * proProfile.evStep
                val nextStops = when {
                    kotlin.math.abs(currentStops) < 0.1f -> 1.0f
                    currentStops > 0.1f -> -1.0f
                    else -> 0.0f
                }
                exposureIndex = CameraCapabilityProfiler.evIndexForStops(nextStops, proProfile.evStep, proProfile.evRange)
                val syncedLabel = CameraCapabilityProfiler.formatEvStops(exposureIndex, proProfile.evStep)
                lastStatusMessage = when {
                    exposureIndex > 0 -> "Экспозиция: $syncedLabel (светлее)"
                    exposureIndex < 0 -> "Экспозиция: $syncedLabel (темнее)"
                    else -> "Экспозиция: Авто ($syncedLabel)"
                }
            },
            onApplyCameraMode = { key -> applyCameraModeSelection(key) },
            onOpenVault = { showVaultDialog = true },
            onTriggerVideoToggle = { triggerVideoRecordingToggle() },
            onTriggerPhotoCapture = { triggerPhotoCapture() },
            onOpenVikaSheet = {
                vikaSheetInitialTab = VikaSheetTab.TASKS
                showVikaSheet = true
            },
            onCycleTimer = {
                timerSeconds = when (timerSeconds) {
                    0 -> 3
                    3 -> 5
                    5 -> 10
                    else -> 0
                }
            }
        )
        }

        if (isStealthBlackoutActive) {
            StealthBlackoutScreen(
                mode = stealthCoverMode,
                isProximityLocked = telemetry.isProximityLocked,
                onCycleMode = {
                    stealthCoverMode = when (stealthCoverMode) {
                        StealthCoverMode.BLACKOUT -> StealthCoverMode.LOCKSCREEN_CLOCK
                        StealthCoverMode.LOCKSCREEN_CLOCK -> StealthCoverMode.BROWSER_MOCK
                        StealthCoverMode.BROWSER_MOCK -> StealthCoverMode.SYSTEM_UPDATE
                        StealthCoverMode.SYSTEM_UPDATE -> StealthCoverMode.LOW_BATTERY
                        StealthCoverMode.LOW_BATTERY -> StealthCoverMode.BLACKOUT
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
        }

        CameraDialogsLayer(
            context = context,
            showVaultDialog = showVaultDialog,
            forcePinSetupFlow = forcePinSetupFlow,
            showCamouflageDialog = showCamouflageDialog,
            currentCamouflage = currentCamouflage,
            showSettingsDialog = showSettingsDialog,
            boundUseCasesSummary = boundUseCasesSummary,
            showVikaSheet = showVikaSheet,
            vikaSheetInitialTab = vikaSheetInitialTab,
            showPcbLabDialog = showPcbLabDialog,
            showPanicConfirm = showPanicConfirm,
            showSelfTestDialog = showSelfTestDialog,
            eglRecordableOk = if (gpuVisorFailed) false else glRenderer.eglRecordableOk,
            imageCapture = imageCapture,
            captureExecutor = captureExecutor,
            isDcpDehazeActive = isDcpDehazeActive,
            sentinelIsNight = telemetry.sentinelIsNight,
            onDismissVault = {
                showVaultDialog = false
                forcePinSetupFlow = false
                vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                pinSessionLockedWarning =
                    GhostCryptoVault.isConfigured(context) && !GhostCryptoVault.hasUnlockedPinSession()
            },
            onVaultUpdated = {
                vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                pinSessionLockedWarning =
                    GhostCryptoVault.isConfigured(context) && !GhostCryptoVault.hasUnlockedPinSession()
            },
            onDismissCamouflage = { showCamouflageDialog = false },
            onSelectCamouflage = { mode, ruName ->
                CamouflageManager.setCamouflage(context, mode)
                currentCamouflage = mode
                showCamouflageDialog = false
                lastStatusMessage = "МАСКИРОВКА: $ruName"
            },
            onDismissSettings = {
                showSettingsDialog = false
                photoIqProfile = ZverPrefs.getPhotoIqProfile(context)
                isZoomIqEnabled = ZverPrefs.isZoomIqEnabled(context)
                isGyroEisEnabled = ZverPrefs.isGyroEisEnabled(context)
                timeLapseIntervalMs = ZverPrefs.getTimeLapseIntervalMs(context)
            },
            onStatusMessage = { msg -> lastStatusMessage = msg },
            onOpenPinSetup = {
                forcePinSetupFlow = true
                showVaultDialog = true
            },
            onOpenVikaFromSettings = {
                vikaSheetInitialTab = VikaSheetTab.TASKS
                showVikaSheet = true
            },
            onOpenSelfTestFromSettings = {
                showSelfTestDialog = true
            },
            onDismissSelfTest = {
                showSelfTestDialog = false
            },
            onCamouflageChangedFromSettings = { mode -> currentCamouflage = mode },
            onSelectModeFromSettings = { modeKey ->
                if (modeKey == "MACRO") {
                    zoomRatio = 2.0f
                    lastStatusMessage = "РЕЖИМ: МАКРО (2x ЗУМ)"
                } else {
                    applyCameraModeSelection(modeKey)
                }
            },
            onDismissVika = { showVikaSheet = false },
            onPcbSavedToVault = { msg ->
                vaultCount = GhostCryptoVault.totalVaultItemsCount(context)
                lastStatusMessage = msg
                showPcbLabDialog = false
            },
            onDismissPcbLab = { showPcbLabDialog = false },
            onDismissPanic = { showPanicConfirm = false },
            onExecutePanicShred = {
                GhostCryptoVault.cryptoShred(context)
                MonoVaultEngine.cryptoShredMonolith(context)
                ByokManager.clearSessionMemory()
                SentinelEngine.clearSession()
                SecureClipboard.clearIfOwned(context)
                sentinelArmed = false
                sentinelEvents = emptyList()
                vaultCount = 0
                pinSessionLockedWarning = false
                showPanicConfirm = false
                lastStatusMessage = "СХРОН И КЛЮЧИ ПОЛНОСТЬЮ УНИЧТОЖЕНЫ"
            }
        )
    }
}
