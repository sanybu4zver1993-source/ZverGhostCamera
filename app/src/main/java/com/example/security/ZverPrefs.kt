package com.example.security

import android.content.Context
import com.example.camera.ExifStripper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ZverPrefs:
 * Lightweight local tactical configuration + daily-reset BioTracker checklist state.
 */
object ZverPrefs {
    private const val PREFS_NAME = "zver_tactical_prefs"

    private const val KEY_COMPRESSION_PRESET = "compression_preset"
    private const val KEY_ANTI_PRNU = "anti_prnu_enabled"
    private const val KEY_RAM_ONLY = "ram_only_mode"
    private const val KEY_PIN_SCRAMBLE = "pin_scramble_enabled"
    private const val KEY_SNATCH_PROTECTION = "snatch_protection_enabled"
    private const val KEY_MUTE_VIDEO_AUDIO = "mute_video_audio"

    private const val KEY_JELLY_DATE = "jelly_last_date"
    private const val KEY_JELLY_DONE_PREFIX = "jelly_done_"

    fun getCompressionPreset(context: Context): ExifStripper.CompressionPreset {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COMPRESSION_PRESET, ExifStripper.CompressionPreset.ULTRA_TINY.name)
        return try {
            ExifStripper.CompressionPreset.valueOf(raw ?: ExifStripper.CompressionPreset.ULTRA_TINY.name)
        } catch (_: Exception) {
            ExifStripper.CompressionPreset.ULTRA_TINY
        }
    }

    fun setCompressionPreset(context: Context, preset: ExifStripper.CompressionPreset) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_COMPRESSION_PRESET, preset.name).apply()
    }

    fun isAntiPrnuEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ANTI_PRNU, true)

    fun setAntiPrnuEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ANTI_PRNU, enabled).apply()
    }

    fun isRamOnlyMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RAM_ONLY, false)

    fun setRamOnlyMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RAM_ONLY, enabled).apply()
    }

    fun isPinScrambleEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PIN_SCRAMBLE, true)

    fun setPinScrambleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PIN_SCRAMBLE, enabled).apply()
    }

    fun isSnatchProtectionEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SNATCH_PROTECTION, true)

    fun setSnatchProtectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SNATCH_PROTECTION, enabled).apply()
    }

    fun isMuteVideoAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MUTE_VIDEO_AUDIO, false)

    fun setMuteVideoAudio(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MUTE_VIDEO_AUDIO, muted).apply()
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun getJellyChecklist(context: Context, itemIds: List<String>): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = todayKey()
        if (prefs.getString(KEY_JELLY_DATE, null) != today) {
            val editor = prefs.edit().putString(KEY_JELLY_DATE, today)
            for (id in itemIds) editor.remove(KEY_JELLY_DONE_PREFIX + id)
            editor.apply()
            return emptySet()
        }
        return itemIds.filter { prefs.getBoolean(KEY_JELLY_DONE_PREFIX + it, false) }.toSet()
    }

    fun setJellyItemDone(context: Context, itemId: String, done: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_JELLY_DATE, todayKey())
            .putBoolean(KEY_JELLY_DONE_PREFIX + itemId, done)
            .apply()
    }
}
