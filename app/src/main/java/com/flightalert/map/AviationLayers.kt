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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.flightalert.TILE_SIZE
import com.flightalert.ThemeTreatment
import com.flightalert.ui.lerp
import com.flightalert.ui.smooth_step
import com.flightalert.ui.with_alpha
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal fun project_aviation_point_to_screen(
    point: AviationLayerPoint,
    viewport: Viewport,
    lat_lon_to_world: (Double, Double, Double) -> WorldPoint
): ScreenPoint? {
    val world = lat_lon_to_world(point.lat, point.lon, viewport.zoom)
    var screen_x = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
    val world_span = (TILE_SIZE * 2.0.pow(viewport.zoom)).toFloat()
    while (screen_x < -world_span / 2f) screen_x += world_span
    while (screen_x > viewport.width + world_span / 2f) screen_x -= world_span
    val screen_y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
    if (
        screen_x < -viewport.width ||
        screen_x > viewport.width * 2f ||
        screen_y < -viewport.height ||
        screen_y > viewport.height * 2f
    ) {
        return null
    }
    return ScreenPoint(screen_x, screen_y)
}

class AviationLayerClient(
    user_agent: String,
    private val aviation_http_transport: AviationHttpTransport = HttpsAviationHttpTransport(user_agent),
    private val aviation_now_ms: () -> Long = System::currentTimeMillis
) {
    private val aviation_arc_gis_source = AviationArcGisSource(
        transport = aviation_http_transport,
        batch_size = ARC_GIS_BATCH_SIZE,
        now_ms = aviation_now_ms
    )

    fun fetch_layers(
        bounds: AviationLayerBounds,
        include_atc_boundaries: Boolean,
        include_restricted_airspaces: Boolean,
        include_airports: Boolean,
        include_oceanic_tracks: Boolean
    ): AviationLayerSnapshot = fetch_layers(
        bounds = bounds,
        include_atc_boundaries = include_atc_boundaries,
        include_restricted_airspaces = include_restricted_airspaces,
        include_airports = include_airports,
        include_oceanic_tracks = include_oceanic_tracks,
        should_continue = { true }
    )

    fun fetch_layers(
        bounds: AviationLayerBounds,
        include_atc_boundaries: Boolean,
        include_restricted_airspaces: Boolean,
        include_airports: Boolean,
        include_oceanic_tracks: Boolean,
        should_continue: (AviationLayerKind) -> Boolean
    ): AviationLayerSnapshot = fetch_layers(
        query_envelopes = AviationQueryEnvelopePlanner.for_viewport(
            min_lat = bounds.min_lat,
            west_lon = bounds.min_lon,
            max_lat = bounds.max_lat,
            east_lon = bounds.max_lon
        ),
        include_atc_boundaries = include_atc_boundaries,
        include_restricted_airspaces = include_restricted_airspaces,
        include_airports = include_airports,
        include_oceanic_tracks = include_oceanic_tracks,
        should_continue = should_continue
    )

    fun fetch_layers(
        query_envelopes: List<AviationLayerBounds>,
        include_atc_boundaries: Boolean,
        include_restricted_airspaces: Boolean,
        include_airports: Boolean,
        include_oceanic_tracks: Boolean
    ): AviationLayerSnapshot = fetch_layers(
        query_envelopes = query_envelopes,
        include_atc_boundaries = include_atc_boundaries,
        include_restricted_airspaces = include_restricted_airspaces,
        include_airports = include_airports,
        include_oceanic_tracks = include_oceanic_tracks,
        should_continue = { true }
    )

    fun fetch_layers(
        query_envelopes: List<AviationLayerBounds>,
        include_atc_boundaries: Boolean,
        include_restricted_airspaces: Boolean,
        include_airports: Boolean,
        include_oceanic_tracks: Boolean,
        should_continue: (AviationLayerKind) -> Boolean
    ): AviationLayerSnapshot {
        val statuses = mutableMapOf<AviationLayerKind, AviationLayerStatus>()
        val publications = mutableMapOf<AviationLayerKind, AviationLayerPublication>()
        val atc = if (include_atc_boundaries) {
            fetch_arc_gis_layer(
                query_envelopes = query_envelopes,
                layer = ATC_BOUNDARY_LAYER,
                scope = ATC_SCOPE,
                statuses = statuses,
                publications = publications,
                should_continue = should_continue
            )
        } else {
            emptyList()
        }
        val restricted = if (include_restricted_airspaces) {
            fetch_arc_gis_layer(
                query_envelopes = query_envelopes,
                layer = SPECIAL_USE_AIRSPACE_LAYER,
                scope = SPECIAL_USE_SCOPE,
                statuses = statuses,
                publications = publications,
                should_continue = should_continue
            )
        } else {
            emptyList()
        }
        val airports = if (include_airports) {
            fetch_arc_gis_layer(
                query_envelopes = query_envelopes,
                layer = OPERATIONAL_AIRPORT_LAYER,
                scope = AIRPORT_SCOPE,
                statuses = statuses,
                publications = publications,
                should_continue = should_continue
            )
        } else {
            emptyList()
        }
        val oceanic = if (include_oceanic_tracks) {
            fetch_oceanic_tracks(statuses, publications, should_continue)
        } else {
            emptyList()
        }

        return AviationLayerSnapshot(
            atc_boundaries = atc,
            restricted_airspaces = restricted,
            airports = airports,
            oceanic_tracks = oceanic,
            statuses = statuses.toMap(),
            publications = publications.toMap(),
            fetched_at_ms = aviation_now_ms()
        )
    }

    private fun <T> fetch_arc_gis_layer(
        query_envelopes: List<AviationLayerBounds>,
        layer: AviationArcGisLayer<T>,
        scope: String,
        statuses: MutableMap<AviationLayerKind, AviationLayerStatus>,
        publications: MutableMap<AviationLayerKind, AviationLayerPublication>,
        should_continue: (AviationLayerKind) -> Boolean
    ): List<T> {
        val result = aviation_arc_gis_source.fetch(
            query_envelopes,
            layer,
            should_continue = { should_continue(layer.kind) }
        )
        val state = result.health.to_layer_state()
        statuses[layer.kind] = AviationLayerStatus(
            state = state,
            message = result.to_status_message(scope, state)
        )
        publications[layer.kind] = AviationLayerPublication(
            displayed_health = result.health,
            displayed_source_identity = result.identity,
            latest_attempt_health = result.health,
            latest_attempt_source_identity = result.identity
        )
        return result.records
    }

    private fun AviationLayerHealth.to_layer_state(): AviationLayerState = when {
        availability == AviationLayerAvailability.USABLE && can_claim_complete ->
            AviationLayerState.LOADED
        availability == AviationLayerAvailability.USABLE &&
            completeness == AviationLayerCompleteness.PARTIAL &&
            freshness == AviationLayerFreshness.CURRENT -> AviationLayerState.PARTIAL
        availability == AviationLayerAvailability.EMPTY && can_claim_complete ->
            AviationLayerState.EMPTY
        else -> AviationLayerState.UNAVAILABLE
    }

    private fun <T> AviationSourceResult<T>.to_status_message(
        scope: String,
        state: AviationLayerState
    ): String = when (state) {
        AviationLayerState.LOADED -> "${records.size} $scope loaded"
        AviationLayerState.PARTIAL ->
            "Incomplete FAA source response: ${records.size} $scope loaded; $message"
        AviationLayerState.EMPTY -> "No $scope in view"
        AviationLayerState.UNAVAILABLE -> "$scope unavailable; $message"
    }

    private fun fetch_oceanic_tracks(
        statuses: MutableMap<AviationLayerKind, AviationLayerStatus>,
        publications: MutableMap<AviationLayerKind, AviationLayerPublication>,
        should_continue: (AviationLayerKind) -> Boolean
    ): List<AviationOceanicTrack> {
        val result = AviationNatSource(
            transport = aviation_http_transport,
            source_url = URL(NAT_TRACKS_URL),
            now_ms = aviation_now_ms
        ).fetch { should_continue(AviationLayerKind.OCEANIC_TRACKS) }
        val state = when {
            result.health.availability == AviationLayerAvailability.EMPTY -> AviationLayerState.EMPTY
            result.health.is_usable && result.health.can_claim_complete -> AviationLayerState.LOADED
            result.health.is_usable -> AviationLayerState.PARTIAL
            else -> AviationLayerState.UNAVAILABLE
        }
        val message = when (state) {
            AviationLayerState.LOADED -> "${result.records.size} NAT tracks loaded"
            AviationLayerState.PARTIAL ->
                "${result.records.size} NAT tracks loaded from incomplete source evidence"
            AviationLayerState.EMPTY,
            AviationLayerState.UNAVAILABLE -> result.message
        }
        statuses[AviationLayerKind.OCEANIC_TRACKS] = AviationLayerStatus(state, message)
        publications[AviationLayerKind.OCEANIC_TRACKS] = AviationLayerPublication(
            displayed_health = result.health,
            displayed_source_identity = result.identity,
            latest_attempt_health = result.health,
            latest_attempt_source_identity = result.identity
        )
        return result.records
    }

    companion object {
        private const val FAA_ARCGIS_ROOT =
            "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services"
        private const val ARC_GIS_BATCH_SIZE = 200
        private const val ATC_SCOPE = "FAA US-chart ARTCC/FIR/OCA boundaries"
        private const val SPECIAL_USE_SCOPE = "FAA special-use airspace"
        private const val AIRPORT_SCOPE = "FAA US-chart operational airport records"
        private const val NAT_TRACKS_URL = "https://nms.aim.faa.gov/datanat/nat.json"

        private val ATC_BOUNDARY_LAYER = AviationArcGisLayer(
            kind = AviationLayerKind.ATC_BOUNDARIES,
            source_provider = "FAA",
            source_service = "Boundary_Airspace",
            source_layer = "0:Airspace_Boundary",
            metadata_url = "$FAA_ARCGIS_ROOT/Boundary_Airspace/FeatureServer/0",
            query_url = "$FAA_ARCGIS_ROOT/Boundary_Airspace/FeatureServer/0/query",
            where_clause = "TYPE_CODE IN ('ARTCC','FIR','OCA')",
            out_fields =
                "OBJECTID,NAME,IDENT,TYPE_CODE,LOCAL_TYPE,UPPER_VAL,UPPER_UOM," +
                    "UPPER_CODE,LOWER_VAL,LOWER_UOM,LOWER_CODE,CITY,STATE",
            record_id = AviationAirspaceFeature::object_id,
            parser = AviationGeoJsonParser::parse_airspaces
        )

        private val SPECIAL_USE_AIRSPACE_LAYER = AviationArcGisLayer(
            kind = AviationLayerKind.RESTRICTED_AIRSPACES,
            source_provider = "FAA",
            source_service = "Special_Use_Airspace",
            source_layer = "0:Special_Use_Airspace",
            metadata_url = "$FAA_ARCGIS_ROOT/Special_Use_Airspace/FeatureServer/0",
            query_url = "$FAA_ARCGIS_ROOT/Special_Use_Airspace/FeatureServer/0/query",
            where_clause = "1=1",
            out_fields =
                "OBJECTID,NAME,TYPE_CODE,UPPER_VAL,LOWER_VAL,UPPER_UOM,LOWER_UOM," +
                    "UPPER_CODE,LOWER_CODE,TIMESOFUSE,CITY,STATE",
            record_id = AviationAirspaceFeature::object_id,
            parser = AviationGeoJsonParser::parse_airspaces
        )

        private val OPERATIONAL_AIRPORT_LAYER = AviationArcGisLayer(
            kind = AviationLayerKind.AIRPORTS,
            source_provider = "FAA",
            source_service = "US_Airport",
            source_layer = "0:Airports",
            metadata_url = "$FAA_ARCGIS_ROOT/US_Airport/FeatureServer/0",
            query_url = "$FAA_ARCGIS_ROOT/US_Airport/FeatureServer/0/query",
            where_clause = "OPERSTATUS = 'OPERATIONAL'",
            out_fields = "OBJECTID,IDENT,NAME,ICAO_ID,TYPE_CODE,MIL_CODE",
            record_id = AviationAirportFeature::object_id,
            parser = AviationGeoJsonParser::parse_airports
        )
    }
}

enum class AviationLayerKind(val display_name: String) {
    ATC_BOUNDARIES("FAA US-chart ARTCC/FIR/OCA boundaries"),
    RESTRICTED_AIRSPACES("FAA special-use airspace"),
    AIRPORTS("FAA US-chart operational airport records"),
    OCEANIC_TRACKS("FAA NAT tracks")
}

enum class AviationLayerState {
    LOADED,
    PARTIAL,
    EMPTY,
    UNAVAILABLE
}
data class AviationLayerStatus(
    val state: AviationLayerState,
    val message: String,
    val showing_last_good: Boolean = false
)

data class AviationLayerPublication(
    val displayed_health: AviationLayerHealth,
    val displayed_source_identity: AviationSourceIdentity?,
    val latest_attempt_health: AviationLayerHealth,
    val latest_attempt_source_identity: AviationSourceIdentity?
)

data class AviationLayerSnapshot(
    val atc_boundaries: List<AviationAirspaceFeature>,
    val restricted_airspaces: List<AviationAirspaceFeature>,
    val airports: List<AviationAirportFeature>,
    val oceanic_tracks: List<AviationOceanicTrack>,
    val statuses: Map<AviationLayerKind, AviationLayerStatus>,
    val fetched_at_ms: Long,
    val publications: Map<AviationLayerKind, AviationLayerPublication> = emptyMap()
)

data class AviationLayerBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
) {
    fun arc_gis_envelope(): String = "$min_lon,$min_lat,$max_lon,$max_lat"
}

internal const val AVIATION_AIRPORT_LABEL_MIN_ZOOM = 8.4

fun List<AviationLayerPoint>.to_bounds(): AviationGeoBounds {
    if (isEmpty()) return AviationGeoBounds(0.0, 0.0, 0.0, 0.0)
    var min_lat = first().lat
    var max_lat = first().lat
    var min_lon = first().lon
    var max_lon = first().lon
    forEach { point ->
        min_lat = min(min_lat, point.lat)
        max_lat = max(max_lat, point.lat)
        min_lon = min(min_lon, point.lon)
        max_lon = max(max_lon, point.lon)
    }
    return AviationGeoBounds(min_lat, min_lon, max_lat, max_lon)
}

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

data class AviationLayerVisibility(
    val restricted_airspaces_enabled: Boolean,
    val atc_boundaries_enabled: Boolean,
    val oceanic_tracks_enabled: Boolean,
    val airport_labels_enabled: Boolean
)

data class AviationLayerStyle(
    val accent_orange: Int,
    val danger: Int,
    val accent_blue: Int,
    val accent_green: Int,
    val accent_pink: Int,
    val accent_yellow: Int,
    val military_gray: Int,
    val panel: Int,
    val text: Int,
    val treatment: ThemeTreatment
)

internal object AviationAirspaceRenderPolicy {
    fun forEachApplicable(
        features: List<AviationAirspaceFeature>,
        visibleBounds: AviationGeoBounds,
        excludedFeature: AviationAirspaceFeature?,
        action: (AviationAirspaceFeature) -> Unit
    ) {
        for (feature in features) {
            if (isApplicable(feature, visibleBounds, excludedFeature)) action(feature)
        }
    }

    fun isApplicable(
        feature: AviationAirspaceFeature,
        visibleBounds: AviationGeoBounds,
        excludedFeature: AviationAirspaceFeature?
    ): Boolean {
        if (feature == excludedFeature ||
            feature.bounds.max_lat < visibleBounds.min_lat ||
            feature.bounds.min_lat > visibleBounds.max_lat
        ) return false
        val rawLongitudeSpan = feature.bounds.max_lon - feature.bounds.min_lon
        val span = if (rawLongitudeSpan <= 180.0) {
            LongitudeSpan(feature.bounds.min_lon, feature.bounds.max_lon)
        } else {
            longitudeSpan(feature)
        }
        return longitudeSpansIntersect(
            span.min,
            span.max,
            visibleBounds.min_lon,
            visibleBounds.max_lon
        )
    }

    fun forEachSourcePolygon(
        feature: AviationAirspaceFeature,
        action: (AviationPolygon) -> Unit
    ) {
        for (polygon in feature.geometry.polygons) action(polygon)
    }

    fun forEachSourceRing(
        polygon: AviationPolygon,
        action: (List<AviationLayerPoint>) -> Unit
    ) {
        for (ring in polygon.all_rings) action(ring)
    }

    fun contains(feature: AviationAirspaceFeature, lat: Double, lon: Double): Boolean {
        val featureAnchorLon = feature.geometry.polygons.first().shell.first().lon
        return feature.geometry.polygons.any { polygon ->
            val polygonAnchorLon = unwrapLongitude(polygon.shell.first().lon, featureAnchorLon)
            pointInsideRing(lat, lon, polygon.shell, polygonAnchorLon) &&
                    polygon.holes.none { hole ->
                        pointInsideRing(lat, lon, hole, polygonAnchorLon)
                    }
        }
    }

    fun boundsArea(feature: AviationAirspaceFeature): Double {
        val latitudeSpan = (feature.bounds.max_lat - feature.bounds.min_lat).coerceAtLeast(0.0)
        val rawLongitudeSpan = (feature.bounds.max_lon - feature.bounds.min_lon).coerceAtLeast(0.0)
        val longitudeWidth = if (rawLongitudeSpan <= 180.0) {
            rawLongitudeSpan
        } else {
            val span = longitudeSpan(feature)
            (span.max - span.min).coerceIn(0.0, 360.0)
        }
        return latitudeSpan * longitudeWidth
    }

    fun unwrapLongitude(lon: Double, referenceLon: Double): Double {
        var unwrapped = lon
        while (unwrapped - referenceLon > 180.0) unwrapped -= 360.0
        while (unwrapped - referenceLon < -180.0) unwrapped += 360.0
        return unwrapped
    }

    fun firstWrappedShift(
        baseLeft: Float,
        baseRight: Float,
        extendedLeft: Float,
        extendedRight: Float,
        worldSpan: Float,
        maxCopies: Int = 8
    ): Float {
        if (worldSpan <= 0f || !worldSpan.isFinite() || maxCopies <= 0) return 0f
        var shift = 0f
        var copies = 0
        while (baseLeft + shift > extendedRight && copies++ < maxCopies) shift -= worldSpan
        copies = 0
        while (baseRight + shift < extendedLeft && copies++ < maxCopies) shift += worldSpan
        return shift
    }

    private fun pointInsideRing(
        lat: Double,
        lon: Double,
        ring: List<AviationLayerPoint>,
        anchorLon: Double
    ): Boolean {
        if (ring.size < 3) return false
        val first = ring.first()
        val firstLon = unwrapLongitude(first.lon, anchorLon)
        val queryLon = unwrapLongitude(lon, firstLon)
        var inside = false
        var previousLat = first.lat
        var previousLon = firstLon
        for (index in 1 until ring.size) {
            val current = ring[index]
            val currentLon = unwrapLongitude(current.lon, previousLon)
            val crossesLat = (current.lat > lat) != (previousLat > lat)
            if (crossesLat) {
                val lonAtLat = (previousLon - currentLon) *
                        (lat - current.lat) /
                        (previousLat - current.lat) +
                        currentLon
                if (queryLon < lonAtLat) inside = !inside
            }
            previousLat = current.lat
            previousLon = currentLon
        }
        return inside
    }

    internal fun longitudeSpansIntersect(
        firstMin: Double,
        firstMax: Double,
        secondMin: Double,
        secondMax: Double
    ): Boolean {
        if (firstMax - firstMin >= 360.0 || secondMax - secondMin >= 360.0) return true
        if (secondMin > secondMax) {
            return longitudeSpansIntersect(firstMin, firstMax, secondMin, 180.0) ||
                    longitudeSpansIntersect(firstMin, firstMax, -180.0, secondMax)
        }
        for (worldCopy in -2..2) {
            val shift = worldCopy * 360.0
            if (firstMax + shift >= secondMin && firstMin + shift <= secondMax) return true
        }
        return false
    }

    private fun longitudeSpan(feature: AviationAirspaceFeature): LongitudeSpan {
        val featureAnchorLon = feature.geometry.polygons.first().shell.first().lon
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (polygon in feature.geometry.polygons) {
            val polygonAnchorLon = unwrapLongitude(polygon.shell.first().lon, featureAnchorLon)
            for (ring in polygon.all_rings) {
                var previousLon = polygonAnchorLon
                for (point in ring) {
                    val currentLon = unwrapLongitude(point.lon, previousLon)
                    minLon = min(minLon, currentLon)
                    maxLon = max(maxLon, currentLon)
                    previousLon = currentLon
                }
            }
        }
        return LongitudeSpan(minLon, maxLon)
    }

    private data class LongitudeSpan(val min: Double, val max: Double)
}

internal class AviationAirspacePreparationTracker {
    private var restrictedSource: List<AviationAirspaceFeature>? = null
    private var atcSource: List<AviationAirspaceFeature>? = null

    fun shouldPrepareRestricted(
        source: List<AviationAirspaceFeature>,
        enabled: Boolean
    ): Boolean {
        if (!enabled || restrictedSource === source) return false
        restrictedSource = source
        return true
    }

    fun shouldPrepareAtc(
        source: List<AviationAirspaceFeature>,
        enabled: Boolean
    ): Boolean {
        if (!enabled || atcSource === source) return false
        atcSource = source
        return true
    }
}

internal class AviationLayerRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val ellipsize: (String, Float) -> String,
    private val now_epoch_ms: () -> Long = System::currentTimeMillis
) {
    private val airspace_preparation_tracker = AviationAirspacePreparationTracker()
    private val selection_hit_collector = AviationSelectionHitCollector(
        lat_lon_to_world = MapProjection::lat_lon_to_world,
        world_to_lat_lon = MapProjection::world_to_lat_lon
    )
    private var prepared_restricted_airspaces: List<PreparedAirspaceFeature> = emptyList()
    private var prepared_atc_boundaries: List<PreparedAirspaceFeature> = emptyList()
    private val layer_label_rects = ArrayList<RectF>(16)
    private var settled_cache_bitmap: Bitmap? = null
    private var settled_cache_canvas: Canvas? = null
    private var settled_cache_key: SettledLayerCacheKey? = null
    private val settled_cache_matrix = Matrix()

    fun draw_layers(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selection: AviationSelection? = null,
        interaction_active: Boolean = false
    ) {
        val draw_time_epoch_ms = if (visibility.oceanic_tracks_enabled) now_epoch_ms() else 0L
        prepare_snapshot_if_needed(snapshot, visibility)
        if (interaction_active && draw_transformed_settled_cache(
                canvas,
                viewport,
                snapshot,
                visibility,
                style,
                selection,
                draw_time_epoch_ms
            )
        ) {
            return
        }
        if (!interaction_active && draw_settled_cache_if_current(
                canvas,
                viewport,
                snapshot,
                visible_bounds,
                visibility,
                style,
                selection,
                draw_time_epoch_ms
            )
        ) {
            return
        }
        if (!interaction_active && draw_into_settled_cache(
                canvas,
                viewport,
                snapshot,
                visible_bounds,
                visibility,
                style,
                selection,
                draw_time_epoch_ms
            )
        ) {
            return
        }
        draw_layers_direct(
            canvas = canvas,
            viewport = viewport,
            snapshot = snapshot,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = style,
            selection = selection,
            interaction_active = interaction_active,
            draw_time_epoch_ms = draw_time_epoch_ms
        )
    }

    fun selection_key_at(
        x: Float,
        y: Float,
        density: Float,
        snapshot: AviationLayerSnapshot,
        viewport: Viewport,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        now_epoch_ms: Long = this.now_epoch_ms()
    ): AviationSelectionKey? = selection_hit_collector.selection_key_at(
        x = x,
        y = y,
        density = density,
        snapshot = snapshot,
        viewport = viewport,
        visible_bounds = visible_bounds,
        visibility = visibility,
        now_epoch_ms = now_epoch_ms
    )

    private fun draw_settled_cache_if_current(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selection: AviationSelection?,
        draw_time_epoch_ms: Long
    ): Boolean {
        val bitmap = settled_cache_bitmap ?: return false
        val key = settled_cache_key ?: return false
        if (key != settled_layer_cache_key(
                viewport,
                snapshot,
                visible_bounds,
                visibility,
                style,
                selection,
                draw_time_epoch_ms
            )
        ) return false
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return true
    }

    private fun draw_transformed_settled_cache(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selection: AviationSelection?,
        draw_time_epoch_ms: Long
    ): Boolean {
        val bitmap = settled_cache_bitmap ?: return false
        val key = settled_cache_key ?: return false
        if (bitmap.isRecycled) return false
        if (key.snapshot_identity != System.identityHashCode(snapshot) ||
            key.width != viewport.width.toInt() ||
            key.height != viewport.height.toInt() ||
            key.visibility != visibility ||
            key.style != style ||
            key.nat_temporal_state_token != nat_temporal_state_token(
                snapshot,
                visibility,
                draw_time_epoch_ms
            ) ||
            key.selection_key != selection?.key
        ) {
            return false
        }
        val zoom_delta = viewport.zoom - key.zoom
        if (abs(zoom_delta) > MAX_TRANSFORMED_CACHE_ZOOM_DELTA) return false
        val scale = 2.0.pow(zoom_delta).toFloat()
        if (scale <= 0f || !scale.isFinite()) return false
        val translation_x =
            (key.center_x * scale - viewport.center_x + viewport.width / 2.0 - key.width * scale / 2.0).toFloat()
        val translation_y =
            (key.center_y * scale - viewport.center_y + viewport.height / 2.0 - key.height * scale / 2.0).toFloat()
        if (abs(translation_x) > viewport.width * MAX_TRANSFORMED_CACHE_TRANSLATION_FRACTION ||
            abs(translation_y) > viewport.height * MAX_TRANSFORMED_CACHE_TRANSLATION_FRACTION
        ) {
            return false
        }
        settled_cache_matrix.reset()
        settled_cache_matrix.setScale(scale, scale)
        settled_cache_matrix.postTranslate(translation_x, translation_y)
        canvas.drawBitmap(bitmap, settled_cache_matrix, null)
        return true
    }

    private fun draw_into_settled_cache(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selection: AviationSelection?,
        draw_time_epoch_ms: Long
    ): Boolean {
        val layer_canvas = settled_cache_canvas_for(viewport) ?: return false
        val bitmap = settled_cache_bitmap ?: return false
        layer_canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        draw_layers_direct(
            canvas = layer_canvas,
            viewport = viewport,
            snapshot = snapshot,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = style,
            selection = selection,
            interaction_active = false,
            draw_time_epoch_ms = draw_time_epoch_ms
        )
        settled_cache_key = settled_layer_cache_key(
            viewport,
            snapshot,
            visible_bounds,
            visibility,
            style,
            selection,
            draw_time_epoch_ms
        )
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return true
    }

    private fun settled_cache_canvas_for(viewport: Viewport): Canvas? {
        val width = viewport.width.toInt().coerceAtLeast(1)
        val height = viewport.height.toInt().coerceAtLeast(1)
        val current = settled_cache_bitmap
        if (current != null && current.width == width && current.height == height && !current.isRecycled) {
            return settled_cache_canvas
        }
        current?.recycle()
        return try {
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            settled_cache_bitmap = bitmap
            settled_cache_canvas = Canvas(bitmap)
            settled_cache_key = null
            settled_cache_canvas
        } catch (_: OutOfMemoryError) {
            settled_cache_bitmap = null
            settled_cache_canvas = null
            settled_cache_key = null
            null
        }
    }

    private fun settled_layer_cache_key(
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selection: AviationSelection?,
        draw_time_epoch_ms: Long
    ): SettledLayerCacheKey {
        val binding = AviationLayerRenderCachePolicy.binding(snapshot, selection)
        return SettledLayerCacheKey(
            snapshot_identity = binding.snapshot_identity,
            width = viewport.width.toInt(),
            height = viewport.height.toInt(),
            zoom = viewport.zoom,
            center_x = viewport.center_x,
            center_y = viewport.center_y,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = style,
            nat_temporal_state_token = nat_temporal_state_token(
                snapshot,
                visibility,
                draw_time_epoch_ms
            ),
            selection_key = binding.selection_key
        )
    }

    private fun nat_temporal_state_token(
        snapshot: AviationLayerSnapshot,
        visibility: AviationLayerVisibility,
        draw_time_epoch_ms: Long
    ): Long = if (visibility.oceanic_tracks_enabled) {
        AviationNatRenderPolicy.temporal_state_token(snapshot.oceanic_tracks, draw_time_epoch_ms)
    } else {
        0L
    }

    private fun draw_layers_direct(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selection: AviationSelection?,
        interaction_active: Boolean,
        draw_time_epoch_ms: Long
    ) {
        layer_label_rects.clear()
        selection_hit_collector.begin_draw(snapshot, viewport, visible_bounds, visibility)
        val selected_restricted_airspace =
            (selection as? AviationSelection.SpecialUse)?.feature
        if (visibility.restricted_airspaces_enabled) {
            draw_airspace_layer(
                canvas = canvas,
                viewport = viewport,
                features = prepared_restricted_airspaces,
                visible_bounds = visible_bounds,
                excluded_feature = selected_restricted_airspace,
                stroke = restricted_airspace_stroke(style),
                fill = restricted_airspace_fill(style),
                label_rects = layer_label_rects,
                style = style,
                restricted = true,
                interaction_active = interaction_active
            )
            selected_restricted_airspace
                ?.takeIf { selected ->
                    AviationAirspaceRenderPolicy.isApplicable(selected, visible_bounds, null)
                }
                ?.let { selected ->
                    val selected_prepared =
                        prepared_restricted_airspaces.firstOrNull { it.source == selected }
                            ?: prepare_airspace_feature(selected)
                            ?: return@let
                    draw_selected_airspace(canvas, viewport, selected, style)
                    if (viewport.zoom >= AIRSPACE_LABEL_MIN_ZOOM) {
                        draw_airspace_label(
                            canvas,
                            viewport,
                            selected_prepared,
                            layer_label_rects,
                            style
                        )
                    }
                }
        }
        if (visibility.atc_boundaries_enabled) {
            val focused_atc_feature = focused_airspace_feature(
                viewport = viewport,
                features = prepared_atc_boundaries,
                visible_bounds = visible_bounds
            )
            draw_airspace_layer(
                canvas = canvas,
                viewport = viewport,
                features = prepared_atc_boundaries,
                visible_bounds = visible_bounds,
                excluded_feature = null,
                stroke = style.accent_blue,
                fill = style.accent_blue,
                label_rects = layer_label_rects,
                style = style,
                restricted = false,
                focused_feature = focused_atc_feature,
                interaction_active = interaction_active
            )
            (selection as? AviationSelection.AtcBoundary)
                ?.feature
                ?.takeIf { selected ->
                    AviationAirspaceRenderPolicy.isApplicable(selected, visible_bounds, null)
                }
                ?.let { selected ->
                    draw_selected_airspace_boundary(
                        canvas = canvas,
                        viewport = viewport,
                        feature = selected,
                        halo = style.panel,
                        core = style.accent_blue
                    )
                }
        }
        if (visibility.oceanic_tracks_enabled) {
            val active_tracks = AviationNatRenderPolicy.select_active_tracks(
                tracks = snapshot.oceanic_tracks,
                visible_bounds = visible_bounds,
                limit = snapshot.oceanic_tracks.size,
                now_epoch_ms = draw_time_epoch_ms
            )
            draw_oceanic_tracks(
                canvas = canvas,
                viewport = viewport,
                tracks = active_tracks,
                label_rects = layer_label_rects,
                style = style
            )
            (selection as? AviationSelection.OceanicTrack)
                ?.track
                ?.takeIf(active_tracks::contains)
                ?.let { selected ->
                    draw_selected_oceanic_track(canvas, viewport, selected, style)
                }
        }
        if (visibility.airport_labels_enabled) {
            draw_airport_labels(
                canvas = canvas,
                viewport = viewport,
                airports = snapshot.airports,
                label_rects = layer_label_rects,
                style = style
            )
            (selection as? AviationSelection.Airport)?.airport?.let { selected ->
                draw_selected_airport(canvas, viewport, selected, style)
            }
        }
    }

    private fun prepare_snapshot_if_needed(
        snapshot: AviationLayerSnapshot,
        visibility: AviationLayerVisibility
    ) {
        if (airspace_preparation_tracker.shouldPrepareRestricted(
                snapshot.restricted_airspaces,
                visibility.restricted_airspaces_enabled
            )
        ) {
            prepared_restricted_airspaces = prepare_airspace_features(snapshot.restricted_airspaces)
        }
        if (airspace_preparation_tracker.shouldPrepareAtc(
                snapshot.atc_boundaries,
                visibility.atc_boundaries_enabled
            )
        ) {
            prepared_atc_boundaries = prepare_airspace_features(snapshot.atc_boundaries)
        }
    }

    private fun prepare_airspace_features(features: List<AviationAirspaceFeature>): List<PreparedAirspaceFeature> {
        return features
            .mapNotNull(::prepare_airspace_feature)
            .sortedBy { it.point_count }
    }

    private fun prepare_airspace_feature(feature: AviationAirspaceFeature): PreparedAirspaceFeature? {
        val polygons = ArrayList<PreparedAirspacePolygon>(feature.geometry.polygons.size)
        val feature_anchor_lon = feature.geometry.polygons.first().shell.first().lon
        AviationAirspaceRenderPolicy.forEachSourcePolygon(feature) { polygon ->
            prepare_airspace_polygon(polygon, feature_anchor_lon)?.let(polygons::add)
        }
        if (polygons.isEmpty()) return null
        val center_geo = MapProjection.world_to_lat_lon(
            x = (polygons.minOf { it.min_x } + polygons.maxOf { it.max_x }) / 2.0,
            y = (polygons.minOf { it.min_y } + polygons.maxOf { it.max_y }) / 2.0,
            zoom = 0.0
        )
        val min_lon = polygons.minOf { it.min_x }.toDouble() / TILE_SIZE * 360.0 - 180.0
        val max_lon = polygons.maxOf { it.max_x }.toDouble() / TILE_SIZE * 360.0 - 180.0
        val label = AviationMapTextAdapter.airspace(feature).primary_text
        return PreparedAirspaceFeature(
            source = feature,
            label = label,
            center = AviationLayerPoint(center_geo.lat, center_geo.lon),
            point_count = polygons.sumOf { it.point_count },
            min_unwrapped_lon = min_lon,
            max_unwrapped_lon = max_lon,
            polygons = polygons
        )
    }

    private fun prepare_airspace_polygon(
        polygon: AviationPolygon,
        feature_anchor_lon: Double
    ): PreparedAirspacePolygon? {
        val world_path = Path()
        world_path.fillType = Path.FillType.EVEN_ODD
        var point_count = 0
        var min_x = Float.POSITIVE_INFINITY
        var max_x = Float.NEGATIVE_INFINITY
        var min_y = Float.POSITIVE_INFINITY
        var max_y = Float.NEGATIVE_INFINITY
        val polygon_anchor_lon = AviationAirspaceRenderPolicy.unwrapLongitude(
            polygon.shell.first().lon,
            feature_anchor_lon
        )
        AviationAirspaceRenderPolicy.forEachSourceRing(polygon) { ring ->
            var ring_point_index = 0
            var previous_lon = polygon_anchor_lon
            ring.forEach { point ->
                val unwrapped_lon = AviationAirspaceRenderPolicy.unwrapLongitude(
                    point.lon,
                    previous_lon
                )
                val world = MapProjection.lat_lon_to_world(point.lat, unwrapped_lon, 0.0)
                val x = world.x.toFloat()
                val y = world.y.toFloat()
                if (ring_point_index == 0) world_path.moveTo(x, y) else world_path.lineTo(x, y)
                min_x = min(min_x, x)
                max_x = max(max_x, x)
                min_y = min(min_y, y)
                max_y = max(max_y, y)
                ring_point_index++
                point_count++
                previous_lon = unwrapped_lon
            }
            world_path.close()
        }
        if (point_count < 3) return null
        return PreparedAirspacePolygon(
            point_count = point_count,
            path = world_path,
            min_x = min_x,
            max_x = max_x,
            min_y = min_y,
            max_y = max_y
        )
    }

    private fun draw_airspace_layer(
        canvas: Canvas,
        viewport: Viewport,
        features: List<PreparedAirspaceFeature>,
        visible_bounds: AviationGeoBounds,
        excluded_feature: AviationAirspaceFeature?,
        stroke: Int,
        fill: Int,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle,
        restricted: Boolean,
        focused_feature: PreparedAirspaceFeature? = null,
        interaction_active: Boolean
    ) {
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        val low_zoom_emphasis = layer_low_zoom_emphasis(viewport.zoom, restricted)
        val base_stroke_width_dp = when {
            interaction_active && restricted -> 1.25f
            interaction_active -> 1.0f
            restricted && viewport.zoom >= 8.0 -> 2.0f
            viewport.zoom >= 8.0 -> 1.6f
            restricted -> 1.35f
            else -> 1.1f
        }
        val min_stroke_width_dp = if (restricted) 0.55f else 0.42f
        stroke_paint.strokeWidth =
            dp(lerp(min_stroke_width_dp, base_stroke_width_dp, low_zoom_emphasis))
        val raw_stroke_alpha = when {
            interaction_active && viewport.zoom >= 8.0 -> 190
            interaction_active -> 150
            viewport.zoom >= 8.0 -> 220
            else -> 170
        }
        val min_stroke_alpha_scale = if (restricted) 0.40f else 0.26f
        val stroke_alpha_scale = lerp(min_stroke_alpha_scale, 1f, low_zoom_emphasis)
        val base_stroke_alpha = (raw_stroke_alpha * stroke_alpha_scale).toInt().coerceIn(24, 235)
        paint.style = Paint.Style.FILL
        val fill_zoom_emphasis = smooth_step(5.3f, 8.5f, viewport.zoom.toFloat())
        val raw_fill_alpha =
            if (restricted && viewport.zoom >= 8.5) 28 else if (viewport.zoom >= 8.5) 18 else 10
        val base_fill_alpha = (raw_fill_alpha * fill_zoom_emphasis).toInt()

        for (feature in features) {
            if (!prepared_airspace_is_applicable(
                    feature,
                    visible_bounds,
                    excluded_feature
                )
            ) continue
            val is_focused = focused_feature == null || focused_feature === feature
            val fill_alpha = when {
                interaction_active -> 0
                restricted -> base_fill_alpha
                focused_feature == null -> 0
                is_focused -> base_fill_alpha
                else -> 0
            }
            val stroke_alpha = when {
                restricted -> base_stroke_alpha
                focused_feature == null -> (base_stroke_alpha * 0.72f).toInt()
                is_focused -> base_stroke_alpha
                else -> (base_stroke_alpha * 0.58f).toInt()
            }
            paint.color = with_alpha(fill, fill_alpha)
            stroke_paint.color = with_alpha(stroke, stroke_alpha)
            feature.polygons.forEach { polygon ->
                draw_prepared_airspace_polygon(
                    canvas = canvas,
                    viewport = viewport,
                    polygon = polygon,
                    fill_paint = paint.takeIf { fill_alpha > 0 },
                    outline_paint = stroke_paint
                )
            }
            if (!interaction_active && viewport.zoom >= AIRSPACE_LABEL_MIN_ZOOM) {
                val accepted_label = draw_airspace_label(
                    canvas,
                    viewport,
                    feature,
                    label_rects,
                    style
                )
                if (!restricted && accepted_label != null) {
                    selection_hit_collector.accept_atc_label(
                        feature.source,
                        accepted_label.point,
                        accepted_label.label_bounds
                    )
                }
            }
        }
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun layer_low_zoom_emphasis(zoom: Double, restricted: Boolean): Float {
        val start = if (restricted) 3.2f else 3.7f
        val end = if (restricted) 7.0f else 7.4f
        return smooth_step(start, end, zoom.toFloat())
    }

    private fun focused_airspace_feature(
        viewport: Viewport,
        features: List<PreparedAirspaceFeature>,
        visible_bounds: AviationGeoBounds
    ): PreparedAirspaceFeature? {
        val center =
            MapProjection.world_to_lat_lon(viewport.center_x, viewport.center_y, viewport.zoom)
        val point = AviationLayerPoint(center.lat, center.lon)
        return features.firstOrNull { feature ->
            prepared_airspace_is_applicable(feature, visible_bounds, null) &&
                    point_inside_airspace(point, feature.source)
        }
    }

    private fun prepared_airspace_is_applicable(
        feature: PreparedAirspaceFeature,
        visible_bounds: AviationGeoBounds,
        excluded_feature: AviationAirspaceFeature?
    ): Boolean {
        if (feature.source == excluded_feature ||
            feature.source.bounds.max_lat < visible_bounds.min_lat ||
            feature.source.bounds.min_lat > visible_bounds.max_lat
        ) return false
        return AviationAirspaceRenderPolicy.longitudeSpansIntersect(
            feature.min_unwrapped_lon,
            feature.max_unwrapped_lon,
            visible_bounds.min_lon,
            visible_bounds.max_lon
        )
    }

    private fun point_inside_airspace(
        point: AviationLayerPoint,
        feature: AviationAirspaceFeature
    ): Boolean = AviationAirspaceRenderPolicy.contains(feature, point.lat, point.lon)

    private fun draw_selected_airspace(
        canvas: Canvas,
        viewport: Viewport,
        feature: AviationAirspaceFeature,
        style: AviationLayerStyle
    ) {
        val prepared = prepared_restricted_airspaces.firstOrNull { it.source == feature }
            ?: prepare_airspace_feature(feature)
            ?: return
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(
            restricted_airspace_fill(style),
            selected_restricted_airspace_fill_alpha(style)
        )
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(3.0f)
        stroke_paint.color = with_alpha(selected_restricted_airspace_stroke(style), 235)
        prepared.polygons.forEach { polygon ->
            draw_prepared_airspace_polygon(canvas, viewport, polygon, paint, stroke_paint)
        }
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_selected_airspace_boundary(
        canvas: Canvas,
        viewport: Viewport,
        feature: AviationAirspaceFeature,
        halo: Int,
        core: Int
    ) {
        val prepared = prepared_atc_boundaries.firstOrNull { it.source == feature }
            ?: prepare_airspace_feature(feature)
            ?: return
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(5.2f)
        stroke_paint.color = with_alpha(halo, 230)
        prepared.polygons.forEach { polygon ->
            draw_prepared_airspace_polygon(canvas, viewport, polygon, null, stroke_paint)
        }
        stroke_paint.strokeWidth = dp(2.4f)
        stroke_paint.color = with_alpha(core, 255)
        prepared.polygons.forEach { polygon ->
            draw_prepared_airspace_polygon(canvas, viewport, polygon, null, stroke_paint)
        }
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_prepared_airspace_polygon(
        canvas: Canvas,
        viewport: Viewport,
        polygon: PreparedAirspacePolygon,
        fill_paint: Paint?,
        outline_paint: Paint
    ) {
        val scale = 2.0.pow(viewport.zoom).toFloat()
        if (scale <= 0f || !scale.isFinite()) return
        val world_span = TILE_SIZE * scale
        val screen_offset_x = (-viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_offset_y = (-viewport.center_y + viewport.height / 2.0).toFloat()
        draw_prepared_airspace_polygon_path(
            canvas = canvas,
            viewport = viewport,
            polygon = polygon,
            scale = scale,
            world_span = world_span,
            screen_offset_x = screen_offset_x,
            screen_offset_y = screen_offset_y,
            fill_paint = fill_paint,
            outline_paint = outline_paint
        )
    }

    private fun draw_prepared_airspace_polygon_path(
        canvas: Canvas,
        viewport: Viewport,
        polygon: PreparedAirspacePolygon,
        scale: Float,
        world_span: Float,
        screen_offset_x: Float,
        screen_offset_y: Float,
        fill_paint: Paint?,
        outline_paint: Paint
    ) {
        if (polygon.point_count < 3 || scale <= 0f || !scale.isFinite()) return
        val extended_left = -viewport.width * 0.5f
        val extended_right = viewport.width * 1.5f
        val extended_top = -viewport.height * 0.5f
        val extended_bottom = viewport.height * 1.5f
        val screen_top = polygon.min_y * scale + screen_offset_y
        val screen_bottom = polygon.max_y * scale + screen_offset_y
        if (screen_bottom < extended_top || screen_top > extended_bottom) return

        val base_left = polygon.min_x * scale + screen_offset_x
        val base_right = polygon.max_x * scale + screen_offset_x
        var shift_x = AviationAirspaceRenderPolicy.firstWrappedShift(
            baseLeft = base_left,
            baseRight = base_right,
            extendedLeft = extended_left,
            extendedRight = extended_right,
            worldSpan = world_span,
            maxCopies = MAX_WRAPPED_RING_COPIES
        )
        var guard = 0
        val original_stroke_width = outline_paint.strokeWidth
        outline_paint.strokeWidth = original_stroke_width / scale
        try {
            while (base_left + shift_x <= extended_right && guard++ < MAX_WRAPPED_RING_COPIES) {
                if (base_right + shift_x >= extended_left) {
                    canvas.withTranslation(screen_offset_x + shift_x, screen_offset_y) {
                        scale(scale, scale)
                        if (fill_paint != null) drawPath(polygon.path, fill_paint)
                        drawPath(polygon.path, outline_paint)
                    }
                }
                shift_x += world_span
            }
        } finally {
            outline_paint.strokeWidth = original_stroke_width
        }
    }

    private fun draw_airspace_label(
        canvas: Canvas,
        viewport: Viewport,
        feature: PreparedAirspaceFeature,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ): AviationAcceptedLabelGeometry? {
        val screen = project_aviation_point_to_screen(
            feature.center,
            viewport,
            MapProjection::lat_lon_to_world
        ) ?: return null
        if (screen.x !in 0f..viewport.width || screen.y !in 0f..viewport.height) return null
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize =
            sp(if (feature.source.type.equals("R", ignoreCase = true)) 11f else 10f)
        text_paint.color = with_alpha(style.text, 224)
        val display = ellipsize(feature.label, dp(142f))
        val width = text_paint.measureText(display) + dp(14f)
        val rect = RectF(
            screen.x - width / 2f,
            screen.y - dp(20f),
            screen.x + width / 2f,
            screen.y + dp(3f)
        )
        if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return null
        val padded = rect.padded_copy(dp(3f))
        if (label_rects.any { RectF.intersects(padded, it) }) return null
        label_rects += padded
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(style.panel, 184)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), paint)
        canvas.drawText(display, rect.centerX(), rect.bottom - dp(7f), text_paint)
        text_paint.isFakeBoldText = false
        return AviationAcceptedLabelGeometry(
            point = screen,
            label_bounds = AviationScreenRect(rect.left, rect.top, rect.right, rect.bottom)
        )
    }

    private fun draw_oceanic_tracks(
        canvas: Canvas,
        viewport: Viewport,
        tracks: List<AviationOceanicTrack>,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ) {
        if (viewport.zoom < OCEANIC_TRACK_MIN_ZOOM) return
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(2.2f)
        stroke_paint.color = with_alpha(style.accent_pink, 215)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(11f)
        text_paint.color = style.accent_pink

        tracks.forEach { track ->
            val label = AviationMapTextAdapter.oceanic_track(track).primary_text
            var track_label_point: ScreenPoint? = null
            track.drawable_segments.forEach segment_loop@{ segment ->
                val points = ring_to_screen_points(segment, viewport)
                if (points.size < 2) return@segment_loop
                path.reset()
                points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                canvas.drawPath(path, stroke_paint)
                if (track_label_point == null) {
                    track_label_point = points.getOrNull(points.size / 2)
                }
            }
            val label_point = track_label_point ?: return@forEach
            canvas.drawCircle(label_point.x, label_point.y, dp(3f), paint.apply {
                this.style = Paint.Style.FILL
                color = style.accent_pink
            })
            val label_width = text_paint.measureText(label)
            val label_rect = RectF(
                label_point.x - label_width / 2f,
                label_point.y - dp(24f),
                label_point.x + label_width / 2f,
                label_point.y - dp(8f)
            )
            val padded = label_rect.padded_copy(dp(4f))
            if (!label_rect.intersects(0f, 0f, viewport.width, viewport.height) ||
                label_rects.any { RectF.intersects(padded, it) }
            ) {
                return@forEach
            }
            label_rects += padded
            canvas.drawText(label, label_point.x, label_point.y - dp(8f), text_paint)
        }
        text_paint.isFakeBoldText = false
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_selected_oceanic_track(
        canvas: Canvas,
        viewport: Viewport,
        track: AviationOceanicTrack,
        style: AviationLayerStyle
    ) {
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(6f)
        stroke_paint.color = with_alpha(style.panel, 235)
        draw_oceanic_track_segments(canvas, viewport, track, stroke_paint)
        stroke_paint.strokeWidth = dp(3.2f)
        stroke_paint.color = with_alpha(style.accent_pink, 255)
        draw_oceanic_track_segments(canvas, viewport, track, stroke_paint)
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_oceanic_track_segments(
        canvas: Canvas,
        viewport: Viewport,
        track: AviationOceanicTrack,
        segment_paint: Paint
    ) {
        track.drawable_segments.forEach { segment ->
            val points = ring_to_screen_points(segment, viewport)
            if (points.size < 2) return@forEach
            path.reset()
            points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            canvas.drawPath(path, segment_paint)
        }
    }

    private fun draw_airport_labels(
        canvas: Canvas,
        viewport: Viewport,
        airports: List<AviationAirportFeature>,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ) {
        if (viewport.zoom < AVIATION_AIRPORT_LABEL_MIN_ZOOM) return
        val visible = airports
            .mapNotNull { airport ->
                project_aviation_point_to_screen(
                    AviationLayerPoint(airport.lat, airport.lon),
                    viewport,
                    MapProjection::lat_lon_to_world
                )?.let { airport to it }
            }
            .filter { (_, point) -> point.x in 0f..viewport.width && point.y in 0f..viewport.height }
            .sortedBy { (_, point) -> distance_from_screen_center(point, viewport) }

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(10f)
        visible.forEach { (airport, point) ->
            val label = AviationMapTextAdapter.airport(airport).primary_text
            val color = if (airport.military) style.military_gray else style.accent_yellow
            val max_width = dp(if (viewport.zoom >= 10.0) 98f else 62f)
            text_paint.color = color
            val display = ellipsize(label, max_width)
            val width = text_paint.measureText(display) + dp(10f)
            val preferred_left = if (point.x + dp(5f) + width <= viewport.width - dp(2f)) {
                point.x + dp(5f)
            } else {
                point.x - dp(5f) - width
            }
            val left = preferred_left.coerceIn(
                dp(2f),
                (viewport.width - width - dp(2f)).coerceAtLeast(dp(2f))
            )
            val top = (point.y - dp(16f)).coerceIn(dp(2f), viewport.height - dp(22f))
            val rect = RectF(left, top, left + width, top + dp(20f))
            if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return@forEach
            val padded = rect.padded_copy(dp(3f))
            if (label_rects.any { RectF.intersects(padded, it) }) return@forEach
            label_rects += padded
            paint.style = Paint.Style.FILL
            paint.color = with_alpha(style.panel, 165)
            canvas.drawRoundRect(rect, dp(4f), dp(4f), paint)
            paint.color = color
            canvas.drawCircle(point.x, point.y, dp(3f), paint)
            canvas.drawText(display, rect.left + dp(5f), rect.bottom - dp(6f), text_paint)
            selection_hit_collector.accept_airport_marker(
                airport,
                point,
                AviationScreenRect(rect.left, rect.top, rect.right, rect.bottom)
            )
        }
        text_paint.isFakeBoldText = false
    }

    private fun draw_selected_airport(
        canvas: Canvas,
        viewport: Viewport,
        airport: AviationAirportFeature,
        style: AviationLayerStyle
    ) {
        if (viewport.zoom < AVIATION_AIRPORT_LABEL_MIN_ZOOM) return
        val point = project_aviation_point_to_screen(
            AviationLayerPoint(airport.lat, airport.lon),
            viewport,
            MapProjection::lat_lon_to_world
        ) ?: return
        if (point.x !in 0f..viewport.width || point.y !in 0f..viewport.height) return
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeWidth = dp(2.4f)
        stroke_paint.color = with_alpha(
            if (airport.military) style.military_gray else style.accent_yellow,
            255
        )
        canvas.drawCircle(point.x, point.y, dp(8f), stroke_paint)
    }

    private fun ring_to_screen_points(
        points: List<AviationLayerPoint>,
        viewport: Viewport
    ): List<ScreenPoint> {
        if (points.isEmpty()) return emptyList()
        val result = ArrayList<ScreenPoint>(points.size)
        val world_span = TILE_SIZE * 2.0.pow(viewport.zoom)
        points.forEach { point ->
            project_aviation_source_point_to_screen(
                point,
                viewport,
                MapProjection::lat_lon_to_world
            )?.let { projected ->
                var x = projected.x.toDouble()
                val previous_x = result.lastOrNull()?.x?.toDouble()
                if (previous_x != null && world_span.isFinite() && world_span > 0.0) {
                    while (x - previous_x > world_span / 2.0) x -= world_span
                    while (x - previous_x < -world_span / 2.0) x += world_span
                }
                result += ScreenPoint(x.toFloat(), projected.y)
            }
        }
        return result
    }

    private fun distance_from_screen_center(point: ScreenPoint, viewport: Viewport): Float {
        val dx = point.x - viewport.width / 2f
        val dy = point.y - viewport.height / 2f
        return dx * dx + dy * dy
    }

    private fun RectF.padded_copy(padding: Float): RectF {
        return RectF(left - padding, top - padding, right + padding, bottom + padding)
    }

    private fun restricted_airspace_stroke(style: AviationLayerStyle): Int {
        return when (style.treatment) {
            ThemeTreatment.PLAIN -> style.accent_orange
            ThemeTreatment.GLASS -> style.accent_pink
            ThemeTreatment.RADAR_GRID -> style.accent_yellow
            ThemeTreatment.DAYLIGHT_CARD -> style.accent_pink
            ThemeTreatment.STORM_BAND -> style.accent_orange
            ThemeTreatment.CRT_SCANLINE -> style.accent_green
        }
    }

    private fun restricted_airspace_fill(style: AviationLayerStyle): Int {
        return when (style.treatment) {
            ThemeTreatment.PLAIN -> style.danger
            ThemeTreatment.GLASS -> style.accent_blue
            ThemeTreatment.RADAR_GRID -> style.danger
            ThemeTreatment.DAYLIGHT_CARD -> style.danger
            ThemeTreatment.STORM_BAND -> style.accent_pink
            ThemeTreatment.CRT_SCANLINE -> style.danger
        }
    }

    private fun selected_restricted_airspace_stroke(style: AviationLayerStyle): Int {
        return when (style.treatment) {
            ThemeTreatment.PLAIN -> style.accent_yellow
            ThemeTreatment.GLASS -> style.accent_yellow
            ThemeTreatment.RADAR_GRID -> style.accent_green
            ThemeTreatment.DAYLIGHT_CARD -> style.accent_orange
            ThemeTreatment.STORM_BAND -> style.accent_yellow
            ThemeTreatment.CRT_SCANLINE -> style.accent_yellow
        }
    }

    private fun selected_restricted_airspace_fill_alpha(style: AviationLayerStyle): Int {
        return when (style.treatment) {
            ThemeTreatment.DAYLIGHT_CARD -> 34
            ThemeTreatment.GLASS -> 36
            else -> 42
        }
    }

    private data class PreparedAirspaceFeature(
        val source: AviationAirspaceFeature,
        val label: String,
        val center: AviationLayerPoint,
        val point_count: Int,
        val min_unwrapped_lon: Double,
        val max_unwrapped_lon: Double,
        val polygons: List<PreparedAirspacePolygon>
    )

    private data class PreparedAirspacePolygon(
        val point_count: Int,
        val path: Path,
        val min_x: Float,
        val max_x: Float,
        val min_y: Float,
        val max_y: Float
    )

    private data class SettledLayerCacheKey(
        val snapshot_identity: Int,
        val width: Int,
        val height: Int,
        val zoom: Double,
        val center_x: Double,
        val center_y: Double,
        val visible_bounds: AviationGeoBounds,
        val visibility: AviationLayerVisibility,
        val style: AviationLayerStyle,
        val nat_temporal_state_token: Long,
        val selection_key: AviationSelectionKey?
    )

    private companion object {
        const val TILE_SIZE = 256
        const val MAX_WRAPPED_RING_COPIES = 8
        const val MAX_TRANSFORMED_CACHE_ZOOM_DELTA = 1.6
        const val MAX_TRANSFORMED_CACHE_TRANSLATION_FRACTION = 0.65f
        const val AIRSPACE_LABEL_MIN_ZOOM = 7.2
        const val OCEANIC_TRACK_MIN_ZOOM = 3.0
    }
}
