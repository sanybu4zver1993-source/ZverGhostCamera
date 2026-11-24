package com.example.assist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * AutomationRouter:
 * Deterministic parser for zver:// deep-links, Termux / MacroDroid broadcast intents,
 * and factual inspection of local automation ecosystem packages.
 */
object AutomationRouter {

    const val ACTION_TRIGGER_CAPTURE = "com.example.camera.TRIGGER_CAPTURE"
    const val ACTION_TOGGLE_VIDEO = "com.example.camera.TOGGLE_VIDEO"
    const val ACTION_TOGGLE_TORCH = "com.example.camera.TOGGLE_TORCH"
    const val ACTION_TOGGLE_BLACKOUT = "com.example.camera.TOGGLE_BLACKOUT"
    const val ACTION_ARM_SENTINEL = "com.example.camera.ARM_SENTINEL"
    const val ACTION_SET_MODE = "com.example.camera.SET_MODE"
    const val ACTION_PANIC_LOCK = "com.example.camera.PANIC_LOCK"

    val ALLOWED_CAMERA_MODES = setOf("PHOTO", "VIDEO", "NIGHT", "VISOR", "SENTINEL", "DOCUMENTS")

    sealed class AutomationAction {
        data object CapturePhoto : AutomationAction()
        data object ToggleVideo : AutomationAction()
        data class ToggleTorch(val explicitState: Boolean? = null) : AutomationAction()
        data object ToggleStealth : AutomationAction()
        data class SetSentinelArmed(val armed: Boolean? = null) : AutomationAction()
        data class SwitchMode(val modeKey: String) : AutomationAction()
        data class SetRamOnly(val enabled: Boolean? = null) : AutomationAction()
        data object PanicLock : AutomationAction()
    }

    data class EcosystemAppStatus(
        val termux: Boolean,
        val termuxApi: Boolean,
        val shizuku: Boolean,
        val shizukuVersion: String?,
        val macroDroid: Boolean,
        val orbot: Boolean
    )

    fun parseCustomUri(uri: Uri?): AutomationAction? {
        if (uri == null || uri.scheme?.lowercase() != "zver") return null
        val route = uri.host?.lowercase()?.trim() ?: return null
        return when (route) {
            "capture" -> AutomationAction.CapturePhoto
            "video" -> AutomationAction.ToggleVideo
            "torch" -> AutomationAction.ToggleTorch(parseBooleanQuery(uri.getQueryParameter("on")))
            "stealth" -> AutomationAction.ToggleStealth
            "sentinel" -> AutomationAction.SetSentinelArmed(parseBooleanQuery(uri.getQueryParameter("arm")))
            "mode" -> {
                val raw = uri.getQueryParameter("name")?.uppercase()?.trim()
                    ?: uri.getQueryParameter("mode")?.uppercase()?.trim()
                if (raw != null && ALLOWED_CAMERA_MODES.contains(raw)) {
                    AutomationAction.SwitchMode(raw)
                } else null
            }
            "ram" -> AutomationAction.SetRamOnly(parseBooleanQuery(uri.getQueryParameter("on")))
            "lock" -> AutomationAction.PanicLock
            else -> null
        }
    }

    fun parseBroadcastIntent(intent: Intent?): AutomationAction? {
        val action = intent?.action ?: return null
        return when (action) {
            ACTION_TRIGGER_CAPTURE -> AutomationAction.CapturePhoto
            ACTION_TOGGLE_VIDEO -> AutomationAction.ToggleVideo
            ACTION_TOGGLE_TORCH -> {
                val hasExtra = intent.hasExtra("on")
                AutomationAction.ToggleTorch(if (hasExtra) intent.getBooleanExtra("on", false) else null)
            }
            ACTION_TOGGLE_BLACKOUT -> AutomationAction.ToggleStealth
            ACTION_ARM_SENTINEL -> {
                val hasExtra = intent.hasExtra("armed")
                AutomationAction.SetSentinelArmed(if (hasExtra) intent.getBooleanExtra("armed", true) else null)
            }
            ACTION_SET_MODE -> {
                val mode = intent.getStringExtra("mode")?.uppercase()?.trim()
                if (mode != null && ALLOWED_CAMERA_MODES.contains(mode)) {
                    AutomationAction.SwitchMode(mode)
                } else null
            }
            ACTION_PANIC_LOCK -> AutomationAction.PanicLock
            else -> null
        }
    }

    private fun parseBooleanQuery(raw: String?): Boolean? {
        val v = raw?.lowercase()?.trim() ?: return null
        return when (v) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageVersionOrNull(context: Context, pkg: String): String? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getPackageInfo(pkg, 0)
            }
            info.versionName ?: "installed"
        } catch (_: Exception) {
            null
        }
    }

    fun inspectEcosystem(context: Context): EcosystemAppStatus {
        val shizukuVer = getPackageVersionOrNull(context, "moe.shizuku.privileged.api")
        val orbotOk = getPackageVersionOrNull(context, "org.torproject.android") != null ||
            getPackageVersionOrNull(context, "org.torproject.torbrowser") != null
        return EcosystemAppStatus(
            termux = getPackageVersionOrNull(context, "com.termux") != null,
            termuxApi = getPackageVersionOrNull(context, "com.termux.api") != null,
            shizuku = shizukuVer != null,
            shizukuVersion = shizukuVer,
            macroDroid = getPackageVersionOrNull(context, "com.arlosoft.macrodroid") != null,
            orbot = orbotOk
        )
    }
}
