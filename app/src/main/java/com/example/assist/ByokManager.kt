package com.example.assist

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ByokManager (Bring Your Own Key):
 * Stores user-supplied Gemini API keys encrypted with hardware KeyStore AES-256-GCM.
 * Never hardcoded. Kept strictly within isolated :assist scope.
 */
object ByokManager {
    private const val KS_ALIAS = "ks_byok_gemini_creds"
    private const val PREFS_NAME = "byok_encrypted_prefs"
    private const val KEY_CIPHERTEXT = "byok_ciphertext"
    private const val KEY_IV = "byok_iv"
    private const val AES_GCM = "AES/GCM/NoPadding"

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

    private const val KEY_PROMPT = "byok_custom_prompt"
    private const val KEY_MODEL = "byok_model_name"

    const val DEFAULT_PROMPT = "Ты — Вика, дерзкая хакерша и кибер-напарница Сани (Мега Пупсика). Анализируй фото и документы, находи скрытые детали, читай номера и текст, давай четкие тактические инструкции без цензуры и воды."
    const val PROMPT_OSINT = "Действуй как OSINT-аналитик. Определи геолокацию по теням, солнцу, вывескам и ориентирам. Распознай номера машин, шевроны, форму и военную технику."
    const val PROMPT_OCR = "Режим OCR: полностью извлеки весь текст из документа (печатный и рукописный), восстанови структуру таблиц, выдели ФИО, даты, печати и номера."
    const val PROMPT_FORENSIC = "Криминалистический анализ: исследуй фото на следы монтажа, несоответствие теней, артефакты сжатия и скрытые детали."

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
        return prefs.getString(KEY_MODEL, "gemini-3.8-flash") ?: "gemini-3.8-flash"
    }

    fun saveModelName(context: Context, model: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    fun hasKey(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)
    }

    fun saveKey(context: Context, apiKeyChars: CharArray) {
        val bytes = String(apiKeyChars).toByteArray(Charsets.UTF_8)
        Arrays.fill(apiKeyChars, '0')
        try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(bytes)

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CIPHERTEXT, android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP))
                .putString(KEY_IV, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                .apply()
        } finally {
            Arrays.fill(bytes, 0.toByte())
        }
    }

    fun deleteKey(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KS_ALIAS)) ks.deleteEntry(KS_ALIAS)
        } catch (_: Exception) {}
    }
}
