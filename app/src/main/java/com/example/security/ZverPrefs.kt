package com.example.security

import android.content.Context
import com.example.camera.ExifStripper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ZverPrefs:
 * Lightweight local tactical configuration + security automation policies + BioTracker daily state.
 */
object ZverPrefs {
    private const val PREFS_NAME = "zver_tactical_prefs"

    private const val KEY_COMPRESSION_PRESET = "compression_preset"
    private const val KEY_ANTI_PRNU = "anti_prnu_enabled"
    private const val KEY_RAM_ONLY = "ram_only_mode"
    private const val KEY_PIN_SCRAMBLE = "pin_scramble_enabled"
    private const val KEY_SNATCH_PROTECTION = "snatch_protection_enabled"
    private const val KEY_FLIP_DISGUISE = "flip_disguise_enabled"
    private const val KEY_MUTE_VIDEO_AUDIO = "mute_video_audio"
    private const val KEY_SCREEN_REC_BLOCK = "screen_rec_block_enabled"
    private const val KEY_AUTO_LOCK_BG = "auto_lock_on_background"

    private const val KEY_DEAD_MAN_DAYS = "dead_man_days"
    private const val KEY_LAST_ACTIVITY_MS = "last_activity_epoch_ms"
    private const val KEY_FAILED_PIN_ATTEMPTS = "failed_pin_attempts"
    private const val KEY_MAX_FAILED_PIN_WIPE = "max_failed_pin_wipe"
    private const val KEY_LOCKOUT_UNTIL_MS = "pin_lockout_until_ms"

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

    fun isFlipToDisguiseEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLIP_DISGUISE, false)

    fun setFlipToDisguiseEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FLIP_DISGUISE, enabled).apply()
    }

    fun isMuteVideoAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MUTE_VIDEO_AUDIO, false)

    fun setMuteVideoAudio(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MUTE_VIDEO_AUDIO, muted).apply()
    }

    fun isScreenRecBlockEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREEN_REC_BLOCK, true)

    fun setScreenRecBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SCREEN_REC_BLOCK, enabled).apply()
    }

    fun isAutoLockOnBackground(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_LOCK_BG, true)

    fun setAutoLockOnBackground(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_LOCK_BG, enabled).apply()
    }

    fun getDeadManDays(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DEAD_MAN_DAYS, 0)

    fun setDeadManDays(context: Context, days: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_DEAD_MAN_DAYS, days.coerceAtLeast(0))
            .putLong(KEY_LAST_ACTIVITY_MS, System.currentTimeMillis())
            .apply()
    }

    fun getLastActivityEpochMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_ACTIVITY_MS, 0L)

    fun touchActivity(context: Context, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_ACTIVITY_MS, nowMs).apply()
    }

    fun getFailedPinAttempts(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FAILED_PIN_ATTEMPTS, 0)

    fun incrementFailedPinAttempts(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val next = prefs.getInt(KEY_FAILED_PIN_ATTEMPTS, 0) + 1
        val lockoutMs = when {
            next >= 6 -> 60_000L
            next >= 3 -> 15_000L
            else -> 0L
        }
        val editor = prefs.edit().putInt(KEY_FAILED_PIN_ATTEMPTS, next)
        if (lockoutMs > 0L) {
            editor.putLong(KEY_LOCKOUT_UNTIL_MS, System.currentTimeMillis() + lockoutMs)
        }
        editor.apply()
        return next
    }

    fun resetFailedPinAttempts(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FAILED_PIN_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
            .apply()
    }

    fun getLockoutUntilMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LOCKOUT_UNTIL_MS, 0L)

    fun getMaxFailedPinWipe(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_FAILED_PIN_WIPE, 0)

    fun setMaxFailedPinWipe(context: Context, maxAttempts: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAX_FAILED_PIN_WIPE, maxAttempts.coerceAtLeast(0)).apply()
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
