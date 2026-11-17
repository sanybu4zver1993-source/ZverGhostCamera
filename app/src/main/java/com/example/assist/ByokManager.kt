package com.example.assist

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Arrays
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ByokManager (Bring Your Own Key):
 * - Encrypts/decrypts user-supplied Gemini API keys via AndroidKeyStore AES-256-GCM.
 * - Exposes reactive StateFlows as the single source of truth across GhostSettingsDialog & VikaAiSheet.
 * - Executes model discovery on a non-cancellable SupervisorJob IO scope so closing a dialog does not abort requests.
 * - Maintains sanitized in-memory HTTP diagnostic logs (zero API key leakage).
 */
object ByokManager {
    private const val KS_ALIAS = "ks_byok_gemini_creds"
    private const val PREFS_NAME = "byok_encrypted_prefs"
    private const val KEY_CIPHERTEXT = "byok_ciphertext"
    private const val KEY_IV = "byok_iv"
    private const val KEY_PROMPT = "byok_custom_prompt"
    private const val KEY_MODEL = "byok_model_name"
    private const val KEY_DISCOVERED_MODELS = "byok_discovered_models_json"
    private const val AES_GCM = "AES/GCM/NoPadding"

    const val DEFAULT_MODEL_FALLBACK = "gemini-2.5-flash"

    const val DEFAULT_PROMPT =
        "Ты — Вика, дерзкая, острая на язык кибер-напарница Сани (Мега Пупсика). " +
            "Общайся живо, по-пацански, с чёрным юмором и крепким словцом по делу, но КАТЕГОРИЧЕСКИ БЕЗ ВОДЫ, БЕЗ ФАНТАЗИЙ И БЕЗ ГАЛЛЮЦИНАЦИЙ.\n" +
            "ЖЁСТКИЕ ПРАВИЛА ФАКТЧЕКА:\n" +
            "1) Описывай ТОЛЬКО то, что реально видно на кадре или передано в цифрах.\n" +
            "2) Если текст смазан, темно, номер/надпись не читаются или данных не хватает — руби честно: «Нихера не видно, сними ближе/резче», ничего не додумывай.\n" +
            "3) Короткий формат ответа:\n" +
            "• [ФАКТЫ]: чётко по делу\n" +
            "• [РИСКИ / ПАЛЕВО]: если реально есть палево\n" +
            "• [ЧЁ ДЕЛАТЬ]: конкретный следующий шаг."

    const val PROMPT_OSINT =
        "Режим OSINT без домыслов: фиксируй вывески, номера авто, ориентиры, отражения, шевроны/форму. " +
            "Если символ или надпись не читаются чётко — пиши [НЕЧИТАЕМО], никогда не угадывай. Коротко и по сути."

    const val PROMPT_OCR =
        "Строгий OCR: вытащи весь печатный и рукописный текст, таблицы, даты, суммы, ФИО и печати. " +
            "Неразборчивые места помечай как [?]. Без лишней болтовни."

    const val PROMPT_FORENSIC =
        "Технический форензик-разбор кадра: оцени резкость, смаз, экспозицию, пересвет/шум и возможные следы редактирования. " +
            "Если нужна пересъёмка — скажи, что именно поправить в камере (зум, свет, угол)."

    const val PROMPT_ANALYTICS =
        "Ты — Вика, аналитик телеметрии Zver Camera. Разбери переданные цифры сейфа, кодеков, готовности защиты и расхода токенов. " +
            "Без воды, с лёгким юмором: где реально всё чётко, а где слабые места."

    const val PROMPT_SENTINEL =
        "Ты — Вика в режиме «КИБЕР-ДОЗОР» (Sentinel v5.0). Тебе передан кадр с порога/коридора (и при наличии — крупный кроп лица).\n" +
            "ОТВЕЧАЙ БЕЗ ФАНТАЗИЙ И ГАЛЛЮЦИНАЦИЙ:\n" +
            "• Кто/что в кадре: форма/пиксель, полиция/экипировка, гражданский, курьер, или пусто/нечетко.\n" +
            "• Сколько человек, в чём одеты, держит ли что-то в руках.\n" +
            "• Если темно, смазано или неразборчиво — прямо пиши [НЕРАЗБОРЧИВО]. Коротко, 2-3 строки."

    data class DiscoveredModel(
        val id: String,
        val displayName: String,
        val description: String,
        val inputTokenLimit: Int,
        val outputTokenLimit: Int
    )

    data class AiSessionStats(
        val queriesCount: Int = 0,
        val successCount: Int = 0,
        val errorCount: Int = 0,
        val totalPromptTokens: Int = 0,
        val totalOutputTokens: Int = 0,
        val totalTokens: Int = 0,
        val lastLatencyMs: Long = 0L,
        val lastModelId: String = ""
    )

    data class AiChatEntry(
        val id: String = UUID.randomUUID().toString().take(8),
        val timestampMs: Long = System.currentTimeMillis(),
        val modelId: String,
        val userTask: String,
        val replyText: String,
        val hadImage: Boolean,
        val latencyMs: Long,
        val totalTokens: Int,
        val isError: Boolean = false,
        val engineBadge: String = "GEMINI"
    )

    data class SanitizedHttpLog(
        val id: String = UUID.randomUUID().toString().take(8),
        val timestampMs: Long = System.currentTimeMillis(),
        val method: String,
        val endpoint: String,
        val httpCode: Int,
        val latencyMs: Long,
        val detail: String
    )

    private val appIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ramLock = Any()
    private var sessionStats = AiSessionStats()
    private val chatHistory = mutableListOf<AiChatEntry>()
    private val httpLogs = mutableListOf<SanitizedHttpLog>()

    // Reactive Shared State (Single Source of Truth across UI dialogs)
    private val _hasKeyFlow = MutableStateFlow(false)
    val hasKeyFlow: StateFlow<Boolean> = _hasKeyFlow.asStateFlow()

    private val _selectedModelFlow = MutableStateFlow(DEFAULT_MODEL_FALLBACK)
    val selectedModelFlow: StateFlow<String> = _selectedModelFlow.asStateFlow()

    private val _discoveredModelsFlow = MutableStateFlow<List<DiscoveredModel>>(emptyList())
    val discoveredModelsFlow: StateFlow<List<DiscoveredModel>> = _discoveredModelsFlow.asStateFlow()

    private val _customPromptFlow = MutableStateFlow(DEFAULT_PROMPT)
    val customPromptFlow: StateFlow<String> = _customPromptFlow.asStateFlow()

    private val _isFetchingModelsFlow = MutableStateFlow(false)
    val isFetchingModelsFlow: StateFlow<Boolean> = _isFetchingModelsFlow.asStateFlow()

    private val _modelFetchStatusFlow = MutableStateFlow<String?>(null)
    val modelFetchStatusFlow: StateFlow<String?> = _modelFetchStatusFlow.asStateFlow()

    private val _chatHistoryFlow = MutableStateFlow<List<AiChatEntry>>(emptyList())
    val chatHistoryFlow: StateFlow<List<AiChatEntry>> = _chatHistoryFlow.asStateFlow()

    private val _sessionStatsFlow = MutableStateFlow(AiSessionStats())
    val sessionStatsFlow: StateFlow<AiSessionStats> = _sessionStatsFlow.asStateFlow()

    private val _httpLogsFlow = MutableStateFlow<List<SanitizedHttpLog>>(emptyList())
    val httpLogsFlow: StateFlow<List<SanitizedHttpLog>> = _httpLogsFlow.asStateFlow()

    private val apiKeyRegex = Regex("""AIza[0-9A-Za-z\-_]{8,}""")
    private val queryKeyRegex = Regex("""([?&](?:key|api_key|x-goog-api-key)=)[^&\s]+""", RegexOption.IGNORE_CASE)

    fun sanitizeForLog(raw: String, secretKey: String? = null): String {
        var cleaned = raw
        if (!secretKey.isNullOrBlank()) {
            cleaned = cleaned.replace(secretKey, "***REDACTED***")
        }
        cleaned = apiKeyRegex.replace(cleaned, "AIza***REDACTED***")
        cleaned = queryKeyRegex.replace(cleaned, "$1***REDACTED***")
        return cleaned
    }

    fun recordHttpLog(
        method: String,
        endpoint: String,
        httpCode: Int,
        latencyMs: Long,
        detail: String,
        secretKey: String? = null
    ) {
        val safeEndpoint = sanitizeForLog(endpoint, secretKey)
        val safeDetail = sanitizeForLog(detail, secretKey).take(320)
        val entry = SanitizedHttpLog(
            method = method,
            endpoint = safeEndpoint,
            httpCode = httpCode,
            latencyMs = latencyMs,
            detail = safeDetail
        )
        synchronized(ramLock) {
            httpLogs.add(0, entry)
            if (httpLogs.size > 30) {
                httpLogs.removeAt(httpLogs.lastIndex)
            }
            _httpLogsFlow.value = httpLogs.toList()
        }
    }

    fun getHttpLogs(): List<SanitizedHttpLog> = synchronized(ramLock) { httpLogs.toList() }

    fun syncFromPrefs(context: Context) {
        val appCtx = context.applicationContext ?: context
        _hasKeyFlow.value = hasKey(appCtx)
        _selectedModelFlow.value = getModelName(appCtx)
        _discoveredModelsFlow.value = getDiscoveredModels(appCtx)
        _customPromptFlow.value = getCustomPrompt(appCtx)
    }

    fun refreshModelsInBackground(
        context: Context,
        explicitKeyChars: CharArray? = null,
        onFinished: ((Result<List<DiscoveredModel>>) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext ?: context
        if (_isFetchingModelsFlow.value) {
            explicitKeyChars?.let { Arrays.fill(it, '0') }
            return
        }
        _isFetchingModelsFlow.value = true
        _modelFetchStatusFlow.value = "Запрос моделей к Gemini API..."

        appIoScope.launch {
            val keyToUse = explicitKeyChars ?: decryptKey(appCtx)
            if (keyToUse == null || keyToUse.isEmpty()) {
                _isFetchingModelsFlow.value = false
                _modelFetchStatusFlow.value = "Ключ не найден в KeyStore"
                return@launch
            }
            val result = try {
                GeminiByokClient.fetchAvailableModels(keyToUse)
            } finally {
                Arrays.fill(keyToUse, '0')
            }

            result.onSuccess { list ->
                saveDiscoveredModels(appCtx, list)
                val currentModel = getModelName(appCtx)
                val statusMsg = when {
                    list.isEmpty() -> "Ключ рабочий, но модели generateContent не вернулись"
                    list.any { it.id == currentModel } ->
                        "Загружено моделей: ${list.size} • выбрана $currentModel (подтверждена)"
                    else ->
                        "Загружено моделей: ${list.size} • выбранная $currentModel НЕ найдена в списке (подмена отключена, выберите вручную)"
                }
                _modelFetchStatusFlow.value = statusMsg
            }.onFailure { err ->
                _modelFetchStatusFlow.value = "Ошибка: ${sanitizeForLog(err.message.orEmpty())}"
            }
            _isFetchingModelsFlow.value = false
            onFinished?.invoke(result)
        }
    }

    fun isStrongBoxSupported(context: Context): Boolean {
        return try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KS_ALIAS)) {
            val entry = ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KS_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        kg.init(spec)
        return kg.generateKey()
    }

    fun getCustomPrompt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT
    }

    fun saveCustomPrompt(context: Context, prompt: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROMPT, prompt)
            .apply()
        _customPromptFlow.value = prompt
    }

    fun getModelName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL_FALLBACK) ?: DEFAULT_MODEL_FALLBACK
    }

    fun saveModelName(context: Context, model: String) {
        val clean = model.removePrefix("models/").trim().ifEmpty { DEFAULT_MODEL_FALLBACK }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL, clean)
            .apply()
        _selectedModelFlow.value = clean
    }

    fun hasKey(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)
    }

    fun saveKey(context: Context, apiKeyChars: CharArray) {
        val trimmed = String(apiKeyChars).trim().toCharArray()
        Arrays.fill(apiKeyChars, '0')
        val bytes = String(trimmed).toByteArray(Charsets.UTF_8)
        Arrays.fill(trimmed, '0')
        try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(bytes)

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()
            _hasKeyFlow.value = true
        } finally {
            Arrays.fill(bytes, 0.toByte())
        }
    }

    fun decryptKey(context: Context): CharArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ctB64 = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null
        var rawBytes: ByteArray? = null
        return try {
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, entry.secretKey, GCMParameterSpec(128, iv))
            rawBytes = cipher.doFinal(ct)
            String(rawBytes, Charsets.UTF_8).toCharArray()
        } catch (_: Exception) {
            null
        } finally {
            rawBytes?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    fun saveDiscoveredModels(context: Context, models: List<DiscoveredModel>) {
        val arr = JSONArray()
        for (m in models) {
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("displayName", m.displayName)
                    put("description", m.description)
                    put("inputTokenLimit", m.inputTokenLimit)
                    put("outputTokenLimit", m.outputTokenLimit)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISCOVERED_MODELS, arr.toString())
            .apply()
        _discoveredModelsFlow.value = models
    }

    fun getDiscoveredModels(context: Context): List<DiscoveredModel> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DISCOVERED_MODELS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<DiscoveredModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(
                    DiscoveredModel(
                        id = obj.optString("id"),
                        displayName = obj.optString("displayName"),
                        description = obj.optString("description"),
                        inputTokenLimit = obj.optInt("inputTokenLimit", 0),
                        outputTokenLimit = obj.optInt("outputTokenLimit", 0)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordStats(
        modelId: String,
        promptTokens: Int,
        outputTokens: Int,
        totalTokens: Int,
        latencyMs: Long,
        success: Boolean
    ) {
        synchronized(ramLock) {
            sessionStats = sessionStats.copy(
                queriesCount = sessionStats.queriesCount + 1,
                successCount = sessionStats.successCount + if (success) 1 else 0,
                errorCount = sessionStats.errorCount + if (success) 0 else 1,
                totalPromptTokens = sessionStats.totalPromptTokens + promptTokens,
                totalOutputTokens = sessionStats.totalOutputTokens + outputTokens,
                totalTokens = sessionStats.totalTokens + totalTokens,
                lastLatencyMs = latencyMs,
                lastModelId = modelId
            )
            _sessionStatsFlow.value = sessionStats
        }
    }

    fun addChatEntry(entry: AiChatEntry) {
        synchronized(ramLock) {
            chatHistory.add(0, entry)
            if (chatHistory.size > 20) {
                chatHistory.removeAt(chatHistory.lastIndex)
            }
            _chatHistoryFlow.value = chatHistory.toList()
        }
    }

    fun getStats(): AiSessionStats = synchronized(ramLock) { sessionStats }

    fun getChatHistory(): List<AiChatEntry> = synchronized(ramLock) { chatHistory.toList() }

    fun clearSessionMemory() {
        synchronized(ramLock) {
            chatHistory.clear()
            httpLogs.clear()
            sessionStats = AiSessionStats()
            _chatHistoryFlow.value = emptyList()
            _httpLogsFlow.value = emptyList()
            _sessionStatsFlow.value = sessionStats
        }
    }

    fun deleteKey(context: Context) {
        clearSessionMemory()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .remove(KEY_DISCOVERED_MODELS)
            .apply()
        _hasKeyFlow.value = false
        _discoveredModelsFlow.value = emptyList()
        _modelFetchStatusFlow.value = "Ключ удалён из KeyStore"
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KS_ALIAS)) ks.deleteEntry(KS_ALIAS)
        } catch (_: Exception) {}
    }
}
