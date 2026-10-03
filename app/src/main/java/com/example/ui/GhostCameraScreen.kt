package com.example.ui

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private val CyberBlack = Color(0xFF070B0E)
private val CyberGreen = Color(0xFF00FF66)
private val CyberGreenDark = Color(0xFF008F39)
private val CyberRed = Color(0xFFFF1744)
private val CyberMuted = Color(0xFF6B7E8C)
private val CyberSurface = Color(0xFF111820)

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

    val securityStatus = remember { AntiForensicsGuard.assessDeviceSecurity(context) }
    var currentProfile by remember { mutableStateOf(GhostCryptoVault.activeProfile) }

    // Architecture v2.0: Process isolation toggle (:core offline vs :assist online)
    var isAssistModeActive by remember { mutableStateOf(false) }

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

    DisposableEffect(Unit) {
        onDispose {
            captureExecutor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack)
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
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Tactical Reticle in Center
        TacticalReticle(modifier = Modifier.fillMaxSize())

        // RED NEON PERIMETER BORDER when :assist process is active
        if (isAssistModeActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(4.dp, CyberRed.copy(alpha = redBorderAlpha))
            )
        }

        // Top Tactical HUD
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
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
                            text = if (isAssistModeActive) "GHOST CAMERA // ASSIST ONLINE" else "GHOST CAMERA 👻 v2.0",
                            color = if (isAssistModeActive) CyberRed else CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = if (isAssistModeActive) "PROCESS: :assist (NETWORK ACTIVE)" else "PROCESS: :core (AIR-GAPPED)",
                        color = if (isAssistModeActive) CyberRed.copy(alpha = 0.8f) else CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Assist Mode Toggle
                    IconButton(
                        onClick = { isAssistModeActive = !isAssistModeActive },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (isAssistModeActive) Icons.Default.Warning else Icons.Default.Shield,
                            contentDescription = "Toggle Assist Mode",
                            tint = if (isAssistModeActive) CyberRed else CyberMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    // Flash Mode Toggle
                    IconButton(
                        onClick = {
                            flashMode = when (flashMode) {
                                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                        },
                        modifier = Modifier.size(34.dp)
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

                    Spacer(modifier = Modifier.width(2.dp))

                    // Lens Flip (Front / Back)
                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier.size(34.dp)
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

            // Forensic Badges Bar (Architecture v2.1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (securityStatus.isCompromised) {
                    ForensicBadge(text = "ALERT: ROOT/DEBUG", active = true, isAlert = true)
                }
                ForensicBadge(text = "BYTE-STRIP", active = true)
                ForensicBadge(text = "PBKDF2-SHA256", active = true)
                ForensicBadge(text = "TEE/STRONGBOX", active = true)
                ForensicBadge(text = "NO-MEDIASTORE", active = true)
            }
        }

        // Capture in progress / Status notification banner
        AnimatedVisibility(
            visible = isCapturing || lastStatusMessage != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 114.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(CyberBlack.copy(alpha = 0.92f))
                    .border(1.dp, if (isCapturing) CyberGreen else CyberGreenDark, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            color = CyberGreen,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "STREAM-STRIPPING -> CIPHEROUTPUTSTREAM...",
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
                            fontSize = 11.sp
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Vault",
                        tint = CyberGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "VAULT [${currentProfile.name}] ($vaultCount)",
                        color = CyberGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Shutter Button
                ShutterButton(
                    isCapturing = isCapturing,
                    isAssistMode = isAssistModeActive,
                    onClick = {
                        val cap = imageCapture ?: return@ShutterButton
                        if (isCapturing) return@ShutterButton
                        isCapturing = true
                        scope.launch {
                            try {
                                val manager = CaptureManager(
                                    context = context,
                                    imageCapture = cap,
                                    captureExecutor = captureExecutor
                                )
                                val result = manager.takeSecurePhoto()
                                vaultCount = GhostCryptoVault.listVaultFiles(context).size
                                val kb = result.encryptedSizeBytes / 1024
                                lastStatusMessage = "SAVED: ${kb}KB (STRIPPED ${result.strippedSegmentsCount} APPn/COM) IN ${result.durationMs}ms"
                            } catch (e: Exception) {
                                lastStatusMessage = "ERR: ${e.localizedMessage ?: "Capture failed"}"
                            } finally {
                                isCapturing = false
                            }
                        }
                    }
                )

                // Panic Button (Crypto-Shredder)
                IconButton(
                    onClick = { showPanicConfirm = true },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CyberRed.copy(alpha = 0.2f))
                        .border(1.dp, CyberRed.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = "Panic Wipe",
                        tint = CyberRed,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Vault Viewer Dialog (Tella Decoy PIN enabled)
        if (showVaultDialog) {
            GhostVaultDialog(
                onDismiss = { showVaultDialog = false },
                onVaultUpdated = {
                    vaultCount = GhostCryptoVault.listVaultFiles(context).size
                }
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
                        "Immediately deletes the AES-256 master key inside AndroidKeyStore (TEE hardware). All files on flash storage instantly turn into random unrecoverable noise. Safe against wear leveling.",
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
            .size(76.dp)
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
                .size(60.dp)
                .clip(CircleShape)
                .background(if (isCapturing) CyberRed.copy(alpha = 0.4f) else activeColor.copy(alpha = 0.25f))
                .border(2.dp, if (isCapturing) CyberRed else activeColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Capture",
                tint = if (isCapturing) CyberRed else activeColor,
                modifier = Modifier.size(28.dp)
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
