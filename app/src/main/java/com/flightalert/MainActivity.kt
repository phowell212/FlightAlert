package com.flightalert

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.flightalert.data.web.GlobeWebAircraftSource
import com.flightalert.service.AircraftAlertService
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.ui.map.FlightMapView

// Activity stays intentionally thin: permissions, lifecycle, and the single custom map surface.
class MainActivity : ComponentActivity() {
    private var flight_map_view: FlightMapView? = null
    private var globe_web_aircraft_source: GlobeWebAircraftSource? = null

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

        // Make the hidden globe source first. It is a real aircraft source, not a visible app screen.
        val globe_source = GlobeWebAircraftSource(this, APP_USER_AGENT)
        globe_source.set_enabled(
            FlightAlertSettings.read_aircraft_feed_mode(this).uses_globe
        )
        globe_web_aircraft_source = globe_source

        // Make the actual cockpit view. This is the main logic file Android will call to draw and handle input.
        val view = FlightMapView(this, globe_source)
        view.keepScreenOn = true
        flight_map_view = view

        // Stack the invisible web source underneath the canvas map, then give that stack to Android.
        val root = FrameLayout(this).apply {
            addView(
                globe_source.web_view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)

        // Put keyboard focus on FlightMapView so emulator keys and filter typing land in the map controller.
        view.requestFocus()
        request_location_permission_if_needed()
        request_notification_permission_if_needed()
    }

    // Android calls this whenever the app comes back on screen; restart live systems here.
    override fun onResume() {
        super.onResume()
        configure_system_bars()
        request_location_permission_if_needed()
        globe_web_aircraft_source?.start()
        flight_map_view?.start()
        update_alert_service()
    }

    // Android calls this when the app is leaving the foreground; stop screen-only work here.
    override fun onPause() {
        flight_map_view?.stop()
        globe_web_aircraft_source?.stop()
        super.onPause()
    }

    // Android is tearing down the activity; destroy the WebView source so it does not keep a process alive.
    override fun onDestroy() {
        globe_web_aircraft_source?.destroy()
        globe_web_aircraft_source = null
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

    private companion object {
        const val APP_USER_AGENT = "FlightAlertPrototype/0.1"
    }
}
