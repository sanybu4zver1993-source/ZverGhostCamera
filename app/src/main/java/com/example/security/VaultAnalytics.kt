package com.example.security

import android.content.Context
import com.example.assist.ByokManager
import com.example.camera.ExifStripper
import com.example.crypto.GhostCryptoVault

/**
 * VaultAnalytics:
 * 100% offline factual telemetry & storage/protection metrics (no PII, no filenames, no GPS).
 */
object VaultAnalytics {

    private const val ESTIMATED_RAW_PHOTO_BYTES = 3_500_000L // Typical 12.5MP Redmi 13C JPEG

    data class ProtectionCheck(
        val label: String,
        val enabled: Boolean
    )

    data class LocalAnalyticsSnapshot(
        val diskFilesCount: Int,
        val diskPhotosCount: Int,
        val diskVideosCount: Int,
        val diskTotalBytes: Long,
        val ramShotsCount: Int,
        val ramPhotosCount: Int,
        val ramVideosCount: Int,
        val ramTotalBytes: Long,
        val avgPhotoKb: Int,
        val estimatedSavedPercent: Int,
        val activePreset: ExifStripper.CompressionPreset,
        val ramOnlyActive: Boolean,
        val antiPrnuActive: Boolean,
        val protections: List<ProtectionCheck>,
        val readinessScore: Int,
        val readinessMax: Int
    )

    fun captureSnapshot(context: Context): LocalAnalyticsSnapshot {
        val diskFiles = GhostCryptoVault.listVaultFiles(context)
        val diskVideos = diskFiles.count { GhostCryptoVault.isVideoFile(it) }
        val diskPhotos = diskFiles.size - diskVideos
        val diskBytes = diskFiles.sumOf { it.length() }

        val ramShots = GhostCryptoVault.listRamShots()
        val ramVideos = ramShots.count { it.isVideo }
        val ramPhotos = ramShots.size - ramVideos
        val ramBytes = ramShots.sumOf { it.bytes.size.toLong() }

        val totalPhotos = diskPhotos + ramPhotos
        val photoBytesApprox = diskFiles.filter { !GhostCryptoVault.isVideoFile(it) }.sumOf { it.length() } +
            ramShots.filter { !it.isVideo }.sumOf { it.bytes.size.toLong() }

        val avgPhotoBytes = if (totalPhotos > 0) photoBytesApprox / totalPhotos else 0L
        val avgPhotoKb = (avgPhotoBytes / 1024L).toInt()
        val savedPct = if (totalPhotos > 0 && avgPhotoBytes < ESTIMATED_RAW_PHOTO_BYTES) {
            (((ESTIMATED_RAW_PHOTO_BYTES - avgPhotoBytes).toDouble() / ESTIMATED_RAW_PHOTO_BYTES) * 100.0)
                .toInt().coerceIn(0, 99)
        } else 0

        val preset = ZverPrefs.getCompressionPreset(context)
        val ramOnly = ZverPrefs.isRamOnlyMode(context)
        val antiPrnu = ZverPrefs.isAntiPrnuEnabled(context)

        val checks = listOf(
            ProtectionCheck("PIN-сейф задан", GhostCryptoVault.isConfigured(context)),
            ProtectionCheck("Duress-PIN принуждения", GhostCryptoVault.hasDuressConfigured(context)),
            ProtectionCheck("Anti-PRNU каскад", antiPrnu),
            ProtectionCheck("PIN Scramble", ZverPrefs.isPinScrambleEnabled(context)),
            ProtectionCheck("Snatch Lock (рывок)", ZverPrefs.isSnatchProtectionEnabled(context)),
            ProtectionCheck("Стоп-запись экрана (A15)", ZverPrefs.isScreenRecBlockEnabled(context)),
            ProtectionCheck("Dead-Man Switch", ZverPrefs.getDeadManDays(context) > 0)
        )

        return LocalAnalyticsSnapshot(
            diskFilesCount = diskFiles.size,
            diskPhotosCount = diskPhotos,
            diskVideosCount = diskVideos,
            diskTotalBytes = diskBytes,
            ramShotsCount = ramShots.size,
            ramPhotosCount = ramPhotos,
            ramVideosCount = ramVideos,
            ramTotalBytes = ramBytes,
            avgPhotoKb = avgPhotoKb,
            estimatedSavedPercent = savedPct,
            activePreset = preset,
            ramOnlyActive = ramOnly,
            antiPrnuActive = antiPrnu,
            protections = checks,
            readinessScore = checks.count { it.enabled },
            readinessMax = checks.size
        )
    }

    fun formatBytesHuman(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes Б"
            bytes < 1024L * 1024L -> "${bytes / 1024L} КБ"
            else -> String.format(java.util.Locale.US, "%.2f МБ", bytes / (1024.0 * 1024.0))
        }
    }

    fun buildTelemetrySummaryForAi(context: Context): String {
        val snap = captureSnapshot(context)
        val aiStats = ByokManager.getStats()
        val missing = snap.protections.filter { !it.enabled }.joinToString(", ") { it.label }.ifBlank { "нет" }
        return buildString {
            appendLine("=== ЛОКАЛЬНАЯ СТАТИСТИКА ZVER CAMERA (БЕЗ PII) ===")
            appendLine("Сейф Диск (.gcf): ${snap.diskFilesCount} файлов (фото: ${snap.diskPhotosCount}, видео: ${snap.diskVideosCount}), объём: ${formatBytesHuman(snap.diskTotalBytes)}")
            appendLine("ОЗУ-сейф (RAM-Only режим ${if (snap.ramOnlyActive) "ВКЛ" else "ВЫКЛ"}): ${snap.ramShotsCount} кадров, объём: ${formatBytesHuman(snap.ramTotalBytes)}")
            appendLine("Активный пресет кодека: ${snap.activePreset.labelRu} | Anti-PRNU: ${if (snap.antiPrnuActive) "ВКЛ" else "ВЫКЛ"}")
            if (snap.avgPhotoKb > 0) {
                appendLine("Средний размер фото: ${snap.avgPhotoKb} КБ (оценка экономии против сырого ~3.5МБ JPEG: ~${snap.estimatedSavedPercent}%)")
            }
            appendLine("Индекс защиты: ${snap.readinessScore}/${snap.readinessMax}. Не включено: $missing")
            appendLine("BYOK AI сессия: модель=${ByokManager.getModelName(context)}, запросов=${aiStats.queriesCount}, токенов=${aiStats.totalTokens}, последняя задержка=${aiStats.lastLatencyMs} мс")
        }
    }
}
