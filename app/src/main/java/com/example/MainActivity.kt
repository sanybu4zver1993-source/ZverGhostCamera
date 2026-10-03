package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.example.ui.GhostCameraScreen
import com.example.ui.theme.MyApplicationTheme

private val CyberBlack = Color(0xFF090D10)
private val CyberGreen = Color(0xFF00FF66)
private val CyberMuted = Color(0xFF8A9BA8)
private val CyberBorder = Color(0xFF223240)

class MainActivity : ComponentActivity() {
    companion object {
        var onVolumeShutterTrigger: (() -> Unit)? = null
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            val callback = onVolumeShutterTrigger
            if (callback != null) {
                callback.invoke()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // Immediately shred any ephemeral shared files from cacheDir
        com.example.crypto.GhostCryptoVault.shredEphemeralShareFiles(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots, screen recording, and task switcher thumbnail leaks
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        enableEdgeToEdge()

        setContent {
            MyApplicationTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CyberBlack
                ) {
                    CameraPermissionGate()
                }
            }
        }
    }
}

@Composable
fun CameraPermissionGate() {
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
        GhostCameraScreen()
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
                    text = "GHOST CAMERA 👻",
                    color = CyberGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "AIR-GAPPED ZERO-EXIF VAULT",
                    color = CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Ghost Camera requires hardware Camera access to capture sensor frames. Network access is physically removed from the APK manifest.",
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
                        contentDescription = "Grant",
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "INITIALIZE CAMERA",
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
