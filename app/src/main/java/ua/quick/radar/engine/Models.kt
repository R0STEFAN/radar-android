package ua.quick.radar.engine

import com.google.gson.annotations.SerializedName

data class RadarTarget(
    @SerializedName("trackId") val trackId: String,
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
    @SerializedName("courseDeg") val courseDeg: Double? = null,
    @SerializedName("courseName") val courseName: String? = null,
    @SerializedName("category") val category: String = "Повітряна ціль",
    @SerializedName("sub") val sub: String = "",
    @SerializedName("iconName") val iconName: String = "ic_air_threat_combined_white",
    @SerializedName("tag") val tag: String = "TARGET",
    @SerializedName("echelon") val echelon: String = "Невідомо",
    @SerializedName("avgSpeedKmh") val avgSpeedKmh: Double? = null,
    @SerializedName("instantSpeedKmh") val instantSpeedKmh: Double? = null,
    @SerializedName("distanceKm") var distanceKm: Double? = null,
    @SerializedName("bearingToUser") var bearingToUser: Double? = null,
    @SerializedName("isHeadingToUser") var isHeadingToUser: Boolean = false,
    @SerializedName("timeToUserMin") var timeToUserMin: Double? = null,
    @SerializedName("source") val source: String = "radar",
    @SerializedName("sourceLabel") val sourceLabel: String = "Радар",
    @SerializedName("path") val path: List<List<Double>> = emptyList(),
    @SerializedName("count") val count: Int = 1,
    @SerializedName("sampleCount") val sampleCount: Int = 1,
    @SerializedName("isFused") val isFused: Boolean = false,
    @SerializedName("isCriticalIncoming") var isCriticalIncoming: Boolean = false,
    @SerializedName("isStagnant") val isStagnant: Boolean = false,
    @SerializedName("isUnknownState") val isUnknownState: Boolean = false
)

data class ClassificationResult(
    val name: String,
    val sub: String,
    val icon: String,
    val iconName: String,
    val tag: String,
    val echelon: String
)

data class AlertAssessment(
    val isCritical: Boolean,
    val isSector: Boolean,
    val criticalTargets: List<RadarTarget>,
    val sectorTargets: List<RadarTarget>,
    val summaryText: String
)

data class AppSettings(
    var pinnedLat: Double = 50.4501,
    var pinnedLng: Double = 30.5234,
    var pinnedName: String = "Київ",
    var criticalEnabled: Boolean = true,
    var criticalRadiusKm: Double = 40.0,
    var criticalSoundUri: String = "",
    var criticalVolume: Int = 80,
    var sectorEnabled: Boolean = true,
    var sectorRadiusKm: Double = 100.0,
    var sectorSoundUri: String = "",
    var sectorVolume: Int = 60,
    var enableFluger: Boolean = true,
    var enableUaAlarm: Boolean = true,
    var uaAllUkraine: Boolean = false,
    var hideScoutDrones: Boolean = false,
    var serviceEnabled: Boolean = true
)

data class RadarPayload(
    val lat: Double,
    val lng: Double,
    val fcmToken: String = "quick_radar_fcm_token_local",
    val googleToken: String = "quick_radar_google_token_local",
    val background: Boolean = false
)

data class UaMovementItem(
    @SerializedName("trackId") val trackId: String?,
    @SerializedName("x") val x: Double,
    @SerializedName("y") val y: Double,
    @SerializedName("heading") val heading: Double?,
    @SerializedName("formular") val formular: String?,
    @SerializedName("detectedType") val detectedType: String?,
    @SerializedName("iconName") val iconName: String?,
    @SerializedName("currentCount") val currentCount: Int?
)

data class UaMovementWrapper(
    @SerializedName("from") val from: List<UaMovementItem>?
)

data class UaTargetsResponse(
    @SerializedName("movement") val movement: UaMovementWrapper?
)

data class UaAlertItem(
    @SerializedName("regionId") val regionId: String,
    @SerializedName("type") val type: String?,
    @SerializedName("lastUpdate") val lastUpdate: String?
)

data class UaMapUpdateResponse(
    @SerializedName("alerts") val alerts: List<UaAlertItem>?
)
