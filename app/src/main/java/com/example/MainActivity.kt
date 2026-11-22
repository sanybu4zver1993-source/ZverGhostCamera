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
import androidx.lifecycle.lifecycleScope
import com.example.assist.ByokManager
import com.example.crypto.GhostCryptoVault
import com.example.security.CamouflageManager
import com.example.security.SentinelEngine
import com.example.security.ZverPrefs
import com.example.ui.DecoyCalculatorScreen
import com.example.ui.GhostCameraScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
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
        var onScreenCapturePolicyChanged: (() -> Unit)? = null
        var onVaultSecureOverrideChanged: ((Boolean) -> Unit)? = null
    }

    private val isScreenBeingRecorded = mutableStateOf(false)
    private var screenRecCallback: Consumer<Int>? = null

    private var forceVaultSecureOverride: Boolean = false

    private fun applyScreenCapturePolicy() {
        try {
            val blockEnabled = forceVaultSecureOverride || ZverPrefs.isScreenRecBlockEnabled(this)
            if (blockEnabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                isScreenBeingRecorded.value = false
            }
        } catch (_: Throwable) {}
    }

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
            when (ZverPrefs.getVolumeKeyAction(this)) {
                ZverPrefs.VolumeKeyAction.SHUTTER_OR_VIDEO -> {
                    onVolumeShutterTrigger?.let {
                        it.invoke()
                        return true
                    }
                }
                ZverPrefs.VolumeKeyAction.STEALTH_BLACKOUT -> {
                    onBlackoutToggleTrigger?.let {
                        it.invoke()
                        return true
                    }
                }
                ZverPrefs.VolumeKeyAction.PANIC_LOCK_GATE -> {
                    GhostCryptoVault.lockSession()
                    ByokManager.clearSessionMemory()
                    SentinelEngine.clearSession()
                    com.example.security.SecureClipboard.clearIfOwned(this)
                    onLockGateTrigger?.let {
                        it.invoke()
                        return true
                    }
                }
                ZverPrefs.VolumeKeyAction.TORCH_TOGGLE -> {
                    onTorchToggleTrigger?.let {
                        it.invoke()
                        return true
                    }
                }
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
                            (forceVaultSecureOverride || ZverPrefs.isScreenRecBlockEnabled(this))
                }
                screenRecCallback = cb
                val initialState = windowManager.addScreenRecordingCallback(mainExecutor, cb)
                isScreenBeingRecorded.value =
                    (initialState == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) &&
                        (forceVaultSecureOverride || ZverPrefs.isScreenRecBlockEnabled(this))
            } catch (_: Throwable) {
                screenRecCallback = null
                isScreenBeingRecorded.value = false
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                screenRecCallback?.let { windowManager.removeScreenRecordingCallback(it) }
            } catch (_: Throwable) {}
            screenRecCallback = null
            isScreenBeingRecorded.value = false
        }
        try {
            if (ZverPrefs.isAutoLockOnBackground(this)) {
                GhostCryptoVault.lockSession()
                ByokManager.clearSessionMemory()
                SentinelEngine.clearSession()
                com.example.security.SecureClipboard.clearIfOwned(this)
            }
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        applyScreenCapturePolicy()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            GhostCryptoVault.shredEphemeralShareFiles(this@MainActivity)
            GhostCryptoVault.shredOrphanedVideoTemps(this@MainActivity, minAgeMs = 15_000L)
            GhostCryptoVault.shredOrphanedVaultTempFiles(this@MainActivity, minAgeMs = 15_000L)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCustomUri(intent)
    }

    private fun handleCustomUri(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme?.lowercase() != "zver") return
        val route = uri.host?.lowercase()?.trim() ?: return
        when (route) {
            "capture" -> onVolumeShutterTrigger?.invoke()
            "video" -> onVideoToggleTrigger?.invoke()
            "torch" -> onTorchToggleTrigger?.invoke()
            "stealth" -> onBlackoutToggleTrigger?.invoke()
            "lock" -> {
                GhostCryptoVault.lockSession()
                ByokManager.clearSessionMemory()
                SentinelEngine.clearSession()
                com.example.security.SecureClipboard.clearIfOwned(this)
                onLockGateTrigger?.invoke()
            }
            else -> {
                // Unknown zver:// route ignored cleanly (no arbitrary command execution)
            }
        }
    }

    private var receiverRegistered = false

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            try {
                unregisterReceiver(externalCommandReceiver)
            } catch (_: Throwable) {}
            receiverRegistered = false
        }
        if (isFinishing) {
            try {
                GhostCryptoVault.clearRamShots()
                ByokManager.clearSessionMemory()
                SentinelEngine.clearSession()
                com.example.security.SecureClipboard.clearIfOwned(this)
            } catch (_: Throwable) {}
        }
    }

    private fun installOfflineCrashBreadcrumbRecorder() {
        val appCtx = applicationContext ?: return
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev?.javaClass?.name?.startsWith("com.example.") == true) return
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val topFrames = throwable.stackTrace.take(3).joinToString(" <- ") {
                    "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                }
                val summary = "[${thread.name}] ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()} @ $topFrames"
                ZverPrefs.recordLastCrashBreadcrumb(appCtx, summary)
            } catch (_: Throwable) {}
            prev?.uncaughtException(thread, throwable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installOfflineCrashBreadcrumbRecorder()
        super.onCreate(savedInstanceState)

        // Prevent Android Recents task snapshots in /data/system_ce/0/snapshots/ without blocking user screenshots
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                setRecentsScreenshotEnabled(false)
            } catch (_: Throwable) {}
        }

        // Enforce Dead-Man Switch before presenting any UI
        try {
            GhostCryptoVault.checkDeadManSwitch(this)
        } catch (_: Throwable) {}

        val filter = android.content.IntentFilter().apply {
            addAction("com.example.camera.TRIGGER_CAPTURE")
            addAction("com.example.camera.TOGGLE_VIDEO")
            addAction("com.example.camera.TOGGLE_TORCH")
            addAction("com.example.camera.TOGGLE_BLACKOUT")
        }
        try {
            ContextCompat.registerReceiver(
                this,
                externalCommandReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        } catch (_: Throwable) {
            try {
                ContextCompat.registerReceiver(
                    this,
                    externalCommandReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            } catch (_: Throwable) {}
        }

        applyScreenCapturePolicy()
        onScreenCapturePolicyChanged = {
            runOnUiThread { applyScreenCapturePolicy() }
        }
        onVaultSecureOverrideChanged = { active ->
            runOnUiThread {
                forceVaultSecureOverride = active
                applyScreenCapturePolicy()
            }
        }
        try {
            handleCustomUri(intent)
        } catch (_: Throwable) {}
        try {
            enableEdgeToEdge()
        } catch (_: Throwable) {}

        setContent {
            val activeCamo = remember {
                try {
                    CamouflageManager.getCurrentCamouflage(this)
                } catch (_: Throwable) {
                    CamouflageManager.CamouflageMode.ZVER
                }
            }
            var isGateUnlocked by remember {
                mutableStateOf(activeCamo != CamouflageManager.CamouflageMode.CALCULATOR)
            }
            val recordingBlocked by remember { isScreenBeingRecorded }

            DisposableEffect(Unit) {
                onLockGateTrigger = {
                    val currentMode = CamouflageManager.getCurrentCamouflage(this@MainActivity)
                    if (currentMode == CamouflageManager.CamouflageMode.CALCULATOR) {
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
                                    if (mode == CamouflageManager.CamouflageMode.CALCULATOR) {
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
                    text = "ОФФЛАЙН-СХРОН // OPT-IN BYOK // БЕЗ EXIF",
                    color = CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Камере нужен прямой доступ к сенсору устройства. Снимки и сейф шифруются локально без фоновой телеметрии. Сеть (INTERNET) используется ТОЛЬКО по твоему запросу при подключении твоего BYOK API-ключа.",
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
