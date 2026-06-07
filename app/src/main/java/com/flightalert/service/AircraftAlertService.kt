package com.flightalert.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import com.flightalert.MainActivity
import com.flightalert.data.AircraftFeedClient
import com.flightalert.data.FeedAircraft
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedStatus
import com.flightalert.settings.FlightAlertSettings
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.max

// Foreground watcher for drone-flight safety: live position plus live aircraft feed, no stored tracks.
class AircraftAlertService : Service(), LocationListener {
    private val executor = Executors.newSingleThreadExecutor()
    private val aircraftFeedClient = AircraftFeedClient(USER_AGENT)
    private lateinit var locationManager: LocationManager
    private var latestLocation: Location? = null
    private var polling = false
    private var stopped = false
    private var lastAlertMs = 0L
    private var lastAlertIcao24: String? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ensureChannel()
        startForeground(ONGOING_NOTIFICATION_ID, buildNotification("Monitoring aircraft hazards", "Flight Alert is watching live aircraft traffic."))
        startLocationUpdates()
        schedulePoll(1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!alertsEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
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
        latestLocation = location
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
                if (last != null && (latestLocation == null || last.time > (latestLocation?.time ?: 0L))) {
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

    private fun schedulePoll(delayMs: Long) {
        if (stopped) return
        android.os.Handler(mainLooper).postDelayed({ pollAircraft() }, delayMs)
    }

    private fun pollAircraft() {
        if (polling || stopped) return
        if (!alertsEnabled()) {
            stopSelf()
            return
        }
        val location = latestLocation
        if (location == null) {
            schedulePoll(POLL_MS)
            return
        }

        polling = true
        executor.execute {
            try {
                // Query around the device, then notify only when user-defined hazard thresholds are met.
                val hazard = fetchAircraft(location).firstOrNull { it.isHazard }
                if (hazard != null) {
                    maybeNotify(hazard)
                }
            } catch (_: Exception) {
            } finally {
                polling = false
                schedulePoll(POLL_MS)
            }
        }
    }

    private fun fetchAircraft(location: Location): List<AlertAircraft> {
        val prefs = FlightAlertSettings.prefs(this)
        val alertDistanceFeet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, FlightAlertSettings.DEFAULT_ALERT_DISTANCE_FEET)
        val alertAltitudeFeet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, FlightAlertSettings.DEFAULT_ALERT_ALTITUDE_FEET)
        val radiusMeters = max(feetToMeters(alertDistanceFeet.toDouble()), feetToMeters(MIN_QUERY_RADIUS_FEET))
        val bounds = boundsAround(location, radiusMeters)
        val result = aircraftFeedClient.fetchAircraft(bounds.toFeedBounds(), location.latitude, location.longitude)
        if (result.status != FeedStatus.OK) return emptyList()
        return result.aircraft
            .filter { it.onGround != true }
            .mapNotNull { it.toAlertAircraft(alertDistanceFeet, alertAltitudeFeet) }
            .sortedBy { it.distanceFeet }
    }

    private fun maybeNotify(aircraft: AlertAircraft) {
        val now = System.currentTimeMillis()
        if (aircraft.icao24 == lastAlertIcao24 && now - lastAlertMs < ALERT_COOLDOWN_MS) return
        lastAlertIcao24 = aircraft.icao24
        lastAlertMs = now

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            HAZARD_NOTIFICATION_ID,
            buildNotification(
                "Aircraft hazard: ${aircraft.callsign}",
                String.format(Locale.US, "%.0f ft away, %.0f ft altitude", aircraft.distanceFeet, aircraft.altitudeFeet)
            )
        )
    }

    private fun buildNotification(title: String, body: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setOngoing(title.startsWith("Monitoring"))
            .setAutoCancel(!title.startsWith("Monitoring"))
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Aircraft hazard alerts", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Live aircraft proximity alerts for drone flying"
        manager.createNotificationChannel(channel)
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun alertsEnabled(): Boolean {
        return FlightAlertSettings.prefs(this).getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true)
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

    private fun FeedAircraft.toAlertAircraft(alertDistanceFeet: Float, alertAltitudeFeet: Float): AlertAircraft? {
        val altitudeMeters = altitudeM ?: return null
        val distanceFeet = metersToFeet(distanceM)
        val altitudeFeet = metersToFeet(altitudeMeters)
        return AlertAircraft(
            icao24 = icao24,
            callsign = callsign,
            distanceFeet = distanceFeet,
            altitudeFeet = altitudeFeet,
            isHazard = distanceFeet <= alertDistanceFeet && altitudeFeet <= alertAltitudeFeet
        )
    }

    private fun feetToMeters(feet: Double): Double = feet / 3.28084

    private fun metersToFeet(meters: Double): Double = meters * 3.28084

    private fun immutableFlag(): Int {
        return PendingIntent.FLAG_IMMUTABLE
    }

    private data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    private data class AlertAircraft(
        val icao24: String,
        val callsign: String,
        val distanceFeet: Double,
        val altitudeFeet: Double,
        val isHazard: Boolean
    )

    companion object {
        const val CHANNEL_ID = "aircraft_hazard_alerts"
        const val ONGOING_NOTIFICATION_ID = 2001
        const val HAZARD_NOTIFICATION_ID = 2002
        const val POLL_MS = 30000L
        const val ALERT_COOLDOWN_MS = 60000L
        const val MIN_QUERY_RADIUS_FEET = 10000.0
        const val USER_AGENT = "FlightAlertPrototype/0.1"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AircraftAlertService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AircraftAlertService::class.java))
        }
    }
}
