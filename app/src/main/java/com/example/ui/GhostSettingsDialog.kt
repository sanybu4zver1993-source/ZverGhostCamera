package com.example.ui

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.testTag
import com.example.assist.ByokManager
import com.example.camera.CameraDiagnostics
import com.example.camera.ExifStripper
import com.example.camera.PhotoIqEngine
import com.example.camera.TimeLapseRecorder
import com.example.crypto.GhostCryptoVault
import com.example.security.CamouflageManager
import com.example.security.SecureClipboard
import com.example.security.ZverPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    SECURITY_CODECS("🛡️ Защита и Кодеки"),
    AI_VIKA("🧠 Нейросеть (Вика)"),
    MODES_GUIDE("📸 Режимы съёмки"),
    TERMUX_AUTOMATION("⚡ Termux & Скрипты")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GhostSettingsDialog(
    onDismiss: () -> Unit,
    onStatusMessage: (String) -> Unit,
    onSelectMode: (String) -> Unit = {},
    onOpenPinSetup: () -> Unit = {},
    onOpenVikaSheet: () -> Unit = {},
    onOpenSelfTest: () -> Unit = {},
    onCamouflageChanged: (CamouflageManager.CamouflageMode) -> Unit = {},
    boundUseCasesSummary: String = "N/A"
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ByokManager.syncFromPrefs(context)
    }
    var selectedTab by remember { mutableStateOf(SettingsTab.SECURITY_CODECS) }

    // Shared Reactive AI States (Single Source of Truth in ByokManager)
    var apiKeyInput by remember { mutableStateOf("") }
    val hasKeyConfigured by ByokManager.hasKeyFlow.collectAsState()
    val currentPrompt by ByokManager.customPromptFlow.collectAsState()
    val selectedModel by ByokManager.selectedModelFlow.collectAsState()
    val discoveredModels by ByokManager.discoveredModelsFlow.collectAsState()
    val isFetchingModels by ByokManager.isFetchingModelsFlow.collectAsState()
    val modelFetchStatus by ByokManager.modelFetchStatusFlow.collectAsState()
    val httpLogs by ByokManager.httpLogsFlow.collectAsState()

    // Security & Codec States
    var preset by remember { mutableStateOf(ZverPrefs.getCompressionPreset(context)) }
    var antiPrnu by remember { mutableStateOf(ZverPrefs.isAntiPrnuEnabled(context)) }
    var ramOnly by remember { mutableStateOf(ZverPrefs.isRamOnlyMode(context)) }
    var muteVideoAudio by remember { mutableStateOf(ZverPrefs.isMuteVideoAudio(context)) }
    var pinScramble by remember { mutableStateOf(ZverPrefs.isPinScrambleEnabled(context)) }
    var snatchLock by remember { mutableStateOf(ZverPrefs.isSnatchProtectionEnabled(context)) }
    var flipDisguise by remember { mutableStateOf(ZverPrefs.isFlipToDisguiseEnabled(context)) }
    var screenRecBlock by remember { mutableStateOf(ZverPrefs.isScreenRecBlockEnabled(context)) }
    var maxFailWipe by remember { mutableStateOf(ZverPrefs.getMaxFailedPinWipe(context)) }
    var deadManDays by remember { mutableStateOf(ZverPrefs.getDeadManDays(context)) }
    var photoIqProfile by remember { mutableStateOf(ZverPrefs.getPhotoIqProfile(context)) }
    var zoomIqEnabled by remember { mutableStateOf(ZverPrefs.isZoomIqEnabled(context)) }
    var zebraThreshold by remember { mutableStateOf(ZverPrefs.getZebraThreshold(context)) }
    var smartCompression by remember { mutableStateOf(ZverPrefs.isSmartCompressionEnabled(context)) }
    var gyroEisEnabled by remember { mutableStateOf(ZverPrefs.isGyroEisEnabled(context)) }
    var timeLapseIntervalMs by remember { mutableStateOf(ZverPrefs.getTimeLapseIntervalMs(context)) }
    var lastCrashBreadcrumb by remember { mutableStateOf(ZverPrefs.getLastCrashBreadcrumb(context)) }
    var currentCamo by remember { mutableStateOf(CamouflageManager.getCurrentCamouflage(context)) }
    var volumeAction by remember { mutableStateOf(ZverPrefs.getVolumeKeyAction(context)) }
    val transportConfig by ByokManager.transportConfigFlow.collectAsState()
    val strongBoxCapable = remember { ByokManager.isStrongBoxSupported(context) }
    val cameraDiagReport = remember(boundUseCasesSummary) {
        CameraDiagnostics.buildTextReport(context, boundUseCasesSummary)
    }

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
                .background(CyberBlack.copy(alpha = 0.95f))
                .zverDialogSafeBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)
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
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp).testTag("settings_close_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = CyberMuted, modifier = Modifier.size(20.dp))
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
                        SettingsTab.SECURITY_CODECS -> {
                            Text("1. ФОРМАТ И СЖАТИЕ КАДРА", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "WebP и перекодирование заменяют таблицы DQT/MakerNotes камеры (0 Б сторонних метаданных). Режим «ДОКУМЕНТ Ч/Б» сжимает лист А4 в ~25-45 КБ.",
                                color = CyberMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ExifStripper.CompressionPreset.values().forEach { cp ->
                                    val isSel = preset == cp
                                    Text(
                                        text = cp.labelRu,
                                        color = if (isSel) Color.Black else CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSel) CyberGreen else CyberSurface)
                                            .border(1.dp, if (isSel) CyberGreen else CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                preset = cp
                                                ZverPrefs.setCompressionPreset(context, cp)
                                                onStatusMessage("КОДЕК: ${cp.labelRu}")
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("1B. PHOTO IQ, ZOOM IQ И ZEBRA (PRO)", color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text(
                                "Локальная доводка кадра в ОЗУ (LUT + резкость без переусиления шума) и теле-улучшение при зуме 2x..10x.",
                                color = CyberMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PhotoIqEngine.PhotoIqProfile.values().forEach { iq ->
                                    val isSel = photoIqProfile == iq
                                    Text(
                                        text = iq.badgeRu,
                                        color = if (isSel) Color.Black else CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSel) NeonCyan else CyberSurface)
                                            .border(1.dp, if (isSel) NeonCyan else CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                photoIqProfile = iq
                                                ZverPrefs.setPhotoIqProfile(context, iq)
                                                onStatusMessage("PHOTO IQ: ${iq.labelRu}")
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                            .testTag("settings_iq_${iq.name.lowercase()}")
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            SecurityToggleRow("Zoom Intelligence (2x–10x)", "Авто-чёткость и микроконтраст при цифровом зуме ≥1.8x", zoomIqEnabled) {
                                zoomIqEnabled = it; ZverPrefs.setZoomIqEnabled(context, it)
                            }
                            SecurityToggleRow("Smart WebP/JPEG сжатие", "Адаптивное качество (±8%) по плотности деталей и текста кадра", smartCompression) {
                                smartCompression = it
                                ZverPrefs.setSmartCompressionEnabled(context, it)
                                onStatusMessage(if (it) "SMART СЖАТИЕ: ВКЛ" else "SMART СЖАТИЕ: ФИКС. ПРЕСЕТ")
                            }
                            SecurityToggleRow(
                                title = "Gyro-EIS Lite (Визор)",
                                subtitle = "Гироскопическая стабилизация кадра визора через GPU MVP-матрицу (1.10x кроп)",
                                checked = gyroEisEnabled,
                                testTag = "settings_eis_switch"
                            ) {
                                gyroEisEnabled = it
                                ZverPrefs.setGyroEisEnabled(context, it)
                                onStatusMessage(if (it) "GYRO-EIS LITE: ВКЛ" else "GYRO-EIS LITE: ВЫКЛ")
                            }
                            Text("Интервал Таймлапса (интервалометр):", color = CyberMuted, fontSize = 10.sp)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp)
                            ) {
                                TimeLapseRecorder.SUPPORTED_INTERVALS_MS.forEach { ms ->
                                    val sel = timeLapseIntervalMs == ms
                                    val label = if (ms < 1000L) "${ms}мс" else "${ms / 1000L}с"
                                    Text(
                                        text = label,
                                        color = if (sel) Color.Black else CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) NeonCyan else CyberSurface)
                                            .border(1.dp, if (sel) NeonCyan else CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                timeLapseIntervalMs = ms
                                                ZverPrefs.setTimeLapseIntervalMs(context, ms)
                                                onStatusMessage("ТАЙМЛАПС ИНТЕРВАЛ: $label")
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                            .testTag("settings_timelapse_interval_$ms")
                                    )
                                }
                            }
                            Text("Порог пересвета Zebra (яркость Y): $zebraThreshold", color = CyberMuted, fontSize = 10.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                listOf(225, 235, 240, 248).forEach { thr ->
                                    val sel = zebraThreshold == thr
                                    Text(
                                        text = thr.toString(),
                                        color = if (sel) Color.Black else CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) NeonYellow else CyberSurface)
                                            .border(1.dp, if (sel) NeonYellow else CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                zebraThreshold = thr
                                                ZverPrefs.setZebraThreshold(context, thr)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("2. ТАКТИЧЕСКАЯ БЕЗОПАСНОСТЬ", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))

                            SecurityToggleRow("Anti-PRNU каскад", "Асимметричный срез + 8x8 Skia-деформация сетки + CSPRNG микродизер", antiPrnu) {
                                antiPrnu = it; ZverPrefs.setAntiPrnuEnabled(context, it)
                            }
                            SecurityToggleRow("RAM-Only контур", "0 байт на диск — кадры живут только в ОЗУ", ramOnly) {
                                ramOnly = it; ZverPrefs.setRamOnlyMode(context, it)
                            }
                            SecurityToggleRow("Видео без микрофона (Mute)", "Запись видео без аудиодорожки и доступа к MIC", muteVideoAudio) {
                                muteVideoAudio = it; ZverPrefs.setMuteVideoAudio(context, it)
                            }
                            SecurityToggleRow("PIN Scramble (Перемешивание)", "Случайный порядок цифр при входе в Сейф", pinScramble) {
                                pinScramble = it; ZverPrefs.setPinScrambleEnabled(context, it)
                            }
                            SecurityToggleRow("Snatch Lock (Вырывание)", "Резкий рывок (акселерометр+гиро) блокирует экран", snatchLock) {
                                snatchLock = it; ZverPrefs.setSnatchProtectionEnabled(context, it)
                            }
                            SecurityToggleRow("Переворот экраном вниз", "Кладёшь экраном вниз → мгновенный стелс", flipDisguise) {
                                flipDisguise = it; ZverPrefs.setFlipToDisguiseEnabled(context, it)
                            }
                            SecurityToggleRow("Блок скриншотов и записи экрана", "Выкл по умолчанию (разрешены скрины/видео). Вкл = FLAG_SECURE + щит Android 15", screenRecBlock) {
                                screenRecBlock = it
                                ZverPrefs.setScreenRecBlockEnabled(context, it)
                                com.example.MainActivity.onScreenCapturePolicyChanged?.invoke()
                                onStatusMessage(if (it) "СКРИНШОТЫ И ЗАПИСЬ ЗАБЛОКИРОВАНЫ" else "СКРИНШОТЫ И ЗАПИСЬ РАЗРЕШЕНЫ")
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("3. АВТО-ШРЕДЕР И DEAD-MAN SWITCH", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))

                            Text("Лимит неверных PIN до крипто-шредера:", color = CyberMuted, fontSize = 10.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                listOf(0 to "Задержка", 5 to "5 попыток", 10 to "10 попыток").forEach { (v, lbl) ->
                                    val sel = maxFailWipe == v
                                    Text(
                                        text = lbl,
                                        color = if (sel) Color.Black else CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) CyberRed else CyberSurface)
                                            .border(1.dp, if (sel) CyberRed else CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                maxFailWipe = v
                                                ZverPrefs.setMaxFailedPinWipe(context, v)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }

                            Text("Авто-уничтожение при неактивности (Dead-Man):", color = CyberMuted, fontSize = 10.sp)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp)
                            ) {
                                listOf(0 to "Выкл", 3 to "3 дня", 7 to "7 дней", 14 to "14 дней", 30 to "30 дней").forEach { (d, lbl) ->
                                    val sel = deadManDays == d
                                    Text(
                                        text = lbl,
                                        color = if (sel) Color.Black else CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) NeonYellow else CyberSurface)
                                            .border(1.dp, if (sel) NeonYellow else CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                deadManDays = d
                                                ZverPrefs.setDeadManDays(context, d)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("4. МАСКИРОВКА ИКОНКИ И КОНТУРЫ PIN", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                CamouflageManager.CamouflageMode.values().forEach { mode ->
                                    val sel = currentCamo == mode
                                    val label = when (mode) {
                                        CamouflageManager.CamouflageMode.ZVER -> "Zver Camera"
                                        CamouflageManager.CamouflageMode.CALCULATOR -> "Калькулятор"
                                        CamouflageManager.CamouflageMode.SYSTEM_TOOLS -> "Системные"
                                    }
                                    Text(
                                        text = label,
                                        color = if (sel) Color.Black else Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) CyberGreen else CyberSurface)
                                            .border(1.dp, if (sel) CyberGreen else CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                CamouflageManager.setCamouflage(context, mode)
                                                currentCamo = mode
                                                onCamouflageChanged(mode)
                                                onStatusMessage("ИКОНКА: $label")
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text("АППАРАТНЫЕ КНОПКИ ГРОМКОСТИ (±):", color = CyberMuted, fontSize = 10.sp)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp)
                            ) {
                                ZverPrefs.VolumeKeyAction.values().forEach { act ->
                                    val sel = volumeAction == act
                                    Text(
                                        text = act.labelRu,
                                        color = if (sel) Color.Black else Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) NeonCyan else CyberSurface)
                                            .border(1.dp, if (sel) NeonCyan else CyberBorder, RoundedCornerShape(6.dp))
                                            .clickable {
                                                volumeAction = act
                                                ZverPrefs.setVolumeKeyAction(context, act)
                                                onStatusMessage("ГРОМКОСТЬ (±): ${act.labelRu}")
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                            .testTag("settings_vol_${act.name.lowercase()}")
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        onDismiss()
                                        onOpenPinSetup()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                                ) {
                                    Text("3 PIN (DURESS)", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        onDismiss()
                                        onOpenSelfTest()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan.copy(alpha = 0.2f)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("settings_open_selftest_btn")
                                ) {
                                    Text("🧪 SELF-TEST", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            val duressState = if (GhostCryptoVault.hasDuressConfigured(context)) "DURESS ПИН: АКТИВЕН" else "DURESS ПИН: НЕ ЗАДАН"
                            val teeLabel = if (strongBoxCapable) "KeyStore: StrongBox" else "KeyStore: Hardware TEE"
                            Text("$teeLabel • $duressState", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("5. ДИАГНОСТИКА CAMERA2 / HAL (ФАКТИЧЕСКАЯ)", color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .background(CyberSurface, RoundedCornerShape(8.dp))
                                    .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = cameraDiagReport,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    lineHeight = 13.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = {
                                        SecureClipboard.copySensitive(context, "Zver Camera2 Diagnostics", cameraDiagReport)
                                        onStatusMessage("ОТЧЁТ CAMERA2 СКОПИРОВАН (45с)")
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(40.dp).testTag("copy_camera_diag_button")
                                ) {
                                    Text("КОПИРОВАТЬ ОТЧЁТ HAL", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }
                            }

                            lastCrashBreadcrumb?.let { crashText ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CyberSurface, RoundedCornerShape(8.dp))
                                        .border(1.dp, CyberRed.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    Text("ПОСЛЕДНИЙ ОФФЛАЙН-КРАШ (ЛОГ):", color = CyberRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(crashText, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 13.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = {
                                            ZverPrefs.clearLastCrashBreadcrumb(context)
                                            lastCrashBreadcrumb = null
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.height(36.dp).testTag("clear_crash_breadcrumb_button")
                                    ) {
                                        Text("ОЧИСТИТЬ ЛОГ", color = CyberRed, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                    }
                                }
                            }
                        }

                        SettingsTab.AI_VIKA -> {
                            fun fetchModelsNow(explicitChars: CharArray? = null) {
                                ByokManager.refreshModelsInBackground(context, explicitChars) { res ->
                                    res.onSuccess { list ->
                                        onStatusMessage("МОДЕЛИ ЗАГРУЖЕНЫ: ${list.size}")
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    onDismiss()
                                    onOpenVikaSheet()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Default.Psychology, contentDescription = "Вика AI", tint = NeonCyan, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("ОТКРЫТЬ ПАНЕЛЬ ВИКИ И АНАЛИТИКУ", color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Section 1: API Key & Encrypted Channel
                            Text("1. КЛЮЧ API GEMINI (BYOK)", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Канал: ${transportConfig.describeShort()} (TLS 1.3/1.2). Ключ шифруется в ${if (strongBoxCapable) "StrongBox" else "Hardware TEE"} KeyStore.",
                                color = CyberMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text(if (hasKeyConfigured) "Ключ сохранён в KeyStore (введи новый для замены)" else "Вставь API-ключ Gemini (AIza...)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberGreen,
                                    unfocusedBorderColor = CyberBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val clean = apiKeyInput.trim()
                                        if (clean.isNotEmpty()) {
                                            val chars = clean.toCharArray()
                                            ByokManager.saveKey(context, chars.clone())
                                            apiKeyInput = ""
                                            onStatusMessage("КЛЮЧ СОХРАНЁН — ЗАГРУЗКА МОДЕЛЕЙ")
                                            fetchModelsNow(chars)
                                        }
                                    },
                                    enabled = !isFetchingModels && apiKeyInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(44.dp)
                                ) {
                                    Text("СОХРАНИТЬ + МОДЕЛИ", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }

                                if (hasKeyConfigured) {
                                    Button(
                                        onClick = { fetchModelsNow(null) },
                                        enabled = !isFetchingModels,
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberSurface),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.height(44.dp)
                                    ) {
                                        Text(if (isFetchingModels) "..." else "↻ МОДЕЛИ", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                    }
                                    Button(
                                        onClick = {
                                            ByokManager.deleteKey(context)
                                            onStatusMessage("КЛЮЧ УДАЛЁН ИЗ KEYSTORE")
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberRed.copy(alpha = 0.8f)),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.height(44.dp)
                                    ) {
                                        Text("СНЕСТИ", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                    }
                                }
                            }

                            modelFetchStatus?.let {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(it, color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Section 2: Model Selection (Dynamic from key!)
                            Text("2. МОДЕЛИ КЛЮЧА (${discoveredModels.size}) — ТЕКУЩАЯ: $selectedModel", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (discoveredModels.isEmpty()) {
                                Text("Нажми «СОХРАНИТЬ + МОДЕЛИ» или «↻ МОДЕЛИ» чтобы увидеть все модели твоего ключа.", color = CyberMuted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp))
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    discoveredModels.forEach { m ->
                                        val isCur = selectedModel == m.id
                                        Text(
                                            text = m.id,
                                            color = if (isCur) CyberGreen else CyberMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isCur) CyberGreen.copy(alpha = 0.2f) else CyberSurface)
                                                .border(1.dp, if (isCur) CyberGreen else CyberBorder, RoundedCornerShape(6.dp))
                                                .clickable {
                                                    ByokManager.saveModelName(context, m.id)
                                                    onStatusMessage("МОДЕЛЬ: ${m.id}")
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Section 3: Prompt & Personality Presets
                            Text("3. СИСТЕМНЫЙ ПРОМПТ ВИКИ", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Дефолтный промпт настроен без воды, без выдумок и с юмором/матюками:",
                                color = CyberMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "Вика База" to ByokManager.DEFAULT_PROMPT,
                                    "Дозор" to ByokManager.PROMPT_SENTINEL,
                                    "OSINT-разведка" to ByokManager.PROMPT_OSINT,
                                    "OCR Документов" to ByokManager.PROMPT_OCR,
                                    "Форензик" to ByokManager.PROMPT_FORENSIC,
                                    "Аналитик" to ByokManager.PROMPT_ANALYTICS
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
                                                ByokManager.saveCustomPrompt(context, text)
                                                onStatusMessage("ПРОМПТ: $title")
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = currentPrompt,
                                onValueChange = {
                                    ByokManager.saveCustomPrompt(context, it)
                                },
                                label = { Text("Текст системного промпта", fontSize = 11.sp) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberGreen,
                                    unfocusedBorderColor = CyberBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            if (httpLogs.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("4. HTTP-ЖУРНАЛ (${httpLogs.size}, БЕЗ КЛЮЧЕЙ)", color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    TextButton(onClick = {
                                        val txt = httpLogs.joinToString("\n") { l ->
                                            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(l.timestampMs))
                                            "[$ts] ${l.method} ${l.endpoint} -> HTTP ${l.httpCode} (${l.latencyMs}ms): ${l.detail}"
                                        }
                                        SecureClipboard.copySensitive(context, "Zver HTTP Logs", txt)
                                        onStatusMessage("HTTP-ЖУРНАЛ СКОПИРОВАН (45с)")
                                    }) {
                                        Text("КОПИРОВАТЬ", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                    }
                                }
                                httpLogs.take(5).forEach { l ->
                                    Text(
                                        text = "HTTP ${l.httpCode} • ${l.endpoint}: ${l.detail}",
                                        color = if (l.httpCode in 200..299) CyberGreen else CyberRed,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }

                        SettingsTab.MODES_GUIDE -> {
                            Text("КАК РАБОТАЮТ РЕЖИМЫ СЪЁМКИ (ПРОСТЫМИ СЛОВАМИ)", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(10.dp))

                            ModeExplanationCard(
                                title = "☀️ ФОТО (+ PRO И МАКРО-ЗУМ)",
                                desc = "Работает по профилю Camera2 HAL (стандартный биннинг + ручные ISO/выдержка в PRO-панели, профили качества, стекинг и зум 2x–10x для макросъёмки пломб/номеров). EXIF/GPS вырезаются до шифрования.",
                                modeKey = "PHOTO",
                                onSelectMode = { key ->
                                    onSelectMode(key)
                                    onDismiss()
                                }
                            )
                            ModeExplanationCard(
                                title = "🎥 ВИДЕОСЪЁМКА (СЕЙФ)",
                                desc = "Запись защищённого видео со звуком или без звука. После остановки файл очищается от метаданных (udta/meta/stco) и шифруется по AES-256.",
                                modeKey = "VIDEO",
                                onSelectMode = { key ->
                                    onSelectMode(key)
                                    onDismiss()
                                }
                            )
                            ModeExplanationCard(
                                title = "🌙 НОЧНОЙ РЕЖИМ И МУЛЬТИ-СТЕК",
                                desc = "Автоматически поднимает экспозицию на +0.5 EV, антисмаз ждёт фиксации рук. В шторке можно включить стекинг кадров (HDR-стек, Ночь-стек, Детали+).",
                                modeKey = "NIGHT",
                                onSelectMode = { key ->
                                    onSelectMode(key)
                                    onDismiss()
                                }
                            )
                            ModeExplanationCard(
                                title = "🔬 БИО-ВИЗОР v2.2 (13-в-1)",
                                desc = "Офлайн CV и ML Kit: Пульс rPPG + HRV, Тепло-Кожа, Эйлер x20, Веновизор, Рентген кожи, Тепловизор, Анти-Туман, Скелет, Хлорофилл, Ночь, Поиск ИК, Дорожки ПП (Sobel) и Маркировка OCR.",
                                modeKey = "VISOR",
                                onSelectMode = { key ->
                                    onSelectMode(key)
                                    onDismiss()
                                }
                            )
                            ModeExplanationCard(
                                title = "🛠️ ПЛАТА PCB И АКУСТИК-СТОРОЖ",
                                desc = "Офлайн инспекция печатных плат (мостики/прогар/пайка), декодер SMD/4-полос и OCR маркировки в «Плата PCB» + реальный PCM-16 шумовой сторож «Акустик-сторож» (-34..-18 дБ).",
                                modeKey = null
                            )
                            ModeExplanationCard(
                                title = "🛡️ ДОЗОР (APEX SENTINEL v5.0)",
                                desc = "Автономная охрана: 12x12 детектор движения с фильтром прохожих (2/4/6 сек), датчик удара/стука, авто-CLAHE в темноте, 2-Stage кроп лица и опциональный триаж в Вику AI.",
                                modeKey = "SENTINEL",
                                onSelectMode = { key ->
                                    onSelectMode(key)
                                    onDismiss()
                                }
                            )
                            ModeExplanationCard(
                                title = "📄 ДОКУМЕНТЫ (ЛАЗЕРНЫЙ УРОВЕНЬ)",
                                desc = "Включает прицел параллельности к столу/листу (зелёный круг при ровном телефоне) — текст фиксируется без трапеций и перекосов.",
                                modeKey = "DOCUMENTS",
                                onSelectMode = { key ->
                                    onSelectMode(key)
                                    onDismiss()
                                }
                            )
                            ModeExplanationCard(
                                title = "🎙️ ГОЛОСОВОЕ УПРАВЛЕНИЕ (ИНСТРУМЕНТ)",
                                desc = "Запускается из шторки инструментов: «Снять», «Приблизь к лицу», «Сброс», «Фонарик». Работает через локальный микрофон.",
                                modeKey = "VOICE",
                                onSelectMode = { key ->
                                    onSelectMode(key)
                                    onDismiss()
                                }
                            )
                            ModeExplanationCard(
                                title = "🕶️ СТЕЛС-ЭКРАН (ИНСТРУМЕНТ / МЕНЮ ⋮)",
                                desc = "Маскирует экран под чёрный экран, часы блокировки или браузер. Снимок делается кнопками громкости или тапом. Доступен из шторки и меню ⋮.",
                                modeKey = "STEALTH",
                                onSelectMode = { key ->
                                    onSelectMode(key)
                                    onDismiss()
                                }
                            )
                        }

                        SettingsTab.TERMUX_AUTOMATION -> {
                            Text("СИМБИОЗ: TERMUX, SHIZUKU, ORBOT И MACRODROID", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Прямая связь на уровне Android без root. Zver Camera принимает deep-link zver:// (singleTop) и широковещательные интенты.",
                                color = CyberMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            val eco = remember { com.example.assist.AutomationRouter.inspectEcosystem(context) }

                            Text("СТАТУС ПОДКЛЮЧЕНИЯ В СИСТЕМЕ:", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AppBadge("Termux", eco.termux) {
                                    context.packageManager.getLaunchIntentForPackage("com.termux")?.let { context.startActivity(it) }
                                }
                                AppBadge("Termux:API", eco.termuxApi) {
                                    context.packageManager.getLaunchIntentForPackage("com.termux.api")?.let { context.startActivity(it) }
                                }
                                AppBadge(if (eco.shizukuVersion != null) "Shizuku ${eco.shizukuVersion}" else "Shizuku", eco.shizuku) {
                                    context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { context.startActivity(it) }
                                }
                                AppBadge("MacroDroid", eco.macroDroid) {
                                    context.packageManager.getLaunchIntentForPackage("com.arlosoft.macrodroid")?.let { context.startActivity(it) }
                                }
                                AppBadge("Orbot / Tor", eco.orbot) {
                                    (context.packageManager.getLaunchIntentForPackage("org.torproject.android")
                                        ?: context.packageManager.getLaunchIntentForPackage("org.torproject.torbrowser"))
                                        ?.let { context.startActivity(it) }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text("DEEP-LINK URI (SINGLETOP — БЕЗ ДУБЛЕЙ ОКНА):", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                            AutomationCommandCard(
                                label = "Сделать фото с текущей сцены (Termux / ADB):",
                                cmd = "am start -d zver://capture",
                                onCopy = {
                                    SecureClipboard.copySensitive(context, "Zver Capture URI", "am start -d zver://capture")
                                    onStatusMessage("СКОПИРОВАНО (45с)")
                                }
                            )

                            AutomationCommandCard(
                                label = "Поставить / снять Дозор (Sentinel) удалённо:",
                                cmd = "am start -d \"zver://sentinel?arm=1\"",
                                onCopy = {
                                    SecureClipboard.copySensitive(context, "Zver Sentinel URI", "am start -d \"zver://sentinel?arm=1\"")
                                    onStatusMessage("СКОПИРОВАН ДОЗОР URI (45с)")
                                }
                            )

                            AutomationCommandCard(
                                label = "Переключить режим (PHOTO|VIDEO|NIGHT|VISOR|SENTINEL|DOCUMENTS):",
                                cmd = "am start -d \"zver://mode?name=NIGHT\"",
                                onCopy = {
                                    SecureClipboard.copySensitive(context, "Zver Mode URI", "am start -d \"zver://mode?name=NIGHT\"")
                                    onStatusMessage("СКОПИРОВАН MODE URI (45с)")
                                }
                            )

                            AutomationCommandCard(
                                label = "Включить RAM-Only (0 записей на диск) / Экстренная блокировка:",
                                cmd = "am start -d \"zver://ram?on=1\"  # или zver://lock",
                                onCopy = {
                                    SecureClipboard.copySensitive(context, "Zver RAM URI", "am start -d \"zver://ram?on=1\"")
                                    onStatusMessage("СКОПИРОВАН RAM-ONLY URI (45с)")
                                }
                            )

                            AutomationCommandCard(
                                label = "Broadcast-интенты (MacroDroid / Tasker / Termux):",
                                cmd = "am broadcast -a com.example.camera.ARM_SENTINEL --ez armed true",
                                onCopy = {
                                    SecureClipboard.copySensitive(context, "Zver Broadcast Intent", "am broadcast -a com.example.camera.ARM_SENTINEL --ez armed true")
                                    onStatusMessage("СКОПИРОВАН INTENT (45с)")
                                }
                            )

                            AutomationCommandCard(
                                label = "Фонарик / Захват кадра через broadcast:",
                                cmd = "am broadcast -a com.example.camera.TOGGLE_TORCH",
                                onCopy = {
                                    SecureClipboard.copySensitive(context, "Zver Torch Intent", "am broadcast -a com.example.camera.TOGGLE_TORCH")
                                    onStatusMessage("СКОПИРОВАН INTENT ФОНАРИКА (45с)")
                                }
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        context.sendBroadcast(android.content.Intent(com.example.assist.AutomationRouter.ACTION_TRIGGER_CAPTURE))
                                        onStatusMessage("⚡ ТЕСТОВЫЙ ИМПУЛЬС КАДРА ОТПРАВЛЕН!")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).height(44.dp)
                                ) {
                                    Text("⚡ ТЕСТ КАДР", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        context.sendBroadcast(android.content.Intent(com.example.assist.AutomationRouter.ACTION_TOGGLE_TORCH))
                                        onStatusMessage("🔦 ТЕСТ ФОНАРИКА ОТПРАВЛЕН!")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberSurface),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).height(44.dp)
                                ) {
                                    Text("🔦 ТЕСТ СВЕТ", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("ЗАКРЫТЬ НАСТРОЙКИ", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ModeExplanationCard(
    title: String,
    desc: String,
    modeKey: String? = null,
    onSelectMode: ((String) -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(CyberSurface, RoundedCornerShape(8.dp))
            .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (modeKey != null && onSelectMode != null) {
                Button(
                    onClick = { onSelectMode(modeKey) },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreenDark),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("ВКЛЮЧИТЬ", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(desc, color = Color.White, fontSize = 10.sp, lineHeight = 14.sp)
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
        Text(
            text = name,
            color = if (isInstalled) Color.White else CyberMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun SecurityToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (testTag != null) it.testTag(testTag) else it }
            .padding(vertical = 3.dp)
            .background(CyberSurface, RoundedCornerShape(8.dp))
            .border(1.dp, if (checked) CyberGreen.copy(alpha = 0.4f) else CyberBorder, RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = if (checked) CyberGreen else Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(subtitle, color = CyberMuted, fontSize = 9.sp, lineHeight = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = CyberGreen,
                uncheckedThumbColor = CyberMuted,
                uncheckedTrackColor = CyberBorder
            )
        )
    }
}


