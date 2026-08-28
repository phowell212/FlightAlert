@file:JvmName("PriorityNotificationSnapshotSupportKt")
@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.alerts

import com.flightalert.aircraft.AlertAircraft
import kotlin.math.roundToInt


@Suppress("ArrayInDataClass")
data class PrioritySnapshotPayload(
    val signature: String,
    val icao: Array<String>,
    val callsign: Array<String>,
    val registration: Array<String>,
    val distance_feet: DoubleArray,
    val altitude_feet: DoubleArray,
    val vertical_feet: DoubleArray,
    val contact_age_seconds: DoubleArray,
    val estimated: BooleanArray
)


internal fun fresh_priority_snapshot(
    priority_aircraft: List<AlertAircraft>,
    elapsed_since_snapshot_ms: Long,
    max_contact_age_seconds: Double
): List<AlertAircraft> {
    val elapsed_seconds = elapsed_since_snapshot_ms.coerceAtLeast(0L) / 1000.0
    return priority_aircraft.filter {
        it.is_extreme_priority &&
                it.contact_age_seconds + elapsed_seconds <= max_contact_age_seconds
    }
}


internal fun priority_snapshot_expiry_delay_ms(
    priority_aircraft: List<AlertAircraft>,
    elapsed_since_snapshot_ms: Long,
    max_contact_age_seconds: Double
): Long? = priority_snapshot_expiry_delay_ms(
    priority_aircraft.asSequence()
        .filter { it.is_extreme_priority }
        .map { it.contact_age_seconds },
    elapsed_since_snapshot_ms,
    max_contact_age_seconds
)


internal fun priority_snapshot_expiry_delay_ms(
    contact_age_seconds: DoubleArray,
    elapsed_since_snapshot_ms: Long,
    max_contact_age_seconds: Double
): Long? = priority_snapshot_expiry_delay_ms(
    contact_age_seconds.asSequence(),
    elapsed_since_snapshot_ms,
    max_contact_age_seconds
)


private fun priority_snapshot_expiry_delay_ms(
    contact_age_seconds: Sequence<Double>,
    elapsed_since_snapshot_ms: Long,
    max_contact_age_seconds: Double
): Long? {
    val elapsed_seconds = elapsed_since_snapshot_ms.coerceAtLeast(0L) / 1000.0
    val seconds_until_expiry = contact_age_seconds
        .map { max_contact_age_seconds - it - elapsed_seconds }
        .filter { it >= 0.0 }
        .minOrNull() ?: return null
    return (seconds_until_expiry * 1000.0).toLong() + 1L
}


internal fun should_apply_priority_snapshot_expiry(
    scheduled_generation: Long,
    current_generation: Long,
    stopped: Boolean
): Boolean = !stopped && scheduled_generation == current_generation


internal fun should_publish_priority_snapshot(
    signature: String,
    last_signature: String?,
    force: Boolean,
    now_elapsed_ms: Long,
    last_publish_elapsed_ms: Long,
    refresh_interval_ms: Long
): Boolean {
    if (force || signature != last_signature) return true
    return signature.isNotEmpty() &&
            now_elapsed_ms - last_publish_elapsed_ms >= refresh_interval_ms.coerceAtLeast(0L)
}


internal fun should_refresh_priority_snapshot_before_expiry(
    now_elapsed_ms: Long,
    last_expiry_elapsed_ms: Long,
    refresh_lead_ms: Long
): Boolean = now_elapsed_ms >= last_expiry_elapsed_ms - refresh_lead_ms.coerceAtLeast(0L)


fun prepare_priority_snapshot(
    priority_aircraft: List<AlertAircraft>
): PrioritySnapshotPayload {
    val size = priority_aircraft.size
    val icao = Array(size) { "" }
    val callsign = Array(size) { "" }
    val registration = Array(size) { "" }
    val distance_feet = DoubleArray(size)
    val altitude_feet = DoubleArray(size)
    val vertical_feet = DoubleArray(size)
    val contact_age_seconds = DoubleArray(size)
    val estimated = BooleanArray(size)
    val signature = StringBuilder(size * 24)
    priority_aircraft.forEachIndexed { index, aircraft ->
        icao[index] = aircraft.icao24
        callsign[index] = aircraft.callsign
        registration[index] = aircraft.registration.orEmpty()
        distance_feet[index] = aircraft.distance_feet
        altitude_feet[index] = aircraft.altitude_feet
        vertical_feet[index] = aircraft.vertical_separation_feet ?: Double.NaN
        contact_age_seconds[index] = aircraft.contact_age_seconds
        estimated[index] = aircraft.is_estimated_position
        if (index > 0) signature.append('|')
        signature.append(aircraft.icao24)
            .append(':')
            .append(aircraft.registration.orEmpty())
            .append(':')
            .append(aircraft.altitude_feet.roundToInt())
            .append(':')
            .append(aircraft.is_estimated_position)
    }
    return PrioritySnapshotPayload(
        signature = signature.toString(),
        icao = icao,
        callsign = callsign,
        registration = registration,
        distance_feet = distance_feet,
        altitude_feet = altitude_feet,
        vertical_feet = vertical_feet,
        contact_age_seconds = contact_age_seconds,
        estimated = estimated
    )
}
