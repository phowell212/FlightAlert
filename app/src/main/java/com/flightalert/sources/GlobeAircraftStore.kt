@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.sources

import android.location.Location
import com.flightalert.FlightAlertAppSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.nextDown


internal fun normalize_globe_search_key(value: String?): String? {
    return value
        ?.uppercase(Locale.US)
        ?.filter { it.isLetterOrDigit() || it == '-' }
        ?.takeIf { it.isNotBlank() }
}

private fun FeedAircraft.matches_normalized_search(search: String): Boolean {
    return normalize_globe_search_key(callsign)?.contains(search) == true ||
            normalize_globe_search_key(registration)?.contains(search) == true ||
            normalize_globe_search_key(icao24)?.contains(search) == true ||
            normalize_globe_search_key(type_code)?.contains(search) == true
}

private fun FeedAircraft.matches_normalized_exact_key(search: String): Boolean {
    return normalize_globe_search_key(icao24) == search ||
            normalize_globe_search_key(callsign) == search ||
            normalize_globe_search_key(registration) == search
}


internal class GlobeAircraftStore(
    val aircraft: List<FeedAircraft>,
    private val display_projection_bands: ByteArray,
    private val alert_projection_bands: ByteArray,
    private val maximum_display_projection_band: Int,
    private val maximum_alert_projection_band: Int
) {
    internal data class QueryResult(
        val aircraft: List<FeedAircraft>,
        val motion_candidates: List<FeedAircraft>,
        val airborne_total: Int,
        val nearest_airborne: FeedAircraft?
    )

    internal fun query(
        display_bounds: List<GlobeQueryBounds>,
        safety_bounds: GlobeQueryBounds?,
        normalized_search: String?,
        selected_aircraft_key: String?,
        own_lat: Double,
        own_lon: Double
    ): QueryResult {
        val selected = ArrayList<FeedAircraft>()
        val motion_candidates = ArrayList<FeedAircraft>()
        val display_bounds_by_band =
            Array(display_bounds.size) {
                arrayOfNulls<GlobeQueryBounds>(maximum_display_projection_band + 1)
            }
        val alert_bounds_by_band =
            safety_bounds?.let {
                arrayOfNulls<GlobeQueryBounds>(maximum_alert_projection_band + 1)
            }
        val distance_result = FloatArray(1)
        var airborne_total = 0
        var nearest_airborne: FeedAircraft? = null
        var nearest_distance_meters = Double.POSITIVE_INFINITY
        aircraft.forEachIndexed { index, item ->
            if (item.on_ground == false) {
                airborne_total++
                val latitude_distance_floor =
                    abs(item.lat - own_lat) * MINIMUM_METERS_PER_LATITUDE_DEGREE
                if (latitude_distance_floor < nearest_distance_meters) {
                    Location.distanceBetween(
                        own_lat,
                        own_lon,
                        item.lat,
                        item.lon,
                        distance_result
                    )
                    val distance_meters = distance_result[0].toDouble()
                    if (distance_meters < nearest_distance_meters) {
                        nearest_airborne = item
                        nearest_distance_meters = distance_meters
                    }
                }
            }

            var raw_authoritative = false
            var bounds_index = 0
            while (bounds_index < display_bounds.size) {
                if (display_bounds[bounds_index].contains(item.lat, item.lon)) {
                    raw_authoritative = true
                    break
                }
                bounds_index++
            }
            if (!raw_authoritative && safety_bounds?.contains(item.lat, item.lon) == true) {
                raw_authoritative = true
            }
            if (!raw_authoritative && selected_aircraft_key != null &&
                item.matches_normalized_exact_key(selected_aircraft_key)
            ) {
                raw_authoritative = true
            }
            if (raw_authoritative ||
                normalized_search != null && item.matches_normalized_search(normalized_search)
            ) {
                selected += item.copy(
                    distance_m = globe_distance_meters(own_lat, own_lon, item.lat, item.lon)
                )
            }
            if (!raw_authoritative) {
                val display_band = display_projection_bands[index].toInt() and 0xff
                var may_project_into_display = false
                if (display_band != 0) {
                    bounds_index = 0
                    while (bounds_index < display_bounds.size) {
                        if (bounds_for_projection_band(
                                display_bounds[bounds_index],
                                display_bounds_by_band[bounds_index],
                                display_band
                            ).contains(item.lat, item.lon)
                        ) {
                            may_project_into_display = true
                            break
                        }
                        bounds_index++
                    }
                }
                val alert_band = alert_projection_bands[index].toInt() and 0xff
                val may_project_into_safety =
                    safety_bounds != null &&
                            alert_bounds_by_band != null &&
                            alert_band != 0 &&
                            bounds_for_projection_band(
                                safety_bounds,
                                alert_bounds_by_band,
                                alert_band
                            ).contains(item.lat, item.lon)
                if (may_project_into_display || may_project_into_safety) {
                    motion_candidates += item
                }
            }
        }
        selected.sortWith(distance_order)
        return QueryResult(
            aircraft = selected,
            motion_candidates = motion_candidates,
            airborne_total = airborne_total,
            nearest_airborne = nearest_airborne
        )
    }

    companion object {
        private val distance_order = Comparator<FeedAircraft> { left, right ->
            left.distance_m.compareTo(right.distance_m)
        }

        internal fun display_projection_band(
            item: FeedAircraft,
            projection_valid_until: Double
        ): Int {
            return projection_band(
                display_projection_reach_meters(item, projection_valid_until)
            )
        }

        internal fun alert_projection_band(
            item: FeedAircraft,
            projection_valid_until: Double
        ): Int {
            return projection_band(
                alert_projection_reach_meters(item, projection_valid_until)
            )
        }

        private fun display_projection_reach_meters(
            item: FeedAircraft,
            projection_valid_until: Double
        ): Double {
            if (item.on_ground == true || !projection_valid_until.isFinite()) return 0.0
            val speed = item.velocity_ms?.takeIf {
                it.isFinite() &&
                        it > MIN_DISPLAY_PROJECTION_SPEED_MS &&
                        it <= MAX_DISPLAY_PROJECTION_SPEED_MS
            } ?: return 0.0
            if (item.track_deg?.isFinite() != true) return 0.0
            val position_time =
                item.position_time_sec?.takeIf { it.isFinite() } ?: return 0.0
            val raw_elapsed = projection_valid_until - position_time
            if (!raw_elapsed.isFinite()) return 0.0
            return speed * raw_elapsed.coerceAtLeast(0.0)
        }

        private fun alert_projection_reach_meters(
            item: FeedAircraft,
            projection_valid_until: Double
        ): Double {
            if (!projection_valid_until.isFinite()) return 0.0
            val speed = item.velocity_ms?.takeIf {
                it.isFinite() &&
                        it in FlightAlertAppSettings.AlertProjection.MIN_PROJECTABLE_SPEED_MS..
                        FlightAlertAppSettings.AlertProjection.MAX_PROJECTABLE_SPEED_MS
            } ?: return 0.0
            if (item.track_deg?.isFinite() != true) return 0.0
            val report_time = item.position_time_sec ?: item.last_contact_sec ?: return 0.0
            val raw_age = projection_valid_until - report_time
            val age = when {
                raw_age.isNaN() -> 0.0
                raw_age <= 0.0 -> 0.0
                raw_age >= FlightAlertAppSettings.AlertProjection.MAX_SECONDS ->
                    FlightAlertAppSettings.AlertProjection.MAX_SECONDS
                else -> raw_age
            }
            if (age <= FlightAlertAppSettings.AlertProjection.MIN_SECONDS) return 0.0
            return speed * age
        }

        private fun projection_band(distance_meters: Double): Int {
            if (!(distance_meters > 0.0)) return 0
            if (!distance_meters.isFinite()) return FULL_GLOBE_PROJECTION_BAND
            val rounded_exponent =
                (Math.getExponent(distance_meters.nextDown()) + 1).coerceAtLeast(0)
            return if (rounded_exponent >= FULL_GLOBE_PROJECTION_BAND - 1) {
                FULL_GLOBE_PROJECTION_BAND
            } else {
                rounded_exponent + 1
            }
        }

        private fun bounds_for_projection_band(
            bounds: GlobeQueryBounds,
            cached_bounds: Array<GlobeQueryBounds?>,
            band: Int
        ): GlobeQueryBounds {
            return cached_bounds[band]
                ?: bounds.expanded_by_distance_meters(
                    if (band == FULL_GLOBE_PROJECTION_BAND) {
                        Double.POSITIVE_INFINITY
                    } else {
                        Math.scalb(1.0, band - 1)
                    }
                ).also { cached_bounds[band] = it }
        }

        private const val FULL_GLOBE_PROJECTION_BAND = 0xff
        private const val MINIMUM_METERS_PER_LATITUDE_DEGREE = 110_000.0
        private const val MIN_DISPLAY_PROJECTION_SPEED_MS = 0.5
        private const val MAX_DISPLAY_PROJECTION_SPEED_MS = 1_200.0
    }
}
