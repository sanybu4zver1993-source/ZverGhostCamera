package com.example.assist

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * GhostAssistService:
 * Runs in an isolated process (:assist).
 * Kept completely separated from the core camera process (:core).
 * Never has direct access to the Camera hardware or local vault encryption keys.
 */
class GhostAssistService : Service() {
    private val binder = LocalBinder()

    class LocalBinder : Binder() {
        fun getServiceStatus(): String = "ONLINE (:assist process isolated)"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("GhostAssist", "GhostAssistService started in isolated :assist process (PID ${android.os.Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.i("GhostAssist", "GhostAssistService stopped.")
    }
}
