package com.example.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crypto.GhostCryptoVault
import com.example.security.ZverPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin

private val BioBg = Color(0xFF080D11)
private val BioCard = Color(0xFF101820)
private val BioBorder = Color(0xFF1E2E3B)
private val BioGreen = Color(0xFF00E676)
private val BioCyan = Color(0xFF00E5FF)
private val BioAmber = Color(0xFFFFC107)
private val BioMuted = Color(0xFF8FA3B0)

private enum class JellyTab(val title: String) {
    MORNING("УТРО & БАДы"),
    FACE("ЛИЦО v2"),
    NEURO("НЕЙРО & ДЫХАНИЕ")
}

private data class BioTaskItem(
    val id: String,
    val title: String,
    val detail: String
)

private data class NeuroProtocol(
    val id: String,
    val name: String,
    val window: String,
    val durationMin: Int,
    val volumeHint: String,
    val carrierHz: Double,
    val beatHz: Double,
    val desc: String
)

/**
 * BioTracker / «ЖИЛИСТЫЙ ТАНК v3.0»
 * OPSEC Note: Zero location/city identifiers are included anywhere in UI or code.
 * Can run as:
 * - Launcher camouflage gate (long-press header to enter PIN -> unlocks Zver Camera)
 * - Stealth cover overlay inside camera (double-tap header or close)
 * - Daily tracking tool opened from Settings
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JellyTankScreen(
    isCamouflageGate: Boolean = false,
    onUnlockToCamera: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(JellyTab.MORNING) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    val morningTasks = remember {
        listOf(
            BioTaskItem("hygiene", "1. Подъем и гигиена", "Стакан воды + умывание сразу после сна"),
            BioTaskItem("mikulin", "2. Ударная волна (Микулин) + Зарядка", "Удары пятками от 120+ повторений (лимфоток, капилляры) + растяжка"),
            BioTaskItem("genus", "3. Нейроакустика Утро (GENUS)", "06:00–11:00 • 15–30 мин (громкость 45–60%) • 40 Гц ASSR + 13.5 Гц SMR"),
            BioTaskItem("cavalier", "4. Комплекс по Джеффу Кавальеру", "Пресс: 7–10 мин интенсив | Спина/осанка/сколиоз: 10–12 мин"),
            BioTaskItem("moriarty", "5. Дыхательный протокол (Проф. Мариарти)", "Резонансное ~6 вдохов/мин, 1–3 круга + задержки дыхания до 2 мин"),
            BioTaskItem("shower", "6. Контрастный душ", "Тонус сосудов и парасимпатический отклик"),
            BioTaskItem("breakfast", "7. Завтрак: Молочная каша + 4 вареных яйца", "Чистый белок и медленные углеводы"),
            BioTaskItem("sup_vitc", "БАД: Витамин C", "Приём с завтраком"),
            BioTaskItem("sup_omega", "БАД: Омега-3 (Now Foods)", "Приём с завтраком"),
            BioTaskItem("sup_adam", "БАД: Adam (Now Foods)", "Приём с завтраком"),
            BioTaskItem("sup_k2d3", "БАД: Витамин K2 + D7 / D3", "Приём с завтраком"),
            BioTaskItem("sup_taurine", "БАД: Таурин (Stark Form)", "Приём с завтраком"),
            BioTaskItem("sup_creatine", "БАД: Креатин (Stark Form)", "Приём с завтраком"),
            BioTaskItem("face_done", "Уход: Массаж лица «Мегапупсик v2»", "Полный 5-шаговый комплекс + уход по дню недели"),
            BioTaskItem("melatonin", "Ночь: Мелатонин 3 мг", "Перед сном + протокол SWS 1.5 Гц")
        )
    }

    var doneIds by remember {
        mutableStateOf(ZverPrefs.getJellyChecklist(context, morningTasks.map { it.id }))
    }

    // Neuroacoustic Tone & Breathing Pacer State
    val neuroProtocols = remember {
        listOf(
            NeuroProtocol("genus", "1. Утро — GENUS", "06:00 – 11:00", 20, "45–60% (7–9/15)", 200.0, 40.0, "40 Гц ASSR + 13.5 Гц SMR: снимает сонный туман, глушит DMN, чистый фокус (DOI 10.1038/nature20587)."),
            NeuroProtocol("flow", "2. День — Flow", "11:00 – 16:00", 30, "40–55% (6–8/15)", 180.0, 10.2, "10.2 Гц Upper-Alpha + выход в 14 Гц SMR: состояние потока для кодинга без выгорания."),
            NeuroProtocol("gateway", "3. Вечер — Врата (Gateway)", "16:00 – 19:00", 20, "30–40% (4–6/15)", 144.0, 7.0, "~7 Гц Тета/Альфа + огибающая 0.1 Гц: сброс кортизола после нагрузки, парасимпатика."),
            NeuroProtocol("gamma_pac", "4. Эпифиз (Gamma PAC)", "19:00 – 21:00", 20, "35–45% (5–7/15)", 234.0, 7.83, "936 Гц / 7.83 Гц тета-биение + 40 Гц гамма: чтение, обучение и ассоциативная память."),
            NeuroProtocol("plasma", "5. Эфир (Plasma)", "20:00 – 22:00", 15, "25–35% (3–5/15)", 136.1, 4.5, "Лаховский MWO + 4.5 Гц Тета: разгрузка навязчивых мыслей и мышечных зажимов."),
            NeuroProtocol("sws", "6. Ночь — SWS Protocol", "22:00 – 04:00", 45, "20–30%", 110.0, 1.5, "1.5 Гц Дельта + сонные веретена 11–15.5 Гц: глубокий восстановительный сон."),
            NeuroProtocol("titan_legacy", "Titan Legacy (Восстановление)", "При боли/усталости", 20, "35–45%", 285.0, 6.0, "285 / 396 / 528 Гц + Курамото: снятие физической усталости и болевого фона."),
            NeuroProtocol("titan_athlete", "Titan Athlete / Recovery", "После стресса", 15, "Без звука / тихо", 174.0, 0.1, "Резонансное дыхание 0.1 Гц (~6 вдох/мин): вагусная асимметрия вдох 40% / выдох 60% для HRV.")
        )
    }

    var activeProtocol by remember { mutableStateOf(neuroProtocols.first()) }
    var isTimerRunning by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableStateOf(activeProtocol.durationMin * 60) }
    var isAudioPlaying by remember { mutableStateOf(false) }
    var isBreathPacerRunning by remember { mutableStateOf(false) }
    var breathInhale by remember { mutableStateOf(true) } // 4s inhale (40%), 6s exhale (60%)

    // Timer Loop
    LaunchedEffect(isTimerRunning, activeProtocol) {
        if (isTimerRunning) {
            while (isActive && remainingSeconds > 0) {
                delay(1000)
                remainingSeconds--
            }
            if (remainingSeconds <= 0) {
                isTimerRunning = false
                isAudioPlaying = false
            }
        }
    }

    // Resonant Breathing Loop (Vagal asymmetry: 4s inhale = 40%, 6s exhale = 60% -> 6 breaths/min)
    LaunchedEffect(isBreathPacerRunning) {
        if (isBreathPacerRunning) {
            while (isActive) {
                breathInhale = true
                delay(4000)
                breathInhale = false
                delay(6000)
            }
        } else {
            breathInhale = false
        }
    }

    // Pure Synthesized Binaural AudioTrack Generator (Runs safely on IO dispatcher)
    LaunchedEffect(isAudioPlaying, activeProtocol) {
        if (isAudioPlaying) {
            withContext(Dispatchers.IO) {
                val sampleRate = 22050
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                try {
                    track.play()
                    val chunkFrames = 1024
                    val pcm = ShortArray(chunkFrames * 2)
                    var phaseL = 0.0
                    var phaseR = 0.0
                    val fLeft = activeProtocol.carrierHz
                    val fRight = activeProtocol.carrierHz + activeProtocol.beatHz
                    val stepL = 2.0 * PI * fLeft / sampleRate
                    val stepR = 2.0 * PI * fRight / sampleRate
                    val amplitude = 2600.0 // Gentle non-clipping volume

                    while (isActive) {
                        for (i in 0 until chunkFrames) {
                            pcm[i * 2] = (sin(phaseL) * amplitude).toInt().toShort()
                            pcm[i * 2 + 1] = (sin(phaseR) * amplitude).toInt().toShort()
                            phaseL = (phaseL + stepL) % (2.0 * PI)
                            phaseR = (phaseR + stepR) % (2.0 * PI)
                        }
                        track.write(pcm, 0, pcm.size)
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        track.stop()
                        track.release()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    val breathScale by animateFloatAsState(
        targetValue = if (!isBreathPacerRunning) 0.72f else if (breathInhale) 1.0f else 0.58f,
        animationSpec = tween(durationMillis = if (breathInhale) 3900 else 5900),
        label = "breathScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BioBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(14.dp)
    ) {
        // Header (Long press unlocks hidden camera gate when used as BioTracker camouflage)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BioCard, RoundedCornerShape(10.dp))
                .border(1.dp, BioGreen.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (isCamouflageGate) {
                            showUnlockDialog = true
                        }
                    }
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "⚡ ЖИЛИСТЫЙ ТАНК v3.0",
                    color = BioGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "БОЕВАЯ БИОСИСТЕМА // РЕЗОНАНС И ВОССТАНОВЛЕНИЕ",
                    color = BioMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }
            if (onClose != null) {
                IconButton(
                    onClick = {
                        isAudioPlaying = false
                        isTimerRunning = false
                        isBreathPacerRunning = false
                        onClose()
                    },
                    modifier = Modifier.size(40.dp).testTag("jelly_close_btn")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = BioMuted)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Tab Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            JellyTab.values().forEach { tab ->
                val isSel = selectedTab == tab
                Text(
                    text = tab.title,
                    color = if (isSel) Color.Black else BioMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) BioGreen else BioCard)
                        .border(1.dp, if (isSel) BioGreen else BioBorder, RoundedCornerShape(8.dp))
                        .clickable { selectedTab = tab }
                        .padding(vertical = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Scrollable Body
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (selectedTab) {
                JellyTab.MORNING -> {
                    val progress = if (morningTasks.isEmpty()) 0f else doneIds.size.toFloat() / morningTasks.size
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BioCard, RoundedCornerShape(10.dp))
                            .border(1.dp, BioBorder, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "ПРОГРЕСС ДНЯ: ${doneIds.size} / ${morningTasks.size}",
                                color = BioCyan,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                "${(progress * 100).toInt()}%",
                                color = BioGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            color = BioGreen,
                            trackColor = BioBorder,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                    }

                    morningTasks.forEach { task ->
                        val isDone = task.id in doneIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDone) BioGreen.copy(alpha = 0.08f) else BioCard)
                                .border(1.dp, if (isDone) BioGreen.copy(alpha = 0.5f) else BioBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    val nextDone = !isDone
                                    ZverPrefs.setJellyItemDone(context, task.id, nextDone)
                                    doneIds = if (nextDone) doneIds + task.id else doneIds - task.id
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isDone,
                                onCheckedChange = { checked ->
                                    ZverPrefs.setJellyItemDone(context, task.id, checked)
                                    doneIds = if (checked) doneIds + task.id else doneIds - task.id
                                },
                                colors = CheckboxDefaults.colors(checkedColor = BioGreen, checkmarkColor = Color.Black)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.title,
                                    color = if (isDone) BioGreen else Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = task.detail,
                                    color = BioMuted,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }
                }

                JellyTab.FACE -> {
                    val dayOfWeek = remember { Calendar.getInstance().get(Calendar.DAY_OF_WEEK) }
                    val todaySpecial = when (dayOfWeek) {
                        Calendar.MONDAY -> "СЕГОДНЯ ПОНЕДЕЛЬНИК: Вечер — РЕТИНАЛЬ"
                        Calendar.TUESDAY -> "СЕГОДНЯ ВТОРНИК: ПИЛИНГ FarmStay + вечерний уход"
                        Calendar.THURSDAY -> "СЕГОДНЯ ЧЕТВЕРГ: Вечер — РЕТИНАЛЬ"
                        Calendar.SATURDAY -> "СЕГОДНЯ СУББОТА: ПИЛИНГ FarmStay + вечерний уход"
                        else -> "СЕГОДНЯ: Стандартный ежедневный 5-шаговый массаж"
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BioAmber.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                            .border(1.dp, BioAmber.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = todaySpecial,
                            color = BioAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    val faceSteps = listOf(
                        "1. Точечный массаж (акупрессура)" to "14 точек, сухие руки (50–70 мин полный цикл или быстрая версия).",
                        "2. Жевательные мышцы (расслабление)" to "По 20 секунд проработки на каждую зону для снятия спазма челюсти.",
                        "3. Анатомический лимфодренажный слив" to "Строго с маслом Inlia Olive по линиям лимфотока.",
                        "4. Силовой фейсбилдинг (каркас)" to "Выполняется на сухих руках для тонуса мышечного корсета.",
                        "5. Японский массаж Асахи / Зоган" to "С маслом Inlia Olive — финальный дренаж и скульптуринг."
                    )

                    faceSteps.forEach { (step, desc) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BioCard, RoundedCornerShape(8.dp))
                                .border(1.dp, BioBorder, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(step, color = BioGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(desc, color = Color.White, fontSize = 10.sp)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BioCard, RoundedCornerShape(8.dp))
                            .border(1.dp, BioCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text("НЕДЕЛЬНЫЙ ГРАФИК АКТИВОВ:", color = BioCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("• Понедельник (вечер): Ретиналь\n• Вторник: Пилинг FarmStay + вечерний уход\n• Четверг (вечер): Ретиналь\n• Суббота: Пилинг FarmStay + вечерний уход", color = BioMuted, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                }

                JellyTab.NEURO -> {
                    // Live Resonant Breathing Pacer + Session Player
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BioCard, RoundedCornerShape(12.dp))
                            .border(1.dp, BioCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = activeProtocol.name,
                            color = BioGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "${activeProtocol.carrierHz.toInt()} Гц / Биение ${activeProtocol.beatHz} Гц • Громкость ${activeProtocol.volumeHint}",
                            color = BioCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Animated Breathing Orb (4s inhale / 6s exhale)
                        Box(
                            modifier = Modifier.size(110.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .scale(breathScale)
                                    .clip(CircleShape)
                                    .background(if (breathInhale) BioCyan.copy(alpha = 0.25f) else BioGreen.copy(alpha = 0.2f))
                                    .border(2.dp, if (breathInhale) BioCyan else BioGreen, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                val mins = remainingSeconds / 60
                                val secs = remainingSeconds % 60
                                Text(
                                    text = if (isBreathPacerRunning) {
                                        if (breathInhale) "ВДОХ 4с" else "ВЫДОХ 6с"
                                    } else {
                                        String.format("%02d:%02d", mins, secs)
                                    },
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    isTimerRunning = !isTimerRunning
                                    if (!isTimerRunning) isAudioPlaying = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isTimerRunning) BioAmber else BioGreen),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(
                                    if (isTimerRunning) "ПАУЗА" else "СТАРТ ТАЙМЕРА",
                                    color = Color.Black,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }

                            Button(
                                onClick = { isAudioPlaying = !isAudioPlaying },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isAudioPlaying) BioCyan else BioBorder),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(
                                    if (isAudioPlaying) "ЗВУК: ВКЛ" else "ТОН (НАУШН.)",
                                    color = if (isAudioPlaying) Color.Black else Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }

                            Button(
                                onClick = { isBreathPacerRunning = !isBreathPacerRunning },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isBreathPacerRunning) BioGreen else BioBorder),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(
                                    if (isBreathPacerRunning) "ДЫХ: 6/МИН" else "ДЫХАНИЕ",
                                    color = if (isBreathPacerRunning) Color.Black else Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    neuroProtocols.forEach { proto ->
                        val isSel = activeProtocol.id == proto.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) BioCyan.copy(alpha = 0.1f) else BioCard)
                                .border(1.dp, if (isSel) BioCyan else BioBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    activeProtocol = proto
                                    remainingSeconds = proto.durationMin * 60
                                }
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(proto.name, color = if (isSel) BioCyan else BioGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text("${proto.window} • ${proto.durationMin}м", color = BioAmber, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(proto.desc, color = Color.White, fontSize = 10.sp, lineHeight = 13.sp)
                        }
                    }
                }
            }
        }
    }

    if (showUnlockDialog) {
        AlertDialog(
            onDismissRequest = {
                showUnlockDialog = false
                pinInput = ""
                pinError = false
            },
            title = {
                Text("СЕРВИСНЫЙ КОД ДОСТУПА", color = BioGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            pinInput = it.filter { c -> c.isDigit() }.take(8)
                            pinError = false
                        },
                        label = { Text("ПИН-код (1337 по умолчанию)") },
                        singleLine = true
                    )
                    if (pinError) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Неверный код", color = Color(0xFFFF3366), fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val configured = GhostCryptoVault.isConfigured(context)
                        if (!configured && pinInput == "1337") {
                            showUnlockDialog = false
                            pinInput = ""
                            onUnlockToCamera?.invoke()
                        } else if (configured && GhostCryptoVault.unlockWithPinOutcome(context, pinInput.toCharArray()) != GhostCryptoVault.UnlockOutcome.INVALID) {
                            showUnlockDialog = false
                            pinInput = ""
                            onUnlockToCamera?.invoke()
                        } else {
                            pinError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BioGreen)
                ) {
                    Text("ВХОД", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockDialog = false; pinInput = "" }) {
                    Text("ОТМЕНА", color = BioMuted)
                }
            },
            containerColor = BioCard
        )
    }
}
