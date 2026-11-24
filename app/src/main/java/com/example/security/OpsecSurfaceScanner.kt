package com.example.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityManager

/**
 * OpsecSurfaceScanner (Часть Е):
 * 100% non-privileged, permission-minimal live hardware/environment OPSEC audit for Android 15 (Redmi 13C):
 * - External / Virtual / Presentation displays (detects Cast, Wi-Fi Display, scrcpy mirrors).
 * - Enabled Accessibility services (potential UI-tree readers / overlay watchers).
 * - USB data cable, ADB debugging, and Developer Options status.
 * - Active VPN / Tor tunnel & Private DNS (DoT) status via already-declared ACCESS_NETWORK_STATE.
 * - Live hardware sensor count inventory.
 */
object OpsecSurfaceScanner {

    data class OpsecSurfaceReport(
        val externalDisplayNames: List<String>,
        val accessibilityServiceLabels: List<String>,
        val adbEnabled: Boolean,
        val devOptionsEnabled: Boolean,
        val usbPlugged: Boolean,
        val vpnActive: Boolean,
        val privateDnsHost: String?,
        val isPrivateDnsStrictOrAuto: Boolean,
        val sensorCount: Int,
        val hasHighRiskExposure: Boolean,
        val notesRu: List<String>
    ) {
        fun formatCompactSummaryRu(): String = buildString {
            appendLine("🛡️ СКАНЕР ПОВЕРХНОСТИ УГРОЗ (OPSEC):")
            appendLine("• Экраны/трансляция: ${if (externalDisplayNames.isEmpty()) "чисто (только основной экран)" else "ВНИМАНИЕ (${externalDisplayNames.joinToString()})"}")
            appendLine("• Accessibility-наблюдатели: ${if (accessibilityServiceLabels.isEmpty()) "0 активных" else "активно ${accessibilityServiceLabels.size}: ${accessibilityServiceLabels.take(3).joinToString()}"}")
            appendLine("• USB-кабель: ${if (usbPlugged) "ПОДКЛЮЧЁН" else "нет"} | ADB: ${if (adbEnabled) "ВКЛ" else "выкл"} | Dev: ${if (devOptionsEnabled) "ВКЛ" else "выкл"}")
            val dnsLabel = when {
                !privateDnsHost.isNullOrBlank() -> "Private DNS ($privateDnsHost)"
                isPrivateDnsStrictOrAuto -> "Авто/Системный DNS"
                else -> "Обычный/Не задан"
            }
            appendLine("• Туннель: VPN/Tor=${if (vpnActive) "АКТИВЕН" else "нет"} | $dnsLabel | Датчиков: $sensorCount")
            if (notesRu.isNotEmpty()) {
                notesRu.forEach { appendLine("  ⚠ $it") }
            }
        }.trimEnd()
    }

    fun scanSurface(context: Context): OpsecSurfaceReport {
        val externalDisplays = detectExternalDisplays(context)
        val a11yLabels = detectEnabledAccessibilityServices(context)
        val adbEnabled = readGlobalInt(context, Settings.Global.ADB_ENABLED) == 1
        val devOptions = readGlobalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
        val usbPlugged = isUsbCablePlugged(context)
        val (vpnActive, privateDnsHost, dnsAuto) = inspectNetworkPrivacy(context)
        val sensorCount = countHardwareSensors(context)

        val notes = mutableListOf<String>()
        if (externalDisplays.isNotEmpty()) {
            notes += "Обнаружен второй/виртуальный дисплей — возможен захват экрана или Cast"
        }
        if (a11yLabels.isNotEmpty()) {
            notes += "Активны службы Accessibility (${a11yLabels.size}) — могут читать содержимое интерфейса"
        }
        if (adbEnabled && usbPlugged) {
            notes += "ADB включён и подключён USB-кабель — высокий риск дампа по проводу"
        } else if (adbEnabled) {
            notes += "Отладка по USB (ADB) включена в системе"
        }
        if (!vpnActive && privateDnsHost.isNullOrBlank()) {
            notes += "При выходе в сеть не активны ни VPN/Tor, ни явно заданный Private DNS"
        }

        val highRisk = externalDisplays.isNotEmpty() || (adbEnabled && usbPlugged)

        return OpsecSurfaceReport(
            externalDisplayNames = externalDisplays,
            accessibilityServiceLabels = a11yLabels,
            adbEnabled = adbEnabled,
            devOptionsEnabled = devOptions,
            usbPlugged = usbPlugged,
            vpnActive = vpnActive,
            privateDnsHost = privateDnsHost,
            isPrivateDnsStrictOrAuto = dnsAuto,
            sensorCount = sensorCount,
            hasHighRiskExposure = highRisk,
            notesRu = notes
        )
    }

    private fun detectExternalDisplays(context: Context): List<String> {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
            dm.displays
                .filter { it.displayId != Display.DEFAULT_DISPLAY }
                .map { d ->
                    val name = d.name?.takeIf { it.isNotBlank() } ?: "Display#${d.displayId}"
                    val isPresentation = (d.flags and Display.FLAG_PRESENTATION) != 0
                    if (isPresentation) "$name (Presentation)" else name
                }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun detectEnabledAccessibilityServices(context: Context): List<String> {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return emptyList()
            val pm = context.packageManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { info ->
                    val si = info.resolveInfo?.serviceInfo
                    val label = try {
                        si?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                    } catch (_: Throwable) {
                        null
                    }
                    label ?: si?.packageName ?: info.id
                }
                .distinct()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readGlobalInt(context: Context, key: String): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, key, 0)
        } catch (_: Throwable) {
            0
        }
    }

    private fun isUsbCablePlugged(context: Context): Boolean {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun inspectNetworkPrivacy(context: Context): Triple<Boolean, String?, Boolean> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Triple(false, null, false)
            val active = cm.activeNetwork ?: return Triple(false, null, false)
            val caps = cm.getNetworkCapabilities(active)
            val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val lp = cm.getLinkProperties(active)
            val dnsServer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp?.privateDnsServerName
            } else null
            val isPrivateDnsActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp?.isPrivateDnsActive == true
            } else false
            Triple(vpn, dnsServer, isPrivateDnsActive)
        } catch (_: Throwable) {
            Triple(false, null, false)
        }
    }

    private fun countHardwareSensors(context: Context): Int {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return 0
            sm.getSensorList(android.hardware.Sensor.TYPE_ALL)?.size ?: 0
        } catch (_: Throwable) {
            0
        }
    }
}
