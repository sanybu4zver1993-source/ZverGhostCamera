package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.crypto.SecretSplitEngine
import com.example.security.SecureClipboard

private val SplitBlack = Color(0xFF070B0E)
private val SplitSurface = Color(0xFF111820)
private val SplitBorder = Color(0xFF223240)
private val SplitGreen = Color(0xFF00FF66)
private val SplitCyan = Color(0xFF00E5FF)
private val SplitAmber = Color(0xFFFFC107)
private val SplitRed = Color(0xFFFF3366)
private val SplitMuted = Color(0xFF8FA3B0)

@Composable
fun SecretSplitDialog(
    onStatusMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isCreateMode by remember { mutableStateOf(true) }
    var secretInput by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var schemePair by remember { mutableStateOf(2 to 3) } // (threshold, total)
    var generatedShares by remember { mutableStateOf<List<SecretSplitEngine.SecretShare>>(emptyList()) }

    var combineInput by remember { mutableStateOf("") }
    var recoveredSecret by remember { mutableStateOf<String?>(null) }
    var showRecovered by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SplitBlack.copy(alpha = 0.96f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, SplitCyan.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = "Split Key", tint = SplitAmber, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "СПЛИТ-КЛЮЧ (ШАМИР GF256)",
                            color = SplitAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp).testTag("split_key_close_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = SplitMuted)
                    }
                }

                Text(
                    text = "Пороговое разделение мастер-пароля .ZVB или стего-ключа: 1 доля не даёт ничего, любые K долей восстанавливают точный секрет.",
                    color = SplitMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "✂️ РАЗБИТЬ КЛЮЧ",
                        color = if (isCreateMode) Color.Black else SplitMuted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isCreateMode) SplitCyan else SplitSurface)
                            .border(1.dp, if (isCreateMode) SplitCyan else SplitBorder, RoundedCornerShape(6.dp))
                            .clickable {
                                isCreateMode = true
                                errorMsg = null
                            }
                            .padding(vertical = 8.dp, horizontal = 10.dp)
                            .testTag("split_mode_create")
                    )
                    Text(
                        text = "🧩 СОБРАТЬ ИЗ ДОЛЕЙ",
                        color = if (!isCreateMode) Color.Black else SplitMuted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isCreateMode) SplitGreen else SplitSurface)
                            .border(1.dp, if (!isCreateMode) SplitGreen else SplitBorder, RoundedCornerShape(6.dp))
                            .clickable {
                                isCreateMode = false
                                errorMsg = null
                            }
                            .padding(vertical = 8.dp, horizontal = 10.dp)
                            .testTag("split_mode_combine")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isCreateMode) {
                        OutlinedTextField(
                            value = secretInput,
                            onValueChange = { secretInput = it },
                            label = { Text("Пароль .ZVB / стего-фраза", color = SplitMuted, fontSize = 11.sp) },
                            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showSecret = !showSecret }) {
                                    Icon(
                                        imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Показать/скрыть",
                                        tint = SplitCyan
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("split_secret_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SplitCyan,
                                unfocusedBorderColor = SplitBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(2 to 3, 2 to 4, 3 to 5).forEach { (k, n) ->
                                val selected = schemePair == (k to n)
                                Text(
                                    text = "Схема $k из $n",
                                    color = if (selected) Color.Black else SplitCyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selected) SplitAmber else SplitSurface)
                                        .border(1.dp, if (selected) SplitAmber else SplitBorder, RoundedCornerShape(6.dp))
                                        .clickable { schemePair = k to n }
                                        .padding(vertical = 8.dp, horizontal = 8.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                errorMsg = null
                                try {
                                    generatedShares = SecretSplitEngine.splitSecret(
                                        secretText = secretInput,
                                        totalShares = schemePair.second,
                                        threshold = schemePair.first
                                    )
                                    onStatusMessage("🔑 СОЗДАНО ДОЛЕЙ: ${generatedShares.size} (ПОРОГ ${schemePair.first})")
                                } catch (e: Exception) {
                                    errorMsg = e.message ?: "Ошибка разделения ключа"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SplitCyan),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("split_generate_btn")
                        ) {
                            Text(
                                text = "СГЕНЕРИРОВАТЬ ${schemePair.second} ДОЛЕЙ (ПОРОГ ${schemePair.first})",
                                color = Color.Black,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }

                        if (generatedShares.isNotEmpty()) {
                            Text(
                                text = "ДОЛИ (СЛУЧАЙНЫЙ ПОРЯДОК ХРАНЕНИЯ В РАЗНЫХ МЕСТАХ):",
                                color = SplitGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                            generatedShares.forEach { share ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SplitSurface)
                                        .border(1.dp, SplitGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = "ДОЛЯ #${share.index} (нужно ${share.threshold})",
                                            color = SplitAmber,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                        Text(
                                            text = share.encoded,
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            SecureClipboard.copySensitive(context, "Zver Key Share #${share.index}", share.encoded)
                                            onStatusMessage("ДОЛЯ #${share.index} СКОПИРОВАНА (45с)")
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать долю", tint = SplitGreen)
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = combineInput,
                            onValueChange = { combineInput = it },
                            label = { Text("Вставь 2+ доли (по одной в строке ZVS1-...)", color = SplitMuted, fontSize = 11.sp) },
                            minLines = 4,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth().testTag("split_combine_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SplitGreen,
                                unfocusedBorderColor = SplitBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        Button(
                            onClick = {
                                errorMsg = null
                                recoveredSecret = null
                                try {
                                    val lines = combineInput.lines().map { it.trim() }.filter { it.isNotEmpty() }
                                    recoveredSecret = SecretSplitEngine.combineShares(lines)
                                    onStatusMessage("✅ СЕКРЕТ ВОССТАНОВЛЕН")
                                } catch (e: Exception) {
                                    errorMsg = e.message ?: "Ошибка сборки секрета"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SplitGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("split_reconstruct_btn")
                        ) {
                            Text(
                                text = "ВОССТАНОВИТЬ СЕКРЕТ",
                                color = Color.Black,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }

                        recoveredSecret?.let { rec ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SplitSurface)
                                    .border(1.dp, SplitGreen, RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🔓 ВОССТАНОВЛЕННЫЙ СЕКРЕТ:",
                                        color = SplitGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                    Row {
                                        IconButton(onClick = { showRecovered = !showRecovered }, modifier = Modifier.size(36.dp)) {
                                            Icon(
                                                imageVector = if (showRecovered) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = "Показать/скрыть",
                                                tint = SplitCyan,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                SecureClipboard.copySensitive(context, "Zver Recovered Secret", rec)
                                                onStatusMessage("СЕКРЕТ СКОПИРОВАН (45с)")
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = SplitGreen, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                Text(
                                    text = if (showRecovered) rec else "•".repeat(rec.length.coerceIn(6, 24)),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    errorMsg?.let { err ->
                        Text(
                            text = "⚠ $err",
                            color = SplitRed,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
