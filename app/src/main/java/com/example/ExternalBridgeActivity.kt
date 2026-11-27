package com.example

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.ExifStripper
import com.example.camera.Mp4Sanitizer
import com.example.crypto.GhostCryptoVault
import com.example.security.ZverPrefs
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val BridgeBg = Color(0xFF070B0E)
private val BridgeSurface = Color(0xFF111820)
private val BridgeBorder = Color(0xFF223240)
private val BridgeGreen = Color(0xFF00FF66)
private val BridgeCyan = Color(0xFF00E5FF)
private val BridgeRed = Color(0xFFFF1744)
private val BridgeAmber = Color(0xFFFFC107)
private val BridgeMuted = Color(0xFF8FA3B0)

enum class BridgeLaunchMode {
    CAPTURE_PHOTO,
    CAPTURE_VIDEO,
    PICK_FROM_VAULT
}

/**
 * ExternalBridgeActivity:
 * Handles external camera/picker intents (IMAGE_CAPTURE, VIDEO_CAPTURE, GET_CONTENT, PICK)
 * from messengers, browsers, and AI chats so Саня can shoot 0-EXIF media cleanly or pick one
 * item from the encrypted Vault without revealing the rest of the Vault or needing storage permissions.
 */
class ExternalBridgeActivity : ComponentActivity() {

    private var wasVaultUnlockedOnEntry = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (ZverPrefs.isScreenRecBlockEnabled(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        wasVaultUnlockedOnEntry = GhostCryptoVault.hasUnlockedPinSession()

        val initialMode = resolveBridgeMode(intent)
        val extraOutputUri = IntentCompat.getParcelableExtra(
            intent,
            MediaStore.EXTRA_OUTPUT,
            Uri::class.java
        )
        val (allowImages, allowVideos) = resolveAllowedMediaTypes(intent)

        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = BridgeBg) {
                    ExternalBridgeScreen(
                        initialMode = initialMode,
                        extraOutputUri = extraOutputUri,
                        allowImages = allowImages,
                        allowVideos = allowVideos,
                        onDeliverResult = { resultIntent ->
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        },
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations && !wasVaultUnlockedOnEntry) {
            GhostCryptoVault.lockSession()
        }
    }

    companion object {
        fun resolveBridgeMode(intent: Intent?): BridgeLaunchMode {
            return when (intent?.action) {
                MediaStore.ACTION_VIDEO_CAPTURE -> BridgeLaunchMode.CAPTURE_VIDEO
                Intent.ACTION_GET_CONTENT, Intent.ACTION_PICK -> BridgeLaunchMode.PICK_FROM_VAULT
                else -> BridgeLaunchMode.CAPTURE_PHOTO
            }
        }

        fun resolveAllowedMediaTypes(intent: Intent?): Pair<Boolean, Boolean> {
            val type = intent?.type?.lowercase(Locale.ROOT).orEmpty()
            val extraMimes = intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                ?.map { it.lowercase(Locale.ROOT) }
                .orEmpty()
            val allTypes = buildList {
                if (type.isNotBlank()) add(type)
                addAll(extraMimes)
            }
            if (allTypes.isEmpty() || allTypes.any { it == "*/*" }) {
                return true to true
            }
            val images = allTypes.any { it.startsWith("image/") }
            val videos = allTypes.any { it.startsWith("video/") }
            return if (!images && !videos) true to true else images to videos
        }
    }
}

@Composable
private fun ExternalBridgeScreen(
    initialMode: BridgeLaunchMode,
    extraOutputUri: Uri?,
    allowImages: Boolean,
    allowVideos: Boolean,
    onDeliverResult: (Intent) -> Unit,
    onCancel: () -> Unit
) {
    var mode by remember { mutableStateOf(initialMode) }

    BackHandler {
        if (mode == BridgeLaunchMode.PICK_FROM_VAULT && initialMode != BridgeLaunchMode.PICK_FROM_VAULT) {
            mode = initialMode
        } else {
            onCancel()
        }
    }

    when (mode) {
        BridgeLaunchMode.CAPTURE_PHOTO,
        BridgeLaunchMode.CAPTURE_VIDEO -> {
            BridgeCameraPane(
                isVideoMode = (mode == BridgeLaunchMode.CAPTURE_VIDEO),
                extraOutputUri = extraOutputUri,
                allowSwitchToVaultPicker = (extraOutputUri == null),
                onSwitchToVaultPicker = { mode = BridgeLaunchMode.PICK_FROM_VAULT },
                onDeliverResult = onDeliverResult,
                onCancel = onCancel
            )
        }
        BridgeLaunchMode.PICK_FROM_VAULT -> {
            BridgeVaultPickerPane(
                allowImages = allowImages,
                allowVideos = allowVideos,
                onBackToCamera = if (initialMode != BridgeLaunchMode.PICK_FROM_VAULT) {
                    { mode = initialMode }
                } else null,
                onDeliverResult = onDeliverResult,
                onCancel = onCancel
            )
        }
    }
}

@Composable
private fun BridgeCameraPane(
    isVideoMode: Boolean,
    extraOutputUri: Uri?,
    allowSwitchToVaultPicker: Boolean,
    onSwitchToVaultPicker: () -> Unit,
    onDeliverResult: (Intent) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else if (isVideoMode && !ZverPrefs.isMuteVideoAudio(context) && !hasAudioPermission) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var saveCopyToVault by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var statusText by remember {
        mutableStateOf(
            if (isVideoMode) "МОСТ ВИДЕО • 0 МЕТАДАННЫХ" else "МОСТ ФОТО • 0 EXIF"
        )
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var activePreviewRef by remember { mutableStateOf<Preview?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try { activeRecording?.stop() } catch (_: Exception) {}
            try { activePreviewRef?.setSurfaceProvider(null) } catch (_: Exception) {}
            try { cameraProviderRef?.unbindAll() } catch (_: Exception) {}
            captureExecutor.shutdown()
        }
    }

    LaunchedEffect(hasCameraPermission, lensFacing, isVideoMode, previewView) {
        if (!hasCameraPermission || previewView == null) return@LaunchedEffect
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        cameraProviderRef = provider
        try { activePreviewRef?.setSurfaceProvider(null) } catch (_: Exception) {}

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView!!.surfaceProvider)
        }
        activePreviewRef = preview
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            provider.unbindAll()
            if (isVideoMode) {
                imageCapture = null
                val quality = QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                )
                val recorder = Recorder.Builder().setQualitySelector(quality).build()
                val vc = VideoCapture.withOutput(recorder)
                provider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
                videoCapture = vc
            } else {
                videoCapture = null
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, ic)
                imageCapture = ic
            }
        } catch (e: Exception) {
            statusText = "ОШИБКА КАМЕРЫ HAL: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    fun captureAndDeliverPhoto() {
        val ic = imageCapture ?: return
        if (isBusy) return
        isBusy = true
        statusText = "⏳ СНЯТИЕ EXIF + ОЧИСТКА..."
        scope.launch {
            try {
                val (rawBytes, rotationDeg) = suspendCancellableCoroutine<Pair<ByteArray, Int>> { cont ->
                    ic.takePicture(
                        captureExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                try {
                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    val rot = image.imageInfo.rotationDegrees
                                    image.close()
                                    if (cont.isActive) cont.resume(bytes to rot)
                                } catch (t: Throwable) {
                                    image.close()
                                    if (cont.isActive) cont.resumeWithException(t)
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                if (cont.isActive) cont.resumeWithException(exception)
                            }
                        }
                    )
                }

                val cleanBytes = withContext(Dispatchers.Default) {
                    val bmp = decodeBoundedBitmap(rawBytes, rotationDeg, maxPixels = 8_000_000L)
                        ?: throw IllegalStateException("Не удалось декодировать кадр")
                    Arrays.fill(rawBytes, 0.toByte())
                    ExifStripper.processBitmapClean(
                        sourceBitmap = bmp,
                        recycleSource = true,
                        preset = ExifStripper.CompressionPreset.HIGH_QUALITY,
                        antiPrnu = ZverPrefs.isAntiPrnuEnabled(context)
                    ).cleanBytes
                }

                val resultIntent = withContext(Dispatchers.IO) {
                    if (saveCopyToVault) {
                        try {
                            if (ZverPrefs.isRamOnlyMode(context)) {
                                GhostCryptoVault.addRamShot(cleanBytes.clone(), isVideo = false)
                            } else {
                                GhostCryptoVault.encryptBytesToVault(
                                    context,
                                    cleanBytes,
                                    prefix = GhostCryptoVault.MEDIA_PHOTO
                                )
                            }
                        } catch (_: Exception) {}
                    }

                    deliverFileBytes(
                        context = context,
                        bytes = cleanBytes,
                        mimeType = "image/jpeg",
                        ext = "jpg",
                        extraOutputUri = extraOutputUri
                    )
                }
                Arrays.fill(cleanBytes, 0.toByte())
                onDeliverResult(resultIntent)
            } catch (e: Exception) {
                statusText = "ОШИБКА: ${e.localizedMessage ?: "Сбой захвата"}"
                isBusy = false
            }
        }
    }

    fun toggleVideoCapture() {
        val vc = videoCapture ?: return
        if (isBusy) return
        if (isRecording) {
            try {
                isBusy = true
                statusText = "⏳ ОЧИСТКА MP4 (0 МЕТАДАННЫХ)..."
                activeRecording?.stop()
                activeRecording = null
                isRecording = false
            } catch (e: Exception) {
                isBusy = false
                statusText = "СБОЙ ОСТАНОВКИ: ${e.message}"
            }
            return
        }

        val stageDir = GhostCryptoVault.getVideoStageDir(context)
        val rawMp4 = File(stageDir, "bridge_raw_${System.currentTimeMillis()}.mp4")
        val cleanMp4 = File(stageDir, "bridge_clean_${System.currentTimeMillis()}.mp4")
        try {
            val pending = vc.output.prepareRecording(context, FileOutputOptions.Builder(rawMp4).build())
            if (!ZverPrefs.isMuteVideoAudio(context) && hasAudioPermission) {
                pending.withAudioEnabled()
            }
            activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        statusText = "🔴 ЗАПИСЬ ДЛЯ ОТПРАВКИ..."
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        activeRecording = null
                        if (!event.hasError() && rawMp4.exists() && rawMp4.length() > 0L) {
                            scope.launch {
                                try {
                                    val resultIntent = withContext(Dispatchers.IO) {
                                        val okSan = Mp4Sanitizer.sanitizeMp4File(rawMp4, cleanMp4)
                                        val finalFile = if (okSan && cleanMp4.exists()) cleanMp4 else rawMp4
                                        if (saveCopyToVault) {
                                            try {
                                                if (ZverPrefs.isRamOnlyMode(context)) {
                                                    GhostCryptoVault.addRamShot(finalFile.readBytes(), isVideo = true)
                                                } else {
                                                    val copyTmp = File(stageDir, "vault_dup_${System.currentTimeMillis()}.mp4")
                                                    finalFile.copyTo(copyTmp, overwrite = true)
                                                    GhostCryptoVault.encryptFileToVault(
                                                        context,
                                                        copyTmp,
                                                        prefix = GhostCryptoVault.MEDIA_VIDEO
                                                    )
                                                }
                                            } catch (_: Exception) {}
                                        }
                                        val delivered = deliverVideoFile(
                                            context = context,
                                            videoFile = finalFile,
                                            extraOutputUri = extraOutputUri
                                        )
                                        GhostCryptoVault.shredFile(rawMp4)
                                        GhostCryptoVault.shredFile(cleanMp4)
                                        delivered
                                    }
                                    onDeliverResult(resultIntent)
                                } catch (e: Exception) {
                                    GhostCryptoVault.shredFile(rawMp4)
                                    GhostCryptoVault.shredFile(cleanMp4)
                                    statusText = "ОШИБКА ВИДЕО: ${e.message}"
                                    isBusy = false
                                }
                            }
                        } else {
                            GhostCryptoVault.shredFile(rawMp4)
                            GhostCryptoVault.shredFile(cleanMp4)
                            statusText = "СБОЙ ЗАПИСИ (КОД ${event.error})"
                            isBusy = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            GhostCryptoVault.shredFile(rawMp4)
            statusText = "ОШИБКА СТАРТА: ${e.message}"
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
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
                onRelease = { previewView = null },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Нет доступа к камере для моста", color = Color.White, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = BridgeGreen)
                ) {
                    Text("ВЫДАТЬ ДОСТУП", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Top Bridge Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(BridgeBg.copy(alpha = 0.78f))
                        .border(1.dp, BridgeGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(BridgeGreen))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        color = BridgeGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (allowSwitchToVaultPicker) {
                        Text(
                            text = "ИЗ СЕЙФА",
                            color = BridgeCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(BridgeSurface.copy(alpha = 0.9f))
                                .border(1.dp, BridgeCyan, RoundedCornerShape(10.dp))
                                .clickable(enabled = !isBusy && !isRecording) { onSwitchToVaultPicker() }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .testTag("bridge_switch_to_vault_btn")
                        )
                    }
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(BridgeBg.copy(alpha = 0.72f))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                    }
                }
            }
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(BridgeBg.copy(alpha = 0.85f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Дублировать копию в Сейф",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Switch(
                    checked = saveCopyToVault,
                    onCheckedChange = { saveCopyToVault = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = BridgeGreen, checkedTrackColor = BridgeGreen.copy(alpha = 0.35f))
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (!isRecording && !isBusy) {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(BridgeSurface)
                        .border(1.dp, BridgeBorder, CircleShape)
                ) {
                    Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Сменить камеру", tint = Color.White)
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(if (isVideoMode && isRecording) BridgeRed else BridgeGreen)
                        .border(3.dp, Color.White, CircleShape)
                        .clickable(enabled = !isBusy && hasCameraPermission) {
                            if (isVideoMode) toggleVideoCapture() else captureAndDeliverPhoto()
                        }
                        .testTag("bridge_shutter_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else if (isVideoMode && isRecording) {
                        Icon(Icons.Default.Stop, contentDescription = "Стоп", tint = Color.White, modifier = Modifier.size(34.dp))
                    } else if (isVideoMode) {
                        Icon(Icons.Default.Videocam, contentDescription = "Видео", tint = Color.Black, modifier = Modifier.size(32.dp))
                    } else {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Снять фото", tint = Color.Black, modifier = Modifier.size(32.dp))
                    }
                }

                Spacer(modifier = Modifier.size(48.dp))
            }
        }
    }
}

private data class BridgeVaultEntry(
    val diskFile: File? = null,
    val ramShotId: String? = null,
    val isVideo: Boolean,
    val title: String,
    val subtitle: String,
    val timestampMs: Long
)

@Composable
private fun BridgeVaultPickerPane(
    allowImages: Boolean,
    allowVideos: Boolean,
    onBackToCamera: (() -> Unit)?,
    onDeliverResult: (Intent) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isConfigLoaded by remember { mutableStateOf(false) }
    var isPinConfigured by remember { mutableStateOf(false) }
    var unlockedProfile by remember { mutableStateOf<GhostCryptoVault.VaultProfile?>(null) }
    var enteredPin by remember { mutableStateOf(charArrayOf()) }
    var isPinBusy by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var lockoutSec by remember { mutableIntStateOf(0) }
    val scrambleEnabled = remember { ZverPrefs.isPinScrambleEnabled(context) }

    var entries by remember { mutableStateOf<List<BridgeVaultEntry>>(emptyList()) }
    var isPickingItem by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val configured = withContext(Dispatchers.IO) { GhostCryptoVault.isConfigured(context) }
        isPinConfigured = configured
        if (!configured) {
            unlockedProfile = GhostCryptoVault.activeProfile
        }
        isConfigLoaded = true
    }

    LaunchedEffect(isConfigLoaded, unlockedProfile) {
        if (!isConfigLoaded) return@LaunchedEffect
        while (unlockedProfile == null && isPinConfigured) {
            val rem = ((ZverPrefs.getLockoutUntilMs(context) - System.currentTimeMillis()) / 1000L).toInt()
            lockoutSec = maxOf(0, rem)
            delay(500)
        }
    }

    LaunchedEffect(unlockedProfile) {
        val profile = unlockedProfile ?: return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            val disk = GhostCryptoVault.listVaultFiles(context, profile)
                .filter { f ->
                    val vid = GhostCryptoVault.isVideoFile(f)
                    (vid && allowVideos) || (!vid && allowImages)
                }
                .map { f ->
                    val vid = GhostCryptoVault.isVideoFile(f)
                    BridgeVaultEntry(
                        diskFile = f,
                        isVideo = vid,
                        title = (if (vid) "🎥 Видео " else "📷 Фото ") + fmt.format(Date(f.lastModified())),
                        subtitle = "${f.length() / 1024} КБ • ${f.name}",
                        timestampMs = f.lastModified()
                    )
                }
            val ram = GhostCryptoVault.listRamShots()
                .filter { s -> (s.isVideo && allowVideos) || (!s.isVideo && allowImages) }
                .map { s ->
                    BridgeVaultEntry(
                        ramShotId = s.id,
                        isVideo = s.isVideo,
                        title = (if (s.isVideo) "🎥 ОЗУ Видео " else "📷 ОЗУ Фото ") + fmt.format(Date(s.timestamp)),
                        subtitle = "${s.bytes.size / 1024} КБ • ОЗУ #${s.id}",
                        timestampMs = s.timestamp
                    )
                }
            (ram + disk).sortedByDescending { it.timestampMs }
        }
        entries = loaded
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BridgeBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = BridgeCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "МОСТ СЕЙФА (ВЫБОР 1 ФАЙЛА)",
                    color = BridgeCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBackToCamera != null) {
                    TextButton(onClick = onBackToCamera) {
                        Text("КАМЕРА", color = BridgeGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Отмена", tint = BridgeMuted)
                }
            }
        }

        statusBanner?.let {
            Text(
                text = it,
                color = BridgeAmber,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        if (!isConfigLoaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BridgeCyan)
            }
        } else if (unlockedProfile == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .background(BridgeSurface, RoundedCornerShape(14.dp))
                        .border(1.dp, BridgeCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "ПИН", tint = BridgeCyan, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("ПОДТВЕРДИ ПИН СЕЙФА", color = BridgeCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in 0 until 6) {
                            val filled = i < enteredPin.size
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (filled) BridgeCyan else Color.Transparent)
                                    .border(1.5.dp, if (filled) BridgeCyan else BridgeBorder, CircleShape)
                            )
                        }
                    }
                    if (lockoutSec > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("БЛОКИРОВКА: ${lockoutSec}с", color = BridgeAmber, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    } else if (pinError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(pinError!!, color = BridgeRed, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    if (isPinBusy) {
                        CircularProgressIndicator(color = BridgeCyan, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        BridgePinKeypad(
                            scramble = scrambleEnabled,
                            enabled = lockoutSec == 0,
                            onDigit = { ch ->
                                pinError = null
                                if (enteredPin.size < 6) enteredPin += ch
                            },
                            onClear = {
                                enteredPin.fill('0')
                                enteredPin = charArrayOf()
                            },
                            onConfirm = {
                                if (enteredPin.isNotEmpty() && lockoutSec == 0) {
                                    val pinClone = enteredPin.clone()
                                    enteredPin.fill('0')
                                    enteredPin = charArrayOf()
                                    isPinBusy = true
                                    scope.launch {
                                        val outcome = withContext(Dispatchers.Default) {
                                            GhostCryptoVault.unlockWithPinOutcome(context, pinClone)
                                        }
                                        isPinBusy = false
                                        when (outcome) {
                                            GhostCryptoVault.UnlockOutcome.MAIN -> {
                                                unlockedProfile = GhostCryptoVault.VaultProfile.MAIN
                                            }
                                            GhostCryptoVault.UnlockOutcome.DECOY,
                                            GhostCryptoVault.UnlockOutcome.DURESS_WIPED -> {
                                                unlockedProfile = GhostCryptoVault.VaultProfile.DECOY
                                            }
                                            GhostCryptoVault.UnlockOutcome.INVALID -> {
                                                val fails = ZverPrefs.incrementFailedPinAttempts(context)
                                                val wipeLimit = ZverPrefs.getMaxFailedPinWipe(context)
                                                if (wipeLimit > 0 && fails >= wipeLimit) {
                                                    GhostCryptoVault.cryptoShred(context)
                                                    ZverPrefs.resetFailedPinAttempts(context)
                                                    onCancel()
                                                } else {
                                                    pinError = "НЕВЕРНЫЙ ПИН"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        } else if (entries.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "В этом профиле Сейфа нет подходящих файлов",
                    color = BridgeMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        } else {
            Text(
                text = "Выбери 1 файл — передастся временная 0-EXIF копия без доступа к остальному Сейфу:",
                color = BridgeMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(entries, key = { it.diskFile?.absolutePath ?: it.ramShotId ?: it.title }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BridgeSurface)
                            .border(1.dp, BridgeBorder, RoundedCornerShape(10.dp))
                            .clickable(enabled = !isPickingItem) {
                                isPickingItem = true
                                statusBanner = "⏳ ПОДГОТОВКА ЧИСТОЙ КОПИИ..."
                                scope.launch {
                                    try {
                                        val resultIntent = withContext(Dispatchers.IO) {
                                            val shareFile = when {
                                                item.diskFile != null -> GhostCryptoVault.createEphemeralShareFile(context, item.diskFile)
                                                else -> {
                                                    val raw = GhostCryptoVault.getRamShotBytes(item.ramShotId!!)
                                                        ?: throw IllegalStateException("Кадр ОЗУ не найден")
                                                    val ext = GhostCryptoVault.detectMediaExtension(raw, item.isVideo)
                                                    val f = GhostCryptoVault.createEphemeralShareBytes(context, raw, ext)
                                                    Arrays.fill(raw, 0.toByte())
                                                    f
                                                }
                                            }
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                shareFile
                                            )
                                            val mime = when (shareFile.extension.lowercase(Locale.ROOT)) {
                                                "mp4" -> "video/mp4"
                                                "webp" -> "image/webp"
                                                "png" -> "image/png"
                                                else -> "image/jpeg"
                                            }
                                            Intent().apply {
                                                setDataAndType(uri, mime)
                                                clipData = ClipData.newUri(context.contentResolver, "ZverVaultShare", uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                        }
                                        onDeliverResult(resultIntent)
                                    } catch (e: Exception) {
                                        isPickingItem = false
                                        statusBanner = "ОШИБКА: ${e.message}"
                                    }
                                }
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BridgeEntryThumbnail(item = item)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.subtitle,
                                color = BridgeMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "ВЫБРАТЬ",
                            color = BridgeGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgeEntryThumbnail(item: BridgeVaultEntry) {
    val context = LocalContext.current
    var thumb by remember(item) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item) {
        if (item.isVideo) return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) {
            try {
                val bytes = when {
                    item.diskFile != null -> GhostCryptoVault.decryptToRam(context, item.diskFile)
                    item.ramShotId != null -> GhostCryptoVault.getRamShotBytes(item.ramShotId)
                    else -> null
                } ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 240 || bounds.outHeight / sample > 240) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                Arrays.fill(bytes, 0.toByte())
                decoded
            } catch (_: Exception) {
                null
            }
        }
        thumb = bmp
    }

    DisposableEffect(thumb) {
        val b = thumb
        onDispose {
            if (b != null && !b.isRecycled) b.recycle()
        }
    }

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BridgeBg)
            .border(1.dp, BridgeBorder, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (thumb != null && !thumb!!.isRecycled) {
            Image(
                bitmap = thumb!!.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = if (item.isVideo) Icons.Default.Videocam else Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = if (item.isVideo) BridgeAmber else BridgeCyan,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun BridgePinKeypad(
    scramble: Boolean,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit
) {
    val digits = remember(scramble) {
        val base = mutableListOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
        if (scramble) base.shuffle()
        base
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (col in 0 until 3) {
                    val ch = digits[row * 3 + col]
                    Button(
                        enabled = enabled,
                        onClick = { onDigit(ch) },
                        colors = ButtonDefaults.buttonColors(containerColor = BridgeBg),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(64.dp, 48.dp)
                    ) {
                        Text(ch.toString(), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = enabled,
                onClick = onClear,
                colors = ButtonDefaults.buttonColors(containerColor = BridgeRed.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(64.dp, 48.dp)
            ) {
                Text("C", color = BridgeRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            val zeroChar = digits[9]
            Button(
                enabled = enabled,
                onClick = { onDigit(zeroChar) },
                colors = ButtonDefaults.buttonColors(containerColor = BridgeBg),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(64.dp, 48.dp)
            ) {
                Text(zeroChar.toString(), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
            }
            Button(
                enabled = enabled,
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = BridgeCyan),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(64.dp, 48.dp)
            ) {
                Text("OK", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun deliverFileBytes(
    context: Context,
    bytes: ByteArray,
    mimeType: String,
    ext: String,
    extraOutputUri: Uri?
): Intent {
    if (extraOutputUri != null) {
        val outStream = context.contentResolver.openOutputStream(extraOutputUri, "wt")
            ?: context.contentResolver.openOutputStream(extraOutputUri, "w")
            ?: throw IllegalStateException("Не удалось записать в EXTRA_OUTPUT URI")
        outStream.use {
            it.write(bytes)
            it.flush()
        }
        return Intent().apply {
            data = extraOutputUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val shareFile = GhostCryptoVault.createEphemeralShareBytes(context, bytes, ext)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
    return Intent().apply {
        setDataAndType(uri, mimeType)
        clipData = ClipData.newUri(context.contentResolver, "ZverBridgeCapture", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun deliverVideoFile(
    context: Context,
    videoFile: File,
    extraOutputUri: Uri?
): Intent {
    if (extraOutputUri != null) {
        val outStream = context.contentResolver.openOutputStream(extraOutputUri, "wt")
            ?: context.contentResolver.openOutputStream(extraOutputUri, "w")
            ?: throw IllegalStateException("Не удалось записать видео в EXTRA_OUTPUT URI")
        outStream.use { out ->
            FileInputStream(videoFile).use { input ->
                input.copyTo(out)
            }
            out.flush()
        }
        return Intent().apply {
            data = extraOutputUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val shareDir = File(context.cacheDir, "ephemeral_shared").apply { if (!exists()) mkdirs() }
    val shareFile = File(shareDir, "ephemeral_vid_${System.currentTimeMillis()}.mp4")
    FileInputStream(videoFile).use { input ->
        shareFile.outputStream().use { out ->
            input.copyTo(out)
            out.fd.sync()
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
    return Intent().apply {
        setDataAndType(uri, "video/mp4")
        clipData = ClipData.newUri(context.contentResolver, "ZverBridgeVideo", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun decodeBoundedBitmap(bytes: ByteArray, rotationDegrees: Int, maxPixels: Long): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > maxPixels) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val base = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val normRot = ((rotationDegrees % 360) + 360) % 360
    if (normRot == 0) return base
    val matrix = Matrix().apply { postRotate(normRot.toFloat()) }
    val rotated = Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
    if (rotated !== base) base.recycle()
    return rotated
}
