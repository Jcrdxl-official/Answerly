package com.answerly.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the relay after a reboot so notifications keep arriving without the
 * user having to open the app first.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs.get(context)
        val url = prefs.getString("url", "").orEmpty()
        val deviceId = prefs.getString("device_id", "").orEmpty()
        val deviceKey = prefs.getString("device_key", "").orEmpty()
        if (url.isEmpty() || deviceKey.isEmpty()) return
        ServiceRelay.connect(context, url, deviceId, deviceKey)
    }
}
