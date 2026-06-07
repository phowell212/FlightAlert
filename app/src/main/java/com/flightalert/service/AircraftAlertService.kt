package com.flightalert.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import com.flightalert.MainActivity
import com.flightalert.data.AircraftFeedClient
import com.flightalert.data.FeedAircraft
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedStatus
import com.flightalert.settings.FlightAlertSettings
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

// Foreground watcher for drone-flight safety: live position plus live aircraft feed, no stored tracks.
class AircraftAlertService : Service(), LocationListener {
    private val executor = Executors.newSingleThreadExecutor()
    private val aircraftFeedClient = AircraftFeedClient(USER_AGENT)
    private val activeHazards = linkedMapOf<String, AlertAircraft>()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in ALERT_RELEVANT_PREF_KEYS) {
            if (!monitoringEnabled()) {
                stopSelf()
            } else {
                pollAircraft()
            }
        }
    }
    private lateinit var locationManager: LocationManager
    private lateinit var prefs: SharedPreferences
    private var latestLocation: Location? = null
    private var polling = false
    private var stopped = false
    private var eventSequence = 0
    private var lastMonitoringBody = ""

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        prefs = FlightAlertSettings.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        ensureChannels()
        startForeground(
            ONGOING_NOTIFICATION_ID,
            buildNotification(MONITORING_CHANNEL_ID, "Monitoring aircraft hazards", "Flight Alert is watching live aircraft traffic.", true)
        )
        startLocationUpdates()
        schedulePoll(1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!monitoringEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PRIORITY_NOTIFICATION_ID)
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val previous = latestLocation
        latestLocation = if (!location.hasAltitude() && previous?.hasAltitude() == true && location.time - previous.time < OWN_ALTITUDE_MAX_AGE_MS) {
            Location(location).apply { altitude = previous.altitude }
        } else {
            location
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = startLocationUpdates()

    override fun onProviderDisabled(provider: String) = Unit

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager.getProviders(true).forEach { provider ->
                val last = locationManager.getLastKnownLocation(provider)
                if (last != null && shouldUseLocation(last, latestLocation)) {
                    latestLocation = last
                }
            }
            if (locationManager.getProviders(true).contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 10f, this)
            }
            if (locationManager.getProviders(true).contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 6000L, 20f, this)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun shouldUseLocation(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        if (candidate.hasAltitude() && !current.hasAltitude()) return true
        return candidate.time > current.time
    }

    private fun schedulePoll(delayMs: Long) {
        if (stopped) return
        Handler(mainLooper).postDelayed({ pollAircraft() }, delayMs)
    }

    private fun pollAircraft() {
        if (polling || stopped) return
        if (!monitoringEnabled()) {
            stopSelf()
            return
        }
        val location = latestLocation
        if (location == null) {
            updateMonitoringNotification("Waiting for a current device location.")
            schedulePoll(POLL_MS)
            return
        }

        polling = true
        executor.execute {
            try {
                val poll = fetchAircraft(location)
                if (!poll.feedAvailable) {
                    updatePriorityNotification(emptyList())
                    updateMonitoringNotification("Aircraft feed unavailable; retaining last hazard state.")
                    return@execute
                }

                val currentHazards = if (alertsEnabled()) {
                    poll.aircraft.filter { it.isHazard }.associateBy { it.icao24 }
                } else {
                    emptyMap()
                }
                val entered = currentHazards.filterKeys { it !in activeHazards.keys }.values
                val left = activeHazards.filterKeys { it !in currentHazards.keys }.values

                entered.forEach { notifyHazardEvent("Aircraft entered alert range", it) }
                left.forEach { notifyHazardEvent("Aircraft left alert range", it) }

                activeHazards.clear()
                activeHazards.putAll(currentHazards)
                updatePriorityNotification(poll.aircraft.filter { it.isExtremePriority })

                val nearest = poll.aircraft.firstOrNull()
                updateMonitoringNotification(
                    when {
                        currentHazards.isNotEmpty() -> "${currentHazards.size} aircraft inside alert range."
                        nearest != null -> "No aircraft inside range. Nearest: ${formatSeparation(nearest)}."
                        else -> "No aircraft reported inside the query area."
                    }
                )
            } catch (_: Exception) {
                updateMonitoringNotification("Aircraft alert check failed; retaining last alert state.")
            } finally {
                polling = false
                schedulePoll(POLL_MS)
            }
        }
    }

    private fun fetchAircraft(location: Location): AlertPoll {
        val alertDistanceFeet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, FlightAlertSettings.DEFAULT_ALERT_DISTANCE_FEET)
        val alertAltitudeFeet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, FlightAlertSettings.DEFAULT_ALERT_ALTITUDE_FEET)
        val priorityEnabled = prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
        val priorityRangeFeet = prefs.getFloat(FlightAlertSettings.KEY_PRIORITY_RANGE_FEET, FlightAlertSettings.DEFAULT_PRIORITY_RANGE_FEET)
        val priorityAltitudeBelowFeet = prefs.getFloat(FlightAlertSettings.KEY_PRIORITY_ALTITUDE_BELOW_FEET, FlightAlertSettings.DEFAULT_PRIORITY_ALTITUDE_BELOW_FEET)
        val ownAltitudeFeet = if (location.hasAltitude()) metersToFeet(location.altitude) else null
        val radiusFeet = max(alertDistanceFeet.toDouble(), if (priorityEnabled) priorityRangeFeet.toDouble() else MIN_QUERY_RADIUS_FEET)
        val radiusMeters = max(feetToMeters(radiusFeet), feetToMeters(MIN_QUERY_RADIUS_FEET))
        val bounds = boundsAround(location, radiusMeters)
        val result = aircraftFeedClient.fetchAircraft(bounds.toFeedBounds(), location.latitude, location.longitude)
        if (result.status != FeedStatus.OK) return AlertPoll(emptyList(), feedAvailable = false)
        return AlertPoll(
            aircraft = result.aircraft
                .filter { it.onGround != true }
                .mapNotNull {
                    it.toAlertAircraft(
                        alertDistanceFeet = alertDistanceFeet,
                        alertAltitudeFeet = alertAltitudeFeet,
                        ownAltitudeFeet = ownAltitudeFeet,
                        priorityEnabled = priorityEnabled,
                        priorityRangeFeet = priorityRangeFeet,
                        priorityAltitudeBelowFeet = priorityAltitudeBelowFeet
                    )
                }
                .sortedBy { it.distanceFeet },
            feedAvailable = true
        )
    }

    private fun notifyHazardEvent(title: String, aircraft: AlertAircraft) {
        if (!hasNotificationPermission()) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            EVENT_NOTIFICATION_BASE_ID + (eventSequence++ % EVENT_NOTIFICATION_ID_WINDOW),
            buildNotification(
                HAZARD_CHANNEL_ID,
                "$title: ${aircraft.callsign}",
                String.format(Locale.US, "%s, %.0f ft altitude", formatSeparation(aircraft), aircraft.altitudeFeet),
                false
            )
        )
    }

    private fun formatSeparation(aircraft: AlertAircraft): String {
        return if (aircraft.verticalSeparationFeet == null) {
            String.format(Locale.US, "%.0f ft horizontal, vertical unavailable", aircraft.distanceFeet)
        } else {
            String.format(Locale.US, "%.0f ft horizontal, %.0f ft vertical", aircraft.distanceFeet, aircraft.verticalSeparationFeet)
        }
    }

    private fun updateMonitoringNotification(body: String) {
        if (body == lastMonitoringBody) return
        lastMonitoringBody = body
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ONGOING_NOTIFICATION_ID, buildNotification(MONITORING_CHANNEL_ID, "Monitoring aircraft hazards", body, true))
    }

    private fun updatePriorityNotification(priorityAircraft: List<AlertAircraft>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!priorityTrackingEnabled() || priorityAircraft.isEmpty() || !hasNotificationPermission()) {
            manager.cancel(PRIORITY_NOTIFICATION_ID)
            return
        }
        val body = priorityAircraft
            .sortedWith(compareBy<AlertAircraft> { it.altitudeFeet }.thenBy { it.distanceFeet })
            .take(4)
            .joinToString("; ") { aircraft ->
                val registration = aircraft.registration ?: "reg unavailable"
                String.format(Locale.US, "%s %.0f ft", registration, aircraft.altitudeFeet)
            }
        val suffix = if (priorityAircraft.size > 4) " +${priorityAircraft.size - 4} more" else ""
        manager.notify(
            PRIORITY_NOTIFICATION_ID,
            buildNotification(
                PRIORITY_CHANNEL_ID,
                "Extreme priority aircraft",
                "$body$suffix",
                true
            )
        )
    }

    private fun buildNotification(channelId: String, title: String, body: String, ongoing: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(ongoing)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun ensureChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(MONITORING_CHANNEL_ID, "Aircraft monitoring status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing status while Flight Alert is monitoring live aircraft traffic"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(HAZARD_CHANNEL_ID, "Aircraft range entry and exit alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Aircraft entering or leaving the selected alert range"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(PRIORITY_CHANNEL_ID, "Extreme priority aircraft", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Persistent list of aircraft below the selected altitude inside the priority tracking range"
            }
        )
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun alertsEnabled(): Boolean {
        return prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true)
    }

    private fun priorityTrackingEnabled(): Boolean {
        return prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
    }

    private fun monitoringEnabled(): Boolean {
        return alertsEnabled() || priorityTrackingEnabled()
    }

    private fun boundsAround(location: Location, radiusMeters: Double): Bounds {
        val radiusKm = radiusMeters / 1000.0
        val latDelta = radiusKm / 111.0
        val lonDelta = radiusKm / (111.0 * max(0.25, cos(Math.toRadians(location.latitude))))
        return Bounds(
            minLat = (location.latitude - latDelta).coerceIn(-90.0, 90.0),
            maxLat = (location.latitude + latDelta).coerceIn(-90.0, 90.0),
            minLon = (location.longitude - lonDelta).coerceIn(-180.0, 180.0),
            maxLon = (location.longitude + lonDelta).coerceIn(-180.0, 180.0)
        )
    }

    private fun Bounds.toFeedBounds(): FeedBounds {
        return FeedBounds(minLat = minLat, minLon = minLon, maxLat = maxLat, maxLon = maxLon)
    }

    private fun FeedAircraft.toAlertAircraft(
        alertDistanceFeet: Float,
        alertAltitudeFeet: Float,
        ownAltitudeFeet: Double?,
        priorityEnabled: Boolean,
        priorityRangeFeet: Float,
        priorityAltitudeBelowFeet: Float
    ): AlertAircraft? {
        val altitudeMeters = altitudeM ?: return null
        val distanceFeet = metersToFeet(distanceM)
        val altitudeFeet = metersToFeet(altitudeMeters)
        val verticalSeparationFeet = ownAltitudeFeet?.let { abs(altitudeFeet - it) }
        return AlertAircraft(
            icao24 = icao24,
            callsign = callsign,
            registration = registration,
            distanceFeet = distanceFeet,
            altitudeFeet = altitudeFeet,
            verticalSeparationFeet = verticalSeparationFeet,
            isHazard = distanceFeet <= alertDistanceFeet &&
                verticalSeparationFeet != null &&
                verticalSeparationFeet <= alertAltitudeFeet,
            isExtremePriority = priorityEnabled && distanceFeet <= priorityRangeFeet && altitudeFeet <= priorityAltitudeBelowFeet
        )
    }

    private fun feetToMeters(feet: Double): Double = feet / 3.28084

    private fun metersToFeet(meters: Double): Double = meters * 3.28084

    private fun immutableFlag(): Int = PendingIntent.FLAG_IMMUTABLE

    private data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    private data class AlertPoll(val aircraft: List<AlertAircraft>, val feedAvailable: Boolean)

    private data class AlertAircraft(
        val icao24: String,
        val callsign: String,
        val registration: String?,
        val distanceFeet: Double,
        val altitudeFeet: Double,
        val verticalSeparationFeet: Double?,
        val isHazard: Boolean,
        val isExtremePriority: Boolean
    )

    companion object {
        const val MONITORING_CHANNEL_ID = "aircraft_monitoring_status"
        const val HAZARD_CHANNEL_ID = "aircraft_range_events"
        const val PRIORITY_CHANNEL_ID = "extreme_priority_aircraft"
        const val ONGOING_NOTIFICATION_ID = 2001
        const val PRIORITY_NOTIFICATION_ID = 2002
        const val EVENT_NOTIFICATION_BASE_ID = 2100
        const val EVENT_NOTIFICATION_ID_WINDOW = 200
        const val POLL_MS = 10000L
        const val MIN_QUERY_RADIUS_FEET = 10000.0
        const val OWN_ALTITUDE_MAX_AGE_MS = 120000L
        const val USER_AGENT = "FlightAlertPrototype/0.1"
        val ALERT_RELEVANT_PREF_KEYS = setOf(
            FlightAlertSettings.KEY_ALERTS_ENABLED,
            FlightAlertSettings.KEY_ALERT_DISTANCE_FEET,
            FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET,
            FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED,
            FlightAlertSettings.KEY_PRIORITY_RANGE_FEET,
            FlightAlertSettings.KEY_PRIORITY_ALTITUDE_BELOW_FEET
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AircraftAlertService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AircraftAlertService::class.java))
        }
    }
}
