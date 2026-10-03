package com.example.panic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.util.Log
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
import java.security.MessageDigest
import java.util.UUID

/**
 * PanicTriggerReceiver v4.0 (Anti-Timing Attack & KeyStore Hardware Purge):
 * - Constant-time token verification via MessageDigest.isEqual() to prevent timing side-channel leaks.
 * - Hardware KeyStore purge execution order: keys revoked first, followed by foreground disk zeroing.
 * - Dual-path Android 14/15 authorization (UID + APK cert signature or Shared Secret Nonce).
 */
class PanicTriggerReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CONNECT = "info.guardianproject.panic.action.CONNECT"
        const val ACTION_TRIGGER = "info.guardianproject.panic.action.TRIGGER"
        const val ACTION_DISCONNECT = "info.guardianproject.panic.action.DISCONNECT"
        const val EXTRA_SHARED_SECRET = "info.guardianproject.panic.extra.SHARED_SECRET"

        private const val PREFS_PANIC = "panickit_secure_prefs"
        private const val KEY_TRUSTED_FINGERPRINTS = "trusted_fingerprints"
        private const val KEY_SHARED_SECRET = "panic_shared_secret_nonce"

        fun getOrGenerateSharedSecret(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_PANIC, Context.MODE_PRIVATE)
            var secret = prefs.getString(KEY_SHARED_SECRET, null)
            if (secret == null) {
                secret = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString()
                prefs.edit().putString(KEY_SHARED_SECRET, secret).apply()
            }
            return secret
        }

        fun constantTimeEquals(a: String, b: String): Boolean {
            return MessageDigest.isEqual(
                a.toByteArray(Charsets.UTF_8),
                b.toByteArray(Charsets.UTF_8)
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = context.getSharedPreferences(PREFS_PANIC, Context.MODE_PRIVATE)

        val callerUid = getCallerUid()
        val incomingSecret = intent.getStringExtra(EXTRA_SHARED_SECRET)
            ?: intent.getStringExtra("shared_secret")
            ?: intent.getStringExtra("panic_token")

        val storedSecret = prefs.getString(KEY_SHARED_SECRET, null)

        when (action) {
            ACTION_CONNECT -> {
                Log.i("PanicKit", "Handling ACTION_CONNECT (UID: $callerUid)")
                if (callerUid != -1) {
                    val callerPackages = context.packageManager.getPackagesForUid(callerUid)
                    if (!callerPackages.isNullOrEmpty()) {
                        val certs = getCertificateFingerprints(context, callerPackages[0])
                        val trusted = prefs.getStringSet(KEY_TRUSTED_FINGERPRINTS, mutableSetOf())?.toMutableSet()
                            ?: mutableSetOf()
                        trusted.addAll(certs)
                        prefs.edit().putStringSet(KEY_TRUSTED_FINGERPRINTS, trusted).apply()
                        Log.i("PanicKit", "Registered cert fingerprint for ${callerPackages[0]}")
                    }
                }

                val secret = incomingSecret ?: getOrGenerateSharedSecret(context)
                prefs.edit().putString(KEY_SHARED_SECRET, secret).apply()
                Log.i("PanicKit", "PanicKit Shared Secret Nonce established.")
            }

            ACTION_DISCONNECT -> {
                Log.i("PanicKit", "Handling ACTION_DISCONNECT")
                prefs.edit().remove(KEY_SHARED_SECRET).apply()
            }

            ACTION_TRIGGER -> {
                Log.w("PanicKit", "Received ACTION_TRIGGER. Verifying caller authenticity...")
                var isAuthorized = false

                // Path A: Verified UID and Signing Certificate (Android 14+)
                if (callerUid != -1) {
                    val callerPackages = context.packageManager.getPackagesForUid(callerUid)
                    if (!callerPackages.isNullOrEmpty()) {
                        val callerCerts = getCertificateFingerprints(context, callerPackages[0])
                        val trusted = prefs.getStringSet(KEY_TRUSTED_FINGERPRINTS, emptySet()) ?: emptySet()
                        if (callerCerts.any { trusted.contains(it) } || (trusted.isEmpty() && callerPackages[0].contains("guardianproject"))) {
                            isAuthorized = true
                            Log.i("PanicKit", "Authorized via APK Certificate Fingerprint match.")
                        }
                    }
                }

                // Path B: Fallback for Android 14/15 where getSentFromUid() == -1 via Constant-Time comparison
                if (!isAuthorized && incomingSecret != null && storedSecret != null) {
                    if (constantTimeEquals(incomingSecret, storedSecret)) {
                        isAuthorized = true
                        Log.i("PanicKit", "Authorized via Cryptographic Shared Secret Nonce match (constant-time).")
                    }
                }

                // Path C: First-time setup / Trusted GuardianProject package fallback
                if (!isAuthorized && (storedSecret == null || callerUid != -1)) {
                    val pkg = intent.getPackage() ?: intent.getStringExtra("caller_package") ?: ""
                    if (pkg.contains("guardianproject") || pkg.contains("ripple")) {
                        isAuthorized = true
                        Log.i("PanicKit", "Authorized via GuardianProject Package Origin.")
                    }
                }

                if (isAuthorized) {
                    Log.w("PanicKit", "PANIC TRIGGER VALIDATED! Initiating instant hardware purge...")
                    try {
                        // Execution order: Revoke TEE hardware keys FIRST, then wipe disk bytes
                        val c2 = MonoVaultEngine.cryptoShredMonolith(context)
                        val c1 = GhostCryptoVault.cryptoShred(context)
                        Log.w("PanicKit", "PANIC PURGE COMPLETED (Monolith: $c2, Individual files: $c1).")
                    } catch (e: Exception) {
                        Log.e("PanicKit", "Error during panic execution", e)
                    }
                } else {
                    Log.e("PanicKit", "REJECTED UNVERIFIED PANIC TRIGGER! Neither Certificate nor Nonce matched.")
                }
            }
        }
    }

    private fun getCallerUid(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                getSentFromUid()
            } catch (_: Exception) {
                Binder.getCallingUid()
            }
        } else {
            Binder.getCallingUid()
        }
    }

    private fun getCertificateFingerprints(context: Context, packageName: String): List<String> {
        val pm = context.packageManager
        val fingerprints = mutableListOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo ?: return emptyList()
                val signers = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                for (sig in signers) {
                    val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                    fingerprints.add(digest.joinToString("") { "%02x".format(it) })
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val signatures = packageInfo.signatures ?: return emptyList()
                for (sig in signatures) {
                    val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                    fingerprints.add(digest.joinToString("") { "%02x".format(it) })
                }
            }
        } catch (e: Exception) {
            Log.e("PanicKit", "Cert fingerprint extraction failed for $packageName", e)
        }
        return fingerprints
    }
}
