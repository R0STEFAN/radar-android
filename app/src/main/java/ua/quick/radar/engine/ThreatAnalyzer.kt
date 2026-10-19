package ua.quick.radar.engine

import kotlin.math.*

object ThreatAnalyzer {

    const val UKRAINE_LAT_MIN = 44.0
    const val UKRAINE_LAT_MAX = 52.5
    const val UKRAINE_LNG_MIN = 22.0
    const val UKRAINE_LNG_MAX = 40.5

    fun isInsideUkraine(lat: Double, lng: Double): Boolean {
        return lat in UKRAINE_LAT_MIN..UKRAINE_LAT_MAX && lng in UKRAINE_LNG_MIN..UKRAINE_LNG_MAX
    }

    fun safeRound(value: Double?, decimals: Int = 1, default: Double = 0.0): Double {
        if (value == null || value.isNaN() || value.isInfinite()) return default
        val factor = 10.0.pow(decimals)
        return round(value * factor) / factor
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val clampedA = max(0.0, min(1.0, a))
        return 2 * r * asin(sqrt(clampedA))
    }

    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLam = Math.toRadians(lon2 - lon1)

        val y = sin(dLam) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLam)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    fun bearingToCompass(deg: Double?): String {
        if (deg == null) return "Визначається..."
        val directions = arrayOf("Пн", "Пн-Сх", "Сх", "Пд-Сх", "Пд", "Пд-Зх", "Зх", "Пн-Зх")
        val index = (round(((deg % 360.0) / 45.0)).toInt() % 8 + 8) % 8
        return directions[index]
    }

    fun getDestinationPoint(lat: Double, lng: Double, distMeters: Double, bearingDeg: Double): Pair<Double, Double> {
        val r = 6371e3
        val d = distMeters / r
        val brng = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lng)

        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brng))
        val lon2 = lon1 + atan2(sin(brng) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return Pair(Math.toDegrees(lat2), (Math.toDegrees(lon2) + 540.0) % 360.0 - 180.0)
    }

    fun smoothTrajectory(history: List<Pair<Double, Double>>): List<List<Double>> {
        if (history.isEmpty()) return emptyList()
        if (history.size < 3) {
            return history.map { listOf(safeRound(it.first, 5), safeRound(it.second, 5)) }
        }

        val filtered = history.toMutableList()
        val n = filtered.size
        for (i in 1 until n - 1) {
            val pPrev = filtered[i - 1]
            val pCurr = filtered[i]
            val pNext = filtered[i + 1]

            val midLat = (pPrev.first + pNext.first) / 2.0
            val midLng = (pPrev.second + pNext.second) / 2.0
            val dSpike = haversineKm(pCurr.first, pCurr.second, midLat, midLng)
            val dStep = haversineKm(pPrev.first, pPrev.second, pNext.first, pNext.second)

            if (dSpike > 1.0 && dSpike > dStep * 0.6) {
                filtered[i] = Pair(midLat, midLng)
            }
        }

        var smoothed = filtered.toList()
        repeat(2) {
            val temp = smoothed.toMutableList()
            for (i in 1 until n - 1) {
                val lat = 0.20 * smoothed[i - 1].first + 0.60 * smoothed[i].first + 0.20 * smoothed[i + 1].first
                val lng = 0.20 * smoothed[i - 1].second + 0.60 * smoothed[i].second + 0.20 * smoothed[i + 1].second
                temp[i] = Pair(lat, lng)
            }
            smoothed = temp
        }

        return smoothed.map { listOf(safeRound(it.first, 5), safeRound(it.second, 5)) }
    }

    fun calculateTrackCourse(history: List<Pair<Double, Double>>, prevCourse: Double? = null): Double? {
        val n = history.size
        if (n < 2) return prevCourse

        val pStart = history[0]
        val pEnd = history[n - 1]
        val netD = haversineKm(pStart.first, pStart.second, pEnd.first, pEnd.second)
        if (netD < 0.15) return prevCourse

        if (n == 2) {
            return safeRound(calculateBearing(pStart.first, pStart.second, pEnd.first, pEnd.second), 0)
        }

        val weights = doubleArrayOf(0.55, 0.30, 0.15)
        var vxSum = 0.0
        var vySum = 0.0
        var weightAcc = 0.0
        var segIdx = 0

        for (i in n - 1 downTo 1) {
            if (segIdx >= weights.size) break
            val pCurr = history[i]
            val pPrev = history[i - 1]
            val dKm = haversineKm(pPrev.first, pPrev.second, pCurr.first, pCurr.second)
            if (dKm < 0.05) continue

            val bRad = Math.toRadians(calculateBearing(pPrev.first, pPrev.second, pCurr.first, pCurr.second))
            val w = weights[segIdx]
            vxSum += w * sin(bRad)
            vySum += w * cos(bRad)
            weightAcc += w
            segIdx++
        }

        if (weightAcc < 0.1) {
            val pRecent = history[max(0, n - 4)]
            if (haversineKm(pRecent.first, pRecent.second, pEnd.first, pEnd.second) >= 0.1) {
                return safeRound(calculateBearing(pRecent.first, pRecent.second, pEnd.first, pEnd.second), 0)
            }
            return prevCourse
        }

        val rawCourse = (Math.toDegrees(atan2(vxSum, vySum)) + 360.0) % 360.0
        return safeRound(rawCourse, 0)
    }

    fun classifyTarget(
        avgSpeed: Double?,
        sampleCount: Int,
        maxSpeed: Double? = null,
        instantSpeed: Double? = null,
        obsSec: Double = 0.0,
        inSalvo: Boolean = false,
        salvoIdx: Int = 1,
        salvoTotal: Int = 1,
        isKabConfirmed: Boolean = false,
        salvoPrefix: String = ""
    ): ClassificationResult {
        if (sampleCount < 2 || avgSpeed == null) {
            return ClassificationResult(
                name = "Нова ціль (визначення...)",
                sub = "Збір замірів для усунення коливань",
                icon = "🎯",
                iconName = "ic_air_threat_combined_white",
                tag = "NEW",
                echelon = "Визначається..."
            )
        }

        if (avgSpeed < 50.0) {
            return ClassificationResult(
                name = "Стаціонарний сигнал / Завада",
                sub = "Електромагнітна завада, гроза або фантом",
                icon = "⚡",
                iconName = "ic_drone",
                tag = "STATIONARY",
                echelon = "На поверхні"
            )
        }

        val prefix = if (salvoPrefix.isNotEmpty()) {
            salvoPrefix
        } else if (inSalvo && salvoTotal > 1) {
            " [Група $salvoIdx/$salvoTotal]"
        } else {
            ""
        }
        val maxSpd = maxSpeed ?: avgSpeed

        // STRICT AERODYNAMIC KAB (UMPK GLIDE BOMB) FILTER
        // 1. Lifetime cap: unpowered glide bomb cannot remain airborne > 160 s (2.6 min).
        // 2. Physical speed floor: 500-1500 kg bomb stalls < 300 km/h.
        //    Must have avgSpeed >= 340 km/h AND (instantSpeed == null || instantSpeed >= 260 km/h).
        // 3. Early detection from sampleCount >= 2
        var isRealKab = false
        if (obsSec <= 160.0 && avgSpeed >= 340.0 && (instantSpeed == null || instantSpeed >= 260.0)) {
            if (inSalvo && salvoTotal >= 2) {
                isRealKab = true
            } else if (sampleCount >= 2 && (avgSpeed >= 380.0 || maxSpd >= 450.0)) {
                isRealKab = true
            }
        }

        if (isRealKab) {
            return when {
                inSalvo && salvoTotal >= 2 -> ClassificationResult(
                    name = "КАБ (УМПК) у залпі$prefix",
                    sub = "Парний скид авіабомб (${avgSpeed.toInt()} км/год, пікова ${maxSpd.toInt()} км/год)",
                    icon = "💣",
                    iconName = "ic_airbomb",
                    tag = "KAB",
                    echelon = "Планування з висоти (сходження 5000м → 0м)"
                )
                avgSpeed in 350.0..549.9 -> ClassificationResult(
                    name = "КАБ (УМПК) на зниженні$prefix",
                    sub = "Планування/піке (${avgSpeed.toInt()} км/год, пікова ${maxSpd.toInt()} км/год)",
                    icon = "💣",
                    iconName = "ic_airbomb",
                    tag = "KAB",
                    echelon = "Пікірування (сходження 4000м → 0м)"
                )
                else -> ClassificationResult(
                    name = "КАБ (УМПК) / Авіабомба$prefix",
                    sub = "Швидкісний схід з висоти (${avgSpeed.toInt()} км/год)",
                    icon = "💣",
                    iconName = "ic_airbomb",
                    tag = "KAB",
                    echelon = "Висотний ешелон (6 000 – 10 000 м)"
                )
            }
        }

        return when {
            avgSpeed < 135.0 -> ClassificationResult(
                name = "БПЛА: Розвідник / «Молнія»$prefix",
                sub = "Орлан / Zala / Supercam або ударний камікадзе «Молнія-1/2»",
                icon = "🛸",
                iconName = "ic_drone",
                tag = "UAV",
                echelon = "Тактичний оглядовий (1 000 – 2 500 м)"
            )
            avgSpeed < 250.0 -> ClassificationResult(
                name = "Шахед / «Молнія» (піке)$prefix",
                sub = "Shahed-136 або пікіруюча «Молнія» / камікадзе",
                icon = "🛩️",
                iconName = "ic_shahed",
                tag = "SHAHED",
                echelon = "Бриючий ешелон (80 – 350 м)"
            )
            avgSpeed < 550.0 -> ClassificationResult(
                name = "Реактивний БПЛА$prefix",
                sub = "Shahed-238 або швидкісний дрон",
                icon = "⚡",
                iconName = "ic_shahed_jet",
                tag = "JET",
                echelon = "Середній ешелон (1 500 – 3 500 м)"
            )
            avgSpeed < 1200.0 -> ClassificationResult(
                name = "Крилата ракета / Швидкісна ціль$prefix",
                sub = "Бандероль (S8000) / Х-101 / Калібр / реактивний боєприпас",
                icon = "🚀",
                iconName = "ic_missile",
                tag = "MISSILE",
                echelon = "Наднизький ешелон (40 – 120 м)"
            )
            else -> ClassificationResult(
                name = "Балістика / Надзвукова$prefix",
                sub = "Аеробалістика або гіперзвук",
                icon = "💥",
                iconName = "ic_ballistic",
                tag = "BALLISTIC",
                echelon = "Стратосфера (15+ км) → круте піке"
            )
        }
    }

    /**
     * Assesses targets relative to the user's pinned location.
     * Sets kinematic fields on targets and partitions them into Critical and Sector threats.
     */
    fun assessThreats(
        targets: List<RadarTarget>,
        settings: AppSettings
    ): AlertAssessment {
        val userLat = settings.pinnedLat
        val userLng = settings.pinnedLng
        val critRadius = settings.criticalRadiusKm
        val sectRadius = settings.sectorRadiusKm

        val criticalList = mutableListOf<RadarTarget>()
        val sectorList = mutableListOf<RadarTarget>()

        for (target in targets) {
            val dist = haversineKm(userLat, userLng, target.lat, target.lng)
            target.distanceKm = safeRound(dist, 1)

            val bearingToUser = calculateBearing(target.lat, target.lng, userLat, userLng)
            target.bearingToUser = safeRound(bearingToUser, 0)

            val course = target.courseDeg
            var isHeadingToUser = false
            var etaMin: Double? = null

            if (course != null) {
                val angleDiff = abs((course - bearingToUser + 180.0) % 360.0 - 180.0)
                // Strict heading threshold: within 20 degrees towards the observer
                isHeadingToUser = angleDiff <= 20.0

                val speedKmh = target.avgSpeedKmh ?: target.instantSpeedKmh ?: 180.0
                if (speedKmh > 20.0) {
                    val eta = (dist / speedKmh) * 60.0
                    etaMin = safeRound(eta, 0)
                }
            }

            target.isHeadingToUser = isHeadingToUser
            target.timeToUserMin = etaMin

            // Critical status strictly triggers ONLY if inside critical radius AND heading directly to user (<= 20°)
            val isCriticalIncoming = (dist <= critRadius) && isHeadingToUser
            target.isCriticalIncoming = isCriticalIncoming

            if (settings.criticalEnabled && isCriticalIncoming) {
                criticalList.add(target)
            } else if (settings.sectorEnabled && dist <= sectRadius) {
                sectorList.add(target)
            }
        }

        val summary = when {
            criticalList.isNotEmpty() -> {
                val closest = criticalList.minByOrNull { it.distanceKm ?: 999.0 }
                val etaText = closest?.timeToUserMin?.let { " (~${it.toInt()} хв)" } ?: ""
                "🚨 КУРС НА ВАС! ${closest?.category ?: "Ціль"} за ${closest?.distanceKm?.toInt() ?: 0} км$etaText"
            }
            sectorList.isNotEmpty() -> {
                val count = sectorList.size
                val closest = sectorList.minByOrNull { it.distanceKm ?: 999.0 }
                "🟡 У секторі: $count цілей (найближча ${closest?.distanceKm?.toInt() ?: 0} км)"
            }
            else -> "🟢 Небо чисте в радіусі моніторингу"
        }

        return AlertAssessment(
            isCritical = criticalList.isNotEmpty() && settings.criticalEnabled,
            isSector = sectorList.isNotEmpty() && settings.sectorEnabled,
            criticalTargets = criticalList,
            sectorTargets = sectorList,
            summaryText = summary
        )
    }
}
