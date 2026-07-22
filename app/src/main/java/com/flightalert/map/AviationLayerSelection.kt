package com.flightalert.map

import java.time.Instant
import java.util.Locale

internal sealed interface AviationSelectionKey {
    val kind: AviationLayerKind

    data class ArcGis(
        override val kind: AviationLayerKind,
        val object_id: Long
    ) : AviationSelectionKey {
        init {
            require(kind != AviationLayerKind.OCEANIC_TRACKS) {
                "Oceanic tracks require a source-bound NAT key"
            }
        }
    }

    data class Nat(
        val designator: String,
        val source_notam: String?,
        val icao_id: String?,
        val part_number: Int?,
        val start_datetime: String?,
        val end_datetime: String?,
        val entry_datetime: String?,
        val raw_track_line: String
    ) : AviationSelectionKey {
        override val kind: AviationLayerKind = AviationLayerKind.OCEANIC_TRACKS
    }
}

internal sealed interface AviationSelection {
    val key: AviationSelectionKey
    val kind: AviationLayerKind

    data class SpecialUse(val feature: AviationAirspaceFeature) : AviationSelection {
        override val kind: AviationLayerKind = AviationLayerKind.RESTRICTED_AIRSPACES
        override val key: AviationSelectionKey =
            AviationSelectionKey.ArcGis(kind, feature.object_id)
    }

    data class AtcBoundary(val feature: AviationAirspaceFeature) : AviationSelection {
        override val kind: AviationLayerKind = AviationLayerKind.ATC_BOUNDARIES
        override val key: AviationSelectionKey =
            AviationSelectionKey.ArcGis(kind, feature.object_id)
    }

    data class OceanicTrack(val track: AviationOceanicTrack) : AviationSelection {
        override val kind: AviationLayerKind = AviationLayerKind.OCEANIC_TRACKS
        override val key: AviationSelectionKey = track.selection_key()
    }

    data class Airport(val airport: AviationAirportFeature) : AviationSelection {
        override val kind: AviationLayerKind = AviationLayerKind.AIRPORTS
        override val key: AviationSelectionKey =
            AviationSelectionKey.ArcGis(kind, airport.object_id)
    }
}

internal enum class AviationHitKind {
    AIRPORT,
    NAT_SEGMENT,
    SPECIAL_USE_BOUNDARY,
    ATC_BOUNDARY,
    SPECIAL_USE_INTERIOR
}

internal data class AviationHitCandidate(
    val key: AviationSelectionKey,
    val kind: AviationHitKind,
    val distance_squared_dp: Double? = null,
    val containment_area: Double? = null
)

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

    fun rebind(
        selection: AviationSelection,
        snapshot: AviationLayerSnapshot
    ): AviationSelection? = resolve(selection.key, snapshot)

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
        return when {
            first is AviationSelectionKey.ArcGis && second is AviationSelectionKey.ArcGis ->
                first.object_id.compareTo(second.object_id)
            first is AviationSelectionKey.Nat && second is AviationSelectionKey.Nat ->
                compare_nat_keys(first, second)
            first is AviationSelectionKey.ArcGis -> -1
            else -> 1
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

internal enum class AviationProvenanceState {
    CURRENT_COMPLETE,
    CURRENT_PARTIAL,
    SHOWING_LAST_GOOD,
    UNAVAILABLE
}

internal data class AviationProvenancePresentation(
    val state: AviationProvenanceState,
    val banner: String,
    val latest_refresh: String?,
    val source: String,
    val observed_utc: String
)

internal data class AviationDetailRow(val label: String, val value: String)

internal data class AviationSelectionPresentation(
    val title: String,
    val subtitle: String,
    val provenance: AviationProvenancePresentation,
    val rows: List<AviationDetailRow>
)

internal object AviationSelectionPresentationPolicy {
    fun build(
        selection: AviationSelection,
        snapshot: AviationLayerSnapshot,
        now_epoch_ms: Long
    ): AviationSelectionPresentation = build(
        selection = selection,
        status = snapshot.statuses[selection.kind],
        publication = snapshot.publications[selection.kind],
        now_epoch_ms = now_epoch_ms
    )

    fun build(
        selection: AviationSelection,
        status: AviationLayerStatus?,
        publication: AviationLayerPublication?,
        now_epoch_ms: Long
    ): AviationSelectionPresentation {
        val provenance = provenance(selection.kind, status, publication)
        return when (selection) {
            is AviationSelection.SpecialUse -> special_use(selection.feature, provenance)
            is AviationSelection.AtcBoundary -> atc(selection.feature, provenance)
            is AviationSelection.OceanicTrack -> nat(selection.track, provenance, now_epoch_ms)
            is AviationSelection.Airport -> airport(selection.airport, provenance)
        }
    }

    fun provenance(
        kind: AviationLayerKind,
        status: AviationLayerStatus?,
        publication: AviationLayerPublication?
    ): AviationProvenancePresentation {
        val presentation = provenance(status, publication)
        if (presentation.state == AviationProvenanceState.UNAVAILABLE) return presentation
        publication ?: return unavailable_provenance()
        if (!identity_matches_contract(publication.displayed_source_identity, kind)) {
            return unavailable_provenance()
        }
        val latest_identity = publication.latest_attempt_source_identity
        if (latest_identity != null && !identity_matches_contract(latest_identity, kind)) {
            return unavailable_provenance()
        }
        return presentation
    }

    // This overload validates typed evidence without claiming it belongs to a particular layer kind.
    fun provenance(
        status: AviationLayerStatus?,
        publication: AviationLayerPublication?
    ): AviationProvenancePresentation {
        status ?: return unavailable_provenance()
        publication ?: return unavailable_provenance()
        val identity = publication.displayed_source_identity
            ?.takeIf(::identity_is_present)
            ?: return unavailable_provenance()
        val displayed = publication.displayed_health
        val latest = publication.latest_attempt_health
        val latest_identity = publication.latest_attempt_source_identity

        val state_and_latest = when {
            status.showing_last_good &&
                status.state == AviationLayerState.PARTIAL &&
                displayed.availability == AviationLayerAvailability.USABLE &&
                displayed.completeness in setOf(
                    AviationLayerCompleteness.COMPLETE,
                    AviationLayerCompleteness.PARTIAL
                ) &&
                displayed.freshness == AviationLayerFreshness.STALE &&
                latest.availability == AviationLayerAvailability.USABLE &&
                latest.completeness == AviationLayerCompleteness.PARTIAL &&
                latest.freshness == AviationLayerFreshness.CURRENT &&
                latest_identity?.let(::identity_is_present) == true &&
                same_source_contract(identity, latest_identity) ->
                AviationProvenanceState.SHOWING_LAST_GOOD to "Latest refresh incomplete"
            status.showing_last_good &&
                status.state == AviationLayerState.UNAVAILABLE &&
                displayed.availability == AviationLayerAvailability.USABLE &&
                displayed.completeness in setOf(
                    AviationLayerCompleteness.COMPLETE,
                    AviationLayerCompleteness.PARTIAL
                ) &&
                displayed.freshness == AviationLayerFreshness.STALE &&
                latest.availability == AviationLayerAvailability.UNAVAILABLE &&
                latest.completeness == AviationLayerCompleteness.UNKNOWN &&
                latest.freshness == AviationLayerFreshness.STALE ->
                AviationProvenanceState.SHOWING_LAST_GOOD to "Latest refresh unavailable"
            !status.showing_last_good &&
                status.state == AviationLayerState.LOADED &&
                displayed == current_complete_health() &&
                latest == displayed &&
                source_identities_match(identity, latest_identity) ->
                AviationProvenanceState.CURRENT_COMPLETE to null
            !status.showing_last_good &&
                status.state == AviationLayerState.PARTIAL &&
                displayed == current_partial_health() &&
                latest == displayed &&
                source_identities_match(identity, latest_identity) ->
                AviationProvenanceState.CURRENT_PARTIAL to null
            else -> return unavailable_provenance()
        }
        val state = state_and_latest.first
        return AviationProvenancePresentation(
            state = state,
            banner = when (state) {
                AviationProvenanceState.CURRENT_COMPLETE -> "Current complete source response"
                AviationProvenanceState.CURRENT_PARTIAL -> "Current incomplete source response"
                AviationProvenanceState.SHOWING_LAST_GOOD -> "Showing last verified data"
                AviationProvenanceState.UNAVAILABLE -> error("Unavailable provenance returned early")
            },
            latest_refresh = state_and_latest.second,
            source = listOf(identity.provider, identity.service, identity.layer).joinToString(" · "),
            observed_utc = Instant.ofEpochMilli(identity.response_observed_at_epoch_ms).toString()
        )
    }

    private fun special_use(
        feature: AviationAirspaceFeature,
        provenance: AviationProvenancePresentation
    ): AviationSelectionPresentation = AviationSelectionPresentation(
        title = feature.name,
        subtitle = "FAA SPECIAL USE AIRSPACE",
        provenance = provenance,
        rows = listOf(
            AviationDetailRow("Type", feature.type.ifBlank { "Unavailable" }),
            AviationDetailRow("Vertical", vertical(feature)),
            AviationDetailRow("Schedule", feature.schedule ?: "Unavailable from FAA feature"),
            AviationDetailRow("Location", location(feature)),
            AviationDetailRow("Source", "FAA AIS Special Use Airspace")
        )
    )

    private fun atc(
        feature: AviationAirspaceFeature,
        provenance: AviationProvenancePresentation
    ): AviationSelectionPresentation {
        val type_code = feature.type.trim().uppercase(Locale.US)
        val subtitle = if (type_code in setOf("ARTCC", "FIR", "OCA")) {
            "FAA $type_code BOUNDARY"
        } else {
            "FAA ATC BOUNDARY"
        }
        return AviationSelectionPresentation(
            title = feature.name,
            subtitle = subtitle,
            provenance = provenance,
            rows = listOf(
                AviationDetailRow("Type", feature.type.ifBlank { "Unavailable from FAA feature" }),
                AviationDetailRow("Vertical", vertical(feature)),
                AviationDetailRow("Location", location(feature))
            )
        )
    }

    private fun airport(
        feature: AviationAirportFeature,
        provenance: AviationProvenancePresentation
    ): AviationSelectionPresentation {
        val position = if (feature.lat.isFinite() && feature.lat in -90.0..90.0 &&
            feature.lon.isFinite() && feature.lon in -180.0..180.0
        ) {
            "${feature.lat}, ${feature.lon}"
        } else {
            "Unavailable from FAA feature"
        }
        return AviationSelectionPresentation(
            title = feature.ident.ifBlank { "Airport identifier unavailable" },
            subtitle = "FAA OPERATIONAL AIRPORT RECORD",
            provenance = provenance,
            rows = listOf(
                AviationDetailRow("Name", feature.name.ifBlank { "Unavailable from FAA feature" }),
                AviationDetailRow("Type code", feature.type.ifBlank { "Unavailable from FAA feature" }),
                AviationDetailRow(
                    "Military code",
                    feature.military_code.ifBlank { "Unavailable from FAA feature" }
                ),
                AviationDetailRow("Source position", position)
            )
        )
    }

    private fun nat(
        track: AviationOceanicTrack,
        provenance: AviationProvenancePresentation,
        now_epoch_ms: Long
    ): AviationSelectionPresentation {
        val coordinate_count = track.waypoints.count { it is AviationNatWaypoint.Coordinate }
        val named_fix_count = track.waypoints.count { it is AviationNatWaypoint.NamedFix }
        val unrecognized_count = track.waypoints.count { it is AviationNatWaypoint.Unrecognized }
        return AviationSelectionPresentation(
            title = track.name,
            subtitle = "NORTH ATLANTIC TRACK",
            provenance = provenance,
            rows = listOf(
                AviationDetailRow("State", nat_state(track, now_epoch_ms)),
                AviationDetailRow(
                    "Validity",
                    "Start: ${source_value(track.start_datetime)}; End: ${source_value(track.end_datetime)}"
                ),
                AviationDetailRow("NOTAM", source_value(track.source_notam)),
                AviationDetailRow("ICAO ID", source_value(track.icao_id)),
                AviationDetailRow(
                    "Part",
                    "Record ${source_value(track.part_number)}; message " +
                        "${source_value(track.declared_part_number)} of " +
                        source_value(track.declared_part_count)
                ),
                AviationDetailRow("Route", source_value(track.raw_track_line)),
                AviationDetailRow(
                    "Geometry",
                    "$coordinate_count/${track.waypoints.size} source waypoints positioned; " +
                        "$named_fix_count named fixes unresolved; $unrecognized_count unrecognized"
                ),
                AviationDetailRow("Last updated (raw)", source_value(track.last_updated))
            )
        )
    }

    private fun nat_state(track: AviationOceanicTrack, now_epoch_ms: Long): String {
        val start = track.start_epoch_ms ?: return "Unavailable from source record"
        val end = track.end_epoch_ms ?: return "Unavailable from source record"
        if (start >= end) return "Unavailable from source record"
        return when {
            now_epoch_ms < start -> "Upcoming"
            now_epoch_ms >= end -> "Expired"
            else -> "Active"
        }
    }

    private fun vertical(feature: AviationAirspaceFeature): String = listOf(
        feature.lower_limit ?: "Lower unavailable",
        feature.upper_limit ?: "Upper unavailable"
    ).joinToString(" to ")

    private fun location(feature: AviationAirspaceFeature): String =
        listOfNotNull(feature.city, feature.state)
            .joinToString(", ")
            .ifBlank { "Unavailable from FAA feature" }

    private fun source_value(value: String?): String =
        value?.takeIf(String::isNotBlank) ?: "Unavailable from source record"

    private fun source_value(value: Int?): String =
        value?.toString() ?: "Unavailable from source record"

    private fun identity_is_present(identity: AviationSourceIdentity): Boolean {
        if (identity.provider.isBlank() || identity.service.isBlank() || identity.layer.isBlank()) {
            return false
        }
        if (identity.response_observed_at_epoch_ms < 0L) return false
        val captured_at = identity.object_ids_captured_at_epoch_ms
        if (captured_at != null &&
            (captured_at < 0L || captured_at > identity.response_observed_at_epoch_ms)
        ) return false
        return identity.final_source_url_is_valid
    }

    private fun source_identities_match(
        displayed: AviationSourceIdentity,
        latest: AviationSourceIdentity?
    ): Boolean = latest != null &&
        identity_is_present(latest) &&
        displayed.provider == latest.provider &&
        displayed.service == latest.service &&
        displayed.layer == latest.layer &&
        displayed.requested_envelopes == latest.requested_envelopes &&
        displayed.object_ids_captured_at_epoch_ms == latest.object_ids_captured_at_epoch_ms &&
        displayed.response_observed_at_epoch_ms == latest.response_observed_at_epoch_ms &&
        displayed.final_source_url == latest.final_source_url &&
        displayed.advertised_revision == latest.advertised_revision

    private fun same_source_contract(
        displayed: AviationSourceIdentity,
        latest: AviationSourceIdentity
    ): Boolean = displayed.provider == latest.provider &&
        displayed.service == latest.service &&
        displayed.layer == latest.layer

    private fun identity_matches_contract(
        identity: AviationSourceIdentity?,
        kind: AviationLayerKind
    ): Boolean {
        identity ?: return false
        val contract = source_contract(kind)
        return identity.provider == contract.provider &&
            identity.service == contract.service &&
            identity.layer == contract.layer
    }

    private fun source_contract(kind: AviationLayerKind): FixedSourceContract = when (kind) {
        AviationLayerKind.ATC_BOUNDARIES -> FixedSourceContract(
            provider = "FAA",
            service = "Boundary_Airspace",
            layer = "0:Airspace_Boundary"
        )
        AviationLayerKind.RESTRICTED_AIRSPACES -> FixedSourceContract(
            provider = "FAA",
            service = "Special_Use_Airspace",
            layer = "0:Special_Use_Airspace"
        )
        AviationLayerKind.AIRPORTS -> FixedSourceContract(
            provider = "FAA",
            service = "US_Airport",
            layer = "0:Airports"
        )
        AviationLayerKind.OCEANIC_TRACKS -> FixedSourceContract(
            provider = "FAA",
            service = "NMS North Atlantic Tracks",
            layer = "NAT"
        )
    }

    private data class FixedSourceContract(
        val provider: String,
        val service: String,
        val layer: String
    )

    private fun current_complete_health(): AviationLayerHealth = AviationLayerHealth(
        AviationLayerAvailability.USABLE,
        AviationLayerCompleteness.COMPLETE,
        AviationLayerFreshness.CURRENT
    )

    private fun current_partial_health(): AviationLayerHealth = AviationLayerHealth(
        AviationLayerAvailability.USABLE,
        AviationLayerCompleteness.PARTIAL,
        AviationLayerFreshness.CURRENT
    )

    private fun unavailable_provenance(): AviationProvenancePresentation =
        AviationProvenancePresentation(
            state = AviationProvenanceState.UNAVAILABLE,
            banner = "Source status unavailable",
            latest_refresh = null,
            source = "Unavailable",
            observed_utc = "Unavailable"
        )
}

private fun AviationOceanicTrack.selection_key(): AviationSelectionKey.Nat =
    AviationSelectionKey.Nat(
        designator = designator,
        source_notam = source_notam,
        icao_id = icao_id,
        part_number = part_number,
        start_datetime = start_datetime,
        end_datetime = end_datetime,
        entry_datetime = entry_datetime,
        raw_track_line = raw_track_line
    )
