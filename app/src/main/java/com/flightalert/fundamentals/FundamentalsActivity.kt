package com.flightalert.fundamentals

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.flightalert.VisualTheme
import com.flightalert.FlightAlertSettings
import com.flightalert.AircraftAlertService
import com.flightalert.alerts.MonitoringNotificationHider
import kotlin.math.abs

/** Flight Alert's rebuilt launcher, using the shared monitoring and dictionary services. */
class FundamentalsActivity : ComponentActivity() {
    private lateinit var map: FundamentalsMapView
    private lateinit var locations: LocationManager
    private var resumed = false
    private var askedForLocation = false
    private var askedForNotifications = false
    private var settingsDialog: AlertDialog? = null
    private lateinit var dictionary: FundamentalsDictionary
    private val openedElapsedMs = SystemClock.elapsedRealtime()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { map.setLocation(location) }
        override fun onProviderDisabled(provider: String) { updateLocationStatus() }
        override fun onProviderEnabled(provider: String) { updateLocationStatus() }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (resumed) { startLocation(); updateAlertService() }
        if (!hasNotificationPermission() && !askedForNotifications) requestNotifications()
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (resumed) { dictionary.start(); updateAlertService() }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askedForLocation = savedInstanceState?.getBoolean("askedForLocation") ?: false
        askedForNotifications = savedInstanceState?.getBoolean("askedForNotifications") ?: false
        locations = getSystemService(LocationManager::class.java)
        bordersEnabled = savedInstanceState?.getBoolean("borders", true) ?: true
        labelsEnabled = savedInstanceState?.getBoolean("labels", true) ?: true
        val prefs = FlightAlertSettings.prefs(this)
        if (!prefs.contains(FlightAlertSettings.KEY_ALERTS_ENABLED)) {
            prefs.edit().putBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, false).apply()
        }
        map = FundamentalsMapView(this) { if (resumed) updateAlertService() }.apply { keepScreenOn = true }
        bordersEnabled = savedInstanceState?.getBoolean("borders") ?: map.referenceFilters().outlines_master_enabled
        labelsEnabled = savedInstanceState?.getBoolean("labels") ?: map.referenceFilters().labels_master_enabled
        dictionary = FundamentalsDictionary(this,
            { map.setDownloadStatus(dictionary.status()) }, map::dictionaryInstalled)
        installChrome()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!map.handleBack()) finish()
            }
        })
        requestSixtyHz()
        if (!hasLocationPermission() && !askedForLocation) requestLocation()
        else if (!hasNotificationPermission() && !askedForNotifications) requestNotifications()
    }

    @Suppress("DEPRECATION")
    private fun installChrome() {
        (map.parent as? ViewGroup)?.removeView(map)
        val chrome = FundamentalsChrome(this, map, onRecenter = ::enableLocationOrRecenter,
            onSettings = ::showSettings, onMap = { showMapControls(aircraftTab = true) })
        map.onReadout = chrome::show
        ViewCompat.setOnApplyWindowInsetsListener(chrome) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        map.setReferenceLayers(bordersEnabled, labelsEnabled)
        setContentView(chrome)
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = map.theme().colors.system_bar
            window.navigationBarColor = map.theme().colors.system_bar
        }
        WindowCompat.getInsetsController(window, chrome).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        chrome.requestApplyInsets()
        map.refreshReadout()
    }

    private fun showMapControls(aircraftTab: Boolean = false) {
        showFundamentalsMapControls(this, map, aircraftTab) { borders, labels ->
            bordersEnabled = borders; labelsEnabled = labels
            map.setReferenceLayers(borders, labels)
        }
    }

    private fun showSettings() {
        settingsDialog?.dismiss()
        settingsDialog = showFundamentalsSettings(this, map.theme(), map.units(), map.alerts,
            onTheme = { map.setTheme(it); installChrome() }, onUnits = map::setUnits,
            onMap = { showMapControls() },
            onNotifications = ::requestNotifications,
            onNotificationAccess = { startActivity(MonitoringNotificationHider.settings_intent()) },
            onDictionary = { dictionary.showControls(::requestNotifications) },
            onSelectAircraft = map::selectQueuedAircraft, appOpenedElapsedMs = openedElapsedMs)
    }

    @Suppress("DEPRECATION")
    private fun requestSixtyHz() {
        val display = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        display?.supportedModes?.filter {
            it.physicalWidth == display.mode.physicalWidth && it.physicalHeight == display.mode.physicalHeight
        }?.minByOrNull { abs(it.refreshRate - 60f) }?.let {
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = it.modeId
                preferredRefreshRate = 60f
            }
        }
        if (Build.VERSION.SDK_INT >= 35) map.requestedFrameRate = 60f
    }

    private var bordersEnabled = true
    private var labelsEnabled = true

    override fun onResume() {
        super.onResume()
        resumed = true
        requestSixtyHz()
        map.start()
        startLocation()
        dictionary.start()
        MonitoringNotificationHider.request_rebind_if_enabled(this)
        updateAlertService()
    }

    override fun onPause() {
        resumed = false
        locations.removeUpdates(listener)
        map.stop()
        dictionary.stop()
        super.onPause()
    }

    override fun onDestroy() {
        settingsDialog?.dismiss()
        dictionary.close()
        map.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestSixtyHz()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("askedForLocation", askedForLocation)
        outState.putBoolean("askedForNotifications", askedForNotifications)
        outState.putBoolean("borders", bordersEnabled)
        outState.putBoolean("labels", labelsEnabled)
        super.onSaveInstanceState(outState)
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this,
        Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        askedForLocation = true
        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun hasNotificationPermission() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotifications() {
        if (!hasNotificationPermission() && (!askedForNotifications || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            askedForNotifications = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun updateAlertService() {
        val settings = map.alerts.settings()
        val requested = settings.alertsEnabled || settings.priorityEnabled
        if (requested && hasLocationPermission() && locations.isLocationEnabled) {
            try {
                AircraftAlertService.start(this)
                val notificationsEnabled = hasNotificationPermission() && getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                map.setMonitoringMessage(if (notificationsEnabled) "Background monitoring requested"
                    else "Monitoring requested · Notifications disabled in Android")
            } catch (_: RuntimeException) {
                map.setMonitoringMessage("Background monitoring could not start · Check Android permissions")
            }
        } else {
            AircraftAlertService.stop(this)
            map.setMonitoringMessage(when {
                !requested -> "Background monitoring off"
                !hasLocationPermission() -> "Monitoring needs location permission"
                else -> "Monitoring needs Location turned on in Android"
            })
        }
    }

    private fun enableLocationOrRecenter() {
        when {
            !hasLocationPermission() -> {
                if (askedForLocation && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                } else requestLocation()
            }
            !locations.isLocationEnabled -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> { startLocation(); map.recenter() }
        }
    }

    private fun updateLocationStatus() {
        map.setLocationAvailable(hasLocationPermission() && locations.isLocationEnabled)
        map.setLocationMessage(if (locations.isLocationEnabled) "Waiting for location fix" else "Location off · Tap My location")
    }

    private fun startLocation() {
        if (!hasLocationPermission()) {
            map.setLocationAvailable(false)
            map.setLocationMessage("Location denied · Tap My location")
            return
        }
        locations.removeUpdates(listener)
        updateLocationStatus()
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { locations.allProviders.contains(it) && locations.isProviderEnabled(it) }
            providers.mapNotNull { locations.getLastKnownLocation(it) }
                .filter { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) < 120_000_000_000L }
                .maxByOrNull { it.elapsedRealtimeNanos }?.let(map::setLocation)
            providers.forEach { locations.requestLocationUpdates(it, 1000L, 0f, listener) }
        } catch (_: SecurityException) {
            map.setLocationMessage("Location permission changed · Tap My location")
        }
    }
}
