package com.example.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import com.example.audio.OfflineVoiceChanger
import com.example.camera.ExifStripper
import com.example.camera.Mp4Sanitizer
import com.example.crypto.EncryptedMediaDataSource
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
import com.example.crypto.VaultIndexEngine
import com.example.crypto.ZverBackupEngine
import com.example.security.ZverPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val CyberBlack = Color(0xFF090D10)
private val CyberSurface = Color(0xFF131A21)
private val CyberBorder = Color(0xFF223240)
private val CyberGreen = Color(0xFF00FF66)
private val CyberCyan = Color(0xFF00E5FF)
private val CyberRed = Color(0xFFFF3366)
private val CyberAmber = Color(0xFFFFC107)
private val CyberMuted = Color(0xFF8A9BA8)

private enum class VaultTab {
    DISK,
    RAM,
    MONOLITH
}

@Composable
fun GhostVaultDialog(
    forceSetupFlow: Boolean = false,
    onDismiss: () -> Unit,
    onVaultUpdated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isConfigLoaded by remember { mutableStateOf(false) }
    var inSetupFlow by remember { mutableStateOf(forceSetupFlow) }
    var setupStep by remember { mutableStateOf(1) } // 1: Main, 2: Decoy, 3: Duress
    var setupMainPin by remember { mutableStateOf(charArrayOf()) }
    var setupDecoyPin by remember { mutableStateOf<CharArray?>(null) }

    var unlockedProfile by remember { mutableStateOf<GhostCryptoVault.VaultProfile?>(null) }
    var enteredPinChars by remember { mutableStateOf(charArrayOf()) }
    var pinErrorText by remember { mutableStateOf<String?>(null) }
    var lockoutRemainingSec by remember { mutableStateOf(0) }
    var isPinBusy by remember { mutableStateOf(false) }
    var isWiping by remember { mutableStateOf(false) }
    var pendingSingleShred by remember { mutableStateOf(false) }

    var activeTab by remember {
        mutableStateOf(
            if (ZverPrefs.isRamOnlyMode(context) && GhostCryptoVault.listRamShots().isNotEmpty()) {
                VaultTab.RAM
            } else {
                VaultTab.DISK
            }
        )
    }

    var vaultFiles by remember { mutableStateOf(emptyList<File>()) }
    var vaultIndexMap by remember { mutableStateOf(emptyMap<String, VaultIndexEngine.VaultItemMeta>()) }
    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf(VaultIndexEngine.VaultFilterType.ALL) }
    var ramShots by remember { mutableStateOf(GhostCryptoVault.listRamShots()) }
    var monoRecords by remember { mutableStateOf(emptyList<MonoVaultEngine.StoredRecord>()) }

    var selectedFile by remember { mutableStateOf<File?>(null) }
    var selectedRamId by remember { mutableStateOf<String?>(null) }
    var selectedMonoId by remember { mutableStateOf<String?>(null) }
    var selectedMonoSigValid by remember { mutableStateOf<Boolean?>(null) }
    var decryptedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var decryptedVideoFile by remember { mutableStateOf<File?>(null) }
    var streamingVideoDataSource by remember { mutableStateOf<EncryptedMediaDataSource?>(null) }
    var decryptedRawBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isDecrypting by remember { mutableStateOf(false) }

    // Offline Voice Changer states for decrypted MP4 playback & saving
    var selectedVoicePreset by remember { mutableStateOf(OfflineVoiceChanger.VoicePreset.CHIPMUNK) }
    var voicePreviewFile by remember { mutableStateOf<File?>(null) }
    var isTransformingVoice by remember { mutableStateOf(false) }
    var voiceTransformProgress by remember { mutableStateOf(0f) }

    var showWipeConfirm by remember { mutableStateOf(false) }
    var showPrivacyEditor by remember { mutableStateOf(false) }
    var showPhotoEditor by remember { mutableStateOf(false) }
    var showPcbLab by remember { mutableStateOf(false) }
    var showVikaAi by remember { mutableStateOf(false) }
    var showMetaTtlDialog by remember { mutableStateOf(false) }
    var zvbPasswordPromptMode by remember { mutableStateOf<String?>(null) } // "EXPORT" | "IMPORT" | null
    var pendingZvbPassword by remember { mutableStateOf(charArrayOf()) }
    var isZvbBusy by remember { mutableStateOf(false) }
    var auditDialogReport by remember { mutableStateOf<ExifStripper.AuditReport?>(null) }
    var proofVerifyResult by remember { mutableStateOf<GhostCryptoVault.ProofVerificationResult?>(null) }
    var vaultDiagReportText by remember { mutableStateOf<String?>(null) }
    var isRunningVaultDiag by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val scrambleEnabled = remember { ZverPrefs.isPinScrambleEnabled(context) }

    LaunchedEffect(forceSetupFlow) {
        val configured = withContext(Dispatchers.IO) {
            GhostCryptoVault.isConfigured(context)
        }
        inSetupFlow = forceSetupFlow || !configured
        isConfigLoaded = true
    }

    LaunchedEffect(isConfigLoaded, unlockedProfile, inSetupFlow) {
        if (!isConfigLoaded) return@LaunchedEffect
        while (unlockedProfile == null && !inSetupFlow) {
            val until = ZverPrefs.getLockoutUntilMs(context)
            val rem = ((until - System.currentTimeMillis()) / 1000L).toInt()
            lockoutRemainingSec = maxOf(0, rem)
            delay(500)
        }
    }

    fun cleanupPreviewMedia() {
        decryptedBitmap?.recycle()
        decryptedBitmap = null
        try { streamingVideoDataSource?.close() } catch (_: Exception) {}
        streamingVideoDataSource = null
        voicePreviewFile?.let { GhostCryptoVault.shredFile(it) }
        voicePreviewFile = null
        decryptedVideoFile?.let { GhostCryptoVault.shredFile(it) }
        decryptedVideoFile = null
        decryptedRawBytes?.let { Arrays.fill(it, 0.toByte()) }
        decryptedRawBytes = null
    }

    fun refreshFiles() {
        scope.launch {
            val (files, idxMap, ram, monoPurged) = withContext(Dispatchers.IO) {
                val prof = GhostCryptoVault.activeProfile
                val purged = VaultIndexEngine.purgeExpiredFiles(context, prof)
                val f = GhostCryptoVault.listVaultFiles(context, prof)
                val idx = VaultIndexEngine.loadIndex(context, prof)
                val r = GhostCryptoVault.listRamShots()
                val vol = if (prof == GhostCryptoVault.VaultProfile.MAIN) {
                    MonoVaultEngine.VolumeType.MASTER
                } else {
                    MonoVaultEngine.VolumeType.DECOY
                }
                val m = MonoVaultEngine.listRecords(context, vol)
                listOf(f, idx, r, Pair(m, purged))
            }
            @Suppress("UNCHECKED_CAST")
            vaultFiles = files as List<File>
            @Suppress("UNCHECKED_CAST")
            vaultIndexMap = idxMap as Map<String, VaultIndexEngine.VaultItemMeta>
            @Suppress("UNCHECKED_CAST")
            ramShots = ram as List<GhostCryptoVault.RamShot>
            @Suppress("UNCHECKED_CAST")
            val pair = monoPurged as Pair<List<MonoVaultEngine.StoredRecord>, Int>
            monoRecords = pair.first
            if (pair.second > 0) {
                statusMessage = "⏳ АВТО-ШРЕДЕР TTL: УНИЧТОЖЕНО ${pair.second} ФАЙЛ(ОВ)"
            }
            onVaultUpdated()
        }
    }

    val exportZvbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val pwd = pendingZvbPassword.clone()
        pendingZvbPassword.fill('\u0000')
        pendingZvbPassword = charArrayOf()
        if (uri == null || pwd.isEmpty()) {
            pwd.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        isZvbBusy = true
        statusMessage = "⏳ ЭКСПОРТ .ZVB АРХИВА..."
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        ZverBackupEngine.exportProfileBackup(
                            context = context,
                            profile = GhostCryptoVault.activeProfile,
                            masterPassword = pwd,
                            outStream = os
                        )
                    } ?: throw IllegalStateException("Не удалось открыть файл для записи")
                }
                statusMessage = "✅ ЭКСПОРТ .ZVB: ${res.fileCount} ФАЙЛ(ОВ) (${res.archiveBytes / 1024} КБ)"
            } catch (e: Exception) {
                pwd.fill('\u0000')
                statusMessage = "ОШИБКА ЭКСПОРТА .ZVB: ${e.message}"
            } finally {
                isZvbBusy = false
            }
        }
    }

    val importZvbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val pwd = pendingZvbPassword.clone()
        pendingZvbPassword.fill('\u0000')
        pendingZvbPassword = charArrayOf()
        if (uri == null || pwd.isEmpty()) {
            pwd.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        isZvbBusy = true
        statusMessage = "⏳ ИМПОРТ .ZVB АРХИВА..."
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        ZverBackupEngine.importProfileBackup(
                            context = context,
                            targetProfile = GhostCryptoVault.activeProfile,
                            masterPassword = pwd,
                            inStream = ins
                        )
                    } ?: throw IllegalStateException("Не удалось прочитать файл .zvb")
                }
                val skippedNote = if (res.skippedExpiredCount > 0) ", просрочено TTL: ${res.skippedExpiredCount}" else ""
                statusMessage = "✅ ИМПОРТ .ZVB: ${res.importedFilesCount} ФАЙЛ(ОВ)$skippedNote"
                refreshFiles()
            } catch (e: Exception) {
                pwd.fill('\u0000')
                statusMessage = "ОШИБКА ИМПОРТА .ZVB: ${e.message}"
            } finally {
                isZvbBusy = false
            }
        }
    }

    LaunchedEffect(unlockedProfile) {
        if (unlockedProfile != null) {
            refreshFiles()
        }
    }

    fun finishSetupWith(duressPin: CharArray?) {
        if (isPinBusy) return
        val mainClone = setupMainPin.clone()
        val decoyClone = setupDecoyPin?.clone()
        val duressClone = duressPin?.clone()
        setupMainPin.fill('0')
        setupMainPin = charArrayOf()
        setupDecoyPin?.fill('0')
        setupDecoyPin = null
        duressPin?.fill('0')
        enteredPinChars.fill('0')
        enteredPinChars = charArrayOf()
        isPinBusy = true
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    GhostCryptoVault.setupVaultPins(context, mainClone, decoyClone, duressClone)
                }
                inSetupFlow = false
                unlockedProfile = GhostCryptoVault.VaultProfile.MAIN
                statusMessage = "КЛЮЧИ PBKDF2 И КОНТУРЫ ЗАЩИТЫ АКТИВНЫ"
                refreshFiles()
            } finally {
                isPinBusy = false
            }
        }
    }

    Dialog(
        onDismissRequest = {
            cleanupPreviewMedia()
            GhostCryptoVault.lockSession()
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogView = LocalView.current
        DisposableEffect(Unit) {
            val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
            val blockScreenRec = ZverPrefs.isScreenRecBlockEnabled(context)
            if (blockScreenRec) {
                dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            com.example.MainActivity.onVaultSecureOverrideChanged?.invoke(blockScreenRec)
            onDispose {
                com.example.MainActivity.onVaultSecureOverrideChanged?.invoke(false)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBlack.copy(alpha = 0.97f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(14.dp)
        ) {
            if (!isConfigLoaded) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = CyberGreen, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "ИНИЦИАЛИЗАЦИЯ СХРОНА...",
                        color = CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
            // STEP A: 3-CONTOUR PBKDF2 SETUP
            else if (inSetupFlow) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .align(Alignment.Center)
                        .background(CyberSurface, RoundedCornerShape(14.dp))
                        .border(1.dp, CyberGreen.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Shield, contentDescription = "Setup", tint = CyberGreen, modifier = Modifier.size(34.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when (setupStep) {
                            1 -> "ШАГ 1/3: МАСТЕР-ПИН СЕЙФА"
                            2 -> "ШАГ 2/3: ЛОЖНЫЙ ПИН (DECOY)"
                            else -> "ШАГ 3/3: ПИН ПРИНУЖДЕНИЯ (DURESS)"
                        },
                        color = if (setupStep == 3) CyberAmber else CyberGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (setupStep) {
                            1 -> "Ключ PBKDF2-SHA256 (600k) от основного боевого сейфа"
                            2 -> "При вводе открывает пустой/безопасный раздел для прикрытия"
                            else -> "Тихо уничтожает ОСНОВНОЙ сейф и открывает ЛОЖНЫЙ при досмотре"
                        },
                        color = CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    PinDotsRow(count = enteredPinChars.size, max = 6)
                    Spacer(modifier = Modifier.height(14.dp))

                    if (isPinBusy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 10.dp)
                        ) {
                            CircularProgressIndicator(color = CyberGreen, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("ГЕНЕРАЦИЯ PBKDF2-КЛЮЧЕЙ...", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }

                    SecureKeypad(
                        scrambleDigits = false,
                        enabled = !isPinBusy,
                        onKeyPressed = { char ->
                            if (enteredPinChars.size < 6) enteredPinChars = enteredPinChars + char
                        },
                        onClear = {
                            enteredPinChars.fill('0')
                            enteredPinChars = charArrayOf()
                        },
                        onConfirm = {
                            if (!isPinBusy && enteredPinChars.size >= 4) {
                                when (setupStep) {
                                    1 -> {
                                        setupMainPin = enteredPinChars.clone()
                                        enteredPinChars.fill('0')
                                        enteredPinChars = charArrayOf()
                                        setupStep = 2
                                    }
                                    2 -> {
                                        setupDecoyPin = enteredPinChars.clone()
                                        enteredPinChars.fill('0')
                                        enteredPinChars = charArrayOf()
                                        setupStep = 3
                                    }
                                    else -> {
                                        finishSetupWith(enteredPinChars.clone())
                                    }
                                }
                            }
                        }
                    )

                    if (setupStep >= 2) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            enabled = !isPinBusy,
                            onClick = {
                                enteredPinChars.fill('0')
                                enteredPinChars = charArrayOf()
                                if (setupStep == 2) {
                                    setupDecoyPin = null
                                    setupStep = 3
                                } else {
                                    finishSetupWith(null)
                                }
                            }
                        ) {
                            Text(
                                text = if (setupStep == 2) "ПРОПУСТИТЬ ЛОЖНЫЙ ПИН" else "ПРОПУСТИТЬ ПИН ПРИНУЖДЕНИЯ",
                                color = CyberGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
            // STEP B: UNLOCK PIN CHALLENGE
            else if (unlockedProfile == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .align(Alignment.Center)
                        .background(CyberSurface, RoundedCornerShape(14.dp))
                        .border(1.dp, CyberGreen.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Lock", tint = CyberGreen, modifier = Modifier.size(34.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("ВВЕДИ ПИН СЕЙФА", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        text = if (scrambleEnabled) "СКРЕМБЛ КЛАВИАТУРЫ ВКЛ // PBKDF2" else "БЕЗ СЛЕДОВ В ОЗУ // PBKDF2",
                        color = CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    PinDotsRow(count = enteredPinChars.size, max = 6)

                    if (lockoutRemainingSec > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "БЛОКИРОВКА ПЕРЕБОРА: ${lockoutRemainingSec}с",
                            color = CyberAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    } else if (pinErrorText != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pinErrorText!!,
                            color = CyberRed,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isPinBusy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 10.dp)
                        ) {
                            CircularProgressIndicator(color = CyberGreen, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("ПРОВЕРКА PBKDF2 (600K)...", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }

                    SecureKeypad(
                        scrambleDigits = scrambleEnabled,
                        enabled = lockoutRemainingSec == 0 && !isPinBusy,
                        onKeyPressed = { char ->
                            pinErrorText = null
                            if (enteredPinChars.size < 6) enteredPinChars = enteredPinChars + char
                        },
                        onClear = {
                            enteredPinChars.fill('0')
                            enteredPinChars = charArrayOf()
                        },
                        onConfirm = {
                            if (!isPinBusy && enteredPinChars.isNotEmpty() && lockoutRemainingSec == 0) {
                                val pinClone = enteredPinChars.clone()
                                enteredPinChars.fill('0')
                                enteredPinChars = charArrayOf()
                                isPinBusy = true

                                scope.launch {
                                    try {
                                        val outcome = withContext(Dispatchers.Default) {
                                            GhostCryptoVault.unlockWithPinOutcome(context, pinClone)
                                        }
                                        when (outcome) {
                                            GhostCryptoVault.UnlockOutcome.MAIN -> {
                                                unlockedProfile = GhostCryptoVault.VaultProfile.MAIN
                                                refreshFiles()
                                            }
                                            GhostCryptoVault.UnlockOutcome.DECOY,
                                            GhostCryptoVault.UnlockOutcome.DURESS_WIPED -> {
                                                unlockedProfile = GhostCryptoVault.VaultProfile.DECOY
                                                refreshFiles()
                                            }
                                            GhostCryptoVault.UnlockOutcome.INVALID -> {
                                                val fails = ZverPrefs.incrementFailedPinAttempts(context)
                                                val maxWipe = ZverPrefs.getMaxFailedPinWipe(context)
                                                if (maxWipe > 0 && fails >= maxWipe) {
                                                    withContext(Dispatchers.IO) {
                                                        GhostCryptoVault.cryptoShred(context)
                                                        MonoVaultEngine.cryptoShredMonolith(context)
                                                    }
                                                    ZverPrefs.resetFailedPinAttempts(context)
                                                    unlockedProfile = GhostCryptoVault.VaultProfile.DECOY
                                                    refreshFiles()
                                                } else {
                                                    val until = ZverPrefs.getLockoutUntilMs(context)
                                                    val rem = ((until - System.currentTimeMillis()) / 1000L).toInt()
                                                    lockoutRemainingSec = maxOf(0, rem)
                                                    pinErrorText = "ОШИБКА: НЕВЕРНЫЙ ПИН (ПОПЫТКА #$fails)"
                                                }
                                            }
                                        }
                                    } finally {
                                        isPinBusy = false
                                    }
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        enabled = !isPinBusy,
                        onClick = {
                            enteredPinChars.fill('0')
                            onDismiss()
                        }
                    ) {
                        Text("ОТМЕНА", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
            // STEP C: VAULT BROWSER
            else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, CyberGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LockOpen, contentDescription = "Unlocked", tint = CyberGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (unlockedProfile == GhostCryptoVault.VaultProfile.MAIN) "СХРОН: БОЕВОЙ (MAIN)" else "СХРОН: ПРИКРЫТИЕ (DECOY)",
                                color = CyberGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        IconButton(onClick = {
                            cleanupPreviewMedia()
                            GhostCryptoVault.lockSession()
                            onDismiss()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = CyberMuted)
                        }
                    }

                    // Contour Switcher (DISK .GCF vs RAM-ONLY vs MONOLITH)
                    if (selectedFile == null && selectedRamId == null && selectedMonoId == null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val diskSel = activeTab == VaultTab.DISK
                            Text(
                                text = ".GCF (${vaultFiles.size})",
                                color = if (diskSel) Color.Black else CyberMuted,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (diskSel) CyberGreen else CyberSurface)
                                    .border(1.dp, if (diskSel) CyberGreen else CyberBorder, RoundedCornerShape(6.dp))
                                    .clickable { activeTab = VaultTab.DISK }
                                    .padding(vertical = 6.dp)
                            )
                            val ramSel = activeTab == VaultTab.RAM
                            Text(
                                text = "ОЗУ (${ramShots.size})",
                                color = if (ramSel) Color.Black else CyberCyan,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (ramSel) CyberCyan else CyberSurface)
                                    .border(1.dp, if (ramSel) CyberCyan else CyberBorder, RoundedCornerShape(6.dp))
                                    .clickable { activeTab = VaultTab.RAM }
                                    .padding(vertical = 6.dp)
                            )
                            val monoSel = activeTab == VaultTab.MONOLITH
                            Text(
                                text = "МОНОЛИТ (${monoRecords.size})",
                                color = if (monoSel) Color.Black else CyberAmber,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (monoSel) CyberAmber else CyberSurface)
                                    .border(1.dp, if (monoSel) CyberAmber else CyberBorder, RoundedCornerShape(6.dp))
                                    .clickable { activeTab = VaultTab.MONOLITH }
                                    .padding(vertical = 6.dp)
                                    .testTag("vault_tab_monolith")
                            )
                        }
                    }

                    statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    HorizontalDivider(color = CyberBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    val isViewingItem = selectedFile != null || selectedRamId != null || selectedMonoId != null
                    if (isViewingItem) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Viewer Action Toolbar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "← НАЗАД",
                                    color = CyberGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(CyberSurface)
                                        .border(1.dp, CyberBorder, RoundedCornerShape(6.dp))
                                        .clickable {
                                            cleanupPreviewMedia()
                                            selectedFile = null
                                            selectedRamId = null
                                            selectedMonoId = null
                                            selectedMonoSigValid = null
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                )

                                if (decryptedBitmap != null) {
                                    Button(
                                        onClick = { showPrivacyEditor = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan.copy(alpha = 0.2f)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.testTag("vault_open_obscura")
                                    ) {
                                        Icon(Icons.Default.Face, contentDescription = "Маска", modifier = Modifier.size(14.dp), tint = CyberCyan)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("МАСКА", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberCyan)
                                    }

                                    Button(
                                        onClick = { showPhotoEditor = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberAmber.copy(alpha = 0.2f)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.testTag("vault_open_editor")
                                    ) {
                                        Icon(Icons.Default.Tune, contentDescription = "Редактор", modifier = Modifier.size(14.dp), tint = CyberAmber)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("РЕДАКТОР", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberAmber)
                                    }

                                    Button(
                                        onClick = { showPcbLab = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan.copy(alpha = 0.16f)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.testTag("vault_open_pcb_lab")
                                    ) {
                                        Icon(Icons.Default.Memory, contentDescription = "PCB Лаб", modifier = Modifier.size(14.dp), tint = CyberCyan)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("ПЛАТА", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberCyan)
                                    }
                                }

                                val isSelectedDiskVideo = selectedFile != null && GhostCryptoVault.isVideoFile(selectedFile!!)
                                if (decryptedBitmap != null || decryptedVideoFile != null || isSelectedDiskVideo) {
                                    Button(
                                        onClick = {
                                            if (decryptedVideoFile == null && selectedFile != null && isSelectedDiskVideo) {
                                                scope.launch {
                                                    isDecrypting = true
                                                    try {
                                                        decryptedVideoFile = withContext(Dispatchers.IO) {
                                                            GhostCryptoVault.decryptToEphemeralFile(context, selectedFile!!)
                                                        }
                                                        showVikaAi = true
                                                    } catch (e: Exception) {
                                                        statusMessage = "ОШИБКА ПОДГОТОВКИ ВИДЕО: ${e.message}"
                                                    } finally {
                                                        isDecrypting = false
                                                    }
                                                }
                                            } else {
                                                showVikaAi = true
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberGreen.copy(alpha = 0.18f)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.testTag("vault_open_vika_ai")
                                    ) {
                                        Icon(Icons.Default.Psychology, contentDescription = "Вика AI", modifier = Modifier.size(14.dp), tint = CyberGreen)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("ВИКА AI", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberGreen)
                                    }
                                }

                                if (selectedFile != null) {
                                    Button(
                                        onClick = { showMetaTtlDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberAmber.copy(alpha = 0.18f)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.testTag("vault_meta_ttl_btn")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Теги и TTL", modifier = Modifier.size(14.dp), tint = CyberAmber)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("ТЕГИ/TTL", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberAmber)
                                    }
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            val raw = decryptedRawBytes ?: selectedVideoOrFileBytes(context, selectedFile, decryptedVideoFile)
                                            if (raw != null) {
                                                auditDialogReport = withContext(Dispatchers.Default) {
                                                    ExifStripper.auditBytes(raw)
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberSurface),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("vault_audit_btn")
                                ) {
                                    Icon(Icons.Default.VerifiedUser, contentDescription = "Аудит", modifier = Modifier.size(14.dp), tint = CyberGreen)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("АУДИТ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberGreen)
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val isVideo = selectedFile?.let { GhostCryptoVault.isVideoFile(it) }
                                                    ?: (selectedRamId?.let { id -> ramShots.firstOrNull { it.id == id }?.isVideo } ?: false)
                                                val shareTmp = withContext(Dispatchers.IO) {
                                                    when {
                                                        selectedFile != null -> GhostCryptoVault.createEphemeralShareFile(context, selectedFile!!)
                                                        selectedMonoId != null -> {
                                                            val bytes = decryptedRawBytes ?: throw IllegalStateException("Блок монолита не расшифрован")
                                                            val ext = GhostCryptoVault.detectMediaExtension(bytes, isVideo = false)
                                                            GhostCryptoVault.createEphemeralShareBytes(context, bytes, ext)
                                                        }
                                                        else -> {
                                                            val bytes = GhostCryptoVault.getRamShotBytes(selectedRamId!!)
                                                                ?: throw IllegalStateException("Кадр не найден в ОЗУ")
                                                            val ext = GhostCryptoVault.detectMediaExtension(bytes, isVideo)
                                                            GhostCryptoVault.createEphemeralShareBytes(context, bytes, ext)
                                                        }
                                                    }
                                                }
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareTmp)
                                                val mime = when (shareTmp.extension.lowercase()) {
                                                    "mp4" -> "video/mp4"
                                                    "webp" -> "image/webp"
                                                    else -> "image/jpeg"
                                                }
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = mime
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "Передать чистый файл"))
                                                statusMessage = "ВРЕМЕННЫЙ ФАЙЛ (УДАЛИТСЯ ПОСЛЕ ВЫХОДА)"
                                            } catch (e: Exception) {
                                                statusMessage = "ОШИБКА: ${e.message}"
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(14.dp), tint = CyberGreen)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("ОТПРАВИТЬ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberGreen)
                                }

                                if (selectedMonoId == null) {
                                    Button(
                                        onClick = { pendingSingleShred = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.testTag("vault_shred_single_btn")
                                    ) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = "Shred", modifier = Modifier.size(14.dp), tint = Color.White)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("СТЕРЕТЬ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (isDecrypting) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = CyberGreen, strokeWidth = 2.dp)
                                }
                            } else if (decryptedVideoFile != null || streamingVideoDataSource != null) {
                                val origVideoFile = decryptedVideoFile
                                val activeVideo = voicePreviewFile ?: origVideoFile
                                val activeDataSource = streamingVideoDataSource
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (voicePreviewFile == null && activeDataSource != null) {
                                        Gcf2StreamingVideoPlayer(
                                            dataSource = activeDataSource,
                                            onError = { msg -> statusMessage = msg },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else if (activeVideo != null) {
                                        key(activeVideo.absolutePath) {
                                            androidx.compose.ui.viewinterop.AndroidView(
                                                factory = { ctx ->
                                                    android.widget.VideoView(ctx).apply {
                                                        val controller = android.widget.MediaController(ctx)
                                                        controller.setAnchorView(this)
                                                        setMediaController(controller)
                                                        setVideoPath(activeVideo.absolutePath)
                                                        setOnPreparedListener { mp ->
                                                            mp.isLooping = false
                                                            start()
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Offline Voice Changer Panel inside Vault Video Player
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CyberSurface)
                                        .border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (voicePreviewFile != null) "🎙 ГОЛОС: ${selectedVoicePreset.labelRu}" else "🎙 ОФФЛАЙН СМЕНА ГОЛОСА (MP4)",
                                            color = CyberCyan,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                        if (isTransformingVoice) {
                                            Text(
                                                text = "${(voiceTransformProgress * 100).toInt()}%",
                                                color = CyberGreen,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OfflineVoiceChanger.VoicePreset.values()
                                            .filter { it != OfflineVoiceChanger.VoicePreset.ORIGINAL }
                                            .forEach { vp ->
                                                val sel = selectedVoicePreset == vp
                                                Text(
                                                    text = vp.badgeRu,
                                                    color = if (sel) Color.Black else CyberMuted,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.sp,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (sel) CyberCyan else CyberBlack)
                                                        .border(1.dp, if (sel) CyberCyan else CyberBorder, RoundedCornerShape(6.dp))
                                                        .clickable(enabled = !isTransformingVoice) { selectedVoicePreset = vp }
                                                        .padding(horizontal = 9.dp, vertical = 5.dp)
                                                        .testTag("voice_preset_${vp.name.lowercase()}")
                                                )
                                            }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Button(
                                            enabled = !isTransformingVoice,
                                            onClick = {
                                                isTransformingVoice = true
                                                voiceTransformProgress = 0f
                                                statusMessage = "ОБРАБОТКА ГОЛОСА (${selectedVoicePreset.badgeRu})..."
                                                scope.launch {
                                                    try {
                                                        val inputMp4 = origVideoFile ?: withContext(Dispatchers.IO) {
                                                            GhostCryptoVault.decryptToEphemeralFile(context, selectedFile!!)
                                                        }.also { decryptedVideoFile = it }
                                                        val outStage = File(
                                                            GhostCryptoVault.getVideoStageDir(context),
                                                            "voice_${selectedVoicePreset.name.lowercase()}_${System.currentTimeMillis()}.mp4"
                                                        )
                                                        val res = withContext(Dispatchers.IO) {
                                                            OfflineVoiceChanger.processMp4Offline(
                                                                inputMp4 = inputMp4,
                                                                outputMp4 = outStage,
                                                                preset = selectedVoicePreset,
                                                                onProgress = { p -> voiceTransformProgress = p }
                                                            )
                                                        }
                                                        if (res.success && outStage.exists()) {
                                                            voicePreviewFile?.let { GhostCryptoVault.shredFile(it) }
                                                            voicePreviewFile = outStage
                                                            statusMessage = "✅ ${res.detailRu} (ПРЕДПРОСМОТР)"
                                                        } else {
                                                            GhostCryptoVault.shredFile(outStage)
                                                            statusMessage = "⚠ ${res.detailRu}"
                                                        }
                                                    } catch (e: Exception) {
                                                        statusMessage = "СБОЙ ГОЛОСА: ${e.message}"
                                                    } finally {
                                                        isTransformingVoice = false
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan.copy(alpha = 0.22f)),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.weight(1f).height(40.dp).testTag("voice_apply_btn")
                                        ) {
                                            Text(
                                                if (isTransformingVoice) "ОБРАБОТКА..." else "ПРИМЕНИТЬ ГОЛОС",
                                                color = CyberCyan,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }

                                        if (voicePreviewFile != null) {
                                            OutlinedButton(
                                                enabled = !isTransformingVoice,
                                                onClick = {
                                                    voicePreviewFile?.let { GhostCryptoVault.shredFile(it) }
                                                    voicePreviewFile = null
                                                    statusMessage = "ВОССТАНОВЛЕН ОРИГИНАЛ"
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp),
                                                modifier = Modifier.height(40.dp)
                                            ) {
                                                Text("ОРИГ", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                            }

                                            Button(
                                                enabled = !isTransformingVoice,
                                                onClick = {
                                                    val preview = voicePreviewFile ?: return@Button
                                                    isTransformingVoice = true
                                                    scope.launch {
                                                        try {
                                                            val msg = withContext(Dispatchers.IO) {
                                                                val cleanCopy = File(
                                                                    GhostCryptoVault.getVideoStageDir(context),
                                                                    "voice_clean_${System.currentTimeMillis()}.mp4"
                                                                )
                                                                val sanitized = Mp4Sanitizer.sanitizeMp4File(preview, cleanCopy)
                                                                val srcToStore = if (sanitized && cleanCopy.exists()) cleanCopy else preview.copyTo(cleanCopy, overwrite = true)
                                                                if (ZverPrefs.isRamOnlyMode(context)) {
                                                                    val bytes = srcToStore.readBytes()
                                                                    val shot = GhostCryptoVault.addRamShot(bytes, isVideo = true)
                                                                    GhostCryptoVault.shredFile(cleanCopy)
                                                                    "⚡ КОПИЯ С ГОЛОСОМ В ОЗУ (RAM_${shot.id})"
                                                                } else {
                                                                    val savedFile = GhostCryptoVault.encryptFileToVault(context, srcToStore, GhostCryptoVault.MEDIA_VIDEO)
                                                                    GhostCryptoVault.shredFile(cleanCopy)
                                                                    "💾 КОПИЯ С ГОЛОСОМ: ${savedFile.name}"
                                                                }
                                                            }
                                                            statusMessage = msg
                                                            refreshFiles()
                                                        } catch (e: Exception) {
                                                            statusMessage = "ОШИБКА СОХРАНЕНИЯ: ${e.message}"
                                                        } finally {
                                                            isTransformingVoice = false
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen),
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp),
                                                modifier = Modifier.height(40.dp).testTag("voice_save_copy_btn")
                                            ) {
                                                Text("В СЕЙФ", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            } else if (decryptedBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = decryptedBitmap!!.asImageBitmap(),
                                        contentDescription = "Decrypted",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            } else if (selectedFile != null) {
                                val failedFile = selectedFile!!
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CyberSurface)
                                        .border(1.dp, CyberRed.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = "Ошибка ключа", tint = CyberRed, modifier = Modifier.size(34.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = statusMessage ?: "Ошибка расшифровки (активный ключ: ${GhostCryptoVault.getActiveVaultKeySourceLabel(context)})",
                                        color = CyberRed,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            isRunningVaultDiag = true
                                            scope.launch {
                                                val d = withContext(Dispatchers.IO) {
                                                    GhostCryptoVault.diagnoseDecryptKeySource(context, failedFile)
                                                }
                                                isRunningVaultDiag = false
                                                vaultDiagReportText = buildString {
                                                    appendLine("READ-ONLY ДИАГНОСТИКА ФАЙЛА (оригинал НЕ изменён):")
                                                    appendLine("• Файл: ${d.headerInfo.fileName}")
                                                    appendLine("• Размер: ${d.headerInfo.sizeBytes} Б (шифротекст ${d.headerInfo.ciphertextBytes} Б)")
                                                    appendLine("• Заголовок GCF1: ${if (d.headerInfo.hasValidMagic) "OK" else "ОШИБКА"}")
                                                    appendLine("• IV: ${d.headerInfo.ivHex}")
                                                    appendLine("• Подошедший ключ: ${d.matchedKeyKind.labelRu}")
                                                    if (d.plaintextBytesLength > 0) {
                                                        appendLine("• Расшифровано в ОЗУ: ${d.plaintextBytesLength} Б")
                                                    }
                                                    d.errorDetail?.let { appendLine("• Детали: $it") }
                                                }
                                            }
                                        },
                                        enabled = !isRunningVaultDiag,
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                                        modifier = Modifier.testTag("vault_diagnose_single_btn")
                                    ) {
                                        Text("READ-ONLY ДИАГНОСТИКА КЛЮЧА", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = when {
                                    selectedRamId != null -> "КОНТУР: ТОЛЬКО ОЗУ // 0 БАЙТ НА ФЛЕШ-ПАМЯТИ"
                                    selectedMonoId != null -> {
                                        val sigStr = when (selectedMonoSigValid) {
                                            true -> "ECDSA P-256 ПОДПИСЬ: ВАЛИДНА"
                                            false -> "ECDSA: ОШИБКА ПОДПИСИ!"
                                            null -> "МОНОЛИТ AES-256-GCM"
                                        }
                                        "МОНОЛИТ // $sigStr // ЗАПИСЬ #$selectedMonoId"
                                    }
                                    else -> "AES-256-GCM // PBKDF2 // БЕЗ EXIF И ГАЛЕРЕИ"
                                },
                                color = if (selectedMonoSigValid == false) CyberRed else CyberMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            )
                        }
                    } else if (activeTab == VaultTab.MONOLITH) {
                        if (monoRecords.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "В МОНОЛИТНОМ КОНТЕЙНЕРЕ ПОКА НЕТ ЗАПИСЕЙ.\nВсе новые снимки автоматически зеркалируются в скрытую зону storage_block.bin с ECDSA-подписью.",
                                    color = CyberMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(monoRecords, key = { it.id }) { rec ->
                                    val dateStr = remember(rec.timestamp) {
                                        SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date(rec.timestamp))
                                    }
                                    val kb = rec.sizeBytes / 1024
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .background(CyberSurface, RoundedCornerShape(6.dp))
                                            .border(1.dp, CyberAmber.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                            .clickable {
                                                selectedMonoId = rec.id
                                                selectedMonoSigValid = null
                                                isDecrypting = true
                                                scope.launch {
                                                    try {
                                                        cleanupPreviewMedia()
                                                        val vol = if (unlockedProfile == GhostCryptoVault.VaultProfile.MAIN) {
                                                            MonoVaultEngine.VolumeType.MASTER
                                                        } else {
                                                            MonoVaultEngine.VolumeType.DECOY
                                                        }
                                                        val (bytes, sigOk) = withContext(Dispatchers.IO) {
                                                            MonoVaultEngine.readRecord(context, vol, rec.id)
                                                        }
                                                        decryptedRawBytes = bytes
                                                        selectedMonoSigValid = sigOk
                                                        decryptedBitmap = withContext(Dispatchers.Default) {
                                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                        }
                                                        statusMessage = if (sigOk) {
                                                            "МОНОЛИТ: ПОДПИСЬ ECDSA ПОДТВЕРЖДЕНА"
                                                        } else {
                                                            "МОНОЛИТ: ВНИМАНИЕ — ПОДПИСЬ НЕ СОВПАЛА"
                                                        }
                                                    } catch (e: Exception) {
                                                        statusMessage = "ОШИБКА МОНОЛИТА: ${e.message}"
                                                    } finally {
                                                        isDecrypting = false
                                                    }
                                                }
                                            }
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.VerifiedUser, contentDescription = "Monolith Record", tint = CyberAmber, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = "🧱 MONO_${rec.id}",
                                                    color = CyberAmber,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                                Text("$dateStr | $kb КБ | AAD+ECDSA", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                            }
                                        }
                                        Icon(Icons.Default.Visibility, contentDescription = "Open", tint = CyberAmber, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { showWipeConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = "Crypto-Shred", tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ШРЕДЕР МОНОЛИТА И КЛЮЧЕЙ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 11.sp)
                        }
                    } else if (activeTab == VaultTab.RAM) {
                        if (ramShots.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "В ОЗУ ПУСТО.\nВключи «RAM-Only контур» в настройках для съёмки без записи на диск.",
                                    color = CyberMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(ramShots, key = { it.id }) { shot ->
                                    val dateStr = remember(shot.timestamp) {
                                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(shot.timestamp))
                                    }
                                    val kb = shot.bytes.size / 1024
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .background(CyberSurface, RoundedCornerShape(6.dp))
                                            .border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                            .clickable {
                                                selectedRamId = shot.id
                                                isDecrypting = true
                                                scope.launch {
                                                    cleanupPreviewMedia()
                                                    val bytes = shot.bytes.clone()
                                                    decryptedRawBytes = bytes
                                                    if (shot.isVideo) {
                                                        decryptedVideoFile = withContext(Dispatchers.IO) {
                                                            GhostCryptoVault.createEphemeralShareBytes(context, bytes, "mp4")
                                                        }
                                                    } else {
                                                        decryptedBitmap = withContext(Dispatchers.Default) {
                                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                        }
                                                    }
                                                    isDecrypting = false
                                                }
                                            }
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "⚡ RAM_${shot.id} | $dateStr | ${kb} КБ",
                                            color = CyberCyan,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        )
                                        Icon(Icons.Default.Visibility, contentDescription = "Open", tint = CyberCyan, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    val cleared = GhostCryptoVault.clearRamShots()
                                    statusMessage = "ОЗУ ЗАТЁРТО ($cleared КАДРОВ)"
                                    refreshFiles()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("ОЧИСТИТЬ ВСЁ ОЗУ", color = CyberCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    } else {
                        // Search & Filter Bar for Disk Vault
                        if (vaultFiles.isNotEmpty()) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Поиск по тегам, заметке, имени...", fontSize = 11.sp, color = CyberMuted) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск", tint = CyberCyan, modifier = Modifier.size(16.dp)) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Очистить", tint = CyberMuted, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("vault_search_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberCyan,
                                    unfocusedBorderColor = CyberBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                VaultIndexEngine.VaultFilterType.values().forEach { ft ->
                                    val sel = filterType == ft
                                    Text(
                                        text = ft.labelRu,
                                        color = if (sel) Color.Black else CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) CyberCyan else CyberSurface)
                                            .border(1.dp, if (sel) CyberCyan else CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable { filterType = ft }
                                            .padding(horizontal = 9.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        val filteredVaultFiles = remember(vaultFiles, vaultIndexMap, searchQuery, filterType) {
                            vaultFiles.filter { f ->
                                VaultIndexEngine.matchesFilter(f, vaultIndexMap[f.name], searchQuery, filterType)
                            }
                        }

                        if (filteredVaultFiles.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Shield, contentDescription = "Empty", tint = CyberMuted, modifier = Modifier.size(40.dp))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        when {
                                            vaultFiles.isNotEmpty() -> "ПО ФИЛЬТРУ НИЧЕГО НЕ НАЙДЕНО"
                                            unlockedProfile == GhostCryptoVault.VaultProfile.DECOY -> "ЛОЖНЫЙ СХРОН ПУСТ"
                                            else -> "В СЕЙФЕ ПОКА НЕТ ФАЙЛОВ"
                                        },
                                        color = CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(filteredVaultFiles, key = { it.name }) { file ->
                                    val dateStr = remember(file) {
                                        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                                    }
                                    val sizeKb = remember(file) { file.length() / 1024 }
                                    val isVideo = remember(file) { GhostCryptoVault.isVideoFile(file) }
                                    val isGcf2 = remember(file) { GhostCryptoVault.isGcf2File(file) }
                                    val meta = vaultIndexMap[file.name]
                                    val ttlText = meta?.takeIf { it.hasTtl() }?.formatRemainingRu() ?: ""
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .background(CyberSurface, RoundedCornerShape(6.dp))
                                            .border(1.dp, if (ttlText.isNotEmpty()) CyberAmber.copy(alpha = 0.5f) else CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                selectedFile = file
                                                isDecrypting = true
                                                scope.launch {
                                                    try {
                                                        cleanupPreviewMedia()
                                                        if (isVideo) {
                                                            if (GhostCryptoVault.isGcf2File(file)) {
                                                                val ds = withContext(Dispatchers.IO) {
                                                                    GhostCryptoVault.openGcf2DataSource(context, file)
                                                                }
                                                                if (ds != null) {
                                                                    streamingVideoDataSource = ds
                                                                } else {
                                                                    decryptedVideoFile = withContext(Dispatchers.IO) {
                                                                        GhostCryptoVault.decryptToEphemeralFile(context, file)
                                                                    }
                                                                }
                                                            } else {
                                                                decryptedVideoFile = withContext(Dispatchers.IO) {
                                                                    GhostCryptoVault.decryptToEphemeralFile(context, file)
                                                                }
                                                            }
                                                        } else {
                                                            val raw = withContext(Dispatchers.IO) {
                                                                GhostCryptoVault.decryptToRam(context, file)
                                                            }
                                                            decryptedRawBytes = raw
                                                            decryptedBitmap = withContext(Dispatchers.Default) {
                                                                BitmapFactory.decodeByteArray(raw, 0, raw.size)
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        statusMessage = "DECRYPT ERROR: ${e.message}"
                                                    } finally {
                                                        isDecrypting = false
                                                    }
                                                }
                                            }
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(
                                                imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Photo,
                                                contentDescription = if (isVideo) "Видео" else "Фото",
                                                tint = if (isVideo) CyberRed else CyberGreen,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (isVideo) "🎥 ${file.name}" else "📸 ${file.name}",
                                                    color = Color.White,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val containerLabel = if (isGcf2) "GCF2-STREAM" else "GCF1"
                                                val ttlPart = if (ttlText.isNotEmpty()) " | ⏳$ttlText" else ""
                                                Text(
                                                    text = "$dateStr | $sizeKb КБ | $containerLabel$ttlPart",
                                                    color = if (ttlText.isNotEmpty()) CyberAmber else CyberMuted,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 9.sp
                                                )
                                                if (meta != null && (meta.tags.isNotEmpty() || meta.note.isNotEmpty())) {
                                                    val tagStr = meta.tags.joinToString(" ") { "#$it" }
                                                    val notePart = if (meta.note.isNotEmpty()) " • ${meta.note}" else ""
                                                    Text(
                                                        text = "$tagStr$notePart".trim(),
                                                        color = CyberCyan,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 9.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                        Icon(
                                            imageVector = if (isVideo) Icons.Default.PlayArrow else Icons.Default.Visibility,
                                            contentDescription = if (isVideo) "Play" else "View",
                                            tint = CyberGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (vaultFiles.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (!isRunningVaultDiag) {
                                            isRunningVaultDiag = true
                                            scope.launch {
                                                val list = withContext(Dispatchers.IO) {
                                                    GhostCryptoVault.diagnoseVaultDirectory(context)
                                                }
                                                isRunningVaultDiag = false
                                                val grouped = list.groupingBy { it.matchedKeyKind }.eachCount()
                                                vaultDiagReportText = buildString {
                                                    appendLine("READ-ONLY ДИАГНОСТИКА СЕЙФА (${list.size} файлов)")
                                                    appendLine("Оригиналы НЕ изменялись и НЕ перезаписывались.")
                                                    appendLine("Активный ключ сессии: ${GhostCryptoVault.getActiveVaultKeySourceLabel(context)}")
                                                    appendLine()
                                                    grouped.forEach { (kind, count) ->
                                                        appendLine("• ${kind.labelRu}: $count шт")
                                                    }
                                                    appendLine()
                                                    list.take(35).forEach { d ->
                                                        appendLine("- ${d.file.name} [${d.headerInfo.formatVersion}]: ${d.matchedKeyKind.labelRu} (${d.headerInfo.sizeBytes / 1024} КБ)")
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isRunningVaultDiag,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).height(42.dp).testTag("vault_diagnose_all_btn")
                                ) {
                                    Text(
                                        if (isRunningVaultDiag) "СКАН..." else "🩺 КЛЮЧИ (${vaultFiles.size})",
                                        color = CyberCyan,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val res = withContext(Dispatchers.IO) {
                                                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                                    val bDir = File(context.noBackupFilesDir, "vault_backup_$ts")
                                                    GhostCryptoVault.backupVaultDirectoryReadOnly(context, GhostCryptoVault.activeProfile, bDir)
                                                }
                                                statusMessage = "БЭКАП (READ-ONLY): ${res.copiedFilesCount} ФАЙЛОВ (${res.copiedBytes / 1024} КБ)"
                                            } catch (e: Exception) {
                                                statusMessage = "ОШИБКА БЭКАПА: ${e.message}"
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).height(42.dp).testTag("vault_backup_readonly_btn")
                                ) {
                                    Text(
                                        "📦 ЛОК.БЭКАП",
                                        color = CyberGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val manifestJson = withContext(Dispatchers.IO) {
                                                    GhostCryptoVault.generateVaultChainManifestJson(context, GhostCryptoVault.activeProfile)
                                                }
                                                val tmp = withContext(Dispatchers.IO) {
                                                    GhostCryptoVault.createEphemeralShareBytes(
                                                        context,
                                                        manifestJson.toByteArray(Charsets.UTF_8),
                                                        "chain_manifest.json"
                                                    )
                                                }
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmp)
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "application/json"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Экспорт Chain-of-Custody (.json)"))
                                                statusMessage = "ЦЕПЬ КУРАТОРСТВА (.JSON) СФОРМИРОВАНА (${vaultFiles.size} ФАЙЛОВ)"
                                            } catch (e: Exception) {
                                                statusMessage = "ОШИБКА МАНИФЕСТА: ${e.message}"
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).height(42.dp).testTag("vault_export_chain_manifest_btn")
                                ) {
                                    Text(
                                        "⛓️ ЦЕПЬ",
                                        color = CyberAmber,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }

                        // Portable Encrypted .ZVB Backup/Restore Row (available even on empty vault for Restore!)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                enabled = !isZvbBusy && vaultFiles.isNotEmpty(),
                                onClick = { zvbPasswordPromptMode = "EXPORT" },
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f).height(42.dp).testTag("vault_zvb_export_btn")
                            ) {
                                Text(
                                    "🔐 ЭКСПОРТ .ZVB",
                                    color = if (vaultFiles.isNotEmpty()) CyberGreen else CyberMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                            OutlinedButton(
                                enabled = !isZvbBusy,
                                onClick = { zvbPasswordPromptMode = "IMPORT" },
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f).height(42.dp).testTag("vault_zvb_import_btn")
                            ) {
                                Text(
                                    "📥 ИМПОРТ .ZVB",
                                    color = CyberCyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showWipeConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = "Crypto-Shred", tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ЭКСТРЕННО СТЕРЕТЬ ВСЕ ДАННЫЕ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }

            vaultDiagReportText?.let { diagText ->
                AlertDialog(
                    onDismissRequest = { vaultDiagReportText = null },
                    title = {
                        Text(
                            "READ-ONLY ДИАГНОСТИКА СЕЙФА",
                            color = CyberCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    },
                    text = {
                        Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            Text(
                                text = diagText,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            com.example.security.SecureClipboard.copySensitive(context, "Zver Vault Diag", diagText)
                            statusMessage = "ОТЧЁТ ДИАГНОСТИКИ СКОПИРОВАН (45с)"
                            vaultDiagReportText = null
                        }) {
                            Text("КОПИРОВАТЬ", color = CyberGreen, fontFamily = FontFamily.Monospace)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { vaultDiagReportText = null }) {
                            Text("ЗАКРЫТЬ", color = CyberMuted, fontFamily = FontFamily.Monospace)
                        }
                    },
                    containerColor = CyberSurface
                )
            }

            if (pendingSingleShred) {
                AlertDialog(
                    onDismissRequest = { pendingSingleShred = false },
                    title = { Text("УНИЧТОЖИТЬ ФАЙЛ?", color = CyberRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    text = { Text("Файл будет затёрт случайным шумом и удалён без возможности восстановления.", color = Color.White, fontSize = 12.sp) },
                    confirmButton = {
                        Button(
                            onClick = {
                                val targetF = selectedFile
                                val targetRam = selectedRamId
                                pendingSingleShred = false
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        targetF?.let {
                                            GhostCryptoVault.shredFile(it)
                                            VaultIndexEngine.removeMeta(context, GhostCryptoVault.activeProfile, it.name)
                                        }
                                        targetRam?.let { GhostCryptoVault.shredRamShot(it) }
                                    }
                                    cleanupPreviewMedia()
                                    selectedFile = null
                                    selectedRamId = null
                                    statusMessage = "ФАЙЛ УНИЧТОЖЕН"
                                    refreshFiles()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberRed)
                        ) { Text("СТЕРЕТЬ", color = Color.White) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingSingleShred = false }) { Text("ОТМЕНА", color = CyberMuted) }
                    },
                    containerColor = CyberSurface
                )
            }

            if (showWipeConfirm) {
                AlertDialog(
                    onDismissRequest = { if (!isWiping) showWipeConfirm = false },
                    title = { Text("УНИЧТОЖИТЬ ВСЕ КЛЮЧИ И СХРОН", color = CyberRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    text = { Text("Отзывает ключи TEE KeyStore, затирает монолит и файлы во всех контурах.", color = Color.White, fontSize = 12.sp) },
                    confirmButton = {
                        Button(
                            enabled = !isWiping,
                            onClick = {
                                isWiping = true
                                scope.launch {
                                    try {
                                        val count = withContext(Dispatchers.IO) {
                                            val c = GhostCryptoVault.cryptoShred(context)
                                            MonoVaultEngine.cryptoShredMonolith(context)
                                            c
                                        }
                                        cleanupPreviewMedia()
                                        selectedFile = null
                                        selectedRamId = null
                                        selectedMonoId = null
                                        showWipeConfirm = false
                                        statusMessage = "УНИЧТОЖЕНО $count ЭЛЕМЕНТОВ И ВСЕ КЛЮЧИ"
                                        refreshFiles()
                                    } finally {
                                        isWiping = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberRed)
                        ) { Text(if (isWiping) "ЗАТИРАНИЕ..." else "ДА, УНИЧТОЖИТЬ", color = Color.White) }
                    },
                    dismissButton = {
                        TextButton(enabled = !isWiping, onClick = { showWipeConfirm = false }) {
                            Text("ОТМЕНА", color = CyberMuted)
                        }
                    },
                    containerColor = CyberSurface
                )
            }

            if (showPrivacyEditor && decryptedBitmap != null) {
                PrivacyEditorDialog(
                    sourceBitmap = decryptedBitmap!!,
                    onDismiss = { showPrivacyEditor = false },
                    onSavedToVault = { msg ->
                        showPrivacyEditor = false
                        statusMessage = msg
                        refreshFiles()
                    }
                )
            }

            if (showPhotoEditor && decryptedBitmap != null) {
                VaultPhotoEditorDialog(
                    sourceBitmap = decryptedBitmap!!,
                    onDismiss = { showPhotoEditor = false },
                    onSavedCopyToVault = { msg ->
                        showPhotoEditor = false
                        statusMessage = msg
                        refreshFiles()
                    }
                )
            }

            if (showPcbLab && decryptedBitmap != null) {
                PcbLabDialog(
                    initialBitmap = decryptedBitmap!!,
                    onSavedToVault = { msg ->
                        showPcbLab = false
                        statusMessage = msg
                        refreshFiles()
                    },
                    onDismiss = { showPcbLab = false }
                )
            }

            if (showVikaAi && (decryptedBitmap != null || decryptedVideoFile != null)) {
                VikaAiSheet(
                    initialTab = VikaSheetTab.TASKS,
                    externalBitmap = decryptedBitmap,
                    externalVideoFile = decryptedVideoFile,
                    onStatusMessage = { msg -> statusMessage = msg },
                    onDismiss = { showVikaAi = false }
                )
            }

            auditDialogReport?.let { rep ->
                AlertDialog(
                    onDismissRequest = {
                        auditDialogReport = null
                        proofVerifyResult = null
                    },
                    title = {
                        Text(
                            text = if (rep.isClean) "✅ АУДИТ: 0 МЕТАДАННЫХ" else "⚠ АУДИТ: НАЙДЕНЫ ТЕГИ",
                            color = if (rep.isClean) CyberGreen else CyberRed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text("Формат: ${rep.format} • Размер: ${rep.totalBytes / 1024} КБ", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text("Метаданные: ${rep.metadataBytes} байт", color = if (rep.metadataBytes == 0) CyberGreen else CyberRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            rep.notes.forEach { n ->
                                Text("• $n", color = CyberCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            proofVerifyResult?.let { vr ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CyberBlack, RoundedCornerShape(6.dp))
                                        .border(1.dp, if (vr.valid) CyberGreen else CyberAmber, RoundedCornerShape(6.dp))
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = if (vr.valid) "🔐 PROOF ВЕРИФИЦИРОВАН (ECDSA + SHA-256)" else "⚠ ПРОВЕРКА PROOF: ${vr.detailRu}",
                                        color = if (vr.valid) CyberGreen else CyberAmber,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                    Text("SHA-256 совпал: ${if (vr.sha256Matches) "ДА" else "НЕТ"}", color = Color.LightGray, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                    Text("ECDSA подпись: ${if (vr.signatureValid) "ВАЛИДНА" else "НЕТ/KEYSTORE"}", color = Color.LightGray, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                    Text("Хэш: ${vr.actualSha256.take(24)}...", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Структура контейнера:", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            rep.segments.take(18).forEach { seg ->
                                Text("  $seg", color = Color.LightGray, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val raw = decryptedRawBytes ?: selectedVideoOrFileBytes(context, selectedFile, decryptedVideoFile)
                                            if (raw != null) {
                                                val vr = withContext(Dispatchers.Default) {
                                                    val proofJson = GhostCryptoVault.generateDetachedProofJson(raw, rep)
                                                    GhostCryptoVault.verifyDetachedProofJson(raw, proofJson)
                                                }
                                                proofVerifyResult = vr
                                            }
                                        } catch (e: Exception) {
                                            statusMessage = "Ошибка проверки proof: ${e.message}"
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("vault_verify_proof_btn")
                            ) {
                                Text("ПРОВЕРИТЬ", color = CyberCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val raw = decryptedRawBytes ?: selectedVideoOrFileBytes(context, selectedFile, decryptedVideoFile)
                                            if (raw != null) {
                                                val proofJson = withContext(Dispatchers.Default) {
                                                    GhostCryptoVault.generateDetachedProofJson(raw, rep)
                                                }
                                                val tmp = GhostCryptoVault.createEphemeralShareBytes(
                                                    context,
                                                    proofJson.toByteArray(Charsets.UTF_8),
                                                    "proof.json"
                                                )
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmp)
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "application/json"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Отправить .proof.json"))
                                            }
                                        } catch (e: Exception) {
                                            statusMessage = "Ошибка proof: ${e.message}"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                            ) {
                                Text("ЭКСПОРТ .PROOF", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            auditDialogReport = null
                            proofVerifyResult = null
                        }) {
                            Text("ЗАКРЫТЬ", color = CyberMuted, fontFamily = FontFamily.Monospace)
                        }
                    },
                    containerColor = CyberSurface
                )
            }
            if (showMetaTtlDialog && selectedFile != null) {
                val targetFile = selectedFile!!
                val existingMeta = remember(targetFile.name, vaultIndexMap) {
                    vaultIndexMap[targetFile.name] ?: VaultIndexEngine.VaultItemMeta(fileName = targetFile.name)
                }
                var tagsInput by remember { mutableStateOf(existingMeta.tags.joinToString(", ")) }
                var noteInput by remember { mutableStateOf(existingMeta.note) }
                AlertDialog(
                    onDismissRequest = { showMetaTtlDialog = false },
                    title = {
                        Text(
                            "ТЕГИ, ЗАМЕТКА И TTL-ШРЕДЕР",
                            color = CyberAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tagsInput,
                                onValueChange = { tagsInput = it },
                                label = { Text("Теги через запятую (напр. док, плата)", fontSize = 10.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberCyan,
                                    unfocusedBorderColor = CyberBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            OutlinedTextField(
                                value = noteInput,
                                onValueChange = { noteInput = it },
                                label = { Text("Заметка", fontSize = 10.sp) },
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberCyan,
                                    unfocusedBorderColor = CyberBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Text("Таймер авто-уничтожения (TTL):", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                VaultIndexEngine.TtlPreset.values().forEach { preset ->
                                    Text(
                                        text = preset.labelRu,
                                        color = CyberAmber,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(CyberBlack)
                                            .border(1.dp, CyberAmber.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                            .clickable {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        VaultIndexEngine.setTtl(
                                                            context,
                                                            GhostCryptoVault.activeProfile,
                                                            targetFile.name,
                                                            preset.durationMs
                                                        )
                                                    }
                                                    statusMessage = "⏳ TTL УСТАНОВЛЕН: ${preset.labelRu}"
                                                    refreshFiles()
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val parsed = VaultIndexEngine.parseTagsInput(tagsInput)
                                val cleanNote = noteInput
                                showMetaTtlDialog = false
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        VaultIndexEngine.setTagsAndNote(
                                            context,
                                            GhostCryptoVault.activeProfile,
                                            targetFile.name,
                                            parsed,
                                            cleanNote
                                        )
                                    }
                                    statusMessage = "🏷️ ТЕГИ И ЗАМЕТКА СОХРАНЕНЫ"
                                    refreshFiles()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                        ) {
                            Text("СОХРАНИТЬ", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showMetaTtlDialog = false }) {
                            Text("ЗАКРЫТЬ", color = CyberMuted, fontFamily = FontFamily.Monospace)
                        }
                    },
                    containerColor = CyberSurface
                )
            }

            zvbPasswordPromptMode?.let { mode ->
                var pwdText by remember(mode) { mutableStateOf("") }
                var pwdConfirm by remember(mode) { mutableStateOf("") }
                val isExport = mode == "EXPORT"
                val canProceed = pwdText.length >= 4 && (!isExport || pwdText == pwdConfirm)
                AlertDialog(
                    onDismissRequest = { zvbPasswordPromptMode = null },
                    title = {
                        Text(
                            text = if (isExport) "ЭКСПОРТ ПОРТАТИВНОГО .ZVB" else "ИМПОРТ ПОРТАТИВНОГО .ZVB",
                            color = if (isExport) CyberGreen else CyberCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (isExport) {
                                    "Задай мастер-пароль для шифрования архива (PBKDF2 300k + AES-256-GCM). Подойдёт для переноса между debug/release и разными телефонами."
                                } else {
                                    "Введи мастер-пароль от файла .zvb. Файлы и теги будут перешифрованы под текущий сейф (${GhostCryptoVault.activeProfile.name})."
                                },
                                color = Color.White,
                                fontSize = 11.sp
                            )
                            OutlinedTextField(
                                value = pwdText,
                                onValueChange = { pwdText = it },
                                label = { Text("Мастер-пароль (мин. 4 символа)", fontSize = 10.sp) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("zvb_password_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberGreen,
                                    unfocusedBorderColor = CyberBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            if (isExport) {
                                OutlinedTextField(
                                    value = pwdConfirm,
                                    onValueChange = { pwdConfirm = it },
                                    label = { Text("Повтори мастер-пароль", fontSize = 10.sp) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CyberGreen,
                                        unfocusedBorderColor = CyberBorder,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            enabled = canProceed,
                            onClick = {
                                pendingZvbPassword.fill('\u0000')
                                pendingZvbPassword = pwdText.toCharArray()
                                pwdText = ""
                                pwdConfirm = ""
                                zvbPasswordPromptMode = null
                                if (isExport) {
                                    val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                    exportZvbLauncher.launch("zver_vault_${GhostCryptoVault.activeProfile.name.lowercase()}_$ts.zvb")
                                } else {
                                    importZvbLauncher.launch(arrayOf("*/*"))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isExport) CyberGreen else CyberCyan)
                        ) {
                            Text(if (isExport) "ВЫБРАТЬ ФАЙЛ" else "ОТКРЫТЬ .ZVB", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { zvbPasswordPromptMode = null }) {
                            Text("ОТМЕНА", color = CyberMuted, fontFamily = FontFamily.Monospace)
                        }
                    },
                    containerColor = CyberSurface
                )
            }
        }
    }
}

@Composable
private fun Gcf2StreamingVideoPlayer(
    dataSource: EncryptedMediaDataSource,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var mediaPlayer by remember(dataSource) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(dataSource) { mutableStateOf(false) }
    var durationMs by remember(dataSource) { mutableStateOf(0) }
    var positionMs by remember(dataSource) { mutableStateOf(0) }
    var isPrepared by remember(dataSource) { mutableStateOf(false) }

    LaunchedEffect(mediaPlayer, isPlaying, isPrepared) {
        while (isPlaying && isPrepared) {
            try {
                positionMs = mediaPlayer?.currentPosition ?: 0
            } catch (_: Exception) {}
            delay(250)
        }
    }

    DisposableEffect(dataSource) {
        onDispose {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            try {
                                val mp = MediaPlayer().apply {
                                    setSurface(holder.surface)
                                    setDataSource(dataSource)
                                    setOnPreparedListener { readyMp ->
                                        isPrepared = true
                                        durationMs = readyMp.duration.coerceAtLeast(1)
                                        readyMp.start()
                                        isPlaying = true
                                    }
                                    setOnCompletionListener {
                                        isPlaying = false
                                    }
                                    setOnErrorListener { _, what, extra ->
                                        onError("GCF2 ПЛЕЕР: код $what/$extra")
                                        true
                                    }
                                    prepareAsync()
                                }
                                mediaPlayer = mp
                            } catch (e: Exception) {
                                onError("GCF2 STREAM ОШИБКА: ${e.message}")
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            try {
                                mediaPlayer?.setSurface(null)
                                mediaPlayer?.release()
                            } catch (_: Exception) {}
                            mediaPlayer = null
                            isPrepared = false
                            isPlaying = false
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = {
                    val mp = mediaPlayer ?: return@IconButton
                    if (!isPrepared) return@IconButton
                    if (mp.isPlaying) {
                        mp.pause()
                        isPlaying = false
                    } else {
                        mp.start()
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = CyberGreen
                )
            }

            if (durationMs > 0) {
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                    onValueChange = { v ->
                        positionMs = v.toInt()
                        if (isPrepared) {
                            try { mediaPlayer?.seekTo(v.toInt()) } catch (_: Exception) {}
                        }
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = CyberGreen,
                        activeTrackColor = CyberGreen,
                        inactiveTrackColor = CyberBorder
                    )
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Text(
                text = "GCF2 • ${positionMs / 1000}/${durationMs / 1000}с",
                color = CyberCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
        }
    }
}

private suspend fun selectedVideoOrFileBytes(
    context: android.content.Context,
    selectedFile: File?,
    decryptedVideoFile: File?
): ByteArray? = withContext(Dispatchers.IO) {
    try {
        when {
            decryptedVideoFile != null && decryptedVideoFile.exists() -> decryptedVideoFile.readBytes()
            selectedFile != null -> GhostCryptoVault.decryptToRam(context, selectedFile)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun PinDotsRow(count: Int, max: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(max) { idx ->
            val filled = idx < count
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (filled) CyberGreen else CyberBorder)
                    .border(1.dp, if (filled) CyberGreen else CyberMuted, RoundedCornerShape(6.dp))
            )
        }
    }
}

@Composable
private fun SecureKeypad(
    scrambleDigits: Boolean,
    enabled: Boolean = true,
    onKeyPressed: (Char) -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit
) {
    val digits = remember(scrambleDigits) {
        val list = ('0'..'9').toMutableList()
        if (scrambleDigits) list.shuffle() else listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0').also {
            list.clear()
            list.addAll(it)
        }
        list
    }
    val layout = listOf(
        listOf(digits[0], digits[1], digits[2]),
        listOf(digits[3], digits[4], digits[5]),
        listOf(digits[6], digits[7], digits[8]),
        listOf('C', digits[9], 'V')
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in layout) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                for (key in row) {
                    Button(
                        onClick = {
                            if (!enabled) return@Button
                            when (key) {
                                'C' -> onClear()
                                'V' -> onConfirm()
                                else -> onKeyPressed(key)
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier.size(62.dp, 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberBorder.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = if (key == 'C') "CLR" else if (key == 'V') "OK" else key.toString(),
                            color = if (key == 'V') CyberGreen else if (key == 'C') CyberRed else Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
