package ua.quick.radar.engine

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.*

class UkraineAlarmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Internal cache of track histories to calculate speed and track trajectories over time
    private data class TrackPoint(val timeSec: Double, val lat: Double, val lng: Double)
    private data class TrackHistory(
        val history: MutableList<TrackPoint> = mutableListOf(),
        var lastSeenSec: Double = 0.0
    )
    private val trackHistories = ConcurrentHashMap<String, TrackHistory>()

    companion object {
        private const val UA_ALARM_VIEWBOX_WIDTH = 260.0
        private const val UA_ALARM_VIEWBOX_HEIGHT = 175.0
        private const val UA_ALARM_WEST = 22.137059
        private const val UA_ALARM_EAST = 40.2275801
        private const val UA_ALARM_SOUTH = 44.184598
        private const val UA_ALARM_NORTH = 52.3791473

        fun normalizedToLngLat(x: Double, y: Double): Pair<Double, Double> {
            val mapX = x * UA_ALARM_VIEWBOX_WIDTH
            val mapY = y * UA_ALARM_VIEWBOX_HEIGHT
            val projW = (UA_ALARM_EAST - UA_ALARM_WEST) * Math.PI / 180.0
            val mercN = ln(tan(Math.PI / 4.0 + (UA_ALARM_NORTH * Math.PI / 180.0) / 2.0))
            val mercS = ln(tan(Math.PI / 4.0 + (UA_ALARM_SOUTH * Math.PI / 180.0) / 2.0))
            val projH = mercN - mercS
            val scale = minOf(UA_ALARM_VIEWBOX_WIDTH / projW, UA_ALARM_VIEWBOX_HEIGHT / projH)
            val offsetX = (UA_ALARM_VIEWBOX_WIDTH - projW * scale) / 2.0
            val offsetY = (UA_ALARM_VIEWBOX_HEIGHT - projH * scale) / 2.0

            val lng = UA_ALARM_WEST + ((mapX - offsetX) / scale) * 180.0 / Math.PI
            val projLat = mercN - (mapY - offsetY) / scale
            val lat = (2.0 * atan(exp(projLat)) - Math.PI / 2.0) * 180.0 / Math.PI

            return Pair(ThreatAnalyzer.safeRound(lat, 5), ThreatAnalyzer.safeRound(lng, 5))
        }
    }

    fun fetchUaRadarTargets(): List<RadarTarget> {
        val url = "https://map.ukrainealarm.com/api/v2/data/targetsForMapsState/public?separateTracks=false"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
            .header("Accept", "application/json")
            .get()
            .build()

        val responseBody = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body?.string() ?: return emptyList()
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val parsed = try {
            gson.fromJson(responseBody, UaTargetsResponse::class.java)
        } catch (e: Exception) {
            return emptyList()
        }

        val items = parsed.movement?.from ?: return emptyList()
        val nowSec = System.currentTimeMillis() / 1000.0

        // Prune stale tracks older than 6 minutes (360 seconds)
        trackHistories.entries.removeIf { nowSec - it.value.lastSeenSec > 360.0 }

        val result = mutableListOf<RadarTarget>()

        for (item in items) {
            val tid = item.trackId ?: continue
            val (lat, lng) = normalizedToLngLat(item.x, item.y)

            var iconName = item.iconName?.trim() ?: ""
            var formular = item.formular?.trim() ?: ""
            val dtype = item.detectedType?.trim() ?: ""
            val count = item.currentCount ?: 1

            if (iconName.isEmpty()) {
                val lowDtype = dtype.lowercase()
                val lowForm = formular.lowercase()
                iconName = when {
                    "kab" in lowDtype || "каб" in lowForm -> "ic_airbomb"
                    "jet" in lowDtype || "реакт" in lowForm -> "ic_shahed_jet"
                    "shahed" in lowDtype || "шахед" in lowForm || "дрон" in lowForm -> "ic_shahed"
                    "missile" in lowDtype || "ракета" in lowForm -> "ic_missile"
                    "ballistic" in lowDtype || "баліст" in lowForm -> "ic_ballistic"
                    "drone" in lowDtype || "розвід" in lowForm -> "ic_drone"
                    else -> "ic_air_threat_combined_white"
                }
            }

            if (formular.isEmpty()) {
                formular = when (iconName) {
                    "ic_shahed" -> "Ударний БПЛА (Шахед)"
                    "ic_shahed_jet" -> "Реактивний БПЛА"
                    "ic_airbomb" -> "КАБ (УМПК)"
                    "ic_missile" -> "Крилата ракета"
                    "ic_ballistic" -> "Балістична ракета"
                    "ic_drone" -> "Розвідувальний БПЛА"
                    else -> if (dtype.isNotEmpty()) dtype else "Повітряна ціль"
                }
            }

            val echelon = when (iconName) {
                "ic_airbomb" -> "Планування з висоти (6 000 – 10 000 м → піке)"
                "ic_shahed" -> "Бриючий ешелон (80 – 350 м)"
                "ic_shahed_jet" -> "Середній ешелон (1 500 – 3 500 м)"
                "ic_missile" -> "Наднизький ешелон (40 – 120 м)"
                "ic_ballistic" -> "Стратосфера (15+ км) → круте піке"
                "ic_drone" -> "Тактичний оглядовий (1 000 – 2 500 м)"
                else -> "Висотний ешелон"
            }

            val track = trackHistories.getOrPut(tid) { TrackHistory() }
            track.lastSeenSec = nowSec

            val lastPoint = track.history.lastOrNull()
            if (lastPoint == null || (nowSec - lastPoint.timeSec >= 2.0) ||
                ThreatAnalyzer.haversineKm(lastPoint.lat, lastPoint.lng, lat, lng) > 0.05
            ) {
                track.history.add(TrackPoint(nowSec, lat, lng))
                if (track.history.size > 200) track.history.removeAt(0)
            }

            val history = track.history
            var avgSpeedKmh: Double? = null
            var instantSpeedKmh: Double? = null
            var courseDeg = item.heading

            if (history.size >= 2) {
                val firstP = history.first()
                val lastP = history.last()
                val totalDt = lastP.timeSec - firstP.timeSec
                val totalDist = ThreatAnalyzer.haversineKm(firstP.lat, firstP.lng, lastP.lat, lastP.lng)

                if (totalDt > 2.0 && totalDist >= 0.05) {
                    avgSpeedKmh = ThreatAnalyzer.safeRound((totalDist / totalDt) * 3600.0, 0)
                }

                val prevP = history[history.size - 2]
                val dtInst = lastP.timeSec - prevP.timeSec
                val distInst = ThreatAnalyzer.haversineKm(prevP.lat, prevP.lng, lastP.lat, lastP.lng)
                if (dtInst > 1.0 && distInst >= 0.03) {
                    instantSpeedKmh = ThreatAnalyzer.safeRound((distInst / dtInst) * 3600.0, 0)
                }

                if (courseDeg == null && distInst >= 0.05) {
                    courseDeg = ThreatAnalyzer.calculateBearing(prevP.lat, prevP.lng, lastP.lat, lastP.lng)
                }
            }

            val pathCoords = history.map { listOf(it.lat, it.lng) }
            val courseRounded = courseDeg?.let { ThreatAnalyzer.safeRound(it, 0) }
            val courseCompass = courseRounded?.let { ThreatAnalyzer.bearingToCompass(it) }

            val subDesc = buildString {
                if (dtype.isNotEmpty()) append(dtype) else append("Офіційне джерело (Мінцифра/ПС)")
                if (count > 1) append(" • Група: ×$count")
                avgSpeedKmh?.let { append(" • ~${it.toInt()} км/год") }
            }

            result.add(
                RadarTarget(
                    trackId = "ua_${tid.take(8)}",
                    lat = lat,
                    lng = lng,
                    courseDeg = courseRounded,
                    courseName = courseCompass,
                    category = formular,
                    sub = subDesc,
                    iconName = iconName,
                    tag = if (dtype.isNotEmpty()) dtype else "UA_ALARM",
                    echelon = echelon,
                    avgSpeedKmh = avgSpeedKmh,
                    instantSpeedKmh = instantSpeedKmh,
                    source = "ukrainealarm",
                    sourceLabel = "Офіційна карта «Повітряна тривога»",
                    path = pathCoords,
                    count = count
                )
            )
        }

        return result
    }
}
