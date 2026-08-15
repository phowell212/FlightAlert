@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
)

package com.flightalert

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.Manifest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.flightalert.aircraft.AlertAircraft
import com.flightalert.aircraft.AlertAircraftClassifier
import com.flightalert.aircraft.AlertPositionProjector
import com.flightalert.aircraft.ProjectedAlertPosition
import com.flightalert.alerts.MonitoringNotificationHider
import com.flightalert.alerts.PrioritySnapshotPayload
import com.flightalert.map.Bounds
import com.flightalert.map.bounds_around_location
import com.flightalert.sources.AircraftFeedClient
import com.flightalert.sources.FeedAircraft
import com.flightalert.sources.FeedBounds
import com.flightalert.sources.FeedStatus



object PriorityNotificationPresenter {
    fun extreme_priority_aircraft(priority_aircraft: List<AlertAircraft>): List<AlertAircraft> {
        return priority_aircraft.filter { it.is_extreme_priority }
    }

    fun notification_body(extreme_priority_aircraft: List<AlertAircraft>): String {
        val body = extreme_priority_aircraft
            .sortedWith(compareBy<AlertAircraft> { it.altitude_feet }.thenBy { it.distance_feet })
            .take(MAX_LISTED_AIRCRAFT)
            .joinToString("; ") { aircraft ->
                val registration = aircraft.registration ?: "reg unavailable"
                val estimate_note = if (aircraft.is_estimated_position) " est." else ""
                String.format(
                    Locale.US,
                    "%s %.0f ft%s",
                    registration,
                    aircraft.altitude_feet,
                    estimate_note
                )
            }
        val suffix = if (extreme_priority_aircraft.size > MAX_LISTED_AIRCRAFT) {
            " +${extreme_priority_aircraft.size - MAX_LISTED_AIRCRAFT} more"
        } else {
            ""
        }
        return "$body$suffix"
    }

    private const val MAX_LISTED_AIRCRAFT =
        FlightAlertAppSettings.PriorityNotification.MAX_LISTED_AIRCRAFT
}
