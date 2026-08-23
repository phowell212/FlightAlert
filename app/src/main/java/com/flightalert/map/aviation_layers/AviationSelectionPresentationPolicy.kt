@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.time.Instant
import java.util.Locale



    internal fun build(
        selection: AviationSelection,
        snapshot: AviationLayerSnapshot,
        now_epoch_ms: Long
    ): AviationSelectionPresentation = build(
        selection = selection,
        status = snapshot.statuses[selection.kind],
        publication = snapshot.publications[selection.kind],
        now_epoch_ms = now_epoch_ms
    )

    internal fun build(
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

    internal fun provenance(
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

    // Validate typed evidence without assigning an unsupported layer kind.
    internal fun provenance(
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
