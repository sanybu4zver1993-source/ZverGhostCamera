package com.example.security

import android.content.Context
import com.example.camera.ExifStripper

/**
 * ZverPrefs:
 * Lightweight local tactical configuration + security automation policies + CyberVisor pulse metrics.
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
    private const val KEY_SCREEN_REC_BLOCK = "screen_rec_block_v2"
    private const val KEY_AUTO_LOCK_BG = "auto_lock_on_background"

    private const val KEY_DEAD_MAN_DAYS = "dead_man_days"
    private const val KEY_LAST_ACTIVITY_MS = "last_activity_epoch_ms"
    private const val KEY_FAILED_PIN_ATTEMPTS = "failed_pin_attempts"
    private const val KEY_MAX_FAILED_PIN_WIPE = "max_failed_pin_wipe"
    private const val KEY_LOCKOUT_UNTIL_MS = "pin_lockout_until_ms"

    private const val KEY_LAST_PULSE_BPM = "visor_last_pulse_bpm"
    private const val KEY_LAST_PULSE_TS = "visor_last_pulse_ts"
    private const val KEY_LAST_HRV_MS = "visor_last_hrv_ms"

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
            .getBoolean(KEY_SCREEN_REC_BLOCK, false)

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

    fun saveLastPulseBpm(
        context: Context,
        bpm: Int,
        timestampMs: Long = System.currentTimeMillis(),
        hrvMs: Int = 0
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_PULSE_BPM, bpm.coerceIn(30, 220))
            .putInt(KEY_LAST_HRV_MS, hrvMs.coerceIn(0, 300))
            .putLong(KEY_LAST_PULSE_TS, timestampMs)
            .apply()
    }

    fun getLastPulseBpm(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_PULSE_BPM, 0)

    fun getLastHrvMs(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_HRV_MS, 0)

    fun getLastPulseTimestampMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_PULSE_TS, 0L)

    fun clearLastPulseBpm(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_PULSE_BPM, 0)
            .putInt(KEY_LAST_HRV_MS, 0)
            .putLong(KEY_LAST_PULSE_TS, 0L)
            .apply()
    }
}
