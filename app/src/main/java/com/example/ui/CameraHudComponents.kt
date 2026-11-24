package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FilterHdr
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShutterSpeed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoStable
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.AudioWatcherEngine
import com.example.camera.CameraCapabilityProfiler
import com.example.camera.ExifStripper
import com.example.camera.MultiFrameStacker
import com.example.camera.PhotoIqEngine
import com.example.camera.ZoomIntelligence
import com.example.security.SentinelEngine
import com.example.visor.VisorMode
import com.example.visor.VisorTelemetry
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

@Composable
internal fun CameraTopHudAndDrawer(
    ramMode: Boolean,
    compressionPreset: ExifStripper.CompressionPreset,
    isAssistModeActive: Boolean,
    hasByokKey: Boolean,
    isTorchActive: Boolean,
    isToolsDrawerOpen: Boolean,
    activeToolsCount: Int,
    isTopOverflowMenuOpen: Boolean,
    isProPanelExpanded: Boolean,
    isHistogramEnabled: Boolean,
    isZebraEnabled: Boolean,
    zebraThreshold: Int,
    isFocusPeakingEnabled: Boolean,
    isFalseColorEnabled: Boolean,
    isDocumentModeActive: Boolean,
    isDcpDehazeActive: Boolean,
    isAutoShutterEnabled: Boolean,
    isFocusBracketingActive: Boolean,
    timerSeconds: Int,
    isVoiceControlActive: Boolean,
    photoIqProfile: PhotoIqEngine.PhotoIqProfile,
    multiFrameMode: MultiFrameStacker.StackMode?,
    showPcbLabDialog: Boolean,
    isAudioWatcherActive: Boolean,
    audioThresholdDbfs: Double,
    isGyroEisEnabled: Boolean,
    isRetroScanning: Boolean,
    isTimeLapseRecording: Boolean,
    timeLapseIntervalMs: Long,
    isCapturing: Boolean,
    isRecordingVideo: Boolean,
    lastStatusMessage: String?,
    telemetry: CameraTelemetryState,
    onToggleTorch: () -> Unit,
    onToggleDrawer: () -> Unit,
    onCloseDrawer: () -> Unit,
    onFlipCamera: () -> Unit,
    onOpenOverflowMenu: () -> Unit,
    onDismissOverflowMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleProPanel: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onToggleFalseColor: () -> Unit,
    onToggleDocumentMode: () -> Unit,
    onToggleDcpDehaze: () -> Unit,
    onToggleAutoShutter: () -> Unit,
    onToggleFocusBracketing: () -> Unit,
    onCycleTimer: () -> Unit,
    onToggleVoiceControl: () -> Unit,
    onActivateStealth: () -> Unit,
    onOpenCamouflage: () -> Unit,
    onToggleAssistMode: () -> Unit,
    onCyclePhotoIq: () -> Unit,
    onCycleMultiFrame: () -> Unit,
    onOpenPcbLab: () -> Unit,
    onToggleAudioWatcher: () -> Unit,
    onToggleGyroEis: () -> Unit,
    onTriggerRetroScan: () -> Unit,
    onToggleTimeLapse: () -> Unit,
    onOpenSelfTest: () -> Unit,
    onOpenVikaSheet: () -> Unit,
    onOpenPanicShred: () -> Unit,
    onDismissStatus: () -> Unit = {}
) {
    val presetShort = when (compressionPreset) {
        ExifStripper.CompressionPreset.ULTRA_TINY -> "WebP"
        ExifStripper.CompressionPreset.BALANCED -> "WebP+"
        ExifStripper.CompressionPreset.DOCUMENT_BW -> "Ч/Б"
        ExifStripper.CompressionPreset.HIGH_QUALITY -> "JPEG"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CyberBlack.copy(alpha = 0.58f))
                .border(
                    1.dp,
                    if (isAssistModeActive) CyberRed.copy(alpha = 0.7f) else CyberGreen.copy(alpha = 0.3f),
                    RoundedCornerShape(16.dp)
                )
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isAssistModeActive) CyberRed else if (hasByokKey) NeonCyan else CyberGreen)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ZVER",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (ramMode) "ОЗУ • $presetShort" else "СЕЙФ • $presetShort",
                        color = if (ramMode) NeonYellow else CyberGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CyberSurface.copy(alpha = 0.8f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                CameraTelemetrySummaryText(telemetry = telemetry)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onToggleTorch,
                    modifier = Modifier.size(40.dp).testTag("torch_button")
                ) {
                    Icon(
                        imageVector = if (isTorchActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Фонарик",
                        tint = if (isTorchActive) NeonYellow else Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Box(contentAlignment = Alignment.TopEnd) {
                    IconButton(
                        onClick = onToggleDrawer,
                        modifier = Modifier.size(40.dp).testTag("tools_drawer_button")
                    ) {
                        Icon(
                            imageVector = if (isToolsDrawerOpen) Icons.Default.KeyboardArrowUp else Icons.Default.Tune,
                            contentDescription = "Шторка инструментов",
                            tint = if (isToolsDrawerOpen || activeToolsCount > 0) CyberGreen else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (activeToolsCount > 0 && !isToolsDrawerOpen) {
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp, end = 4.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(CyberGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = activeToolsCount.toString(),
                                color = Color.Black,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.sp
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onFlipCamera,
                    modifier = Modifier.size(40.dp).testTag("flip_camera_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FlipCameraAndroid,
                        contentDescription = "Сменить камеру",
                        tint = CyberGreen,
                        modifier = Modifier.size(19.dp)
                    )
                }

                TopOverflowMenuButton(
                    expanded = isTopOverflowMenuOpen,
                    isProPanelExpanded = isProPanelExpanded,
                    isHistogramEnabled = isHistogramEnabled,
                    isZebraEnabled = isZebraEnabled,
                    onOpenMenu = onOpenOverflowMenu,
                    onDismissMenu = onDismissOverflowMenu,
                    onOpenSettings = onOpenSettings,
                    onToggleProPanel = onToggleProPanel,
                    onToggleHistogram = onToggleHistogram,
                    onToggleZebra = onToggleZebra,
                    onOpenPcbLab = onOpenPcbLab,
                    onOpenCamouflage = onOpenCamouflage,
                    onActivateStealth = onActivateStealth,
                    onOpenVikaSheet = onOpenVikaSheet
                )
            }
        }

        CameraToolsDrawer(
            visible = isToolsDrawerOpen,
            isFocusPeakingEnabled = isFocusPeakingEnabled,
            isFalseColorEnabled = isFalseColorEnabled,
            isDocumentModeActive = isDocumentModeActive,
            isDcpDehazeActive = isDcpDehazeActive,
            isAutoShutterEnabled = isAutoShutterEnabled,
            isFocusBracketingActive = isFocusBracketingActive,
            timerSeconds = timerSeconds,
            isVoiceControlActive = isVoiceControlActive,
            isAssistModeActive = isAssistModeActive,
            isProPanelExpanded = isProPanelExpanded,
            isHistogramEnabled = isHistogramEnabled,
            isZebraEnabled = isZebraEnabled,
            photoIqProfile = photoIqProfile,
            multiFrameMode = multiFrameMode,
            showPcbLabDialog = showPcbLabDialog,
            isAudioWatcherActive = isAudioWatcherActive,
            audioThresholdDbfs = audioThresholdDbfs,
            isGyroEisEnabled = isGyroEisEnabled,
            isRetroScanning = isRetroScanning,
            isTimeLapseRecording = isTimeLapseRecording,
            timeLapseIntervalMs = timeLapseIntervalMs,
            onToggleFocusPeaking = onToggleFocusPeaking,
            onToggleFalseColor = onToggleFalseColor,
            onToggleDocumentMode = onToggleDocumentMode,
            onToggleDcpDehaze = onToggleDcpDehaze,
            onToggleAutoShutter = onToggleAutoShutter,
            onToggleFocusBracketing = onToggleFocusBracketing,
            onCycleTimer = onCycleTimer,
            onToggleVoiceControl = onToggleVoiceControl,
            onActivateStealth = onActivateStealth,
            onOpenCamouflage = onOpenCamouflage,
            onToggleAssistMode = onToggleAssistMode,
            onOpenSettings = onOpenSettings,
            onToggleProPanel = onToggleProPanel,
            onToggleHistogram = onToggleHistogram,
            onToggleZebra = onToggleZebra,
            onCyclePhotoIq = onCyclePhotoIq,
            onCycleMultiFrame = onCycleMultiFrame,
            onOpenPcbLab = onOpenPcbLab,
            onToggleAudioWatcher = onToggleAudioWatcher,
            onToggleGyroEis = onToggleGyroEis,
            onTriggerRetroScan = onTriggerRetroScan,
            onToggleTimeLapse = onToggleTimeLapse,
            onOpenSelfTest = onOpenSelfTest,
            onOpenPanicShred = onOpenPanicShred,
            onCloseDrawer = onCloseDrawer
        )

        RecordingAndStatusBanner(
            isCapturing = isCapturing,
            isRecordingVideo = isRecordingVideo,
            isTimeLapseRecording = isTimeLapseRecording,
            lastStatusMessage = lastStatusMessage,
            telemetry = telemetry,
            onDismissStatus = onDismissStatus
        )

        LiveTelemetryMetricsRow(
            isToolsDrawerOpen = isToolsDrawerOpen,
            isHistogramEnabled = isHistogramEnabled,
            isFocusPeakingEnabled = isFocusPeakingEnabled,
            isFalseColorEnabled = isFalseColorEnabled,
            isZebraEnabled = isZebraEnabled,
            zebraThreshold = zebraThreshold,
            telemetry = telemetry
        )
    }
}

@Composable
private fun CameraTelemetrySummaryText(telemetry: CameraTelemetryState) {
    val isTripod = telemetry.isTripodMode
    val isStable = telemetry.isGyroStable
    val variance = telemetry.liveLaplacianVariance
    val stabilityText = if (isTripod) "Штатив" else if (isStable) "Стабильно" else "Тряска!"
    Text(
        text = "$stabilityText • Резкость ${variance.toInt()}",
        color = if (isStable) CyberMuted else CyberRed,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun TopOverflowMenuButton(
    expanded: Boolean,
    isProPanelExpanded: Boolean,
    isHistogramEnabled: Boolean,
    isZebraEnabled: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleProPanel: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleZebra: () -> Unit,
    onOpenPcbLab: () -> Unit,
    onOpenCamouflage: () -> Unit,
    onActivateStealth: () -> Unit,
    onOpenVikaSheet: () -> Unit
) {
    Box {
        IconButton(
            onClick = onOpenMenu,
            modifier = Modifier.size(40.dp).testTag("top_overflow_menu_button")
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Меню",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissMenu,
            modifier = Modifier
                .background(CyberSurface)
                .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
        ) {
            DropdownMenuItem(
                text = { Text("Настройки ZVER", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = CyberGreen, modifier = Modifier.size(18.dp)) },
                onClick = { onDismissMenu(); onOpenSettings() }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (isProPanelExpanded) "Скрыть PRO-панель" else "Открыть PRO-панель",
                        color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp
                    )
                },
                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, tint = NeonYellow, modifier = Modifier.size(18.dp)) },
                onClick = { onDismissMenu(); onToggleProPanel() }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (isHistogramEnabled) "Гистограмма: Вкл" else "Гистограмма: Выкл",
                        color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp
                    )
                },
                leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null, tint = CyberGreen, modifier = Modifier.size(18.dp)) },
                onClick = { onDismissMenu(); onToggleHistogram() }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (isZebraEnabled) "Зебра пересветов: Вкл" else "Зебра пересветов: Выкл",
                        color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp
                    )
                },
                leadingIcon = { Icon(Icons.Default.Grain, contentDescription = null, tint = NeonYellow, modifier = Modifier.size(18.dp)) },
                onClick = { onDismissMenu(); onToggleZebra() }
            )
            DropdownMenuItem(
                text = { Text("Лаборатория плат PCB", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Memory, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                onClick = { onDismissMenu(); onOpenPcbLab() }
            )
            DropdownMenuItem(
                text = { Text("Маскировка иконки", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = CyberGreen, modifier = Modifier.size(18.dp)) },
                onClick = { onDismissMenu(); onOpenCamouflage() }
            )
            DropdownMenuItem(
                text = { Text("Стелс-экран", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.DarkMode, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                onClick = { onDismissMenu(); onActivateStealth() }
            )
            DropdownMenuItem(
                text = { Text("Вика AI и аналитика", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                onClick = { onDismissMenu(); onOpenVikaSheet() }
            )
        }
    }
}

@Composable
private fun CameraToolsDrawer(
    visible: Boolean,
    isFocusPeakingEnabled: Boolean,
    isFalseColorEnabled: Boolean,
    isDocumentModeActive: Boolean,
    isDcpDehazeActive: Boolean,
    isAutoShutterEnabled: Boolean,
    isFocusBracketingActive: Boolean,
    timerSeconds: Int,
    isVoiceControlActive: Boolean,
    isAssistModeActive: Boolean,
    isProPanelExpanded: Boolean,
    isHistogramEnabled: Boolean,
    isZebraEnabled: Boolean,
    photoIqProfile: PhotoIqEngine.PhotoIqProfile,
    multiFrameMode: MultiFrameStacker.StackMode?,
    showPcbLabDialog: Boolean,
    isAudioWatcherActive: Boolean,
    audioThresholdDbfs: Double,
    isGyroEisEnabled: Boolean,
    isRetroScanning: Boolean,
    isTimeLapseRecording: Boolean,
    timeLapseIntervalMs: Long,
    onToggleFocusPeaking: () -> Unit,
    onToggleFalseColor: () -> Unit,
    onToggleDocumentMode: () -> Unit,
    onToggleDcpDehaze: () -> Unit,
    onToggleAutoShutter: () -> Unit,
    onToggleFocusBracketing: () -> Unit,
    onCycleTimer: () -> Unit,
    onToggleVoiceControl: () -> Unit,
    onActivateStealth: () -> Unit,
    onOpenCamouflage: () -> Unit,
    onToggleAssistMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleProPanel: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleZebra: () -> Unit,
    onCyclePhotoIq: () -> Unit,
    onCycleMultiFrame: () -> Unit,
    onOpenPcbLab: () -> Unit,
    onToggleAudioWatcher: () -> Unit,
    onToggleGyroEis: () -> Unit,
    onTriggerRetroScan: () -> Unit,
    onToggleTimeLapse: () -> Unit,
    onOpenSelfTest: () -> Unit,
    onOpenPanicShred: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(CyberSurface.copy(alpha = 0.94f), CyberBlack.copy(alpha = 0.96f))
                    )
                )
                .border(1.dp, CyberGreen.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 285.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DrawerTileButton(
                        icon = Icons.Default.CenterFocusStrong,
                        label = "Фокус\nпикинг",
                        active = isFocusPeakingEnabled,
                        accent = NeonCyan,
                        modifier = Modifier.weight(1f),
                        onClick = onToggleFocusPeaking
                    )
                    DrawerTileButton(
                        icon = Icons.Default.Exposure,
                        label = "Карта\nсвета",
                        active = isFalseColorEnabled,
                        accent = NeonYellow,
                        modifier = Modifier.weight(1f),
                        onClick = onToggleFalseColor
                    )
                    DrawerTileButton(
                        icon = Icons.Default.CropFree,
                        label = "Уровень\nлиста",
                        active = isDocumentModeActive,
                        accent = CyberGreen,
                        modifier = Modifier.weight(1f),
                        onClick = onToggleDocumentMode
                    )
                    DrawerTileButton(
                        icon = Icons.Default.AutoFixHigh,
                        label = "Анти\nтуман",
                        active = isDcpDehazeActive,
                        accent = CyberGreen,
                        modifier = Modifier.weight(1f),
                        onClick = onToggleDcpDehaze
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DrawerTileButton(
                        icon = Icons.Default.ShutterSpeed,
                        label = "Авто\nспуск",
                        active = isAutoShutterEnabled,
                        accent = CyberGreen,
                        modifier = Modifier.weight(1f),
                        onClick = onToggleAutoShutter
                    )
                    DrawerTileButton(
                        icon = Icons.Default.BurstMode,
                        label = "Серия\nфокуса",
                        active = isFocusBracketingActive,
                        accent = NeonCyan,
                        modifier = Modifier.weight(1f),
                        onClick = onToggleFocusBracketing
                    )
                    DrawerTileButton(
                        icon = Icons.Default.Timer,
                        label = if (timerSeconds == 0) "Таймер" else "Таймер\n${timerSeconds} сек",
                        active = timerSeconds > 0,
                        accent = NeonYellow,
                        modifier = Modifier.weight(1f),
                        onClick = onCycleTimer
                    )
                    DrawerTileButton(
                        icon = Icons.Default.Mic,
                        label = "Голос\nкоманд",
                        active = isVoiceControlActive,
                        accent = NeonCyan,
                        modifier = Modifier.weight(1f),
                        onClick = onToggleVoiceControl
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DrawerTileButton(
                        icon = Icons.Default.DarkMode,
                        label = "Стелс\nэкран",
                        active = false,
                        accent = NeonCyan,
                        modifier = Modifier.weight(1f).testTag("stealth_button"),
                        onClick = onActivateStealth
                    )
                    DrawerTileButton(
                        icon = Icons.Default.VisibilityOff,
                        label = "Маска\nиконки",
                        active = false,
                        accent = CyberGreen,
                        modifier = Modifier.weight(1f).testTag("camouflage_button"),
                        onClick = onOpenCamouflage
                    )
                    DrawerTileButton(
                        icon = Icons.Default.Shield,
                        label = "Щит AI\nрежим",
                        active = isAssistModeActive,
                        accent = CyberRed,
                        modifier = Modifier.weight(1f).testTag("assist_toggle_button"),
                        onClick = onToggleAssistMode
                    )
                    DrawerTileButton(
                        icon = Icons.Default.Settings,
                        label = "Центр\nопций",
                        active = false,
                        accent = CyberGreen,
                        modifier = Modifier.weight(1f).testTag("settings_button"),
                        onClick = onOpenSettings
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DrawerTileButton(
                        icon = Icons.Default.Tune,
                        label = "PRO\nпанель",
                        active = isProPanelExpanded,
                        accent = NeonYellow,
                        modifier = Modifier.weight(1f).testTag("drawer_pro_toggle"),
                        onClick = onToggleProPanel
                    )
                    DrawerTileButton(
                        icon = Icons.Default.BarChart,
                        label = "Гисто\nграмма",
                        active = isHistogramEnabled,
                        accent = CyberGreen,
                        modifier = Modifier.weight(1f).testTag("drawer_histogram_toggle"),
                        onClick = onToggleHistogram
                    )
                    DrawerTileButton(
                        icon = Icons.Default.Grain,
                        label = "Зебра\nсвета",
                        active = isZebraEnabled,
                        accent = NeonYellow,
                        modifier = Modifier.weight(1f).testTag("drawer_zebra_toggle"),
                        onClick = onToggleZebra
                    )
                    DrawerTileButton(
                        icon = Icons.Default.AutoAwesome,
                        label = "Качество\n${photoIqProfile.badgeRu}",
                        active = photoIqProfile != PhotoIqEngine.PhotoIqProfile.OFF,
                        accent = NeonCyan,
                        modifier = Modifier.weight(1f).testTag("drawer_iq_cycle"),
                        onClick = onCyclePhotoIq
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DrawerTileButton(
                        icon = Icons.Default.FilterHdr,
                        label = "Стекинг\n${multiFrameMode?.badgeRu ?: "Выкл"}",
                        active = multiFrameMode != null,
                        accent = NeonCyan,
                        modifier = Modifier.weight(1f).testTag("drawer_stack_cycle"),
                        onClick = onCycleMultiFrame
                    )
                    DrawerTileButton(
                        icon = Icons.Default.Memory,
                        label = "Плата\nPCB",
                        active = showPcbLabDialog,
                        accent = NeonCyan,
                        modifier = Modifier.weight(1f).testTag("drawer_pcb_lab"),
                        onClick = onOpenPcbLab
                    )
                    DrawerTileButton(
                        icon = Icons.Default.GraphicEq,
                        label = if (isAudioWatcherActive) "Акустик\n${audioThresholdDbfs.toInt()} дБ" else "Акуст.\nсторож",
                        active = isAudioWatcherActive,
                        accent = NeonYellow,
                        modifier = Modifier.weight(1f).testTag("drawer_audio_watcher"),
                        onClick = onToggleAudioWatcher
                    )
                    DrawerTileButton(
                        icon = Icons.Default.DeleteForever,
                        label = "Шредер\nсейфа",
                        active = false,
                        accent = CyberRed,
                        modifier = Modifier.weight(1f).testTag("panic_shred_button"),
                        onClick = onOpenPanicShred
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val intervalLabel = if (timeLapseIntervalMs < 1000L) "${timeLapseIntervalMs}мс" else "${timeLapseIntervalMs / 1000L}с"
                    DrawerTileButton(
                        icon = Icons.Default.VideoStable,
                        label = "Gyro-EIS\nLite",
                        active = isGyroEisEnabled,
                        accent = NeonCyan,
                        modifier = Modifier.weight(1f).testTag("drawer_eis_toggle"),
                        onClick = onToggleGyroEis
                    )
                    DrawerTileButton(
                        icon = Icons.Default.Flare,
                        label = if (isRetroScanning) "Скан...\nОптика" else "Анти\nОптика",
                        active = isRetroScanning,
                        accent = CyberRed,
                        modifier = Modifier.weight(1f).testTag("drawer_retro_scan_btn"),
                        onClick = onTriggerRetroScan
                    )
                    DrawerTileButton(
                        icon = Icons.Default.Timelapse,
                        label = if (isTimeLapseRecording) "Стоп\nТаймлапс" else "Таймлапс\n$intervalLabel",
                        active = isTimeLapseRecording,
                        accent = NeonYellow,
                        modifier = Modifier.weight(1f).testTag("drawer_timelapse_toggle"),
                        onClick = onToggleTimeLapse
                    )
                    DrawerTileButton(
                        icon = Icons.AutoMirrored.Filled.FactCheck,
                        label = "Self-\nTest",
                        active = false,
                        accent = CyberGreen,
                        modifier = Modifier.weight(1f).testTag("drawer_selftest_btn"),
                        onClick = onOpenSelfTest
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCloseDrawer)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "▲ Свернуть панель",
                    color = CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.RecordingAndStatusBanner(
    isCapturing: Boolean,
    isRecordingVideo: Boolean,
    isTimeLapseRecording: Boolean,
    lastStatusMessage: String?,
    telemetry: CameraTelemetryState,
    onDismissStatus: () -> Unit = {}
) {
    val msgUpper = lastStatusMessage?.uppercase(Locale.ROOT).orEmpty()
    val isErrorStatus = msgUpper.contains("ОШИБКА") || msgUpper.contains("СБОЙ") || msgUpper.contains("НЕТ ДОСТУПА")
    val isWarnStatus = !isErrorStatus && (msgUpper.contains("ВНИМАНИЕ") || msgUpper.contains("ТРЕВОГА") || msgUpper.contains("НЕДОСТУП"))
    val bannerAccent = when {
        isRecordingVideo -> CyberRed
        isTimeLapseRecording -> NeonYellow
        isCapturing -> CyberGreen
        isErrorStatus -> CyberRed
        isWarnStatus -> NeonYellow
        else -> CyberGreen
    }
    val bannerBorder = when {
        isRecordingVideo -> CyberRed
        isTimeLapseRecording -> NeonYellow
        isCapturing -> CyberGreen
        isErrorStatus -> CyberRed
        isWarnStatus -> NeonYellow
        else -> CyberGreenDark
    }

    AnimatedVisibility(
        visible = isCapturing || lastStatusMessage != null || isRecordingVideo || isTimeLapseRecording,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(CyberBlack.copy(alpha = 0.85f))
                .border(1.5.dp, bannerBorder, RoundedCornerShape(16.dp))
                .let { mod ->
                    if (!isRecordingVideo && !isTimeLapseRecording && !isCapturing && lastStatusMessage != null) {
                        mod.clickable(onClick = onDismissStatus)
                    } else {
                        mod
                    }
                }
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecordingVideo) {
                    val recSec = telemetry.recordingDurationSeconds
                    val recBytes = telemetry.recordingBytesWritten
                    val m = recSec / 60
                    val s = recSec % 60
                    val timerStr = String.format(Locale.ROOT, "%02d:%02d", m, s)
                    val mb = recBytes / (1024f * 1024f)
                    val statusText = if (recSec >= 2 && recBytes == 0L) {
                        "🔴 ОЖИДАНИЕ КАДРОВ HAL: $timerStr"
                    } else if (recBytes > 0L) {
                        String.format(Locale.US, "🔴 ЗАПИСЬ: %s • %.1f МБ", timerStr, mb)
                    } else {
                        "🔴 ЗАПИСЬ В СЕЙФ: $timerStr"
                    }
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(CyberRed))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        color = CyberRed,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (isTimeLapseRecording) {
                    val elapsed = telemetry.timeLapseElapsedSec
                    val timerStr = String.format(Locale.ROOT, "%02d:%02d", elapsed / 60, elapsed % 60)
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(NeonYellow))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "⏱ ТАЙМЛАПС: ${telemetry.timeLapseFrames} кадр. • $timerStr",
                        color = NeonYellow,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (isCapturing) {
                    CircularProgressIndicator(color = CyberGreen, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ШИФРОВАНИЕ...", color = CyberGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                } else {
                    Text(
                        text = lastStatusMessage ?: "",
                        color = bannerAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTelemetryMetricsRow(
    isToolsDrawerOpen: Boolean,
    isHistogramEnabled: Boolean,
    isFocusPeakingEnabled: Boolean,
    isFalseColorEnabled: Boolean,
    isZebraEnabled: Boolean,
    zebraThreshold: Int,
    telemetry: CameraTelemetryState
) {
    if (isToolsDrawerOpen) return
    val overPct = telemetry.overexposedPercent
    if (!(isHistogramEnabled || isFocusPeakingEnabled || isFalseColorEnabled || (isZebraEnabled && overPct > 0))) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isHistogramEnabled) {
                LiveHistogramOverlay(
                    bins32 = telemetry.liveHistogramBins32,
                    overexposedPct = overPct,
                    underexposedPct = telemetry.underexposedPercent,
                    meanLuma = telemetry.liveMeanLuma
                )
            }
            if (isFocusPeakingEnabled) {
                val lap = telemetry.liveLaplacianVariance
                val ptsCount = telemetry.peakingPoints.size
                val isSharp = lap >= 110.0
                Text(
                    text = if (isSharp) "Фокус-пикинг: резко (${lap.toInt()})" else "Фокус-пикинг: $ptsCount конт.",
                    color = if (isSharp) CyberGreen else NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CyberBlack.copy(alpha = 0.60f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isZebraEnabled && overPct > 0) {
                Text(
                    text = "Зебра ≥$zebraThreshold: $overPct%",
                    color = if (overPct > 10) CyberRed else NeonYellow,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CyberBlack.copy(alpha = 0.60f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
            if (isFalseColorEnabled) {
                Text(
                    text = "Свет $overPct% • Тени ${telemetry.underexposedPercent}%",
                    color = if (overPct > 12) CyberRed else NeonYellow,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CyberBlack.copy(alpha = 0.60f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
internal fun CameraBottomControlDeck(
    modifier: Modifier = Modifier,
    telemetry: CameraTelemetryState,
    isAudioWatcherActive: Boolean,
    audioTelemetry: AudioWatcherEngine.AcousticTelemetry,
    audioThresholdDbfs: Double,
    selectedCameraMode: String,
    cameraModes: List<Pair<String, String>>,
    sentinelArmed: Boolean,
    sentinelConfig: SentinelEngine.SentinelConfig,
    sentinelEvents: List<SentinelEngine.SentinelEvent>,
    hasByokKey: Boolean,
    visorMode: VisorMode,
    isProPanelExpanded: Boolean,
    proProfile: CameraCapabilityProfiler.CameraProProfile,
    exposureIndex: Int,
    awbPreset: CameraCapabilityProfiler.AwbPreset,
    manualFocusNorm: Float?,
    isoOverride: Int?,
    shutterNsOverride: Long?,
    isAeLocked: Boolean,
    isAwbLocked: Boolean = false,
    targetFpsRange: IntRange? = null,
    photoIqProfile: PhotoIqEngine.PhotoIqProfile,
    isHistogramEnabled: Boolean,
    isZebraEnabled: Boolean,
    isZoomIqEnabled: Boolean,
    zoomRatio: Float,
    multiFrameMode: MultiFrameStacker.StackMode?,
    vaultCount: Int,
    isCapturing: Boolean,
    countdownRemaining: Int?,
    isAssistModeActive: Boolean,
    isRecordingVideo: Boolean,
    onSelectAudioThreshold: (Double) -> Unit,
    onToggleSentinelArmed: () -> Unit,
    onCycleSentinelLinger: () -> Unit,
    onToggleSentinelKnock: () -> Unit,
    onToggleSentinelClaheNight: () -> Unit,
    onToggleSentinelTwoStageCrop: () -> Unit,
    onToggleSentinelAiTriage: () -> Unit,
    onClearSentinelEvents: () -> Unit,
    onStealthGuard: () -> Unit,
    onSelectVisorMode: (VisorMode) -> Unit,
    onSavePulseMetrics: (Int, Int?) -> Unit,
    onSaveVisorSnapshot: () -> Unit,
    onExposureIndexChange: (Int) -> Unit,
    onAwbPresetChange: (CameraCapabilityProfiler.AwbPreset) -> Unit,
    onManualFocusChange: (Float?) -> Unit,
    onIsoChange: (Int?) -> Unit,
    onShutterNsChange: (Long?) -> Unit,
    onAeLockChange: (Boolean) -> Unit,
    onAwbLockChange: (Boolean) -> Unit = {},
    onTargetFpsRangeChange: (IntRange?) -> Unit = {},
    onResetPro: () -> Unit = {},
    onPhotoIqChange: (PhotoIqEngine.PhotoIqProfile) -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleZebra: () -> Unit,
    onCloseProPanel: () -> Unit,
    onSelectZoom: (Float) -> Unit,
    onCycleMultiFrame: () -> Unit,
    onToggleProPanel: () -> Unit,
    onCycleEvQuick: () -> Unit,
    onApplyCameraMode: (String) -> Unit,
    onOpenVault: () -> Unit,
    onTriggerVideoToggle: () -> Unit,
    onTriggerPhotoCapture: () -> Unit,
    onOpenVikaSheet: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isAudioWatcherActive) {
            AudioWatcherMeterStrip(
                audioTelemetry = audioTelemetry,
                audioThresholdDbfs = audioThresholdDbfs,
                onSelectAudioThreshold = onSelectAudioThreshold
            )
        }

        val hasOverlayCards = selectedCameraMode == "SENTINEL" ||
            selectedCameraMode == "VISOR" ||
            visorMode != VisorMode.NORMAL
        if (hasOverlayCards) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedCameraMode == "SENTINEL") {
                    SentinelControlCardLeaf(
                        armed = sentinelArmed,
                        config = sentinelConfig,
                        telemetry = telemetry,
                        hasByokKey = hasByokKey,
                        events = sentinelEvents,
                        onToggleArmed = onToggleSentinelArmed,
                        onCycleLinger = onCycleSentinelLinger,
                        onToggleKnock = onToggleSentinelKnock,
                        onToggleClaheNight = onToggleSentinelClaheNight,
                        onToggleTwoStageCrop = onToggleSentinelTwoStageCrop,
                        onToggleAiTriage = onToggleSentinelAiTriage,
                        onClearEvents = onClearSentinelEvents,
                        onStealthGuard = onStealthGuard
                    )
                }

                if (selectedCameraMode == "VISOR" || visorMode != VisorMode.NORMAL) {
                    CyberVisorControlCardLeaf(
                        activeMode = visorMode,
                        telemetryState = telemetry,
                        onSelectVisorMode = onSelectVisorMode,
                        onSavePulseMetrics = onSavePulseMetrics,
                        onSaveVisorSnapshot = onSaveVisorSnapshot
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        AnimatedVisibility(visible = isProPanelExpanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ProCameraControlPanel(
                    profile = proProfile,
                    exposureIndex = exposureIndex,
                    onExposureIndexChange = onExposureIndexChange,
                    awbPreset = awbPreset,
                    onAwbPresetChange = onAwbPresetChange,
                    manualFocusNorm = manualFocusNorm,
                    onManualFocusChange = onManualFocusChange,
                    isoOverride = isoOverride,
                    onIsoChange = onIsoChange,
                    shutterNsOverride = shutterNsOverride,
                    onShutterNsChange = onShutterNsChange,
                    isAeLocked = isAeLocked,
                    onAeLockChange = onAeLockChange,
                    isAwbLocked = isAwbLocked,
                    onAwbLockChange = onAwbLockChange,
                    targetFpsRange = targetFpsRange,
                    onTargetFpsRangeChange = onTargetFpsRangeChange,
                    onResetPro = onResetPro,
                    photoIqProfile = photoIqProfile,
                    onPhotoIqChange = onPhotoIqChange,
                    isHistogramEnabled = isHistogramEnabled,
                    onToggleHistogram = onToggleHistogram,
                    isZebraEnabled = isZebraEnabled,
                    onToggleZebra = onToggleZebra,
                    onClose = onCloseProPanel
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        ZoomIntelligenceBannerLeaf(
            isZoomIqEnabled = isZoomIqEnabled,
            zoomRatio = zoomRatio,
            telemetry = telemetry
        )

        ZoomProEvFloatingStrip(
            zoomRatio = zoomRatio,
            multiFrameMode = multiFrameMode,
            isProPanelExpanded = isProPanelExpanded,
            exposureIndex = exposureIndex,
            proProfile = proProfile,
            onSelectZoom = onSelectZoom,
            onCycleMultiFrame = onCycleMultiFrame,
            onToggleProPanel = onToggleProPanel,
            onCycleEvQuick = onCycleEvQuick
        )

        ModeCarouselRow(
            cameraModes = cameraModes,
            selectedCameraMode = selectedCameraMode,
            onApplyCameraMode = onApplyCameraMode
        )

        PrimaryActionDeckRow(
            selectedCameraMode = selectedCameraMode,
            vaultCount = vaultCount,
            isCapturing = isCapturing,
            countdownRemaining = countdownRemaining,
            isAssistModeActive = isAssistModeActive,
            isRecordingVideo = isRecordingVideo,
            hasByokKey = hasByokKey,
            onOpenVault = onOpenVault,
            onTriggerVideoToggle = onTriggerVideoToggle,
            onTriggerPhotoCapture = onTriggerPhotoCapture,
            onOpenVikaSheet = onOpenVikaSheet
        )
    }
}

@Composable
private fun AudioWatcherMeterStrip(
    audioTelemetry: AudioWatcherEngine.AcousticTelemetry,
    audioThresholdDbfs: Double,
    onSelectAudioThreshold: (Double) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CyberBlack.copy(alpha = 0.85f))
            .border(
                1.dp,
                if (audioTelemetry.isAboveThreshold) CyberRed else NeonYellow.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("audio_watcher_hud"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "🔊 ${audioTelemetry.rmsDbfs.toInt()} dBFS (ПИК ${audioTelemetry.peakDbfs.toInt()}) • ПОРОГ ${audioThresholdDbfs.toInt()}",
                color = if (audioTelemetry.isAboveThreshold) CyberRed else NeonYellow,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.height(14.dp)
            ) {
                audioTelemetry.waveformBars.forEach { bar ->
                    val hDp = (2f + bar * 12f).dp
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height(hDp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (audioTelemetry.isAboveThreshold) CyberRed else CyberGreen)
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(-34.0, -26.0, -18.0).forEach { thr ->
                val sel = audioThresholdDbfs == thr
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 38.dp, minHeight = 32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) NeonYellow else CyberSurface)
                        .clickable { onSelectAudioThreshold(thr) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${thr.toInt()}",
                        color = if (sel) Color.Black else NeonYellow,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomIntelligenceBannerLeaf(
    isZoomIqEnabled: Boolean,
    zoomRatio: Float,
    telemetry: CameraTelemetryState
) {
    if (!isZoomIqEnabled || zoomRatio < 1.8f) return
    val isStable = telemetry.isGyroStable
    val zoomAdvice = ZoomIntelligence.evaluateZoomTier(zoomRatio)
    Text(
        text = if (zoomAdvice.requiresSteadyHand && !isStable) "${zoomAdvice.badgeText} • ДЕРЖИ РОВНЕЕ!" else zoomAdvice.badgeText,
        color = if (zoomAdvice.requiresSteadyHand && !isStable) NeonYellow else NeonCyan,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CyberBlack.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun ZoomProEvFloatingStrip(
    zoomRatio: Float,
    multiFrameMode: MultiFrameStacker.StackMode?,
    isProPanelExpanded: Boolean,
    exposureIndex: Int,
    proProfile: CameraCapabilityProfiler.CameraProProfile,
    onSelectZoom: (Float) -> Unit,
    onCycleMultiFrame: () -> Unit,
    onToggleProPanel: () -> Unit,
    onCycleEvQuick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CyberBlack.copy(alpha = 0.55f))
                .border(1.dp, CyberBorder.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            listOf(1.0f, 2.0f, 4.0f, 6.0f, 10.0f).forEach { z ->
                val sel = zoomRatio == z
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) CyberGreen.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { onSelectZoom(z) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${z.toInt()}x",
                        color = if (sel) CyberGreen else CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (multiFrameMode != null) {
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(NeonCyan.copy(alpha = 0.22f))
                        .border(1.dp, NeonCyan, RoundedCornerShape(20.dp))
                        .clickable(onClick = onCycleMultiFrame)
                        .padding(horizontal = 8.dp)
                        .testTag("stack_mode_pill"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${multiFrameMode.badgeRu}×3",
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isProPanelExpanded) NeonCyan.copy(alpha = 0.22f) else CyberBlack.copy(alpha = 0.55f))
                    .border(1.dp, if (isProPanelExpanded) NeonCyan else CyberBorder, RoundedCornerShape(20.dp))
                    .clickable(onClick = onToggleProPanel)
                    .padding(horizontal = 10.dp)
                    .testTag("pro_panel_pill"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PRO",
                    color = if (isProPanelExpanded) NeonCyan else CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            val evLabel = CameraCapabilityProfiler.formatEvStops(exposureIndex, proProfile.evStep)
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(CyberBlack.copy(alpha = 0.55f))
                    .border(1.dp, if (exposureIndex != 0) NeonYellow else CyberBorder, RoundedCornerShape(20.dp))
                    .clickable(onClick = onCycleEvQuick)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = evLabel,
                    color = if (exposureIndex != 0) NeonYellow else CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ModeCarouselRow(
    cameraModes: List<Pair<String, String>>,
    selectedCameraMode: String,
    onApplyCameraMode: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cameraModes.forEach { (title, key) ->
            val isSelected = selectedCameraMode == key
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onApplyCameraMode(key) }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    text = title,
                    color = if (isSelected) Color.White else CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) NeonYellow else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun PrimaryActionDeckRow(
    selectedCameraMode: String,
    vaultCount: Int,
    isCapturing: Boolean,
    countdownRemaining: Int?,
    isAssistModeActive: Boolean,
    isRecordingVideo: Boolean,
    hasByokKey: Boolean,
    onOpenVault: () -> Unit,
    onTriggerVideoToggle: () -> Unit,
    onTriggerPhotoCapture: () -> Unit,
    onOpenVikaSheet: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CyberBlack.copy(alpha = 0.65f))
            .border(1.dp, CyberBorder.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onOpenVault,
            modifier = Modifier.height(48.dp).testTag("vault_button"),
            colors = ButtonDefaults.buttonColors(containerColor = CyberSurface.copy(alpha = 0.9f)),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CyberGreen.copy(alpha = 0.55f)),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(Icons.Default.Lock, contentDescription = "Сейф", tint = CyberGreen, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$vaultCount",
                color = CyberGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selectedCameraMode == "VIDEO") {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .testTag("video_record_button")
                        .clip(CircleShape)
                        .border(3.dp, CyberRed, CircleShape)
                        .clickable(onClick = onTriggerVideoToggle),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecordingVideo) {
                        Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(CyberRed))
                    } else {
                        Box(modifier = Modifier.size(54.dp).clip(CircleShape).background(CyberRed))
                    }
                }
            } else {
                ShutterButton(
                    isCapturing = isCapturing || countdownRemaining != null,
                    isAssistMode = isAssistModeActive,
                    onClick = onTriggerPhotoCapture
                )

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("video_record_button")
                        .clip(CircleShape)
                        .background(if (isRecordingVideo) CyberRed else CyberRed.copy(alpha = 0.22f))
                        .border(1.5.dp, CyberRed, CircleShape)
                        .clickable(onClick = onTriggerVideoToggle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRecordingVideo) Icons.Default.Stop else Icons.Default.Videocam,
                        contentDescription = if (isRecordingVideo) "Остановить запись" else "Запись видео",
                        tint = if (isRecordingVideo) Color.White else CyberRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        IconButton(
            onClick = onOpenVikaSheet,
            modifier = Modifier
                .size(48.dp)
                .testTag("vika_ai_button")
                .clip(RoundedCornerShape(14.dp))
                .background(if (hasByokKey) NeonCyan.copy(alpha = 0.2f) else CyberSurface)
                .border(1.dp, if (hasByokKey) NeonCyan else CyberBorder, RoundedCornerShape(14.dp))
        ) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = "Вика AI и Аналитика",
                tint = if (hasByokKey) NeonCyan else CyberMuted,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SentinelControlCardLeaf(
    armed: Boolean,
    config: SentinelEngine.SentinelConfig,
    telemetry: CameraTelemetryState,
    hasByokKey: Boolean,
    events: List<SentinelEngine.SentinelEvent>,
    onToggleArmed: () -> Unit,
    onCycleLinger: () -> Unit,
    onToggleKnock: () -> Unit,
    onToggleClaheNight: () -> Unit,
    onToggleTwoStageCrop: () -> Unit,
    onToggleAiTriage: () -> Unit,
    onClearEvents: () -> Unit,
    onStealthGuard: () -> Unit
) {
    SentinelControlCard(
        armed = armed,
        config = config,
        motionScore = telemetry.sentinelMotionScore,
        avgLuma = telemetry.sentinelAvgLuma,
        isNight = telemetry.sentinelIsNight,
        stepLabel = telemetry.sentinelStepLabel,
        hasByokKey = hasByokKey,
        events = events,
        onToggleArmed = onToggleArmed,
        onCycleLinger = onCycleLinger,
        onToggleKnock = onToggleKnock,
        onToggleClaheNight = onToggleClaheNight,
        onToggleTwoStageCrop = onToggleTwoStageCrop,
        onToggleAiTriage = onToggleAiTriage,
        onClearEvents = onClearEvents,
        onStealthGuard = onStealthGuard
    )
}

@Composable
internal fun SentinelControlCard(
    armed: Boolean,
    config: SentinelEngine.SentinelConfig,
    motionScore: Int,
    avgLuma: Int,
    isNight: Boolean,
    stepLabel: String,
    hasByokKey: Boolean,
    events: List<SentinelEngine.SentinelEvent>,
    onToggleArmed: () -> Unit,
    onCycleLinger: () -> Unit,
    onToggleKnock: () -> Unit,
    onToggleClaheNight: () -> Unit,
    onToggleTwoStageCrop: () -> Unit = {},
    onToggleAiTriage: () -> Unit,
    onClearEvents: () -> Unit = {},
    onStealthGuard: () -> Unit
) {
    var showAllEvents by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CyberBlack.copy(alpha = 0.86f))
            .border(1.dp, if (armed) CyberRed.copy(alpha = 0.7f) else NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (armed) "🛡️ ДОЗОР НА ПОСТУ // $stepLabel" else "🛡️ SENTINEL v5.0 // $stepLabel",
                    color = if (armed) CyberRed else NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                Text(
                    text = "Движение: $motionScore% • ${if (isNight) "НОЧЬ-CLAHE" else "ДЕНЬ-HDR"} (L=$avgLuma)",
                    color = CyberMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }
            Button(
                onClick = onToggleArmed,
                colors = ButtonDefaults.buttonColors(containerColor = if (armed) CyberRed else CyberGreenDark),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(40.dp).testTag("sentinel_arm_button")
            ) {
                Text(
                    text = if (armed) "СТОП" else "НА ПОСТ",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SentinelChip(
                label = "Фильтр ${config.lingerThresholdSec}с",
                active = true,
                accent = NeonYellow,
                modifier = Modifier.testTag("sentinel_linger_chip"),
                onClick = onCycleLinger
            )
            SentinelChip(
                label = "Стук",
                active = config.knockTriggerEnabled,
                accent = CyberGreen,
                modifier = Modifier.testTag("sentinel_knock_chip"),
                onClick = onToggleKnock
            )
            SentinelChip(
                label = "CLAHE Ночь",
                active = config.autoNightClaheEnabled,
                accent = NeonCyan,
                modifier = Modifier.testTag("sentinel_clahe_chip"),
                onClick = onToggleClaheNight
            )
            SentinelChip(
                label = "2-Stage Кроп",
                active = config.twoStageFaceCropEnabled,
                accent = CyberGreen,
                modifier = Modifier.testTag("sentinel_crop_chip"),
                onClick = onToggleTwoStageCrop
            )
            SentinelChip(
                label = if (!hasByokKey) "ИИ (нет ключа)" else "ИИ-триаж",
                active = config.aiTriageEnabled && hasByokKey,
                accent = NeonCyan,
                modifier = Modifier.testTag("sentinel_ai_chip"),
                onClick = onToggleAiTriage
            )
            SentinelChip(
                label = "Стелс-пост",
                active = false,
                accent = CyberRed,
                modifier = Modifier.testTag("sentinel_stealth_chip"),
                onClick = onStealthGuard
            )
        }

        if (events.isNotEmpty()) {
            val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            val visibleEvents = if (showAllEvents) events.take(8) else events.take(3)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                visibleEvents.forEach { ev ->
                    val col = when (ev.kind) {
                        SentinelEngine.EventKind.LINGER_ALARM, SentinelEngine.EventKind.KNOCK_ALARM -> CyberRed
                        SentinelEngine.EventKind.AI_VERDICT -> NeonCyan
                        SentinelEngine.EventKind.IGNORED -> CyberGreen
                        SentinelEngine.EventKind.INFO -> CyberMuted
                    }
                    Text(
                        text = "${timeFmt.format(Date(ev.timestampMs))} [${ev.kind.tagRu}] ${ev.message}",
                        color = col,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        maxLines = 2
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (events.size > 3) {
                        Text(
                            text = if (showAllEvents) "Свернуть лог" else "Весь лог (${events.size})",
                            color = NeonCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            modifier = Modifier
                                .clickable { showAllEvents = !showAllEvents }
                                .padding(vertical = 2.dp)
                                .testTag("sentinel_expand_log_btn")
                        )
                    }
                    Text(
                        text = "Очистить лог",
                        color = CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .clickable(onClick = onClearEvents)
                            .padding(vertical = 2.dp)
                            .testTag("sentinel_clear_log_btn")
                    )
                }
            }
        }
    }
}

@Composable
internal fun SentinelChip(
    label: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (active) Color.Black else CyberMuted,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) accent else CyberSurface)
            .border(1.dp, if (active) accent else CyberBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
    )
}

@Composable
internal fun DrawerTileButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) accent.copy(alpha = 0.22f) else CyberBlack.copy(alpha = 0.55f))
            .border(1.dp, if (active) accent else CyberBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label.replace("\n", " "),
            tint = if (active) accent else Color.White,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = if (active) accent else CyberMuted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CyberVisorControlCardLeaf(
    activeMode: VisorMode,
    telemetryState: CameraTelemetryState,
    onSelectVisorMode: (VisorMode) -> Unit,
    onSavePulseMetrics: (Int, Int?) -> Unit,
    onSaveVisorSnapshot: () -> Unit
) {
    val visorState = telemetryState.visorDisplayState
    val gpuTel = telemetryState.gpuVisorTelemetry
    val activeTel = visorState?.second ?: gpuTel ?: VisorTelemetry(mode = activeMode)
    val hasFrame = (visorState != null) || (gpuTel != null && activeMode != VisorMode.NORMAL)
    CyberVisorControlCard(
        activeMode = activeMode,
        telemetry = activeTel,
        hasVisorFrame = hasFrame,
        onSelectVisorMode = onSelectVisorMode,
        onSavePulseMetrics = onSavePulseMetrics,
        onSaveVisorSnapshot = onSaveVisorSnapshot
    )
}

@Composable
internal fun CyberVisorControlCard(
    activeMode: VisorMode,
    telemetry: VisorTelemetry,
    hasVisorFrame: Boolean,
    onSelectVisorMode: (VisorMode) -> Unit,
    onSavePulseMetrics: (Int, Int?) -> Unit,
    onSaveVisorSnapshot: () -> Unit
) {
    var isCollapsed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (isCollapsed) 12.dp else 16.dp))
            .background(CyberBlack.copy(alpha = if (isCollapsed) 0.68f else 0.84f))
            .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(if (isCollapsed) 12.dp else 16.dp))
            .padding(horizontal = 10.dp, vertical = if (isCollapsed) 5.dp else 9.dp),
        verticalArrangement = Arrangement.spacedBy(if (isCollapsed) 0.dp else 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val collapsedMetric = when {
                activeMode == VisorMode.BIO_RPPG && telemetry.estimatedBpm != null -> " • ${telemetry.estimatedBpm} BPM"
                activeMode == VisorMode.IR_BEACON && telemetry.irPeakDetected -> " • ИК!"
                else -> ""
            }
            Text(
                text = "🔬 Био-Визор: ${activeMode.title}$collapsedMetric",
                color = NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeMode != VisorMode.NORMAL && hasVisorFrame) {
                    Text(
                        text = "В сейф",
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(CyberGreen)
                            .clickable(onClick = onSaveVisorSnapshot)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("visor_snapshot_button")
                    )
                }
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(CyberSurface)
                        .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                        .clickable { isCollapsed = !isCollapsed }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("visor_card_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isCollapsed) "▲ Развернуть" else "▼ Свернуть",
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }
        }

        if (!isCollapsed) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VisorMode.values().forEach { m ->
                    val sel = m == activeMode
                    Text(
                        text = m.title,
                        color = if (sel) Color.Black else CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) NeonCyan else CyberSurface)
                            .border(1.dp, if (sel) NeonCyan else CyberBorder, RoundedCornerShape(8.dp))
                            .clickable { onSelectVisorMode(m) }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                            .testTag("visor_mode_${m.name.lowercase()}")
                    )
                }
            }

            when (activeMode) {
                VisorMode.BIO_RPPG -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val bpmText = telemetry.estimatedBpm?.let { "$it BPM" } ?: "Калибровка..."
                            val hrvText = telemetry.estimatedHrvMs?.let { " • HRV ~${it}мс (6-8с)" } ?: ""
                            val qualPct = (telemetry.signalQuality * 100f).toInt()
                            Text(
                                text = "❤️ ЧСС: $bpmText$hrvText • Сигнал $qualPct%",
                                color = if (telemetry.estimatedBpm != null) CyberGreen else NeonYellow,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                            if (telemetry.rppgSourceLabel.isNotBlank()) {
                                Text(
                                    text = "🔒 AE/AWB Lock • ${telemetry.rppgSourceLabel}",
                                    color = NeonCyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = { telemetry.signalQuality.coerceIn(0f, 1f) },
                                color = if (telemetry.signalQuality >= 0.35f) CyberGreen else NeonYellow,
                                trackColor = CyberBorder,
                                modifier = Modifier.fillMaxWidth(0.9f).height(4.dp).clip(RoundedCornerShape(2.dp))
                            )
                        }
                        telemetry.estimatedBpm?.let { bpm ->
                            Text(
                                text = "📋 ФИКС ЧСС",
                                color = Color.Black,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CyberGreen)
                                    .clickable { onSavePulseMetrics(bpm, telemetry.estimatedHrvMs) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .testTag("visor_save_bpm_button")
                            )
                        }
                    }
                    Text(
                        text = activeMode.hintRu,
                        color = CyberMuted,
                        fontSize = 9.sp
                    )
                }
                VisorMode.SKIN_CHROMA -> {
                    val compactPct = (telemetry.skinCompactness * 100f).toInt()
                    Text(
                        text = "👤 Тепло-Кожа: ${telemetry.skinPercent}% • Сплочён. $compactPct% • Движ. ${telemetry.movingSkinPercent}%",
                        color = if (telemetry.skinPercent >= 2 && compactPct >= 18) CyberGreen else NeonYellow,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Text(
                        text = activeMode.hintRu,
                        color = CyberMuted,
                        fontSize = 9.sp
                    )
                }
                VisorMode.VEG_CHLOROPHYLL -> {
                    Text(
                        text = "🌿 Живая зелень: ${telemetry.vegLivePercent}% • Синтетика/пластик: ${telemetry.vegSyntheticPercent}%",
                        color = if (telemetry.vegLivePercent >= 3) CyberGreen else NeonYellow,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "Изумрудный = живой хлорофилл; лиловый = пластик/синтетика/ткань",
                        color = CyberMuted,
                        fontSize = 9.sp
                    )
                }
                VisorMode.EDGE_TRACE -> {
                    Text(
                        text = "🔌 Sobel-дорожки ПП: ${telemetry.edgeTraceDensityPercent}% кадра (золото = пятаки, бирюза = трассы)",
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Text(
                        text = activeMode.hintRu,
                        color = CyberMuted,
                        fontSize = 9.sp
                    )
                }
                VisorMode.EULER_AMP -> {
                    val stabPct = (telemetry.eulerStability * 100f).toInt()
                    Text(
                        text = "〰 1D-стабилизация сдвига: $stabPct% • Демпфер краёв активен",
                        color = if (stabPct >= 65) CyberGreen else NeonYellow,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Text(
                        text = activeMode.hintRu,
                        color = CyberMuted,
                        fontSize = 9.sp
                    )
                }
                VisorMode.WIREFRAME_POSE -> {
                    val count = telemetry.poseWireframe?.visibleCount ?: 0
                    Text(
                        text = if (count >= 3) {
                            "🦴 Скелет (сглаж.): $count/33 точек в реальном времени"
                        } else {
                            "🦴 Скелет: поиск опорных точек ($count/33)..."
                        },
                        color = if (count >= 3) CyberGreen else NeonYellow,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Text(
                        text = activeMode.hintRu,
                        color = CyberMuted,
                        fontSize = 9.sp
                    )
                }
                VisorMode.IR_BEACON -> {
                    val statusTxt = telemetry.irStatusLabel.ifBlank {
                        if (telemetry.irPeakDetected) {
                            "🚨 ИК-ИЗЛУЧЕНИЕ ОБНАРУЖЕНО (${telemetry.irHotPixels} пикс.)"
                        } else {
                            activeMode.hintRu
                        }
                    }
                    Text(
                        text = statusTxt,
                        color = when {
                            telemetry.irPeakDetected -> CyberRed
                            telemetry.irHotPixels > 0 -> NeonYellow
                            else -> CyberMuted
                        },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (telemetry.irPeakDetected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 9.sp
                    )
                }
                else -> {
                    Text(
                        text = activeMode.hintRu,
                        color = CyberMuted,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}
