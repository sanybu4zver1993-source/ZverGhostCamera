package com.example.assist

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
 * - Caches discovered models unlocked by that specific key.
 * - Tracks volatile RAM-only AI session chat history & token usage analytics.
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
        val isError: Boolean = false
    )

    private val ramLock = Any()
    private var sessionStats = AiSessionStats()
    private val chatHistory = mutableListOf<AiChatEntry>()

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
    }

    fun getModelName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL_FALLBACK) ?: DEFAULT_MODEL_FALLBACK
    }

    fun saveModelName(context: Context, model: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL, model.removePrefix("models/").trim())
            .apply()
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
        }
    }

    fun addChatEntry(entry: AiChatEntry) {
        synchronized(ramLock) {
            chatHistory.add(0, entry)
            if (chatHistory.size > 20) {
                chatHistory.removeAt(chatHistory.lastIndex)
            }
        }
    }

    fun getStats(): AiSessionStats = synchronized(ramLock) { sessionStats }

    fun getChatHistory(): List<AiChatEntry> = synchronized(ramLock) { chatHistory.toList() }

    fun clearSessionMemory() {
        synchronized(ramLock) {
            chatHistory.clear()
            sessionStats = AiSessionStats()
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
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KS_ALIAS)) ks.deleteEntry(KS_ALIAS)
        } catch (_: Exception) {}
    }
}
