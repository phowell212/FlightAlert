@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


internal object AviationSelectionPolicy {
    fun resolve(
        key: AviationSelectionKey,
        snapshot: AviationLayerSnapshot
    ): AviationSelection? = when (key) {
        is AviationSelectionKey.ArcGis -> when (key.kind) {
            AviationLayerKind.ATC_BOUNDARIES -> snapshot.atc_boundaries
                .firstOrNull { it.object_id == key.object_id }
                ?.let(AviationSelection::AtcBoundary)
            AviationLayerKind.RESTRICTED_AIRSPACES -> snapshot.restricted_airspaces
                .firstOrNull { it.object_id == key.object_id }
                ?.let(AviationSelection::SpecialUse)
            AviationLayerKind.AIRPORTS -> snapshot.airports
                .firstOrNull { it.object_id == key.object_id }
                ?.let(AviationSelection::Airport)
            AviationLayerKind.OCEANIC_TRACKS -> null
        }
        is AviationSelectionKey.Nat -> snapshot.oceanic_tracks
            .firstOrNull { it.selection_key() == key }
            ?.let(AviationSelection::OceanicTrack)
    }

    fun after_toggle(
        selected_key: AviationSelectionKey?,
        changed_kind: AviationLayerKind,
        enabled: Boolean
    ): AviationSelectionKey? {
        return if (!enabled && selected_key?.kind == changed_kind) null else selected_key
    }

    fun layer_is_eligible(
        kind: AviationLayerKind,
        enabled: Boolean,
        status: AviationLayerStatus?,
        publication: AviationLayerPublication?
    ): Boolean {
        if (!enabled) return false
        return AviationSelectionPresentationPolicy.provenance(kind, status, publication).state !=
            AviationProvenanceState.UNAVAILABLE
    }

    fun airport_is_eligible(
        enabled: Boolean,
        zoom: Double,
        status: AviationLayerStatus?,
        publication: AviationLayerPublication?
    ): Boolean = zoom.isFinite() &&
        zoom >= AVIATION_AIRPORT_LABEL_MIN_ZOOM &&
        layer_is_eligible(AviationLayerKind.AIRPORTS, enabled, status, publication)

    fun nat_is_eligible(
        enabled: Boolean,
        track: AviationOceanicTrack,
        now_epoch_ms: Long,
        status: AviationLayerStatus?,
        publication: AviationLayerPublication?
    ): Boolean {
        if (!layer_is_eligible(
                AviationLayerKind.OCEANIC_TRACKS,
                enabled,
                status,
                publication
            )
        ) return false
        val start = track.start_epoch_ms ?: return false
        val end = track.end_epoch_ms ?: return false
        return start < end &&
            now_epoch_ms >= start &&
            now_epoch_ms < end &&
            track.drawable_segments.any { it.size >= 2 }
    }

    fun hit_radius_dp(kind: AviationHitKind): Double? = when (kind) {
        AviationHitKind.AIRPORT -> 20.0
        AviationHitKind.NAT_SEGMENT -> 16.0
        AviationHitKind.SPECIAL_USE_BOUNDARY -> 18.0
        AviationHitKind.ATC_BOUNDARY -> 14.0
        AviationHitKind.SPECIAL_USE_INTERIOR -> null
    }

    fun choose_hit(candidates: List<AviationHitCandidate>): AviationSelectionKey? {
        val valid_candidates = candidates.filter(::candidate_kind_matches_key)
        val nearest = valid_candidates.mapNotNull { candidate ->
            val radius = hit_radius_dp(candidate.kind) ?: return@mapNotNull null
            val distance_squared = candidate.distance_squared_dp ?: return@mapNotNull null
            if (!distance_squared.isFinite() || distance_squared < 0.0) return@mapNotNull null
            val radius_squared = radius * radius
            if (distance_squared > radius_squared) return@mapNotNull null
            RankedHit(candidate, distance_squared / radius_squared)
        }.minWithOrNull { first, second ->
            compare_ranked_hits(first, second)
        }
        if (nearest != null) return nearest.candidate.key

        return valid_candidates.asSequence()
            .filter { it.kind == AviationHitKind.SPECIAL_USE_INTERIOR }
            .filter { it.containment_area?.isFinite() == true && it.containment_area >= 0.0 }
            .minWithOrNull { first, second ->
                val area_order = first.containment_area!!.compareTo(second.containment_area!!)
                if (area_order != 0) area_order else compare_keys(first.key, second.key)
            }
            ?.key
    }

    private fun candidate_kind_matches_key(candidate: AviationHitCandidate): Boolean =
        when (candidate.kind) {
            AviationHitKind.AIRPORT -> candidate.key.kind == AviationLayerKind.AIRPORTS
            AviationHitKind.NAT_SEGMENT -> candidate.key.kind == AviationLayerKind.OCEANIC_TRACKS
            AviationHitKind.SPECIAL_USE_BOUNDARY,
            AviationHitKind.SPECIAL_USE_INTERIOR ->
                candidate.key.kind == AviationLayerKind.RESTRICTED_AIRSPACES
            AviationHitKind.ATC_BOUNDARY ->
                candidate.key.kind == AviationLayerKind.ATC_BOUNDARIES
        }

    private fun compare_ranked_hits(first: RankedHit, second: RankedHit): Int {
        val priority_order = hit_priority(first.candidate.kind).compareTo(
            hit_priority(second.candidate.kind)
        )
        if (priority_order != 0) return priority_order
        val distance_order = first.normalized_distance.compareTo(second.normalized_distance)
        if (distance_order != 0) return distance_order
        return compare_keys(first.candidate.key, second.candidate.key)
    }

    private fun hit_priority(kind: AviationHitKind): Int = when (kind) {
        AviationHitKind.AIRPORT -> 0
        AviationHitKind.NAT_SEGMENT -> 1
        AviationHitKind.SPECIAL_USE_BOUNDARY -> 2
        AviationHitKind.ATC_BOUNDARY -> 3
        AviationHitKind.SPECIAL_USE_INTERIOR -> 4
    }

    private fun compare_keys(first: AviationSelectionKey, second: AviationSelectionKey): Int {
        val kind_order = first.kind.ordinal.compareTo(second.kind.ordinal)
        if (kind_order != 0) return kind_order
        return when (first) {
            is AviationSelectionKey.ArcGis ->
                if (second is AviationSelectionKey.ArcGis) {
                    first.object_id.compareTo(second.object_id)
                } else {
                    -1
                }
            is AviationSelectionKey.Nat ->
                if (second is AviationSelectionKey.Nat) compare_nat_keys(first, second) else 1
        }
    }

    private fun compare_nat_keys(
        first: AviationSelectionKey.Nat,
        second: AviationSelectionKey.Nat
    ): Int {
        compare_nullable(first.designator, second.designator).takeIf { it != 0 }?.let { return it }
        compare_nullable(first.source_notam, second.source_notam).takeIf { it != 0 }?.let { return it }
        compare_nullable(first.icao_id, second.icao_id).takeIf { it != 0 }?.let { return it }
        compare_nullable(first.part_number, second.part_number).takeIf { it != 0 }?.let { return it }
        compare_nullable(first.start_datetime, second.start_datetime).takeIf { it != 0 }?.let { return it }
        compare_nullable(first.end_datetime, second.end_datetime).takeIf { it != 0 }?.let { return it }
        compare_nullable(first.entry_datetime, second.entry_datetime).takeIf { it != 0 }?.let { return it }
        return first.raw_track_line.compareTo(second.raw_track_line)
    }

    private fun <T : Comparable<T>> compare_nullable(first: T?, second: T?): Int = when {
        first == null && second == null -> 0
        first == null -> -1
        second == null -> 1
        else -> first.compareTo(second)
    }

    private data class RankedHit(
        val candidate: AviationHitCandidate,
        val normalized_distance: Double
    )
}