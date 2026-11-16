package com.example.ui

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
import androidx.compose.foundation.layout.*
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
    BROWSER_MOCK("Поиск (Браузер)"),
    JELLY_TANK("BioTracker")
}

@Composable
fun StealthBlackoutScreen(
    mode: StealthCoverMode,
    isProximityLocked: Boolean,
    onCycleMode: () -> Unit,
    onCapture: () -> Unit,
    onExit: () -> Unit
) {
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

    if (mode == StealthCoverMode.JELLY_TANK && !isProximityLocked) {
        Box(modifier = Modifier.fillMaxSize()) {
            JellyTankScreen(
                isCamouflageGate = false,
                onClose = onExit
            )
            // Subtle bottom-right mode switcher chip
            Text(
                text = "Сменить вид",
                color = Color.Gray.copy(alpha = 0.45f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .clickable { onCycleMode() }
            )
        }
        return
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
            StealthCoverMode.JELLY_TANK -> {}
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
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val dotRadius = 2.2.dp.toPx()
        for (pt in peakingPoints) {
            drawCircle(
                color = HudCyan.copy(alpha = 0.78f),
                radius = dotRadius,
                center = Offset(pt.x * size.width, pt.y * size.height)
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
    modifier: Modifier = Modifier
) {
    val borderColor = if (overexposedPct > 12) HudRed else HudYellow
    Box(
        modifier = modifier
            .border(2.dp, borderColor.copy(alpha = 0.65f))
            .padding(10.dp)
    ) {
        Text(
            text = "ЭКСПО: ПЕРЕСВЕТ $overexposedPct% | ТЕНИ $underexposedPct%",
            color = borderColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
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
