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
import com.flightalert.details.json_number_or_null
import com.flightalert.ThemeTreatment
import com.flightalert.ui.lerp
import com.flightalert.ui.smooth_step
import com.flightalert.ui.with_alpha
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject

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

class AviationLayerClient(private val user_agent: String) {

    fun fetch_layers(
        bounds: AviationLayerBounds,
        include_atc_boundaries: Boolean,
        include_restricted_airspaces: Boolean,
        include_airports: Boolean,
        include_oceanic_tracks: Boolean
    ): AviationLayerSnapshot {
        val statuses = mutableMapOf<AviationLayerKind, AviationLayerStatus>()
        val atc = if (include_atc_boundaries) {
            fetch_feature_layer(
                kind = AviationLayerKind.ATC_BOUNDARIES,
                bounds = bounds,
                base_url = BOUNDARY_AIRSPACE_QUERY_URL,
                where = "TYPE_CODE IN ('ARTCC','FIR','OCA')",
                out_fields = "NAME,IDENT,TYPE_CODE,LOCAL_TYPE,LEVEL_,UPPER_VAL,LOWER_VAL",
                max_records = MAX_BOUNDARY_RECORDS,
                statuses = statuses,
                parser = ::parse_airspace_features
            )
        } else {
            emptyList()
        }
        val restricted = if (include_restricted_airspaces) {
            fetch_feature_layer(
                kind = AviationLayerKind.RESTRICTED_AIRSPACES,
                bounds = bounds,
                base_url = SPECIAL_USE_AIRSPACE_QUERY_URL,
                where = "1=1",
                out_fields = "NAME,TYPE_CODE,UPPER_VAL,LOWER_VAL,UPPER_UOM,LOWER_UOM,TIMESOFUSE,CITY,STATE",
                max_records = MAX_SPECIAL_USE_RECORDS,
                statuses = statuses,
                parser = ::parse_airspace_features
            )
        } else {
            emptyList()
        }
        val airports = if (include_airports) {
            fetch_feature_layer(
                kind = AviationLayerKind.AIRPORTS,
                bounds = bounds,
                base_url = AIRPORT_QUERY_URL,
                where = "OPERSTATUS = 'OPERATIONAL'",
                out_fields = "IDENT,NAME,ICAO_ID,TYPE_CODE,MIL_CODE,SERVCITY,STATE,COUNTRY",
                max_records = MAX_AIRPORT_RECORDS,
                statuses = statuses,
                parser = ::parse_airport_features
            )
        } else {
            emptyList()
        }
        val oceanic = if (include_oceanic_tracks) {
            fetch_oceanic_tracks(statuses)
        } else {
            emptyList()
        }

        return AviationLayerSnapshot(
            atc_boundaries = atc,
            restricted_airspaces = restricted,
            airports = airports,
            oceanic_tracks = oceanic,
            statuses = statuses,
            fetched_at_ms = System.currentTimeMillis()
        )
    }

    private fun <T> fetch_feature_layer(
        kind: AviationLayerKind,
        bounds: AviationLayerBounds,
        base_url: String,
        where: String,
        out_fields: String,
        max_records: Int,
        statuses: MutableMap<AviationLayerKind, AviationLayerStatus>,
        parser: (JSONObject) -> List<T>
    ): List<T> {
        return try {
            val url = URL(
                "$base_url?f=geojson" +
                        "&where=${encode(where)}" +
                        "&geometry=${encode(bounds.arc_gis_envelope())}" +
                        "&geometryType=esriGeometryEnvelope" +
                        "&inSR=4326" +
                        "&spatialRel=esriSpatialRelIntersects" +
                        "&outFields=${encode(out_fields)}" +
                        "&outSR=4326" +
                        "&returnGeometry=true" +
                        "&resultRecordCount=$max_records"
            )
            val json = fetch_json(url)
            val parsed = parser(json)
            statuses[kind] = if (parsed.isEmpty()) {
                AviationLayerStatus(
                    AviationLayerState.EMPTY,
                    "No ${kind.display_name.lowercase(Locale.US)} in view"
                )
            } else {
                AviationLayerStatus(
                    AviationLayerState.LOADED,
                    "${parsed.size} ${kind.display_name.lowercase(Locale.US)} loaded"
                )
            }
            parsed
        } catch (_: Exception) {
            statuses[kind] = AviationLayerStatus(
                AviationLayerState.UNAVAILABLE,
                "${kind.display_name} unavailable"
            )
            emptyList()
        }
    }

    private fun fetch_oceanic_tracks(statuses: MutableMap<AviationLayerKind, AviationLayerStatus>): List<AviationOceanicTrack> {
        return try {
            val json = fetch_json_array(URL(NAT_TRACKS_URL))
            val tracks = parse_nat_tracks(json)
            statuses[AviationLayerKind.OCEANIC_TRACKS] = if (tracks.isEmpty()) {
                AviationLayerStatus(AviationLayerState.EMPTY, "No drawable NAT track coordinates")
            } else {
                AviationLayerStatus(AviationLayerState.LOADED, "${tracks.size} NAT tracks loaded")
            }
            tracks
        } catch (_: Exception) {
            statuses[AviationLayerKind.OCEANIC_TRACKS] =
                AviationLayerStatus(AviationLayerState.UNAVAILABLE, "NAT tracks unavailable")
            emptyList()
        }
    }

    private fun parse_airspace_features(json: JSONObject): List<AviationAirspaceFeature> {
        val features = json.optJSONArray("features") ?: JSONArray()
        val parsed = mutableListOf<AviationAirspaceFeature>()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val rings = polygon_rings(feature.optJSONObject("geometry")).take(MAX_RINGS_PER_FEATURE)
            if (rings.isEmpty()) continue
            val type = properties.aviation_json_clean_string("TYPE_CODE")
                ?: properties.aviation_json_clean_string("LOCAL_TYPE") ?: "Airspace"
            val name = properties.aviation_json_clean_string("NAME")
                ?: properties.aviation_json_clean_string("IDENT")
                ?: type
            parsed += AviationAirspaceFeature(
                name = name,
                type = type,
                lower_limit = altitude_label(properties, "LOWER"),
                upper_limit = altitude_label(properties, "UPPER"),
                schedule = properties.aviation_json_clean_string("TIMESOFUSE"),
                city = properties.aviation_json_clean_string("CITY"),
                state = properties.aviation_json_clean_string("STATE"),
                rings = rings,
                bounds = rings.flatten().to_bounds()
            )
        }
        return parsed
    }

    private fun parse_airport_features(json: JSONObject): List<AviationAirportFeature> {
        val features = json.optJSONArray("features") ?: JSONArray()
        val parsed = mutableListOf<AviationAirportFeature>()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") != "Point") continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            val lon = coordinates.json_number_or_null(0) ?: continue
            val lat = coordinates.json_number_or_null(1) ?: continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val ident = properties.aviation_json_clean_string("ICAO_ID")
                ?: properties.aviation_json_clean_string("IDENT")
                ?: continue
            val name = properties.aviation_json_clean_string("NAME") ?: ident
            parsed += AviationAirportFeature(
                ident = ident,
                name = name,
                type = properties.aviation_json_clean_string("TYPE_CODE") ?: "AD",
                military = properties.aviation_json_clean_string("MIL_CODE")
                    ?.contains("MIL", ignoreCase = true) == true,
                lat = lat,
                lon = lon
            )
        }
        return parsed
    }

    private fun parse_nat_tracks(json: JSONArray): List<AviationOceanicTrack> {
        val tracks = linkedMapOf<String, AviationOceanicTrack>()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val source = item.aviation_json_clean_string("notam_number_formatted")
                ?: item.aviation_json_clean_string("icao_id") ?: "FAA NMS"
            val window = listOfNotNull(
                item.aviation_json_clean_string("start_datetime"),
                item.aviation_json_clean_string("end_datetime")
            )
                .joinToString(" to ")
                .ifBlank { null }
            val message = item.aviation_json_clean_string("condition_message") ?: continue
            message.split('\n')
                .map { it.trim().trimEnd('-') }
                .forEach { line ->
                    val tokens = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (tokens.size < 3) return@forEach
                    val designator =
                        tokens.first().takeIf { TRACK_DESIGNATOR.matches(it) } ?: return@forEach
                    val points = tokens.drop(1).mapNotNull(::parse_nat_coordinate)
                    if (points.size < 2) return@forEach
                    val key = "$source:$designator"
                    tracks[key] = AviationOceanicTrack(
                        name = "NAT $designator",
                        source = source,
                        active_window = window,
                        points = points,
                        bounds = points.to_bounds()
                    )
                }
        }
        return tracks.values.toList()
    }

    private fun parse_nat_coordinate(raw: String): AviationLayerPoint? {
        val token = raw.trim().uppercase(Locale.US).trimEnd(',', '.', ';')
        val match = NAT_COORDINATE.matchEntire(token) ?: return null
        val lat_degrees = match.groupValues[1].toDoubleOrNull() ?: return null
        val lat_minutes =
            match.groupValues[2].takeIf { it.isNotBlank() }?.toDoubleOrNull()?.div(60.0) ?: 0.0
        val lon_degrees = match.groupValues[3].toDoubleOrNull() ?: return null
        val lon_minutes =
            match.groupValues[4].takeIf { it.isNotBlank() }?.toDoubleOrNull()?.div(60.0) ?: 0.0
        val lat = lat_degrees + lat_minutes
        val lon = -(lon_degrees + lon_minutes)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return AviationLayerPoint(lat, lon)
    }

    private fun polygon_rings(geometry: JSONObject?): List<List<AviationLayerPoint>> {
        geometry ?: return emptyList()
        return when (geometry.optString("type")) {
            "Polygon" -> geometry.optJSONArray("coordinates").polygon_to_rings()
            "MultiPolygon" -> {
                val coordinates = geometry.optJSONArray("coordinates") ?: return emptyList()
                val rings = mutableListOf<List<AviationLayerPoint>>()
                for (index in 0 until coordinates.length()) {
                    rings += coordinates.optJSONArray(index).polygon_to_rings()
                }
                rings
            }

            else -> emptyList()
        }
    }

    private fun JSONArray?.polygon_to_rings(): List<List<AviationLayerPoint>> {
        this ?: return emptyList()
        val rings = mutableListOf<List<AviationLayerPoint>>()
        for (ring_index in 0 until length()) {
            val ring = optJSONArray(ring_index) ?: continue
            val points = mutableListOf<AviationLayerPoint>()
            for (point_index in 0 until ring.length()) {
                val coordinate = ring.optJSONArray(point_index) ?: continue
                val lon = coordinate.json_number_or_null(0) ?: continue
                val lat = coordinate.json_number_or_null(1) ?: continue
                points += AviationLayerPoint(lat, lon)
            }
            if (points.size >= MIN_RING_POINTS) rings += points
        }
        return rings
    }

    private fun altitude_label(properties: JSONObject, prefix: String): String? {
        val value = properties.json_number_or_null("${prefix}_VAL") ?: return null
        if (value <= MISSING_ALTITUDE_SENTINEL) return null
        val unit = properties.aviation_json_clean_string("${prefix}_UOM") ?: return null
        return "${value.toInt()} $unit"
    }

    private fun fetch_json(url: URL): JSONObject {
        return JSONObject(fetch_text(url))
    }

    private fun fetch_json_array(url: URL): JSONArray {
        return JSONArray(fetch_text(url))
    }

    private fun fetch_text(url: URL): String {
        require(
            url.protocol.equals(
                "https",
                ignoreCase = true
            )
        ) { "Only HTTPS aviation layers are allowed" }
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", user_agent)
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                throw IllegalStateException("HTTP $code")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val BOUNDARY_AIRSPACE_QUERY_URL =
            "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/Boundary_Airspace/FeatureServer/0/query"
        private const val SPECIAL_USE_AIRSPACE_QUERY_URL =
            "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/Special_Use_Airspace/FeatureServer/0/query"
        private const val AIRPORT_QUERY_URL =
            "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/US_Airport/FeatureServer/0/query"
        private const val NAT_TRACKS_URL = "https://nms.aim.faa.gov/datanat/nat.json"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 9000
        private const val MAX_BOUNDARY_RECORDS = 80
        private const val MAX_SPECIAL_USE_RECORDS = 140
        private const val MAX_AIRPORT_RECORDS = 220
        private const val MAX_RINGS_PER_FEATURE = 8
        private const val MIN_RING_POINTS = 3
        private const val MISSING_ALTITUDE_SENTINEL = -9000.0
        private val TRACK_DESIGNATOR = Regex("^[A-Z]$")
        private val NAT_COORDINATE = Regex("^(\\d{2})(\\d{2})?/(\\d{2,3})(\\d{2})?$")
    }
}

internal fun JSONObject.aviation_json_clean_string(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotBlank() }
}

enum class AviationLayerKind(val display_name: String) {
    ATC_BOUNDARIES("ATC boundaries"),
    RESTRICTED_AIRSPACES("Restricted airspaces"),
    AIRPORTS("Airport labels"),
    OCEANIC_TRACKS("Oceanic tracks")
}

enum class AviationLayerState {
    LOADED,
    EMPTY,
    UNAVAILABLE
}

data class AviationLayerStatus(
    val state: AviationLayerState,
    val message: String
)

data class AviationLayerSnapshot(
    val atc_boundaries: List<AviationAirspaceFeature>,
    val restricted_airspaces: List<AviationAirspaceFeature>,
    val airports: List<AviationAirportFeature>,
    val oceanic_tracks: List<AviationOceanicTrack>,
    val statuses: Map<AviationLayerKind, AviationLayerStatus>,
    val fetched_at_ms: Long
)

data class AviationLayerBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
) {
    fun arc_gis_envelope(): String = "$min_lon,$min_lat,$max_lon,$max_lat"
}

data class AviationLayerPoint(val lat: Double, val lon: Double)

data class AviationGeoBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
) {
    fun intersects(other: AviationGeoBounds): Boolean {
        return max_lat >= other.min_lat &&
                min_lat <= other.max_lat &&
                max_lon >= other.min_lon &&
                min_lon <= other.max_lon
    }
}

data class AviationAirspaceFeature(
    val name: String,
    val type: String,
    val lower_limit: String?,
    val upper_limit: String?,
    val schedule: String?,
    val city: String?,
    val state: String?,
    val rings: List<List<AviationLayerPoint>>,
    val bounds: AviationGeoBounds
)

data class AviationAirportFeature(
    val ident: String,
    val name: String,
    val type: String,
    val military: Boolean,
    val lat: Double,
    val lon: Double
)

data class AviationOceanicTrack(
    val name: String,
    val source: String,
    val active_window: String?,
    val points: List<AviationLayerPoint>,
    val bounds: AviationGeoBounds
)

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
    private val fetch_layers: (AviationLayerBounds, Boolean, Boolean, Boolean, Boolean) -> AviationLayerSnapshot =
        { bounds, include_atc_boundaries, include_restricted_airspaces, include_airports, include_oceanic_tracks ->
            client.fetch_layers(
                bounds = bounds,
                include_atc_boundaries = include_atc_boundaries,
                include_restricted_airspaces = include_restricted_airspaces,
                include_airports = include_airports,
                include_oceanic_tracks = include_oceanic_tracks
            )
        }
) {
    var snapshot: AviationLayerSnapshot? = null
        private set

    var fetch_in_flight: Boolean = false
        private set

    var status_text: String = "Layers off"
        private set

    private var last_fetch_ms = 0L
    private var last_bounds: Bounds? = null
    private var last_selection_key = ""
    private var latest_visibility = AviationLayerVisibility(
        restricted_airspaces_enabled = false,
        atc_boundaries_enabled = false,
        oceanic_tracks_enabled = false,
        airport_labels_enabled = false
    )

    fun request_if_needed(
        viewport: Viewport,
        visibility: AviationLayerVisibility,
        force: Boolean = false
    ) {
        latest_visibility = visibility
        if (!has_enabled_layers(visibility)) {
            status_text = "Layers off"
            request_warm_aviation_layers_if_needed(viewport)
            return
        }
        val query_bounds = layer_bounds_for_viewport(viewport)
        val visible_viewport_bounds = visible_bounds(viewport) ?: query_bounds
        val selection_key = selection_key(visibility)
        val now = now_ms()
        val needs_fetch = force ||
                snapshot == null ||
                !snapshot_covers_visibility(snapshot, visibility) ||
                last_bounds?.contains(visible_viewport_bounds) != true ||
                now - last_fetch_ms >= refresh_ms
        if (!needs_fetch || fetch_in_flight) {
            if (!needs_fetch) status_text = summary(snapshot, visibility)
            return
        }

        status_text = "Loading aviation layers"
        start_fetch(
            query_bounds = query_bounds,
            visibility = visibility,
            selection_key = selection_key,
            prefetch = false,
            now = now
        )
    }

    private fun request_warm_aviation_layers_if_needed(viewport: Viewport) {
        val query_bounds = layer_bounds_for_viewport(viewport)
        val visible_viewport_bounds = visible_bounds(viewport) ?: query_bounds
        val visibility = WARM_AVIATION_LAYERS_VISIBILITY
        val selection_key = selection_key(visibility)
        val now = now_ms()
        val needs_fetch = snapshot == null ||
                !snapshot_covers_visibility(snapshot, visibility) ||
                last_bounds?.contains(visible_viewport_bounds) != true ||
                now - last_fetch_ms >= refresh_ms
        if (!needs_fetch || fetch_in_flight) return
        start_fetch(
            query_bounds = query_bounds,
            visibility = visibility,
            selection_key = selection_key,
            prefetch = true,
            now = now
        )
    }

    private fun start_fetch(
        query_bounds: Bounds,
        visibility: AviationLayerVisibility,
        selection_key: String,
        prefetch: Boolean,
        now: Long
    ) {
        val requested_kinds = active_kinds(visibility)
        fetch_in_flight = true
        last_fetch_ms = now
        run_in_background {
            val next_snapshot = try {
                fetch_layers(
                    query_bounds.to_aviation_layer_bounds(),
                    visibility.atc_boundaries_enabled,
                    visibility.restricted_airspaces_enabled,
                    visibility.airport_labels_enabled,
                    visibility.oceanic_tracks_enabled
                )
            } catch (_: Exception) {
                null
            }
            post_to_main {
                apply_fetch_result(
                    snapshot = next_snapshot,
                    requested_kinds = requested_kinds,
                    query_bounds = query_bounds,
                    selection_key = selection_key,
                    prefetch = prefetch
                )
            }
        }
    }

    fun on_visibility_changed(visibility: AviationLayerVisibility) {
        latest_visibility = visibility
        if (!has_enabled_layers(visibility)) {
            status_text = "Layers off"
            current_viewport()?.let { request_warm_aviation_layers_if_needed(it) }
            request_redraw()
            return
        }
        status_text = summary(snapshot, visibility)
        current_viewport()?.let { request_if_needed(it, visibility, force = false) }
    }

    fun clear() {
        snapshot = null
        fetch_in_flight = false
        last_bounds = null
        last_selection_key = ""
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
        query_bounds: Bounds,
        selection_key: String,
        prefetch: Boolean
    ) {
        fetch_in_flight = false
        val latest_key = selection_key(latest_visibility)
        val latest_enabled = has_enabled_layers(latest_visibility)
        if (selection_key != latest_key &&
            !snapshot_covers_visibility(snapshot, latest_visibility) &&
            (!prefetch || latest_enabled)
        ) {
            last_fetch_ms = 0L
            status_text = if (latest_enabled) "Refreshing aviation layers" else "Layers off"
            current_viewport()?.let { request_if_needed(it, latest_visibility, force = true) }
            request_redraw()
            return
        }
        if (snapshot == null) {
            status_text = if (this.snapshot != null) {
                "Aviation layers unavailable; keeping previous"
            } else {
                "Aviation layers unavailable"
            }
            request_redraw()
            return
        }

        val requested_kind_set = requested_kinds.toSet()
        val all_unavailable = requested_kinds.isNotEmpty() &&
                requested_kinds.all { snapshot.statuses[it]?.state == AviationLayerState.UNAVAILABLE }
        val previous = this.snapshot
        this.snapshot = if (all_unavailable && previous != null) {
            merge_layer_snapshot(previous, snapshot, emptySet())
        } else {
            previous?.let { merge_layer_snapshot(it, snapshot, requested_kind_set) } ?: snapshot
        }
        last_bounds = query_bounds
        last_selection_key = selection_key
        status_text = if (latest_enabled) {
            summary(
                this.snapshot,
                latest_visibility,
                kept_last_good = all_unavailable && previous != null
            )
        } else {
            "Layers off"
        }
        request_redraw()
    }

    private fun layer_bounds_for_viewport(viewport: Viewport): Bounds {
        val padding = max(viewport.width, viewport.height) * bounds_padding_fraction
        val left = viewport.center_x - viewport.width / 2.0 - padding
        val right = viewport.center_x + viewport.width / 2.0 + padding
        val top = viewport.center_y - viewport.height / 2.0 - padding
        val bottom = viewport.center_y + viewport.height / 2.0 + padding
        val top_left = MapProjection.world_to_lat_lon(left, top, viewport.zoom)
        val bottom_right = MapProjection.world_to_lat_lon(right, bottom, viewport.zoom)
        val world_width = TILE_SIZE * 2.0.pow(viewport.zoom)
        val longitude_span_degrees = ((right - left) / world_width) * 360.0
        val use_world_longitude_bounds = abs(top_left.lon - bottom_right.lon) > 180.0 ||
                longitude_span_degrees >= 180.0
        return Bounds(
            min_lat = min(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0),
            min_lon = if (use_world_longitude_bounds) -180.0 else min(
                top_left.lon,
                bottom_right.lon
            ).coerceIn(-180.0, 180.0),
            max_lat = max(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0),
            max_lon = if (use_world_longitude_bounds) 180.0 else max(
                top_left.lon,
                bottom_right.lon
            ).coerceIn(-180.0, 180.0)
        )
    }

    private fun active_kinds(visibility: AviationLayerVisibility): List<AviationLayerKind> {
        val kinds = mutableListOf<AviationLayerKind>()
        if (visibility.atc_boundaries_enabled) kinds += AviationLayerKind.ATC_BOUNDARIES
        if (visibility.restricted_airspaces_enabled) kinds += AviationLayerKind.RESTRICTED_AIRSPACES
        if (visibility.oceanic_tracks_enabled) kinds += AviationLayerKind.OCEANIC_TRACKS
        if (visibility.airport_labels_enabled) kinds += AviationLayerKind.AIRPORTS
        return kinds
    }

    private fun selection_key(visibility: AviationLayerVisibility): String {
        return listOf(
            if (visibility.atc_boundaries_enabled) "atc" else "",
            if (visibility.restricted_airspaces_enabled) "restricted" else "",
            if (visibility.oceanic_tracks_enabled) "oceanic" else "",
            if (visibility.airport_labels_enabled) "airports" else ""
        ).joinToString("|")
    }

    private fun summary(
        snapshot: AviationLayerSnapshot?,
        visibility: AviationLayerVisibility,
        kept_last_good: Boolean = false
    ): String {
        if (!has_enabled_layers(visibility)) return "Layers off"
        snapshot ?: return "Waiting for aviation layers"
        val loaded =
            active_kinds(visibility).count { snapshot.statuses[it]?.state == AviationLayerState.LOADED }
        return when {
            kept_last_good -> "Network unavailable; showing last aviation layers"
            loaded > 0 -> "$loaded aviation layer${if (loaded == 1) "" else "s"} loaded"
            else -> "No aviation layer data in view"
        }
    }

    private fun snapshot_covers_visibility(
        snapshot: AviationLayerSnapshot?,
        visibility: AviationLayerVisibility
    ): Boolean {
        snapshot ?: return false
        return active_kinds(visibility).all { kind ->
            snapshot.statuses[kind] != null
        }
    }

    private fun merge_layer_snapshot(
        previous: AviationLayerSnapshot,
        fetched: AviationLayerSnapshot,
        requested_kinds: Set<AviationLayerKind>
    ): AviationLayerSnapshot {
        return AviationLayerSnapshot(
            atc_boundaries = merged_layer_data(
                kind = AviationLayerKind.ATC_BOUNDARIES,
                requested_kinds = requested_kinds,
                fetched_statuses = fetched.statuses,
                previous = previous.atc_boundaries,
                fetched = fetched.atc_boundaries
            ),
            restricted_airspaces = merged_layer_data(
                kind = AviationLayerKind.RESTRICTED_AIRSPACES,
                requested_kinds = requested_kinds,
                fetched_statuses = fetched.statuses,
                previous = previous.restricted_airspaces,
                fetched = fetched.restricted_airspaces
            ),
            airports = merged_layer_data(
                kind = AviationLayerKind.AIRPORTS,
                requested_kinds = requested_kinds,
                fetched_statuses = fetched.statuses,
                previous = previous.airports,
                fetched = fetched.airports
            ),
            oceanic_tracks = merged_layer_data(
                kind = AviationLayerKind.OCEANIC_TRACKS,
                requested_kinds = requested_kinds,
                fetched_statuses = fetched.statuses,
                previous = previous.oceanic_tracks,
                fetched = fetched.oceanic_tracks
            ),
            statuses = previous.statuses + fetched.statuses,
            fetched_at_ms = fetched.fetched_at_ms
        )
    }

    private fun <T> merged_layer_data(
        kind: AviationLayerKind,
        requested_kinds: Set<AviationLayerKind>,
        fetched_statuses: Map<AviationLayerKind, AviationLayerStatus>,
        previous: List<T>,
        fetched: List<T>
    ): List<T> {
        if (kind !in requested_kinds) return previous
        if (fetched_statuses[kind]?.state == AviationLayerState.UNAVAILABLE) return previous
        return fetched
    }

    private fun Bounds.to_aviation_layer_bounds(): AviationLayerBounds {
        return AviationLayerBounds(
            min_lat = min_lat,
            min_lon = min_lon,
            max_lat = max_lat,
            max_lon = max_lon
        )
    }

    private fun Bounds.contains(other: Bounds): Boolean {
        return min_lat <= other.min_lat &&
                min_lon <= other.min_lon &&
                max_lat >= other.max_lat &&
                max_lon >= other.max_lon
    }

    private companion object {
        const val TILE_SIZE = FlightAlertAppSettings.AviationLayer.TILE_SIZE
        val WARM_AVIATION_LAYERS_VISIBILITY = AviationLayerVisibility(
            restricted_airspaces_enabled = true,
            atc_boundaries_enabled = true,
            oceanic_tracks_enabled = true,
            airport_labels_enabled = true
        )
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

class AviationLayerRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val ellipsize: (String, Float) -> String
) {
    private var prepared_snapshot: AviationLayerSnapshot? = null
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
        selected_restricted_airspace: AviationAirspaceFeature? = null,
        interaction_active: Boolean = false
    ) {
        prepare_snapshot_if_needed(snapshot)
        if (interaction_active && draw_transformed_settled_cache(
                canvas,
                viewport,
                snapshot,
                visibility,
                style,
                selected_restricted_airspace
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
                selected_restricted_airspace
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
                selected_restricted_airspace
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
            selected_restricted_airspace = selected_restricted_airspace,
            interaction_active = interaction_active
        )
    }

    private fun draw_settled_cache_if_current(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selected_restricted_airspace: AviationAirspaceFeature?
    ): Boolean {
        val bitmap = settled_cache_bitmap ?: return false
        val key = settled_cache_key ?: return false
        if (key != settled_layer_cache_key(
                viewport,
                snapshot,
                visible_bounds,
                visibility,
                style,
                selected_restricted_airspace
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
        selected_restricted_airspace: AviationAirspaceFeature?
    ): Boolean {
        val bitmap = settled_cache_bitmap ?: return false
        val key = settled_cache_key ?: return false
        if (bitmap.isRecycled) return false
        if (key.snapshot_identity != System.identityHashCode(snapshot) ||
            key.width != viewport.width.toInt() ||
            key.height != viewport.height.toInt() ||
            key.visibility != visibility ||
            key.style != style ||
            key.selected_restricted_airspace_identity != selected_restricted_airspace?.let(System::identityHashCode)
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
        selected_restricted_airspace: AviationAirspaceFeature?
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
            selected_restricted_airspace = selected_restricted_airspace,
            interaction_active = false
        )
        settled_cache_key = settled_layer_cache_key(
            viewport,
            snapshot,
            visible_bounds,
            visibility,
            style,
            selected_restricted_airspace
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
        selected_restricted_airspace: AviationAirspaceFeature?
    ): SettledLayerCacheKey {
        return SettledLayerCacheKey(
            snapshot_identity = System.identityHashCode(snapshot),
            width = viewport.width.toInt(),
            height = viewport.height.toInt(),
            zoom = viewport.zoom,
            center_x = viewport.center_x,
            center_y = viewport.center_y,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = style,
            selected_restricted_airspace_identity = selected_restricted_airspace?.let(System::identityHashCode)
        )
    }

    private fun draw_layers_direct(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selected_restricted_airspace: AviationAirspaceFeature?,
        interaction_active: Boolean
    ) {
        layer_label_rects.clear()
        if (visibility.restricted_airspaces_enabled) {
            draw_airspace_layer(
                canvas = canvas,
                viewport = viewport,
                features = prepared_restricted_airspaces,
                visible_bounds = visible_bounds,
                excluded_feature = selected_restricted_airspace,
                stroke = restricted_airspace_stroke(style),
                fill = restricted_airspace_fill(style),
                label_limit = if (viewport.zoom >= 8.0) 6 else 3,
                label_rects = layer_label_rects,
                style = style,
                restricted = true,
                interaction_active = interaction_active
            )
            selected_restricted_airspace
                ?.takeIf { it.bounds.intersects(visible_bounds) }
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
                label_limit = 4,
                label_rects = layer_label_rects,
                style = style,
                restricted = false,
                focused_feature = focused_atc_feature,
                interaction_active = interaction_active
            )
        }
        if (visibility.oceanic_tracks_enabled) {
            draw_oceanic_tracks(
                canvas = canvas,
                viewport = viewport,
                tracks = snapshot.oceanic_tracks.filter { it.bounds.intersects(visible_bounds) },
                label_rects = layer_label_rects,
                style = style
            )
        }
        if (visibility.airport_labels_enabled) {
            draw_airport_labels(
                canvas = canvas,
                viewport = viewport,
                airports = snapshot.airports,
                label_rects = layer_label_rects,
                style = style
            )
        }
    }

    private fun prepare_snapshot_if_needed(snapshot: AviationLayerSnapshot) {
        if (prepared_snapshot === snapshot) return
        prepared_snapshot = snapshot
        prepared_restricted_airspaces = prepare_airspace_features(snapshot.restricted_airspaces)
        prepared_atc_boundaries = prepare_airspace_features(snapshot.atc_boundaries)
    }

    private fun prepare_airspace_features(features: List<AviationAirspaceFeature>): List<PreparedAirspaceFeature> {
        return features
            .mapNotNull(::prepare_airspace_feature)
            .sortedBy { it.point_count }
    }

    private fun prepare_airspace_feature(feature: AviationAirspaceFeature): PreparedAirspaceFeature? {
        val rings = feature.rings
            .take(MAX_DRAWN_RINGS_PER_FEATURE)
            .mapNotNull { ring -> prepare_airspace_ring(ring, MAX_DRAWN_AIRSPACE_POINTS_PER_RING) }
        val interaction_rings = feature.rings
            .take(MAX_DRAWN_RINGS_PER_FEATURE)
            .mapNotNull { ring ->
                prepare_airspace_ring(
                    ring,
                    MAX_DRAWN_AIRSPACE_POINTS_PER_RING_INTERACTION
                )
            }
        if (rings.isEmpty()) return null
        val label = airspace_label(feature)
        return PreparedAirspaceFeature(
            source = feature,
            label = label,
            center = feature.bounds.center_point(),
            point_count = rings.sumOf { it.point_count },
            rings = rings,
            interaction_rings = interaction_rings.ifEmpty { rings }
        )
    }

    private fun airspace_label(feature: AviationAirspaceFeature): String {
        val type = feature.type.trim()
        if (type.isBlank() || type.equals(feature.name, ignoreCase = true)) return feature.name
        val type_already_in_name = Regex(
            "\\b${Regex.escape(type)}\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(feature.name)
        return if (type_already_in_name) feature.name else "${feature.name} $type"
    }

    private fun prepare_airspace_ring(
        ring: List<AviationLayerPoint>,
        max_points: Int
    ): PreparedAirspaceRing? {
        if (ring.size < 3) return null
        val step = max(1, ring.size / max_points)
        val sampled_count =
            ring.indices.count { index -> index % step == 0 || index == ring.lastIndex }
        if (sampled_count < 3) return null
        val world_path = Path()
        var point_index = 0
        var min_x = Float.POSITIVE_INFINITY
        var max_x = Float.NEGATIVE_INFINITY
        var min_y = Float.POSITIVE_INFINITY
        var max_y = Float.NEGATIVE_INFINITY
        ring.forEachIndexed { index, point ->
            if (index % step == 0 || index == ring.lastIndex) {
                val world = MapProjection.lat_lon_to_world(point.lat, point.lon, 0.0)
                val x = world.x.toFloat()
                val y = world.y.toFloat()
                if (point_index == 0) world_path.moveTo(x, y) else world_path.lineTo(x, y)
                min_x = min(min_x, x)
                max_x = max(max_x, x)
                min_y = min(min_y, y)
                max_y = max(max_y, y)
                point_index++
            }
        }
        world_path.close()
        return PreparedAirspaceRing(
            point_count = sampled_count,
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
        label_limit: Int,
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

        var labels_drawn = 0
        var drawn_features = 0
        for (feature in features) {
            if (feature.source == excluded_feature || !feature.source.bounds.intersects(
                    visible_bounds
                )
            ) continue
            if (drawn_features >= MAX_DRAWN_AIRSPACE_FEATURES) break
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
            val rings = if (interaction_active) feature.interaction_rings else feature.rings
            rings.forEach { ring ->
                draw_prepared_airspace_ring(
                    canvas = canvas,
                    viewport = viewport,
                    ring = ring,
                    fill_paint = paint.takeIf { fill_alpha > 0 },
                    outline_paint = stroke_paint
                )
            }
            if (!interaction_active && labels_drawn < label_limit && viewport.zoom >= AIRSPACE_LABEL_MIN_ZOOM) {
                if (draw_airspace_label(canvas, viewport, feature, label_rects, style)) {
                    labels_drawn++
                }
            }
            drawn_features++
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
            feature.source.bounds.intersects(visible_bounds) &&
                    feature.source.bounds.contains(point) &&
                    point_inside_airspace(point, feature.source)
        }
    }

    private fun AviationGeoBounds.contains(point: AviationLayerPoint): Boolean {
        return point.lat in min_lat..max_lat && point.lon in min_lon..max_lon
    }

    private fun point_inside_airspace(
        point: AviationLayerPoint,
        feature: AviationAirspaceFeature
    ): Boolean {
        var crossings = 0
        feature.rings.forEach { ring ->
            if (point_inside_ring(point, ring)) crossings++
        }
        return crossings % 2 == 1
    }

    private fun point_inside_ring(
        point: AviationLayerPoint,
        ring: List<AviationLayerPoint>
    ): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val pi = ring[i]
            val pj = ring[j]
            val intersects = (pi.lat > point.lat) != (pj.lat > point.lat) &&
                    point.lon < (pj.lon - pi.lon) * (point.lat - pi.lat) / ((pj.lat - pi.lat).takeIf {
                abs(
                    it
                ) > 1e-9
            } ?: 1e-9) + pi.lon
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

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
        prepared.rings.forEach { ring ->
            draw_prepared_airspace_ring(canvas, viewport, ring, paint, stroke_paint)
        }
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_prepared_airspace_ring(
        canvas: Canvas,
        viewport: Viewport,
        ring: PreparedAirspaceRing,
        fill_paint: Paint?,
        outline_paint: Paint
    ) {
        val scale = 2.0.pow(viewport.zoom).toFloat()
        if (scale <= 0f || !scale.isFinite()) return
        val world_span = TILE_SIZE * scale
        val screen_offset_x = (-viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_offset_y = (-viewport.center_y + viewport.height / 2.0).toFloat()
        draw_prepared_airspace_ring_path(
            canvas = canvas,
            viewport = viewport,
            ring = ring,
            scale = scale,
            world_span = world_span,
            screen_offset_x = screen_offset_x,
            screen_offset_y = screen_offset_y,
            fill_paint = fill_paint,
            outline_paint = outline_paint
        )
    }

    private fun draw_prepared_airspace_ring_path(
        canvas: Canvas,
        viewport: Viewport,
        ring: PreparedAirspaceRing,
        scale: Float,
        world_span: Float,
        screen_offset_x: Float,
        screen_offset_y: Float,
        fill_paint: Paint?,
        outline_paint: Paint
    ) {
        if (ring.point_count < 3 || scale <= 0f || !scale.isFinite()) return
        val extended_left = -viewport.width * 0.5f
        val extended_right = viewport.width * 1.5f
        val extended_top = -viewport.height * 0.5f
        val extended_bottom = viewport.height * 1.5f
        val screen_top = ring.min_y * scale + screen_offset_y
        val screen_bottom = ring.max_y * scale + screen_offset_y
        if (screen_bottom < extended_top || screen_top > extended_bottom) return

        val base_left = ring.min_x * scale + screen_offset_x
        val base_right = ring.max_x * scale + screen_offset_x
        var shift_x = 0f
        var guard = 0
        while (base_right + shift_x < extended_left && guard++ < MAX_WRAPPED_RING_COPIES) {
            shift_x += world_span
        }
        guard = 0
        val original_stroke_width = outline_paint.strokeWidth
        outline_paint.strokeWidth = original_stroke_width / scale
        try {
            while (base_left + shift_x <= extended_right && guard++ < MAX_WRAPPED_RING_COPIES) {
                if (base_right + shift_x >= extended_left) {
                    canvas.withTranslation(screen_offset_x + shift_x, screen_offset_y) {
                        scale(scale, scale)
                        if (fill_paint != null) drawPath(ring.path, fill_paint)
                        drawPath(ring.path, outline_paint)
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
    ): Boolean {
        val screen = project_aviation_point_to_screen(
            feature.center,
            viewport,
            MapProjection::lat_lon_to_world
        ) ?: return false
        if (screen.x !in 0f..viewport.width || screen.y !in 0f..viewport.height) return false
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
        if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return false
        val padded = rect.padded_copy(dp(3f))
        if (label_rects.any { RectF.intersects(padded, it) }) return false
        label_rects += padded
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(style.panel, 184)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), paint)
        canvas.drawText(display, rect.centerX(), rect.bottom - dp(7f), text_paint)
        text_paint.isFakeBoldText = false
        return true
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

        tracks.take(MAX_DRAWN_OCEANIC_TRACKS).forEach { track ->
            val points = ring_to_screen_points(track.points, viewport)
            if (points.size < 2) return@forEach
            path.reset()
            points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            canvas.drawPath(path, stroke_paint)
            val label_point = points.getOrNull(points.size / 2) ?: return@forEach
            canvas.drawCircle(label_point.x, label_point.y, dp(3f), paint.apply {
                this.style = Paint.Style.FILL
                color = style.accent_pink
            })
            val label_width = text_paint.measureText(track.name)
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
            canvas.drawText(track.name, label_point.x, label_point.y - dp(8f), text_paint)
        }
        text_paint.isFakeBoldText = false
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_airport_labels(
        canvas: Canvas,
        viewport: Viewport,
        airports: List<AviationAirportFeature>,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ) {
        if (viewport.zoom < AIRPORT_LABEL_MIN_ZOOM) return
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
            .take(if (viewport.zoom >= 10.0) MAX_DRAWN_AIRPORT_LABELS else MAX_DRAWN_AIRPORT_LABELS_LOW_ZOOM)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(10f)
        visible.forEach { (airport, point) ->
            val label = airport.ident.ifBlank { airport.name }
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
        }
        text_paint.isFakeBoldText = false
    }

    private fun ring_to_screen_points(
        points: List<AviationLayerPoint>,
        viewport: Viewport
    ): List<ScreenPoint> {
        if (points.isEmpty()) return emptyList()
        val step = max(1, points.size / MAX_DRAWN_OCEANIC_POINTS)
        val result = mutableListOf<ScreenPoint>()
        points.forEachIndexed { index, point ->
            if (index % step == 0 || index == points.lastIndex) {
                project_aviation_point_to_screen(
                    point,
                    viewport,
                    MapProjection::lat_lon_to_world
                )?.let { result += it }
            }
        }
        return result
    }

    private fun distance_from_screen_center(point: ScreenPoint, viewport: Viewport): Float {
        val dx = point.x - viewport.width / 2f
        val dy = point.y - viewport.height / 2f
        return dx * dx + dy * dy
    }

    private fun AviationGeoBounds.center_point(): AviationLayerPoint {
        return AviationLayerPoint((min_lat + max_lat) / 2.0, (min_lon + max_lon) / 2.0)
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
        val rings: List<PreparedAirspaceRing>,
        val interaction_rings: List<PreparedAirspaceRing>
    )

    private data class PreparedAirspaceRing(
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
        val selected_restricted_airspace_identity: Int?
    )

    private companion object {
        const val TILE_SIZE = 256
        const val MAX_DRAWN_AIRSPACE_FEATURES = 80
        const val MAX_DRAWN_RINGS_PER_FEATURE = 4
        const val MAX_DRAWN_AIRSPACE_POINTS_PER_RING = 180
        const val MAX_DRAWN_AIRSPACE_POINTS_PER_RING_INTERACTION = 42
        const val MAX_WRAPPED_RING_COPIES = 8
        const val MAX_TRANSFORMED_CACHE_ZOOM_DELTA = 1.6
        const val MAX_TRANSFORMED_CACHE_TRANSLATION_FRACTION = 0.65f
        const val MAX_DRAWN_AIRPORT_LABELS = 36
        const val MAX_DRAWN_AIRPORT_LABELS_LOW_ZOOM = 16
        const val MAX_DRAWN_OCEANIC_TRACKS = 16
        const val MAX_DRAWN_OCEANIC_POINTS = 24
        const val AIRSPACE_LABEL_MIN_ZOOM = 7.2
        const val AIRPORT_LABEL_MIN_ZOOM = 8.4
        const val OCEANIC_TRACK_MIN_ZOOM = 3.0
    }
}

internal data class RestrictedAirspaceStyle(
    val panel_color: Int,
    val modal_panel_alpha: Int,
    val text_color: Int,
    val muted_color: Int,
    val accent_orange_color: Int
)

internal class RestrictedAirspaceInspector(
    private val text_paint: Paint,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val ellipsize: (String, Float) -> String,
    private val draw_panel_surface: (Canvas, RectF, Int, Int) -> Unit,
    private val draw_choice_button: (Canvas, RectF, String, Boolean) -> Unit,
    private val draw_wrapped_text: (Canvas, String, Float, Float, Float, Int) -> Float,
    private val lat_lon_to_world: (Double, Double, Double) -> WorldPoint,
    private val world_to_lat_lon: (Double, Double, Double) -> GeoPoint
) {
    fun draw_details_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        feature: AviationAirspaceFeature,
        style: RestrictedAirspaceStyle
    ) {
        val panel = panel_bounds(w, h)
        draw_panel_surface(canvas, panel, style.panel_color, style.modal_panel_alpha)
        draw_choice_button(canvas, close_button_bounds(panel), "Close", false)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(22f)
        text_paint.color = style.text_color
        val title_max_width = panel.width() - dp(142f)
        canvas.drawText(
            ellipsize(feature.name, title_max_width),
            panel.left + dp(18f),
            panel.top + dp(34f),
            text_paint
        )

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(11f)
        text_paint.color = style.accent_orange_color
        canvas.drawText(
            "FAA SPECIAL USE AIRSPACE",
            panel.left + dp(18f),
            panel.top + dp(55f),
            text_paint
        )

        val vertical = listOf(
            feature.lower_limit ?: "Lower unavailable",
            feature.upper_limit ?: "Upper unavailable"
        ).joinToString(" to ")
        var row_y = panel.top + dp(88f)
        row_y = draw_detail_row(
            canvas,
            panel,
            row_y,
            "Type",
            feature.type.ifBlank { "Unavailable" },
            style
        )
        row_y = draw_detail_row(canvas, panel, row_y, "Vertical", vertical, style)
        row_y = draw_detail_row(
            canvas,
            panel,
            row_y,
            "Schedule",
            feature.schedule ?: "Unavailable from FAA feature",
            style
        )
        row_y = draw_detail_row(canvas, panel, row_y, "Location", location_label(feature), style)
        draw_detail_row(canvas, panel, row_y, "Source", RESTRICTED_AIRSPACE_SOURCE_LABEL, style)
    }

    fun panel_bounds(w: Float, h: Float): RectF {
        val panel_width = min(w - dp(32f), dp(620f)).coerceAtLeast(dp(280f))
        val panel_height = min(h - dp(72f), dp(286f)).coerceAtLeast(dp(236f))
        val left = (w - panel_width) / 2f
        val top = (h - panel_height - dp(24f)).coerceAtLeast(dp(24f))
        return RectF(left, top, left + panel_width, top + panel_height)
    }

    fun close_button_bounds(panel: RectF): RectF {
        return RectF(
            panel.right - dp(118f),
            panel.top + dp(14f),
            panel.right - dp(18f),
            panel.top + dp(48f)
        )
    }

    fun airspace_at(
        x: Float,
        y: Float,
        snapshot: AviationLayerSnapshot,
        viewport: Viewport,
        visible_bounds: AviationGeoBounds
    ): AviationAirspaceFeature? {
        val geo = screen_to_geo_point(x, y, viewport)
        val candidates = snapshot.restricted_airspaces
            .asSequence()
            .filter { it.bounds.intersects(visible_bounds) }
            .take(MAX_RESTRICTED_AIRSPACE_HIT_TEST_FEATURES)
            .toList()
        val inside = candidates
            .filter { point_inside_airspace(geo, it) }
            .minByOrNull { airspace_bounds_area(it.bounds) }
        if (inside != null) return inside

        val max_distance_sq = dp(RESTRICTED_AIRSPACE_HIT_RADIUS_DP).let { it * it }
        return candidates
            .mapNotNull { feature ->
                val distance_sq = distance_to_airspace_screen_sq(x, y, feature, viewport)
                    ?: return@mapNotNull null
                if (distance_sq <= max_distance_sq) feature to distance_sq else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun draw_detail_row(
        canvas: Canvas,
        panel: RectF,
        y: Float,
        label: String,
        value: String,
        style: RestrictedAirspaceStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(12f)
        text_paint.color = style.muted_color
        canvas.drawText(label.uppercase(Locale.US), panel.left + dp(18f), y, text_paint)

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(14f)
        text_paint.color = style.text_color
        val value_x = panel.left + dp(112f)
        val max_width = panel.right - value_x - dp(18f)
        val bottom = draw_wrapped_text(canvas, value, value_x, y, max_width, 2)
        return max(bottom + dp(9f), y + dp(30f))
    }

    private fun location_label(feature: AviationAirspaceFeature): String {
        return listOfNotNull(feature.city, feature.state)
            .joinToString(", ")
            .ifBlank { "Unavailable from FAA feature" }
    }

    private fun screen_to_geo_point(x: Float, y: Float, viewport: Viewport): GeoPoint {
        val world_x = viewport.center_x - viewport.width / 2.0 + x
        val world_y = viewport.center_y - viewport.height / 2.0 + y
        return world_to_lat_lon(world_x, world_y, viewport.zoom)
    }

    private fun point_inside_airspace(point: GeoPoint, feature: AviationAirspaceFeature): Boolean {
        var crossings = 0
        feature.rings.forEach { ring ->
            if (point_inside_ring(point, ring)) crossings++
        }
        return crossings % 2 == 1
    }

    private fun point_inside_ring(point: GeoPoint, ring: List<AviationLayerPoint>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var previous = ring.last()
        ring.forEach { current ->
            val crosses_lat = (current.lat > point.lat) != (previous.lat > point.lat)
            if (crosses_lat) {
                val lon_at_lat = (previous.lon - current.lon) *
                        (point.lat - current.lat) /
                        (previous.lat - current.lat) +
                        current.lon
                if (point.lon < lon_at_lat) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun distance_to_airspace_screen_sq(
        x: Float,
        y: Float,
        feature: AviationAirspaceFeature,
        viewport: Viewport
    ): Float? {
        var best: Float? = null
        feature.rings.take(MAX_RESTRICTED_AIRSPACE_HIT_RINGS).forEach { ring ->
            val points = airspace_ring_screen_points(ring, viewport)
            if (points.size < 2) return@forEach
            var previous = points.first()
            points.drop(1).forEach { current ->
                val distance = point_to_segment_distance_sq(x, y, previous, current)
                best = min(best ?: distance, distance)
                previous = current
            }
        }
        return best
    }

    private fun airspace_ring_screen_points(
        ring: List<AviationLayerPoint>,
        viewport: Viewport
    ): List<ScreenPoint> {
        if (ring.isEmpty()) return emptyList()
        val step = max(1, ring.size / MAX_RESTRICTED_AIRSPACE_HIT_POINTS_PER_RING)
        val result =
            ArrayList<ScreenPoint>(min(ring.size, MAX_RESTRICTED_AIRSPACE_HIT_POINTS_PER_RING + 1))
        ring.forEachIndexed { index, point ->
            if (index % step == 0 || index == ring.lastIndex) {
                project_aviation_point_to_screen(
                    point,
                    viewport,
                    lat_lon_to_world
                )?.let(result::add)
            }
        }
        return result
    }

    private fun point_to_segment_distance_sq(
        x: Float,
        y: Float,
        start: ScreenPoint,
        end: ScreenPoint
    ): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length_sq = dx * dx + dy * dy
        if (length_sq <= 0.0001f) {
            val px = x - start.x
            val py = y - start.y
            return px * px + py * py
        }
        val t = (((x - start.x) * dx + (y - start.y) * dy) / length_sq).coerceIn(0f, 1f)
        val closest_x = start.x + dx * t
        val closest_y = start.y + dy * t
        val px = x - closest_x
        val py = y - closest_y
        return px * px + py * py
    }

    private fun airspace_bounds_area(bounds: AviationGeoBounds): Double {
        return (bounds.max_lat - bounds.min_lat).coerceAtLeast(0.0) *
                (bounds.max_lon - bounds.min_lon).coerceAtLeast(0.0)
    }

    private companion object {
        const val TILE_SIZE = 256
        const val RESTRICTED_AIRSPACE_HIT_RADIUS_DP = 18f
        const val MAX_RESTRICTED_AIRSPACE_HIT_TEST_FEATURES = 180
        const val MAX_RESTRICTED_AIRSPACE_HIT_RINGS = 8
        const val MAX_RESTRICTED_AIRSPACE_HIT_POINTS_PER_RING = 160
        const val RESTRICTED_AIRSPACE_SOURCE_LABEL = "FAA AIS Special Use Airspace"
    }
}
