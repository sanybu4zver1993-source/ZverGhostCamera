package com.example.crypto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Arrays

/**
 * VaultIndexEngine:
 * Per-profile encrypted metadata index stored inside profile dir as `vault_index.zvi`
 * (extension intentionally NOT `.gcf` so media listing & chain-manifest never treat it as media).
 * Stores tags, tactical notes, and optional Self-Destruct TTL timestamps.
 */
object VaultIndexEngine {
    const val INDEX_FILE_NAME = "vault_index.zvi"

    enum class TtlPreset(val labelRu: String, val durationMs: Long) {
        NONE("∞ Без таймера", 0L),
        MIN_10("10 мин", 10L * 60_000L),
        HOUR_1("1 час", 60L * 60_000L),
        HOUR_24("24 часа", 24L * 60L * 60_000L)
    }

    enum class VaultFilterType(val labelRu: String) {
        ALL("Все"),
        PHOTO("Фото"),
        VIDEO("Видео"),
        TTL_ONLY("С TTL")
    }

    data class VaultItemMeta(
        val fileName: String,
        val tags: List<String> = emptyList(),
        val note: String = "",
        val expiresAtMs: Long = 0L,
        val updatedAtMs: Long = 0L
    ) {
        fun hasTtl(): Boolean = expiresAtMs > 0L

        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs > 0L && nowMs >= expiresAtMs

        fun formatRemainingRu(nowMs: Long = System.currentTimeMillis()): String {
            if (expiresAtMs <= 0L) return ""
            val remMs = (expiresAtMs - nowMs).coerceAtLeast(0L)
            if (remMs == 0L) return "ИСТЁК"
            val totalSec = remMs / 1000L
            val hours = totalSec / 3600L
            val mins = (totalSec % 3600L) / 60L
            val secs = totalSec % 60L
            return when {
                hours > 0L -> "${hours}ч ${mins}м"
                mins > 0L -> "${mins}м ${secs}с"
                else -> "${secs}с"
            }
        }
    }

    private fun getIndexFile(context: Context, profile: GhostCryptoVault.VaultProfile): File {
        return File(profile.getDirectory(context), INDEX_FILE_NAME)
    }

    @Synchronized
    fun loadIndex(
        context: Context,
        profile: GhostCryptoVault.VaultProfile = GhostCryptoVault.activeProfile
    ): Map<String, VaultItemMeta> {
        val file = getIndexFile(context, profile)
        if (!file.exists() || file.length() < GcfChunkedCodec.HEADER_SIZE) {
            return emptyMap()
        }
        return try {
            val prevProfile = GhostCryptoVault.activeProfile
            val bytes = try {
                GhostCryptoVault.activeProfile = profile
                GcfChunkedCodec.decryptToBytes(file, GhostCryptoVault.getActiveVaultKey(context))
            } finally {
                GhostCryptoVault.activeProfile = prevProfile
            }
            try {
                parseIndexJson(String(bytes, Charsets.UTF_8))
            } finally {
                Arrays.fill(bytes, 0.toByte())
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    fun saveIndex(
        context: Context,
        profile: GhostCryptoVault.VaultProfile = GhostCryptoVault.activeProfile,
        entries: Map<String, VaultItemMeta>
    ) {
        val file = getIndexFile(context, profile)
        val jsonStr = serializeIndexJson(entries)
        val bytes = jsonStr.toByteArray(Charsets.UTF_8)
        val prevProfile = GhostCryptoVault.activeProfile
        try {
            GhostCryptoVault.activeProfile = profile
            GcfChunkedCodec.encryptBytesAtomically(
                plainBytes = bytes,
                targetFile = file,
                key = GhostCryptoVault.getActiveVaultKey(context)
            )
        } finally {
            GhostCryptoVault.activeProfile = prevProfile
            Arrays.fill(bytes, 0.toByte())
        }
    }

    fun getMeta(
        context: Context,
        profile: GhostCryptoVault.VaultProfile = GhostCryptoVault.activeProfile,
        fileName: String
    ): VaultItemMeta {
        return loadIndex(context, profile)[fileName] ?: VaultItemMeta(fileName = fileName)
    }

    @Synchronized
    fun setTagsAndNote(
        context: Context,
        profile: GhostCryptoVault.VaultProfile = GhostCryptoVault.activeProfile,
        fileName: String,
        tags: List<String>,
        note: String,
        nowMs: Long = System.currentTimeMillis()
    ): VaultItemMeta {
        val map = loadIndex(context, profile).toMutableMap()
        val prev = map[fileName] ?: VaultItemMeta(fileName = fileName)
        val cleanTags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12)
        val cleanNote = note.trim().take(1000)
        val updated = prev.copy(
            tags = cleanTags,
            note = cleanNote,
            updatedAtMs = nowMs
        )
        if (updated.tags.isEmpty() && updated.note.isEmpty() && updated.expiresAtMs <= 0L) {
            map.remove(fileName)
        } else {
            map[fileName] = updated
        }
        saveIndex(context, profile, map)
        return updated
    }

    @Synchronized
    fun setTtl(
        context: Context,
        profile: GhostCryptoVault.VaultProfile = GhostCryptoVault.activeProfile,
        fileName: String,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): VaultItemMeta {
        val map = loadIndex(context, profile).toMutableMap()
        val prev = map[fileName] ?: VaultItemMeta(fileName = fileName)
        val expiresAt = if (durationMs <= 0L) 0L else nowMs + durationMs
        val updated = prev.copy(
            expiresAtMs = expiresAt,
            updatedAtMs = nowMs
        )
        if (updated.tags.isEmpty() && updated.note.isEmpty() && updated.expiresAtMs <= 0L) {
            map.remove(fileName)
        } else {
            map[fileName] = updated
        }
        saveIndex(context, profile, map)
        return updated
    }

    @Synchronized
    fun removeMeta(
        context: Context,
        profile: GhostCryptoVault.VaultProfile = GhostCryptoVault.activeProfile,
        fileName: String
    ) {
        val map = loadIndex(context, profile).toMutableMap()
        if (map.remove(fileName) != null) {
            saveIndex(context, profile, map)
        }
    }

    @Synchronized
    fun purgeExpiredFiles(
        context: Context,
        profile: GhostCryptoVault.VaultProfile = GhostCryptoVault.activeProfile,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val map = loadIndex(context, profile).toMutableMap()
        if (map.isEmpty()) return 0

        val vaultDir = profile.getDirectory(context)
        var shreddedCount = 0
        var changed = false

        val expiredNames = map.values.filter { it.isExpired(nowMs) }.map { it.fileName }
        for (name in expiredNames) {
            val target = File(vaultDir, name)
            if (target.exists()) {
                if (GhostCryptoVault.shredFile(target)) {
                    shreddedCount++
                }
            }
            map.remove(name)
            changed = true
        }

        // Also prune orphaned entries whose files were already manually shredded
        val orphanNames = map.keys.filter { !File(vaultDir, it).exists() }
        for (orphan in orphanNames) {
            map.remove(orphan)
            changed = true
        }

        if (changed) {
            saveIndex(context, profile, map)
        }
        return shreddedCount
    }

    fun matchesFilter(
        file: File,
        meta: VaultItemMeta?,
        query: String,
        filterType: VaultFilterType
    ): Boolean {
        val isVideo = GhostCryptoVault.isVideoFile(file)
        when (filterType) {
            VaultFilterType.ALL -> Unit
            VaultFilterType.PHOTO -> if (isVideo) return false
            VaultFilterType.VIDEO -> if (!isVideo) return false
            VaultFilterType.TTL_ONLY -> if (meta == null || !meta.hasTtl()) return false
        }
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (file.name.lowercase().contains(q)) return true
        if (meta != null) {
            if (meta.note.lowercase().contains(q)) return true
            if (meta.tags.any { it.lowercase().contains(q) }) return true
        }
        return false
    }

    fun parseTagsInput(raw: String): List<String> {
        return raw.split(",", ";", "\n")
            .map { it.trim().removePrefix("#").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(12)
    }

    fun serializeIndexJson(entries: Map<String, VaultItemMeta>): String {
        val arr = JSONArray()
        for (item in entries.values.sortedBy { it.fileName }) {
            arr.put(JSONObject().apply {
                put("file", item.fileName)
                put("note", item.note)
                put("expires_at_ms", item.expiresAtMs)
                put("updated_at_ms", item.updatedAtMs)
                put("tags", JSONArray().apply {
                    item.tags.forEach { put(it) }
                })
            })
        }
        return JSONObject().apply {
            put("schema", "zver-vault-index-v1")
            put("items", arr)
        }.toString()
    }

    fun parseIndexJson(jsonStr: String): Map<String, VaultItemMeta> {
        val root = JSONObject(jsonStr)
        val arr = root.optJSONArray("items") ?: return emptyMap()
        val result = linkedMapOf<String, VaultItemMeta>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("file", "").trim()
            if (name.isEmpty()) continue
            val tagsArr = obj.optJSONArray("tags")
            val tags = buildList {
                if (tagsArr != null) {
                    for (t in 0 until tagsArr.length()) {
                        val tag = tagsArr.optString(t, "").trim()
                        if (tag.isNotEmpty()) add(tag)
                    }
                }
            }
            result[name] = VaultItemMeta(
                fileName = name,
                tags = tags,
                note = obj.optString("note", ""),
                expiresAtMs = obj.optLong("expires_at_ms", 0L),
                updatedAtMs = obj.optLong("updated_at_ms", 0L)
            )
        }
        return result
    }
}
