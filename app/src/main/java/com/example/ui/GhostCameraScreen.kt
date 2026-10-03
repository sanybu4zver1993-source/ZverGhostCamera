package com.example.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
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
import java.util.concurrent.Executors

private val CyberBlack = Color(0xFF070B0E)
private val CyberGreen = Color(0xFF00FF66)
private val CyberGreenDark = Color(0xFF008F39)
private val CyberRed = Color(0xFFFF1744)
private val CyberMuted = Color(0xFF6B7E8C)
private val CyberSurface = Color(0xFF111820)
private val CyberBorder = Color(0xFF223240)
private val NeonCyan = Color(0xFF00E5FF)
private val NeonYellow = Color(0xFFFFEA00)

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

    // Computational & Hardware Sensor States (Redmi 13C Helio G85 spec)
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
    var isDcpDehazeActive by remember { mutableStateOf(false) }

    // Live Sensor Telemetry
    var gyroAngularSpeed by remember { mutableStateOf(0f) }
    var isGyroStable by remember { mutableStateOf(true) }
    var isTripodMode by remember { mutableStateOf(false) }

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

    // Asynchronous 1GB Monolithic Container initialization on IO (prevents eMMC 5.1 ANR)
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

            isCapturing = true
            try {
                for (shotIdx in 1..burstCount) {
                    val manager = CaptureManager(
                        context = context,
                        imageCapture = cap,
                        captureExecutor = captureExecutor
                    )
                    val result = manager.takeSecurePhoto()
                    vaultCount = GhostCryptoVault.listVaultFiles(context).size
                    val kb = result.encryptedSizeBytes / 1024
                    lastStatusMessage = "4080x3072 [$shotIdx/$burstCount]: ${kb}KB IN ${result.durationMs}ms"

                    if (shotIdx < burstCount) {
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                lastStatusMessage = "ERR: ${e.localizedMessage ?: "Capture failed"}"
            } finally {
                isCapturing = false
            }
        }
    }

    // Wire hardware volume keys to trigger capture
    LaunchedEffect(Unit) {
        MainActivity.onVolumeShutterTrigger = {
            triggerPhotoCapture()
        }
    }

    // Auto-shutter loop: fires when stable + focus locked + sharpness threshold satisfied
    LaunchedEffect(isAutoShutterEnabled, isFocusLocked, isGyroStable, liveLaplacianVariance) {
        if (isAutoShutterEnabled && isFocusLocked && isGyroStable && (liveLaplacianVariance >= 110.0 || liveLaplacianVariance == 0.0) && !isCapturing) {
            triggerPhotoCapture()
            isFocusLocked = false
        }
    }

    // Bind CameraX with Redmi 13C parameters (Fixed 2MP Macro lock + 320x240 ImageAnalysis)
    LaunchedEffect(lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraProvider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }

        val preview = Preview.Builder().build()

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)

        // Hardware-level MediaTek Helio G85 ISP bypass & stabilization options
        val c2Extender = androidx.camera.camera2.interop.Camera2Interop.Extender(captureBuilder)
        c2Extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        )
        c2Extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        c2Extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_MODE,
            android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
        )
        c2Extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
            android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_OFF
        )
        c2Extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.EDGE_MODE,
            android.hardware.camera2.CaptureRequest.EDGE_MODE_OFF
        )

        val capture = captureBuilder.build()

        // 1:1 Center ROI ImageAnalysis: captures 400x400 unscaled native sensor crop in 1ms
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

        // Enforce Camera ID 0 (Main 50MP -> 12.5MP Quad-Bayer). Blocks Macro 2MP (Fixed focus).
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
            lastStatusMessage = "Camera bind error: ${e.localizedMessage}"
        }
    }

    LaunchedEffect(flashMode) { imageCapture?.flashMode = flashMode }
    LaunchedEffect(exposureIndex) {
        try { cameraControl?.setExposureCompensationIndex(exposureIndex) } catch (_: Exception) {}
    }
    LaunchedEffect(zoomRatio) {
        try { cameraControl?.setZoomRatio(zoomRatio) } catch (_: Exception) {}
    }

    // STEALTH BLACKOUT MODE: Screen is totally dark, capture via volume key or single tap
    if (isStealthBlackoutActive) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { isStealthBlackoutActive = false },
                        onTap = { triggerPhotoCapture() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "•",
                color = Color.DarkGray.copy(alpha = 0.2f),
                fontSize = 12.sp
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack)
    ) {
        // CameraX Live Viewfinder with Tap-To-Focus
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
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

        // Center Tactical Reticle
        TacticalReticle(modifier = Modifier.fillMaxSize())

        // Focus Peaking Simulation Overlay
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

        // RED NEON PERIMETER BORDER when :assist process is active
        if (isAssistModeActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(4.dp, CyberRed.copy(alpha = redBorderAlpha))
            )
        }

        // TOP TACTICAL HUD
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberBlack.copy(alpha = 0.90f), RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        if (isAssistModeActive) CyberRed.copy(alpha = 0.8f) else CyberGreen.copy(alpha = 0.4f),
                        RoundedCornerShape(8.dp)
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
                            text = "ZVER CAMERA v3.9 🐾",
                            color = if (isAssistModeActive) CyberRed else CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = "4080x3072 QUAD-BAYER // MT6768",
                        color = CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // BYOK Settings
                    IconButton(onClick = { showByokDialog = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Key, contentDescription = "BYOK", tint = CyberGreen, modifier = Modifier.size(16.dp))
                    }

                    // Stealth Blackout Toggle
                    IconButton(onClick = { isStealthBlackoutActive = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.DarkMode, contentDescription = "Blackout", tint = CyberMuted, modifier = Modifier.size(16.dp))
                    }

                    // Camouflage Selector
                    IconButton(onClick = { showCamouflageDialog = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = "Camouflage", tint = CyberGreen, modifier = Modifier.size(16.dp))
                    }

                    // Assist Process Toggle
                    IconButton(onClick = { isAssistModeActive = !isAssistModeActive }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (isAssistModeActive) Icons.Default.Warning else Icons.Default.Shield,
                            contentDescription = "Toggle Assist Mode",
                            tint = if (isAssistModeActive) CyberRed else CyberMuted,
                            modifier = Modifier.size(16.dp)
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
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip", tint = CyberGreen, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Gyro & Computational State Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberBlack.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .border(1.dp, CyberBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gyro stability status
                val gyroLabel = when {
                    isTripodMode -> "TRIPOD: NOISE REDUCTION"
                    isGyroStable -> "HANDHELD: MFSR 2x ACTIVE"
                    else -> "MICRO-BLUR ALERT"
                }
                val gyroColor = if (isGyroStable) CyberGreen else CyberRed
                Text(
                    text = "$gyroLabel | SHARP: ${liveLaplacianVariance.toInt()} (${String.format("%.3f", gyroAngularSpeed)} rad/s)",
                    color = gyroColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )

                // Macro Optical Limit Info
                Text(
                    text = "MACRO MIN: 10cm",
                    color = CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // OSINT Engineering Tools Bar (Peaking, False-Color, Auto-Shutter, Dehaze)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = isFocusPeakingEnabled,
                    onClick = { isFocusPeakingEnabled = !isFocusPeakingEnabled },
                    label = { Text("PEAKING", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonCyan.copy(alpha = 0.3f),
                        selectedLabelColor = NeonCyan
                    )
                )
                FilterChip(
                    selected = isFalseColorEnabled,
                    onClick = { isFalseColorEnabled = !isFalseColorEnabled },
                    label = { Text("EXPOSURE", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonYellow.copy(alpha = 0.3f),
                        selectedLabelColor = NeonYellow
                    )
                )
                FilterChip(
                    selected = isAutoShutterEnabled,
                    onClick = { isAutoShutterEnabled = !isAutoShutterEnabled },
                    label = { Text("AUTO-SHUTTER", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberGreen.copy(alpha = 0.3f),
                        selectedLabelColor = CyberGreen
                    )
                )
                FilterChip(
                    selected = isDcpDehazeActive,
                    onClick = { isDcpDehazeActive = !isDcpDehazeActive },
                    label = { Text("DEHAZE", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberGreen.copy(alpha = 0.3f),
                        selectedLabelColor = CyberGreen
                    )
                )
            }

            // Digital Crop Warning if zoom > 2.0x
            if (zoomRatio > 2.0f) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberRed.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "DIGITAL CROP (NO ADDED RESOLUTION // MAX OPTICAL: 2x)",
                        color = CyberRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
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

        // Notification banner
        AnimatedVisibility(
            visible = isCapturing || lastStatusMessage != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 180.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(CyberBlack.copy(alpha = 0.92f))
                    .border(1.dp, if (isCapturing) CyberGreen else CyberGreenDark, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCapturing) {
                        CircularProgressIndicator(color = CyberGreen, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "MFSR ZERO-COPY STREAMING...",
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    } else {
                        Text(
                            text = lastStatusMessage ?: "",
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // BOTTOM CONTROLS HUD
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom & EV Quick Selectors
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
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (zoomRatio == z) CyberGreen.copy(alpha = 0.2f) else Color.Transparent)
                                .border(1.dp, if (zoomRatio == z) CyberGreen else CyberBorder, RoundedCornerShape(4.dp))
                                .clickable { zoomRatio = z }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // EV Steps
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-1, 0, 1).forEach { ev ->
                        Text(
                            text = if (ev == 0) "0" else "${if (ev > 0) "+" else ""}$ev",
                            color = if (exposureIndex == ev) CyberGreen else CyberMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (exposureIndex == ev) CyberGreen.copy(alpha = 0.2f) else Color.Transparent)
                                .border(1.dp, if (exposureIndex == ev) CyberGreen else CyberBorder, RoundedCornerShape(4.dp))
                                .clickable { exposureIndex = ev }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Primary Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Monolithic Vault Button
                Button(
                    onClick = { showVaultDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSurface.copy(alpha = 0.90f)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberGreen.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Vault", tint = CyberGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "MONO-VAULT ($vaultCount)",
                        color = CyberGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Shutter Button
                ShutterButton(
                    isCapturing = isCapturing || countdownRemaining != null,
                    isAssistMode = isAssistModeActive,
                    onClick = { triggerPhotoCapture() }
                )

                // Panic Shred Button
                IconButton(
                    onClick = { showPanicConfirm = true },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CyberRed.copy(alpha = 0.2f))
                        .border(1.dp, CyberRed.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = "Panic", tint = CyberRed, modifier = Modifier.size(22.dp))
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
                title = { Text("DISGUISE / CAMOUFLAGE", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp) },
                text = {
                    Column {
                        Text("Select launcher disguise identity:", color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        for (mode in CamouflageManager.CamouflageMode.values()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        CamouflageManager.setCamouflage(context, mode)
                                        currentCamouflage = mode
                                        showCamouflageDialog = false
                                        lastStatusMessage = "CAMOUFLAGE: ${mode.displayName.uppercase()}"
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(mode.displayName, color = if (mode == currentCamouflage) CyberGreen else Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                if (mode == currentCamouflage) {
                                    Icon(Icons.Default.Check, contentDescription = "Active", tint = CyberGreen, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showCamouflageDialog = false }) { Text("CLOSE", color = CyberMuted, fontFamily = FontFamily.Monospace) } },
                containerColor = CyberSurface
            )
        }

        // BYOK Gemini Dialog
        if (showByokDialog) {
            var inputKey by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showByokDialog = false },
                title = { Text("BYOK GEMINI CREDENTIALS", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                text = {
                    Column {
                        Text("Your Gemini API key is encrypted directly into hardware KeyStore. Used exclusively in the isolated :assist process.", color = Color.White, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = inputKey,
                            onValueChange = { inputKey = it },
                            label = { Text("Gemini API Key", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
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
                                lastStatusMessage = "BYOK GEMINI KEY SAVED IN KEYSTORE"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark)
                    ) {
                        Text("SAVE KEY", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showByokDialog = false }) {
                        Text("CANCEL", color = CyberMuted, fontFamily = FontFamily.Monospace)
                    }
                },
                containerColor = CyberSurface
            )
        }

        // Emergency Crypto-Shred Confirmation
        if (showPanicConfirm) {
            AlertDialog(
                onDismissRequest = { showPanicConfirm = false },
                title = { Text("VERACRYPT MONOLITH SHRED", color = CyberRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = { Text("Overwrites monolithic container with noise and permanently deletes all KeyStore hardware keys. Irreversible.", color = Color.White, fontSize = 12.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            GhostCryptoVault.cryptoShred(context)
                            com.example.crypto.MonoVaultEngine.cryptoShredMonolith(context)
                            vaultCount = 0
                            showPanicConfirm = false
                            lastStatusMessage = "CONTAINER & TEE KEYS DESTROYED"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberRed)
                    ) {
                        Text("DESTROY NOW", color = Color.White)
                    }
                },
                dismissButton = { TextButton(onClick = { showPanicConfirm = false }) { Text("CANCEL", color = CyberMuted) } },
                containerColor = CyberSurface
            )
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
            .background(Color(0x33001133))
            .border(2.dp, NeonYellow.copy(alpha = 0.6f))
            .padding(10.dp)
    ) {
        Text(
            text = "EXPOSURE / LUMINANCE EVALUATION [ACTIVE]",
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
                contentDescription = "Capture",
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
