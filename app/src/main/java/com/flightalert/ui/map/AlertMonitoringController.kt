package com.flightalert.ui.map

import android.content.Context
import com.flightalert.service.AlertAircraft
import com.flightalert.service.AircraftAlertService

class AlertMonitoringController(private val context: Context) {
    fun apply(alerts_enabled: Boolean, priority_tracking_enabled: Boolean) {
        if (alerts_enabled || priority_tracking_enabled) {
            AircraftAlertService.start(context)
        } else {
            AircraftAlertService.stop(context)
        }
    }

    fun publish_priority_snapshot(priority_aircraft: List<AlertAircraft>) {
        AircraftAlertService.publish_priority_snapshot(context, priority_aircraft)
    }
}
