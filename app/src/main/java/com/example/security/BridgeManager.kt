package com.example.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * BridgeManager:
 * Toggles the opt-in external system bridge <activity-alias> entries
 * (.ExternalCameraBridgeAlias and .ExternalPickerBridgeAlias) at runtime without killing
 * the process. Disabled by default for maximum stealth until explicitly enabled.
 */
object BridgeManager {

    const val CAMERA_BRIDGE_ALIAS = "ExternalCameraBridgeAlias"
    const val PICKER_BRIDGE_ALIAS = "ExternalPickerBridgeAlias"

    private val ALL_ALIASES = listOf(CAMERA_BRIDGE_ALIAS, PICKER_BRIDGE_ALIAS)

    fun getComponentName(context: Context, aliasSuffix: String): ComponentName {
        val aliasPkg = com.example.MainActivity::class.java.`package`?.name ?: "com.example"
        return ComponentName(context.packageName, "$aliasPkg.$aliasSuffix")
    }

    fun isBridgeAliasEnabled(context: Context, aliasSuffix: String): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(
            getComponentName(context, aliasSuffix)
        )
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    fun isAnyBridgeAliasEnabled(context: Context): Boolean {
        return ALL_ALIASES.any { isBridgeAliasEnabled(context, it) }
    }

    fun setBridgeEnabled(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val targetState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        for (alias in ALL_ALIASES) {
            try {
                pm.setComponentEnabledSetting(
                    getComponentName(context, alias),
                    targetState,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}
        }
        ZverPrefs.setExternalBridgeEnabled(context, enabled)
    }

    fun verifyBridgeAliases(context: Context): Map<String, Boolean> {
        val pm = context.packageManager
        return ALL_ALIASES.associateWith { alias ->
            try {
                @Suppress("DEPRECATION")
                pm.getActivityInfo(
                    getComponentName(context, alias),
                    PackageManager.MATCH_DISABLED_COMPONENTS
                )
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
