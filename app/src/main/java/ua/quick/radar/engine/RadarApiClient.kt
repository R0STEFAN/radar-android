package ua.quick.radar.engine

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
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

        val targets = mutableListOf<RadarTarget>()
        var trackIdx = 1

        if (obs1.isNotEmpty()) {
            for (t0 in obs0) {
                for (t1 in obs1) {
                    val intersection = intersectRays(lat, lng, t0.azimuth, lat2, lng2, t1.azimuth) ?: continue
                    val (tLat, tLng, dist0) = intersection
                    val dist1 = ThreatAnalyzer.haversineKm(lat2, lng2, tLat, tLng)

                    if (dist0 in t0.minKm..t0.maxKm && dist1 in t1.minKm..t1.maxKm) {
                        targets.add(
                            RadarTarget(
                                trackId = "flg_$trackIdx",
                                lat = ThreatAnalyzer.safeRound(tLat, 5),
                                lng = ThreatAnalyzer.safeRound(tLng, 5),
                                courseDeg = null,
                                courseName = null,
                                category = "Повітряна ціль (Флюгер)",
                                sub = "Тріангуляція радіопроменів",
                                iconName = "ic_air_threat_combined_white",
                                tag = "FLUGER",
                                source = "fluger",
                                sourceLabel = "Радар Флюгер"
                            )
                        )
                        trackIdx++
                    }
                }
            }
        }

        // If no triangulation intersection succeeded (e.g. single observation), estimate point along azimuth midpoint
        if (targets.isEmpty()) {
            for (obs in obs0) {
                val midDistMeters = ((obs.minKm + obs.maxKm) / 2.0) * 1000.0
                val r = 6371e3
                val d = midDistMeters / r
                val brng = Math.toRadians(obs.azimuth)
                val lat1 = Math.toRadians(lat)
                val lon1 = Math.toRadians(lng)

                val latEst = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brng))
                val lonEst = lon1 + atan2(sin(brng) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(latEst))
                val degLat = Math.toDegrees(latEst)
                val degLon = (Math.toDegrees(lonEst) + 540.0) % 360.0 - 180.0

                targets.add(
                    RadarTarget(
                        trackId = "flg_est_$trackIdx",
                        lat = ThreatAnalyzer.safeRound(degLat, 5),
                        lng = ThreatAnalyzer.safeRound(degLon, 5),
                        courseDeg = null,
                        courseName = null,
                        category = "Повітряна ціль",
                        sub = "Сектор ${obs.azimuth.toInt()}° (${obs.minKm.toInt()}-${obs.maxKm.toInt()} км)",
                        iconName = "ic_air_threat_combined_white",
                        tag = "FLUGER",
                        source = "fluger",
                        sourceLabel = "Радар Флюгер"
                    )
                )
                trackIdx++
            }
        }

        return targets
    }
}
