package com.example.assist

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * GhostAssistService:
 * Auxiliary companion worker service running in the `:assist` Android process.
 * Does not hold CameraX handles or vault PINs directly; provides active worker telemetry
 * for the online BYOK assistant perimeter indicator.
 */
class GhostAssistService : Service() {
    private val binder = LocalBinder()

    class LocalBinder : Binder() {
        fun getServiceStatus(): String = "ACTIVE (:assist worker process)"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("GhostAssist", "GhostAssistService active in :assist worker process (PID ${android.os.Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.i("GhostAssist", "GhostAssistService stopped.")
    }
}
