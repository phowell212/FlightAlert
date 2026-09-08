@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.flightalert.sources.GlobeBinCraftAircraftSource
import kotlin.math.abs


class MainActivity : ComponentActivity() {
    private var flight_map_view: FlightMapView? = null
    private var globe_bin_craft_aircraft_source: GlobeBinCraftAircraftSource? = null


    private val location_permission_launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        flight_map_view?.set_location_permission_granted(has_flight_location_permission())
        update_alert_service()
    }
    private val notification_permission_launcher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        update_alert_service()
        if (granted) ReferenceDictionaryDownloadService.start_if_missing(this)
    }


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


        val globe_source = GlobeBinCraftAircraftSource(APP_USER_AGENT)
        globe_source.set_enabled(
            FlightAlertSettings.read_aircraft_feed_mode(this).uses_globe
        )
        globe_bin_craft_aircraft_source = globe_source


        val view = FlightMapView(this, globe_source)
        view.keepScreenOn = true
        configure_map_frame_rate(view)
        flight_map_view = view

        setContentView(view)
        view.post { configure_map_frame_rate(view) }

        // Put keyboard focus on FlightMapView so emulator keys and filter typing land in the map controller.
        view.requestFocus()
        request_location_permission_if_needed()
        request_notification_permission_if_needed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configure_system_bars()
        flight_map_view?.let { view ->
            configure_map_frame_rate(view)
            view.requestLayout()
            view.postInvalidateOnAnimation()
        }
    }


    override fun onResume() {
        super.onResume()
        configure_system_bars()
        request_location_permission_if_needed()
        flight_map_view?.let { configure_map_frame_rate(it) }
        globe_bin_craft_aircraft_source?.start()
        flight_map_view?.start()
        update_alert_service()
        if (has_notification_permission()) {
            ReferenceDictionaryDownloadService.start_if_missing(this)
        }
    }


    override fun onPause() {
        flight_map_view?.stop()
        globe_bin_craft_aircraft_source?.stop()
        super.onPause()
    }

    // Destroy FlightMapView before its globe source.
    override fun onDestroy() {
        flight_map_view?.destroy()
        flight_map_view = null
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
    private fun configure_map_frame_rate(view: FlightMapView? = null) {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        } ?: return
        val current_mode = display.mode
        // Favor a sustainable frame budget without changing the display's resolution.
        val steady_mode = display.supportedModes
            .filter {
                it.physicalWidth == current_mode.physicalWidth &&
                    it.physicalHeight == current_mode.physicalHeight && it.refreshRate > 0f
            }
            .minByOrNull { abs(it.refreshRate - 60f) } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = steady_mode.modeId
            preferredRefreshRate = steady_mode.refreshRate
        }
        if (Build.VERSION.SDK_INT >= 35) {
            view?.requestedFrameRate = 60f
        }
    }

    private fun request_location_permission_if_needed() {
        val granted = has_flight_location_permission()
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

    private fun request_notification_permission_if_needed() {
        if (!has_notification_permission()) {
            notification_permission_launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ReferenceDictionaryDownloadService.start_if_missing(this)
        }
    }

    private fun has_notification_permission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED


    private fun update_alert_service() {
        if (monitoring_requested() && has_flight_location_permission()) {
            AircraftAlertService.start(this)
        } else {
            AircraftAlertService.stop(this)
        }
    }

    private fun monitoring_requested(): Boolean {
        val prefs = FlightAlertSettings.prefs(this)
        return prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true) ||
                prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
    }

    private companion object {
        const val APP_USER_AGENT = FlightAlertAppSettings.App.USER_AGENT
    }
}
