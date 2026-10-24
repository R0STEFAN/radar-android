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

    var isPublicTargetsEnabled: Boolean = true
        private set
    var pauseReason: String? = null
        private set

    fun resetTracks() {
        trackHistories.clear()
    }

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
        val url = "https://map.ukrainealarm.com/api/centrifuge/map/fallback"
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
        } catch (e: Throwable) {
            return emptyList()
        }

        val parsed = try {
            val trimmed = responseBody.trim()
            CentrifugeMapDecoder.decode(trimmed)
                ?: gson.fromJson(responseBody, UaTargetsResponse::class.java)
        } catch (e: Throwable) {
            null
        } ?: return emptyList()

        val isPublicEnabled = parsed.publicTargetsEnabled ?: true
        isPublicTargetsEnabled = isPublicEnabled
        if (!isPublicEnabled) {
            pauseReason = "Офіційне джерело (Мінцифра) оновлює карту, відображення цілей тимчасово призупинено"
            trackHistories.clear()
            return emptyList()
        } else {
            pauseReason = null
        }

        val nowSec = System.currentTimeMillis() / 1000.0

        // Pre-initialize movement starting vector points (movement.from) for immediate target activation
        val movementFrom = parsed.movement?.from
        if (!movementFrom.isNullOrEmpty()) {
            for (mItem in movementFrom) {
                val mTid = mItem.trackId ?: continue
                if (trackHistories.containsKey(mTid)) continue

                val mDur = (mItem.movementDurationMs?.toDouble() ?: 20000.0) / 1000.0
                val fromTs = nowSec - maxOf(5.0, minOf(60.0, mDur))
                val mOx = mItem.markerOffsetX ?: 0.0
                val mOy = mItem.markerOffsetY ?: 0.0
                val (mLat, mLng) = if (mItem.lat != null && mItem.lng != null && mItem.lat != 0.0) {
                    Pair(ThreatAnalyzer.safeRound(mItem.lat, 5), ThreatAnalyzer.safeRound(mItem.lng, 5))
                } else {
                    normalizedToLngLat(mItem.x + mOx, mItem.y + mOy)
                }

                val hist = TrackHistory()
                hist.lastSeenSec = nowSec
                hist.history.add(TrackPoint(fromTs, mLat, mLng))
                trackHistories[mTid] = hist
            }
        }

        // Prune stale tracks older than 75 seconds (~3.5 cycles)
        trackHistories.entries.removeIf { nowSec - it.value.lastSeenSec > 75.0 }

        val items = when {
            parsed.targets != null && parsed.targets.isNotEmpty() -> parsed.targets
            parsed.movement?.from != null -> parsed.movement.from
            else -> emptyList()
        }

        val result = mutableListOf<RadarTarget>()

        for (item in items) {
            val tid = item.trackId ?: continue
            val ox = item.markerOffsetX ?: 0.0
            val oy = item.markerOffsetY ?: 0.0
            val (lat, lng) = if (item.lat != null && item.lng != null && item.lat != 0.0) {
                Pair(ThreatAnalyzer.safeRound(item.lat, 5), ThreatAnalyzer.safeRound(item.lng, 5))
            } else {
                normalizedToLngLat(item.x + ox, item.y + oy)
            }

            var iconName = item.iconName?.trim() ?: ""
            var formular = item.formular?.trim() ?: ""
            val dtype = item.detectedType?.trim() ?: ""
            val count = item.currentCount ?: 1

            if (iconName.isEmpty()) {
                val lowDtype = dtype.lowercase()
                val lowForm = formular.lowercase()
                iconName = when {
                    "kab" in lowDtype || "каб" in lowForm -> "ic_airbomb"
                    "banderol" in lowDtype || "бандероль" in lowForm -> "ic_banderol"
                    "jet" in lowDtype || "реакт" in lowForm -> "ic_shahed_jet"
                    "shahed" in lowDtype || "шахед" in lowForm || "дрон" in lowForm -> "ic_shahed"
                    "missile" in lowDtype || "ракета" in lowForm -> "ic_missile"
                    "ballistic" in lowDtype || "баліст" in lowForm -> "ic_ballistic"
                    "drone" in lowDtype || "розвід" in lowForm || "lancet" in lowDtype || "zala" in lowDtype || "supercam" in lowDtype -> "ic_drone"
                    else -> "ic_air_threat_combined_white"
                }
            }

            if (formular.isEmpty()) {
                formular = when {
                    iconName == "ic_shahed" -> "Ударний БПЛА (Шахед)"
                    "shahed_jet" in iconName -> "Реактивний БПЛА"
                    iconName == "ic_banderol" -> "Швидкісний БПЛА «Бандероль»"
                    iconName == "ic_airbomb" -> "КАБ (УМПК)"
                    iconName == "ic_missile" -> "Крилата ракета"
                    iconName == "ic_ballistic" -> "Балістична ракета"
                    iconName == "ic_drone" -> "Розвідувальний БПЛА"
                    else -> if (dtype.isNotEmpty()) dtype else "Повітряна ціль"
                }
            }

            val echelon = when {
                iconName == "ic_airbomb" -> "Планування з висоти (6 000 – 10 000 м → піке)"
                iconName == "ic_shahed" -> "Бриючий ешелон (80 – 350 м)"
                "shahed_jet" in iconName || iconName == "ic_banderol" -> "Середній ешелон (1 500 – 3 500 м)"
                iconName == "ic_missile" -> "Наднизький ешелон (40 – 120 м)"
                iconName == "ic_ballistic" -> "Стратосфера (15+ км) → круте піке"
                iconName == "ic_drone" -> "Тактичний оглядовий (1 000 – 2 500 м)"
                else -> "Висотний ешелон"
            }

            // Якщо в цілі є вектор prediction і історія треку ще порожня — додаємо початкову точку
            val pred = item.prediction
            if (!trackHistories.containsKey(tid) && pred != null && (pred.fromX != 0.0 || pred.fromY != 0.0)) {
                val pOx = pred.fromMarkerOffsetX ?: ox
                val pOy = pred.fromMarkerOffsetY ?: oy
                val (pLat, pLng) = normalizedToLngLat(pred.fromX + pOx, pred.fromY + pOy)
                if (ThreatAnalyzer.haversineKm(pLat, pLng, lat, lng) >= 0.05) {
                    val mDur = (item.movementDurationMs?.toDouble() ?: 20000.0) / 1000.0
                    val fromTs = nowSec - maxOf(5.0, minOf(60.0, mDur))
                    val hist = TrackHistory()
                    hist.lastSeenSec = nowSec
                    hist.history.add(TrackPoint(fromTs, pLat, pLng))
                    trackHistories[tid] = hist
                }
            }

            val track = trackHistories.getOrPut(tid) { TrackHistory() }
            track.lastSeenSec = nowSec

            val lastPoint = track.history.lastOrNull()
            if (lastPoint == null || (nowSec - lastPoint.timeSec >= 1.5) ||
                ThreatAnalyzer.haversineKm(lastPoint.lat, lastPoint.lng, lat, lng) > 0.05
            ) {
                track.history.add(TrackPoint(nowSec, lat, lng))
                if (track.history.size > 200) track.history.removeAt(0)
            }

            val history = track.history
            var avgSpeedKmh: Double? = null
            var instantSpeedKmh: Double? = null
            var courseDeg = item.heading

            var totalDist = 0.0
            if (history.size >= 2) {
                val firstP = history.first()
                val lastP = history.last()
                val totalDt = lastP.timeSec - firstP.timeSec
                totalDist = ThreatAnalyzer.haversineKm(firstP.lat, firstP.lng, lastP.lat, lastP.lng)

                if (totalDt > 1.5 && totalDist >= 0.05) {
                    avgSpeedKmh = ThreatAnalyzer.safeRound((totalDist / totalDt) * 3600.0, 0)
                }

                val prevP = history[history.size - 2]
                val dtInst = lastP.timeSec - prevP.timeSec
                val distInst = ThreatAnalyzer.haversineKm(prevP.lat, prevP.lng, lastP.lat, lastP.lng)
                if (dtInst > 0.8 && distInst >= 0.03) {
                    instantSpeedKmh = ThreatAnalyzer.safeRound((distInst / dtInst) * 3600.0, 0)
                }

                if (courseDeg == null && distInst >= 0.05) {
                    courseDeg = ThreatAnalyzer.calculateBearing(prevP.lat, prevP.lng, lastP.lat, lastP.lng)
                }
            }

            // Якщо швидкість ще не обчислена по треку, але є офіційний курс — призначаємо типову крейсерську швидкість
            if (avgSpeedKmh == null && courseDeg != null) {
                avgSpeedKmh = when {
                    iconName == "ic_airbomb" -> 480.0
                    "shahed_jet" in iconName || iconName == "ic_banderol" -> 420.0
                    "missile" in iconName -> 750.0
                    "ballistic" in iconName -> 2200.0
                    "drone" in iconName -> 115.0
                    else -> 185.0
                }
            }

            // Офіційні верифіковані цілі Мінцифри з відомим курсом (heading) відображаються одразу
            val isMoving = courseDeg != null && (
                item.heading != null || (history.size >= 2 && (avgSpeedKmh ?: instantSpeedKmh ?: 0.0) >= 45.0 && totalDist >= 0.25)
            )

            if (!isMoving) {
                continue
            }

            // Optimized flight path: keep recent points with spatial threshold >= 120m
            val recentHist = history.takeLast(50)
            val flightPath = mutableListOf<List<Double>>()
            var lastP: List<Double>? = null
            for (p in recentHist) {
                val pt = listOf(ThreatAnalyzer.safeRound(p.lat, 5), ThreatAnalyzer.safeRound(p.lng, 5))
                if (lastP == null || ThreatAnalyzer.haversineKm(lastP[0], lastP[1], pt[0], pt[1]) >= 0.12) {
                    flightPath.add(pt)
                    lastP = pt
                }
            }
            if (recentHist.isNotEmpty()) {
                val lastHistPt = listOf(ThreatAnalyzer.safeRound(recentHist.last().lat, 5), ThreatAnalyzer.safeRound(recentHist.last().lng, 5))
                if (flightPath.isEmpty() || flightPath.last() != lastHistPt) {
                    flightPath.add(lastHistPt)
                }
            }

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
                    path = flightPath,
                    count = count,
                    sampleCount = history.size
                )
            )
        }

        return result
    }
}
