package com.flightalert

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.flightalert.service.AircraftAlertService
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.ui.map.FlightMapView

// Activity stays intentionally thin: permissions, lifecycle, and the single custom map surface.
class MainActivity : ComponentActivity() {
    private var flightMapView: FlightMapView? = null
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        flightMapView?.setLocationPermissionGranted(hasLocationPermission())
        updateAlertService()
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateAlertService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (flightMapView?.handleBackPress() == true) return
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )

        val view = FlightMapView(this)
        view.keepScreenOn = true
        flightMapView = view
        setContentView(view)
        requestLocationPermissionIfNeeded()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        requestLocationPermissionIfNeeded()
        flightMapView?.start()
        updateAlertService()
    }

    override fun onPause() {
        flightMapView?.stop()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        // The map view handles insets; opaque bars keep Android chrome from blending into app UI.
        val systemBarColor = Color.rgb(13, 29, 25)
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    private fun requestLocationPermissionIfNeeded() {
        val granted = hasLocationPermission()
        flightMapView?.setLocationPermissionGranted(granted)
        if (!granted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateAlertService() {
        val prefs = FlightAlertSettings.prefs(this)
        val enabled = prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true) ||
            prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
        if (enabled && hasLocationPermission()) {
            AircraftAlertService.start(this)
        } else {
            AircraftAlertService.stop(this)
        }
    }
}
