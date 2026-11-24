package com.example.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.crypto.GhostCryptoVault
import com.example.crypto.StegoCarrierEngine
import com.example.security.SecureClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays

private val StegoBg = Color(0xFF070B0E)
private val StegoCard = Color(0xFF10171E)
private val StegoBorder = Color(0xFF20303D)
private val StegoGreen = Color(0xFF00FF66)
private val StegoCyan = Color(0xFF00E5FF)
private val StegoAmber = Color(0xFFFFC107)
private val StegoRed = Color(0xFFFF3366)
private val StegoMuted = Color(0xFF8FA3B0)

@Composable
fun StegoCarrierDialog(
    sourceBitmap: Bitmap,
    onSavedToVault: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler { onDismiss() }

    var mode by remember { mutableStateOf("EMBED") } // "EMBED" | "EXTRACT"
    var secretNote by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var extractedText by remember { mutableStateOf<String?>(null) }
    var externalPickedPng by remember { mutableStateOf<ByteArray?>(null) }
    var externalPickedName by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val maxCapacity = remember(sourceBitmap) {
        StegoCarrierEngine.maxCapacityBytes(sourceBitmap.width, sourceBitmap.height)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null && bytes.isNotEmpty()) {
                externalPickedPng = bytes
                externalPickedName = "Выбран внешний файл (${bytes.size / 1024} КБ)"
                statusText = "Загружен внешний PNG-носитель. Введи пароль и жми «ИЗВЛЕЧЬ»."
                isError = false
            } else {
                statusText = "Не удалось прочитать выбранное фото"
                isError = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            externalPickedPng?.let { Arrays.fill(it, 0.toByte()) }
            externalPickedPng = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(StegoBg.copy(alpha = 0.96f))
                .zverDialogSafeBarsPadding()
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(StegoBg)
                    .border(1.dp, StegoCyan.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🕵️ СТЕГО-КОНТЕЙНЕР (PIXELKNOT)",
                            color = StegoCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "AES-256-GCM + RGB LSB в PNG без EXIF • ёмкость ${maxCapacity / 1024} КБ",
                            color = StegoMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp).testTag("stego_close_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = StegoMuted)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("EMBED" to "🔒 ЗАШИТЬ СЕКРЕТ", "EXTRACT" to "🔓 ВЫТАЩИТЬ").forEach { (key, title) ->
                        val sel = mode == key
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) StegoCyan else StegoCard)
                                .border(1.dp, if (sel) StegoCyan else StegoBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    mode = key
                                    statusText = null
                                    isError = false
                                }
                                .testTag("stego_mode_${key.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                color = if (sel) Color.Black else StegoMuted,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Пароль стего-контейнера (PBKDF2 120k)", fontSize = 11.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("stego_passphrase_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StegoCyan,
                            unfocusedBorderColor = StegoBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    if (mode == "EMBED") {
                        OutlinedTextField(
                            value = secretNote,
                            onValueChange = { secretNote = it },
                            label = { Text("Скрытый текст (ключи, координаты, заметка)", fontSize = 11.sp) },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth().testTag("stego_secret_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StegoGreen,
                                unfocusedBorderColor = StegoBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        Text(
                            text = "⚠️ ВАЖНО: пересылай получившийся PNG только как «Файл / Документ» без сжатия — мессенджеры при фото-сжатии затирают LSB-пиксели.",
                            color = StegoAmber,
                            fontSize = 10.sp
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (isBusy) return@Button
                                    isBusy = true
                                    statusText = null
                                    val passChars = passphrase.toCharArray()
                                    val txt = secretNote
                                    scope.launch {
                                        try {
                                            val result = withContext(Dispatchers.Default) {
                                                StegoCarrierEngine.embedSecretInBitmap(sourceBitmap, txt, passChars)
                                            }
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    GhostCryptoVault.encryptBytesToVault(
                                                        context = context,
                                                        plainBytes = result.pngBytes,
                                                        prefix = GhostCryptoVault.MEDIA_PHOTO
                                                    )
                                                }
                                            } finally {
                                                result.pngBytes.fill(0)
                                                if (!result.stegoBitmap.isRecycled) result.stegoBitmap.recycle()
                                            }
                                            secretNote = ""
                                            onSavedToVault("✅ СТЕГО-PNG СОХРАНЁН В СЕЙФ (${result.payloadBytesCount} Б внутри)")
                                        } catch (e: Exception) {
                                            isError = true
                                            statusText = e.message ?: "Ошибка стего-упаковки"
                                        } finally {
                                            isBusy = false
                                        }
                                    }
                                },
                                enabled = !isBusy && secretNote.isNotEmpty() && passphrase.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008F39)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(48.dp).testTag("stego_save_vault_btn")
                            ) {
                                Icon(Icons.Default.EnhancedEncryption, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("В СЕЙФ (.GCF)", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }

                            Button(
                                onClick = {
                                    if (isBusy) return@Button
                                    isBusy = true
                                    statusText = null
                                    val passChars = passphrase.toCharArray()
                                    val txt = secretNote
                                    scope.launch {
                                        try {
                                            val result = withContext(Dispatchers.Default) {
                                                StegoCarrierEngine.embedSecretInBitmap(sourceBitmap, txt, passChars)
                                            }
                                            val tmp = try {
                                                withContext(Dispatchers.IO) {
                                                    GhostCryptoVault.createEphemeralShareBytes(context, result.pngBytes, "png")
                                                }
                                            } finally {
                                                result.pngBytes.fill(0)
                                                if (!result.stegoBitmap.isRecycled) result.stegoBitmap.recycle()
                                            }
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmp)
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "image/png"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Отправить PNG как файл"))
                                            isError = false
                                            statusText = "✅ Стего-PNG готов к отправке как файл (0 EXIF)"
                                        } catch (e: Exception) {
                                            isError = true
                                            statusText = e.message ?: "Ошибка"
                                        } finally {
                                            isBusy = false
                                        }
                                    }
                                },
                                enabled = !isBusy && secretNote.isNotEmpty() && passphrase.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = StegoCyan),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(48.dp).testTag("stego_share_png_btn")
                            ) {
                                Text("ОТПРАВИТЬ PNG", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp).testTag("stego_pick_external_btn")
                        ) {
                            Text(
                                text = externalPickedName ?: "📁 ВЫБРАТЬ ВНЕШНИЙ PNG (PHOTO PICKER)",
                                color = StegoCyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }

                        Button(
                            onClick = {
                                if (isBusy) return@Button
                                isBusy = true
                                statusText = null
                                extractedText = null
                                val passChars = passphrase.toCharArray()
                                val extBytes = externalPickedPng
                                scope.launch {
                                    try {
                                        val secret = withContext(Dispatchers.Default) {
                                            if (extBytes != null) {
                                                StegoCarrierEngine.extractSecretFromPngBytes(extBytes, passChars)
                                            } else {
                                                StegoCarrierEngine.extractSecretFromBitmap(sourceBitmap, passChars)
                                            }
                                        }
                                        extractedText = secret
                                        isError = false
                                        statusText = "✅ Секрет расшифрован (${secret.length} симв.)"
                                    } catch (e: Exception) {
                                        isError = true
                                        statusText = e.message ?: "Контейнер не найден или неверный пароль"
                                    } finally {
                                        isBusy = false
                                    }
                                }
                            },
                            enabled = !isBusy && passphrase.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = StegoCyan),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("stego_extract_btn")
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ИЗВЛЕЧЬ И РАСШИФРОВАТЬ", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        extractedText?.let { secret ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(StegoCard, RoundedCornerShape(10.dp))
                                    .border(1.dp, StegoGreen.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Text("РАСШИФРОВАННЫЙ СЕКРЕТ:", color = StegoGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(secret, color = Color.White, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(
                                    onClick = {
                                        SecureClipboard.copySensitive(context, "Zver Stego Secret", secret)
                                        statusText = "Скопировано в буфер (очистка 45с)"
                                    }
                                ) {
                                    Text("КОПИРОВАТЬ", color = StegoCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    statusText?.let { msg ->
                        Text(
                            text = msg,
                            color = if (isError) StegoRed else StegoGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
