package com.flightalert.fundamentals

import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AlertAircraft
import com.flightalert.aircraft.EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS
import com.flightalert.aircraft.classify_alert_aircraft
import com.flightalert.aircraft.projected_alert_position

data class FundamentalsAlertSettings(
    val alertsEnabled: Boolean,
    val priorityEnabled: Boolean,
    val ringVisible: Boolean,
    val horizontalFeet: Float,
    val verticalFeet: Float,
    val priorityFeet: Float
)

data class FundamentalsAlertEvaluation(
    val hazardKeys: Set<String> = emptySet(),
    val volumeKeys: Set<String> = emptySet(),
    val queue: List<AlertAircraft> = emptyList(),
    val unknownKeys: Set<String> = emptySet(),
    val volumeAvailable: Boolean = false,
    val status: String = "Alert volume unknown · waiting for fresh data",
    internal val notificationAircraft: List<AlertAircraft> = emptyList(),
    internal val evaluatedEpochSec: Double = 0.0,
    internal val ownshipExpiryEpochSec: Double = 0.0,
    internal val configuration: FundamentalsAlertSettings? = null
)

internal fun evaluate_fundamentals_alerts(
    aircraft: List<Aircraft>,
    settings: FundamentalsAlertSettings,
    ownLatitude: Double?,
    ownLongitude: Double?,
    ownAltitudeMeters: Double?,
    ownLocationEpochSec: Double?,
    feedReady: Boolean,
    nowEpochSec: Double
): FundamentalsAlertEvaluation {
    val airborne = aircraft.filter { it.on_ground != true }
    val unknown = linkedSetOf<String>()
    val ownshipMaxAge = FlightAlertAppSettings.AlertService.OWN_ALTITUDE_MAX_AGE_MS / 1000.0
    val locationReady = ownLatitude != null && ownLatitude.isFinite() && ownLatitude in -90.0..90.0 &&
            ownLongitude != null && ownLongitude.isFinite() && ownLongitude in -180.0..180.0 &&
            fresh_report(ownLocationEpochSec, nowEpochSec, ownshipMaxAge)
    if (!feedReady || !locationReady) {
        return FundamentalsAlertEvaluation(
            unknownKeys = airborne.mapTo(unknown) { it.icao_key },
            status = if (!feedReady) "Alert volume unknown · aircraft feed unavailable or stale"
                else "Alert volume unknown · ownship location unavailable or stale",
            evaluatedEpochSec = nowEpochSec,
            configuration = settings
        )
    }
    val ownAltitudeFeet = ownAltitudeMeters?.takeIf { it.isFinite() }?.div(0.3048)
    val volume = linkedSetOf<String>()
    val queue = ArrayList<AlertAircraft>()
    val notifications = ArrayList<AlertAircraft>()
    for (item in airborne) {
        val report = item.position_time_sec ?: item.last_contact_sec
        val contact = item.last_contact_sec ?: item.position_time_sec
        if (!item.lat.isFinite() || item.lat !in -90.0..90.0 ||
            !item.lon.isFinite() || item.lon !in -180.0..180.0 ||
            !fresh_report(report, nowEpochSec, EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS) ||
            !fresh_report(contact, nowEpochSec, EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS)) {
            unknown += item.icao_key
            continue
        }
        val projected = projected_alert_position(
            own_lat = ownLatitude!!, own_lon = ownLongitude!!,
            aircraft_lat = item.lat, aircraft_lon = item.lon,
            // Feed distances may use a viewport query center instead of ownship.
            reported_distance_meters = Double.NaN,
            altitude_meters = item.altitude_m?.takeIf { it.isFinite() },
            velocity_ms = item.velocity_ms, track_deg = item.track_deg,
            vertical_rate_ms = item.vertical_rate_ms,
            position_time_sec = item.position_time_sec, last_contact_sec = item.last_contact_sec,
            now_epoch_sec = nowEpochSec
        )
        val altitude = projected.altitude_meters?.takeIf { it.isFinite() }
        if (ownAltitudeFeet == null || altitude == null) unknown += item.icao_key
        val alert = classify_alert_aircraft(
            icao24 = item.icao24, callsign = item.callsign, registration = item.registration,
            distance_meters = projected.distance_meters, altitude_meters = altitude,
            last_contact_sec = item.last_contact_sec, position_time_sec = item.position_time_sec,
            own_altitude_feet = ownAltitudeFeet, alerts_enabled = true,
            alert_distance_feet = settings.horizontalFeet, alert_altitude_feet = settings.verticalFeet,
            priority_enabled = settings.priorityEnabled, priority_range_feet = settings.priorityFeet,
            now_epoch_sec = nowEpochSec, is_estimated_position = projected.estimated
        )
        if (alert?.is_hazard == true) {
            volume += item.icao_key
            if (settings.alertsEnabled) notifications += alert
        }
        if (alert?.is_priority_range_aircraft == true) {
            queue += alert.copy(is_hazard = settings.alertsEnabled && alert.is_hazard,
                is_extreme_priority = settings.alertsEnabled && alert.is_extreme_priority)
        } else if (altitude == null && settings.priorityEnabled &&
            projected.distance_meters / 0.3048 <= settings.priorityFeet) {
            queue += AlertAircraft(item.icao24, item.callsign, item.registration,
                projected.distance_meters / 0.3048, Double.NaN, null,
                (nowEpochSec - contact!!).coerceAtLeast(0.0), false, true, false, projected.estimated)
        }
    }
    val volumeAvailable = ownAltitudeFeet != null
    val status = when {
        !volumeAvailable -> "Alert volume unknown · ownship altitude unavailable"
        !settings.alertsEnabled && !settings.priorityEnabled -> "Monitoring off"
        unknown.isNotEmpty() -> "Observed ${volume.size} in alert volume · ${unknown.size} unknown"
        else -> "Observed ${volume.size} in alert volume"
    } + if (settings.priorityEnabled) " · ${queue.size} in priority radius queue" else ""
    return FundamentalsAlertEvaluation(
        hazardKeys = if (settings.alertsEnabled) volume else emptySet(),
        volumeKeys = volume,
        queue = queue.sortedBy { it.distance_feet },
        unknownKeys = unknown,
        volumeAvailable = volumeAvailable,
        status = status,
        notificationAircraft = notifications,
        evaluatedEpochSec = nowEpochSec,
        ownshipExpiryEpochSec = ownLocationEpochSec!! + ownshipMaxAge,
        configuration = settings
    )
}

private fun fresh_report(reportEpochSec: Double?, nowEpochSec: Double, maxAge: Double): Boolean =
    reportEpochSec != null && reportEpochSec.isFinite() && reportEpochSec > 0.0 &&
            nowEpochSec.isFinite() && nowEpochSec - reportEpochSec in 0.0..maxAge
