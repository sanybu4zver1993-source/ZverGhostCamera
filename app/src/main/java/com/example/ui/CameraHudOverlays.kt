package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HudGreen = Color(0xFF00FF66)
private val HudRed = Color(0xFFFF1744)
private val HudCyan = Color(0xFF00E5FF)
private val HudYellow = Color(0xFFFFEA00)

enum class StealthCoverMode(val titleRu: String) {
    BLACKOUT("Чёрный экран"),
    LOCKSCREEN_CLOCK("Часы (Блокировка)"),
    BROWSER_MOCK("Поиск (Браузер)")
}

@Composable
fun StealthBlackoutScreen(
    mode: StealthCoverMode,
    isProximityLocked: Boolean,
    onCycleMode: () -> Unit,
    onCapture: () -> Unit,
    onExit: () -> Unit
) {
    BackHandler { onExit() }
    var currentTimeStr by remember { mutableStateOf("") }
    var currentDateStr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
        while (true) {
            val now = Date()
            currentTimeStr = timeFormat.format(now)
            currentDateStr = dateFormat.format(now).replaceFirstChar { it.uppercase() }
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isProximityLocked) {
                detectTapGestures(
                    onDoubleTap = { onExit() },
                    onTap = { if (!isProximityLocked) onCapture() }
                )
            }
    ) {
        if (isProximityLocked) {
            // Pocket/Ear Proximity Lock: pure pitch black + touch guard
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "КАРМАННЫЙ БЛОК (ДАТЧИК ПРИБЛИЖЕНИЯ)",
                    color = Color.DarkGray.copy(alpha = 0.25f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }
            return@Box
        }

        when (mode) {
            StealthCoverMode.BLACKOUT -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "•",
                        color = Color.DarkGray.copy(alpha = 0.15f),
                        fontSize = 12.sp
                    )
                }
            }
            StealthCoverMode.LOCKSCREEN_CLOCK -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 90.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Заблокировано",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = currentTimeStr,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 68.sp,
                        fontWeight = FontWeight.Light
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentDateStr,
                        color = Color.LightGray.copy(alpha = 0.7f),
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Проведите вверх для разблокировки",
                        color = Color.Gray.copy(alpha = 0.4f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 40.dp)
                    )
                }
            }
            StealthCoverMode.BROWSER_MOCK -> {
                // Neutral OPSEC-clean offline browser cover (no location leaks)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF202124))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Поиск", tint = Color.Gray, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("search.local/article?topic=tech-weekly", color = Color.LightGray, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text("Обзор технологий и стандартов", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Краткая сводка обновлений за неделю: оптимизация автономности мобильных процессоров, повышение пропускной способности памяти и обновления открытых систем.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Top stealth controls (Switch disguise & Exit)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Маскировка: ${mode.titleRu}",
                color = Color.DarkGray.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable { onCycleMode() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            IconButton(
                onClick = onExit,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Выйти из стелса",
                    tint = Color.DarkGray.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Real-time Y-plane gradient Focus Peaking contour overlay.
 */
@Composable
fun FocusPeakingOverlay(
    peakingPoints: List<Offset>,
    sharpnessVariance: Double = 0.0,
    showBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dotRadius = 2.2.dp.toPx()
            for (pt in peakingPoints) {
                drawCircle(
                    color = HudCyan.copy(alpha = 0.78f),
                    radius = dotRadius,
                    center = Offset(pt.x * size.width, pt.y * size.height)
                )
            }
        }
        if (showBadge) {
            val isSharp = sharpnessVariance >= 110.0
            Text(
                text = if (isSharp) "Фокус-пикинг: резко (${sharpnessVariance.toInt()})" else "Фокус-пикинг: контуры ${peakingPoints.size} (${sharpnessVariance.toInt()})",
                color = if (isSharp) HudGreen else HudCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 74.dp)
                    .background(Color.Black.copy(alpha = 0.60f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * Real-time Exposure Clipping Telemetry Badge (% overexposed highlights & crushed shadows).
 */
@Composable
fun FalseColorExposureOverlay(
    overexposedPct: Int,
    underexposedPct: Int,
    showBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    val borderColor = if (overexposedPct > 12) HudRed else HudYellow
    Box(
        modifier = modifier
            .border(2.dp, borderColor.copy(alpha = 0.55f))
            .padding(10.dp)
    ) {
        if (showBadge) {
            Text(
                text = "Экспозиция: пересвет $overexposedPct% • тени $underexposedPct%",
                color = borderColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 64.dp, end = 4.dp)
                    .background(Color.Black.copy(alpha = 0.60f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
fun ShutterButton(
    isCapturing: Boolean,
    isAssistMode: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val activeColor = if (isAssistMode) HudRed else HudGreen

    Box(
        modifier = Modifier
            .size(72.dp)
            .testTag("camera_shutter_button")
            .clickable(enabled = !isCapturing, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = if (isCapturing) HudRed else activeColor.copy(alpha = pulseAlpha),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (isCapturing) HudRed.copy(alpha = 0.4f) else activeColor.copy(alpha = 0.25f))
                .border(2.dp, if (isCapturing) HudRed else activeColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Спуск",
                tint = if (isCapturing) HudRed else activeColor,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
fun TacticalReticle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val reticleRadius = 40.dp.toPx()
        val tickLen = 14.dp.toPx()
        val cornerColor = HudGreen.copy(alpha = 0.35f)
        val strokeW = 1.5.dp.toPx()

        drawLine(cornerColor, Offset(cx - reticleRadius, cy), Offset(cx - reticleRadius + tickLen, cy), strokeW)
        drawLine(cornerColor, Offset(cx + reticleRadius - tickLen, cy), Offset(cx + reticleRadius, cy), strokeW)
        drawLine(cornerColor, Offset(cx, cy - reticleRadius), Offset(cx, cy - reticleRadius + tickLen), strokeW)
        drawLine(cornerColor, Offset(cx, cy + reticleRadius - tickLen), Offset(cx, cy + reticleRadius), strokeW)
        drawCircle(color = HudGreen.copy(alpha = 0.5f), radius = 2.5.dp.toPx(), center = Offset(cx, cy))
    }
}

/**
 * Live 32-bin Luma Histogram widget for viewfinder HUD.
 */
@Composable
fun LiveHistogramOverlay(
    bins32: FloatArray,
    overexposedPct: Int,
    underexposedPct: Int,
    meanLuma: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(142.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF070B0E).copy(alpha = 0.56f))
            .border(1.dp, HudGreen.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(6.dp)
            .testTag("live_histogram_overlay")
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
        ) {
            val count = bins32.size.coerceAtLeast(1)
            val barW = size.width / count.toFloat()
            drawLine(
                color = Color.White.copy(alpha = 0.15f),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx()
            )
            for (i in 0 until count) {
                val hRatio = bins32[i].coerceIn(0f, 1f)
                val barH = hRatio * size.height
                val barColor = when {
                    i >= count - 3 && overexposedPct > 5 -> HudRed
                    i <= 2 && underexposedPct > 15 -> HudCyan
                    else -> HudGreen.copy(alpha = 0.85f)
                }
                drawRect(
                    color = barColor,
                    topLeft = Offset(i * barW + 0.5f, size.height - barH),
                    size = androidx.compose.ui.geometry.Size((barW - 1f).coerceAtLeast(1f), barH)
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Ярк:$meanLuma",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp
            )
            Text(
                text = "Т:$underexposedPct% С:$overexposedPct%",
                color = if (overexposedPct > 8) HudRed else HudGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp
            )
        }
    }
}

/**
 * Animated diagonal Zebra Overexposure Highlight Overlay from Y-plane hotspots.
 */
@Composable
fun ZebraExposureOverlay(
    zebraHotspots: List<Offset>,
    overexposedPct: Int,
    threshold: Int,
    showBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "zebraAnim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "zebraPhase"
    )

    Box(modifier = modifier.testTag("zebra_exposure_overlay")) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellSize = 16.dp.toPx()
            val half = cellSize * 0.5f
            val stripeShift = (phase % 8f)
            for (pt in zebraHotspots) {
                val cx = pt.x * size.width
                val cy = pt.y * size.height
                drawLine(
                    color = HudRed.copy(alpha = 0.78f),
                    start = Offset(cx - half + stripeShift * 0.3f, cy + half),
                    end = Offset(cx + half + stripeShift * 0.3f, cy - half),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = HudYellow.copy(alpha = 0.55f),
                    start = Offset(cx - half, cy),
                    end = Offset(cx, cy - half),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }
        if (showBadge && overexposedPct > 0) {
            Text(
                text = "Зебра ≥$threshold: пересвет $overexposedPct%",
                color = if (overexposedPct > 10) HudRed else HudYellow,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 74.dp, end = 12.dp)
                    .background(Color.Black.copy(alpha = 0.60f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * Collapsible Camera2 Pro Control Deck:
 * - EV slider within factual HAL evRange
 * - White Balance presets (AwbPreset)
 * - Manual Focus normalized slider (0=Infinity .. 1=Macro) when supported
 * - ISO & Shutter presets when HAL supports MANUAL_SENSOR
 * - AE-L lock toggle, Photo IQ preset selector, Histogram & Zebra toggles
 */
private enum class ProControlSection(val titleRu: String) {
    IQ("Качество"),
    WB("Баланс белого"),
    MANUAL("Фокус / ISO")
}

@Composable
fun ProCameraControlPanel(
    profile: com.example.camera.CameraCapabilityProfiler.CameraProProfile,
    exposureIndex: Int,
    onExposureIndexChange: (Int) -> Unit,
    awbPreset: com.example.camera.CameraCapabilityProfiler.AwbPreset,
    onAwbPresetChange: (com.example.camera.CameraCapabilityProfiler.AwbPreset) -> Unit,
    manualFocusNorm: Float?,
    onManualFocusChange: (Float?) -> Unit,
    isoOverride: Int?,
    onIsoChange: (Int?) -> Unit,
    shutterNsOverride: Long?,
    onShutterNsChange: (Long?) -> Unit,
    isAeLocked: Boolean,
    onAeLockChange: (Boolean) -> Unit,
    photoIqProfile: com.example.camera.PhotoIqEngine.PhotoIqProfile,
    onPhotoIqChange: (com.example.camera.PhotoIqEngine.PhotoIqProfile) -> Unit,
    isHistogramEnabled: Boolean,
    onToggleHistogram: () -> Unit,
    isZebraEnabled: Boolean,
    onToggleZebra: () -> Unit,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var activeSection by remember { mutableStateOf(ProControlSection.IQ) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF070B0E).copy(alpha = 0.56f))
            .border(1.dp, HudCyan.copy(alpha = 0.38f), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("pro_camera_panel"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Compact header strip + quick visual toggles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PRO • ${profile.hardwareLevelName}",
                color = HudCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProChip(
                    label = "Гист.",
                    selected = isHistogramEnabled,
                    accent = HudGreen,
                    modifier = Modifier.testTag("pro_toggle_histogram"),
                    onClick = onToggleHistogram
                )
                ProChip(
                    label = "Зебра",
                    selected = isZebraEnabled,
                    accent = HudYellow,
                    modifier = Modifier.testTag("pro_toggle_zebra"),
                    onClick = onToggleZebra
                )
                if (profile.supportsAeLock) {
                    ProChip(
                        label = if (isAeLocked) "Фикс AE 🔒" else "Фикс AE",
                        selected = isAeLocked,
                        accent = HudRed,
                        modifier = Modifier.testTag("pro_toggle_ael"),
                        onClick = { onAeLockChange(!isAeLocked) }
                    )
                }
                if (onClose != null) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Скрыть PRO-панель",
                            tint = Color.LightGray,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Always-available EV Compensation Slider (synced with bottom EV pill via formatEvStops)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = com.example.camera.CameraCapabilityProfiler.formatEvStops(exposureIndex, profile.evStep),
                color = if (exposureIndex != 0) HudYellow else Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                modifier = Modifier.width(58.dp)
            )
            androidx.compose.material3.Slider(
                value = exposureIndex.toFloat(),
                onValueChange = { v -> onExposureIndexChange(kotlin.math.round(v).toInt()) },
                valueRange = profile.evRange.first.toFloat()..profile.evRange.last.toFloat(),
                steps = (profile.evRange.last - profile.evRange.first - 1).coerceAtLeast(0),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = HudYellow,
                    activeTrackColor = HudYellow,
                    inactiveTrackColor = Color.DarkGray.copy(alpha = 0.65f)
                ),
                modifier = Modifier.weight(1f).height(22.dp).testTag("pro_ev_slider")
            )
        }

        // Compact section selector tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ProControlSection.values().forEach { section ->
                val sel = activeSection == section
                ProChip(
                    label = section.titleRu,
                    selected = sel,
                    accent = HudCyan,
                    modifier = Modifier.weight(1f),
                    onClick = { activeSection = section }
                )
            }
        }

        // Active sub-panel (1 compact row at a time so preview stays open)
        when (activeSection) {
            ProControlSection.IQ -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.example.camera.PhotoIqEngine.PhotoIqProfile.values().forEach { iq ->
                        ProChip(
                            label = iq.badgeRu,
                            selected = photoIqProfile == iq,
                            accent = HudGreen,
                            modifier = Modifier.testTag("pro_iq_${iq.name.lowercase()}"),
                            onClick = { onPhotoIqChange(iq) }
                        )
                    }
                }
            }
            ProControlSection.WB -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.example.camera.CameraCapabilityProfiler.AwbPreset.values().forEach { preset ->
                        val available = profile.availableAwbModes.contains(preset.camera2Mode) ||
                            preset == com.example.camera.CameraCapabilityProfiler.AwbPreset.AUTO
                        if (available) {
                            ProChip(
                                label = preset.labelRu,
                                selected = awbPreset == preset,
                                accent = HudCyan,
                                modifier = Modifier.testTag("pro_wb_${preset.name.lowercase()}"),
                                onClick = { onAwbPresetChange(preset) }
                            )
                        }
                    }
                }
            }
            ProControlSection.MANUAL -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (profile.isManualFocusSupported) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProChip(
                                label = if (manualFocusNorm == null) "Автофокус" else "Ручной",
                                selected = manualFocusNorm != null,
                                accent = HudCyan,
                                modifier = Modifier.testTag("pro_mf_toggle"),
                                onClick = {
                                    onManualFocusChange(if (manualFocusNorm == null) 0.5f else null)
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            androidx.compose.material3.Slider(
                                value = manualFocusNorm ?: 0f,
                                onValueChange = { onManualFocusChange(it.coerceIn(0f, 1f)) },
                                valueRange = 0f..1f,
                                colors = androidx.compose.material3.SliderDefaults.colors(
                                    thumbColor = HudCyan,
                                    activeTrackColor = HudCyan,
                                    inactiveTrackColor = Color.DarkGray.copy(alpha = 0.65f)
                                ),
                                modifier = Modifier.weight(1f).height(22.dp).testTag("pro_mf_slider")
                            )
                            Text(
                                text = if (manualFocusNorm == null) "Авто" else "${(manualFocusNorm * 100).toInt()}%",
                                color = HudCyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                modifier = Modifier.width(34.dp)
                            )
                        }
                    }
                    if (profile.supportsManualExposure && profile.isoRange != null && profile.exposureTimeNsRange != null) {
                        val isoSteps = remember(profile.isoRange) {
                            listOf(100, 200, 400, 800, 1600, 3200).filter { it in profile.isoRange }
                        }
                        val shutterSteps = remember(profile.exposureTimeNsRange) {
                            listOf(
                                1_000_000L,
                                4_000_000L,
                                16_666_666L,
                                33_333_333L,
                                100_000_000L,
                                250_000_000L
                            ).filter { it in profile.exposureTimeNsRange }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("ISO:", color = Color.LightGray, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            ProChip(
                                label = "Авто",
                                selected = isoOverride == null,
                                accent = HudGreen,
                                modifier = Modifier.testTag("pro_iso_auto"),
                                onClick = { onIsoChange(null) }
                            )
                            isoSteps.forEach { isoVal ->
                                ProChip(
                                    label = isoVal.toString(),
                                    selected = isoOverride == isoVal,
                                    accent = HudGreen,
                                    onClick = { onIsoChange(isoVal) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Выдержка:", color = Color.LightGray, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            ProChip(
                                label = "Авто",
                                selected = shutterNsOverride == null,
                                accent = HudYellow,
                                modifier = Modifier.testTag("pro_shutter_auto"),
                                onClick = { onShutterNsChange(null) }
                            )
                            shutterSteps.forEach { ns ->
                                ProChip(
                                    label = com.example.camera.CameraCapabilityProfiler.formatShutterSpeed(ns),
                                    selected = shutterNsOverride == ns,
                                    accent = HudYellow,
                                    onClick = { onShutterNsChange(ns) }
                                )
                            }
                        }
                    } else {
                        Text(
                            text = profile.halSafetyNoteRu,
                            color = Color(0xFF9FB0BE),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            lineHeight = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProChip(
    label: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) accent.copy(alpha = 0.25f) else Color(0xFF0E151D).copy(alpha = 0.72f))
            .border(1.dp, if (selected) accent else Color(0xFF283A4A), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) accent else Color(0xFFBFD0DE),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}
