package ua.quick.radar.engine

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.*

class RadarApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Persistent multi-sweep track storage (matching fluger_server.py)
    data class InternalTrack(
        val trackId: Int,
        var lat: Double,
        var lng: Double,
        var lastTimeSec: Double,
        val history: MutableList<Pair<Double, Double>> = mutableListOf(),
        val timestamps: MutableList<Double> = mutableListOf(),
        var course: Double? = null,
        var avgSpeed: Double? = null,
        var instantSpeed: Double? = null,
        var maxSpeed: Double = 0.0
    )

    private val activeTracks = ConcurrentHashMap<Int, InternalTrack>()
    private var nextTrackId = 1

    fun generateFibonacciKey(source: String): String {
        val fib = mutableListOf(0, 1)
        while ((fib[fib.size - 1] + fib[fib.size - 2]) < source.length) {
            fib.add(fib[fib.size - 1] + fib[fib.size - 2])
        }
        val sb = java.lang.StringBuilder()
        for (idx in fib) {
            if (idx < source.length) {
                sb.append(source[idx])
            }
        }
        return sb.toString()
    }

    private fun generateHmacSha256(key: String, data: String): String {
        val sha256Hmac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        sha256Hmac.init(secretKey)
        val signedBytes = sha256Hmac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(signedBytes, Base64.NO_WRAP)
    }

    fun fetchRawRadarData(lat: Double, lng: Double): JsonObject? {
        val payload = mapOf(
            "lat" to lat,
            "lng" to lng,
            "fcmToken" to "quick_radar_fcm_token_local",
            "googleToken" to "quick_radar_google_token_local",
            "background" to false
        )
        val jsonStr = gson.toJson(payload)
        val packageB64 = Base64.encodeToString(jsonStr.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val fibKey = generateFibonacciKey(packageB64)
        val signature = generateHmacSha256(fibKey, packageB64)

        val formBody = FormBody.Builder()
            .add("package", packageB64)
            .add("signature", signature)
            .build()

        val request = Request.Builder()
            .url("https://radar.quick.ua/api-2026-09-01/information.php")
            .header("User-Agent", "okhttp/4.12.0")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: return null
                gson.fromJson(bodyStr, JsonObject::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun intersectRays(
        lat1: Double, lon1: Double, brng1: Double,
        lat2: Double, lon2: Double, brng2: Double
    ): Triple<Double, Double, Double>? {
        val phi1 = Math.toRadians(lat1)
        val lam1 = Math.toRadians(lon1)
        val phi2 = Math.toRadians(lat2)
        val lam2 = Math.toRadians(lon2)
        val theta13 = Math.toRadians(brng1)
        val theta23 = Math.toRadians(brng2)
        val dPhi = phi2 - phi1
        val dLam = lam2 - lam1

        val delta12 = 2 * asin(sqrt(sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLam / 2).pow(2)))
        if (delta12 == 0.0) return null

        var cosThetaA = (sin(phi2) - sin(phi1) * cos(delta12)) / (sin(delta12) * cos(phi1))
        cosThetaA = max(-1.0, min(1.0, cosThetaA))
        val thetaA = acos(cosThetaA)

        var cosThetaB = (sin(phi1) - sin(phi2) * cos(delta12)) / (sin(delta12) * cos(phi2))
        cosThetaB = max(-1.0, min(1.0, cosThetaB))
        val thetaB = acos(cosThetaB)

        val theta12: Double
        val theta21: Double
        if (sin(dLam) > 0) {
            theta12 = thetaA
            theta21 = 2 * Math.PI - thetaB
        } else {
            theta12 = 2 * Math.PI - thetaA
            theta21 = thetaB
        }

        val alpha1 = theta13 - theta12
        val alpha2 = theta21 - theta23

        if (sin(alpha1) == 0.0 && sin(alpha2) == 0.0) return null
        if (sin(alpha1) * sin(alpha2) < 0) return null

        val cosAlpha3 = max(-1.0, min(1.0, -cos(alpha1) * cos(alpha2) + sin(alpha1) * sin(alpha2) * cos(delta12)))
        val alpha3 = acos(cosAlpha3)
        val delta13 = atan2(sin(delta12) * sin(alpha1) * sin(alpha2), cos(alpha2) + cos(alpha1) * cos(alpha3))

        val phi3 = asin(sin(phi1) * cos(delta13) + cos(phi1) * sin(delta13) * cos(theta13))
        val dLam13 = atan2(sin(theta13) * sin(delta13) * cos(phi1), cos(delta13) - sin(phi1) * sin(phi3))
        val lam3 = lam1 + dLam13

        val distKm = delta13 * 6371.0
        val resLat = Math.toDegrees(phi3)
        val resLng = (Math.toDegrees(lam3) + 540.0) % 360.0 - 180.0
        return Triple(resLat, resLng, distKm)
    }

    /**
     * Triangulates targets from radar azimuths using 2 probe points (lat,lng) and (lat + 0.08, lng)
     * and performs kinematic cone tracking to calculate speeds, trajectories, and classifications.
     */
    fun fetchFlugerTriangulatedTargets(lat: Double, lng: Double): List<RadarTarget> {
        val r0 = fetchRawRadarData(lat, lng) ?: return emptyList()
        val radarObj = r0.getAsJsonObject("radar") ?: return emptyList()

        val zoneLimits = mapOf(
            "low" to Pair(0.0, 16.0),
            "medium" to Pair(14.0, 32.0),
            "high" to Pair(28.0, 48.0)
        )

        data class RadarObs(val zone: String, val azimuth: Double, val minKm: Double, val maxKm: Double)
        val obs0 = mutableListOf<RadarObs>()
        for ((zone, limits) in zoneLimits) {
            val zData = radarObj.getAsJsonObject(zone) ?: continue
            val azArray = zData.getAsJsonArray("azimuth") ?: continue
            for (azElement in azArray) {
                obs0.add(RadarObs(zone, azElement.asDouble, limits.first, limits.second))
            }
        }

        if (obs0.isEmpty()) return emptyList()

        val lat2 = lat + 0.08
        val lng2 = lng
        val r1 = fetchRawRadarData(lat2, lng2)
        val obs1 = mutableListOf<RadarObs>()
        if (r1 != null && r1.has("radar")) {
            val r1Radar = r1.getAsJsonObject("radar")
            for ((zone, limits) in zoneLimits) {
                val zData = r1Radar.getAsJsonObject(zone) ?: continue
                val azArray = zData.getAsJsonArray("azimuth") ?: continue
                for (azElement in azArray) {
                    obs1.add(RadarObs(zone, azElement.asDouble, limits.first, limits.second))
                }
            }
        }

        val rawDetectedPoints = mutableListOf<Pair<Double, Double>>()

        if (obs1.isNotEmpty()) {
            for (t0 in obs0) {
                for (t1 in obs1) {
                    val intersection = intersectRays(lat, lng, t0.azimuth, lat2, lng2, t1.azimuth) ?: continue
                    val (tLat, tLng, dist0) = intersection
                    val dist1 = ThreatAnalyzer.haversineKm(lat2, lng2, tLat, tLng)

                    if (dist0 in t0.minKm..t0.maxKm && dist1 in t1.minKm..t1.maxKm) {
                        rawDetectedPoints.add(Pair(tLat, tLng))
                    }
                }
            }
        }

        if (rawDetectedPoints.isEmpty()) {
            for (obs in obs0) {
                val midDistMeters = ((obs.minKm + obs.maxKm) / 2.0) * 1000.0
                val est = ThreatAnalyzer.getDestinationPoint(lat, lng, midDistMeters, obs.azimuth)
                rawDetectedPoints.add(est)
            }
        }

        val nowSec = System.currentTimeMillis() / 1000.0
        // Clean stale tracks > 360 seconds
        activeTracks.entries.removeIf { nowSec - it.value.lastTimeSec > 360.0 }

        val resultTargets = mutableListOf<RadarTarget>()
        val usedTracks = mutableSetOf<Int>()

        for (pt in rawDetectedPoints) {
            val pLat = pt.first
            val pLng = pt.second

            // Find closest candidate track within kinematic range
            var bestTrack: InternalTrack? = null
            var bestDist = Double.MAX_VALUE

            for ((tid, tr) in activeTracks) {
                if (usedTracks.contains(tid)) continue
                val dt = max(1.0, nowSec - tr.lastTimeSec)
                val knownSpd = tr.avgSpeed ?: tr.instantSpeed ?: 300.0
                val speedCap = max(knownSpd * 1.35, 1100.0)
                val maxAllowedDist = max(3.0, min(55.0, (speedCap / 3600.0) * dt + 4.0))

                val dRaw = ThreatAnalyzer.haversineKm(tr.lat, tr.lng, pLat, pLng)
                if (dRaw <= maxAllowedDist && dRaw < bestDist) {
                    bestDist = dRaw
                    bestTrack = tr
                }
            }

            val track = if (bestTrack != null) {
                usedTracks.add(bestTrack.trackId)
                bestTrack
            } else {
                val newId = nextTrackId++
                val newTr = InternalTrack(newId, pLat, pLng, nowSec)
                activeTracks[newId] = newTr
                newTr
            }

            track.lat = pLat
            track.lng = pLng
            track.lastTimeSec = nowSec

            val lastSample = track.history.lastOrNull()
            if (lastSample == null || (nowSec - (track.timestamps.lastOrNull() ?: 0.0) >= 3.0) ||
                ThreatAnalyzer.haversineKm(lastSample.first, lastSample.second, pLat, pLng) > 0.04
            ) {
                track.history.add(Pair(pLat, pLng))
                track.timestamps.add(nowSec)
                if (track.history.size > 200) {
                    track.history.removeAt(0)
                    track.timestamps.removeAt(0)
                }
            }

            val hist = track.history
            val sampleCount = hist.size
            val firstTime = track.timestamps.firstOrNull() ?: nowSec
            val totalDt = max(1.0, nowSec - firstTime)

            var avgSpd: Double? = null
            var instSpd: Double? = null

            if (sampleCount >= 2) {
                val firstP = hist.first()
                val lastP = hist.last()
                val netDist = ThreatAnalyzer.haversineKm(firstP.first, firstP.second, lastP.first, lastP.second)
                val prevP = hist[sampleCount - 2]
                val prevTime = track.timestamps[sampleCount - 2]
                val stepDt = max(1.0, nowSec - prevTime)
                val stepD = ThreatAnalyzer.haversineKm(prevP.first, prevP.second, lastP.first, lastP.second)

                if (stepDt >= 2.0 && stepD >= 0.04) {
                    instSpd = ThreatAnalyzer.safeRound((stepD / stepDt) * 3600.0, 0)
                }
                if (totalDt >= 6.0 && netDist >= 0.08) {
                    avgSpd = ThreatAnalyzer.safeRound((netDist / totalDt) * 3600.0, 0)
                }
            }

            track.avgSpeed = avgSpd
            track.instantSpeed = instSpd
            track.maxSpeed = max(track.maxSpeed, max(avgSpd ?: 0.0, instSpd ?: 0.0))

            val course = ThreatAnalyzer.calculateTrackCourse(hist, track.course)
            track.course = course
            val courseCompass = ThreatAnalyzer.bearingToCompass(course)
            val smoothedPath = ThreatAnalyzer.smoothTrajectory(hist)

            val classification = ThreatAnalyzer.classifyTarget(
                avgSpeed = avgSpd ?: instSpd,
                sampleCount = sampleCount,
                maxSpeed = track.maxSpeed,
                obsSec = totalDt
            )

            resultTargets.add(
                RadarTarget(
                    trackId = "flg_${track.trackId}",
                    lat = ThreatAnalyzer.safeRound(pLat, 5),
                    lng = ThreatAnalyzer.safeRound(pLng, 5),
                    courseDeg = ThreatAnalyzer.safeRound(course, 0),
                    courseName = courseCompass,
                    category = classification.name,
                    sub = classification.sub,
                    iconName = classification.iconName,
                    tag = classification.tag,
                    echelon = classification.echelon,
                    avgSpeedKmh = avgSpd,
                    instantSpeedKmh = instSpd,
                    source = "fluger",
                    sourceLabel = "Радар Флюгер",
                    path = smoothedPath
                )
            )
        }

        return resultTargets
    }
}
