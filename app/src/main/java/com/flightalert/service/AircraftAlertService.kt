package com.flightalert.service

import android.Manifest
import android.annotation.SuppressLint
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
import com.flightalert.data.api.AircraftFeedClient
import com.flightalert.data.FeedAircraft
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedStatus
import com.flightalert.settings.FlightAlertSettings
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Foreground watcher for drone-flight safety: live position plus live aircraft feed, no stored tracks.
class AircraftAlertService : Service(), LocationListener {
    private val executor = Executors.newSingleThreadExecutor()
    private val aircraft_feed_client = AircraftFeedClient(USER_AGENT)
    private val active_hazards = linkedMapOf<String, AlertAircraft>()

    // Settings changes immediately repoll or stop the watcher so notification state follows the configured volume.
    private val preference_listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in ALERT_RELEVANT_PREF_KEYS) {
            if (!monitoring_enabled()) {
                stopSelf()
            } else {
                poll_aircraft()
            }
        }
    }
    private lateinit var location_manager: LocationManager
    private lateinit var prefs: SharedPreferences
    private var latest_location: Location? = null
    private var polling = false
    private var stopped = false
    private var foreground_active = false
    private var priority_notification_showing = false
    private var next_poll_delay_ms = POLL_MS

    // Android starts the service here; set up providers, channels, and the first poll.
    override fun onCreate() {
        super.onCreate()
        location_manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        prefs = FlightAlertSettings.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(preference_listener)
        ensure_channels()
        clear_legacy_notifications()
        start_location_updates()
        schedule_poll(1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!monitoring_enabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // Service teardown clears foreground state so no priority notification survives a stopped watcher.
    override fun onDestroy() {
        stopped = true
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(preference_listener)
        }
        clear_legacy_notifications()
        leave_foreground()
        try {
            location_manager.removeUpdates(this)
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

    // Keep a recent altitude briefly when Android sends a new horizontal fix without altitude.
    override fun onLocationChanged(location: Location) {
        val previous = latest_location
        latest_location = if (!location.hasAltitude() && previous?.hasAltitude() == true && location.time - previous.time < OWN_ALTITUDE_MAX_AGE_MS) {
            Location(location).apply { altitude = previous.altitude }
        } else {
            location
        }
    }


    override fun onProviderEnabled(provider: String) = start_location_updates()

    override fun onProviderDisabled(provider: String) = Unit

    // Seed from last known providers, then subscribe to GPS/network so alert math has real ownship position.
    private fun start_location_updates() {
        if (!has_location_permission()) return
        try {
            location_manager.getProviders(true).forEach { provider ->
                val last = location_manager.getLastKnownLocation(provider)
                if (last != null && should_use_location(last, latest_location)) {
                    latest_location = last
                }
            }
            if (location_manager.getProviders(true).contains(LocationManager.GPS_PROVIDER)) {
                location_manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 10f, this)
            }
            if (location_manager.getProviders(true).contains(LocationManager.NETWORK_PROVIDER)) {
                location_manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 6000L, 20f, this)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun should_use_location(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        if (candidate.hasAltitude() && !current.hasAltitude()) return true
        return candidate.time > current.time
    }

    private fun schedule_poll(delay_ms: Long) {
        if (stopped) return
        Handler(mainLooper).postDelayed({ poll_aircraft() }, delay_ms)
    }

    // One poll reads location, fetches real aircraft, recomputes hazards, then updates the persistent notification.
    private fun poll_aircraft() {
        if (polling || stopped) return
        if (!monitoring_enabled()) {
            stopSelf()
            return
        }
        val location = latest_location
        if (location == null) {
            start_location_updates()
            update_priority_notification(emptyList())
            next_poll_delay_ms = POLL_MS
            schedule_poll(next_poll_delay_ms)
            return
        }

        polling = true
        executor.execute {
            try {
                val poll = fetch_aircraft(location)
                if (!poll.feed_available) {
                    update_priority_notification(emptyList())
                    next_poll_delay_ms = POLL_MS
                    return@execute
                }

                // The extreme list comes from the classifier; notification code never invents membership.
                val current_hazards = if (alerts_enabled()) {
                    poll.aircraft.filter { it.is_hazard }.associateBy { it.icao24 }
                } else {
                    emptyMap()
                }
                active_hazards.clear()
                active_hazards.putAll(current_hazards)
                val extreme_priority = if (alerts_enabled()) poll.aircraft.filter { it.is_extreme_priority } else emptyList()
                update_priority_notification(extreme_priority)
                next_poll_delay_ms = next_poll_delay_for(
                    aircraft = poll.aircraft,
                    has_hazard = current_hazards.isNotEmpty(),
                    has_extreme_priority = extreme_priority.isNotEmpty(),
                    predictive_delay_ms = poll.next_predictive_poll_delay_ms
                )
            } catch (_: Exception) {
                update_priority_notification(emptyList())
                next_poll_delay_ms = POLL_MS
            } finally {
                polling = false
                schedule_poll(next_poll_delay_ms)
            }
        }
    }

    // Poll faster only while nearby or priority contacts could go stale soon.
    private fun next_poll_delay_for(
        aircraft: List<AlertAircraft>,
        has_hazard: Boolean,
        has_extreme_priority: Boolean,
        predictive_delay_ms: Long? = null
    ): Long {
        val extreme_aircraft = aircraft.filter { it.is_extreme_priority }
        val hazard_aircraft = aircraft.filter { it.is_hazard }
        val priority_aircraft = aircraft.filter { it.is_priority_range_aircraft }
        val normal_delay = when {
            has_extreme_priority -> delay_until_stale(extreme_aircraft, AlertAircraftClassifier.EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS)
            has_hazard -> delay_until_stale(hazard_aircraft, PRIORITY_CONTACT_MAX_AGE_SECONDS)
            priority_aircraft.isNotEmpty() -> delay_until_stale(priority_aircraft, PRIORITY_CONTACT_MAX_AGE_SECONDS)
            else -> POLL_MS
        }
        return predictive_delay_ms?.let { min(normal_delay, it) } ?: normal_delay
    }

    private fun delay_until_stale(aircraft: List<AlertAircraft>, max_age_seconds: Double): Long {
        val oldest_age = aircraft.maxOfOrNull { it.contact_age_seconds } ?: return POLL_MS
        val seconds_until_stale = max_age_seconds - oldest_age
        return if (seconds_until_stale <= 0.0) {
            STALE_CONTACT_RETRY_MS
        } else {
            (seconds_until_stale * 1000.0).toLong().coerceIn(STALE_CONTACT_RETRY_MS, (max_age_seconds * 1000.0).toLong())
        }
    }

    // Safety monitoring deliberately uses API feeds only; map Web/Hybrid mode never drives notifications.
    // The query bubble is wider than the alert volume so entering aircraft are seen before they cross the boundary.
    private fun fetch_aircraft(location: Location): AlertPoll {
        val alert_distance_feet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, FlightAlertSettings.DEFAULT_ALERT_DISTANCE_FEET)
        val alert_altitude_feet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, FlightAlertSettings.DEFAULT_ALERT_ALTITUDE_FEET)
        val priority_enabled = prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
        val priority_range_feet = prefs.getFloat(FlightAlertSettings.KEY_PRIORITY_RANGE_FEET, FlightAlertSettings.DEFAULT_PRIORITY_RANGE_FEET)
        val own_altitude_feet = if (location.hasAltitude()) meters_to_feet(location.altitude) else null
        val radius_feet = api_query_radius_feet(alert_distance_feet, priority_enabled, priority_range_feet)
        val radius_meters = max(feet_to_meters(radius_feet), feet_to_meters(MIN_QUERY_RADIUS_FEET))
        val bounds = bounds_around(location, radius_meters)
        val result = aircraft_feed_client.fetch_aircraft(bounds.to_feed_bounds(), location.latitude, location.longitude)
        if (result.status != FeedStatus.OK) return AlertPoll(emptyList(), feed_available = false)
        val airborne_aircraft = result.aircraft.filter { it.on_ground != true }
        val unsupported_altitude_count = airborne_aircraft.count { it.altitude_m == null }
        val now_epoch_sec = System.currentTimeMillis() / 1000.0
        return AlertPoll(
            aircraft = airborne_aircraft
                .mapNotNull {
                    it.to_alert_aircraft(
                        own_lat = location.latitude,
                        own_lon = location.longitude,
                        alert_distance_feet = alert_distance_feet,
                        alert_altitude_feet = alert_altitude_feet,
                        own_altitude_feet = own_altitude_feet,
                        priority_enabled = priority_enabled,
                        priority_range_feet = priority_range_feet,
                        now_epoch_sec = now_epoch_sec
                    )
                }
                .sortedBy { it.distance_feet },
            feed_available = true,
            unsupported_altitude_count = unsupported_altitude_count,
            next_predictive_poll_delay_ms = next_predictive_poll_delay_ms(
                aircraft = airborne_aircraft,
                own_lat = location.latitude,
                own_lon = location.longitude,
                own_altitude_feet = own_altitude_feet,
                alert_distance_feet = alert_distance_feet,
                alert_altitude_feet = alert_altitude_feet,
                now_epoch_sec = now_epoch_sec
            )
        )
    }

    private fun api_query_radius_feet(
        alert_distance_feet: Float,
        priority_enabled: Boolean,
        priority_range_feet: Float
    ): Double {
        val alert_radius = alert_distance_feet.toDouble()
        val alert_radius_with_margin = max(
            alert_radius * ALERT_QUERY_RADIUS_MULTIPLIER,
            alert_radius + ALERT_QUERY_MIN_PADDING_FEET
        )
        val queue_radius = if (priority_enabled) priority_range_feet.toDouble() else MIN_QUERY_RADIUS_FEET
        return max(alert_radius_with_margin, queue_radius)
    }

    private fun format_separation(aircraft: AlertAircraft): String {
        return if (aircraft.vertical_separation_feet == null) {
            String.format(Locale.US, "%.0f ft horizontal, vertical unavailable", aircraft.distance_feet)
        } else {
            String.format(Locale.US, "%.0f ft horizontal, %.0f ft vertical", aircraft.distance_feet, aircraft.vertical_separation_feet)
        }
    }

    // The persistent notification is tied directly to the non-empty extreme-priority list.
    private fun update_priority_notification(priority_aircraft: List<AlertAircraft>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Defensive filtering keeps the notification contract tied to classifier output, even if a caller passes nearby queue contacts.
        val extreme_priority_aircraft = PriorityNotificationPresenter.extreme_priority_aircraft(priority_aircraft)
        if (!AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = alerts_enabled(),
                extreme_priority_aircraft = extreme_priority_aircraft,
                has_notification_permission = has_notification_permission()
            )
        ) {
            manager.cancel(PRIORITY_NOTIFICATION_ID)
            priority_notification_showing = false
            leave_foreground()
            return
        }
        val is_update = priority_notification_showing
        val notification = build_notification(
            PRIORITY_CHANNEL_ID,
            "Extreme priority aircraft",
            PriorityNotificationPresenter.notification_body(extreme_priority_aircraft),
            ongoing = true,
            silent = is_update
        )
        try {
            if (foreground_active) {
                notify_priority_fallback(manager, notification)
            } else {
                startForeground(PRIORITY_NOTIFICATION_ID, notification)
                foreground_active = true
            }
            priority_notification_showing = true
        } catch (_: Exception) {
            notify_priority_fallback(manager, notification)
            priority_notification_showing = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify_priority_fallback(manager: NotificationManager, notification: Notification) {
        if (!has_notification_permission()) return
        manager.notify(PRIORITY_NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun build_notification(
        channel_id: String,
        title: String,
        body: String,
        ongoing: Boolean,
        silent: Boolean = false
    ): Notification {
        val pending_intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable_flag()
        )
        val builder = Notification.Builder(this, channel_id)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending_intent)
            .setOnlyAlertOnce(ongoing)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(build_public_notification(channel_id, title, ongoing, silent))
        if (silent) {
            builder
                .setDefaults(0)
                .setSound(null)
                .setVibrate(null)
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun build_public_notification(channel_id: String, title: String, ongoing: Boolean, silent: Boolean): Notification {
        val builder = Notification.Builder(this, channel_id)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText("Flight Alert is monitoring live aircraft traffic.")
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (silent) {
            builder
                .setDefaults(0)
                .setSound(null)
                .setVibrate(null)
        }
        return builder.build()
    }

    private fun ensure_channels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(PRIORITY_CHANNEL_ID, "Extreme priority aircraft", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Persistent list of priority-tracked aircraft inside the selected alert range"
            }
        )
    }

    // Clean old notification IDs before using the new extreme-priority foreground notification.
    private fun clear_legacy_notifications() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.activeNotifications.any { it.id == ONGOING_NOTIFICATION_ID }) {
            take_over_legacy_foreground_notification()
        }
        remove_foreground_notification()
        manager.cancel(ONGOING_NOTIFICATION_ID)
        manager.cancel(PRIORITY_NOTIFICATION_ID)
        repeat(EVENT_NOTIFICATION_ID_WINDOW) { offset ->
            manager.cancel(EVENT_NOTIFICATION_BASE_ID + offset)
        }
    }

    private fun take_over_legacy_foreground_notification() {
        if (!has_notification_permission()) return
        try {
            startForeground(
                ONGOING_NOTIFICATION_ID,
                build_notification(PRIORITY_CHANNEL_ID, "Flight Alert", "Updating alert notifications.", true)
            )
            foreground_active = true
        } catch (_: Exception) {
        }
    }

    private fun leave_foreground() {
        if (!foreground_active) return
        remove_foreground_notification()
    }

    private fun remove_foreground_notification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground_active = false
        priority_notification_showing = false
    }

    private fun has_location_permission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun has_notification_permission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun alerts_enabled(): Boolean {
        return prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true)
    }

    private fun priority_tracking_enabled(): Boolean {
        return prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
    }

    private fun monitoring_enabled(): Boolean {
        return alerts_enabled() || priority_tracking_enabled()
    }

    // Turn the alert radius into a provider query box around the device location.
    private fun bounds_around(location: Location, radius_meters: Double): Bounds {
        val radius_km = radius_meters / 1000.0
        val lat_delta = radius_km / 111.0
        val lon_delta = radius_km / (111.0 * max(0.25, cos(Math.toRadians(location.latitude))))
        return Bounds(
            min_lat = (location.latitude - lat_delta).coerceIn(-90.0, 90.0),
            max_lat = (location.latitude + lat_delta).coerceIn(-90.0, 90.0),
            min_lon = (location.longitude - lon_delta).coerceIn(-180.0, 180.0),
            max_lon = (location.longitude + lon_delta).coerceIn(-180.0, 180.0)
        )
    }

    private fun Bounds.to_feed_bounds(): FeedBounds {
        return FeedBounds(min_lat = min_lat, min_lon = min_lon, max_lat = max_lat, max_lon = max_lon)
    }

    // Convert provider units into classifier inputs so 3D alert math stays in one object.
    private fun FeedAircraft.to_alert_aircraft(
        own_lat: Double,
        own_lon: Double,
        alert_distance_feet: Float,
        alert_altitude_feet: Float,
        own_altitude_feet: Double?,
        priority_enabled: Boolean,
        priority_range_feet: Float,
        now_epoch_sec: Double
    ): AlertAircraft? {
        val projected = projected_alert_state(own_lat, own_lon, now_epoch_sec)
        return AlertAircraftClassifier.classify(
            icao24 = icao24,
            callsign = callsign,
            registration = registration,
            distance_meters = projected.distance_meters,
            altitude_meters = projected.altitude_meters,
            last_contact_sec = last_contact_sec,
            position_time_sec = position_time_sec,
            own_altitude_feet = own_altitude_feet,
            alerts_enabled = alerts_enabled(),
            alert_distance_feet = alert_distance_feet,
            alert_altitude_feet = alert_altitude_feet,
            priority_enabled = priority_enabled,
            priority_range_feet = priority_range_feet,
            now_epoch_sec = now_epoch_sec,
            is_estimated_position = projected.estimated
        )
    }

    private fun next_predictive_poll_delay_ms(
        aircraft: List<FeedAircraft>,
        own_lat: Double,
        own_lon: Double,
        own_altitude_feet: Double?,
        alert_distance_feet: Float,
        alert_altitude_feet: Float,
        now_epoch_sec: Double
    ): Long? {
        if (!alerts_enabled() || own_altitude_feet == null) return null
        val alert_distance_m = feet_to_meters(alert_distance_feet.toDouble())
        val alert_altitude_m = feet_to_meters(alert_altitude_feet.toDouble())
        val own_altitude_m = feet_to_meters(own_altitude_feet)
        var soonest_delay_ms: Long? = null
        for (item in aircraft) {
            if (!item.has_fresh_projectable_motion(now_epoch_sec) || item.altitude_m == null) continue
            for (seconds_ahead in 1..ALERT_ENTRY_LOOKAHEAD_SECONDS) {
                val state = item.projected_alert_state(own_lat, own_lon, now_epoch_sec + seconds_ahead)
                val altitude_m = state.altitude_meters ?: continue
                val vertical_separation_m = kotlin.math.abs(altitude_m - own_altitude_m)
                if (state.distance_meters <= alert_distance_m && vertical_separation_m <= alert_altitude_m) {
                    val delay_ms = ((seconds_ahead * 1000L) - ALERT_ENTRY_POLL_LEAD_MS)
                        .coerceAtLeast(STALE_CONTACT_RETRY_MS)
                    soonest_delay_ms = soonest_delay_ms?.let { min(it, delay_ms) } ?: delay_ms
                    break
                }
            }
        }
        return soonest_delay_ms
    }

    private fun FeedAircraft.projected_alert_state(own_lat: Double, own_lon: Double, now_epoch_sec: Double): ProjectedAlertState {
        val reported_distance = if (distance_m.isFinite() && distance_m > 0.0) {
            distance_m
        } else {
            distance_meters(own_lat, own_lon, lat, lon)
        }
        val age_seconds = projection_age_seconds(now_epoch_sec)
        if (age_seconds <= ALERT_PROJECTION_MIN_SECONDS || !has_projectable_motion()) {
            return ProjectedAlertState(reported_distance, altitude_m, estimated = false)
        }
        val speed = velocity_ms ?: return ProjectedAlertState(reported_distance, altitude_m, estimated = false)
        val track = normalized_degrees(track_deg) ?: return ProjectedAlertState(reported_distance, altitude_m, estimated = false)
        val projected = advance_position(lat, lon, track, speed * age_seconds)
        val projected_altitude = projected_altitude_meters(age_seconds)
        return ProjectedAlertState(
            distance_meters = distance_meters(own_lat, own_lon, projected.lat, projected.lon),
            altitude_meters = projected_altitude,
            estimated = true
        )
    }

    private fun FeedAircraft.projected_altitude_meters(age_seconds: Double): Double? {
        val altitude = altitude_m ?: return null
        val vertical_rate = vertical_rate_ms?.takeIf { it.isFinite() && kotlin.math.abs(it) <= MAX_PROJECTABLE_VERTICAL_RATE_MS } ?: return altitude
        return altitude + vertical_rate * age_seconds
    }

    private fun FeedAircraft.projection_age_seconds(now_epoch_sec: Double): Double {
        val report_time = position_time_sec ?: last_contact_sec ?: return 0.0
        return (now_epoch_sec - report_time)
            .coerceAtLeast(0.0)
            .coerceAtMost(ALERT_PROJECTION_MAX_SECONDS.toDouble())
    }

    private fun FeedAircraft.has_fresh_projectable_motion(now_epoch_sec: Double): Boolean {
        val report_time = position_time_sec ?: last_contact_sec ?: return false
        if (now_epoch_sec - report_time > ALERT_ENTRY_LOOKAHEAD_SECONDS + ALERT_PROJECTION_MAX_SECONDS) return false
        return has_projectable_motion()
    }

    private fun FeedAircraft.has_projectable_motion(): Boolean {
        val speed = velocity_ms ?: return false
        return speed.isFinite() &&
            speed in MIN_PROJECTABLE_ALERT_SPEED_MS..MAX_PROJECTABLE_ALERT_SPEED_MS &&
            normalized_degrees(track_deg) != null
    }

    private fun advance_position(lat: Double, lon: Double, track_degrees: Double, distance_meters: Double): GeoPosition {
        val bearing = Math.toRadians(track_degrees)
        val lat_rad = Math.toRadians(lat)
        val angular = distance_meters / EARTH_RADIUS_M
        val projected_lat = lat_rad + angular * cos(bearing)
        val projected_lon = Math.toRadians(lon) + angular * sin(bearing) / max(0.1, cos(lat_rad))
        return GeoPosition(
            lat = Math.toDegrees(projected_lat).coerceIn(-90.0, 90.0),
            lon = normalize_lon_degrees(Math.toDegrees(projected_lon))
        )
    }

    private fun normalized_degrees(value: Double?): Double? {
        val degrees = value?.takeIf { it.isFinite() } ?: return null
        return ((degrees % 360.0) + 360.0) % 360.0
    }

    private fun normalize_lon_degrees(value: Double): Double {
        return ((value + 540.0) % 360.0) - 180.0
    }

    private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = EARTH_RADIUS_M
        val d_lat = Math.toRadians(lat2 - lat1)
        val d_lon = Math.toRadians(lon2 - lon1)
        val a = sin(d_lat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(d_lon / 2).pow(2.0)
        return 2 * radius * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun feet_to_meters(feet: Double): Double = feet / 3.28084

    private fun meters_to_feet(meters: Double): Double = meters * 3.28084

    private fun immutable_flag(): Int = PendingIntent.FLAG_IMMUTABLE

    private data class Bounds(val min_lat: Double, val min_lon: Double, val max_lat: Double, val max_lon: Double)

    private data class GeoPosition(val lat: Double, val lon: Double)

    private data class ProjectedAlertState(
        val distance_meters: Double,
        val altitude_meters: Double?,
        val estimated: Boolean
    )

    private data class AlertPoll(
        val aircraft: List<AlertAircraft>,
        val feed_available: Boolean,
        val unsupported_altitude_count: Int = 0,
        val next_predictive_poll_delay_ms: Long? = null
    )

    companion object {
        const val MONITORING_CHANNEL_ID = "aircraft_monitoring_status"
        const val HAZARD_CHANNEL_ID = "aircraft_range_events"
        const val PRIORITY_CHANNEL_ID = "extreme_priority_aircraft"
        const val ONGOING_NOTIFICATION_ID = 2001
        const val PRIORITY_NOTIFICATION_ID = 2002
        const val EVENT_NOTIFICATION_BASE_ID = 2100
        const val EVENT_NOTIFICATION_ID_WINDOW = 200
        const val POLL_MS = 30000L
        const val PRIORITY_CONTACT_MAX_AGE_SECONDS = 10.0
        const val STALE_CONTACT_RETRY_MS = 1000L
        const val ALERT_ENTRY_LOOKAHEAD_SECONDS = 20
        const val ALERT_ENTRY_POLL_LEAD_MS = 1200L
        const val ALERT_PROJECTION_MAX_SECONDS = 8
        const val ALERT_PROJECTION_MIN_SECONDS = 0.2
        const val MIN_PROJECTABLE_ALERT_SPEED_MS = 8.0
        const val MAX_PROJECTABLE_ALERT_SPEED_MS = 600.0
        const val MAX_PROJECTABLE_VERTICAL_RATE_MS = 120.0
        const val EARTH_RADIUS_M = 6371000.0
        const val MIN_QUERY_RADIUS_FEET = 10000.0
        const val ALERT_QUERY_MIN_PADDING_FEET = 5000.0
        const val ALERT_QUERY_RADIUS_MULTIPLIER = 1.25
        const val OWN_ALTITUDE_MAX_AGE_MS = 120000L
        const val USER_AGENT = "FlightAlertPrototype/0.1"
        val ALERT_RELEVANT_PREF_KEYS = setOf(
            FlightAlertSettings.KEY_ALERTS_ENABLED,
            FlightAlertSettings.KEY_ALERT_DISTANCE_FEET,
            FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET,
            FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED,
            FlightAlertSettings.KEY_PRIORITY_RANGE_FEET
        )

        fun start(context: Context) {
            context.startService(Intent(context, AircraftAlertService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AircraftAlertService::class.java))
        }
    }
}
