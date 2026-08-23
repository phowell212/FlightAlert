@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft


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
        get() = source_type != null ||
                squawk != null ||
                baro_altitude_m != null ||
                geom_altitude_m != null ||
                ground_speed_ms != null ||
                true_speed_ms != null ||
                indicated_speed_ms != null ||
                mach != null ||
                baro_rate_ms != null ||
                geom_rate_ms != null ||
                selected_altitude_m != null ||
                selected_heading_deg != null ||
                wind_speed_ms != null ||
                wind_direction_deg != null ||
                tat_c != null ||
                oat_c != null ||
                qnh_hpa != null ||
                true_heading_deg != null ||
                magnetic_heading_deg != null ||
                magnetic_declination_deg != null ||
                track_rate_deg_per_sec != null ||
                roll_deg != null ||
                adsb_version != null ||
                nac_p != null ||
                nac_v != null ||
                sil != null ||
                sil_type != null ||
                nic_baro != null ||
                rc_m != null ||
                rssi != null ||
                message_rate != null ||
                receiver_count_label != null ||
                category_label != null ||
                nav_modes.isNotEmpty()

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
            magnetic_declination_deg = magnetic_declination_deg
                ?: fallback.magnetic_declination_deg,
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
