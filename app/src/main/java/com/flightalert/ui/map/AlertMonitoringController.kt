package com.flightalert.ui.map

import android.content.Context
import com.flightalert.service.AircraftAlertService

class AlertMonitoringController(private val context: Context) {
    fun apply(alerts_enabled: Boolean, priority_tracking_enabled: Boolean) {
        if (alerts_enabled || priority_tracking_enabled) {
            AircraftAlertService.start(context)
        } else {
            AircraftAlertService.stop(context)
        }
    }
}
