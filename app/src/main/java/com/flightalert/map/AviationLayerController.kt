@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.map

import com.flightalert.FlightAlertAppSettings
import android.os.SystemClock
import com.flightalert.TILE_SIZE
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class AviationLayerController(
    private val client: AviationLayerClient,
    private val run_in_background: (() -> Unit) -> Unit,
    private val post_to_main: (() -> Unit) -> Unit,
    private val request_redraw: () -> Unit,
    private val current_viewport: () -> Viewport?,
    private val visible_bounds: (Viewport) -> Bounds?,
    private val refresh_ms: Long,
    private val bounds_padding_fraction: Double,
    private val now_ms: () -> Long = { SystemClock.elapsedRealtime() },
    private val fetch_layers: ((List<AviationLayerBounds>, Boolean, Boolean, Boolean, Boolean) ->
        AviationLayerSnapshot)? = null
) {
    var snapshot: AviationLayerSnapshot? = null
        private set

    var fetch_in_flight: Boolean = false
        private set

    var status_text: String = "Layers off"
        private set

    private val last_fetch_completed_ms = mutableMapOf<AviationLayerKind, Long>()
    private val last_attempt_query_envelopes =
        mutableMapOf<AviationLayerKind, List<AviationLayerBounds>>()
    private val forced_refresh_required_kinds = linkedSetOf<AviationLayerKind>()
    private var active_fetch_token: AviationLayerFetchToken? = null
    private var latest_visibility = AviationLayerVisibility(
        restricted_airspaces_enabled = false,
        atc_boundaries_enabled = false,
        oceanic_tracks_enabled = false,
        airport_labels_enabled = false
    )
    private var latest_requested_viewport: Viewport? = null

    fun request_if_needed(
        viewport: Viewport,
        visibility: AviationLayerVisibility,
        force: Boolean = false
    ) {
        latest_visibility = visibility
        latest_requested_viewport = viewport
        if (!has_enabled_layers(visibility)) {
            cancel_active_fetch_if_not_requested(emptyList())
            status_text = "Layers off"
            return
        }
        val fetchable_kinds = fetchable_active_kinds(viewport, visibility)
        if (force) forced_refresh_required_kinds += fetchable_kinds
        cancel_active_fetch_if_not_requested(fetchable_kinds)
        val query_envelopes = source_query_envelopes_for_viewport(
            viewport,
            bounds_padding_fraction
        )
        val unpadded_target_envelopes = source_query_envelopes_for_viewport(viewport, 0.0)
        val now = now_ms()
        val requested_kinds = fetchable_kinds.filter { kind ->
            kind in forced_refresh_required_kinds || kind_needs_fetch(
                kind = kind,
                unpadded_target_envelopes = unpadded_target_envelopes,
                now = now
            )
        }
        if (requested_kinds.isEmpty() || fetch_in_flight) {
            if (requested_kinds.isEmpty()) {
                status_text = summary_for_viewport(snapshot, visibility, viewport)
            } else {
                status_text = "Loading aviation layers"
            }
            return
        }

        status_text = "Loading aviation layers"
        start_fetch(
            query_envelopes = query_envelopes,
            requested_kind = requested_kinds.first()
        )
    }

    private fun kind_needs_fetch(
        kind: AviationLayerKind,
        unpadded_target_envelopes: List<AviationLayerBounds>,
        now: Long
    ): Boolean {
        if (snapshot?.statuses?.get(kind) == null) return true
        val completed_at = last_fetch_completed_ms[kind] ?: return true
        if (kind != AviationLayerKind.OCEANIC_TRACKS) {
            val attempted_envelopes = last_attempt_query_envelopes[kind].orEmpty()
            if (!query_envelopes_cover(attempted_envelopes, unpadded_target_envelopes)) {
                return true
            }
        }
        return now - completed_at >= refresh_ms
    }

    private fun start_fetch(
        query_envelopes: List<AviationLayerBounds>,
        requested_kind: AviationLayerKind
    ) {
        val requested_kinds = listOf(requested_kind)
        val token = AviationLayerFetchToken(requested_kind)
        active_fetch_token = token
        fetch_in_flight = true
        run_in_background {
            var cancelled = false
            val next_snapshot = try {
                if (token.cancelled) throw AviationFetchCancelledException()
                fetch_layers?.invoke(
                    query_envelopes,
                    requested_kind == AviationLayerKind.ATC_BOUNDARIES,
                    requested_kind == AviationLayerKind.RESTRICTED_AIRSPACES,
                    requested_kind == AviationLayerKind.AIRPORTS,
                    requested_kind == AviationLayerKind.OCEANIC_TRACKS
                ) ?: client.fetch_layers(
                    query_envelopes = query_envelopes,
                    include_atc_boundaries = requested_kind == AviationLayerKind.ATC_BOUNDARIES,
                    include_restricted_airspaces =
                        requested_kind == AviationLayerKind.RESTRICTED_AIRSPACES,
                    include_airports = requested_kind == AviationLayerKind.AIRPORTS,
                    include_oceanic_tracks = requested_kind == AviationLayerKind.OCEANIC_TRACKS,
                    should_continue = { kind -> kind == requested_kind && !token.cancelled }
                )
            } catch (_: AviationFetchCancelledException) {
                cancelled = true
                null
            } catch (_: Exception) {
                null
            }
            post_to_main {
                apply_fetch_result(
                    snapshot = next_snapshot,
                    requested_kinds = requested_kinds,
                    query_envelopes = query_envelopes,
                    token = token,
                    cancelled = cancelled || token.cancelled
                )
            }
        }
    }

    fun on_visibility_changed(visibility: AviationLayerVisibility) {
        latest_visibility = visibility
        if (!has_enabled_layers(visibility)) {
            cancel_active_fetch_if_not_requested(emptyList())
            status_text = "Layers off"
            request_redraw()
            return
        }
        val active_kinds = active_kinds(visibility)
        cancel_active_fetch_if_not_requested(active_kinds)
        status_text = summary(snapshot, visibility)
        current_viewport()?.let { request_if_needed(it, visibility, force = false) }
    }

    fun clear() {
        val outstanding_fetch = active_fetch_token
        outstanding_fetch?.cancel()
        snapshot = null
        fetch_in_flight = outstanding_fetch != null
        last_attempt_query_envelopes.clear()
        last_fetch_completed_ms.clear()
        forced_refresh_required_kinds.clear()
        latest_visibility = AviationLayerVisibility(
            restricted_airspaces_enabled = false,
            atc_boundaries_enabled = false,
            oceanic_tracks_enabled = false,
            airport_labels_enabled = false
        )
        latest_requested_viewport = null
        status_text = "Layers off"
    }

    fun has_enabled_layers(visibility: AviationLayerVisibility): Boolean {
        return visibility.atc_boundaries_enabled ||
                visibility.restricted_airspaces_enabled ||
                visibility.oceanic_tracks_enabled ||
                visibility.airport_labels_enabled
    }

    private fun apply_fetch_result(
        snapshot: AviationLayerSnapshot?,
        requested_kinds: List<AviationLayerKind>,
        query_envelopes: List<AviationLayerBounds>,
        token: AviationLayerFetchToken,
        cancelled: Boolean
    ) {
        if (active_fetch_token !== token) return
        active_fetch_token = null
        fetch_in_flight = false
        val latest_enabled = has_enabled_layers(latest_visibility)
        if (cancelled) {
            forced_refresh_required_kinds += requested_kinds
            status_text = if (latest_enabled) {
                latest_requested_viewport?.let { viewport ->
                    summary_for_viewport(this.snapshot, latest_visibility, viewport)
                } ?: summary(this.snapshot, latest_visibility)
            } else {
                "Layers off"
            }
            request_redraw()
            request_missing_latest_layers_if_needed(latest_enabled)
            return
        }
        requested_kinds.forEach(forced_refresh_required_kinds::remove)
        val completed_at = now_ms()
        requested_kinds.forEach { kind ->
            last_attempt_query_envelopes[kind] = query_envelopes.toList()
            last_fetch_completed_ms[kind] = completed_at
        }
        if (snapshot == null) {
            val previous = this.snapshot
            val failed_attempt = unavailable_attempt_snapshot(
                requested_kinds = requested_kinds.toSet(),
                fetched_at_ms = previous?.fetched_at_ms ?: 0L,
                message_suffix = "fetch failed"
            )
            this.snapshot = previous?.let {
                merge_layer_snapshot(
                    previous = it,
                    fetched = failed_attempt,
                    requested_kinds = requested_kinds.toSet(),
                    attempted_query_envelopes = query_envelopes
                )
            } ?: failed_attempt
            status_text = if (latest_enabled) {
                latest_requested_viewport?.let { viewport ->
                    summary_for_viewport(this.snapshot, latest_visibility, viewport)
                } ?: summary(this.snapshot, latest_visibility)
            } else {
                "Layers off"
            }
            request_redraw()
            request_missing_latest_layers_if_needed(latest_enabled)
            return
        }

        val requested_kind_set = requested_kinds.toSet()
        val normalized_snapshot = with_missing_requested_results_as_unavailable(
            snapshot,
            requested_kind_set,
            query_envelopes
        )
        val previous = this.snapshot
        this.snapshot = previous?.let {
            merge_layer_snapshot(
                previous = it,
                fetched = normalized_snapshot,
                requested_kinds = requested_kind_set,
                attempted_query_envelopes = query_envelopes
            )
        } ?: normalized_snapshot
        status_text = if (latest_enabled) {
            latest_requested_viewport?.let { viewport ->
                summary_for_viewport(this.snapshot, latest_visibility, viewport)
            } ?: summary(this.snapshot, latest_visibility)
        } else {
            "Layers off"
        }
        request_redraw()
        request_missing_latest_layers_if_needed(latest_enabled)
    }

    private fun request_missing_latest_layers_if_needed(latest_enabled: Boolean) {
        if (!latest_enabled) return
        latest_requested_viewport?.let { viewport ->
            request_if_needed(viewport, latest_visibility, force = false)
        }
    }

    private fun fetchable_active_kinds(
        viewport: Viewport,
        visibility: AviationLayerVisibility
    ): List<AviationLayerKind> = buildList(4) {
        if (visibility.atc_boundaries_enabled) add(AviationLayerKind.ATC_BOUNDARIES)
        if (visibility.restricted_airspaces_enabled) add(AviationLayerKind.RESTRICTED_AIRSPACES)
        if (visibility.oceanic_tracks_enabled) add(AviationLayerKind.OCEANIC_TRACKS)
        if (visibility.airport_labels_enabled &&
            viewport.zoom >= AVIATION_AIRPORT_LABEL_MIN_ZOOM
        ) {
            add(AviationLayerKind.AIRPORTS)
        }
    }

    private fun cancel_active_fetch_if_not_requested(requested_kinds: Collection<AviationLayerKind>) {
        active_fetch_token?.takeIf { it.kind !in requested_kinds }?.cancel()
    }

    private fun summary_for_viewport(
        snapshot: AviationLayerSnapshot?,
        visibility: AviationLayerVisibility,
        viewport: Viewport
    ): String {
        val base = summary(snapshot, visibility)
        if (!visibility.airport_labels_enabled ||
            viewport.zoom >= AVIATION_AIRPORT_LABEL_MIN_ZOOM
        ) return base
        val airport_note = "Airport labels appear at zoom 8.4"
        val other_enabled = visibility.atc_boundaries_enabled ||
            visibility.restricted_airspaces_enabled ||
            visibility.oceanic_tracks_enabled
        return if (other_enabled) "$base; $airport_note" else airport_note
    }

    private fun source_query_envelopes_for_viewport(
        viewport: Viewport,
        padding_fraction: Double
    ): List<AviationLayerBounds> {
        val padding = max(viewport.width, viewport.height) * padding_fraction
        val left = viewport.center_x - viewport.width / 2.0 - padding
        val right = viewport.center_x + viewport.width / 2.0 + padding
        val top = viewport.center_y - viewport.height / 2.0 - padding
        val bottom = viewport.center_y + viewport.height / 2.0 + padding
        val top_left = MapProjection.world_to_lat_lon(left, top, viewport.zoom)
        val bottom_right = MapProjection.world_to_lat_lon(right, bottom, viewport.zoom)
        val world_width = TILE_SIZE * 2.0.pow(viewport.zoom)
        val longitude_span_degrees = ((right - left) / world_width) * 360.0
        val min_lat = min(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0)
        val max_lat = max(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0)
        if (longitude_span_degrees >= 180.0) {
            return listOf(AviationLayerBounds(min_lat, -180.0, max_lat, 180.0))
        }
        return AviationQueryEnvelopePlanner.for_viewport(
            min_lat = min_lat,
            west_lon = top_left.lon,
            max_lat = max_lat,
            east_lon = bottom_right.lon
        )
    }

    private fun query_envelopes_cover(
        covering: List<AviationLayerBounds>,
        targets: List<AviationLayerBounds>
    ): Boolean {
        if (covering.isEmpty() || targets.isEmpty()) return false
        return targets.all { target -> covering.any { it.contains(target) } }
    }

    private fun AviationLayerBounds.contains(other: AviationLayerBounds): Boolean {
        return min_lat <= other.min_lat &&
            min_lon <= other.min_lon &&
            max_lat >= other.max_lat &&
            max_lon >= other.max_lon
    }

    private fun active_kinds(visibility: AviationLayerVisibility): List<AviationLayerKind> {
        val kinds = mutableListOf<AviationLayerKind>()
        if (visibility.atc_boundaries_enabled) kinds += AviationLayerKind.ATC_BOUNDARIES
        if (visibility.restricted_airspaces_enabled) kinds += AviationLayerKind.RESTRICTED_AIRSPACES
        if (visibility.oceanic_tracks_enabled) kinds += AviationLayerKind.OCEANIC_TRACKS
        if (visibility.airport_labels_enabled) kinds += AviationLayerKind.AIRPORTS
        return kinds
    }

    private fun summary(
        snapshot: AviationLayerSnapshot?,
        visibility: AviationLayerVisibility
    ): String {
        if (!has_enabled_layers(visibility)) return "Layers off"
        snapshot ?: return "Waiting for aviation layers"
        val active = active_kinds(visibility)
        val retained = active.count { snapshot.statuses[it]?.showing_last_good == true }
        val loaded = active.count { snapshot.statuses[it]?.state == AviationLayerState.LOADED }
        val partial = active.count {
            snapshot.statuses[it]?.state == AviationLayerState.PARTIAL &&
                snapshot.statuses[it]?.showing_last_good != true
        }
        val unavailable = active.count {
            snapshot.statuses[it]?.state == AviationLayerState.UNAVAILABLE &&
                snapshot.statuses[it]?.showing_last_good != true
        }
        return when {
            retained > 0 -> buildString {
                append("Latest source incomplete; showing last aviation layers")
                if (unavailable > 0) append("; $unavailable unavailable")
            }
            loaded > 0 || partial > 0 || unavailable > 0 -> buildList {
                if (loaded > 0) {
                    add("$loaded aviation layer${if (loaded == 1) "" else "s"} loaded")
                }
                if (partial > 0) {
                    add("$partial partial")
                }
                if (unavailable > 0) {
                    add("$unavailable unavailable")
                }
            }.joinToString("; ")
            else -> "No aviation layer data in view"
        }
    }

    private fun merge_layer_snapshot(
        previous: AviationLayerSnapshot,
        fetched: AviationLayerSnapshot,
        requested_kinds: Set<AviationLayerKind>,
        attempted_query_envelopes: List<AviationLayerBounds>
    ): AviationLayerSnapshot {
        val attempt = with_missing_requested_results_as_unavailable(
            fetched,
            requested_kinds,
            attempted_query_envelopes
        )
        val decisions = AviationLayerKind.entries.associateWith { kind ->
            layer_merge_decision(
                kind,
                requested_kinds,
                previous,
                attempt,
                attempted_query_envelopes
            )
        }
        return AviationLayerSnapshot(
            atc_boundaries = merged_layer_data(
                decision = decisions.getValue(AviationLayerKind.ATC_BOUNDARIES),
                previous = previous.atc_boundaries,
                fetched = attempt.atc_boundaries
            ),
            restricted_airspaces = merged_layer_data(
                decision = decisions.getValue(AviationLayerKind.RESTRICTED_AIRSPACES),
                previous = previous.restricted_airspaces,
                fetched = attempt.restricted_airspaces
            ),
            airports = merged_layer_data(
                decision = decisions.getValue(AviationLayerKind.AIRPORTS),
                previous = previous.airports,
                fetched = attempt.airports
            ),
            oceanic_tracks = merged_layer_data(
                decision = decisions.getValue(AviationLayerKind.OCEANIC_TRACKS),
                previous = previous.oceanic_tracks,
                fetched = attempt.oceanic_tracks
            ),
            statuses = merged_layer_statuses(previous, attempt, decisions),
            publications = merged_layer_publications(previous, attempt, decisions),
            fetched_at_ms = attempt.fetched_at_ms
        )
    }

    private fun with_missing_requested_results_as_unavailable(
        fetched: AviationLayerSnapshot,
        requested_kinds: Set<AviationLayerKind>,
        query_envelopes: List<AviationLayerBounds>
    ): AviationLayerSnapshot {
        val missing = requested_kinds.filterTo(linkedSetOf()) { kind ->
            !has_valid_typed_publication(fetched, kind, query_envelopes)
        }
        if (missing.isEmpty()) return fetched
        val failure = unavailable_attempt_snapshot(
            requested_kinds = missing,
            fetched_at_ms = fetched.fetched_at_ms,
            message_suffix = "source result missing"
        )
        return AviationLayerSnapshot(
            atc_boundaries = if (AviationLayerKind.ATC_BOUNDARIES in missing) {
                emptyList()
            } else {
                fetched.atc_boundaries
            },
            restricted_airspaces = if (AviationLayerKind.RESTRICTED_AIRSPACES in missing) {
                emptyList()
            } else {
                fetched.restricted_airspaces
            },
            airports = if (AviationLayerKind.AIRPORTS in missing) emptyList() else fetched.airports,
            oceanic_tracks = if (AviationLayerKind.OCEANIC_TRACKS in missing) {
                emptyList()
            } else {
                fetched.oceanic_tracks
            },
            statuses = fetched.statuses + failure.statuses,
            fetched_at_ms = fetched.fetched_at_ms,
            publications = fetched.publications + failure.publications
        )
    }

    private fun has_valid_typed_publication(
        snapshot: AviationLayerSnapshot,
        kind: AviationLayerKind,
        query_envelopes: List<AviationLayerBounds>
    ): Boolean {
        val status = snapshot.statuses[kind] ?: return false
        val publication = snapshot.publications[kind] ?: return false
        if (status.showing_last_good) return false
        if (publication.displayed_health != publication.latest_attempt_health) return false
        val displayed_identity = publication.displayed_source_identity
        val latest_identity = publication.latest_attempt_source_identity
        if (!source_identities_match(displayed_identity, latest_identity)) return false
        if (displayed_identity != null && !source_identity_matches_request(
                displayed_identity,
                kind,
                query_envelopes
            )
        ) return false
        if (latest_identity != null && !source_identity_matches_request(
                latest_identity,
                kind,
                query_envelopes
            )
        ) return false
        val record_count = snapshot.record_count(kind)
        return when (status.state) {
            AviationLayerState.LOADED ->
                publication.latest_attempt_health.availability == AviationLayerAvailability.USABLE &&
                    publication.latest_attempt_health.completeness == AviationLayerCompleteness.COMPLETE &&
                    publication.latest_attempt_health.freshness == AviationLayerFreshness.CURRENT &&
                    record_count > 0 &&
                    displayed_identity != null && latest_identity != null &&
                    source_identity_has_complete_proof(displayed_identity, kind)
            AviationLayerState.PARTIAL ->
                publication.latest_attempt_health.availability == AviationLayerAvailability.USABLE &&
                    publication.latest_attempt_health.completeness == AviationLayerCompleteness.PARTIAL &&
                    publication.latest_attempt_health.freshness == AviationLayerFreshness.CURRENT &&
                    record_count > 0 &&
                    displayed_identity != null && latest_identity != null &&
                    source_identity_has_complete_proof(displayed_identity, kind)
            AviationLayerState.EMPTY ->
                publication.latest_attempt_health.availability == AviationLayerAvailability.EMPTY &&
                    publication.latest_attempt_health.can_claim_complete &&
                    publication.latest_attempt_health.freshness == AviationLayerFreshness.CURRENT &&
                    record_count == 0 &&
                    displayed_identity != null && latest_identity != null &&
                    source_identity_has_complete_proof(displayed_identity, kind)
            AviationLayerState.UNAVAILABLE ->
                publication.latest_attempt_health.availability == AviationLayerAvailability.UNAVAILABLE &&
                    publication.latest_attempt_health.completeness == AviationLayerCompleteness.UNKNOWN &&
                    publication.latest_attempt_health.freshness == AviationLayerFreshness.STALE &&
                    record_count == 0
        }
    }

    private fun source_identity_matches_request(
        identity: AviationSourceIdentity,
        kind: AviationLayerKind,
        query_envelopes: List<AviationLayerBounds>
    ): Boolean {
        val contract = source_contract(kind)
        if (identity.provider != contract.provider ||
            identity.service != contract.service ||
            identity.layer != contract.layer
        ) return false
        val expected_envelopes = if (contract.viewport_bound) {
            query_envelopes.map {
                AviationGeoBounds(it.min_lat, it.min_lon, it.max_lat, it.max_lon)
            }
        } else {
            emptyList()
        }
        if (identity.requested_envelopes != expected_envelopes) return false
        val final_url = try {
            URL(identity.final_source_url)
        } catch (_: Exception) {
            return false
        }
        return final_url.protocol.equals("https", ignoreCase = true) &&
            final_url.host.isNotBlank()
    }

    private fun source_identity_has_complete_proof(
        identity: AviationSourceIdentity,
        kind: AviationLayerKind
    ): Boolean {
        if (kind == AviationLayerKind.OCEANIC_TRACKS) return true
        val captured_at = identity.object_ids_captured_at_epoch_ms ?: return false
        return identity.advertised_revision != null &&
            captured_at <= identity.response_observed_at_epoch_ms
    }

    private fun source_contract(kind: AviationLayerKind): AviationSourceContract = when (kind) {
        AviationLayerKind.ATC_BOUNDARIES -> AviationSourceContract(
            provider = "FAA",
            service = "Boundary_Airspace",
            layer = "0:Airspace_Boundary",
            viewport_bound = true
        )
        AviationLayerKind.RESTRICTED_AIRSPACES -> AviationSourceContract(
            provider = "FAA",
            service = "Special_Use_Airspace",
            layer = "0:Special_Use_Airspace",
            viewport_bound = true
        )
        AviationLayerKind.AIRPORTS -> AviationSourceContract(
            provider = "FAA",
            service = "US_Airport",
            layer = "0:Airports",
            viewport_bound = true
        )
        AviationLayerKind.OCEANIC_TRACKS -> AviationSourceContract(
            provider = "FAA",
            service = "NMS North Atlantic Tracks",
            layer = "NAT",
            viewport_bound = false
        )
    }

    private fun AviationLayerSnapshot.record_count(kind: AviationLayerKind): Int = when (kind) {
        AviationLayerKind.ATC_BOUNDARIES -> atc_boundaries.size
        AviationLayerKind.RESTRICTED_AIRSPACES -> restricted_airspaces.size
        AviationLayerKind.AIRPORTS -> airports.size
        AviationLayerKind.OCEANIC_TRACKS -> oceanic_tracks.size
    }

    private fun source_identities_match(
        first: AviationSourceIdentity?,
        second: AviationSourceIdentity?
    ): Boolean {
        if (first == null || second == null) return first == null && second == null
        return first.provider == second.provider &&
            first.service == second.service &&
            first.layer == second.layer &&
            first.requested_envelopes == second.requested_envelopes &&
            first.object_ids_captured_at_epoch_ms == second.object_ids_captured_at_epoch_ms &&
            first.response_observed_at_epoch_ms == second.response_observed_at_epoch_ms &&
            first.final_source_url == second.final_source_url &&
            first.advertised_revision == second.advertised_revision
    }

    private fun unavailable_attempt_snapshot(
        requested_kinds: Set<AviationLayerKind>,
        fetched_at_ms: Long,
        message_suffix: String
    ): AviationLayerSnapshot {
        val health = AviationLayerHealth(
            AviationLayerAvailability.UNAVAILABLE,
            AviationLayerCompleteness.UNKNOWN,
            AviationLayerFreshness.STALE
        )
        val statuses = requested_kinds.associateWith { kind ->
            AviationLayerStatus(
                state = AviationLayerState.UNAVAILABLE,
                message = "${kind.display_name} unavailable; $message_suffix"
            )
        }
        val publications = requested_kinds.associateWith {
            AviationLayerPublication(
                displayed_health = health,
                displayed_source_identity = null,
                latest_attempt_health = health,
                latest_attempt_source_identity = null
            )
        }
        return AviationLayerSnapshot(
            atc_boundaries = emptyList(),
            restricted_airspaces = emptyList(),
            airports = emptyList(),
            oceanic_tracks = emptyList(),
            statuses = statuses,
            fetched_at_ms = fetched_at_ms,
            publications = publications
        )
    }

    private fun <T> merged_layer_data(
        decision: LayerMergeDecision,
        previous: List<T>,
        fetched: List<T>
    ): List<T> = when (decision) {
        LayerMergeDecision.ACCEPT_FETCHED -> fetched
        LayerMergeDecision.KEEP_UNREQUESTED,
        LayerMergeDecision.RETAIN_LAST_GOOD -> previous
    }

    private fun layer_merge_decision(
        kind: AviationLayerKind,
        requested_kinds: Set<AviationLayerKind>,
        previous: AviationLayerSnapshot,
        fetched: AviationLayerSnapshot,
        attempted_query_envelopes: List<AviationLayerBounds>
    ): LayerMergeDecision {
        if (kind !in requested_kinds) return LayerMergeDecision.KEEP_UNREQUESTED
        val fetched_status = fetched.statuses[kind] ?: return if (
            layer_is_displayable(previous, kind) &&
            previous_coverage_contains_attempt(
                kind,
                previous.publications[kind],
                attempted_query_envelopes
            )
        ) {
            LayerMergeDecision.RETAIN_LAST_GOOD
        } else {
            LayerMergeDecision.ACCEPT_FETCHED
        }
        val previous_status = previous.statuses[kind]
        val previous_publication = previous.publications[kind]
        val previous_is_displayable = layer_is_displayable(previous, kind)
        val previous_is_complete_usable = previous_publication?.displayed_health?.let { health ->
            health.availability == AviationLayerAvailability.USABLE && health.can_claim_complete
        } ?: (
            previous_status?.state == AviationLayerState.LOADED &&
                previous.record_count(kind) > 0
            )
        return when (fetched_status.state) {
            AviationLayerState.UNAVAILABLE -> if (
                previous_is_displayable &&
                previous_coverage_contains_attempt(
                    kind,
                    previous_publication,
                    attempted_query_envelopes
                )
            ) {
                LayerMergeDecision.RETAIN_LAST_GOOD
            } else {
                LayerMergeDecision.ACCEPT_FETCHED
            }
            AviationLayerState.PARTIAL -> if (
                previous_is_complete_usable &&
                previous_complete_coverage_contains_attempt(
                    kind,
                    previous_publication,
                    attempted_query_envelopes
                )
            ) {
                LayerMergeDecision.RETAIN_LAST_GOOD
            } else {
                LayerMergeDecision.ACCEPT_FETCHED
            }
            AviationLayerState.LOADED,
            AviationLayerState.EMPTY -> LayerMergeDecision.ACCEPT_FETCHED
        }
    }

    private fun previous_complete_coverage_contains_attempt(
        kind: AviationLayerKind,
        previous: AviationLayerPublication?,
        attempted_query_envelopes: List<AviationLayerBounds>
    ): Boolean = previous_coverage_contains_attempt(
        kind,
        previous,
        attempted_query_envelopes
    )

    private fun previous_coverage_contains_attempt(
        kind: AviationLayerKind,
        previous: AviationLayerPublication?,
        attempted_query_envelopes: List<AviationLayerBounds>
    ): Boolean {
        if (kind == AviationLayerKind.OCEANIC_TRACKS) return true
        val displayed_envelopes = previous?.displayed_source_identity?.requested_envelopes
            ?: return false
        val attempted_envelopes = attempted_query_envelopes.map {
            AviationGeoBounds(it.min_lat, it.min_lon, it.max_lat, it.max_lon)
        }
        if (displayed_envelopes.isEmpty() || attempted_envelopes.isEmpty()) return false
        return attempted_envelopes.all { attempted ->
            displayed_envelopes.any { displayed -> displayed.contains(attempted) }
        }
    }

    private fun AviationGeoBounds.contains(other: AviationGeoBounds): Boolean {
        return min_lat <= other.min_lat &&
            min_lon <= other.min_lon &&
            max_lat >= other.max_lat &&
            max_lon >= other.max_lon
    }

    private fun layer_is_displayable(
        snapshot: AviationLayerSnapshot,
        kind: AviationLayerKind
    ): Boolean {
        val status = snapshot.statuses[kind]
        val availability = snapshot.publications[kind]?.displayed_health?.availability
        return availability == AviationLayerAvailability.USABLE ||
            availability == AviationLayerAvailability.EMPTY ||
            status?.state == AviationLayerState.LOADED ||
            status?.state == AviationLayerState.PARTIAL ||
            status?.state == AviationLayerState.EMPTY ||
            status?.showing_last_good == true
    }

    private fun merged_layer_statuses(
        previous: AviationLayerSnapshot,
        fetched: AviationLayerSnapshot,
        decisions: Map<AviationLayerKind, LayerMergeDecision>
    ): Map<AviationLayerKind, AviationLayerStatus> = buildMap {
        AviationLayerKind.entries.forEach { kind ->
            val status = when (decisions.getValue(kind)) {
                LayerMergeDecision.KEEP_UNREQUESTED -> previous.statuses[kind]
                LayerMergeDecision.ACCEPT_FETCHED -> fetched.statuses[kind]
                LayerMergeDecision.RETAIN_LAST_GOOD -> fetched.statuses[kind]?.copy(
                    showing_last_good = true
                )
            }
            status?.let { put(kind, it) }
        }
    }

    private fun merged_layer_publications(
        previous: AviationLayerSnapshot,
        fetched: AviationLayerSnapshot,
        decisions: Map<AviationLayerKind, LayerMergeDecision>
    ): Map<AviationLayerKind, AviationLayerPublication> = buildMap {
        AviationLayerKind.entries.forEach { kind ->
            val publication = when (decisions.getValue(kind)) {
                LayerMergeDecision.KEEP_UNREQUESTED -> previous.publications[kind]
                LayerMergeDecision.ACCEPT_FETCHED -> fetched.publications[kind]
                LayerMergeDecision.RETAIN_LAST_GOOD -> retained_layer_publication(
                    previous.publications[kind],
                    fetched.publications[kind],
                    fetched.statuses[kind]
                )
            }
            publication?.let { put(kind, it) }
        }
    }

    private fun retained_layer_publication(
        previous: AviationLayerPublication?,
        fetched: AviationLayerPublication?,
        fetched_status: AviationLayerStatus?
    ): AviationLayerPublication? {
        previous ?: return null
        val latest_health = fetched?.latest_attempt_health ?: when (fetched_status?.state) {
            AviationLayerState.PARTIAL -> AviationLayerHealth(
                AviationLayerAvailability.USABLE,
                AviationLayerCompleteness.PARTIAL,
                AviationLayerFreshness.CURRENT
            )
            AviationLayerState.UNAVAILABLE -> AviationLayerHealth(
                AviationLayerAvailability.UNAVAILABLE,
                AviationLayerCompleteness.UNKNOWN,
                AviationLayerFreshness.STALE
            )
            else -> previous.latest_attempt_health
        }
        return AviationLayerPublication(
            displayed_health = previous.displayed_health.copy(
                freshness = AviationLayerFreshness.STALE
            ),
            displayed_source_identity = previous.displayed_source_identity,
            latest_attempt_health = latest_health,
            latest_attempt_source_identity = fetched?.latest_attempt_source_identity
        )
    }

    private enum class LayerMergeDecision {
        KEEP_UNREQUESTED,
        ACCEPT_FETCHED,
        RETAIN_LAST_GOOD
    }

    private data class AviationSourceContract(
        val provider: String,
        val service: String,
        val layer: String,
        val viewport_bound: Boolean
    )

    private class AviationLayerFetchToken(val kind: AviationLayerKind) {
        @Volatile
        var cancelled: Boolean = false
            private set

        fun cancel() {
            cancelled = true
        }
    }

    private companion object {
        const val TILE_SIZE = FlightAlertAppSettings.AviationLayer.TILE_SIZE
    }
}
