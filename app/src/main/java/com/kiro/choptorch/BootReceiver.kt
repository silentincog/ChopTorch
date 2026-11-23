package com.kiro.choptorch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.enabled(context) || ChopService.running) return
        try {
            ChopService.start(context)
        } catch (e: Exception) {
            Log.w("ChopTorch", "could not start on ${intent.action}", e)
        }
    }
}
