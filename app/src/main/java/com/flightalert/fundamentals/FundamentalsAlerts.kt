package com.flightalert.fundamentals

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.flightalert.AircraftAlertService
import com.flightalert.FlightAlertAppSettings
import com.flightalert.FlightAlertSettings
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS
import com.flightalert.alerts.prepare_priority_snapshot
import com.flightalert.alerts.priority_snapshot_expiry_delay_ms
import com.flightalert.alerts.should_publish_priority_snapshot
import com.flightalert.alerts.should_refresh_priority_snapshot_before_expiry
import com.flightalert.has_flight_location_permission
import com.flightalert.map.UnitSystem
import com.flightalert.ui.PriorityRangeValue
import com.flightalert.ui.adjusted_priority_range_feet

/** Persisted alert controls and real-data classification, outside the Canvas draw path. */
class FundamentalsAlerts(context: Context, private val onChanged: () -> Unit) {
    private val context = context.applicationContext
    private val prefs = FlightAlertSettings.prefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var current = FundamentalsAlertEvaluation()
    private var lastSignature: String? = null
    private var lastPublishElapsedMs = 0L
    private var lastExpiryElapsedMs = 0L
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in SETTING_KEYS) handler.post {
            if (!closed) {
                current = FundamentalsAlertEvaluation(status = "Alert volume updating · settings changed")
                onChanged()
            }
        }
    }

    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    fun settings() = FundamentalsAlertSettings(
        alertsEnabled = prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, false),
        priorityEnabled = prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false),
        ringVisible = prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_RANGE_CIRCLE_VISIBLE,
            FlightAlertSettings.DEFAULT_PRIORITY_RANGE_CIRCLE_VISIBLE),
        horizontalFeet = range(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET,
            FlightAlertSettings.DEFAULT_ALERT_DISTANCE_FEET),
        verticalFeet = range(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET,
            FlightAlertSettings.DEFAULT_ALERT_ALTITUDE_FEET),
        priorityFeet = range(FlightAlertSettings.KEY_PRIORITY_RANGE_FEET,
            FlightAlertSettings.DEFAULT_PRIORITY_RANGE_FEET)
    )

    private fun range(key: String, default: Float) =
        prefs.getFloat(key, default).takeIf { it.isFinite() && it > 0f } ?: default

    fun setAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, enabled).apply()
    }

    fun setPriorityEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, enabled).apply()
    }

    fun setRingVisible(visible: Boolean) {
        prefs.edit().putBoolean(FlightAlertSettings.KEY_PRIORITY_RANGE_CIRCLE_VISIBLE, visible).apply()
    }

    fun setPriorityRangeFeet(feet: Float) {
        if (feet.isFinite()) prefs.edit().putFloat(FlightAlertSettings.KEY_PRIORITY_RANGE_FEET,
            feet.coerceIn(500f, 528000f)).apply()
    }

    fun adjustRange(value: PriorityRangeValue, direction: Float, longPress: Boolean, units: UnitSystem) {
        if (!direction.isFinite() || direction == 0f) return
        val state = settings()
        val horizontal = value == PriorityRangeValue.HORIZONTAL
        val adjusted = adjusted_priority_range_feet(
            if (horizontal) state.horizontalFeet else state.verticalFeet,
            value, if (direction > 0f) 1f else -1f, longPress, units)
        prefs.edit().putFloat(if (horizontal) FlightAlertSettings.KEY_ALERT_DISTANCE_FEET
            else FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, adjusted).apply()
    }

    fun evaluate(aircraft: List<Aircraft>, location: Location?, feedReady: Boolean,
                 nowEpochSec: Double): FundamentalsAlertEvaluation = evaluate_fundamentals_alerts(
        aircraft, settings(), location?.latitude, location?.longitude,
        location?.takeIf { it.hasAltitude() }?.altitude, location?.time?.div(1000.0),
        feedReady, nowEpochSec)

    fun evaluation(): FundamentalsAlertEvaluation = current

    /** Apply worker results and publish only fresh hazards; the service owns background polling. */
    fun accept(evaluation: FundamentalsAlertEvaluation) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { accept(evaluation) }
            return
        }
        if (closed) return
        val state = settings()
        if (evaluation.configuration != null && evaluation.configuration != state) return
        current = evaluation
        if (!context.has_flight_location_permission() ||
            !context.getSystemService(LocationManager::class.java).isLocationEnabled) return
        val nowEpochSec = System.currentTimeMillis() / 1000.0
        val elapsedSeconds = (nowEpochSec - evaluation.evaluatedEpochSec).coerceAtLeast(0.0)
        val notifications = if (state.alertsEnabled && nowEpochSec <= evaluation.ownshipExpiryEpochSec)
            evaluation.notificationAircraft.map { it.copy(contact_age_seconds = it.contact_age_seconds + elapsedSeconds) }
                .filter { it.contact_age_seconds <= EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS }
        else emptyList()
        val payload = prepare_priority_snapshot(notifications)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val refreshBeforeExpiry = payload.signature.isNotEmpty() && lastExpiryElapsedMs > 0L &&
                should_refresh_priority_snapshot_before_expiry(nowElapsedMs, lastExpiryElapsedMs,
                    FlightAlertAppSettings.MapTuning.PRIORITY_NOTIFICATION_SNAPSHOT_CHECK_MS)
        if (!should_publish_priority_snapshot(payload.signature, lastSignature, false,
                nowElapsedMs, lastPublishElapsedMs, AircraftAlertService.POLL_MS) && !refreshBeforeExpiry) return
        try {
            AircraftAlertService.publish_priority_snapshot(context, payload)
            lastSignature = payload.signature
            lastPublishElapsedMs = nowElapsedMs
            lastExpiryElapsedMs = priority_snapshot_expiry_delay_ms(payload.contact_age_seconds, 0L,
                EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS)?.let { nowElapsedMs + it } ?: 0L
        } catch (_: RuntimeException) {
            current = evaluation.copy(status = "${evaluation.status} · background monitoring unavailable")
        }
    }

    fun close() {
        closed = true
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        val SETTING_KEYS = setOf(FlightAlertSettings.KEY_ALERTS_ENABLED,
            FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED,
            FlightAlertSettings.KEY_PRIORITY_RANGE_CIRCLE_VISIBLE,
            FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET,
            FlightAlertSettings.KEY_PRIORITY_RANGE_FEET)
    }
}
