package ua.quick.radar.engine

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Dispatcher
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

    private val dispatcher = Dispatcher().apply {
        maxRequests = 32
        maxRequestsPerHost = 20
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
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
        var maxSpeed: Double = 0.0,
        var isKab: Boolean = false,
        var isKabConfirmed: Boolean = false,
        var stagnantCycles: Int = 0,
        var isStagnant: Boolean = false
    )

    private val activeTracks = ConcurrentHashMap<Int, InternalTrack>()
    private var nextTrackId = 1

    fun resetTracks() {
        activeTracks.clear()
        nextTrackId = 1
    }

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

    data class GridNode(val lat: Double, val lng: Double, val name: String)

    private data class RadarObs(
        val zone: String,
        val azimuth: Double,
        val minKm: Double,
        val maxKm: Double,
        val midKm: Double = (minKm + maxKm) / 2.0,
        val color: String = "yellow"
    )

    private data class RawCandidate(
        val lat: Double,
        val lng: Double,
        val isTriangulated: Boolean,
        val weight: Double,
        val sourceNode: Int,
        val azimuth: Double,
        val zone: String,
        val color: String,
        val nodeLat: Double = 0.0,
        val nodeLng: Double = 0.0,
        val minKm: Double = 0.0,
        val maxKm: Double = 0.0
    )

    fun getGrid45km(lat: Double, lng: Double): List<GridNode> {
        val nodes = mutableListOf(GridNode(lat, lng, "Центр (0км)"))
        for (angle in doubleArrayOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0)) {
            val pt = ThreatAnalyzer.getDestinationPoint(lat, lng, 27_000.0, angle)
            nodes.add(GridNode(pt.first, pt.second, "Вузол 27км ${angle.toInt()}°"))
        }
        return nodes
    }

    fun getGrid80km(lat: Double, lng: Double): List<GridNode> {
        val nodes = mutableListOf(GridNode(lat, lng, "Центр (0км)"))
        for (angle in doubleArrayOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0)) {
            val pt = ThreatAnalyzer.getDestinationPoint(lat, lng, 38_000.0, angle)
            nodes.add(GridNode(pt.first, pt.second, "Внутр 38км ${angle.toInt()}°"))
        }
        for (angle in doubleArrayOf(30.0, 90.0, 150.0, 210.0, 270.0, 330.0)) {
            val pt = ThreatAnalyzer.getDestinationPoint(lat, lng, 72_000.0, angle)
            nodes.add(GridNode(pt.first, pt.second, "Зовн 72км ${angle.toInt()}°"))
        }
        return nodes
    }

    fun extractRadarAzimuths(radarDict: JsonObject?): List<Double> {
        if (radarDict == null) return emptyList()
        val radarObj = if (radarDict.has("radar") && radarDict.get("radar").isJsonObject) {
            radarDict.getAsJsonObject("radar")
        } else {
            radarDict
        }
        val azimuths = mutableListOf<Double>()
        for (zone in arrayOf("low", "medium", "high")) {
            val zData = radarObj.getAsJsonObject(zone) ?: continue
            val azArray = zData.getAsJsonArray("azimuth") ?: continue
            for (az in azArray) {
                try {
                    azimuths.add(az.asDouble)
                } catch (e: Exception) {}
            }
        }
        return azimuths
    }

    private fun extractRegionalDetectedPoints(
        grid: List<GridNode>,
        radarResults: List<JsonObject?>,
        radiusKm: Double,
        userLat: Double,
        userLng: Double
    ): List<Pair<Double, Double>> {
        val zoneLimits = mapOf(
            "low" to Pair(0.0, 16.0),
            "medium" to Pair(14.0, 32.0),
            "high" to Pair(28.0, 48.0)
        )

        val nodeObsList = mutableListOf<List<RadarObs>>()
        for ((i, r) in radarResults.withIndex()) {
            val obs = mutableListOf<RadarObs>()
            val radarObj = r?.getAsJsonObject("radar")
            if (radarObj != null) {
                for ((zone, limits) in zoneLimits) {
                    val zData = radarObj.getAsJsonObject(zone) ?: continue
                    val azArray = zData.getAsJsonArray("azimuth") ?: continue
                    val color = if (zData.has("color")) zData.get("color").asString else "yellow"
                    for (azElement in azArray) {
                        obs.add(
                            RadarObs(
                                zone = zone,
                                azimuth = azElement.asDouble,
                                minKm = limits.first,
                                maxKm = limits.second,
                                midKm = (limits.first + limits.second) / 2.0,
                                color = color
                            )
                        )
                    }
                }
            }
            nodeObsList.add(obs)
        }

        // Step A: Multi-node Pairwise Ray Triangulation
        data class TriangulationCandidate(
            val score: Double,
            val lat: Double,
            val lng: Double,
            val node1: Int,
            val az1: Double,
            val node2: Int,
            val az2: Double,
            val zone: String,
            val color: String
        )

        val triCandidates = mutableListOf<TriangulationCandidate>()
        val N = grid.size

        for (i in 0 until N) {
            val n1 = grid[i]
            val obs1 = nodeObsList[i]
            if (obs1.isEmpty()) continue

            for (j in i + 1 until N) {
                val n2 = grid[j]
                val obs2 = nodeObsList[j]
                if (obs2.isEmpty()) continue

                val dNodes = ThreatAnalyzer.haversineKm(n1.lat, n1.lng, n2.lat, n2.lng)
                if (dNodes > 105.0) continue

                for (o1 in obs1) {
                    for (o2 in obs2) {
                        val intersection = intersectRays(n1.lat, n1.lng, o1.azimuth, n2.lat, n2.lng, o2.azimuth) ?: continue
                        val (tLat, tLng, d1) = intersection
                        val d2 = ThreatAnalyzer.haversineKm(n2.lat, n2.lng, tLat, tLng)

                        if (d1 in (o1.minKm - 2.5)..(o1.maxKm + 2.5) && d2 in (o2.minKm - 2.5)..(o2.maxKm + 2.5)) {
                            val angleBetween = abs((o1.azimuth - o2.azimuth + 180.0) % 360.0 - 180.0)
                            if (angleBetween in 12.0..168.0) {
                                val score = abs(d1 - o1.midKm) + abs(d2 - o2.midKm)
                                triCandidates.add(
                                    TriangulationCandidate(
                                        score = score,
                                        lat = tLat,
                                        lng = tLng,
                                        node1 = i,
                                        az1 = o1.azimuth,
                                        node2 = j,
                                        az2 = o2.azimuth,
                                        zone = o1.zone,
                                        color = o1.color
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        triCandidates.sortBy { it.score }
        val usedRays = mutableSetOf<Pair<Int, Double>>()
        val candidateList = mutableListOf<RawCandidate>()

        for (cand in triCandidates) {
            val r1 = Pair(cand.node1, cand.az1)
            val r2 = Pair(cand.node2, cand.az2)
            if (usedRays.contains(r1) || usedRays.contains(r2)) continue
            usedRays.add(r1)
            usedRays.add(r2)

            candidateList.add(
                RawCandidate(
                    lat = cand.lat,
                    lng = cand.lng,
                    isTriangulated = true,
                    weight = 3.0,
                    sourceNode = cand.node1,
                    azimuth = cand.az1,
                    zone = cand.zone,
                    color = cand.color,
                    nodeLat = grid[cand.node1].lat,
                    nodeLng = grid[cand.node1].lng
                )
            )
        }

        // Step B: Untriangulated Rays Estimation
        for (i in 0 until N) {
            val n = grid[i]
            for (o in nodeObsList[i]) {
                val rKey = Pair(i, o.azimuth)
                if (!usedRays.contains(rKey)) {
                    // У локальному режимі (<= 45 км) не додаємо непідтверджені промені від допоміжних вузлів
                    if (radiusKm <= 45.0 && i != 0) {
                        continue
                    }
                    val est = ThreatAnalyzer.getDestinationPoint(n.lat, n.lng, o.midKm * 1000.0, o.azimuth)
                    candidateList.add(
                        RawCandidate(
                            lat = est.first,
                            lng = est.second,
                            isTriangulated = false,
                            weight = 1.0,
                            sourceNode = i,
                            azimuth = o.azimuth,
                            zone = o.zone,
                            color = o.color,
                            nodeLat = n.lat,
                            nodeLng = n.lng,
                            minKm = o.minKm,
                            maxKm = o.maxKm
                        )
                    )
                }
            }
        }

        if (candidateList.isEmpty()) return emptyList()

        // Step C: Error-Aware Spatial Clustering & Deduplication
        val clusters = mutableListOf<MutableList<RawCandidate>>()
        for (cand in candidateList) {
            var matchedCluster: MutableList<RawCandidate>? = null
            var bestDist = Double.MAX_VALUE

            for (cluster in clusters) {
                val isMatch = cluster.any { member ->
                    val d = ThreatAnalyzer.haversineKm(cand.lat, cand.lng, member.lat, member.lng)
                    when {
                        // Both triangulated: tightly clustered within 6.5 km
                        cand.isTriangulated && member.isTriangulated -> d <= 6.5
                        // One triangulated, one untriangulated ray
                        cand.isTriangulated && !member.isTriangulated -> {
                            val brng = ThreatAnalyzer.calculateBearing(member.nodeLat, member.nodeLng, cand.lat, cand.lng)
                            val aDiff = abs((brng - member.azimuth + 180.0) % 360.0 - 180.0)
                            val distNode = ThreatAnalyzer.haversineKm(member.nodeLat, member.nodeLng, cand.lat, cand.lng)
                            (aDiff <= 8.0 && distNode in (member.minKm - 4.0)..(member.maxKm + 5.0)) || d <= 8.5
                        }
                        !cand.isTriangulated && member.isTriangulated -> {
                            val brng = ThreatAnalyzer.calculateBearing(cand.nodeLat, cand.nodeLng, member.lat, member.lng)
                            val aDiff = abs((brng - cand.azimuth + 180.0) % 360.0 - 180.0)
                            val distNode = ThreatAnalyzer.haversineKm(cand.nodeLat, cand.nodeLng, member.lat, member.lng)
                            (aDiff <= 8.0 && distNode in (cand.minKm - 4.0)..(cand.maxKm + 5.0)) || d <= 8.5
                        }
                        // Both untriangulated
                        else -> {
                            if (cand.sourceNode == member.sourceNode) {
                                abs((cand.azimuth - member.azimuth + 180.0) % 360.0 - 180.0) <= 2.0 || d <= 5.0
                            } else {
                                d <= 8.5
                            }
                        }
                    }
                }

                if (isMatch) {
                    val dToFirst = ThreatAnalyzer.haversineKm(cand.lat, cand.lng, cluster.first().lat, cluster.first().lng)
                    if (dToFirst < bestDist) {
                        bestDist = dToFirst
                        matchedCluster = cluster
                    }
                }
            }

            if (matchedCluster != null) {
                matchedCluster.add(cand)
            } else {
                clusters.add(mutableListOf(cand))
            }
        }

        // Step D: Calculate Weighted Centroid for each Cluster
        val finalPoints = mutableListOf<Pair<Double, Double>>()
        for (cluster in clusters) {
            val totalWeight = cluster.sumOf { it.weight }
            val wLat = cluster.sumOf { it.lat * it.weight } / totalWeight
            val wLng = cluster.sumOf { it.lng * it.weight } / totalWeight

            val dUser = ThreatAnalyzer.haversineKm(userLat, userLng, wLat, wLng)
            // Фільтр виходу за межі вибраного радіусу огляду (буфер 4.0 км, як на сервері)
            if (dUser > radiusKm + 4.0) {
                continue
            }

            finalPoints.add(Pair(ThreatAnalyzer.safeRound(wLat, 5), ThreatAnalyzer.safeRound(wLng, 5)))
        }

        return finalPoints
    }

    /**
     * Adaptive dual-mode target surveillance matching fluger_server.py:
     * - Mode 1 (45 km): Tactical Urban Shield. Clear sky -> 1 query to Center. Target ingress -> wakes 2 flanking nodes at 27 km.
     * - Mode 2 (80 km): Regional Air Defense Network. Sentry patrol -> Center + 6 inner nodes at 38 km. Target ingress -> dynamic wake of outer 72 km nodes.
     */
    suspend fun fetchFlugerTriangulatedTargets(
        lat: Double,
        lng: Double,
        radiusKm: Double = 45.0
    ): List<RadarTarget> {
        val effectiveRadius = if (radiusKm > 45.0) 80.0 else 45.0

        val (queriedGrid, radarResults) = if (effectiveRadius <= 45.0) {
            // ======================================================================
            // РЕЖИМ 45 КМ: ТАКТИЧНИЙ ЩИТ МІСТА (Адаптивний гексагональний сектор)
            // ======================================================================
            val gridAll = getGrid45km(lat, lng)
            // Етап 1 (Черговий режим): опитуємо ТІЛЬКИ центральний вузол
            val r0 = fetchRawRadarData(gridAll[0].lat, gridAll[0].lng)
            val cAzimuths = extractRadarAzimuths(r0)

            // Етап 2 (Секторний тригер): при виявленні променя будимо 2 секторні точки на 27 км
            val activeIndices = mutableListOf(0)
            if (cAzimuths.isNotEmpty()) {
                val sectorNodes = mutableSetOf<Int>()
                for (az in cAzimuths) {
                    val sec = ((az % 360.0).toInt() / 60) % 6 // 0..5
                    val n1 = sec + 1
                    val n2 = ((sec + 1) % 6) + 1
                    sectorNodes.add(n1)
                    sectorNodes.add(n2)
                }
                activeIndices.addAll(sectorNodes.sorted())
            }

            if (activeIndices.size == 1) {
                Pair(listOf(gridAll[0]), listOf(r0))
            } else {
                val nodesToFetch = activeIndices.filter { it != 0 }.map { gridAll[it] }
                val extraResults = coroutineScope {
                    nodesToFetch.map { node ->
                        async(Dispatchers.IO) {
                            fetchRawRadarData(node.lat, node.lng)
                        }
                    }.awaitAll()
                }
                Pair(listOf(gridAll[0]) + nodesToFetch, listOf(r0) + extraResults)
            }
        } else {
            // ======================================================================
            // РЕЖИМ 80 КМ: ЕШЕЛОНОВАНЕ ППО (Двоконтурний шаховий гексагон, 13 точок)
            // ======================================================================
            val gridAll = getGrid80km(lat, lng)
            // Етап 1 (Дозорний контур): опитуємо Центр + 6 внутрішніх дозорних на 38 км (0..6)
            val sentryNodes = gridAll.subList(0, 7)
            val sentryResults = coroutineScope {
                sentryNodes.map { node ->
                    async(Dispatchers.IO) {
                        fetchRawRadarData(node.lat, node.lng)
                    }
                }.awaitAll()
            }

            // Таблиця активації: внутрішній дозорний (1..6) будитиме зовнішні точки на 72 км (7..12)
            val innerToOuter = mapOf(
                1 to Pair(12, 7),   // 0° -> Зовн 330° та Зовн 30°
                2 to Pair(7, 8),    // 60° -> Зовн 30° та Зовн 90°
                3 to Pair(8, 9),    // 120° -> Зовн 90° та Зовн 150°
                4 to Pair(9, 10),   // 180° -> Зовн 150° та Зовн 210°
                5 to Pair(10, 11),  // 240° -> Зовн 210° та Зовн 270°
                6 to Pair(11, 12)   // 300° -> Зовн 270° та Зовн 330°
            )

            val outerIndicesToWake = mutableSetOf<Int>()
            for (idx in 1..6) {
                if (extractRadarAzimuths(sentryResults[idx]).isNotEmpty()) {
                    val pair = innerToOuter[idx]
                    if (pair != null) {
                        outerIndicesToWake.add(pair.first)
                        outerIndicesToWake.add(pair.second)
                    }
                }
            }

            if (outerIndicesToWake.isEmpty()) {
                Pair(sentryNodes, sentryResults)
            } else {
                val wakeList = outerIndicesToWake.sorted()
                val outerNodesToFetch = wakeList.map { gridAll[it] }
                val outerResults = coroutineScope {
                    outerNodesToFetch.map { node ->
                        async(Dispatchers.IO) {
                            fetchRawRadarData(node.lat, node.lng)
                        }
                    }.awaitAll()
                }
                Pair(sentryNodes + outerNodesToFetch, sentryResults + outerResults)
            }
        }

        val rawDetectedPoints = extractRegionalDetectedPoints(queriedGrid, radarResults, effectiveRadius, lat, lng)
        return processDetectedPointsToTargets(rawDetectedPoints)
    }

    private fun processDetectedPointsToTargets(rawDetectedPoints: List<Pair<Double, Double>>): List<RadarTarget> {

        val nowSec = System.currentTimeMillis() / 1000.0
        // Clean stale tracks > 75 seconds
        activeTracks.entries.removeIf { nowSec - it.value.lastTimeSec > 75.0 }

        // 1. Compute candidate pairs using Kinematic Cone & Forward Flight Projection
        data class CandidatePair(val cost: Double, val pIdx: Int, val trackId: Int)
        val distPairs = mutableListOf<CandidatePair>()

        for ((pIdx, pt) in rawDetectedPoints.withIndex()) {
            val pLat = pt.first
            val pLng = pt.second
            for ((tid, tr) in activeTracks) {
                val dt = max(1.0, nowSec - tr.lastTimeSec)
                val knownSpd = tr.avgSpeed ?: tr.instantSpeed ?: 350.0
                val course = tr.course

                val speedCap = max(knownSpd * 1.35, 1100.0)
                // Realistic physical distance a target can cover in dt seconds (e.g. at 350 km/h in 20s = 1.9 km)
                val maxAllowedDist = max(1.5, min(10.0, (speedCap / 3600.0) * dt + 1.2))
                val dRaw = ThreatAnalyzer.haversineKm(tr.lat, tr.lng, pLat, pLng)
                if (dRaw > maxAllowedDist) continue

                // Kinematic & Directional Association:
                // Accommodates real-time Shahed maneuvering (acute turns, evasive zigzags) and Fluger sector quantization jitter
                val cost = if (knownSpd >= 80.0 && course != null && tr.history.size >= 2) {
                    val projDistM = (knownSpd * 1000.0 / 3600.0) * dt
                    val projPt = ThreatAnalyzer.getDestinationPoint(tr.lat, tr.lng, projDistM, course)
                    val dProj = ThreatAnalyzer.haversineKm(projPt.first, projPt.second, pLat, pLng)

                    val bearingStep = ThreatAnalyzer.calculateBearing(tr.lat, tr.lng, pLat, pLng)
                    val angleDiff = abs((bearingStep - course + 180.0) % 360.0 - 180.0)

                    // Prioritize forward projection when multiple candidates exist,
                    // but never drop physically reachable targets executing acute turns or zigzags
                    dProj * 0.7 + dRaw * 0.3 + (angleDiff / 25.0)
                } else {
                    dRaw
                }
                distPairs.add(CandidatePair(cost, pIdx, tid))
            }
        }

        distPairs.sortBy { it.cost }
        val matchedP = mutableMapOf<Int, Int>()
        val usedTracks = mutableSetOf<Int>()

        for (cand in distPairs) {
            if (matchedP.containsKey(cand.pIdx) || usedTracks.contains(cand.trackId)) continue
            matchedP[cand.pIdx] = cand.trackId
            usedTracks.add(cand.trackId)
        }

        val updatedTargets = mutableListOf<RadarTarget>()

        for ((pIdx, pt) in rawDetectedPoints.withIndex()) {
            val pLat = pt.first
            val pLng = pt.second

            val tid = matchedP[pIdx]
            val track = if (tid != null && activeTracks.containsKey(tid)) {
                activeTracks[tid]!!
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
            val stepD = if (lastSample != null) ThreatAnalyzer.haversineKm(lastSample.first, lastSample.second, pLat, pLng) else 0.0
            val lastTime = track.timestamps.lastOrNull() ?: nowSec
            val stepDt = max(1.0, nowSec - lastTime)

            if (lastSample == null || (nowSec - lastTime >= 4.0) || stepD > 0.04) {
                track.history.add(Pair(pLat, pLng))
                track.timestamps.add(nowSec)
                if (track.history.size > 400) {
                    track.history.removeAt(0)
                    track.timestamps.removeAt(0)
                }
            }

            val hist = track.history
            val sampleCount = hist.size
            val firstP = hist.first()
            val totalDt = max(1.0, nowSec - (track.timestamps.firstOrNull() ?: nowSec))
            val netDist = ThreatAnalyzer.haversineKm(firstP.first, firstP.second, pLat, pLng)

            // Stagnation check
            if (stepD < 0.18) {
                track.stagnantCycles++
            } else {
                track.stagnantCycles = 0
            }
            track.isStagnant = (track.stagnantCycles >= 3) && (netDist < 0.35)

            // Sliding window instant speed calculation
            var instantSpeed = 0.0
            val recentIndices = track.timestamps.indices.filter { nowSec - track.timestamps[it] <= 35.0 }
            if (recentIndices.size >= 2) {
                val idx0 = recentIndices.first()
                val idx1 = recentIndices.last()
                val wDt = track.timestamps[idx1] - track.timestamps[idx0]
                if (wDt >= 8.0) {
                    val wDist = ThreatAnalyzer.haversineKm(hist[idx0].first, hist[idx0].second, hist[idx1].first, hist[idx1].second)
                    instantSpeed = (wDist / wDt) * 3600.0
                }
            } else if (stepDt >= 8.0 && stepD >= 0.12) {
                instantSpeed = (stepD / stepDt) * 3600.0
            } else if (track.instantSpeed != null && track.instantSpeed!! > 0.0) {
                instantSpeed = track.instantSpeed!!
            } else if (totalDt >= 8.0) {
                instantSpeed = (netDist / totalDt) * 3600.0
            }

            if (stepD < 0.10) {
                instantSpeed = min(instantSpeed, 120.0)
            }

            // Average speed calculation on smoothed trajectory
            var avgSpeed: Double? = null
            if (track.isStagnant) {
                avgSpeed = 0.0
                instantSpeed = 0.0
            } else if (totalDt >= 10.0) {
                if (sampleCount >= 3) {
                    val smPts = ThreatAnalyzer.smoothTrajectory(hist)
                    var pathLen = 0.0
                    for (i in 1 until smPts.size) {
                        pathLen += ThreatAnalyzer.haversineKm(smPts[i-1][0], smPts[i-1][1], smPts[i][0], smPts[i][1])
                    }
                    avgSpeed = (pathLen / totalDt) * 3600.0
                } else {
                    avgSpeed = (netDist / totalDt) * 3600.0
                }
            } else {
                avgSpeed = if (instantSpeed > 0.0) instantSpeed else null
            }

            // Max speed protection & KAB filtering (matching fluger_server.py):
            val prevMax = track.maxSpeed
            var currentMax = prevMax
            if (totalDt >= 14.0 && avgSpeed != null && avgSpeed > 40.0) {
                val validInstant = if (instantSpeed <= max(avgSpeed * 1.4, 380.0)) instantSpeed else avgSpeed
                currentMax = max(prevMax, max(avgSpeed, validInstant))
            } else {
                currentMax = prevMax
            }

            // Захист та калібрування статусу КАБ (УМПК):
            // Бомба планує не довше 160 с і не повільніше швидкості зриву (~300 км/год)
            if (avgSpeed != null && (avgSpeed < 340.0 || totalDt > 160.0 || (instantSpeed > 0.0 && instantSpeed < 260.0))) {
                currentMax = min(currentMax, if (avgSpeed > 40.0) avgSpeed * 1.3 else 220.0)
                track.isKab = false
                track.isKabConfirmed = false
            }
            track.maxSpeed = ThreatAnalyzer.safeRound(currentMax, 1)

            // Раннє маркування КАБ (з 2-го заміру, тільки при збереженні високої швидкості):
            if (totalDt <= 160.0 && avgSpeed != null && avgSpeed >= 340.0 && (instantSpeed <= 0.0 || instantSpeed >= 260.0)) {
                if (sampleCount >= 2 && (avgSpeed >= 380.0 || currentMax >= 450.0)) {
                    track.isKab = true
                }
            } else {
                track.isKab = false
                track.isKabConfirmed = false
            }

            track.avgSpeed = avgSpeed?.let { ThreatAnalyzer.safeRound(it, 0) }
            track.instantSpeed = ThreatAnalyzer.safeRound(instantSpeed, 0)

            val course = ThreatAnalyzer.calculateTrackCourse(hist, track.course)
            track.course = course
            val courseCompass = ThreatAnalyzer.bearingToCompass(course)
            val smoothedPath = ThreatAnalyzer.smoothTrajectory(hist)

            updatedTargets.add(
                RadarTarget(
                    trackId = "flg_${track.trackId}",
                    lat = ThreatAnalyzer.safeRound(pLat, 5),
                    lng = ThreatAnalyzer.safeRound(pLng, 5),
                    courseDeg = course?.let { ThreatAnalyzer.safeRound(it, 0) },
                    courseName = courseCompass,
                    avgSpeedKmh = track.avgSpeed,
                    instantSpeedKmh = track.instantSpeed,
                    source = "fluger",
                    sourceLabel = "Радар Флюгер",
                    sampleCount = hist.size,
                    path = smoothedPath
                )
            )
        }

        // 4. Salvo Corridor Clustering (flights of 2-4 KABs/threats in the same corridor)
        val salvoGroups = mutableMapOf<Int, MutableSet<String>>()
        val assignedSalvo = mutableMapOf<String, Int>()
        var nextSalvoId = 1

        val activeThreats = updatedTargets.filter {
            (it.avgSpeedKmh ?: 0.0) >= 140.0 && it.courseDeg != null && it.path.size >= 2
        }

        for (i in activeThreats.indices) {
            val p1 = activeThreats[i]
            for (j in i + 1 until activeThreats.size) {
                val p2 = activeThreats[j]
                val courseDiff = abs((p1.courseDeg!! - p2.courseDeg!! + 180.0) % 360.0 - 180.0)
                if (courseDiff > 28.0) continue

                val dBetween = ThreatAnalyzer.haversineKm(p1.lat, p1.lng, p2.lat, p2.lng)
                if (dBetween !in 0.8..14.0) continue

                val lineBrng = ThreatAnalyzer.calculateBearing(p1.lat, p1.lng, p2.lat, p2.lng)
                val lineDiff = min(
                    abs((lineBrng - p1.courseDeg!! + 180.0) % 360.0 - 180.0),
                    abs(((lineBrng + 180.0) % 360.0 - p1.courseDeg!! + 180.0) % 360.0 - 180.0)
                )
                if (lineDiff <= 35.0) {
                    val gid = assignedSalvo[p1.trackId] ?: assignedSalvo[p2.trackId] ?: nextSalvoId++
                    assignedSalvo[p1.trackId] = gid
                    assignedSalvo[p2.trackId] = gid
                    salvoGroups.getOrPut(gid) { mutableSetOf() }.apply {
                        add(p1.trackId)
                        add(p2.trackId)
                    }
                }
            }
        }

        // 5. Final target classification with salvo labels
        val finalTargets = mutableListOf<RadarTarget>()
        for (target in updatedTargets) {
            val tidInt = target.trackId.removePrefix("flg_").toIntOrNull()
            val tr = tidInt?.let { activeTracks[it] }

            val inSalvo = assignedSalvo.containsKey(target.trackId)
            var salvoPrefix = ""
            var salvoCount = 1
            if (inSalvo) {
                val gid = assignedSalvo[target.trackId]!!
                val members = salvoGroups[gid]!!.sorted()
                val sIdx = members.indexOf(target.trackId) + 1
                salvoCount = members.size
                if (salvoCount > 1) {
                    salvoPrefix = " [Група $sIdx/$salvoCount]"
                }
            }

            val histSize = tr?.history?.size ?: target.path.size
            val firstTime = tr?.timestamps?.firstOrNull() ?: nowSec
            val obsSec = nowSec - firstTime

            val cls = if (tr?.isStagnant == true) {
                ClassificationResult(
                    name = "Стаціонарний сигнал / Завада",
                    sub = "Електромагнітна завада або фантом (без руху)",
                    icon = "⚡",
                    iconName = "ic_drone",
                    tag = "STATIONARY",
                    echelon = "На поверхні"
                )
            } else {
                ThreatAnalyzer.classifyTarget(
                    avgSpeed = target.avgSpeedKmh ?: target.instantSpeedKmh,
                    sampleCount = histSize,
                    maxSpeed = tr?.maxSpeed,
                    instantSpeed = target.instantSpeedKmh ?: tr?.instantSpeed,
                    obsSec = obsSec,
                    inSalvo = inSalvo,
                    salvoIdx = sIdx,
                    salvoTotal = salvoCount,
                    isKabConfirmed = tr?.isKab == true,
                    salvoPrefix = salvoPrefix
                )
            }

            val course = target.courseDeg ?: tr?.course
            val isStationaryOrStagnant = (tr?.isStagnant == true || cls.tag == "STATIONARY")

            finalTargets.add(
                target.copy(
                    courseDeg = course?.let { ThreatAnalyzer.safeRound(it, 0) },
                    courseName = course?.let { ThreatAnalyzer.bearingToCompass(it) } ?: (if (isStationaryOrStagnant) "Без руху" else "Визначається..."),
                    sampleCount = histSize,
                    category = cls.name,
                    sub = cls.sub,
                    iconName = cls.iconName,
                    tag = cls.tag,
                    echelon = cls.echelon,
                    count = salvoCount,
                    isStagnant = tr?.isStagnant == true,
                    isUnknownState = isStationaryOrStagnant
                )
            )
        }

        return finalTargets
    }
}
