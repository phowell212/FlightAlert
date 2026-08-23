@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException


    internal fun parse(
        json: JSONArray,
        now_epoch_ms: Long,
        identity: AviationSourceIdentity
    ): AviationSourceResult<AviationOceanicTrack> {
        if (json.length() == 0) {
            return result(
                records = emptyList(),
                identity = identity,
                availability = AviationLayerAvailability.EMPTY,
                completeness = AviationLayerCompleteness.COMPLETE,
                freshness = AviationLayerFreshness.CURRENT,
                message = "No FAA NMS NAT records"
            )
        }

        val reasons = linkedSetOf<String>()
        val parts = mutableListOf<NatPartEvidence>()
        val records = mutableListOf<AviationOceanicTrack>()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index)
            if (item == null) {
                reasons += "malformed response member"
                continue
            }
            val part = parse_part(item, index, now_epoch_ms, reasons)
            parts += part
            records += part.tracks
        }

        validate_multipart(parts, reasons)
        records.groupBy { it.source_notam to it.designator }
            .values
            .filter { it.size > 1 && it.first().source_notam != null }
            .takeIf { it.isNotEmpty() }
            ?.let { reasons += "duplicate track designator" }

        if (records.isEmpty()) {
            return result(
                records = emptyList(),
                identity = identity,
                availability = AviationLayerAvailability.UNAVAILABLE,
                completeness = AviationLayerCompleteness.UNKNOWN,
                freshness = AviationLayerFreshness.STALE,
                message = (reasons + "no honest NAT track record").joinToString("; ")
            )
        }
        for (track in records) {
            if (
                track.temporal_state == AviationNatTemporalState.UNKNOWN ||
                track.waypoints.isEmpty() ||
                track.waypoints.any { it !is AviationNatWaypoint.Coordinate } ||
                track.drawable_segments.isEmpty()
            ) {
                reasons += "unresolved or incomplete track"
            }
        }
        return result(
            records = records,
            identity = identity,
            availability = AviationLayerAvailability.USABLE,
            completeness = if (reasons.isEmpty()) {
                AviationLayerCompleteness.COMPLETE
            } else {
                AviationLayerCompleteness.PARTIAL
            },
            freshness = AviationLayerFreshness.CURRENT,
            message = if (reasons.isEmpty()) {
                "Complete FAA NMS NAT response"
            } else {
                reasons.joinToString("; ")
            }
        )
    }

    private fun parse_part(
        item: JSONObject,
        source_index: Int,
        now_epoch_ms: Long,
        reasons: MutableSet<String>
    ): NatPartEvidence {
        val source_notam = item.strict_string("notam_number_formatted")
        val icao_id = item.strict_string("icao_id")
        val part_number = item.opt("part_no")
            .exact_aviation_object_id_or_null()
            ?.takeIf { it in 1..MAX_NAT_PARTS.toLong() }
            ?.toInt()
        val start_datetime = item.strict_string("start_datetime")
        val end_datetime = item.strict_string("end_datetime")
        val entry_datetime = item.strict_string("entry_datetime")
        val last_updated = item.strict_string("last_updated")
        val condition = item.strict_string("condition_message")
        val fields_valid = source_notam != null &&
            icao_id != null &&
            part_number != null &&
            start_datetime != null &&
            end_datetime != null &&
            entry_datetime != null &&
            last_updated != null &&
            condition != null
        if (!fields_valid) reasons += "malformed source field"

        val lines = condition?.split(Regex("\\r?\\n")) ?: emptyList()
        val first_nonblank = lines.firstOrNull { it.isNotBlank() }
        val header = first_nonblank?.trim()?.let(::parse_header)
        if (header == null) reasons += "multipart evidence incomplete"
        val time = parse_window(start_datetime, end_datetime, now_epoch_ms)
        if (time.temporal_state == AviationNatTemporalState.UNKNOWN) {
            reasons += "invalid NAT time window"
        }
        val tracks = mutableListOf<AviationOceanicTrack>()
        if (condition != null) {
            for (raw_line in lines) {
                val trimmed = raw_line.trim()
                if (trimmed.isEmpty()) continue
                val tokens = trimmed.split(WHITESPACE)
                val designator = tokens.firstOrNull()?.takeIf(TRACK_DESIGNATOR::matches) ?: continue
                val waypoints = tokens.drop(1).map(::parse_waypoint)
                val segments = drawable_segments(waypoints)
                tracks += AviationOceanicTrack(
                    designator = designator,
                    source_notam = source_notam,
                    icao_id = icao_id,
                    part_number = part_number,
                    declared_part_number = header?.part_number,
                    declared_part_count = header?.part_count,
                    start_datetime = start_datetime,
                    end_datetime = end_datetime,
                    entry_datetime = entry_datetime,
                    last_updated = last_updated,
                    raw_track_line = raw_line,
                    start_epoch_ms = time.start_epoch_ms,
                    end_epoch_ms = time.end_epoch_ms,
                    temporal_state = time.temporal_state,
                    waypoints = waypoints,
                    drawable_segments = segments
                )
            }
        }
        if (tracks.isEmpty()) reasons += "source part has no track record"
        return NatPartEvidence(
            source_index = source_index,
            source_notam = source_notam,
            icao_id = icao_id,
            part_number = part_number,
            declared_part_number = header?.part_number,
            declared_part_count = header?.part_count,
            start_datetime = start_datetime,
            end_datetime = end_datetime,
            fields_valid = fields_valid,
            tracks = tracks
        )
    }

    private fun validate_multipart(
        parts: List<NatPartEvidence>,
        reasons: MutableSet<String>
    ) {
        val groups = parts.groupBy { it.source_notam ?: "__missing_notam_${it.source_index}" }
        for (group in groups.values) {
            val declared_counts = group.mapNotNull(NatPartEvidence::declared_part_count).toSet()
            val declared_count = declared_counts.singleOrNull()
            val part_numbers = group.mapNotNull(NatPartEvidence::part_number)
            val header_numbers = group.mapNotNull(NatPartEvidence::declared_part_number)
            val complete_parts = declared_count != null &&
                group.all(NatPartEvidence::fields_valid) &&
                group.all { it.declared_part_count == declared_count } &&
                group.all { it.part_number == it.declared_part_number } &&
                part_numbers.size == group.size &&
                header_numbers.size == group.size &&
                part_numbers.size == part_numbers.toSet().size &&
                part_numbers.size == declared_count &&
                part_numbers.toSet() == (1..declared_count).toSet()
            val consistent_identity = group.map(NatPartEvidence::icao_id).toSet().size == 1 &&
                group.map(NatPartEvidence::start_datetime).toSet().size == 1 &&
                group.map(NatPartEvidence::end_datetime).toSet().size == 1
            if (!complete_parts || !consistent_identity) {
                reasons += "multipart evidence incomplete or conflicting"
            }
        }
    }

    private fun parse_header(line: String): NatHeader? {
        val match = NAT_HEADER.find(line) ?: return null
        val part = match.groupValues[1].toLongOrNull()
        val count = match.groupValues[2].toLongOrNull()
        if (part == null || count == null || part !in 1..MAX_NAT_PARTS || count !in 1..MAX_NAT_PARTS) {
            return null
        }
        if (part > count) return null
        return NatHeader(part.toInt(), count.toInt())
    }

    private fun parse_window(
        start_raw: String?,
        end_raw: String?,
        now_epoch_ms: Long
    ): NatWindow {
        val start = parse_utc(start_raw)
        val end = parse_utc(end_raw)
        if (start == null || end == null || !start.isBefore(end)) {
            return NatWindow(null, null, AviationNatTemporalState.UNKNOWN)
        }
        val start_ms = try {
            start.toEpochMilli()
        } catch (_: ArithmeticException) {
            return NatWindow(null, null, AviationNatTemporalState.UNKNOWN)
        }
        val end_ms = try {
            end.toEpochMilli()
        } catch (_: ArithmeticException) {
            return NatWindow(null, null, AviationNatTemporalState.UNKNOWN)
        }
        val state = when {
            now_epoch_ms < start_ms -> AviationNatTemporalState.UPCOMING
            now_epoch_ms >= end_ms -> AviationNatTemporalState.EXPIRED
            else -> AviationNatTemporalState.ACTIVE
        }
        return NatWindow(start_ms, end_ms, state)
    }

    private fun parse_utc(raw: String?): Instant? {
        if (raw == null || !raw.endsWith('Z')) return null
        return try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parse_waypoint(raw: String): AviationNatWaypoint {
        val coordinate = parse_coordinate(raw)
        if (coordinate != null) return AviationNatWaypoint.Coordinate(raw, coordinate)
        val normalized = raw.uppercase().trimEnd(',', '.', ';')
        return if (NAMED_FIX.matches(normalized)) {
            AviationNatWaypoint.NamedFix(raw)
        } else {
            AviationNatWaypoint.Unrecognized(raw)
        }
    }

    private fun parse_coordinate(raw: String): AviationLayerPoint? {
        if (raw.count { it == '/' } != 1) return null
        val latitude_token = raw.substringBefore('/')
        val longitude_token = raw.substringAfter('/')
        if (!latitude_token.all { it in '0'..'9' } || !longitude_token.all { it in '0'..'9' }) return null
        val latitude = parse_coordinate_side(latitude_token, max_degrees = 90)
            ?: return null
        val longitude = parse_coordinate_side(longitude_token, max_degrees = 180)
            ?: return null
        return AviationLayerPoint(latitude, -longitude)
    }

    private fun parse_coordinate_side(
        raw: String,
        max_degrees: Int
    ): Double? {
        if (raw.length != 2 && raw.length != 4) return null
        val degrees = raw.take(2).toIntOrNull() ?: return null
        val minutes = raw.drop(2).takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0
        if (degrees !in 0..max_degrees || minutes !in 0..59) return null
        if (degrees == max_degrees && minutes != 0) return null
        return degrees + minutes / 60.0
    }

    private fun drawable_segments(
        waypoints: List<AviationNatWaypoint>
    ): List<List<AviationLayerPoint>> {
        val segments = mutableListOf<List<AviationLayerPoint>>()
        val run = mutableListOf<AviationLayerPoint>()
        fun flush() {
            if (run.size >= 2) segments += run.toList()
            run.clear()
        }
        for (waypoint in waypoints) {
            if (waypoint is AviationNatWaypoint.Coordinate) {
                run += waypoint.point
            } else {
                flush()
            }
        }
        flush()
        return segments
    }

    private fun result(
        records: List<AviationOceanicTrack>,
        identity: AviationSourceIdentity,
        availability: AviationLayerAvailability,
        completeness: AviationLayerCompleteness,
        freshness: AviationLayerFreshness,
        message: String
    ): AviationSourceResult<AviationOceanicTrack> = AviationSourceResult(
        records = records,
        identity = identity,
        health = AviationLayerHealth(availability, completeness, freshness),
        message = message
    )

    private fun JSONObject.strict_string(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return (opt(key) as? String)?.takeIf { it.isNotBlank() }
    }

    private data class NatPartEvidence(
        val source_index: Int,
        val source_notam: String?,
        val icao_id: String?,
        val part_number: Int?,
        val declared_part_number: Int?,
        val declared_part_count: Int?,
        val start_datetime: String?,
        val end_datetime: String?,
        val fields_valid: Boolean,
        val tracks: List<AviationOceanicTrack>
    )

    private data class NatHeader(val part_number: Int, val part_count: Int)
    private data class NatWindow(
        val start_epoch_ms: Long?,
        val end_epoch_ms: Long?,
        val temporal_state: AviationNatTemporalState
    )

    private const val MAX_NAT_PARTS = 1_000
    private val NAT_HEADER = Regex("^NAT-(\\d+)/(\\d+)(?:\\s|$)")
    private val TRACK_DESIGNATOR = Regex("^[A-Z]$")
    private val NAMED_FIX = Regex("^[A-Z][A-Z0-9]{1,7}$")
    private val WHITESPACE = Regex("\\s+")
