package com.example.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.UUID

/**
 * SecureClipboard:
 * - Marks copied payloads with ClipDescription.EXTRA_IS_SENSITIVE = true (hides Android 13+ popups).
 * - Tags each clipboard write with a unique Zver clip ID in PersistableBundle.
 * - Automatically wipes clipboard after [ttlMs] (default 45s) ONLY if the current clip ID still belongs to us.
 * - Can also wipe immediately on lock / Snatch / Panic / App background.
 */
object SecureClipboard {
    const val EXTRA_ZVER_CLIP_ID = "zver_clip_id"
    const val DEFAULT_TTL_MS = 45_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastOwnedClipId: String? = null
    private var pendingClearRunnable: Runnable? = null

    fun copySensitive(
        context: Context,
        label: String,
        text: String,
        ttlMs: Long = DEFAULT_TTL_MS
    ): String {
        val appCtx = context.applicationContext
        val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipId = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                putString(EXTRA_ZVER_CLIP_ID, clipId)
            }
        }
        cm?.setPrimaryClip(clip)
        lastOwnedClipId = clipId

        pendingClearRunnable?.let { mainHandler.removeCallbacks(it) }
        if (ttlMs > 0L) {
            val r = Runnable {
                clearIfClipMatches(appCtx, clipId)
            }
            pendingClearRunnable = r
            mainHandler.postDelayed(r, ttlMs)
        }
        return clipId
    }

    fun isCurrentlyOwnedByZver(context: Context): Boolean {
        val ownedId = lastOwnedClipId ?: return false
        val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val currentId = cm.primaryClipDescription?.extras?.getString(EXTRA_ZVER_CLIP_ID)
        return currentId == ownedId
    }

    fun clearIfOwned(context: Context): Boolean {
        val ownedId = lastOwnedClipId ?: return false
        return clearIfClipMatches(context.applicationContext, ownedId)
    }

    private fun clearIfClipMatches(context: Context, expectedClipId: String): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val currentId = cm.primaryClipDescription?.extras?.getString(EXTRA_ZVER_CLIP_ID)
        if (currentId != expectedClipId) return false

        pendingClearRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingClearRunnable = null
        lastOwnedClipId = null

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
