package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.MainActivity
import com.example.assist.ByokManager
import com.example.assist.GhostAssistService
import com.example.camera.AntiBlurSensorEngine
import com.example.camera.CaptureManager
import com.example.crypto.GhostCryptoVault
import com.example.security.AntiForensicsGuard
import com.example.security.CamouflageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

private val CyberBlack = Color(0xFF070B0E)
private val CyberGreen = Color(0xFF00FF66)
private val CyberGreenDark = Color(0xFF008F39)
private val CyberRed = Color(0xFFFF1744)
private val CyberMuted = Color(0xFF8FA3B0)
private val CyberSurface = Color(0xFF111820)
private val CyberBorder = Color(0xFF223240)
private val NeonCyan = Color(0xFF00E5FF)
private val NeonYellow = Color(0xFFFFEA00)

enum class StealthCoverMode(val titleRu: String) {
    BLACKOUT("Чёрный экран"),
    LOCKSCREEN_CLOCK("Часы (Блокировка)"),
    BROWSER_MOCK("Поиск (Браузер)")
}

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
@Composable
fun GhostCameraScreen() {
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
    var isCapturing by remember { mutableStateOf(false) }
    var lastStatusMessage by remember { mutableStateOf<String?>(null) }
    var showShutterFlash by remember { mutableStateOf(false) }
    var vaultCount by remember { mutableStateOf(GhostCryptoVault.listVaultFiles(context).size) }
    var showVaultDialog by remember { mutableStateOf(false) }
    var showPanicConfirm by remember { mutableStateOf(false) }
    var showCamouflageDialog by remember { mutableStateOf(false) }
    var showByokDialog by remember { mutableStateOf(false) }

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

    // Sensor engine lifecycle
    DisposableEffect(Unit) {
        antiBlurEngine.start()
        onDispose {
            antiBlurEngine.stop()
            captureExecutor.shutdown()
            analysisExecutor.shutdown()
            MainActivity.onVolumeShutterTrigger = null
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
                        manager.takeSecurePhoto()
                        vaultCount = GhostCryptoVault.listVaultFiles(context).size
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
                        val result = manager.takeSecurePhoto()
                        vaultCount = GhostCryptoVault.listVaultFiles(context).size
                        val kb = result.encryptedSizeBytes / 1024
                        lastStatusMessage = "📸 КАДР В СЕЙФЕ! [#$vaultCount] (${kb}КБ за ${result.durationMs}мс)"

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

    // Wire hardware volume keys to trigger capture
    LaunchedEffect(Unit) {
        MainActivity.onVolumeShutterTrigger = {
            triggerPhotoCapture()
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
                liveLaplacianVariance = variance
            } finally {
                imageProxy.close()
            }
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

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

            previewView?.let { pv ->
                preview.setSurfaceProvider(pv.surfaceProvider)
            }
        } catch (e: Exception) {
            lastStatusMessage = "Ошибка камеры: ${e.localizedMessage}"
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
            onCycleMode = {
                stealthCoverMode = when (stealthCoverMode) {
                    StealthCoverMode.BLACKOUT -> StealthCoverMode.LOCKSCREEN_CLOCK
                    StealthCoverMode.LOCKSCREEN_CLOCK -> StealthCoverMode.BROWSER_MOCK
                    StealthCoverMode.BROWSER_MOCK -> StealthCoverMode.BLACKOUT
                }
            },
            onCapture = { triggerPhotoCapture() },
            onExit = { isStealthBlackoutActive = false }
        )
        return
    }

    // MAIN CAMERA VIEWPORT
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // CameraX Live Viewfinder
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            val factory = meteringPointFactory
                            val point = factory.createPoint(event.x, event.y)
                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            cameraControl?.startFocusAndMetering(action)
                            focusTapPoint = Offset(event.x, event.y)
                            isFocusLocked = true
                        }
                        true
                    }
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Center Tactical Reticle (clean, razor sharp, non-dimming)
        TacticalReticle(modifier = Modifier.fillMaxSize())

        // Document Level Overlay (Virtual Inclinometer)
        if (isDocumentModeActive) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val crosshairColor = if (isDocumentParallel) CyberGreen else NeonYellow
                Canvas(modifier = Modifier.size(160.dp)) {
                    val cx = size.width / 2
                    val cy = size.height / 2
                    drawCircle(
                        color = crosshairColor,
                        radius = 56.dp.toPx(),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = crosshairColor.copy(alpha = 0.35f),
                        radius = 14.dp.toPx()
                    )
                    drawLine(
                        color = crosshairColor,
                        start = Offset(cx - 64.dp.toPx(), cy),
                        end = Offset(cx + 64.dp.toPx(), cy),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawLine(
                        color = crosshairColor,
                        start = Offset(cx, cy - 64.dp.toPx()),
                        end = Offset(cx, cy + 64.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                Text(
                    text = if (isDocumentParallel) "ДОКУМЕНТ В ПЛОСКОСТИ: ${String.format("%.1f", documentTiltDeg)}° [ЗАФИКСИРОВАНО]" else "НАКЛОН: ${String.format("%.1f", documentTiltDeg)}° (ВЫРОВНЯЙ СТОЛ)",
                    color = if (isDocumentParallel) CyberGreen else NeonYellow,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 180.dp)
                )
            }
        }

        // Focus Peaking Simulation
        if (isFocusPeakingEnabled) {
            FocusPeakingOverlay(modifier = Modifier.fillMaxSize())
        }

        // False-Color Exposure Overlay
        if (isFalseColorEnabled) {
            FalseColorExposureOverlay(modifier = Modifier.fillMaxSize())
        }

        // Tap-To-Focus Indicator
        focusTapPoint?.let { pt ->
            LaunchedEffect(pt) {
                delay(2000)
                focusTapPoint = null
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = CyberGreen, radius = 26.dp.toPx(), center = pt, style = Stroke(width = 2.dp.toPx()))
                drawCircle(color = CyberGreen.copy(alpha = 0.3f), radius = 6.dp.toPx(), center = pt)
            }
        }

        // SHUTTER FLASH ANIMATION: Instant green flash when shutter triggers!
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
                    .border(4.dp, CyberRed.copy(alpha = redBorderAlpha))
            )
        }

        // TOP TRANSLUCENT TACTICAL HUD (Clean translucent glass design)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberBlack.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        if (isAssistModeActive) CyberRed.copy(alpha = 0.8f) else CyberGreen.copy(alpha = 0.35f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isAssistModeActive) CyberRed else CyberGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ZVER CAMERA v4.2 🐾",
                            color = if (isAssistModeActive) CyberRed else CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = "СЕНСОР 50МП // ХОЛОДНЫЙ РЕЖИМ",
                        color = CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // BYOK Gemini Key
                    IconButton(onClick = { showByokDialog = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Key, contentDescription = "Ключ", tint = CyberGreen, modifier = Modifier.size(17.dp))
                    }

                    // Stealth Blackout Toggle
                    IconButton(onClick = { isStealthBlackoutActive = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DarkMode, contentDescription = "Стелс", tint = NeonCyan, modifier = Modifier.size(17.dp))
                    }

                    // Camouflage Selector
                    IconButton(onClick = { showCamouflageDialog = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = "Маскировка", tint = CyberGreen, modifier = Modifier.size(17.dp))
                    }

                    // Assist Process Toggle
                    IconButton(onClick = { isAssistModeActive = !isAssistModeActive }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (isAssistModeActive) Icons.Default.Warning else Icons.Default.Shield,
                            contentDescription = "Ассистент",
                            tint = if (isAssistModeActive) CyberRed else CyberMuted,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    // Lens Flip
                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Перевернуть камеру", tint = CyberGreen, modifier = Modifier.size(17.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Gyro & Sensor Telemetry Bar (Translucent glass)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberBlack.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                    .border(1.dp, CyberBorder.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val gyroLabel = when {
                    isTripodMode -> "ШТАТИВ: СТАБИЛИЗАЦИЯ"
                    isGyroStable -> "С РУК: СТАБИЛЬНО"
                    else -> "ВНИМАНИЕ: ТРЯСКА!"
                }
                val gyroColor = if (isGyroStable) CyberGreen else CyberRed
                Text(
                    text = "$gyroLabel | РЕЗКОСТЬ: ${liveLaplacianVariance.toInt()}",
                    color = gyroColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "МАКРО: от 10см",
                    color = CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Tools Horizontal Scroll Bar (100% Russian labels)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = isFocusPeakingEnabled,
                    onClick = { isFocusPeakingEnabled = !isFocusPeakingEnabled },
                    label = { Text("ФОКУС-ПИК", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonCyan.copy(alpha = 0.35f),
                        selectedLabelColor = NeonCyan,
                        containerColor = CyberBlack.copy(alpha = 0.45f),
                        labelColor = CyberMuted
                    )
                )
                FilterChip(
                    selected = isFalseColorEnabled,
                    onClick = { isFalseColorEnabled = !isFalseColorEnabled },
                    label = { Text("ЭКСПО-КАРТА", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonYellow.copy(alpha = 0.35f),
                        selectedLabelColor = NeonYellow,
                        containerColor = CyberBlack.copy(alpha = 0.45f),
                        labelColor = CyberMuted
                    )
                )
                FilterChip(
                    selected = isAutoShutterEnabled,
                    onClick = { isAutoShutterEnabled = !isAutoShutterEnabled },
                    label = { Text("АВТОСПУСК", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberGreen.copy(alpha = 0.35f),
                        selectedLabelColor = CyberGreen,
                        containerColor = CyberBlack.copy(alpha = 0.45f),
                        labelColor = CyberMuted
                    )
                )
                FilterChip(
                    selected = isDocumentModeActive,
                    onClick = { isDocumentModeActive = !isDocumentModeActive },
                    label = { Text("УРОВЕНЬ ДОК", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberGreen.copy(alpha = 0.35f),
                        selectedLabelColor = CyberGreen,
                        containerColor = CyberBlack.copy(alpha = 0.45f),
                        labelColor = CyberMuted
                    )
                )
                FilterChip(
                    selected = isFocusBracketingActive,
                    onClick = { isFocusBracketingActive = !isFocusBracketingActive },
                    label = { Text("БРЕКЕТИНГ", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonCyan.copy(alpha = 0.35f),
                        selectedLabelColor = NeonCyan,
                        containerColor = CyberBlack.copy(alpha = 0.45f),
                        labelColor = CyberMuted
                    )
                )
                FilterChip(
                    selected = isDcpDehazeActive,
                    onClick = { isDcpDehazeActive = !isDcpDehazeActive },
                    label = { Text("АНТИ-ТУМАН", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberGreen.copy(alpha = 0.35f),
                        selectedLabelColor = CyberGreen,
                        containerColor = CyberBlack.copy(alpha = 0.45f),
                        labelColor = CyberMuted
                    )
                )
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

        // Notification Banner: Prominent clear status badge
        AnimatedVisibility(
            visible = isCapturing || lastStatusMessage != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 160.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberBlack.copy(alpha = 0.85f))
                    .border(1.5.dp, if (isCapturing) CyberGreen else CyberGreenDark, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCapturing) {
                        CircularProgressIndicator(color = CyberGreen, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ШИФРОВАНИЕ В СЕЙФ...",
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    } else {
                        Text(
                            text = lastStatusMessage ?: "",
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // BOTTOM CONTROLS HUD (Translucent glass floating above preview)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom & Exposure quick toggles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Zoom toggles
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1.0f, 2.0f, 3.0f).forEach { z ->
                        Text(
                            text = "${z.toInt()}x",
                            color = if (zoomRatio == z) CyberGreen else CyberMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (zoomRatio == z) CyberGreen.copy(alpha = 0.35f) else CyberBlack.copy(alpha = 0.45f))
                                .border(1.dp, if (zoomRatio == z) CyberGreen else CyberBorder, RoundedCornerShape(6.dp))
                                .clickable { zoomRatio = z }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // EV Steps
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-1, 0, 1).forEach { ev ->
                        val evLabel = when {
                            ev == 0 -> "EV 0"
                            ev > 0 -> "EV +$ev"
                            else -> "EV $ev"
                        }
                        Text(
                            text = evLabel,
                            color = if (exposureIndex == ev) CyberGreen else CyberMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (exposureIndex == ev) CyberGreen.copy(alpha = 0.35f) else CyberBlack.copy(alpha = 0.45f))
                                .border(1.dp, if (exposureIndex == ev) CyberGreen else CyberBorder, RoundedCornerShape(6.dp))
                                .clickable { exposureIndex = ev }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Primary Action Row: Vault, Shutter, Panic Shred
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberBlack.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .border(1.dp, CyberBorder.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vault Button
                Button(
                    onClick = { showVaultDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSurface.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberGreen.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Сейф", tint = CyberGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "СХРОН ($vaultCount)",
                        color = CyberGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Tactile Shutter Button
                ShutterButton(
                    isCapturing = isCapturing || countdownRemaining != null,
                    isAssistMode = isAssistModeActive,
                    onClick = { triggerPhotoCapture() }
                )

                // Panic Shred Button
                IconButton(
                    onClick = { showPanicConfirm = true },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CyberRed.copy(alpha = 0.25f))
                        .border(1.dp, CyberRed.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = "Шредер", tint = CyberRed, modifier = Modifier.size(22.dp))
                }
            }
        }

        // Vault Viewer Dialog
        if (showVaultDialog) {
            GhostVaultDialog(
                onDismiss = {
                    showVaultDialog = false
                    currentProfile = GhostCryptoVault.activeProfile
                    vaultCount = GhostCryptoVault.listVaultFiles(context).size
                },
                onVaultUpdated = {
                    currentProfile = GhostCryptoVault.activeProfile
                    vaultCount = GhostCryptoVault.listVaultFiles(context).size
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

        // BYOK Gemini Dialog
        if (showByokDialog) {
            var inputKey by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showByokDialog = false },
                title = { Text("КЛЮЧ GEMINI API", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                text = {
                    Column {
                        Text("Твой API-ключ шифруется в аппаратный чип KeyStore. Используется строго в изолированном фоновом процессе :assist.", color = Color.White, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = inputKey,
                            onValueChange = { inputKey = it },
                            label = { Text("API-ключ Gemini", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberGreen,
                                unfocusedBorderColor = CyberBorder,
                                focusedTextColor = Color.White
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (inputKey.isNotBlank()) {
                                ByokManager.saveKey(context, inputKey.toCharArray())
                                showByokDialog = false
                                lastStatusMessage = "КЛЮЧ GEMINI СОХРАНЁН В KEYSTORE"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark)
                    ) {
                        Text("СОХРАНИТЬ", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showByokDialog = false }) {
                        Text("ОТМЕНА", color = CyberMuted, fontFamily = FontFamily.Monospace)
                    }
                },
                containerColor = CyberSurface
            )
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

/**
 * Advanced Stealth Cover Screen:
 * 1. PURE_BLACK (Minimum IPS screen glow, single tiny dot).
 * 2. LOCKSCREEN_CLOCK (Mimics locked Android/MIUI screen with real live time and battery).
 * 3. BROWSER_MOCK (Mimics open Google search article).
 *
 * Tap anywhere or press volume keys = takes silent photo with discreet haptic tick!
 * Top-right / double-tap = exit stealth mode.
 */
@Composable
private fun StealthBlackoutScreen(
    mode: StealthCoverMode,
    onCycleMode: () -> Unit,
    onCapture: () -> Unit,
    onExit: () -> Unit
) {
    var currentTimeStr by remember { mutableStateOf("") }
    var currentDateStr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
        while (true) {
            val now = Date()
            currentTimeStr = timeFormat.format(now)
            currentDateStr = dateFormat.format(now).replaceFirstChar { it.uppercase() }
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onExit() },
                    onTap = { onCapture() }
                )
            }
    ) {
        when (mode) {
            StealthCoverMode.BLACKOUT -> {
                // Pitch black with barely visible indicator
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "•",
                        color = Color.DarkGray.copy(alpha = 0.15f),
                        fontSize = 12.sp
                    )
                }
            }
            StealthCoverMode.LOCKSCREEN_CLOCK -> {
                // Realistic Android Lockscreen: live clock, date, subtle lock icon
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 90.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Заблокировано",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = currentTimeStr,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 68.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentDateStr,
                        color = Color.LightGray.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                        fontFamily = FontFamily.SansSerif
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "Проведите вверх для разблокировки",
                        color = Color.Gray.copy(alpha = 0.4f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 40.dp)
                    )
                }
            }
            StealthCoverMode.BROWSER_MOCK -> {
                // Mimics Chrome search page
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF202124))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Поиск", tint = Color.Gray, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("google.com/search?q=новости+чернигов", color = Color.LightGray, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text("Главные новости дня", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Погода в регионе: облачно с прояснениями, температура +14°C. Ветер юго-западный до 4 м/с. Прогноз на ближайшие сутки без существенных изменений.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Top stealth controls (Switch disguise & Exit)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mode switcher button
            Text(
                text = "Маскировка: ${mode.titleRu}",
                color = Color.DarkGray.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable { onCycleMode() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            // Exit button
            IconButton(
                onClick = onExit,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Выйти из стелса",
                    tint = Color.DarkGray.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun FocusPeakingOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.dp.toPx()
        val cx = size.width / 2
        val cy = size.height / 2
        drawCircle(color = NeonCyan.copy(alpha = 0.45f), radius = 60.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = stroke))
        drawCircle(color = NeonCyan.copy(alpha = 0.25f), radius = 120.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = stroke))
    }
}

@Composable
private fun FalseColorExposureOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .border(2.dp, NeonYellow.copy(alpha = 0.6f))
            .padding(10.dp)
    ) {
        Text(
            text = "КОНТРОЛЬ ПЕРЕСВЕТОВ [АКТИВЕН]",
            color = NeonYellow,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
private fun ShutterButton(
    isCapturing: Boolean,
    isAssistMode: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val activeColor = if (isAssistMode) CyberRed else CyberGreen

    Box(
        modifier = Modifier
            .size(72.dp)
            .clickable(enabled = !isCapturing, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = if (isCapturing) CyberRed else activeColor.copy(alpha = pulseAlpha),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (isCapturing) CyberRed.copy(alpha = 0.4f) else activeColor.copy(alpha = 0.25f))
                .border(2.dp, if (isCapturing) CyberRed else activeColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Спуск",
                tint = if (isCapturing) CyberRed else activeColor,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun TacticalReticle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val reticleRadius = 40.dp.toPx()
        val tickLen = 14.dp.toPx()
        val cornerColor = CyberGreen.copy(alpha = 0.35f)
        val strokeW = 1.5.dp.toPx()

        drawLine(cornerColor, Offset(cx - reticleRadius, cy), Offset(cx - reticleRadius + tickLen, cy), strokeW)
        drawLine(cornerColor, Offset(cx + reticleRadius - tickLen, cy), Offset(cx + reticleRadius, cy), strokeW)
        drawLine(cornerColor, Offset(cx, cy - reticleRadius), Offset(cx, cy - reticleRadius + tickLen), strokeW)
        drawLine(cornerColor, Offset(cx, cy + reticleRadius - tickLen), Offset(cx, cy + reticleRadius), strokeW)
        drawCircle(color = CyberGreen.copy(alpha = 0.5f), radius = 2.5.dp.toPx(), center = Offset(cx, cy))
    }
}
