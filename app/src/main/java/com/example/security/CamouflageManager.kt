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
            return ComponentName(context.packageName, "${context.packageName}.$aliasSuffix")
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
}
