package com.flightalert.data

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class FeedBounds(val min_lat: Double, val min_lon: Double, val max_lat: Double, val max_lon: Double) {
    fun normalized(): FeedBounds {
        return FeedBounds(
            min_lat = min(min_lat, max_lat).coerceIn(-90.0, 90.0),
            min_lon = min(min_lon, max_lon).coerceIn(-180.0, 180.0),
            max_lat = max(min_lat, max_lat).coerceIn(-90.0, 90.0),
            max_lon = max(min_lon, max_lon).coerceIn(-180.0, 180.0)
        )
    }
}

data class FeedResult(
    val status: FeedStatus,
    val source: FeedSource,
    val aircraft: List<FeedAircraft> = emptyList(),
    val epoch_sec: Double? = null,
    val http_code: Int? = null,
    val query_count: Int = 1,
    val partial_coverage: Boolean = false
)

data class FeedAircraft(
    val icao24: String,
    val callsign: String,
    val registration: String?,
    val type_code: String?,
    val metadata: AircraftMetadataSeed? = null,
    val db_flags: Int?,
    val lat: Double,
    val lon: Double,
    val on_ground: Boolean?,
    val altitude_m: Double?,
    val velocity_ms: Double?,
    val track_deg: Double?,
    val vertical_rate_ms: Double?,
    val category: Int?,
    val position_time_sec: Double?,
    val last_contact_sec: Double?,
    val distance_m: Double,
    val telemetry: AircraftTelemetry? = null
) {
    fun feed_key(): String {
        val hex = icao24.trim().lowercase(Locale.US)
        if (hex.isNotBlank()) return "hex:$hex"
        registration?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { return "reg:$it" }
        callsign.trim().uppercase(Locale.US).takeIf { it.isNotBlank() }?.let { return "call:$it" }
        return "pos:${"%.4f".format(Locale.US, lat)}:${"%.4f".format(Locale.US, lon)}"
    }
}

data class AircraftTelemetry(
    val source_type: String? = null,
    val squawk: String? = null,
    val baro_altitude_m: Double? = null,
    val geom_altitude_m: Double? = null,
    val ground_speed_ms: Double? = null,
    val true_speed_ms: Double? = null,
    val indicated_speed_ms: Double? = null,
    val mach: Double? = null,
    val baro_rate_ms: Double? = null,
    val geom_rate_ms: Double? = null,
    val selected_altitude_m: Double? = null,
    val selected_heading_deg: Double? = null,
    val wind_speed_ms: Double? = null,
    val wind_direction_deg: Double? = null,
    val tat_c: Double? = null,
    val oat_c: Double? = null,
    val qnh_hpa: Double? = null,
    val true_heading_deg: Double? = null,
    val magnetic_heading_deg: Double? = null,
    val magnetic_declination_deg: Double? = null,
    val track_rate_deg_per_sec: Double? = null,
    val roll_deg: Double? = null,
    val nav_modes: List<String> = emptyList(),
    val adsb_version: String? = null,
    val nac_p: Int? = null,
    val nac_v: Int? = null,
    val sil: Int? = null,
    val sil_type: String? = null,
    val nic_baro: String? = null,
    val rc_m: Double? = null,
    val rssi: Double? = null,
    val message_rate: Double? = null,
    val receiver_count_label: String? = null,
    val category_label: String? = null
) {
    val has_values: Boolean
        get() = listOf(
            source_type,
            squawk,
            baro_altitude_m,
            geom_altitude_m,
            ground_speed_ms,
            true_speed_ms,
            indicated_speed_ms,
            mach,
            baro_rate_ms,
            geom_rate_ms,
            selected_altitude_m,
            selected_heading_deg,
            wind_speed_ms,
            wind_direction_deg,
            tat_c,
            oat_c,
            qnh_hpa,
            true_heading_deg,
            magnetic_heading_deg,
            magnetic_declination_deg,
            track_rate_deg_per_sec,
            roll_deg,
            adsb_version,
            nac_p,
            nac_v,
            sil,
            sil_type,
            nic_baro,
            rc_m,
            rssi,
            message_rate,
            receiver_count_label,
            category_label
        ).any { it != null } || nav_modes.isNotEmpty()

    fun with_fallback(fallback: AircraftTelemetry?): AircraftTelemetry {
        fallback ?: return this
        return copy(
            source_type = source_type ?: fallback.source_type,
            squawk = squawk ?: fallback.squawk,
            baro_altitude_m = baro_altitude_m ?: fallback.baro_altitude_m,
            geom_altitude_m = geom_altitude_m ?: fallback.geom_altitude_m,
            ground_speed_ms = ground_speed_ms ?: fallback.ground_speed_ms,
            true_speed_ms = true_speed_ms ?: fallback.true_speed_ms,
            indicated_speed_ms = indicated_speed_ms ?: fallback.indicated_speed_ms,
            mach = mach ?: fallback.mach,
            baro_rate_ms = baro_rate_ms ?: fallback.baro_rate_ms,
            geom_rate_ms = geom_rate_ms ?: fallback.geom_rate_ms,
            selected_altitude_m = selected_altitude_m ?: fallback.selected_altitude_m,
            selected_heading_deg = selected_heading_deg ?: fallback.selected_heading_deg,
            wind_speed_ms = wind_speed_ms ?: fallback.wind_speed_ms,
            wind_direction_deg = wind_direction_deg ?: fallback.wind_direction_deg,
            tat_c = tat_c ?: fallback.tat_c,
            oat_c = oat_c ?: fallback.oat_c,
            qnh_hpa = qnh_hpa ?: fallback.qnh_hpa,
            true_heading_deg = true_heading_deg ?: fallback.true_heading_deg,
            magnetic_heading_deg = magnetic_heading_deg ?: fallback.magnetic_heading_deg,
            magnetic_declination_deg = magnetic_declination_deg ?: fallback.magnetic_declination_deg,
            track_rate_deg_per_sec = track_rate_deg_per_sec ?: fallback.track_rate_deg_per_sec,
            roll_deg = roll_deg ?: fallback.roll_deg,
            nav_modes = nav_modes.ifEmpty { fallback.nav_modes },
            adsb_version = adsb_version ?: fallback.adsb_version,
            nac_p = nac_p ?: fallback.nac_p,
            nac_v = nac_v ?: fallback.nac_v,
            sil = sil ?: fallback.sil,
            sil_type = sil_type ?: fallback.sil_type,
            nic_baro = nic_baro ?: fallback.nic_baro,
            rc_m = rc_m ?: fallback.rc_m,
            rssi = rssi ?: fallback.rssi,
            message_rate = message_rate ?: fallback.message_rate,
            receiver_count_label = receiver_count_label ?: fallback.receiver_count_label,
            category_label = category_label ?: fallback.category_label
        )
    }
}

data class AircraftMetadataSeed(
    val source_name: String,
    val registration: String? = null,
    val manufacturer: String? = null,
    val type: String? = null,
    val type_code: String? = null,
    val owner: String? = null,
    val manufactured_year: String? = null,
    val operator_code: String? = null
) {
    val has_details: Boolean
        get() = listOf(registration, manufacturer, type, type_code, owner, manufactured_year, operator_code)
            .any { !it.isNullOrBlank() }
}

enum class FeedStatus {
    OK,
    RATE_LIMITED,
    UNAVAILABLE
}

enum class FeedSource(val display_name: String) {
    OPENSKY("OpenSky"),
    AIRPLANES_LIVE("Airplanes.Live"),
    AIRPLANES_LIVE_GLOBE("Airplanes.Live binCraft globe feed"),
    HYBRID("Hybrid feed"),
    COMBINED("OpenSky + Airplanes.Live")
}
