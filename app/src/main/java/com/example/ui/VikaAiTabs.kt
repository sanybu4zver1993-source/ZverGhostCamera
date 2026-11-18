package com.example.ui

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.assist.ByokManager
import com.example.assist.GeminiByokClient
import com.example.privacy.LocalSymbiosisCvEngine
import com.example.security.SecureClipboard
import com.example.security.VaultAnalytics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TabCard = Color(0xFF10171E)
private val TabBorder = Color(0xFF20303D)
private val TabGreen = Color(0xFF00FF66)
private val TabGreenDark = Color(0xFF008F39)
private val TabCyan = Color(0xFF00E5FF)
private val TabRed = Color(0xFFFF1744)
private val TabAmber = Color(0xFFFFC107)
private val TabMuted = Color(0xFF8FA3B0)

internal enum class AiFrameMode {
    FULL_ONLY,
    SYMBIOSIS_CROP,
    BG_ISOLATE,
    TEXT_ONLY
}

internal data class VikaStagedPreviewState(
    val bytes: ByteArray? = null,
    val mime: String = "image/webp",
    val cropBytes: ByteArray? = null,
    val cropMime: String = "image/webp",
    val bitmap: Bitmap? = null,
    val report: LocalSymbiosisCvEngine.SymbiosisReport? = null,
    val note: String = "",
    val mode: AiFrameMode? = null
)

@Composable
internal fun ColumnScope.VikaTasksTabContent(
    context: Context,
    hasKey: Boolean,
    isOnlineNow: Boolean,
    hasMediaSource: Boolean,
    externalBitmap: Bitmap?,
    externalVideoFile: File?,
    frameMode: AiFrameMode,
    onFrameModeChange: (AiFrameMode) -> Unit,
    videoEnableCrop: Boolean,
    onVideoCropToggle: () -> Unit,
    stagedPreview: VikaStagedPreviewState,
    isCapturingPreview: Boolean,
    isGenerating: Boolean,
    chatFeed: List<ByokManager.AiChatEntry>,
    taskInput: String,
    onTaskInputChange: (String) -> Unit,
    onClearStagedPreview: () -> Unit,
    onCapturePreview: () -> Unit,
    onSwitchToKeysTab: () -> Unit,
    onExecuteTask: (userTask: String, includeImage: Boolean, includeAnalytics: Boolean, forceLocalOnly: Boolean, systemOverride: String?) -> Unit,
    onStatusMessage: (String) -> Unit
) {
    if (!hasKey || !isOnlineNow) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TabCard, RoundedCornerShape(12.dp))
                .border(1.dp, TabAmber.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Text(
                text = if (!isOnlineNow) "🔴 ОФЛАЙН -> РАБОТАЮТ 6 МАЛЫШЕЙ" else "⚡ КЛЮЧ НЕ ЗАДАН (ОФЛАЙН-МАЛЫШИ АКТИВНЫ)",
                color = TabAmber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (!isOnlineNow) {
                    "Интернета нет — жми «⚡ Малыши Офлайн» или любой чип: 6 локальных нейронок разберут кадр/видео без сети."
                } else {
                    "Можно сразу гонять 6 локальных нейронок офлайн или вставить свой Gemini ключ во вкладке «Ключ и Модели»."
                },
                color = Color.White,
                fontSize = 10.sp
            )
            if (!hasKey) {
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onSwitchToKeysTab,
                    colors = ButtonDefaults.buttonColors(containerColor = TabCyan),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text("ВСТАВИТЬ GEMINI КЛЮЧ", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (externalVideoFile != null) {
        VikaVideoModeSelectorRow(
            frameMode = frameMode,
            videoEnableCrop = videoEnableCrop,
            onFrameModeChange = onFrameModeChange,
            onVideoCropToggle = onVideoCropToggle
        )
        Spacer(modifier = Modifier.height(8.dp))
    } else if (hasMediaSource) {
        VikaPhotoModeSelectorRow(
            frameMode = frameMode,
            onFrameModeChange = onFrameModeChange
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (frameMode != AiFrameMode.TEXT_ONLY && externalVideoFile == null && hasMediaSource) {
        VikaFramePreviewBar(
            externalBitmap = externalBitmap,
            stagedPreview = stagedPreview,
            isCapturingPreview = isCapturingPreview,
            isGenerating = isGenerating,
            onClearPreview = onClearStagedPreview,
            onCapturePreview = onCapturePreview
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        VikaQuickTaskChip("⚡ Малыши Офлайн") {
            onExecuteTask("Полный локальный разбор малышами", true, false, true, null)
        }
        VikaQuickTaskChip("👁️ Разбор кадра") {
            onExecuteTask("Разбери что на кадре: ключевые детали, риски и чё делать дальше.", frameMode != AiFrameMode.TEXT_ONLY, false, false, null)
        }
        VikaQuickTaskChip("🛡️ Дозор-триаж") {
            onExecuteTask("Оцени обстановку на кадре: кто в кадре, форма/гражданка, уровень угрозы.", frameMode != AiFrameMode.TEXT_ONLY, false, false, ByokManager.PROMPT_SENTINEL)
        }
        VikaQuickTaskChip("📝 OCR текста") {
            onExecuteTask("Вытащи весь текст, даты, цифры и печати без выдумок.", frameMode != AiFrameMode.TEXT_ONLY, false, false, ByokManager.PROMPT_OCR)
        }
        VikaQuickTaskChip("🚗 OSINT-палево") {
            onExecuteTask("Проверь на палево: номера, вывески, ориентиры, лица, отражения.", frameMode != AiFrameMode.TEXT_ONLY, false, false, ByokManager.PROMPT_OSINT)
        }
        VikaQuickTaskChip("📊 Аудит статы") {
            onExecuteTask("Сделай краткий аудит нашей текущей статистики и защиты.", false, true, false, ByokManager.PROMPT_ANALYTICS)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (chatFeed.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = "Вика", tint = TabCyan.copy(alpha = 0.7f), modifier = Modifier.size(44.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Выбери режим сверху:\n• «🖼️ ВСЯ» -> чистый полный разбор только в Gemini\n• «🎯 +КРОП» -> симбиоз 6-NN (кроп лица/объекта + теги) + Gemini\n• «✂️ БЕЗ ФОНА» -> прячет фон перед отправкой\n• Нет сети или сдох лимит -> авто-подхват 6 локальных малышей!",
                        color = TabMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        } else {
            items(chatFeed, key = { it.id }) { msg ->
                VikaChatMessageCard(
                    entry = msg,
                    onCopy = {
                        SecureClipboard.copySensitive(context, "Zver Vika AI", msg.replyText)
                        onStatusMessage("ОТВЕТ СКОПИРОВАН (АВТО-ОЧИСТКА 45с)")
                    }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = taskInput,
            onValueChange = onTaskInputChange,
            placeholder = { Text("Малая, посмотри чё тут...", color = TabMuted, fontSize = 12.sp) },
            maxLines = 3,
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TabCyan,
                unfocusedBorderColor = TabBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
        Button(
            onClick = {
                val txt = taskInput.trim()
                if (txt.isNotEmpty() && !isGenerating) {
                    onTaskInputChange("")
                    onExecuteTask(txt, frameMode != AiFrameMode.TEXT_ONLY, false, false, null)
                }
            },
            enabled = !isGenerating && taskInput.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = TabGreenDark),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(52.dp).testTag("vika_send_btn")
        ) {
            if (isGenerating) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить", tint = Color.White)
            }
        }
    }
}

@Composable
private fun VikaVideoModeSelectorRow(
    frameMode: AiFrameMode,
    videoEnableCrop: Boolean,
    onFrameModeChange: (AiFrameMode) -> Unit,
    onVideoCropToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val vidSel = frameMode != AiFrameMode.TEXT_ONLY
        Box(
            modifier = Modifier
                .weight(1.3f)
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (vidSel) TabGreen.copy(alpha = 0.2f) else TabCard)
                .border(1.dp, if (vidSel) TabGreen else TabBorder, RoundedCornerShape(8.dp))
                .clickable { onFrameModeChange(AiFrameMode.SYMBIOSIS_CROP) },
            contentAlignment = Alignment.Center
        ) {
            Text("🎬 ВИДЕО (≤2м / 5к)", color = if (vidSel) TabGreen else TabMuted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
        if (vidSel) {
            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (videoEnableCrop) TabAmber.copy(alpha = 0.2f) else TabCard)
                    .border(1.dp, if (videoEnableCrop) TabAmber else TabBorder, RoundedCornerShape(8.dp))
                    .clickable { onVideoCropToggle() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (videoEnableCrop) "🎯 +КРОП" else "КРОП ВЫКЛ", color = if (videoEnableCrop) TabAmber else TabMuted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            }
        }
        Box(
            modifier = Modifier
                .weight(0.8f)
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (!vidSel) TabCyan.copy(alpha = 0.2f) else TabCard)
                .border(1.dp, if (!vidSel) TabCyan else TabBorder, RoundedCornerShape(8.dp))
                .clickable { onFrameModeChange(AiFrameMode.TEXT_ONLY) },
            contentAlignment = Alignment.Center
        ) {
            Text("💬 ТЕКСТ", color = if (!vidSel) TabCyan else TabMuted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
    }
}

@Composable
private fun VikaPhotoModeSelectorRow(
    frameMode: AiFrameMode,
    onFrameModeChange: (AiFrameMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        listOf(
            Triple(AiFrameMode.FULL_ONLY, "🖼️ ВСЯ", TabGreen),
            Triple(AiFrameMode.SYMBIOSIS_CROP, "🎯 +КРОП", TabAmber),
            Triple(AiFrameMode.BG_ISOLATE, "✂️ БЕЗ ФОНА", TabCyan),
            Triple(AiFrameMode.TEXT_ONLY, "💬 ТЕКСТ", Color.White)
        ).forEach { (m, label, accent) ->
            val sel = frameMode == m
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) accent.copy(alpha = 0.22f) else TabCard)
                    .border(1.dp, if (sel) accent else TabBorder, RoundedCornerShape(8.dp))
                    .clickable { onFrameModeChange(m) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (sel) accent else TabMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun VikaFramePreviewBar(
    externalBitmap: Bitmap?,
    stagedPreview: VikaStagedPreviewState,
    isCapturingPreview: Boolean,
    isGenerating: Boolean,
    onClearPreview: () -> Unit,
    onCapturePreview: () -> Unit
) {
    val activeThumbBmp = externalBitmap ?: stagedPreview.bitmap
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TabCard, RoundedCornerShape(10.dp))
            .border(1.dp, TabBorder, RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (activeThumbBmp != null && !activeThumbBmp.isRecycled) {
            Image(
                bitmap = activeThumbBmp.asImageBitmap(),
                contentDescription = "Предпросмотр кадра для ИИ",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, TabGreen.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                val sizeInfo = stagedPreview.bytes?.let { "${it.size / 1024} КБ (${stagedPreview.mime})" } ?: "Из Сейфа"
                Text(
                    text = "КАДР ГОТОВ: ${activeThumbBmp.width}x${activeThumbBmp.height}",
                    color = TabGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                Text(
                    text = "$sizeInfo${stagedPreview.note}",
                    color = TabMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (externalBitmap == null) {
                IconButton(
                    onClick = onClearPreview,
                    modifier = Modifier.size(48.dp).testTag("vika_clear_preview_btn")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Сбросить кадр", tint = TabMuted)
                }
            }
        } else if (externalBitmap == null) {
            Text(
                text = "Кадр не зафиксирован (снимет автоматически при отправке или сними превью сейчас):",
                color = TabMuted,
                fontSize = 9.sp,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onCapturePreview,
                enabled = !isCapturingPreview && !isGenerating,
                colors = ButtonDefaults.buttonColors(containerColor = TabCyan),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(44.dp).testTag("vika_capture_preview_btn")
            ) {
                Text(
                    if (isCapturingPreview) "..." else "📷 КАДР",
                    color = Color.Black,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.VikaAnalyticsTabContent(
    localSnap: VaultAnalytics.LocalAnalyticsSnapshot,
    selectedModel: String,
    discoveredModelsCount: Int,
    aiStats: ByokManager.AiSessionStats,
    hasKey: Boolean,
    isOnlineNow: Boolean,
    isGenerating: Boolean,
    onAnalyzeStatsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TabCard, RoundedCornerShape(12.dp))
                .border(1.dp, TabBorder, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text("📦 СЕЙФ И СЖАТИЕ КОДЕКА", color = TabGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VikaStatTile("ДИСК (.GCF)", "${localSnap.diskFilesCount} шт", VaultAnalytics.formatBytesHuman(localSnap.diskTotalBytes), TabGreen, Modifier.weight(1f))
                VikaStatTile("ОЗУ (RAM)", "${localSnap.ramShotsCount} шт", VaultAnalytics.formatBytesHuman(localSnap.ramTotalBytes), TabCyan, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            val avgText = if (localSnap.avgPhotoKb > 0) "${localSnap.avgPhotoKb} КБ (экономия ~${localSnap.estimatedSavedPercent}%)" else "нет снятых фото"
            Text("• Фото / Видео: ${localSnap.diskPhotosCount + localSnap.ramPhotosCount} фото / ${localSnap.diskVideosCount + localSnap.ramVideosCount} видео", color = Color.White, fontSize = 11.sp)
            Text("• Пресет: ${localSnap.activePreset.labelRu} • Anti-PRNU: ${if (localSnap.antiPrnuActive) "ВКЛ" else "ВЫКЛ"}", color = TabMuted, fontSize = 11.sp)
            Text("• Средний кадр: $avgText", color = TabCyan, fontSize = 11.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TabCard, RoundedCornerShape(12.dp))
                .border(1.dp, TabBorder, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🛡️ ГОТОВНОСТЬ ЗАЩИТЫ", color = TabCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "${localSnap.readinessScore} / ${localSnap.readinessMax}",
                    color = if (localSnap.readinessScore >= 5) TabGreen else TabAmber,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { localSnap.readinessScore.toFloat() / localSnap.readinessMax.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (localSnap.readinessScore >= 5) TabGreen else TabAmber,
                trackColor = Color(0xFF1A2632)
            )
            Spacer(modifier = Modifier.height(8.dp))
            localSnap.protections.forEach { check ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(check.label, color = if (check.enabled) Color.White else TabMuted, fontSize = 11.sp)
                    Text(
                        if (check.enabled) "ВКЛ" else "ВЫКЛ",
                        color = if (check.enabled) TabGreen else TabAmber,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TabCard, RoundedCornerShape(12.dp))
                .border(1.dp, TabBorder, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text("⚡ BYOK AI СЕССИЯ", color = TabCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text("• Модель: $selectedModel (доступно по ключу: $discoveredModelsCount)", color = Color.White, fontSize = 11.sp)
            Text("• Запросы: ${aiStats.queriesCount} (успех: ${aiStats.successCount} / ошибки: ${aiStats.errorCount})", color = TabMuted, fontSize = 11.sp)
            Text("• Токены: ${aiStats.totalTokens} (вход ${aiStats.totalPromptTokens} + выход ${aiStats.totalOutputTokens})", color = TabCyan, fontSize = 11.sp)
            Text("• Последний пинг API: ${aiStats.lastLatencyMs} мс", color = TabMuted, fontSize = 11.sp)

            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onAnalyzeStatsClick,
                enabled = !isGenerating,
                colors = ButtonDefaults.buttonColors(containerColor = TabGreenDark),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("vika_analyze_stats_btn")
            ) {
                Text(
                    if (hasKey && isOnlineNow) "🧠 ВИКА, РАЗБЕРИ СТАТИСТИКУ" else "📊 РАЗБРАТЬ СТАТИСТИКУ (ОФЛАЙН)",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.VikaKeysModelsTabContent(
    context: Context,
    hasKey: Boolean,
    isStrongBoxSupported: Boolean,
    selectedModel: String,
    discoveredModels: List<ByokManager.DiscoveredModel>,
    customPrompt: String,
    isFetchingModels: Boolean,
    modelStatusText: String?,
    httpLogs: List<ByokManager.SanitizedHttpLog>,
    onTriggerModelDiscovery: (CharArray?) -> Unit,
    onDeleteKey: () -> Unit,
    onStatusMessage: (String) -> Unit
) {
    var apiKeyInput by remember { mutableStateOf("") }
    var manualModelInput by remember { mutableStateOf("") }
    var modelFilter by remember { mutableStateOf("ALL") }

    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("1. GEMINI API КЛЮЧ (BYOK)", color = TabGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
            "Шифруется в KeyStore устройства (${if (isStrongBoxSupported) "StrongBox" else "TEE"}). При сохранении сразу подтягивает живой список моделей твоего ключа.",
            color = TabMuted,
            fontSize = 10.sp
        )

        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            label = { Text(if (hasKey) "Ключ в KeyStore (введи новый для замены)" else "Вставь Gemini API ключ (AIza...)", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TabCyan,
                unfocusedBorderColor = TabBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val raw = apiKeyInput.trim()
                    if (raw.isNotEmpty()) {
                        val chars = raw.toCharArray()
                        ByokManager.saveKey(context, chars.clone())
                        apiKeyInput = ""
                        onTriggerModelDiscovery(chars)
                    }
                },
                enabled = !isFetchingModels && apiKeyInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = TabGreenDark),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Text("СОХРАНИТЬ + МОДЕЛИ", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }

            if (hasKey) {
                Button(
                    onClick = { onTriggerModelDiscovery(null) },
                    enabled = !isFetchingModels,
                    colors = ButtonDefaults.buttonColors(containerColor = TabCard),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    if (isFetchingModels) {
                        CircularProgressIndicator(color = TabCyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить модели", tint = TabCyan)
                    }
                }
                Button(
                    onClick = onDeleteKey,
                    colors = ButtonDefaults.buttonColors(containerColor = TabRed.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить ключ", tint = Color.White)
                }
            }
        }

        modelStatusText?.let { status ->
            Text(status, color = TabCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }

        HorizontalDivider(color = TabBorder)

        Text("2. ДОСТУПНЫЕ МОДЕЛИ КЛЮЧА (${discoveredModels.size})", color = TabGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ALL" to "Все", "HYDROGEN" to "Hydrogen", "FLASH" to "Flash", "PRO" to "Pro").forEach { (k, lbl) ->
                val sel = modelFilter == k
                Text(
                    text = lbl,
                    color = if (sel) Color.Black else TabMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) TabCyan else TabCard)
                        .border(1.dp, if (sel) TabCyan else TabBorder, RoundedCornerShape(8.dp))
                        .clickable { modelFilter = k }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        val filteredModels = remember(discoveredModels, modelFilter) {
            discoveredModels.filter { m ->
                when (modelFilter) {
                    "HYDROGEN" -> m.id.contains("hydrogen", ignoreCase = true) || m.displayName.contains("hydrogen", ignoreCase = true)
                    "FLASH" -> m.id.contains("flash", ignoreCase = true)
                    "PRO" -> m.id.contains("pro", ignoreCase = true)
                    else -> true
                }
            }
        }

        if (filteredModels.isEmpty()) {
            Text(
                text = if (hasKey) "Нажми кнопку обновления (↻) чтобы подтянуть модели ключа, либо впиши ID модели ниже вручную." else "Список подтянется сразу после ввода ключа.",
                color = TabMuted,
                fontSize = 10.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                filteredModels.forEach { m ->
                    val isCur = selectedModel == m.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCur) TabGreen.copy(alpha = 0.16f) else TabCard)
                            .border(1.dp, if (isCur) TabGreen else TabBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                ByokManager.saveModelName(context, m.id)
                                onStatusMessage("МОДЕЛЬ: ${m.id}")
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = m.id,
                                color = if (isCur) TabGreen else Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${m.displayName} • in:${m.inputTokenLimit / 1000}k / out:${m.outputTokenLimit / 1000}k",
                                color = TabMuted,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isCur) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Выбрано", tint = TabGreen, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manualModelInput,
                onValueChange = { manualModelInput = it },
                label = { Text("Или вписать ID вручную (напр. gemini-4-hydrogen)", fontSize = 10.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TabCyan,
                    unfocusedBorderColor = TabBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Button(
                onClick = {
                    val clean = manualModelInput.trim()
                    if (clean.isNotEmpty()) {
                        ByokManager.saveModelName(context, clean)
                        manualModelInput = ""
                        onStatusMessage("МОДЕЛЬ ЗАДАНА: $clean")
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TabCyan),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text("ОК", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }

        HorizontalDivider(color = TabBorder)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("3. ПРОМПТ ВИКИ (ПО УМОЛЧАНИЮ)", color = TabGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            TextButton(
                onClick = {
                    ByokManager.saveCustomPrompt(context, ByokManager.DEFAULT_PROMPT)
                    onStatusMessage("ПРОМПТ СБРОШЕН НА БАЗУ ВИКИ")
                }
            ) {
                Text("СБРОС", color = TabCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "Вика База" to ByokManager.DEFAULT_PROMPT,
                "Дозор" to ByokManager.PROMPT_SENTINEL,
                "OSINT" to ByokManager.PROMPT_OSINT,
                "OCR" to ByokManager.PROMPT_OCR,
                "Форензик" to ByokManager.PROMPT_FORENSIC,
                "Аналитик" to ByokManager.PROMPT_ANALYTICS
            ).forEach { (title, pText) ->
                Text(
                    text = title,
                    color = TabCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TabCyan.copy(alpha = 0.14f))
                        .border(1.dp, TabCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .clickable {
                            ByokManager.saveCustomPrompt(context, pText)
                            onStatusMessage("ПРОМПТ: $title")
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        OutlinedTextField(
            value = customPrompt,
            onValueChange = { ByokManager.saveCustomPrompt(context, it) },
            modifier = Modifier.fillMaxWidth().height(130.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TabGreen,
                unfocusedBorderColor = TabBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        HorizontalDivider(color = TabBorder)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "4. HTTP-ЖУРНАЛ API (${httpLogs.size}, БЕЗ КЛЮЧЕЙ)",
                color = TabCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            if (httpLogs.isNotEmpty()) {
                TextButton(onClick = {
                    val txt = httpLogs.joinToString("\n") { l ->
                        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(l.timestampMs))
                        "[$ts] ${l.method} ${l.endpoint} -> HTTP ${l.httpCode} (${l.latencyMs}ms): ${l.detail}"
                    }
                    SecureClipboard.copySensitive(context, "Zver Gemini HTTP Log", txt)
                    onStatusMessage("HTTP-ЖУРНАЛ СКОПИРОВАН (45с)")
                }) {
                    Text("КОПИРОВАТЬ", color = TabGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
        if (httpLogs.isEmpty()) {
            Text("Пока нет HTTP-вызовов в текущей сессии.", color = TabMuted, fontSize = 10.sp)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TabCard, RoundedCornerShape(8.dp))
                    .border(1.dp, TabBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                httpLogs.take(10).forEach { l ->
                    val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(l.timestampMs))
                    val codeColor = if (l.httpCode in 200..299) TabGreen else TabRed
                    Text(
                        text = "[$ts] HTTP ${l.httpCode} (${l.latencyMs}мс) ${l.method} ${l.endpoint}\n  ${l.detail}",
                        color = codeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun VikaQuickTaskChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(TabCard)
            .border(1.dp, TabCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = TabCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun VikaStatTile(
    title: String,
    value: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF0A1016), RoundedCornerShape(10.dp))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(title, color = TabMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(subtitle, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
private fun VikaChatMessageCard(
    entry: ByokManager.AiChatEntry,
    onCopy: () -> Unit
) {
    val timeStr = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TabCard, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (entry.isError) TabRed.copy(alpha = 0.6f) else TabGreen.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${if (entry.hadImage) "📸 " else ""}${entry.userTask}",
                color = TabCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = timeStr,
                color = TabMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = entry.replyText,
            color = if (entry.isError) TabRed else Color.White,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[${entry.engineBadge}] • ${entry.modelId} • ${entry.latencyMs}мс • ${entry.totalTokens}t",
                color = TabMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "КОПИРОВАТЬ",
                color = TabGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}
