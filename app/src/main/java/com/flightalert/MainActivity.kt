package com.flightalert

import android.Manifest
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.flightalert.data.web.GlobeBinCraftAircraftSource
import com.flightalert.service.AircraftAlertService
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.ui.map.FlightMapView
import com.flightalert.ui.map.TileSource

// Activity stays intentionally thin: permissions, lifecycle, and the single custom map surface.
class MainActivity : ComponentActivity() {
    private var flight_map_view: FlightMapView? = null
    private var globe_bin_craft_aircraft_source: GlobeBinCraftAircraftSource? = null

    // Let Android show the permission popups, then hand the answer back to the map and alert service.
    private val location_permission_launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        flight_map_view?.set_location_permission_granted(has_location_permission())
        update_alert_service()
    }
    private val notification_permission_launcher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        update_alert_service()
    }

    // Android calls this once to build the screen; after this, the custom view owns the app flow.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configure_system_bars()

        // Give overlays first chance at Back so panels close before Android leaves the app.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (flight_map_view?.handle_back_press() == true) return
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )

        // Make the globe source first. It uses lightweight binCraft HTTP for live wide-area inventory.
        val globe_source = GlobeBinCraftAircraftSource(APP_USER_AGENT)
        globe_source.set_enabled(
            FlightAlertSettings.read_aircraft_feed_mode(this).uses_globe
        )
        globe_bin_craft_aircraft_source = globe_source

        // Make the actual cockpit view. This is the main logic file Android will call to draw and handle input.
        val view = FlightMapView(this, globe_source)
        view.keepScreenOn = true
        configure_high_refresh_rate(view)
        flight_map_view = view
        apply_debug_perf_viewport(intent)

        setContentView(view)
        view.post { configure_high_refresh_rate(view) }

        // Put keyboard focus on FlightMapView so emulator keys and filter typing land in the map controller.
        view.requestFocus()
        request_location_permission_if_needed()
        request_notification_permission_if_needed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        apply_debug_perf_viewport(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configure_system_bars()
        flight_map_view?.let { view ->
            view.requestLayout()
            view.postInvalidateOnAnimation()
        }
    }

    // Android calls this whenever the app comes back on screen; restart live systems here.
    override fun onResume() {
        super.onResume()
        configure_system_bars()
        request_location_permission_if_needed()
        flight_map_view?.let { configure_high_refresh_rate(it) }
        globe_bin_craft_aircraft_source?.start()
        flight_map_view?.start()
        update_alert_service()
    }

    // Android calls this when the app is leaving the foreground; stop screen-only work here.
    override fun onPause() {
        flight_map_view?.stop()
        globe_bin_craft_aircraft_source?.stop()
        super.onPause()
    }

    // Android is tearing down the activity; stop the binCraft source worker.
    override fun onDestroy() {
        globe_bin_craft_aircraft_source?.destroy()
        globe_bin_craft_aircraft_source = null
        super.onDestroy()
    }

    // The map view handles insets; opaque bars keep Android chrome from blending into app UI.
    @Suppress("DEPRECATION")
    private fun configure_system_bars() {
        val system_bar_color = FlightAlertSettings.read_visual_theme(this).colors.system_bar
        window.statusBarColor = system_bar_color
        window.navigationBarColor = system_bar_color
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    @Suppress("DEPRECATION")
    private fun configure_high_refresh_rate(view: FlightMapView? = null) {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        } ?: return
        val fastest_mode = display.supportedModes.maxByOrNull { it.refreshRate } ?: return
        if (fastest_mode.refreshRate <= 0f) return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = fastest_mode.modeId
            preferredRefreshRate = fastest_mode.refreshRate
        }
        if (Build.VERSION.SDK_INT >= 35) {
            view?.setRequestedFrameRate(fastest_mode.refreshRate)
        }
    }

    private fun request_location_permission_if_needed() {
        val granted = has_location_permission()
        flight_map_view?.set_location_permission_granted(granted)
        if (!granted) {
            location_permission_launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun has_location_permission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun request_notification_permission_if_needed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notification_permission_launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Keep the background watcher alive only when alerts need it and Android has granted location.
    private fun update_alert_service() {
        val prefs = FlightAlertSettings.prefs(this)
        val enabled = prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true) ||
            prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
        if (enabled && has_location_permission()) {
            AircraftAlertService.start(this)
        } else {
            AircraftAlertService.stop(this)
        }
    }

    private fun apply_debug_perf_viewport(intent: Intent?) {
        if (!is_debuggable_build() || intent == null) return
        intent.extras?.keySet()
            ?.filter { it.contains("PERF") }
            ?.takeIf { it.isNotEmpty() }
            ?.let { keys -> Log.i(TAG, "Debug perf intent keys=${keys.joinToString()}") }
        if (!intent.hasExtra(EXTRA_PERF_LAT) || !intent.hasExtra(EXTRA_PERF_LON)) return
        val lat = intent.double_extra(EXTRA_PERF_LAT, Double.NaN)
        val lon = intent.double_extra(EXTRA_PERF_LON, Double.NaN)
        val zoom = intent.double_extra(EXTRA_PERF_ZOOM, DEFAULT_PERF_ZOOM)
        val run_id = intent.getStringExtra(EXTRA_PERF_RUN_ID)
        val map_source = intent.getStringExtra(EXTRA_PERF_MAP_SOURCE)
            ?.let { value -> TileSource.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
        val restricted = intent.optional_boolean_extra(EXTRA_PERF_RESTRICTED_AIRSPACES_ENABLED)
        val clear_selection = intent.optional_boolean_extra(EXTRA_PERF_CLEAR_SELECTION) == true
        val skip_map = intent.optional_boolean_extra(EXTRA_PERF_SKIP_MAP) == true
        val skip_traffic = intent.optional_boolean_extra(EXTRA_PERF_SKIP_TRAFFIC) == true
        val skip_chrome = intent.optional_boolean_extra(EXTRA_PERF_SKIP_CHROME) == true
        flight_map_view?.apply_debug_perf_viewport(
            lat = lat,
            lon = lon,
            target_zoom = zoom,
            run_id = run_id,
            perf_map_source = map_source,
            perf_restricted_airspaces_enabled = restricted,
            perf_clear_selection = clear_selection,
            perf_skip_map = skip_map,
            perf_skip_traffic = skip_traffic,
            perf_skip_chrome = skip_chrome
        )
    }

    private fun Intent.double_extra(name: String, fallback: Double): Double {
        getStringExtra(name)?.toDoubleOrNull()?.let { return it }
        return try {
            getDoubleExtra(name, fallback)
        } catch (_: ClassCastException) {
            fallback
        }
    }

    private fun Intent.optional_boolean_extra(name: String): Boolean? {
        if (!hasExtra(name)) return null
        getStringExtra(name)?.let { value ->
            return value.equals("true", ignoreCase = true) || value == "1"
        }
        return try {
            getBooleanExtra(name, false)
        } catch (_: ClassCastException) {
            null
        }
    }

    private fun is_debuggable_build(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private companion object {
        const val APP_USER_AGENT = "FlightAlertPrototype/0.1"
        const val TAG = "FlightAlert"
        const val EXTRA_PERF_LAT = "com.flightalert.PERF_LAT"
        const val EXTRA_PERF_LON = "com.flightalert.PERF_LON"
        const val EXTRA_PERF_ZOOM = "com.flightalert.PERF_ZOOM"
        const val EXTRA_PERF_RUN_ID = "com.flightalert.PERF_RUN_ID"
        const val EXTRA_PERF_MAP_SOURCE = "com.flightalert.PERF_MAP_SOURCE"
        const val EXTRA_PERF_RESTRICTED_AIRSPACES_ENABLED = "com.flightalert.PERF_RESTRICTED_AIRSPACES_ENABLED"
        const val EXTRA_PERF_CLEAR_SELECTION = "com.flightalert.PERF_CLEAR_SELECTION"
        const val EXTRA_PERF_SKIP_MAP = "com.flightalert.PERF_SKIP_MAP"
        const val EXTRA_PERF_SKIP_TRAFFIC = "com.flightalert.PERF_SKIP_TRAFFIC"
        const val EXTRA_PERF_SKIP_CHROME = "com.flightalert.PERF_SKIP_CHROME"
        const val DEFAULT_PERF_ZOOM = 4.2
    }
}
