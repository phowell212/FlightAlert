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

import java.net.URL

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
