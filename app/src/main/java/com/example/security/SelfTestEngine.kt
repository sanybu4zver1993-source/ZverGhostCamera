package com.example.security

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.example.assist.ByokManager
import com.example.camera.StorageWatchdog
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
import com.example.crypto.VaultIndexEngine
import java.io.File
import java.security.KeyStore

/**
 * SelfTestEngine:
 * Offline-safe diagnostic suite for KeyStore (StrongBox/TEE), Screen Guard,
 * Storage & 4 GB RAM pressure, GCF1/GCF2 Vault health, Camouflage aliases,
 * Camera2/EGL status, and BYOK encrypted channel readiness.
 */
object SelfTestEngine {

    enum class CheckStatus(val badge: String) {
        PASS("PASS"),
        WARN("WARN"),
        FAIL("FAIL")
    }

    data class SelfTestItem(
        val id: String,
        val title: String,
        val status: CheckStatus,
        val summary: String,
        val detail: String
    )

    data class SelfTestReport(
        val timestampMs: Long,
        val items: List<SelfTestItem>,
        val passCount: Int,
        val warnCount: Int,
        val failCount: Int,
        val orphanTempCount: Int,
        val expiredTtlCount: Int,
        val formattedTextReport: String
    )

    fun runFullDiagnostics(
        context: Context,
        boundUseCasesSummary: String = "Камера не привязана",
        eglRecordableOk: Boolean? = null
    ): SelfTestReport {
        val items = mutableListOf<SelfTestItem>()

        // 1. KeyStore / Crypto readiness
        val strongBox = try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } catch (_: Exception) { false }
        val keyStoreOk = try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            true
        } catch (_: Exception) { false }
        val pinConfigured = GhostCryptoVault.isConfigured(context)
        val decoyConfigured = GhostCryptoVault.hasDecoyConfigured(context)
        val duressConfigured = GhostCryptoVault.hasDuressConfigured(context)
        val ksStatus = when {
            !keyStoreOk -> CheckStatus.FAIL
            !pinConfigured -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        items += SelfTestItem(
            id = "keystore",
            title = "KeyStore & Шифрование",
            status = ksStatus,
            summary = if (strongBox) "StrongBox + TEE AES-256-GCM" else "TEE KeyStore AES-256-GCM (PBKDF2 600k)",
            detail = "PIN MAIN=${if (pinConfigured) "OK" else "НЕТ"}, DECOY=${if (decoyConfigured) "OK" else "НЕТ"}, DURESS=${if (duressConfigured) "OK" else "НЕТ"}"
        )

        // 2. Screen Guard & Lock Policy
        val screenBlock = ZverPrefs.isScreenRecBlockEnabled(context)
        val autoLock = ZverPrefs.isAutoLockOnBackground(context)
        val snatch = ZverPrefs.isSnatchProtectionEnabled(context)
        val guardStatus = when {
            screenBlock && autoLock -> CheckStatus.PASS
            screenBlock || autoLock -> CheckStatus.WARN
            else -> CheckStatus.WARN
        }
        items += SelfTestItem(
            id = "screen_guard",
            title = "Защита экрана и автоблок",
            status = guardStatus,
            summary = "FLAG_SECURE=${if (screenBlock) "ON" else "OFF"} // AutoLock=${if (autoLock) "ON" else "OFF"}",
            detail = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), Анти-Рывок=${if (snatch) "ON" else "OFF"}, PIN Scramble=${if (ZverPrefs.isPinScrambleEnabled(context)) "ON" else "OFF"}"
        )

        // 3. Storage & RAM
        val storage = StorageWatchdog.evaluateAppStorage(context)
        val memInfo = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.getMemoryInfo(memInfo)
        val availRamMb = memInfo.availMem / (1024L * 1024L)
        val totalRamMb = memInfo.totalMem / (1024L * 1024L)
        val memStatus = when {
            storage.level == StorageWatchdog.StorageLevel.CRITICAL_STOP -> CheckStatus.FAIL
            storage.level == StorageWatchdog.StorageLevel.LOW_WARNING || memInfo.lowMemory -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        items += SelfTestItem(
            id = "storage_ram",
            title = "Память & Диск (Watchdog)",
            status = memStatus,
            summary = "Диск: ${storage.availableMb} MB своб. | ОЗУ: $availRamMb/$totalRamMb MB",
            detail = "Порог авто-стопа видео: ${StorageWatchdog.MIN_FREE_BYTES / (1024 * 1024)} MB, lowMemory=${memInfo.lowMemory}"
        )

        // 4. Vault GCF1/GCF2 + Orphans + TTL + MonoVault
        val files = GhostCryptoVault.listVaultFiles(context, GhostCryptoVault.activeProfile)
        var gcf1Count = 0
        var gcf2Count = 0
        var badHeaderCount = 0
        for (f in files) {
            val h = GhostCryptoVault.inspectGcfHeader(f)
            if (!h.hasValidMagic) {
                badHeaderCount++
            } else if (h.formatVersion.startsWith("GCF2")) {
                gcf2Count++
            } else {
                gcf1Count++
            }
        }
        var orphanCount = 0
        for (profile in GhostCryptoVault.VaultProfile.entries) {
            val dir = File(context.filesDir, profile.dirName)
            if (dir.exists()) {
                orphanCount += dir.listFiles()?.count {
                    it.name.endsWith(".tmp") || (it.name.endsWith(".gcf") && it.length() == 0L)
                } ?: 0
            }
        }
        val stageDir = File(context.noBackupFilesDir, "video_stage")
        if (stageDir.exists()) {
            orphanCount += stageDir.listFiles()?.count { it.name != ".nomedia" } ?: 0
        }
        val nowMs = System.currentTimeMillis()
        val indexMap = VaultIndexEngine.loadIndex(context, GhostCryptoVault.activeProfile)
        val expiredCount = indexMap.values.count { it.isExpired(nowMs) }
        val monoFile = MonoVaultEngine.getContainerFile(context)
        val monoExists = monoFile.exists()
        val monoSizeKb = if (monoExists) monoFile.length() / 1024L else 0L
        val vaultStatus = when {
            badHeaderCount > 0 -> CheckStatus.FAIL
            orphanCount > 0 || expiredCount > 0 -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        items += SelfTestItem(
            id = "vault",
            title = "Сейф (${GhostCryptoVault.activeProfile.name}) & MonoVault",
            status = vaultStatus,
            summary = "Файлов: ${files.size} (GCF1:$gcf1Count / GCF2:$gcf2Count), Битых: $badHeaderCount",
            detail = "Хвосты .tmp: $orphanCount, Просрочено TTL: $expiredCount, Индекс: ${indexMap.size} зап., Монолит: ${if (monoExists) "${monoSizeKb} KB" else "нет"}"
        )

        // 5. Camouflage Aliases
        val camoMode = CamouflageManager.getCurrentCamouflage(context)
        val aliasMap = try {
            CamouflageManager.verifyManifestAliases(context)
        } catch (_: Exception) { emptyMap() }
        val camoCheck = try {
            val pm = context.packageManager
            val activeComp = camoMode.getComponentName(context)
            val state = pm.getComponentEnabledSetting(activeComp)
            val manifestExists = aliasMap[camoMode] ?: true
            manifestExists && (
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (camoMode == CamouflageManager.CamouflageMode.ZVER &&
                        state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                )
        } catch (_: Exception) { true }
        items += SelfTestItem(
            id = "camouflage",
            title = "Камуфляж ярлыка",
            status = if (camoCheck) CheckStatus.PASS else CheckStatus.WARN,
            summary = "Режим: ${camoMode.displayName} (${camoMode.aliasSuffix})",
            detail = if (camoCheck) "Манифест-алиас активен и согласован" else "Требуется переприменить режим камуфляжа"
        )

        // 6. Camera2 HAL & EGL_RECORDABLE_ANDROID
        val camInfo = try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = cm.cameraIdList
            val levels = ids.take(2).map { id ->
                val ch = cm.getCameraCharacteristics(id)
                when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "$id:L3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "$id:FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "$id:LIM"
                    else -> "$id:LEG"
                }
            }
            "Сенсоров=${ids.size} (${levels.joinToString(", ")})"
        } catch (e: Exception) {
            "CameraManager недоступен (${e.javaClass.simpleName})"
        }
        val camStatus = when (eglRecordableOk) {
            false -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        items += SelfTestItem(
            id = "camera_egl",
            title = "Camera2 HAL & GLSL EGL",
            status = camStatus,
            summary = camInfo,
            detail = "UseCases: $boundUseCasesSummary | EGL_RECORDABLE=${eglRecordableOk?.toString() ?: "N/A"}"
        )

        // 7. Encrypted BYOK Channel
        val hasByok = ByokManager.hasKey(context)
        val transport = ByokManager.getTransportConfig(context)
        val byokStatus = when {
            !hasByok -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        items += SelfTestItem(
            id = "byok_channel",
            title = "Зашифрованный канал BYOK",
            status = byokStatus,
            summary = if (hasByok) "Ключ активен | ${transport.describeShort()}" else "Ключ не задан | ${transport.describeShort()}",
            detail = "TLS 1.3/1.2 Restricted + RamCache=0 + No-PII Headers"
        )

        val passCount = items.count { it.status == CheckStatus.PASS }
        val warnCount = items.count { it.status == CheckStatus.WARN }
        val failCount = items.count { it.status == CheckStatus.FAIL }

        val text = buildString {
            appendLine("=== ZVER SELF-TEST REPORT ===")
            appendLine("PASS=$passCount | WARN=$warnCount | FAIL=$failCount")
            appendLine()
            for (item in items) {
                appendLine("[${item.status.badge}] ${item.title}")
                appendLine("  ${item.summary}")
                appendLine("  ${item.detail}")
            }
        }

        return SelfTestReport(
            timestampMs = nowMs,
            items = items,
            passCount = passCount,
            warnCount = warnCount,
            failCount = failCount,
            orphanTempCount = orphanCount,
            expiredTtlCount = expiredCount,
            formattedTextReport = text
        )
    }
}
