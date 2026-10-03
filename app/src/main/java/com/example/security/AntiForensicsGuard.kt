package com.example.security

import android.content.Context
import android.os.Build
import android.os.Debug
import com.scottyab.rootbeer.RootBeer
import java.io.File

/**
 * AntiForensicsGuard:
 * Active defense against memory inspection, debugger attachment, rootkits, and Magisk.
 */
object AntiForensicsGuard {

    data class SecurityStatus(
        val isDebuggerAttached: Boolean,
        val isRooted: Boolean,
        val isTestKeys: Boolean,
        val isCompromised: Boolean
    )

    fun assessDeviceSecurity(context: Context): SecurityStatus {
        val debugger = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        val rootBeer = RootBeer(context)
        val rooted = rootBeer.isRooted || checkSuBinaryPaths()
        val testKeys = Build.TAGS?.contains("test-keys") == true

        return SecurityStatus(
            isDebuggerAttached = debugger,
            isRooted = rooted,
            isTestKeys = testKeys,
            isCompromised = debugger || rooted
        )
    }

    private fun checkSuBinaryPaths(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        return paths.any { path ->
            try {
                File(path).exists()
            } catch (_: Exception) {
                false
            }
        }
    }
}
