package ua.quick.radar.bridge

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import ua.quick.radar.MainActivity
import ua.quick.radar.R
import ua.quick.radar.engine.AppSettings
import ua.quick.radar.engine.DeepStateClient
import ua.quick.radar.engine.ThreatAnalyzer
import ua.quick.radar.service.RadarForegroundService
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class WebAppInterface(private val activity: MainActivity) {

    private val gson = Gson()
    private val prefs = activity.getSharedPreferences(RadarForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
    private val deepStateClient = DeepStateClient()
    private val updateHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
            sectorRadiusKm = run {
                val r = prefs.getFloat("sector_radius_km", 45.0f).toDouble()
                if (r != 45.0 && r != 80.0) 45.0 else r
            },
            sectorSoundUri = prefs.getString("sector_sound_uri", "") ?: "",
            sectorVolume = prefs.getInt("sector_volume", 60),
            enableFluger = prefs.getBoolean("enable_fluger", true),
            enableUaAlarm = prefs.getBoolean("enable_ua_alarm", true),
            uaAllUkraine = prefs.getBoolean("ua_all_ukraine", false),
            hideScoutDrones = prefs.getBoolean("hide_scout_drones", false),
            serviceEnabled = prefs.getBoolean("service_enabled", true)
        )
        return gson.toJson(settings)
    }

    @JavascriptInterface
    fun saveSettings(json: String): Boolean {
        return try {
            val settings = gson.fromJson(json, AppSettings::class.java)
            val sanitizedSectorRadius = if (settings.sectorRadiusKm == 80.0) 80.0 else 45.0
            prefs.edit()
                .putFloat("pinned_lat", settings.pinnedLat.toFloat())
                .putFloat("pinned_lng", settings.pinnedLng.toFloat())
                .putString("pinned_name", settings.pinnedName)
                .putBoolean("critical_enabled", settings.criticalEnabled)
                .putFloat("critical_radius_km", settings.criticalRadiusKm.toFloat())
                .putString("critical_sound_uri", settings.criticalSoundUri)
                .putInt("critical_volume", settings.criticalVolume)
                .putBoolean("sector_enabled", settings.sectorEnabled)
                .putFloat("sector_radius_km", sanitizedSectorRadius.toFloat())
                .putString("sector_sound_uri", settings.sectorSoundUri)
                .putInt("sector_volume", settings.sectorVolume)
                .putBoolean("enable_fluger", settings.enableFluger)
                .putBoolean("enable_ua_alarm", settings.enableUaAlarm)
                .putBoolean("ua_all_ukraine", settings.uaAllUkraine)
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

    @JavascriptInterface
    fun triggerScanNow() {
        RadarForegroundService.triggerImmediateScan()
    }

    @JavascriptInterface
    fun resetTracks() {
        RadarForegroundService.resetAllTracks()
        activity.runOnUiThread {
            Toast.makeText(activity, "Історію треків очищено. Сканування...", Toast.LENGTH_SHORT).show()
        }
    }

    @Volatile
    private var cachedDeepStateJson: String? = null

    @JavascriptInterface
    fun getDeepStateData(): String {
        return cachedDeepStateJson ?: deepStateClient.fetchDeepStateData(activity) ?: ""
    }

    @JavascriptInterface
    fun loadDeepStateGeoJson() {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val json = deepStateClient.fetchDeepStateData(activity)
            cachedDeepStateJson = json
            activity.runOnUiThread {
                if (json != null) {
                    activity.webView.evaluateJavascript("window.onDeepStateLoaded && window.onDeepStateLoaded()", null)
                } else {
                    activity.webView.evaluateJavascript("window.onDeepStateFailed && window.onDeepStateFailed()", null)
                }
            }
        }
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            pInfo.versionName ?: "1.4.4"
        } catch (e: Exception) {
            "1.4.4"
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val cParts = current.removePrefix("v").removePrefix("V").trim().split(".").map { it.toIntOrNull() ?: 0 }
        val lParts = latest.removePrefix("v").removePrefix("V").trim().split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(cParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val c = cParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    @JavascriptInterface
    fun checkUpdateFromGitHub() {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val currentVer = getAppVersion()
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/R0STEFAN/radar-android/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "FlugerRadar-Android/$currentVer")
                    .get()
                    .build()

                updateHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errRes = mapOf("status" to "error", "message" to "Помилка GitHub API (HTTP ${response.code})")
                        val jsonOut = gson.toJson(errRes)
                        activity.runOnUiThread {
                            activity.webView.evaluateJavascript("window.onUpdateCheckResult && window.onUpdateCheckResult(${gson.toJson(jsonOut)})", null)
                        }
                        return@use
                    }

                    val bodyStr = response.body?.string() ?: "{}"
                    val root = JsonParser.parseString(bodyStr).asJsonObject
                    val tagName = root.get("tag_name")?.asString ?: ""
                    val latestVer = tagName.removePrefix("v").removePrefix("V").trim()
                    val releaseNotes = root.get("body")?.asString ?: ""

                    var apkUrl = ""
                    var apkSizeMb = 0.0
                    val assets = root.getAsJsonArray("assets")
                    if (assets != null) {
                        for (el in assets) {
                            val asset = el.asJsonObject
                            val name = asset.get("name")?.asString ?: ""
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.get("browser_download_url")?.asString ?: ""
                                val sizeBytes = asset.get("size")?.asLong ?: 0L
                                apkSizeMb = ThreatAnalyzer.safeRound(sizeBytes / (1024.0 * 1024.0), 1)
                                break
                            }
                        }
                    }

                    val hasUpdate = isNewerVersion(currentVer, latestVer) && apkUrl.isNotEmpty()
                    val resMap = mapOf(
                        "status" to "ok",
                        "currentVersion" to currentVer,
                        "latestVersion" to latestVer,
                        "hasUpdate" to hasUpdate,
                        "downloadUrl" to apkUrl,
                        "sizeMb" to apkSizeMb,
                        "notes" to releaseNotes
                    )
                    val jsonOut = gson.toJson(resMap)
                    activity.runOnUiThread {
                        activity.webView.evaluateJavascript("window.onUpdateCheckResult && window.onUpdateCheckResult(${gson.toJson(jsonOut)})", null)
                    }
                }
            } catch (e: Exception) {
                val errRes = mapOf("status" to "error", "message" to "Помилка мережі: ${e.localizedMessage ?: "невідомо"}")
                val jsonOut = gson.toJson(errRes)
                activity.runOnUiThread {
                    activity.webView.evaluateJavascript("window.onUpdateCheckResult && window.onUpdateCheckResult(${gson.toJson(jsonOut)})", null)
                }
            }
        }
    }

    @JavascriptInterface
    fun downloadAndInstallApk(downloadUrl: String) {
        if (downloadUrl.isBlank()) return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "FlugerRadar-Android/${getAppVersion()}")
                    .get()
                    .build()

                updateHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        activity.runOnUiThread {
                            activity.webView.evaluateJavascript("window.onUpdateDownloadError && window.onUpdateDownloadError('Помилка завантаження HTTP ${response.code}')", null)
                        }
                        return@use
                    }

                    val body = response.body ?: run {
                        activity.runOnUiThread {
                            activity.webView.evaluateJavascript("window.onUpdateDownloadError && window.onUpdateDownloadError('Порожній файл відповіді')", null)
                        }
                        return@use
                    }

                    val totalBytes = body.contentLength()
                    val updatesDir = File(activity.cacheDir, "updates")
                    if (!updatesDir.exists()) updatesDir.mkdirs()
                    val apkFile = File(updatesDir, "fluger-radar-update.apk")
                    if (apkFile.exists()) apkFile.delete()

                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var bytesRead: Int
                            var downloadedBytes = 0L
                            var lastReportedPct = -1

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0) {
                                    val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                    if (pct != lastReportedPct && (pct % 2 == 0 || pct == 100)) {
                                        lastReportedPct = pct
                                        activity.runOnUiThread {
                                            activity.webView.evaluateJavascript("window.onUpdateDownloadProgress && window.onUpdateDownloadProgress($pct)", null)
                                        }
                                    }
                                }
                            }
                            output.flush()
                        }
                    }

                    activity.runOnUiThread {
                        activity.webView.evaluateJavascript("window.onUpdateDownloadFinished && window.onUpdateDownloadFinished()", null)
                        activity.promptInstallApk(apkFile)
                    }
                }
            } catch (e: Exception) {
                val msg = (e.localizedMessage ?: "Помилка з'єднання").replace("'", "")
                activity.runOnUiThread {
                    activity.webView.evaluateJavascript("window.onUpdateDownloadError && window.onUpdateDownloadError('$msg')", null)
                }
            }
        }
    }
}
