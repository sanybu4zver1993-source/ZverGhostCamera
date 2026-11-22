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
    private const val KEY_PHOTO_IQ_PROFILE = "photo_iq_profile"
    private const val KEY_ZOOM_IQ_ENABLED = "zoom_iq_enabled"
    private const val KEY_ZEBRA_THRESHOLD = "zebra_luma_threshold"
    private const val KEY_SMART_COMPRESSION = "smart_compression_enabled"
    private const val KEY_MULTI_FRAME_MODE = "multi_frame_stack_mode"
    private const val KEY_VOLUME_KEY_ACTION = "volume_key_action"
    private const val KEY_GYRO_EIS_ENABLED = "gyro_eis_lite_enabled"
    private const val KEY_TIMELAPSE_INTERVAL_MS = "timelapse_interval_ms"
    private const val KEY_LAST_CRASH_BREADCRUMB = "last_crash_breadcrumb"

    enum class VolumeKeyAction(val labelRu: String) {
        SHUTTER_OR_VIDEO("Кадр / Видео"),
        STEALTH_BLACKOUT("Стелс-экран"),
        PANIC_LOCK_GATE("Блок / Скрыть"),
        TORCH_TOGGLE("Фонарик")
    }

    fun getVolumeKeyAction(context: Context): VolumeKeyAction {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VOLUME_KEY_ACTION, VolumeKeyAction.SHUTTER_OR_VIDEO.name)
        return try {
            VolumeKeyAction.valueOf(raw ?: VolumeKeyAction.SHUTTER_OR_VIDEO.name)
        } catch (_: Exception) {
            VolumeKeyAction.SHUTTER_OR_VIDEO
        }
    }

    fun setVolumeKeyAction(context: Context, action: VolumeKeyAction) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VOLUME_KEY_ACTION, action.name).apply()
    }

    fun isGyroEisEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GYRO_EIS_ENABLED, false)

    fun setGyroEisEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GYRO_EIS_ENABLED, enabled).apply()
    }

    fun getTimeLapseIntervalMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_TIMELAPSE_INTERVAL_MS, 2000L).coerceIn(500L, 10_000L)

    fun setTimeLapseIntervalMs(context: Context, intervalMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_TIMELAPSE_INTERVAL_MS, intervalMs.coerceIn(500L, 10_000L)).apply()
    }

    fun isSmartCompressionEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SMART_COMPRESSION, true)

    fun setSmartCompressionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SMART_COMPRESSION, enabled).apply()
    }

    fun getMultiFrameMode(context: Context): com.example.camera.MultiFrameStacker.StackMode? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MULTI_FRAME_MODE, null) ?: return null
        return try {
            com.example.camera.MultiFrameStacker.StackMode.valueOf(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun setMultiFrameMode(context: Context, mode: com.example.camera.MultiFrameStacker.StackMode?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (mode == null) {
            editor.remove(KEY_MULTI_FRAME_MODE)
        } else {
            editor.putString(KEY_MULTI_FRAME_MODE, mode.name)
        }
        editor.apply()
    }

    fun getPhotoIqProfile(context: Context): com.example.camera.PhotoIqEngine.PhotoIqProfile {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PHOTO_IQ_PROFILE, com.example.camera.PhotoIqEngine.PhotoIqProfile.AUTO_IQ.name)
        return try {
            com.example.camera.PhotoIqEngine.PhotoIqProfile.valueOf(
                raw ?: com.example.camera.PhotoIqEngine.PhotoIqProfile.AUTO_IQ.name
            )
        } catch (_: Exception) {
            com.example.camera.PhotoIqEngine.PhotoIqProfile.AUTO_IQ
        }
    }

    fun setPhotoIqProfile(context: Context, profile: com.example.camera.PhotoIqEngine.PhotoIqProfile) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PHOTO_IQ_PROFILE, profile.name).apply()
    }

    fun isZoomIqEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ZOOM_IQ_ENABLED, true)

    fun setZoomIqEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ZOOM_IQ_ENABLED, enabled).apply()
    }

    fun getZebraThreshold(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ZEBRA_THRESHOLD, 240).coerceIn(210, 252)

    fun setZebraThreshold(context: Context, threshold: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ZEBRA_THRESHOLD, threshold.coerceIn(210, 252)).apply()
    }

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

    fun recordLastCrashBreadcrumb(context: Context, summary: String) {
        try {
            val safe = summary.take(360)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CRASH_BREADCRUMB, safe)
                .commit()
        } catch (_: Throwable) {}
    }

    fun getLastCrashBreadcrumb(context: Context): String? =
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_CRASH_BREADCRUMB, null)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }

    fun clearLastCrashBreadcrumb(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_CRASH_BREADCRUMB)
                .apply()
        } catch (_: Throwable) {}
    }
}
