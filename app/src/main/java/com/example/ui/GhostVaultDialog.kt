package com.example.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.WindowManager
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import com.example.camera.ExifStripper
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
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
    RAM
}

@Composable
fun GhostVaultDialog(
    forceSetupFlow: Boolean = false,
    onDismiss: () -> Unit,
    onVaultUpdated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isAlreadyConfigured = remember { GhostCryptoVault.isConfigured(context) }
    var inSetupFlow by remember { mutableStateOf(forceSetupFlow || !isAlreadyConfigured) }
    var setupStep by remember { mutableStateOf(1) } // 1: Main, 2: Decoy, 3: Duress
    var setupMainPin by remember { mutableStateOf(charArrayOf()) }
    var setupDecoyPin by remember { mutableStateOf<CharArray?>(null) }

    var unlockedProfile by remember { mutableStateOf<GhostCryptoVault.VaultProfile?>(null) }
    var enteredPinChars by remember { mutableStateOf(charArrayOf()) }
    var pinErrorText by remember { mutableStateOf<String?>(null) }
    var lockoutRemainingSec by remember { mutableStateOf(0) }

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
    var ramShots by remember { mutableStateOf(GhostCryptoVault.listRamShots()) }

    var selectedFile by remember { mutableStateOf<File?>(null) }
    var selectedRamId by remember { mutableStateOf<String?>(null) }
    var decryptedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var decryptedVideoFile by remember { mutableStateOf<File?>(null) }
    var decryptedRawBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isDecrypting by remember { mutableStateOf(false) }

    var showWipeConfirm by remember { mutableStateOf(false) }
    var showPrivacyEditor by remember { mutableStateOf(false) }
    var showVikaAi by remember { mutableStateOf(false) }
    var auditDialogReport by remember { mutableStateOf<ExifStripper.AuditReport?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val scrambleEnabled = remember { ZverPrefs.isPinScrambleEnabled(context) }

    LaunchedEffect(Unit) {
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
        decryptedVideoFile?.let { GhostCryptoVault.shredFile(it) }
        decryptedVideoFile = null
        decryptedRawBytes?.let { Arrays.fill(it, 0.toByte()) }
        decryptedRawBytes = null
    }

    fun refreshFiles() {
        vaultFiles = GhostCryptoVault.listVaultFiles(context)
        ramShots = GhostCryptoVault.listRamShots()
        onVaultUpdated()
    }

    fun finishSetupWith(duressPin: CharArray?) {
        GhostCryptoVault.setupVaultPins(context, setupMainPin, setupDecoyPin, duressPin)
        setupMainPin = charArrayOf()
        setupDecoyPin = null
        enteredPinChars.fill('0')
        enteredPinChars = charArrayOf()
        inSetupFlow = false
        unlockedProfile = GhostCryptoVault.VaultProfile.MAIN
        statusMessage = "КЛЮЧИ PBKDF2 И КОНТУРЫ ЗАЩИТЫ АКТИВНЫ"
        refreshFiles()
    }

    Dialog(
        onDismissRequest = {
            cleanupPreviewMedia()
            GhostCryptoVault.lockSession()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogView = LocalView.current
        DisposableEffect(Unit) {
            val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            com.example.MainActivity.onVaultSecureOverrideChanged?.invoke(true)
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
            // STEP A: 3-CONTOUR PBKDF2 SETUP
            if (inSetupFlow) {
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

                    SecureKeypad(
                        scrambleDigits = false,
                        onKeyPressed = { char ->
                            if (enteredPinChars.size < 6) enteredPinChars = enteredPinChars + char
                        },
                        onClear = {
                            enteredPinChars.fill('0')
                            enteredPinChars = charArrayOf()
                        },
                        onConfirm = {
                            if (enteredPinChars.size >= 4) {
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
                        TextButton(onClick = {
                            enteredPinChars.fill('0')
                            enteredPinChars = charArrayOf()
                            if (setupStep == 2) {
                                setupDecoyPin = null
                                setupStep = 3
                            } else {
                                finishSetupWith(null)
                            }
                        }) {
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

                    SecureKeypad(
                        scrambleDigits = scrambleEnabled,
                        enabled = lockoutRemainingSec == 0,
                        onKeyPressed = { char ->
                            pinErrorText = null
                            if (enteredPinChars.size < 6) enteredPinChars = enteredPinChars + char
                        },
                        onClear = {
                            enteredPinChars.fill('0')
                            enteredPinChars = charArrayOf()
                        },
                        onConfirm = {
                            if (enteredPinChars.isNotEmpty() && lockoutRemainingSec == 0) {
                                val outcome = GhostCryptoVault.unlockWithPinOutcome(context, enteredPinChars.clone())
                                enteredPinChars.fill('0')
                                enteredPinChars = charArrayOf()

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
                                            GhostCryptoVault.cryptoShred(context)
                                            MonoVaultEngine.cryptoShredMonolith(context)
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
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(onClick = {
                        enteredPinChars.fill('0')
                        onDismiss()
                    }) {
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

                    // Contour Switcher (DISK .GCF vs RAM-ONLY)
                    if (selectedFile == null && selectedRamId == null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val diskSel = activeTab == VaultTab.DISK
                            Text(
                                text = "ДИСК .GCF (${vaultFiles.size})",
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
                                text = "ОЗУ-СЕЙФ (${ramShots.size})",
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

                    val isViewingItem = selectedFile != null || selectedRamId != null
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
                                }

                                if (decryptedBitmap != null || decryptedVideoFile != null) {
                                    Button(
                                        onClick = { showVikaAi = true },
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
                                                    if (selectedFile != null) {
                                                        GhostCryptoVault.createEphemeralShareFile(context, selectedFile!!)
                                                    } else {
                                                        val bytes = GhostCryptoVault.getRamShotBytes(selectedRamId!!)
                                                            ?: throw IllegalStateException("Кадр не найден в ОЗУ")
                                                        val ext = GhostCryptoVault.detectMediaExtension(bytes, isVideo)
                                                        GhostCryptoVault.createEphemeralShareBytes(context, bytes, ext)
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

                                Button(
                                    onClick = {
                                        selectedFile?.let { f -> GhostCryptoVault.shredFile(f) }
                                        selectedRamId?.let { id -> GhostCryptoVault.shredRamShot(id) }
                                        cleanupPreviewMedia()
                                        selectedFile = null
                                        selectedRamId = null
                                        statusMessage = "ФАЙЛ УНИЧТОЖЕН"
                                        refreshFiles()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = "Shred", modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("СТЕРЕТЬ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (isDecrypting) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = CyberGreen, strokeWidth = 2.dp)
                                }
                            } else if (decryptedVideoFile != null) {
                                val videoFile = decryptedVideoFile!!
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.ui.viewinterop.AndroidView(
                                        factory = { ctx ->
                                            android.widget.VideoView(ctx).apply {
                                                val controller = android.widget.MediaController(ctx)
                                                controller.setAnchorView(this)
                                                setMediaController(controller)
                                                setVideoPath(videoFile.absolutePath)
                                                setOnPreparedListener { mp ->
                                                    mp.isLooping = false
                                                    start()
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
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
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (selectedRamId != null) {
                                    "КОНТУР: ТОЛЬКО ОЗУ // 0 БАЙТ НА ФЛЕШ-ПАМЯТИ"
                                } else {
                                    "AES-256-GCM // PBKDF2 // БЕЗ EXIF И ГАЛЕРЕИ"
                                },
                                color = CyberMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            )
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
                        if (vaultFiles.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Shield, contentDescription = "Empty", tint = CyberMuted, modifier = Modifier.size(40.dp))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        if (unlockedProfile == GhostCryptoVault.VaultProfile.DECOY) "ЛОЖНЫЙ СХРОН ПУСТ" else "В СЕЙФЕ ПОКА НЕТ ФАЙЛОВ",
                                        color = CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(vaultFiles, key = { it.name }) { file ->
                                    val dateStr = remember(file) {
                                        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                                    }
                                    val sizeKb = remember(file) { file.length() / 1024 }
                                    val isVideo = remember(file) { GhostCryptoVault.isVideoFile(file) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .background(CyberSurface, RoundedCornerShape(6.dp))
                                            .border(1.dp, CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                selectedFile = file
                                                isDecrypting = true
                                                scope.launch {
                                                    try {
                                                        cleanupPreviewMedia()
                                                        if (isVideo) {
                                                            val vidTmp = withContext(Dispatchers.IO) {
                                                                GhostCryptoVault.decryptToEphemeralFile(context, file)
                                                            }
                                                            decryptedVideoFile = vidTmp
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Photo,
                                                contentDescription = if (isVideo) "Видео" else "Фото",
                                                tint = if (isVideo) CyberRed else CyberGreen,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = if (isVideo) "🎥 ${file.name}" else "📸 ${file.name}",
                                                    color = Color.White,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp
                                                )
                                                Text("$dateStr | $sizeKb КБ", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
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

            if (showWipeConfirm) {
                AlertDialog(
                    onDismissRequest = { showWipeConfirm = false },
                    title = { Text("УНИЧТОЖИТЬ ВСЕ КЛЮЧИ И СХРОН", color = CyberRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    text = { Text("Отзывает ключи TEE KeyStore, затирает монолит и файлы во всех контурах.", color = Color.White, fontSize = 12.sp) },
                    confirmButton = {
                        Button(
                            onClick = {
                                val count = GhostCryptoVault.cryptoShred(context)
                                MonoVaultEngine.cryptoShredMonolith(context)
                                showWipeConfirm = false
                                statusMessage = "УНИЧТОЖЕНО $count ЭЛЕМЕНТОВ И ВСЕ КЛЮЧИ"
                                refreshFiles()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberRed)
                        ) { Text("ДА, УНИЧТОЖИТЬ", color = Color.White) }
                    },
                    dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("ОТМЕНА", color = CyberMuted) } },
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
                    onDismissRequest = { auditDialogReport = null },
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
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Структура контейнера:", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            rep.segments.take(18).forEach { seg ->
                                Text("  $seg", color = Color.LightGray, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        }
                    },
                    confirmButton = {
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
                    },
                    dismissButton = {
                        TextButton(onClick = { auditDialogReport = null }) {
                            Text("ЗАКРЫТЬ", color = CyberMuted, fontFamily = FontFamily.Monospace)
                        }
                    },
                    containerColor = CyberSurface
                )
            }
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
