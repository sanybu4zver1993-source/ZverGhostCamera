package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.crypto.GhostCryptoVault
import com.example.security.CamouflageManager
import com.example.security.ZverPrefs
import com.example.ui.DecoyCalculatorScreen
import com.example.ui.GhostCameraScreen
import com.example.ui.JellyTankScreen
import com.example.ui.theme.MyApplicationTheme
import java.util.function.Consumer

private val CyberBlack = Color(0xFF090D10)
private val CyberGreen = Color(0xFF00FF66)
private val CyberRed = Color(0xFFFF1744)
private val CyberMuted = Color(0xFF8A9BA8)

class MainActivity : ComponentActivity() {
    companion object {
        var onVolumeShutterTrigger: (() -> Unit)? = null
        var onVideoToggleTrigger: (() -> Unit)? = null
        var onTorchToggleTrigger: (() -> Unit)? = null
        var onBlackoutToggleTrigger: (() -> Unit)? = null
        var onLockGateTrigger: (() -> Unit)? = null
    }

    private val isScreenBeingRecorded = mutableStateOf(false)
    private var screenRecCallback: Consumer<Int>? = null

    private val externalCommandReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.camera.TRIGGER_CAPTURE" -> onVolumeShutterTrigger?.invoke()
                "com.example.camera.TOGGLE_VIDEO" -> onVideoToggleTrigger?.invoke()
                "com.example.camera.TOGGLE_TORCH" -> onTorchToggleTrigger?.invoke()
                "com.example.camera.TOGGLE_BLACKOUT" -> onBlackoutToggleTrigger?.invoke()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val callback = onVolumeShutterTrigger
            if (callback != null) {
                callback.invoke()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                val cb = Consumer<Int> { state ->
                    isScreenBeingRecorded.value =
                        (state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) &&
                            ZverPrefs.isScreenRecBlockEnabled(this)
                }
                screenRecCallback = cb
                val initialState = windowManager.addScreenRecordingCallback(mainExecutor, cb)
                isScreenBeingRecorded.value =
                    (initialState == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) &&
                        ZverPrefs.isScreenRecBlockEnabled(this)
            } catch (_: Exception) {}
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                screenRecCallback?.let { windowManager.removeScreenRecordingCallback(it) }
            } catch (_: Exception) {}
            screenRecCallback = null
            isScreenBeingRecorded.value = false
        }
        if (ZverPrefs.isAutoLockOnBackground(this)) {
            GhostCryptoVault.lockSession()
        }
    }

    override fun onResume() {
        super.onResume()
        GhostCryptoVault.shredEphemeralShareFiles(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCustomUri(intent)
    }

    private fun handleCustomUri(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "zver") {
            when (uri.host) {
                "capture" -> onVolumeShutterTrigger?.invoke()
                "video" -> onVideoToggleTrigger?.invoke()
                "torch" -> onTorchToggleTrigger?.invoke()
                "stealth" -> onBlackoutToggleTrigger?.invoke()
                "lock" -> {
                    GhostCryptoVault.lockSession()
                    onLockGateTrigger?.invoke()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(externalCommandReceiver)
        } catch (_: Exception) {}
        if (isFinishing) {
            GhostCryptoVault.clearRamShots()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enforce Dead-Man Switch before presenting any UI
        GhostCryptoVault.checkDeadManSwitch(this)

        val filter = android.content.IntentFilter().apply {
            addAction("com.example.camera.TRIGGER_CAPTURE")
            addAction("com.example.camera.TOGGLE_VIDEO")
            addAction("com.example.camera.TOGGLE_TORCH")
            addAction("com.example.camera.TOGGLE_BLACKOUT")
        }
        ContextCompat.registerReceiver(
            this,
            externalCommandReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        handleCustomUri(intent)
        enableEdgeToEdge()

        setContent {
            val activeCamo = remember { CamouflageManager.getCurrentCamouflage(this) }
            var isGateUnlocked by remember {
                mutableStateOf(
                    activeCamo != CamouflageManager.CamouflageMode.CALCULATOR &&
                        activeCamo != CamouflageManager.CamouflageMode.JELLY_TRACKER
                )
            }
            val recordingBlocked by remember { isScreenBeingRecorded }

            DisposableEffect(Unit) {
                onLockGateTrigger = {
                    val currentMode = CamouflageManager.getCurrentCamouflage(this@MainActivity)
                    if (currentMode == CamouflageManager.CamouflageMode.CALCULATOR ||
                        currentMode == CamouflageManager.CamouflageMode.JELLY_TRACKER
                    ) {
                        isGateUnlocked = false
                    } else {
                        onBlackoutToggleTrigger?.invoke()
                    }
                }
                onDispose { onLockGateTrigger = null }
            }

            MyApplicationTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CyberBlack
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (!isGateUnlocked) {
                            when (CamouflageManager.getCurrentCamouflage(this@MainActivity)) {
                                CamouflageManager.CamouflageMode.CALCULATOR -> {
                                    DecoyCalculatorScreen(
                                        onUnlocked = { isGateUnlocked = true }
                                    )
                                }
                                CamouflageManager.CamouflageMode.JELLY_TRACKER -> {
                                    JellyTankScreen(
                                        isCamouflageGate = true,
                                        onUnlockToCamera = { isGateUnlocked = true }
                                    )
                                }
                                else -> {
                                    CameraPermissionGate(
                                        onQuickExitToCamouflage = {
                                            isGateUnlocked = false
                                        }
                                    )
                                }
                            }
                        } else {
                            CameraPermissionGate(
                                onQuickExitToCamouflage = {
                                    val mode = CamouflageManager.getCurrentCamouflage(this@MainActivity)
                                    if (mode == CamouflageManager.CamouflageMode.CALCULATOR ||
                                        mode == CamouflageManager.CamouflageMode.JELLY_TRACKER
                                    ) {
                                        isGateUnlocked = false
                                    }
                                }
                            )
                        }

                        if (recordingBlocked) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                                    Icon(Icons.Default.VisibilityOff, contentDescription = "Blocked", tint = CyberRed, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "ЗАПИСЬ ЭКРАНА ЗАБЛОКИРОВАНА",
                                        color = CyberRed,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Android 15 ScreenRecording Guard",
                                        color = CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPermissionGate(
    onQuickExitToCamouflage: () -> Unit = {}
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    if (hasPermission) {
        GhostCameraScreen(onQuickExitToCamouflage = onQuickExitToCamouflage)
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBlack)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 500.dp)
                    .background(Color(0xFF131A21), RoundedCornerShape(12.dp))
                    .border(1.dp, CyberGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Security",
                    tint = CyberGreen,
                    modifier = Modifier.size(54.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "ZVER CAMERA 🐾",
                    color = CyberGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "АВТОНОМНЫЙ СХРОН // 0% СЕТИ // БЕЗ EXIF",
                    color = CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Камере нужен прямой доступ к сенсору устройства. Интернет и сетевые библиотеки физически вырезаны из манифеста APK — утечка невозможна.",
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreen),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Активация",
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "АКТИВИРОВАТЬ КАМЕРУ",
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
