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

    fun bearingToCompass(deg: Double): String {
        val directions = arrayOf("Пн", "Пн-Сх", "Сх", "Пд-Сх", "Пд", "Пд-Зх", "Зх", "Пн-Зх")
        val index = round(((deg % 360.0) / 45.0)).toInt() % 8
        return directions[index]
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
                // Heading toward user if track direction aligns within 45 degrees of bearing to user
                isHeadingToUser = angleDiff <= 45.0

                val speedKmh = target.avgSpeedKmh ?: target.instantSpeedKmh ?: 180.0
                if (speedKmh > 20.0) {
                    val eta = (dist / speedKmh) * 60.0
                    etaMin = safeRound(eta, 0)
                }
            }

            target.isHeadingToUser = isHeadingToUser
            target.timeToUserMin = etaMin

            // 1. Critical Channel:
            // Threat within critical radius AND heading towards user (or very close <= 12 km regardless of heading)
            val isHighThreatType = target.iconName in listOf("ic_shahed", "ic_shahed_jet", "ic_airbomb", "ic_missile", "ic_ballistic")
            val isImminentProximity = dist <= 12.0

            if (settings.criticalEnabled && dist <= critRadius && (isHeadingToUser || isImminentProximity)) {
                criticalList.add(target)
            } else if (settings.sectorEnabled && dist <= sectRadius) {
                // 2. Sector Awareness Channel:
                // Within observation sector, but not meeting immediate critical alarm criteria
                sectorList.add(target)
            }
        }

        // Build brief summary string for UI or notification
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
