package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.crypto.GhostCryptoVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val CyberBlack = Color(0xFF090D10)
private val CyberSurface = Color(0xFF131A21)
private val CyberBorder = Color(0xFF223240)
private val CyberGreen = Color(0xFF00FF66)
private val CyberRed = Color(0xFFFF3366)
private val CyberMuted = Color(0xFF8A9BA8)

@Composable
fun GhostVaultDialog(
    onDismiss: () -> Unit,
    onVaultUpdated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isAlreadyConfigured = remember { GhostCryptoVault.isConfigured(context) }
    var inSetupFlow by remember { mutableStateOf(!isAlreadyConfigured) }
    var setupStep by remember { mutableStateOf(1) } // 1: Main PIN, 2: Decoy PIN
    var setupMainPin by remember { mutableStateOf(charArrayOf()) }

    var unlockedProfile by remember { mutableStateOf<GhostCryptoVault.VaultProfile?>(null) }
    var enteredPinChars by remember { mutableStateOf(charArrayOf()) }
    var pinError by remember { mutableStateOf(false) }

    var vaultFiles by remember { mutableStateOf(emptyList<File>()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var decryptedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isDecrypting by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshFiles() {
        vaultFiles = GhostCryptoVault.listVaultFiles(context)
        onVaultUpdated()
    }

    Dialog(
        onDismissRequest = {
            decryptedBitmap?.recycle()
            decryptedBitmap = null
            GhostCryptoVault.lockSession()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBlack.copy(alpha = 0.97f))
                .padding(16.dp)
        ) {
            // STEP A: INITIAL PBKDF2 SETUP (First run)
            if (inSetupFlow) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .align(Alignment.Center)
                        .background(CyberSurface, RoundedCornerShape(14.dp))
                        .border(1.dp, CyberGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Setup",
                        tint = CyberGreen,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (setupStep == 1) "ЗАДАЙ МАСТЕР-ПИН СЕЙФА" else "ЛОЖНЫЙ ПИН (ПРИКРЫТИЕ)",
                        color = CyberGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (setupStep == 1) "Генерирует 256-битный ключ PBKDF2 для НАСТОЯЩЕГО сейфа" else "Открывает пустой/фейковый раздел при досмотре",
                        color = CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // PIN Dots
                    PinDotsRow(count = enteredPinChars.size, max = 6)

                    Spacer(modifier = Modifier.height(14.dp))

                    SecureKeypad(
                        onKeyPressed = { char ->
                            if (enteredPinChars.size < 6) {
                                enteredPinChars = enteredPinChars + char
                            }
                        },
                        onClear = {
                            enteredPinChars.fill('0')
                            enteredPinChars = charArrayOf()
                        },
                        onConfirm = {
                            if (enteredPinChars.size >= 4) {
                                if (setupStep == 1) {
                                    setupMainPin = enteredPinChars.clone()
                                    enteredPinChars.fill('0')
                                    enteredPinChars = charArrayOf()
                                    setupStep = 2
                                } else {
                                    // Complete setup
                                    val decoyClone = if (enteredPinChars.size >= 4) enteredPinChars.clone() else null
                                    GhostCryptoVault.setupVaultPins(context, setupMainPin, decoyClone)
                                    enteredPinChars.fill('0')
                                    enteredPinChars = charArrayOf()
                                    inSetupFlow = false
                                    unlockedProfile = GhostCryptoVault.VaultProfile.MAIN
                                    refreshFiles()
                                }
                            }
                        }
                    )

                    if (setupStep == 2) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            // Skip decoy setup
                            GhostCryptoVault.setupVaultPins(context, setupMainPin, null)
                            enteredPinChars.fill('0')
                            enteredPinChars = charArrayOf()
                            inSetupFlow = false
                            unlockedProfile = GhostCryptoVault.VaultProfile.MAIN
                            refreshFiles()
                        }) {
                            Text("ПРОПУСТИТЬ ЛОЖНЫЙ ПИН", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
            // STEP B: UNLOCK PIN CHALLENGE
            else if (unlockedProfile == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .align(Alignment.Center)
                        .background(CyberSurface, RoundedCornerShape(14.dp))
                        .border(1.dp, CyberGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock",
                        tint = CyberGreen,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ВВЕДИ ПИН СЕЙФА",
                        color = CyberGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "БЕЗ СЛЕДОВ В ОЗУ // PBKDF2 КЛЮЧ",
                        color = CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    PinDotsRow(count = enteredPinChars.size, max = 6)

                    if (pinError) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "ОШИБКА: НЕВЕРНЫЙ ПИН",
                            color = CyberRed,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    SecureKeypad(
                        onKeyPressed = { char ->
                            pinError = false
                            if (enteredPinChars.size < 6) {
                                enteredPinChars = enteredPinChars + char
                            }
                        },
                        onClear = {
                            enteredPinChars.fill('0')
                            enteredPinChars = charArrayOf()
                        },
                        onConfirm = {
                            if (enteredPinChars.isNotEmpty()) {
                                val profile = GhostCryptoVault.unlockWithPin(context, enteredPinChars.clone())
                                enteredPinChars.fill('0')
                                enteredPinChars = charArrayOf()
                                if (profile != null) {
                                    unlockedProfile = profile
                                    refreshFiles()
                                } else {
                                    pinError = true
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
                        .padding(14.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = "Unlocked",
                                tint = CyberGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (unlockedProfile == GhostCryptoVault.VaultProfile.MAIN) "СХРОН: БОЕВОЙ (ОСНОВНОЙ)" else "СХРОН: ЛОЖНЫЙ (ПРИКРЫТИЕ)",
                                color = CyberGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        IconButton(onClick = {
                            decryptedBitmap?.recycle()
                            decryptedBitmap = null
                            GhostCryptoVault.lockSession()
                            onDismiss()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = CyberMuted
                            )
                        }
                    }

                    // Subheader
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val totalSizeKb = vaultFiles.sumOf { it.length() } / 1024
                        Text(
                            text = "ФАЙЛОВ: ${vaultFiles.size} | РАЗМЕР: ${totalSizeKb} КБ",
                            color = CyberMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "PBKDF2-SHA256 // AES-256",
                            color = CyberGreen.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    HorizontalDivider(color = CyberBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (selectedFile != null) {
                        // Image Viewer
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "<- НАЗАД В СПИСОК",
                                    color = CyberGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clickable {
                                            decryptedBitmap?.recycle()
                                            decryptedBitmap = null
                                            selectedFile = null
                                        }
                                        .padding(4.dp)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        onClick = {
                                            selectedFile?.let { f ->
                                                try {
                                                    val shareTmp = GhostCryptoVault.createEphemeralShareFile(context, f)
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        shareTmp
                                                    )
                                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                        type = "image/jpeg"
                                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Передать фото"))
                                                    statusMessage = "ВРЕМЕННЫЙ ФАЙЛ: УДАЛИТСЯ ПОСЛЕ ОТПРАВКИ"
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
                                        Text("ПОДЕЛИТЬСЯ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberGreen)
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Button(
                                        onClick = {
                                            selectedFile?.let { f ->
                                                GhostCryptoVault.shredFile(f)
                                                decryptedBitmap?.recycle()
                                                decryptedBitmap = null
                                                selectedFile = null
                                                statusMessage = "ФАЙЛ УНИЧТОЖЕН ИЗ СЕЙФА"
                                                refreshFiles()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
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

                                Spacer(modifier = Modifier.height(6.dp))

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CyberSurface, RoundedCornerShape(6.dp))
                                        .padding(6.dp)
                                ) {
                                    Text(
                                        text = "СТАТУС: 100% ЧИСТО // БЕЗ СЛЕДОВ",
                                        color = CyberGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "EXIF СТЁРТ // БЕЗ ЗАПИСИ В ОБЩУЮ ГАЛЕРЕЮ // ТОЛЬКО ОЗУ",
                                        color = CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    } else {
                        // File List
                        if (vaultFiles.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Shield, contentDescription = "Empty", tint = CyberMuted, modifier = Modifier.size(40.dp))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        if (unlockedProfile == GhostCryptoVault.VaultProfile.DECOY) "ЛОЖНЫЙ СХРОН ПУСТ" else "В СЕЙФЕ ПОКА НЕТ ФОТО",
                                        color = CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = if (unlockedProfile == GhostCryptoVault.VaultProfile.DECOY) "Сделай пару снимков цветов или улицы для алиби." else "Все фото шифруются ключом PBKDF2 в закрытый контейнер.",
                                        color = CyberMuted.copy(alpha = 0.7f),
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(vaultFiles) { file ->
                                    val dateStr = remember(file) {
                                        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                                    }
                                    val sizeKb = remember(file) { file.length() / 1024 }

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
                                                        val bmp = withContext(Dispatchers.IO) {
                                                            GhostCryptoVault.decryptToBitmap(context, file)
                                                        }
                                                        decryptedBitmap?.recycle()
                                                        decryptedBitmap = bmp
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
                                        Column {
                                            Text(file.name, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                            Text("$dateStr | $sizeKb KB", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                        }
                                        Icon(Icons.Default.Visibility, contentDescription = "View", tint = CyberGreen, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Crypto-shred button
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
                    text = { Text("Мгновенно перезаписывает контейнер шумом, удаляет ключи TEE Keystore и стирает все файлы в обоих сейфах. Восстановление невозможно.", color = Color.White, fontSize = 12.sp) },
                    confirmButton = {
                        Button(
                            onClick = {
                                val count = GhostCryptoVault.cryptoShred(context)
                                com.example.crypto.MonoVaultEngine.cryptoShredMonolith(context)
                                showWipeConfirm = false
                                statusMessage = "УНИЧТОЖЕНО $count ФАЙЛОВ И ВСЕ КЛЮЧИ"
                                refreshFiles()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberRed)
                        ) {
                            Text("ДА, УНИЧТОЖИТЬ", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showWipeConfirm = false }) { Text("ОТМЕНА", color = CyberMuted) }
                    },
                    containerColor = CyberSurface
                )
            }
        }
    }
}

@Composable
private fun PinDotsRow(count: Int, max: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

/**
 * On-screen isolated keypad:
 * Prevents clipboard leaks, ignores system autofill, operates strictly with Char events.
 */
@Composable
private fun SecureKeypad(
    onKeyPressed: (Char) -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit
) {
    val layout = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf('C', '0', 'V')
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in layout) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(vertical = 3.dp)
            ) {
                for (key in row) {
                    Button(
                        onClick = {
                            when (key) {
                                'C' -> onClear()
                                'V' -> onConfirm()
                                else -> onKeyPressed(key)
                            }
                        },
                        modifier = Modifier.size(60.dp, 44.dp),
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
