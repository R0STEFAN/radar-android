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
    val isAlertZone: Boolean = false,
    val isSector: Boolean,
    val criticalTargets: List<RadarTarget>,
    val alertZoneTargets: List<RadarTarget> = emptyList(),
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
    var sectorRadiusKm: Double = 45.0,
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
    val fcmToken: String,
    val googleToken: String = "",
    val background: Boolean = false
)

data class UaPrediction(
    @SerializedName("fromX") val fromX: Double = 0.0,
    @SerializedName("fromY") val fromY: Double = 0.0,
    @SerializedName("toX") val toX: Double = 0.0,
    @SerializedName("toY") val toY: Double = 0.0,
    @SerializedName("startedAt") val startedAt: Long? = null,
    @SerializedName("endsAt") val endsAt: Long? = null,
    @SerializedName("fromMarkerOffsetX") val fromMarkerOffsetX: Double? = null,
    @SerializedName("fromMarkerOffsetY") val fromMarkerOffsetY: Double? = null,
    @SerializedName("toMarkerOffsetX") val toMarkerOffsetX: Double? = null,
    @SerializedName("toMarkerOffsetY") val toMarkerOffsetY: Double? = null
)

data class UaMovementItem(
    @SerializedName("trackId") val trackId: String?,
    @SerializedName("x") val x: Double = 0.0,
    @SerializedName("y") val y: Double = 0.0,
    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lng") val lng: Double? = null,
    @SerializedName("markerOffsetX") val markerOffsetX: Double? = 0.0,
    @SerializedName("markerOffsetY") val markerOffsetY: Double? = 0.0,
    @SerializedName("heading") val heading: Double? = null,
    @SerializedName("formular") val formular: String? = null,
    @SerializedName("detectedType") val detectedType: String? = null,
    @SerializedName("iconName") val iconName: String? = null,
    @SerializedName("iconScale") val iconScale: Double? = 1.0,
    @SerializedName("currentCount") val currentCount: Int? = 1,
    @SerializedName("movementDurationMs") val movementDurationMs: Long? = 20000L,
    @SerializedName("prediction") val prediction: UaPrediction? = null
)

data class UaMovementWrapper(
    @SerializedName("from") val from: List<UaMovementItem>? = null,
    @SerializedName("fromUpdatedAt") val fromUpdatedAt: Long? = null,
    @SerializedName("startedAt") val startedAt: Long? = null
)

data class UaTargetsResponse(
    @SerializedName("publicTargetsEnabled") val publicTargetsEnabled: Boolean? = true,
    @SerializedName("targets") val targets: List<UaMovementItem>? = null,
    @SerializedName("movement") val movement: UaMovementWrapper? = null,
    @SerializedName("serverTime") val serverTime: Long? = null,
    @SerializedName("updatedAt") val updatedAt: Long? = null
)

data class UaAlertItem(
    @SerializedName("regionId") val regionId: String,
    @SerializedName("type") val type: String?,
    @SerializedName("lastUpdate") val lastUpdate: String?
)

data class UaMapUpdateResponse(
    @SerializedName("alerts") val alerts: List<UaAlertItem>?
)
