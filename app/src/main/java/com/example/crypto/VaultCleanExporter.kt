package com.example.crypto

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.camera.ExifStripper
import com.example.camera.Mp4Sanitizer
import java.io.IOException
import java.util.UUID

/**
 * VaultCleanExporter:
 * Play-Policy-compliant (zero storage permissions on Android 10-15 / Scoped Storage) helper
 * that exports an explicit user-chosen 0-EXIF photo or sanitized MP4 into MediaStore
 * (Pictures/ZverClean or Movies/ZverClean) so Android System Photo Picker / Telegram / AI chats
 * can pick it directly, and allows purging all app-owned ZverClean exports in one tap.
 */
object VaultCleanExporter {

    const val EXPORT_SUBDIR = "ZverClean"

    data class GalleryExportResult(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val bytesWritten: Int
    )

    fun exportBytesToSystemGallery(
        context: Context,
        plainBytes: ByteArray,
        isVideo: Boolean
    ): GalleryExportResult {
        if (plainBytes.isEmpty()) {
            throw IllegalArgumentException("Пустой файл не может быть экспортирован")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("Прямой экспорт без разрешений поддерживается на Android 10+")
        }

        val resolver = context.contentResolver
        val payloadBytes: ByteArray
        val ext: String
        val mime: String
        val relPath: String
        val collectionUri: Uri

        if (isVideo) {
            payloadBytes = Mp4Sanitizer.sanitizeMp4Bytes(plainBytes)
            ext = "mp4"
            mime = "video/mp4"
            relPath = "${Environment.DIRECTORY_MOVIES}/$EXPORT_SUBDIR"
            collectionUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            val detected = GhostCryptoVault.detectMediaExtension(plainBytes, isVideo = false)
            payloadBytes = if (detected == "jpg" && ExifStripper.auditBytes(plainBytes).metadataBytes > 0) {
                ExifStripper.processBitmapClean(
                    sourceBitmap = android.graphics.BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.size)
                        ?: throw IOException("Не удалось декодировать изображение"),
                    recycleSource = true,
                    preset = ExifStripper.CompressionPreset.HIGH_QUALITY,
                    antiPrnu = false
                ).cleanBytes
            } else {
                plainBytes
            }
            ext = GhostCryptoVault.detectMediaExtension(payloadBytes, isVideo = false)
            mime = when (ext) {
                "webp" -> "image/webp"
                "png" -> "image/png"
                else -> "image/jpeg"
            }
            relPath = "${Environment.DIRECTORY_PICTURES}/$EXPORT_SUBDIR"
            collectionUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val displayName = "zver_clean_${UUID.randomUUID().toString().take(8)}.$ext"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val insertedUri = resolver.insert(collectionUri, values)
            ?: throw IOException("MediaStore отклонил создание записи")

        try {
            resolver.openOutputStream(insertedUri, "w")?.use { out ->
                out.write(payloadBytes)
                out.flush()
            } ?: throw IOException("Не удалось открыть поток MediaStore")

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(insertedUri, publishValues, null, null)
            return GalleryExportResult(
                uri = insertedUri,
                displayName = displayName,
                mimeType = mime,
                bytesWritten = payloadBytes.size
            )
        } catch (e: Exception) {
            try { resolver.delete(insertedUri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Deletes only app-owned items previously exported into ZverClean (zero storage permissions needed).
     */
    fun purgeExportedGalleryCopies(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val resolver = context.contentResolver
        var deleted = 0
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        )
        for (uri in collections) {
            try {
                deleted += resolver.delete(
                    uri,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("zver_clean_%", "%$EXPORT_SUBDIR%")
                )
            } catch (_: Exception) {}
        }
        return deleted
    }
}
