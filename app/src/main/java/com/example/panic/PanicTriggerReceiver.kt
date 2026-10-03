package com.example.panic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.crypto.GhostCryptoVault

/**
 * PanicTriggerReceiver: Standard PanicKit integration.
 * Listens for info.guardianproject.panic.action.TRIGGER (sent by Ripple, Courier, etc.).
 * Executes instantaneous hardware TEE KeyStore destruction and vault purge.
 */
class PanicTriggerReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TRIGGER = "info.guardianproject.panic.action.TRIGGER"
        const val ACTION_DISCONNECT = "info.guardianproject.panic.action.DISCONNECT"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == ACTION_TRIGGER || action == ACTION_DISCONNECT) {
            Log.w("GhostCamera", "PANICKIT TRIGGER RECEIVED: Executing crypto-shredding...")
            try {
                val shredded = GhostCryptoVault.cryptoShred(context)
                Log.w("GhostCamera", "PANICKIT COMPLETE: $shredded files purged. Keystore key destroyed.")
            } catch (e: Exception) {
                Log.e("GhostCamera", "Panic destruction error", e)
            }
        }
    }
}
