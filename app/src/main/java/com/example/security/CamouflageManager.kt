package com.example.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * CamouflageManager:
 * Changes the app launcher icon and display label at runtime (Tella/Fossify style)
 * by toggling <activity-alias> components without killing the process.
 */
object CamouflageManager {

    enum class CamouflageMode(val aliasSuffix: String, val displayName: String) {
        ZVER("MainActivityDefault", "Zver Camera"),
        CALCULATOR("MainActivityCalculator", "Calculator"),
        SYSTEM_TOOLS("MainActivitySystem", "System Tools");

        fun getComponentName(context: Context): ComponentName {
            val aliasPkg = com.example.MainActivity::class.java.`package`?.name ?: "com.example"
            return ComponentName(context.packageName, "$aliasPkg.$aliasSuffix")
        }
    }

    fun getCurrentCamouflage(context: Context): CamouflageMode {
        val pm = context.packageManager
        for (mode in CamouflageMode.values()) {
            val component = mode.getComponentName(context)
            val state = pm.getComponentEnabledSetting(component)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return mode
            }
        }
        return CamouflageMode.ZVER
    }

    fun setCamouflage(context: Context, targetMode: CamouflageMode) {
        val pm = context.packageManager
        for (mode in CamouflageMode.values()) {
            val component = mode.getComponentName(context)
            val state = if (mode == targetMode) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }

    fun verifyManifestAliases(context: Context): Map<CamouflageMode, Boolean> {
        val pm = context.packageManager
        return CamouflageMode.values().associateWith { mode ->
            try {
                @Suppress("DEPRECATION")
                pm.getActivityInfo(mode.getComponentName(context), PackageManager.MATCH_DISABLED_COMPONENTS)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
