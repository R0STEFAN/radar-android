package ua.quick.radar.service

import android.app.*
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ua.quick.radar.MainActivity
import ua.quick.radar.R
import ua.quick.radar.engine.*
import kotlin.math.*

class RadarForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val radarClient = RadarApiClient()
    private val uaAlarmClient = UkraineAlarmClient()
    private val gson = Gson()

    private var lastCriticalAlertTime = 0L
    private var lastSectorAlertTime = 0L
    private val notifiedCriticalTrackIds = mutableSetOf<String>()
    private val notifiedSectorTrackIds = mutableSetOf<String>()

    private var activeMediaPlayer: MediaPlayer? = null

    companion object {
        const val PREFS_NAME = "fluger_radar_prefs"
        const val CHANNEL_SERVICE_ID = "channel_service_v3"
        const val CHANNEL_CRITICAL_ID = "channel_critical_v3"
        const val CHANNEL_SECTOR_ID = "channel_sector_v3"

        const val NOTIF_SERVICE_ID = 1001
        const val NOTIF_CRITICAL_ID = 1002
        const val NOTIF_SECTOR_ID = 1003

        const val ACTION_RELOAD_SETTINGS = "ua.quick.radar.ACTION_RELOAD_SETTINGS"
        const val ACTION_STOP_SERVICE = "ua.quick.radar.ACTION_STOP_SERVICE"

        private val _latestTargetsFlow = MutableStateFlow<List<RadarTarget>>(emptyList())
        val latestTargetsFlow = _latestTargetsFlow.asStateFlow()

        private val _latestAssessmentFlow = MutableStateFlow<AlertAssessment?>(null)
        val latestAssessmentFlow = _latestAssessmentFlow.asStateFlow()

        var isRunning: Boolean = false
            private set

        private var instance: RadarForegroundService? = null

        fun triggerImmediateScan() {
            instance?.scanTriggerChannel?.trySend(Unit)
        }

        fun resetAllTracks() {
            instance?.radarClient?.resetTracks()
            instance?.uaAlarmClient?.resetTracks()
            _latestTargetsFlow.value = emptyList()
            triggerImmediateScan()
        }
    }

    val scanTriggerChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        acquireWakeLock()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start ongoing foreground service notification
        startForeground(NOTIF_SERVICE_ID, buildServiceNotification("Запуск моніторингу повітряного простору..."))

        // Launch polling loop
        startMonitoringLoop()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        serviceJob.cancel()
        releaseWakeLock()
        stopAudioPlayback()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlugerRadar:ServiceWakeLock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours cap
        } catch (e: Exception) {
            // Ignored if permission not granted
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    fun loadSettings(): AppSettings {
        return AppSettings(
            pinnedLat = prefs.getFloat("pinned_lat", 50.4501f).toDouble(),
            pinnedLng = prefs.getFloat("pinned_lng", 30.5234f).toDouble(),
            pinnedName = prefs.getString("pinned_name", "Київ") ?: "Київ",
            criticalEnabled = prefs.getBoolean("critical_enabled", true),
            criticalRadiusKm = prefs.getFloat("critical_radius_km", 40.0f).toDouble(),
            criticalSoundUri = prefs.getString("critical_sound_uri", "") ?: "",
            criticalVolume = prefs.getInt("critical_volume", 80),
            sectorEnabled = prefs.getBoolean("sector_enabled", true),
            sectorRadiusKm = prefs.getFloat("sector_radius_km", 100.0f).toDouble(),
            sectorSoundUri = prefs.getString("sector_sound_uri", "") ?: "",
            sectorVolume = prefs.getInt("sector_volume", 60),
            enableFluger = prefs.getBoolean("enable_fluger", true),
            enableUaAlarm = prefs.getBoolean("enable_ua_alarm", true),
            uaAllUkraine = prefs.getBoolean("ua_all_ukraine", false),
            hideScoutDrones = prefs.getBoolean("hide_scout_drones", false),
            serviceEnabled = prefs.getBoolean("service_enabled", true)
        )
    }

    private fun getSirenUri(): Uri {
        val custom = prefs.getString("critical_sound_uri", "") ?: ""
        return if (custom.isNotEmpty()) {
            Uri.parse(custom)
        } else {
            Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/${R.raw.fluger_siren}")
        }
    }

    private fun getBeepUri(): Uri {
        val custom = prefs.getString("sector_sound_uri", "") ?: ""
        return if (custom.isNotEmpty()) {
            Uri.parse(custom)
        } else {
            Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/${R.raw.fluger_beep}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // 1. Silent Service Channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_service_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 2. Critical Alert Channel (Siren + Strong Vibration)
            val critChannel = NotificationChannel(
                CHANNEL_CRITICAL_ID,
                getString(R.string.channel_critical_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_critical_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 200, 800, 200, 1200)
                setSound(getSirenUri(), audioAttr)
                enableLights(true)
                lightColor = Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(critChannel)

            // 3. Sector Awareness Channel (Sonar Beep + Short Vibration)
            val sectChannel = NotificationChannel(
                CHANNEL_SECTOR_ID,
                getString(R.string.channel_sector_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_sector_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                setSound(getBeepUri(), audioAttr)
                enableLights(true)
                lightColor = Color.YELLOW
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(sectChannel)
        }
    }

    private fun buildServiceNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpenIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val settings = loadSettings()
        val locText = "${settings.pinnedName} (${ThreatAnalyzer.safeRound(settings.pinnedLat, 4)}, ${ThreatAnalyzer.safeRound(settings.pinnedLng, 4)})"

        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("Флюгер Радар: Активний")
            .setContentText(statusText)
            .setSubText(locText)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setOngoing(true)
            .setContentIntent(pOpenIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            while (isActive) {
                val cycleStartTime = System.currentTimeMillis()
                try {
                    val settings = loadSettings()
                    if (!settings.serviceEnabled) {
                        delay(5000)
                        continue
                    }

                    // Direct fetching according to active sensors (Fluger & UkraineAlarm)
                    val flugerTargetsDef = async {
                        if (settings.enableFluger) {
                            radarClient.fetchFlugerTriangulatedTargets(settings.pinnedLat, settings.pinnedLng, settings.sectorRadiusKm)
                        } else emptyList()
                    }
                    val uaTargetsDef = async {
                        if (settings.enableUaAlarm) {
                            uaAlarmClient.fetchUaRadarTargets()
                        } else emptyList()
                    }

                    val flugerTargets = flugerTargetsDef.await()
                    val uaTargets = uaTargetsDef.await()

                    // Cross-sensor fusion
                    var fusedTargets = fuseTargets(flugerTargets, uaTargets, settings)

                    // Optional Scout UAV filtering
                    if (settings.hideScoutDrones) {
                        fusedTargets = fusedTargets.filterNot {
                            it.iconName == "ic_drone" || it.tag == "UAV" || (it.avgSpeedKmh != null && it.avgSpeedKmh < 135.0)
                        }
                    }

                    // Strict stationary / clutter suppression
                    fusedTargets = fusedTargets.filterNot {
                        it.tag == "STATIONARY" || (it.avgSpeedKmh != null && it.avgSpeedKmh < 45.0 && it.source == "fluger")
                    }

                    // Kinematics and threat assessment
                    val assessment = ThreatAnalyzer.assessThreats(fusedTargets, settings)

                    _latestTargetsFlow.value = fusedTargets
                    _latestAssessmentFlow.value = assessment

                    // Update ongoing notification
                    val notifText = buildStatusNotificationText(fusedTargets, assessment, settings)
                    notificationManager.notify(NOTIF_SERVICE_ID, buildServiceNotification(notifText))

                    // Evaluate and fire alarm notifications
                    handleAlerts(assessment, settings)

                } catch (e: Exception) {
                    // Ignore transient network hiccups and retry
                }

                // Drain any pending trigger signals accumulated during the active network scan
                while (scanTriggerChannel.tryReceive().isSuccess) {}

                // Maintain a precise 20-second cycle between sensor sweeps
                val elapsed = System.currentTimeMillis() - cycleStartTime
                val waitTimeMs = maxOf(2000L, 20_000L - elapsed)

                withTimeoutOrNull(waitTimeMs) {
                    scanTriggerChannel.receive()
                }
            }
        }
    }

    private fun fuseTargets(
        flugerList: List<RadarTarget>,
        uaList: List<RadarTarget>,
        settings: AppSettings
    ): List<RadarTarget> {
        val fused = mutableListOf<RadarTarget>()
        val matchedUa = mutableSetOf<String>()
        val matchedFluger = mutableSetOf<String>()

        data class FusionCandidate(
            val score: Double,
            val flugerTarget: RadarTarget,
            val uaTarget: RadarTarget,
            val distBetween: Double
        )

        val candidates = mutableListOf<FusionCandidate>()

        for (ft in flugerList) {
            for (ut in uaList) {
                val dBetween = ThreatAnalyzer.haversineKm(ft.lat, ft.lng, ut.lat, ut.lng)
                if (dBetween > 16.0) continue

                val ftCourse = ft.courseDeg
                val utCourse = ut.courseDeg

                var cDiff = 0.0
                var courseCompatible = true
                if (ftCourse != null && utCourse != null && (ut.path.size >= 2 || ut.courseDeg != null)) {
                    cDiff = abs((ftCourse - utCourse + 180.0) % 360.0 - 180.0)
                    if (cDiff > 70.0) {
                        courseCompatible = false
                    }
                }

                if (!courseCompatible) continue

                val score = dBetween * 1.0 + (cDiff / 15.0)
                candidates.add(FusionCandidate(score, ft, ut, dBetween))
            }
        }

        candidates.sortBy { it.score }

        for (cand in candidates) {
            val ft = cand.flugerTarget
            val ut = cand.uaTarget
            if (matchedFluger.contains(ft.trackId) || matchedUa.contains(ut.trackId)) {
                continue
            }
            matchedFluger.add(ft.trackId)
            matchedUa.add(ut.trackId)

            val fusedLat = ThreatAnalyzer.safeRound(ft.lat * 0.5 + ut.lat * 0.5, 5)
            val fusedLng = ThreatAnalyzer.safeRound(ft.lng * 0.5 + ut.lng * 0.5, 5)

            val fusedSpeed = ut.avgSpeedKmh ?: ft.avgSpeedKmh ?: ut.instantSpeedKmh ?: ft.instantSpeedKmh
            val speedText = if (fusedSpeed != null && fusedSpeed > 20) " (${fusedSpeed.toInt()} км/год)" else ""

            val combinedPath = if (ft.path.size >= ut.path.size && ft.path.isNotEmpty()) {
                val p = ft.path.toMutableList()
                p[p.size - 1] = listOf(fusedLat, fusedLng)
                p
            } else if (ut.path.isNotEmpty()) {
                val p = ut.path.toMutableList()
                p[p.size - 1] = listOf(fusedLat, fusedLng)
                p
            } else {
                listOf(listOf(fusedLat, fusedLng))
            }

            fused.add(
                RadarTarget(
                    trackId = ft.trackId,
                    lat = fusedLat,
                    lng = fusedLng,
                    courseDeg = ut.courseDeg ?: ft.courseDeg,
                    courseName = ut.courseName ?: ft.courseName,
                    category = ut.category,
                    sub = "${ut.sub} • Радар Флюгер$speedText",
                    iconName = ut.iconName,
                    tag = ut.tag,
                    echelon = if (ut.echelon != "Невідомо") ut.echelon else ft.echelon,
                    avgSpeedKmh = ft.avgSpeedKmh ?: ut.avgSpeedKmh,
                    instantSpeedKmh = ft.instantSpeedKmh ?: ut.instantSpeedKmh,
                    path = combinedPath,
                    count = max(ft.count, ut.count),
                    isFused = true,
                    source = "fused",
                    sourceLabel = "⚡ Флюгер + Мінцифра (Підтверджено)"
                )
            )
        }

        // Add remaining unmatched Fluger targets (filtered to monitored sector)
        for (ft in flugerList) {
            if (!matchedFluger.contains(ft.trackId)) {
                val dist = ThreatAnalyzer.haversineKm(settings.pinnedLat, settings.pinnedLng, ft.lat, ft.lng)
                if (dist <= settings.sectorRadiusKm * 1.15) {
                    fused.add(ft)
                }
            }
        }

        // Add remaining unmatched UkraineAlarm targets (filtered to monitored sector)
        for (ut in uaList) {
            if (!matchedUa.contains(ut.trackId)) {
                val dist = ThreatAnalyzer.haversineKm(settings.pinnedLat, settings.pinnedLng, ut.lat, ut.lng)
                if (!settings.uaAllUkraine && dist > settings.sectorRadiusKm * 1.15) {
                    continue
                }
                fused.add(ut)
            }
        }

        return fused
    }

    private fun buildStatusNotificationText(
        targets: List<RadarTarget>,
        assessment: AlertAssessment,
        settings: AppSettings
    ): String {
        return when {
            assessment.isCritical -> {
                val closest = assessment.criticalTargets.minByOrNull { it.distanceKm ?: 999.0 }
                "🚨 КУРС НА ВАС! ${closest?.category ?: "Ціль"} (${closest?.distanceKm?.toInt() ?: 0} км)"
            }
            assessment.isSector -> {
                "🟡 У секторі ${targets.size} цілей (${settings.sectorRadiusKm.toInt()} км)"
            }
            else -> "🟢 Сектор чистий. Відстеження активне."
        }
    }

    private fun handleAlerts(assessment: AlertAssessment, settings: AppSettings) {
        val now = System.currentTimeMillis()

        // 1. Critical Channel Alert
        if (assessment.isCritical && settings.criticalEnabled) {
            val critTargets = assessment.criticalTargets
            val newTrackFound = critTargets.any { !notifiedCriticalTrackIds.contains(it.trackId) }
            val cooldownPassed = (now - lastCriticalAlertTime) >= 30_000L

            if (newTrackFound || cooldownPassed) {
                lastCriticalAlertTime = now
                critTargets.forEach { notifiedCriticalTrackIds.add(it.trackId) }
                fireCriticalNotification(critTargets, settings)
            }
        } else if (!assessment.isCritical) {
            notificationManager.cancel(NOTIF_CRITICAL_ID)
            notifiedCriticalTrackIds.clear()
        }

        // 2. Sector Awareness Channel Alert (Yellow)
        if (assessment.isSector && !assessment.isCritical && settings.sectorEnabled) {
            val sectTargets = assessment.sectorTargets
            val currentSectorIds = sectTargets.map { it.trackId }.toSet()

            // Detect genuinely new targets that have not triggered an alert in the current sector presence
            val newTracks = sectTargets.filter { !notifiedSectorTrackIds.contains(it.trackId) }

            // Prune tracks that left the sector from history
            notifiedSectorTrackIds.retainAll(currentSectorIds)

            // Alert strictly ONLY when at least one NEW target appears in the sector
            if (newTracks.isNotEmpty()) {
                lastSectorAlertTime = now
                sectTargets.forEach { notifiedSectorTrackIds.add(it.trackId) }
                fireSectorNotification(sectTargets, newTracks, settings)
            }
        } else if (!assessment.isSector) {
            notificationManager.cancel(NOTIF_SECTOR_ID)
            notifiedSectorTrackIds.clear()
        }
    }

    private fun fireCriticalNotification(targets: List<RadarTarget>, settings: AppSettings) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpenIntent = PendingIntent.getActivity(
            this, 1, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closest = targets.minByOrNull { it.distanceKm ?: 999.0 }
        val distText = closest?.distanceKm?.let { "${it.toInt()} км" } ?: ""
        val etaText = closest?.timeToUserMin?.let { " (орієнтовно ${it.toInt()} хв)" } ?: ""
        val targetName = closest?.category ?: "Повітряна ціль"

        val title = "🚨 НЕБЕЗПЕКА: ЦІЛЬ НАБЛИЖАЄТЬСЯ ДО ВАС!"
        val body = "$targetName летить прямо у ваш сектор: $distText$etaText. Прямуйте в укриття!"

        val sirenUri = getSirenUri()
        playActiveSound(sirenUri, settings.criticalVolume)

        val notif = NotificationCompat.Builder(this, CHANNEL_CRITICAL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setColor(Color.RED)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pOpenIntent)
            .setSound(sirenUri)
            .setVibrate(longArrayOf(0, 800, 200, 800, 200, 1200))
            .build()

        notificationManager.notify(NOTIF_CRITICAL_ID, notif)
    }

    private fun fireSectorNotification(targets: List<RadarTarget>, newTargets: List<RadarTarget>, settings: AppSettings) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpenIntent = PendingIntent.getActivity(
            this, 2, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val count = targets.size
        val newTarget = newTargets.firstOrNull() ?: targets.minByOrNull { it.distanceKm ?: 999.0 }
        val targetName = newTarget?.category ?: "Ціль"
        val distText = newTarget?.distanceKm?.let { " (~${it.toInt()} км)" } ?: ""

        val title = if (newTargets.size == 1) {
            "🟡 Нова ціль у секторі: $targetName"
        } else {
            "🟡 Нові цілі у секторі (+${newTargets.size})"
        }

        val body = buildString {
            append("Виявлено: $targetName$distText.")
            if (count > 1) {
                append(" Усього в секторі: $count ${if (count in 2..4) "цілі" else "цілей"}.")
            }
        }

        val beepUri = getBeepUri()
        playActiveSound(beepUri, settings.sectorVolume)

        val notif = NotificationCompat.Builder(this, CHANNEL_SECTOR_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setColor(Color.YELLOW)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pOpenIntent)
            .setSound(beepUri)
            .setVibrate(longArrayOf(0, 250, 150, 250))
            .build()

        notificationManager.notify(NOTIF_SECTOR_ID, notif)
    }

    private fun playActiveSound(soundUri: Uri, volumePercent: Int) {
        val vol = (volumePercent.coerceIn(0, 100) / 100.0f)
        if (vol <= 0.01f) return
        try {
            stopAudioPlayback()
            activeMediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, soundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setVolume(vol, vol)
                setOnCompletionListener {
                    it.release()
                    if (activeMediaPlayer == it) activeMediaPlayer = null
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Channel sound will still ring via Android notification manager
        }
    }

    private fun stopAudioPlayback() {
        try {
            activeMediaPlayer?.stop()
            activeMediaPlayer?.release()
            activeMediaPlayer = null
        } catch (e: Exception) {
            // Ignored
        }
    }
}
