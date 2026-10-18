package ua.quick.radar.bridge

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.google.gson.Gson
import ua.quick.radar.MainActivity
import ua.quick.radar.R
import ua.quick.radar.engine.AppSettings
import ua.quick.radar.engine.ThreatAnalyzer
import ua.quick.radar.service.RadarForegroundService

class WebAppInterface(private val activity: MainActivity) {

    private val gson = Gson()
    private val prefs = activity.getSharedPreferences(RadarForegroundService.PREFS_NAME, Context.MODE_PRIVATE)

    @JavascriptInterface
    fun getSettings(): String {
        val settings = AppSettings(
            pinnedLat = prefs.getFloat("pinned_lat", 50.4501f).toDouble(),
            pinnedLng = prefs.getFloat("pinned_lng", 30.5234f).toDouble(),
            pinnedName = prefs.getString("pinned_name", "Київ") ?: "Київ",
            criticalEnabled = prefs.getBoolean("critical_enabled", true),
            criticalRadiusKm = prefs.getFloat("critical_radius_km", 40.0f).toDouble(),
            criticalSoundUri = prefs.getString("critical_sound_uri", "") ?: "",
            criticalVolume = prefs.getInt("critical_volume", 80),
            sectorEnabled = prefs.getBoolean("sector_enabled", true),
            sectorRadiusKm = prefs.getFloat("sector_radius_km", 100.0f).toDouble(),
            sectorSoundUri = prefs.getString("sector_sound_uri", "") ?: "",
            sectorVolume = prefs.getInt("sector_volume", 60),
            enableFluger = prefs.getBoolean("enable_fluger", true),
            enableUaAlarm = prefs.getBoolean("enable_ua_alarm", true),
            hideScoutDrones = prefs.getBoolean("hide_scout_drones", false),
            serviceEnabled = prefs.getBoolean("service_enabled", true)
        )
        return gson.toJson(settings)
    }

    @JavascriptInterface
    fun saveSettings(json: String): Boolean {
        return try {
            val settings = gson.fromJson(json, AppSettings::class.java)
            prefs.edit()
                .putFloat("pinned_lat", settings.pinnedLat.toFloat())
                .putFloat("pinned_lng", settings.pinnedLng.toFloat())
                .putString("pinned_name", settings.pinnedName)
                .putBoolean("critical_enabled", settings.criticalEnabled)
                .putFloat("critical_radius_km", settings.criticalRadiusKm.toFloat())
                .putString("critical_sound_uri", settings.criticalSoundUri)
                .putInt("critical_volume", settings.criticalVolume)
                .putBoolean("sector_enabled", settings.sectorEnabled)
                .putFloat("sector_radius_km", settings.sectorRadiusKm.toFloat())
                .putString("sector_sound_uri", settings.sectorSoundUri)
                .putInt("sector_volume", settings.sectorVolume)
                .putBoolean("enable_fluger", settings.enableFluger)
                .putBoolean("enable_ua_alarm", settings.enableUaAlarm)
                .putBoolean("hide_scout_drones", settings.hideScoutDrones)
                .putBoolean("service_enabled", settings.serviceEnabled)
                .apply()

            // Trigger service to reload or start/stop
            if (settings.serviceEnabled) {
                activity.startRadarService()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun setPinnedLocation(lat: Double, lng: Double, name: String): Boolean {
        if (!ThreatAnalyzer.isInsideUkraine(lat, lng)) {
            activity.runOnUiThread {
                Toast.makeText(activity, "⚠️ Координати поза межами України (РЕБ-завада або помилка)!", Toast.LENGTH_LONG).show()
            }
            return false
        }

        prefs.edit()
            .putFloat("pinned_lat", lat.toFloat())
            .putFloat("pinned_lng", lng.toFloat())
            .putString("pinned_name", name)
            .apply()

        activity.runOnUiThread {
            Toast.makeText(activity, "📍 Точку спостереження встановлено: $name", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    @JavascriptInterface
    fun getCurrentTargets(): String {
        val targets = RadarForegroundService.latestTargetsFlow.value
        return gson.toJson(targets)
    }

    @JavascriptInterface
    fun getAssessment(): String {
        val assessment = RadarForegroundService.latestAssessmentFlow.value
        return if (assessment != null) gson.toJson(assessment) else "{}"
    }

    @JavascriptInterface
    fun pickSound(channelType: String) {
        activity.runOnUiThread {
            activity.launchSoundPicker(channelType)
        }
    }

    @JavascriptInterface
    fun resetSoundToDefault(channelType: String) {
        if (channelType == "critical") {
            prefs.edit().putString("critical_sound_uri", "").apply()
        } else {
            prefs.edit().putString("sector_sound_uri", "").apply()
        }
        activity.runOnUiThread {
            Toast.makeText(activity, "Звук скинуто на стандартний Флюгер", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun testSound(channelType: String, volumePercent: Int) {
        try {
            val custom = if (channelType == "critical") {
                prefs.getString("critical_sound_uri", "") ?: ""
            } else {
                prefs.getString("sector_sound_uri", "") ?: ""
            }

            val uri = if (custom.isNotEmpty()) {
                Uri.parse(custom)
            } else {
                val resId = if (channelType == "critical") R.raw.fluger_siren else R.raw.fluger_beep
                Uri.parse("android.resource://${activity.packageName}/$resId")
            }

            val volInt = if (volumePercent in 0..100) volumePercent else {
                if (channelType == "critical") prefs.getInt("critical_volume", 80) else prefs.getInt("sector_volume", 60)
            }
            val vol = (volInt.coerceIn(0, 100) / 100.0f)

            val mp = MediaPlayer.create(activity, uri)
            mp.setVolume(vol, vol)
            mp.setOnCompletionListener { it.release() }
            mp.start()
        } catch (e: Exception) {
            activity.runOnUiThread {
                Toast.makeText(activity, "Помилка відтворення звуку: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun requestGpsLocation() {
        activity.runOnUiThread {
            activity.determineGpsLocation()
        }
    }

    @JavascriptInterface
    fun requestBatteryOptimizationExemption() {
        activity.runOnUiThread {
            activity.requestIgnoreBatteryOptimization()
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
