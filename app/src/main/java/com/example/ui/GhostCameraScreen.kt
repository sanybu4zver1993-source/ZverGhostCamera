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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.assist.GhostAssistService
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

    var isCapturing by remember { mutableStateOf(false) }
    var lastStatusMessage by remember { mutableStateOf<String?>(null) }
    var vaultCount by remember { mutableStateOf(GhostCryptoVault.listVaultFiles(context).size) }
    var showVaultDialog by remember { mutableStateOf(false) }
    var showPanicConfirm by remember { mutableStateOf(false) }
    var showCamouflageDialog by remember { mutableStateOf(false) }

    val securityStatus = remember { AntiForensicsGuard.assessDeviceSecurity(context) }
    var currentProfile by remember { mutableStateOf(GhostCryptoVault.activeProfile) }
    var currentCamouflage by remember { mutableStateOf(CamouflageManager.getCurrentCamouflage(context)) }

    // Architecture v2.0: Process isolation toggle (:core offline vs :assist online)
    var isAssistModeActive by remember { mutableStateOf(false) }

    // Open Camera Features: Timer, Burst, Manual Focus, EV Exposure, Zoom
    var timerSeconds by remember { mutableStateOf(0) } // 0, 3, 5, 10
    var burstCount by remember { mutableStateOf(1) } // 1, 3, 5
    var countdownRemaining by remember { mutableStateOf<Int?>(null) }
    var focusTapPoint by remember { mutableStateOf<Offset?>(null) }
    var exposureIndex by remember { mutableStateOf(0) } // -2, -1, 0, +1, +2
    var zoomRatio by remember { mutableStateOf(1.0f) }

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

    // Handle :assist process service binding/lifecycle
    LaunchedEffect(isAssistModeActive) {
        val intent = Intent(context, GhostAssistService::class.java)
        if (isAssistModeActive) {
            context.startService(intent)
        } else {
            context.stopService(intent)
        }
    }

    // Bind CameraX
    LaunchedEffect(lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraProvider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }

        val preview = Preview.Builder().build()

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

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
            lastStatusMessage = "Camera bind error: ${e.localizedMessage}"
        }
    }

    // Update Flash Mode
    LaunchedEffect(flashMode) {
        imageCapture?.flashMode = flashMode
    }

    // Update Exposure Compensation
    LaunchedEffect(exposureIndex) {
        try {
            cameraControl?.setExposureCompensationIndex(exposureIndex)
        } catch (_: Exception) {}
    }

    // Update Zoom
    LaunchedEffect(zoomRatio) {
        try {
            cameraControl?.setZoomRatio(zoomRatio)
        } catch (_: Exception) {}
    }

    DisposableEffect(Unit) {
        onDispose {
            captureExecutor.shutdown()
        }
    }

    // Execution of photo capture (supports countdown timer + burst repeat)
    fun triggerPhotoCapture() {
        val cap = imageCapture ?: return
        if (isCapturing || countdownRemaining != null) return

        scope.launch {
            // Step 1: Countdown Timer with audio tick
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

            // Step 2: Auto-repeat burst capture
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
                    lastStatusMessage = "SHOT $shotIdx/$burstCount: ${kb}KB (STRIPPED ${result.strippedSegmentsCount}) IN ${result.durationMs}ms"

                    if (shotIdx < burstCount) {
                        delay(1200) // 1.2s burst interval
                    }
                }
            } catch (e: Exception) {
                lastStatusMessage = "ERR: ${e.localizedMessage ?: "Capture failed"}"
            } finally {
                isCapturing = false
            }
        }
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
                    setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            val factory = meteringPointFactory
                            val point = factory.createPoint(event.x, event.y)
                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            cameraControl?.startFocusAndMetering(action)
                            focusTapPoint = Offset(event.x, event.y)
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

        // Focus Tap Feedback Indicator
        focusTapPoint?.let { pt ->
            LaunchedEffect(pt) {
                delay(1500)
                focusTapPoint = null
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = CyberGreen, radius = 24.dp.toPx(), center = pt, style = Stroke(width = 2.dp.toPx()))
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
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberBlack.copy(alpha = 0.88f), RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        if (isAssistModeActive) CyberRed.copy(alpha = 0.8f) else CyberGreen.copy(alpha = 0.4f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                            text = if (isAssistModeActive) "ZVER CAMERA // ASSIST ON" else "ZVER CAMERA 🐾 v2.2",
                            color = if (isAssistModeActive) CyberRed else CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = "CAMOUFLAGE: ${currentCamouflage.displayName.uppercase()}",
                        color = CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Camouflage Selector
                    IconButton(
                        onClick = { showCamouflageDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = "Camouflage",
                            tint = CyberGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Assist Mode Toggle
                    IconButton(
                        onClick = { isAssistModeActive = !isAssistModeActive },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isAssistModeActive) Icons.Default.Warning else Icons.Default.Shield,
                            contentDescription = "Toggle Assist Mode",
                            tint = if (isAssistModeActive) CyberRed else CyberMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Flash Mode Toggle
                    IconButton(
                        onClick = {
                            flashMode = when (flashMode) {
                                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        val icon = when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                            else -> Icons.Default.FlashOff
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = "Flash",
                            tint = if (flashMode != ImageCapture.FLASH_MODE_OFF) CyberGreen else CyberMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Lens Flip (Front / Back)
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
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Switch Camera",
                            tint = CyberGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Open Camera Controls Bar: Timer, Burst, EV, Zoom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberBlack.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .border(1.dp, CyberBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timer toggle
                Row(
                    modifier = Modifier.clickable {
                        timerSeconds = when (timerSeconds) {
                            0 -> 3
                            3 -> 5
                            5 -> 10
                            else -> 0
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Timer, contentDescription = "Timer", tint = CyberGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (timerSeconds == 0) "TIMER: OFF" else "${timerSeconds}s", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }

                // Burst repeat mode
                Row(
                    modifier = Modifier.clickable {
                        burstCount = when (burstCount) {
                            1 -> 3
                            3 -> 5
                            else -> 1
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Repeat, contentDescription = "Burst", tint = CyberGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (burstCount == 1) "1x" else "${burstCount}x BURST", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }

                // Exposure index (+/- EV)
                Row(
                    modifier = Modifier.clickable {
                        exposureIndex = when (exposureIndex) {
                            0 -> 1
                            1 -> 2
                            2 -> -2
                            -2 -> -1
                            else -> 0
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Exposure, contentDescription = "EV", tint = CyberGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (exposureIndex == 0) "EV: 0" else "${if (exposureIndex > 0) "+" else ""}$exposureIndex EV", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }

                // Zoom toggle
                Row(
                    modifier = Modifier.clickable {
                        zoomRatio = when (zoomRatio) {
                            1.0f -> 2.0f
                            2.0f -> 4.0f
                            else -> 1.0f
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom", tint = CyberGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${zoomRatio.toInt()}x", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Forensic Badges Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (securityStatus.isCompromised) {
                    ForensicBadge(text = "ALERT: ROOT/DEBUG", active = true, isAlert = true)
                }
                ForensicBadge(text = "PBKDF2-600K", active = true)
                ForensicBadge(text = "ZERO-GPS", active = true)
                ForensicBadge(text = "EPHEMERAL-SHARE", active = true)
                ForensicBadge(text = "TEE/STRONGBOX", active = true)
            }
        }

        // Live Countdown Overlay
        countdownRemaining?.let { count ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    color = CyberGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 90.sp
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
                .padding(top = 142.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(CyberBlack.copy(alpha = 0.92f))
                    .border(1.dp, if (isCapturing) CyberGreen else CyberGreenDark, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            color = CyberGreen,
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "STREAM-ENCRYPTING (NO-BITMAP)...",
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
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Bottom Controls HUD
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vault Button
                Button(
                    onClick = { showVaultDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSurface.copy(alpha = 0.88f)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberGreen.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Vault",
                        tint = CyberGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "VAULT [${currentProfile.name}] ($vaultCount)",
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

                // Panic Button (Crypto-Shredder)
                IconButton(
                    onClick = { showPanicConfirm = true },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CyberRed.copy(alpha = 0.2f))
                        .border(1.dp, CyberRed.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = "Panic Wipe",
                        tint = CyberRed,
                        modifier = Modifier.size(22.dp)
                    )
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
                title = {
                    Text(
                        "DISGUISE / CAMOUFLAGE",
                        color = CyberGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                },
                text = {
                    Column {
                        Text(
                            "Select launcher identity on home screen (Tella-style):",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        for (mode in CamouflageManager.CamouflageMode.values()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        CamouflageManager.setCamouflage(context, mode)
                                        currentCamouflage = mode
                                        showCamouflageDialog = false
                                        lastStatusMessage = "CAMOUFLAGE CHANGED TO: ${mode.displayName.uppercase()}"
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mode.displayName,
                                    color = if (mode == currentCamouflage) CyberGreen else Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                                if (mode == currentCamouflage) {
                                    Icon(Icons.Default.Check, contentDescription = "Active", tint = CyberGreen, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCamouflageDialog = false }) {
                        Text("CLOSE", color = CyberMuted, fontFamily = FontFamily.Monospace)
                    }
                },
                containerColor = CyberSurface
            )
        }

        // Emergency Crypto-Shred Confirmation
        if (showPanicConfirm) {
            AlertDialog(
                onDismissRequest = { showPanicConfirm = false },
                title = {
                    Text(
                        "HARDWARE CRYPTO-SHRED",
                        color = CyberRed,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "Immediately deletes the AES-256 master key inside AndroidKeyStore (TEE hardware) and shreds both MAIN and DECOY vaults. Wear-leveling proof. Irreversible.",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val shredded = GhostCryptoVault.cryptoShred(context)
                            vaultCount = 0
                            showPanicConfirm = false
                            lastStatusMessage = "TEE KEY DESTROYED // $shredded FILES KILLED"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberRed)
                    ) {
                        Text("DESTROY KEY NOW", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPanicConfirm = false }) {
                        Text("CANCEL", color = CyberMuted)
                    }
                },
                containerColor = CyberSurface
            )
        }
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
        // Outer glowing ring
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = if (isCapturing) CyberRed else activeColor.copy(alpha = pulseAlpha),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        // Inner trigger
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
private fun ForensicBadge(text: String, active: Boolean, isAlert: Boolean = false) {
    val borderColor = when {
        isAlert -> CyberRed
        active -> CyberGreen.copy(alpha = 0.6f)
        else -> CyberMuted.copy(alpha = 0.3f)
    }
    val textColor = when {
        isAlert -> CyberRed
        active -> CyberGreen
        else -> CyberMuted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isAlert) CyberRed.copy(alpha = 0.15f) else CyberBlack.copy(alpha = 0.8f))
            .border(
                1.dp,
                borderColor,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
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

        // Center crosshair ticks
        drawLine(cornerColor, Offset(cx - reticleRadius, cy), Offset(cx - reticleRadius + tickLen, cy), strokeW)
        drawLine(cornerColor, Offset(cx + reticleRadius - tickLen, cy), Offset(cx + reticleRadius, cy), strokeW)
        drawLine(cornerColor, Offset(cx, cy - reticleRadius), Offset(cx, cy - reticleRadius + tickLen), strokeW)
        drawLine(cornerColor, Offset(cx, cy + reticleRadius - tickLen), Offset(cx, cy + reticleRadius), strokeW)

        // Center dot
        drawCircle(color = CyberGreen.copy(alpha = 0.5f), radius = 2.5.dp.toPx(), center = Offset(cx, cy))
    }
}
