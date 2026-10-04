package com.example.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.assist.ByokManager

private val CyberBlack = Color(0xFF070B0E)
private val CyberGreen = Color(0xFF00FF66)
private val CyberGreenDark = Color(0xFF008F39)
private val CyberRed = Color(0xFFFF1744)
private val CyberMuted = Color(0xFF8FA3B0)
private val CyberSurface = Color(0xFF111820)
private val CyberBorder = Color(0xFF223240)
private val NeonCyan = Color(0xFF00E5FF)
private val NeonYellow = Color(0xFFFFEA00)

enum class SettingsTab(val titleRu: String) {
    AI_VIKA("🧠 Нейросеть (Вика)"),
    MODES_GUIDE("📸 Режимы съёмки"),
    TERMUX_AUTOMATION("⚡ Termux & Скрипты")
}

@Composable
fun GhostSettingsDialog(
    onDismiss: () -> Unit,
    onStatusMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var selectedTab by remember { mutableStateOf(SettingsTab.AI_VIKA) }

    // AI States
    var apiKeyInput by remember { mutableStateOf("") }
    var hasKeyConfigured by remember { mutableStateOf(ByokManager.hasKey(context)) }
    var currentPrompt by remember { mutableStateOf(ByokManager.getCustomPrompt(context)) }
    var selectedModel by remember { mutableStateOf(ByokManager.getModelName(context)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBlack.copy(alpha = 0.95f))
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, CyberGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = CyberGreen, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ЦЕНТР УПРАВЛЕНИЯ ZVER",
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = CyberMuted, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Navigation Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SettingsTab.values().forEach { tab ->
                        val isSelected = selectedTab == tab
                        Text(
                            text = tab.titleRu,
                            color = if (isSelected) Color.Black else CyberMuted,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) CyberGreen else CyberSurface)
                                .border(1.dp, if (isSelected) CyberGreen else CyberBorder, RoundedCornerShape(6.dp))
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = CyberBorder, thickness = 1.dp)

                // Tab Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    when (selectedTab) {
                        SettingsTab.AI_VIKA -> {
                            // Section 1: API Key
                            Text("1. КЛЮЧ API GEMINI (BYOK)", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Ключ шифруется в аппаратный чип KeyStore устройства. Используется только в изолированном фоновом процессе для анализа кадров.",
                                color = CyberMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text(if (hasKeyConfigured) "Ключ уже сохранён в KeyStore (введи новый для смены)" else "Вставь API-ключ Gemini (AI Studio)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberGreen,
                                    unfocusedBorderColor = CyberBorder,
                                    focusedTextColor = Color.White
                                )
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (apiKeyInput.isNotBlank()) {
                                            ByokManager.saveKey(context, apiKeyInput.toCharArray())
                                            hasKeyConfigured = true
                                            apiKeyInput = ""
                                            onStatusMessage("КЛЮЧ GEMINI СОХРАНЁН В KEYSTORE")
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("СОХРАНИТЬ КЛЮЧ", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }

                                if (hasKeyConfigured) {
                                    Button(
                                        onClick = {
                                            ByokManager.deleteKey(context)
                                            hasKeyConfigured = false
                                            onStatusMessage("КЛЮЧ УДАЛЁН ИЗ KEYSTORE")
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberRed.copy(alpha = 0.8f)),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("УДАЛИТЬ КЛЮЧ", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Section 2: Model Selection
                            Text("2. ВЫБОР МОДЕЛИ НЕЙРОСЕТИ", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-2.5-flash", "gemini-1.5-pro").forEach { model ->
                                    val isCur = selectedModel == model
                                    Text(
                                        text = model,
                                        color = if (isCur) CyberGreen else CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isCur) CyberGreen.copy(alpha = 0.2f) else CyberSurface)
                                            .border(1.dp, if (isCur) CyberGreen else CyberBorder, RoundedCornerShape(4.dp))
                                            .clickable {
                                                selectedModel = model
                                                ByokManager.saveModelName(context, model)
                                                onStatusMessage("МОДЕЛЬ: $model")
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Section 3: Prompt & Personality Presets
                            Text("3. СИСТЕМНЫЙ ПРОМПТ (ХАРАКТЕР И ИНСТРУКЦИЯ)", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Нажми на готовый пресет или напиши свои правила для напарницы:",
                                color = CyberMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            // Preset buttons
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "Вика-напарница" to ByokManager.DEFAULT_PROMPT,
                                    "OSINT-разведка" to ByokManager.PROMPT_OSINT,
                                    "Чтение документов" to ByokManager.PROMPT_OCR,
                                    "Анализ улик" to ByokManager.PROMPT_FORENSIC
                                ).forEach { (title, text) ->
                                    Text(
                                        text = title,
                                        color = NeonCyan,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(NeonCyan.copy(alpha = 0.15f))
                                            .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                            .clickable {
                                                currentPrompt = text
                                                ByokManager.saveCustomPrompt(context, text)
                                                onStatusMessage("ПРОМПТ УСТАНОВЛЕН: $title")
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = currentPrompt,
                                onValueChange = {
                                    currentPrompt = it
                                    ByokManager.saveCustomPrompt(context, it)
                                },
                                label = { Text("Текст системного промпта", fontSize = 11.sp) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberGreen,
                                    unfocusedBorderColor = CyberBorder,
                                    focusedTextColor = Color.White
                                )
                            )
                        }

                        SettingsTab.MODES_GUIDE -> {
                            Text("КАК РАБОТАЮТ РЕЖИМЫ СЪЁМКИ (ПРОСТЫМИ СЛОВАМИ)", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(10.dp))

                            ModeExplanationCard(
                                title = "☀️ ДНЕВНОЙ РЕЖИМ (ПО УМОЛЧАНИЮ)",
                                desc = "Основная матрица 50МП работает на полной детализации. Фокусировка мгновенная, стабилизация гироскопа и антисмаз включены."
                            )
                            ModeExplanationCard(
                                title = "🌙 НОЧНОЙ РЕЖИМ (СВЕТОСИЛА)",
                                desc = "Выдержка и экспозиция увеличиваются на +1 EV. Затвор делает снимок только в момент полного замирания рук (гироскоп < 0.05 рад/с), чтобы кадр не размазался."
                            )
                            ModeExplanationCard(
                                title = "📄 ДОКУМЕНТЫ (ЛАЗЕРНЫЙ УРОВЕНЬ)",
                                desc = "Включает прицел параллельности. Когда телефон лежит строго параллельно столу, круг загорается зелёным. Текст получается без трапеций и искажений."
                            )
                            ModeExplanationCard(
                                title = "🔍 МАКРО (УЛИКИ И МЕЛКИЙ ТЕКСТ)",
                                desc = "Минимальная дистанция оптики Redmi 13C — от 10 см. Включай зум 2x для фиксации номеров, пломб, замков и печатей."
                            )
                            ModeExplanationCard(
                                title = "🎙️ ГОЛОСОВОЙ РЕЖИМ (БЕЗ РУК)",
                                desc = "Командуй голосом: скажи «Снять» или «Фотка», «Приблизить», «Сброс», «Фонарик». Работает без интернета через локальный микрофон!"
                            )
                            ModeExplanationCard(
                                title = "🕶️ СТЕЛС / FODCam (БЕЗ ПАЛЕВА)",
                                desc = "Яркость экрана падает в 0%. Можно включить фон часов (как на заблокированном телефоне) или браузера. Снимок делается по кнопкам громкости или тапу!"
                            )
                        }

                        SettingsTab.TERMUX_AUTOMATION -> {
                            Text("СИМБИОЗ: TERMUX, SHIZUKU И MACRODROID", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Прямая связь на уровне системы Android. Zver Camera слушает широковещательные интенты и прямой протокол zver://",
                                color = CyberMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            // Live App Ecosystem Detection
                            val isTermuxInstalled = remember {
                                try { context.packageManager.getPackageInfo("com.termux", 0); true } catch (_: Exception) { false }
                            }
                            val isTermuxApiInstalled = remember {
                                try { context.packageManager.getPackageInfo("com.termux.api", 0); true } catch (_: Exception) { false }
                            }
                            val isShizukuInstalled = remember {
                                try { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true } catch (_: Exception) { false }
                            }
                            val isMacroDroidInstalled = remember {
                                try { context.packageManager.getPackageInfo("com.arlosoft.macrodroid", 0); true } catch (_: Exception) { false }
                            }

                            Text("СТАТУС ПОДКЛЮЧЕНИЯ В СИСТЕМЕ:", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                AppBadge("Termux", isTermuxInstalled) {
                                    val intent = context.packageManager.getLaunchIntentForPackage("com.termux")
                                    if (intent != null) context.startActivity(intent)
                                }
                                AppBadge("Termux:API", isTermuxApiInstalled) {
                                    val intent = context.packageManager.getLaunchIntentForPackage("com.termux.api")
                                    if (intent != null) context.startActivity(intent)
                                }
                                AppBadge("Shizuku", isShizukuInstalled) {
                                    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                    if (intent != null) context.startActivity(intent)
                                }
                                AppBadge("MacroDroid", isMacroDroidInstalled) {
                                    val intent = context.packageManager.getLaunchIntentForPackage("com.arlosoft.macrodroid")
                                    if (intent != null) context.startActivity(intent)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text("БЫСТРЫЙ ВЫЗОВ (DEEP-LINK URI):", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                            AutomationCommandCard(
                                label = "Прямой запуск из Termux (одной строкой):",
                                cmd = "am start -d zver://capture",
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString("am start -d zver://capture"))
                                    onStatusMessage("СКОПИРОВАНО: am start -d zver://capture")
                                }
                            )

                            AutomationCommandCard(
                                label = "Широковещательный интент (MacroDroid / Termux):",
                                cmd = "am broadcast -a com.example.camera.TRIGGER_CAPTURE",
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString("am broadcast -a com.example.camera.TRIGGER_CAPTURE"))
                                    onStatusMessage("СКОПИРОВАН INTENT")
                                }
                            )

                            AutomationCommandCard(
                                label = "Переключение фонарика из скрипта:",
                                cmd = "am broadcast -a com.example.camera.TOGGLE_TORCH",
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString("am broadcast -a com.example.camera.TOGGLE_TORCH"))
                                    onStatusMessage("СКОПИРОВАН INTENT ФОНАРИКА")
                                }
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Test Impulse Button
                            Button(
                                onClick = {
                                    val testIntent = android.content.Intent("com.example.camera.TRIGGER_CAPTURE")
                                    context.sendBroadcast(testIntent)
                                    onStatusMessage("⚡ ТЕСТОВЫЙ ИМПУЛЬС ОТПРАВЛЕН В КАМЕРУ!")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = "Тест", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ОТПРАВИТЬ ТЕСТОВЫЙ ИМПУЛЬС (СДЕЛАТЬ КАДР)", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ЗАКРЫТЬ НАСТРОЙКИ", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ModeExplanationCard(title: String, desc: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(CyberSurface, RoundedCornerShape(8.dp))
            .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(title, color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(3.dp))
        Text(desc, color = Color.White, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun AutomationCommandCard(label: String, cmd: String, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(CyberSurface, RoundedCornerShape(8.dp))
            .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(label, color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(cmd, color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = CyberGreen, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun AppBadge(name: String, isInstalled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isInstalled) CyberGreen.copy(alpha = 0.15f) else CyberSurface)
            .border(1.dp, if (isInstalled) CyberGreen.copy(alpha = 0.5f) else CyberBorder, RoundedCornerShape(6.dp))
            .clickable(enabled = isInstalled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (isInstalled) CyberGreen else CyberRed)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(name, color = if (isInstalled) Color.White else CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

