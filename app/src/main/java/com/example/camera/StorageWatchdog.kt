package com.example.camera

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * StorageWatchdog:
 * Protects video MP4 muxing on low-storage devices (Redmi 13C / 4 GB RAM).
 * Stops recording cleanly BEFORE ENOSPC can corrupt the moov atom.
 */
object StorageWatchdog {
    const val MIN_FREE_BYTES: Long = 250L * 1024L * 1024L // 250 MB hard stop
    const val WARN_FREE_BYTES: Long = 450L * 1024L * 1024L // 450 MB tactical warning

    enum class StorageLevel {
        OK,
        LOW_WARNING,
        CRITICAL_STOP
    }

    data class StorageStatus(
        val availableBytes: Long,
        val totalBytes: Long,
        val level: StorageLevel
    ) {
        val availableMb: Long get() = availableBytes / (1024L * 1024L)
        val totalMb: Long get() = totalBytes / (1024L * 1024L)
        val canRecord: Boolean get() = level != StorageLevel.CRITICAL_STOP
        val shouldStopRecording: Boolean get() = level == StorageLevel.CRITICAL_STOP
    }

    fun evaluateBytes(availableBytes: Long, totalBytes: Long = 0L): StorageStatus {
        val safeAvail = availableBytes.coerceAtLeast(0L)
        val safeTotal = totalBytes.coerceAtLeast(safeAvail)
        val level = when {
            safeAvail < MIN_FREE_BYTES -> StorageLevel.CRITICAL_STOP
            safeAvail < WARN_FREE_BYTES -> StorageLevel.LOW_WARNING
            else -> StorageLevel.OK
        }
        return StorageStatus(
            availableBytes = safeAvail,
            totalBytes = safeTotal,
            level = level
        )
    }

    fun evaluateDirectory(dir: File): StorageStatus {
        return try {
            val target = if (dir.exists()) dir else (dir.parentFile ?: dir)
            val stat = StatFs(target.absolutePath)
            evaluateBytes(stat.availableBytes, stat.totalBytes)
        } catch (_: Exception) {
            StorageStatus(
                availableBytes = MIN_FREE_BYTES,
                totalBytes = MIN_FREE_BYTES,
                level = StorageLevel.LOW_WARNING
            )
        }
    }

    fun evaluateAppStorage(context: Context): StorageStatus {
        return evaluateDirectory(context.filesDir)
    }
}
