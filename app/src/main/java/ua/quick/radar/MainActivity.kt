package ua.quick.radar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ua.quick.radar.bridge.WebAppInterface
import ua.quick.radar.engine.ThreatAnalyzer
import ua.quick.radar.service.RadarForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingSoundPickerType: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val notifGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        if (!notifGranted) {
            Toast.makeText(this, "⚠️ Дозвіл на сповіщення необхідний для своєчасного попередження!", Toast.LENGTH_LONG).show()
        }
        startRadarService()
    }

    private val soundPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                ?: result.data?.data
            if (uri != null) {
                val prefs = getSharedPreferences(RadarForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
                val key = if (pendingSoundPickerType == "critical") "critical_sound_uri" else "sector_sound_uri"
                prefs.edit().putString(key, uri.toString()).apply()

                val name = if (pendingSoundPickerType == "critical") "Критичний сигнал" else "Сигнал сектора"
                Toast.makeText(this, "✅ $name оновлено!", Toast.LENGTH_SHORT).show()

                webView.post {
                    webView.evaluateJavascript("window.onSoundUpdated && window.onSoundUpdated('$pendingSoundPickerType', '${uri}')", null)
                }
            }
        }
        pendingSoundPickerType = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        setupWebView()

        checkAndRequestPermissions()
        startRadarService()
        observeRadarFlow()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(0xFF0B0F19.toInt())
        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.allowFileAccess = true
        ws.allowContentAccess = true
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.databaseEnabled = true
        ws.displayZoomControls = false
        ws.builtInZoomControls = false

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript("window.onRadarDataUpdated && window.onRadarDataUpdated()", null)
            }
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun startRadarService() {
        val serviceIntent = Intent(this, RadarForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun observeRadarFlow() {
        lifecycleScope.launch {
            RadarForegroundService.latestTargetsFlow.collectLatest {
                webView.post {
                    webView.evaluateJavascript("window.onRadarDataUpdated && window.onRadarDataUpdated()", null)
                }
            }
        }
    }

    fun launchSoundPicker(type: String) {
        pendingSoundPickerType = type
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, if (type == "critical") "Оберіть критичний сигнал" else "Оберіть сигнал сектора")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        }
        soundPickerLauncher.launch(intent)
    }

    fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Відкрийте налаштування батареї та вимкніть оптимізацію для програми", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Оптимізацію батареї вже вимкнено. Фоновий режим працюватиме 24/7.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun determineGpsLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var bestLoc: Location? = null

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null && (bestLoc == null || loc.accuracy < bestLoc.accuracy)) {
                    bestLoc = loc
                }
            } catch (e: SecurityException) {
                // Ignore
            }
        }

        if (bestLoc != null) {
            handleGpsCoordinates(bestLoc.latitude, bestLoc.longitude)
        } else {
            // Request single update
            try {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        handleGpsCoordinates(location.latitude, location.longitude)
                        lm.removeUpdates(this)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null)
                Toast.makeText(this, "Отримання GPS сигналу...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Не вдалося отримати GPS: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleGpsCoordinates(lat: Double, lng: Double) {
        // Anti-EW & Anti-Spoofing Defense:
        if (!ThreatAnalyzer.isInsideUkraine(lat, lng)) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "⚠️ ВИЯВЛЕНО ПІДМІНУ GPS (РЕБ): Координати ($lat, $lng) поза Україною (Ліма/аеропорт)! Позицію залишено без змін.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val prefs = getSharedPreferences(RadarForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("pinned_lat", lat.toFloat())
            .putFloat("pinned_lng", lng.toFloat())
            .putString("pinned_name", "GPS Позиція")
            .apply()

        runOnUiThread {
            Toast.makeText(this, "📍 Позицію зафіксовано по GPS: ${ThreatAnalyzer.safeRound(lat, 4)}, ${ThreatAnalyzer.safeRound(lng, 4)}", Toast.LENGTH_SHORT).show()
            webView.evaluateJavascript("window.onGpsLocationUpdated && window.onGpsLocationUpdated($lat, $lng)", null)
        }
    }
}
