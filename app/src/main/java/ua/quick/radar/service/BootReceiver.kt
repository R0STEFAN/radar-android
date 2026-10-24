package ua.quick.radar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                val prefs = context.getSharedPreferences(RadarForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
                val isEnabled = prefs.getBoolean("service_enabled", true)
                if (isEnabled) {
                    val serviceIntent = Intent(context, RadarForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            } catch (e: Exception) {
                // On Android 12+ (API 31+), starting a foreground service from background on MY_PACKAGE_REPLACED
                // may throw ForegroundServiceStartNotAllowedException. Ignore safely so the app never crashes on update.
            }
        }
    }
}
