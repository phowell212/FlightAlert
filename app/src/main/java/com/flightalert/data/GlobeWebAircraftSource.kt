package com.flightalert.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Experimental source: reads the normalized PlaneObject layer from the public Airplanes.Live globe page.
class GlobeWebAircraftSource(context: Context, private val user_agent: String) {
    val web_view: WebView = WebView(context)

    private val handler = Handler(Looper.getMainLooper())
    private var loaded = false
    private var host_resumed = false
    private var running = false

    @Volatile
    private var source_enabled = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var pending_viewport: GlobeViewport? = null
    private var viewport_generation = 0L
    private var applied_viewport_generation = 0L

    @Volatile
    private var latest: GlobeSnapshot? = null

    @Volatile
    private var pending_detail_request: GlobeDetailRequest? = null

    @Volatile
    private var latest_detail: GlobeDetailSnapshot? = null

    private val extractor = object : Runnable {
        // The hidden WebView is polled on a timer because the globe page owns its own fetch cadence.
        override fun run() {
            if (!running) return
            extract_aircraft()
            handler.postDelayed(this, EXTRACT_INTERVAL_MS)
        }
    }

    init {
        configure_web_view()
    }

    // MainActivity tells the source when the app is visible; source_enabled still decides whether it runs.
    fun start() {
        host_resumed = true
        start_if_allowed()
    }

    fun stop() {
        host_resumed = false
        stop_active(clear_snapshot = false, clear_page = false)
    }

    fun destroy() {
        destroyed = true
        stop_active(clear_snapshot = true, clear_page = true)
        web_view.destroy()
    }

    // Settings can turn the globe source on and off without rebuilding the reusable WebView.
    fun set_enabled(enabled: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { set_enabled(enabled) }
            return
        }
        if (source_enabled == enabled) return
        source_enabled = enabled
        if (enabled) {
            web_view.visibility = View.INVISIBLE
            start_if_allowed()
        } else {
            stop_active(clear_snapshot = true, clear_page = true)
        }
    }

    // Store the requested viewport as a generation so old globe snapshots cannot satisfy a new map view.
    fun update_viewport(bounds: FeedBounds, center_lat: Double, center_lon: Double, zoom: Double) {
        if (!source_enabled || destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { update_viewport(bounds, center_lat, center_lon, zoom) }
            return
        }
        val viewport = GlobeViewport(
            bounds = bounds,
            center_lat = center_lat,
            center_lon = center_lon,
            zoom = zoom.coerceIn(MIN_GLOBE_ZOOM, MAX_GLOBE_ZOOM),
            generation = viewport_generation
        )
        val previous = pending_viewport
        if (previous != null && previous.matches(viewport)) return
        viewport_generation += 1L
        val next = viewport.copy(generation = viewport_generation)
        pending_viewport = next
        latest = null
        if (loaded && running) apply_viewport(next)
    }

    // Return a feed result only when the hidden page has fresh data for the current viewport.
    fun latest_snapshot(bounds: FeedBounds, own_lat: Double, own_lon: Double, exact_search: String?): FeedResult? {
        if (!source_enabled || destroyed) return null
        val viewport = pending_viewport ?: return null
        val snapshot = latest ?: return null
        if (snapshot.viewport_generation != viewport.generation) return null
        if (SystemClock.elapsedRealtime() - snapshot.received_elapsed_ms > MAX_SNAPSHOT_AGE_MS) return null
        if (snapshot.aircraft.isEmpty() && snapshot.partial_coverage) return null
        val normalized_search = exact_search?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
        val normalized_bounds = normalize_bounds(bounds)
        val filtered = snapshot.aircraft
            .asSequence()
            .filter { aircraft -> normalized_search == null || aircraft.matches_search(normalized_search) }
            .filter { aircraft -> normalized_search != null || normalized_bounds.contains(aircraft.lat, aircraft.lon) }
            .map { aircraft -> aircraft.with_distance_from(own_lat, own_lon) }
            .sortedBy { it.distance_m }
            .toList()
        if (normalized_search != null && filtered.isEmpty()) return null
        return FeedResult(
            status = FeedStatus.OK,
            source = FeedSource.AIRPLANES_LIVE_GLOBE,
            aircraft = filtered,
            epoch_sec = snapshot.epoch_sec,
            query_count = 0,
            partial_coverage = snapshot.partial_coverage
        )
    }

    fun request_aircraft_details(icao24: String, callsign: String?, registration: String?) {
        if (!source_enabled || destroyed) return
        val clean_icao = icao24.trim().trimStart('~').lowercase(Locale.US)
        if (!MODE_S_OR_NON_ICAO.matches(clean_icao)) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { request_aircraft_details(icao24, callsign, registration) }
            return
        }
        val request = GlobeDetailRequest(
            icao24 = clean_icao,
            callsign = callsign?.trim()?.takeIf { it.isNotBlank() },
            registration = registration?.trim()?.takeIf { it.isNotBlank() },
            requested_elapsed_ms = SystemClock.elapsedRealtime()
        )
        pending_detail_request = request
        latest_detail = latest_detail?.takeIf { detail -> detail.matches(request) && detail.is_fresh() }
        if (loaded && running) apply_detail_request(request)
    }

    fun latest_aircraft_details(icao24: String, callsign: String?, registration: String?): AircraftDetails? {
        if (!source_enabled || destroyed) return null
        val request = GlobeDetailRequest(
            icao24 = icao24.trim().trimStart('~').lowercase(Locale.US),
            callsign = callsign?.trim()?.takeIf { it.isNotBlank() },
            registration = registration?.trim()?.takeIf { it.isNotBlank() },
            requested_elapsed_ms = 0L
        )
        val detail = latest_detail ?: return null
        if (!detail.matches(request) || !detail.is_fresh()) return null
        return detail.details
    }

    fun latest_photo_hint(icao24: String, callsign: String?, registration: String?): GlobePhotoHint? {
        if (!source_enabled || destroyed) return null
        val request = GlobeDetailRequest(
            icao24 = icao24.trim().trimStart('~').lowercase(Locale.US),
            callsign = callsign?.trim()?.takeIf { it.isNotBlank() },
            registration = registration?.trim()?.takeIf { it.isNotBlank() },
            requested_elapsed_ms = 0L
        )
        val detail = latest_detail ?: return null
        if (!detail.matches(request) || !detail.is_fresh()) return null
        return detail.photo
    }

    // Start the page only when the app is resumed, the source is enabled, and the WebView still exists.
    private fun start_if_allowed() {
        if (destroyed || !source_enabled || !host_resumed || running) return
        running = true
        web_view.visibility = View.INVISIBLE
        web_view.onResume()
        if (!loaded || web_view.url?.startsWith(AirplanesLiveHttp.GLOBE_BASE_URL) != true) {
            loaded = false
            web_view.loadUrl("${AirplanesLiveHttp.GLOBE_BASE_URL}/")
        } else {
            pending_viewport?.let(::apply_viewport)
            pending_detail_request?.let(::apply_detail_request)
        }
        handler.removeCallbacks(extractor)
        handler.postDelayed(extractor, FIRST_EXTRACT_DELAY_MS)
    }

    private fun stop_active(clear_snapshot: Boolean, clear_page: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stop_active(clear_snapshot, clear_page) }
            return
        }
        running = false
        handler.removeCallbacks(extractor)
        web_view.onPause()
        if (clear_snapshot) {
            latest = null
            pending_viewport = null
            pending_detail_request = null
            latest_detail = null
            viewport_generation = 0L
            applied_viewport_generation = 0L
        }
        if (clear_page && !destroyed) {
            loaded = false
            web_view.stopLoading()
            web_view.loadUrl("about:blank")
            web_view.visibility = View.GONE
        }
    }

    // Make the WebView invisible and non-interactive; it is a source adapter, not an app screen.
    @SuppressLint("SetJavaScriptEnabled")
    private fun configure_web_view() {
        web_view.alpha = 0f
        web_view.visibility = View.GONE
        web_view.isClickable = false
        web_view.isFocusable = false
        web_view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        web_view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            userAgentString = AirplanesLiveHttp.browser_user_agent(user_agent)
        }
        web_view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                loaded = source_enabled && url.startsWith(AirplanesLiveHttp.GLOBE_BASE_URL)
                pending_viewport?.takeIf { loaded }?.let(::apply_viewport)
                pending_detail_request?.takeIf { loaded }?.let(::apply_detail_request)
            }
        }
    }

    // Push the map viewport into the globe page so its own real fetch code loads that area.
    private fun apply_viewport(viewport: GlobeViewport) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { apply_viewport(viewport) }
            return
        }
        val script = String.format(
            Locale.US,
            """
            (function() {
              try {
                if (typeof OLMap === 'undefined' || typeof ol === 'undefined' || !OLMap.getView) return false;
                CenterLat = %.7f;
                CenterLon = %.7f;
                if (typeof g !== 'undefined') g.zoomLvl = %.2f;
                var view = OLMap.getView();
                view.setCenter(ol.proj.fromLonLat([%.7f, %.7f]));
                view.setZoom(%.2f);
                if (typeof fetchData === 'function') fetchData({force: true});
                return true;
              } catch (e) {
                return false;
              }
            })();
            """.trimIndent(),
            viewport.center_lat,
            viewport.center_lon,
            viewport.zoom,
            viewport.center_lon,
            viewport.center_lat,
            viewport.zoom
        )
        web_view.evaluateJavascript(script) { result ->
            if (result == "true" && source_enabled && pending_viewport?.generation == viewport.generation) {
                applied_viewport_generation = viewport.generation
            }
        }
    }

    private fun apply_detail_request(request: GlobeDetailRequest) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { apply_detail_request(request) }
            return
        }
        if (!source_enabled || !loaded || !running) return
        web_view.evaluateJavascript(detail_request_js(request), null)
    }

    private fun detail_request_js(request: GlobeDetailRequest): String {
        val request_json = JSONObject()
            .put("hex", request.icao24)
            .put("callsign", request.callsign ?: JSONObject.NULL)
            .put("registration", request.registration ?: JSONObject.NULL)
            .put("requestedElapsedMs", request.requested_elapsed_ms)
        return """
            (function(request) {
              try {
                var globe = (typeof g !== 'undefined') ? g : null;
                if (!globe || !request || !request.hex) return false;
                var hex = String(request.hex).toLowerCase();
                globe.flightAlertDetailRequest = request;
                var plane = globe.planes && globe.planes[hex] ? globe.planes[hex] : null;
                if (!plane && typeof PlaneObject === 'function') plane = new PlaneObject(hex);
                if (!plane) return false;
                if (typeof getTrace === 'function' && (!plane.fullTrace || !plane.recentTrace)) {
                  getTrace(plane, hex, {noDeselect:true, follow:false});
                }
                if (typeof selectPlaneByHex === 'function') {
                  selectPlaneByHex(hex, {noDeselect:true, follow:false, noFetch:true});
                } else if (typeof select === 'function') {
                  select(plane, {follow:false});
                }
                if (plane.checkForDB) plane.checkForDB();
                if (!plane.dbinfoLoaded && plane.getAircraftData) plane.getAircraftData();
                if (plane.routeCheck && plane.position) {
                  var wasVisible = plane.visible;
                  plane.visible = true;
                  plane.routeCheck();
                  plane.visible = wasVisible;
                }
                if (typeof routeDoLookup === 'function') routeDoLookup();
                if (typeof refreshSelected === 'function') refreshSelected();
                if (typeof refreshPhoto === 'function') refreshPhoto(plane);
                return true;
              } catch (e) {
                return false;
              }
            })($request_json);
        """.trimIndent()
    }

    // Read the page's current PlaneObject data and accept it only for the applied viewport generation.
    private fun extract_aircraft() {
        if (!source_enabled || !loaded || applied_viewport_generation == 0L) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { extract_aircraft() }
            return
        }
        val generation = applied_viewport_generation
        web_view.evaluateJavascript(EXTRACT_AIRCRAFT_JS) { value ->
            parse_snapshot(value, generation)?.let { snapshot ->
                if (source_enabled && pending_viewport?.generation == snapshot.viewport_generation) {
                    latest = snapshot
                    snapshot.detail?.let { latest_detail = it }
                }
                Log.d(TAG, "Globe web source: ${snapshot.aircraft.size} aircraft, partial=${snapshot.partial_coverage}")
            }
        }
    }

    // Parse the JavaScript bridge envelope and reject empty or stale page data.
    private fun parse_snapshot(value: String?, viewport_generation: Long): GlobeSnapshot? {
        if (value.isNullOrBlank() || value == "null") return null
        return try {
            val token = JSONTokener(value).nextValue()
            val raw = when (token) {
                is String -> token
                is JSONObject -> token.toString()
                else -> return null
            }
            val json = JSONObject(raw)
            if (!json.optBoolean("ready", false)) return null
            val now_sec = json.opt_double_or_null("now") ?: (System.currentTimeMillis() / 1000.0)
            val rows = json.optJSONArray("aircraft") ?: return null
            val parsed = mutableListOf<FeedAircraft>()
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                parse_aircraft(item, now_sec)?.let { parsed += it }
            }
            val detail = json.optJSONObject("detail")?.let(::parse_detail)
            GlobeSnapshot(
                aircraft = parsed,
                epoch_sec = now_sec,
                received_elapsed_ms = SystemClock.elapsedRealtime(),
                partial_coverage = !json.optBoolean("first_fetch_done", false) || json.optInt("pending_fetches", 0) > 0,
                viewport_generation = viewport_generation,
                detail = detail
            )
        } catch (_: Exception) {
            null
        }
    }

    // Map one globe aircraft object into the shared feed model while preserving nulls for missing fields.
    private fun parse_aircraft(item: JSONObject, now_sec: Double): FeedAircraft? {
        val lat = item.opt_double_or_null("lat") ?: return null
        val lon = item.opt_double_or_null("lon") ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val hex = item.opt_string_or_null("hex")?.trim()?.trimStart('~')?.lowercase(Locale.US) ?: return null
        if (!MODE_S_OR_NON_ICAO.matches(hex)) return null
        val position_time_sec = item.opt_double_or_null("position_time_sec")
        if (position_time_sec != null && now_sec - position_time_sec > MAX_POSITION_AGE_SECONDS) return null
        val last_contact_sec = item.opt_double_or_null("last_contact_sec")
        val altitude = item.opt("altitude")
        val on_ground = altitude is String && altitude.equals("ground", ignoreCase = true)
        val altitude_feet = when (altitude) {
            is Number -> altitude.toDouble()
            is String -> altitude.toDoubleOrNull() ?: if (on_ground) 0.0 else null
            else -> null
        }
        val registration = item.opt_string_or_null("registration")
        val callsign = item.opt_string_or_null("flight") ?: registration ?: hex
        val type_code = item.opt_string_or_null("type_code")
        val metadata = AircraftMetadataSeed(
            source_name = FeedSource.AIRPLANES_LIVE_GLOBE.display_name,
            registration = registration,
            manufacturer = item.opt_string_or_null("manufacturer"),
            type = item.opt_string_or_null("description"),
            type_code = type_code,
            owner = item.opt_string_or_null("owner_operator"),
            manufactured_year = item.opt_string_or_null("year")?.take(4),
            operator_code = item.opt_string_or_null("operator_code")
        ).takeIf { it.has_details }
        return FeedAircraft(
            icao24 = hex,
            callsign = callsign,
            registration = registration,
            type_code = type_code,
            metadata = metadata,
            db_flags = item.opt_int_or_null("db_flags"),
            lat = lat,
            lon = lon,
            on_ground = on_ground,
            altitude_m = altitude_feet?.let { it / FEET_PER_METER },
            velocity_ms = item.opt_double_or_null("speed_kt")?.let { it * KNOTS_TO_METERS_PER_SECOND },
            track_deg = item.opt_double_or_null("track_deg"),
            vertical_rate_ms = item.opt_double_or_null("vertical_rate_fpm")?.let { it / FEET_PER_METER / 60.0 },
            category = category_from_globe(item.opt_string_or_null("category"), item.opt_string_or_null("type_code")),
            position_time_sec = position_time_sec,
            last_contact_sec = last_contact_sec,
            distance_m = 0.0
        )
    }

    private fun parse_detail(item: JSONObject): GlobeDetailSnapshot? {
        val requested_hex = item.opt_string_or_null("requested_hex")?.trim()?.lowercase(Locale.US) ?: return null
        val hex = item.opt_string_or_null("hex")?.trim()?.trimStart('~')?.lowercase(Locale.US) ?: requested_hex
        if (hex != requested_hex) return null
        val route_airports = parse_route_airports(item.optJSONArray("route_airports"))
        val route = item.opt_string_or_null("route")
        val route_source = item.opt_string_or_null("route_source")
            ?.takeIf { route != null || route_airports.isNotEmpty() }
        val source = listOfNotNull(
            FeedSource.AIRPLANES_LIVE_GLOBE.display_name,
            item.opt_string_or_null("trace_source")?.takeIf { item.optBoolean("trace_loaded", false) },
            route_source
        ).distinct().joinToString(" + ").ifEmpty { null }
        val details = AircraftDetails(
            icao24 = hex,
            registration = normalized_registration(item.opt_string_or_null("registration")),
            manufacturer = null,
            type = first_present(
                item.opt_string_or_null("description"),
                item.opt_string_or_null("type_description")
            ),
            type_code = item.opt_string_or_null("type_code"),
            owner = item.opt_string_or_null("owner_operator"),
            manufactured_year = item.opt_string_or_null("year")?.take(4),
            registry_source = source,
            operator_code = item.opt_string_or_null("operator_code"),
            route = route ?: route_airports
                .mapNotNull { airport -> airport.icao.takeIf { it.isNotBlank() } }
                .takeIf { it.size >= 2 }
                ?.joinToString("-"),
            route_updated_epoch_sec = item.opt_long_or_null("route_updated_epoch_sec"),
            route_source = route_source,
            origin_airport = route_airports.firstOrNull(),
            destination_airport = route_airports.lastOrNull()?.takeIf { route_airports.size >= 2 }
        )
        val photo = item.optJSONObject("photo")?.let(::parse_photo_hint)
        return GlobeDetailSnapshot(
            request = GlobeDetailRequest(
                icao24 = requested_hex,
                callsign = item.opt_string_or_null("requested_callsign"),
                registration = item.opt_string_or_null("requested_registration"),
                requested_elapsed_ms = item.opt_long_or_null("requested_elapsed_ms") ?: 0L
            ),
            details = details,
            photo = photo,
            received_elapsed_ms = SystemClock.elapsedRealtime()
        )
    }

    private fun parse_route_airports(rows: JSONArray?): List<AirportDetails> {
        rows ?: return emptyList()
        val airports = mutableListOf<AirportDetails>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val icao = item.opt_string_or_null("icao") ?: item.opt_string_or_null("icao_code") ?: continue
            airports += AirportDetails(
                icao = icao,
                iata = item.opt_string_or_null("iata") ?: item.opt_string_or_null("iata_code"),
                name = item.opt_string_or_null("name") ?: item.opt_string_or_null("airport"),
                country_code = item.opt_string_or_null("country_code") ?: item.opt_string_or_null("country"),
                region_name = item.opt_string_or_null("location") ?: item.opt_string_or_null("region_name"),
                latitude = item.opt_double_or_null("lat") ?: item.opt_double_or_null("latitude"),
                longitude = item.opt_double_or_null("lon") ?: item.opt_double_or_null("lng") ?: item.opt_double_or_null("longitude")
            )
        }
        return airports
    }

    private fun parse_photo_hint(item: JSONObject): GlobePhotoHint? {
        val image_url = item.opt_string_or_null("image_url")
            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?: return null
        return GlobePhotoHint(
            image_url = image_url,
            page_url = item.opt_string_or_null("page_url")?.takeIf { it.startsWith("https://", ignoreCase = true) },
            photographer = item.opt_string_or_null("photographer"),
            source_name = item.opt_string_or_null("source_name") ?: "Airplanes.Live selected aircraft photo"
        )
    }

    private fun GlobeDetailSnapshot.is_fresh(): Boolean {
        return SystemClock.elapsedRealtime() - received_elapsed_ms <= MAX_DETAIL_AGE_MS
    }

    private fun GlobeDetailSnapshot.matches(request: GlobeDetailRequest): Boolean {
        if (request.icao24.isNotBlank() && request.icao24 == this.request.icao24) return true
        val requested_registration = normalized_registration(request.registration)
        val detail_registration = normalized_registration(details.registration)
        if (requested_registration != null && requested_registration == detail_registration) return true
        val requested_callsign = request.callsign.compact_callsign()
        val detail_callsign = this.request.callsign.compact_callsign()
        return requested_callsign.isNotBlank() && requested_callsign == detail_callsign
    }

    private fun FeedAircraft.with_distance_from(own_lat: Double, own_lon: Double): FeedAircraft {
        return copy(distance_m = distance_meters(own_lat, own_lon, lat, lon))
    }

    private fun FeedAircraft.matches_search(search: String): Boolean {
        return icao24.uppercase(Locale.US).contains(search) ||
            callsign.uppercase(Locale.US).replace(" ", "").contains(search.replace(" ", "")) ||
            registration?.uppercase(Locale.US)?.contains(search) == true
    }

    private fun GlobeBounds.contains(lat: Double, lon: Double): Boolean {
        return lat in min_lat..max_lat && lon in min_lon..max_lon
    }

    private fun normalize_bounds(bounds: FeedBounds): GlobeBounds {
        return GlobeBounds(
            min_lat = min(bounds.min_lat, bounds.max_lat).coerceIn(-90.0, 90.0),
            max_lat = max(bounds.min_lat, bounds.max_lat).coerceIn(-90.0, 90.0),
            min_lon = min(bounds.min_lon, bounds.max_lon).coerceIn(-180.0, 180.0),
            max_lon = max(bounds.min_lon, bounds.max_lon).coerceIn(-180.0, 180.0)
        )
    }

    private fun category_from_globe(category: String?, type_code: String?): Int? {
        val normalized = category?.trim()?.uppercase(Locale.US).orEmpty()
        if (normalized.startsWith("A7") || normalized.startsWith("B7")) return 8
        if (normalized.startsWith("B1")) return 9
        if (normalized.startsWith("B6")) return 14
        if (normalized.startsWith("C") || normalized.startsWith("D")) return 15
        val type = type_code?.trim()?.uppercase(Locale.US).orEmpty()
        return when {
            type.startsWith("H") || type.startsWith("R") -> 8
            type.startsWith("GL") -> 9
            type.startsWith("UAV") || type.startsWith("DRON") -> 14
            else -> null
        }
    }

    private data class GlobeSnapshot(
        val aircraft: List<FeedAircraft>,
        val epoch_sec: Double,
        val received_elapsed_ms: Long,
        val partial_coverage: Boolean,
        val viewport_generation: Long,
        val detail: GlobeDetailSnapshot?
    )

    private data class GlobeDetailRequest(
        val icao24: String,
        val callsign: String?,
        val registration: String?,
        val requested_elapsed_ms: Long
    )

    private data class GlobeDetailSnapshot(
        val request: GlobeDetailRequest,
        val details: AircraftDetails,
        val photo: GlobePhotoHint?,
        val received_elapsed_ms: Long
    )

    private data class GlobeViewport(
        val bounds: FeedBounds,
        val center_lat: Double,
        val center_lon: Double,
        val zoom: Double,
        val generation: Long
    )

    private fun GlobeViewport.matches(other: GlobeViewport): Boolean {
        return abs(center_lat - other.center_lat) < VIEWPORT_LAT_LON_EPSILON &&
            abs(center_lon - other.center_lon) < VIEWPORT_LAT_LON_EPSILON &&
            abs(zoom - other.zoom) < VIEWPORT_ZOOM_EPSILON &&
            abs(bounds.min_lat - other.bounds.min_lat) < VIEWPORT_LAT_LON_EPSILON &&
            abs(bounds.max_lat - other.bounds.max_lat) < VIEWPORT_LAT_LON_EPSILON &&
            abs(bounds.min_lon - other.bounds.min_lon) < VIEWPORT_LAT_LON_EPSILON &&
            abs(bounds.max_lon - other.bounds.max_lon) < VIEWPORT_LAT_LON_EPSILON
    }

    private data class GlobeBounds(
        val min_lat: Double,
        val max_lat: Double,
        val min_lon: Double,
        val max_lon: Double
    )

    private companion object {
        private const val TAG = "FlightAlert"
        private const val EXTRACT_INTERVAL_MS = 2500L
        private const val FIRST_EXTRACT_DELAY_MS = 3500L
        private const val MAX_SNAPSHOT_AGE_MS = 9000L
        private const val MAX_DETAIL_AGE_MS = 45000L
        private const val MAX_POSITION_AGE_SECONDS = 120.0
        private const val MIN_GLOBE_ZOOM = 2.0
        private const val MAX_GLOBE_ZOOM = 13.0
        private const val VIEWPORT_LAT_LON_EPSILON = 0.01
        private const val VIEWPORT_ZOOM_EPSILON = 0.12
        private const val FEET_PER_METER = 3.28084
        private const val KNOTS_TO_METERS_PER_SECOND = 0.514444
        private val MODE_S_OR_NON_ICAO = Regex("^[0-9a-f]{6,8}$")

        // This script reads the page's own aircraft objects and wraps them in an auditable envelope for Kotlin.
        private val EXTRACT_AIRCRAFT_JS = """
            (function() {
              try {
                var globe = (typeof g !== 'undefined') ? g : null;
                if (!globe || !globe.planes) {
                  return JSON.stringify({ready:false, reason:"g.planes unavailable"});
                }
                var now_sec = (typeof now !== 'undefined') ? Number(now) : NaN;
                if (!isFinite(now_sec) || now_sec <= 0) now_sec = Date.now() / 1000;
                function truthy_flag(value) {
                  if (value === true || value === 1) return true;
                  if (typeof value === "string") {
                    var normalized = value.trim().toLowerCase();
                    return normalized === "true" || normalized === "1" || normalized === "yes";
                  }
                  return false;
                }
                var rows = [];
                var planes = Object.values(globe.planes);
                for (var i = 0; i < planes.length; i++) {
                  var p = planes[i];
                  if (!p || !p.position || p.position.length < 2) continue;
                  var lon = Number(p.position[0]);
                  var lat = Number(p.position[1]);
                  if (!isFinite(lat) || !isFinite(lon)) continue;
                  var position_time = Number(p.position_time);
                  var last_message = Number(p.last_message_time);
                  var seen_pos = isFinite(position_time) ? now_sec - position_time : Number(p.seen_pos);
                  if (!isFinite(seen_pos) || seen_pos > 120) continue;
                  var db_flags = 0;
                  if (truthy_flag(p.military)) db_flags = db_flags | 1;
                  if (truthy_flag(p.pia)) db_flags = db_flags | 4;
                  if (truthy_flag(p.ladd)) db_flags = db_flags | 8;
                  rows.push({
                    hex: String(p.icao || ""),
                    flight: p.flight || p.name || null,
                    registration: p.registration || null,
                    manufacturer: p.manufacturer || p.manufacturerName || null,
                    type_code: p.icaoType || p.icao_type || null,
                    description: p.typeLong || p.desc || p.description || p.typeDescription || null,
                    type_description: p.typeDescription || null,
                    category: p.category || null,
                    owner_operator: p.ownOp || p.owner_operator || p.operator || p.owner || null,
                    operator_code: p.ownOpCode || p.operator_code || null,
                    year: p.year || p.mfrYear || p.manufacturedYear || null,
                    db_flags: db_flags,
                    lat: lat,
                    lon: lon,
                    altitude: p.altitude != null ? p.altitude : p.alt_baro,
                    speed_kt: p.gs != null ? p.gs : p.speed,
                    track_deg: p.track,
                    vertical_rate_fpm: p.vert_rate != null ? p.vert_rate : (p.baro_rate != null ? p.baro_rate : p.geom_rate),
                    position_time_sec: isFinite(position_time) ? position_time : null,
                    last_contact_sec: isFinite(last_message) ? last_message : null,
                    source_type: p.dataSource || null
                  });
                }
                function cleaned_text(value) {
                  if (value == null) return null;
                  var text = String(value).replace(/\s+/g, " ").trim();
                  if (!text || /^n\/a$/i.test(text) || /^unavailable$/i.test(text)) return null;
                  return text;
                }
                function normalized_callsign_safe(value) {
                  try {
                    if (typeof normalized_callsign === "function") return normalized_callsign(value);
                  } catch (e) {}
                  return String(value || "").replace(/\s+/g, "").toUpperCase();
                }
                function dom_text(id) {
                  try {
                    var element = document.getElementById(id);
                    return element ? cleaned_text(element.innerText || element.textContent) : null;
                  } catch (e) {
                    return null;
                  }
                }
                function route_info_for(p) {
                  var route = cleaned_text(p.routeString);
                  var callsign = normalized_callsign_safe(p.name || p.flight || "");
                  var cached = callsign && globe.route_cache ? globe.route_cache[callsign] : null;
                  var airports = [];
                  if (cached && cached._airports && cached._airports.length) {
                    for (var ai = 0; ai < cached._airports.length; ai++) {
                      var a = cached._airports[ai] || {};
                      airports.push({
                        icao: cleaned_text(a.icao),
                        iata: cleaned_text(a.iata),
                        name: cleaned_text(a.name || a.airport),
                        location: cleaned_text(a.location || a.city || a.municipality),
                        country_code: cleaned_text(a.country_code || a.country || a.countryiso2),
                        lat: Number(a.lat != null ? a.lat : a.latitude),
                        lon: Number(a.lon != null ? a.lon : (a.lng != null ? a.lng : a.longitude))
                      });
                    }
                    if (!route) {
                      route = airports.map(function(a) { return a.icao || a.iata; }).filter(Boolean).join("-");
                    }
                  }
                  if (!route && SelectedPlane === p) route = dom_text("selected_route");
                  return {
                    route: route,
                    route_source: (route || airports.length) ? "Airplanes.Live globe route cache" : null,
                    route_updated_epoch_sec: cached && cached.tarNextUpdate ? Number(cached.tarNextUpdate) : null,
                    route_airports: airports.filter(function(a) { return !!(a.icao || a.iata); })
                  };
                }
                function photo_info_for(p) {
                  var response = p.psAPIresponse || null;
                  var photos = response ? (response.photos || response.images) : null;
                  if (!photos || !photos.length) return null;
                  var photo = photos[0] || {};
                  var thumb = photo.thumbnail && (photo.thumbnail.src || photo.thumbnail);
                  var imageUrl = cleaned_text(photo.image || photo.imageUrl || photo.src || thumb);
                  var pageUrl = cleaned_text(photo.link || photo.url || photo.pageUrl);
                  if (!imageUrl || imageUrl.indexOf("https://") !== 0) return null;
                  return {
                    image_url: imageUrl,
                    page_url: pageUrl && pageUrl.indexOf("https://") === 0 ? pageUrl : null,
                    photographer: cleaned_text(photo.photographer || photo.user),
                    source_name: "Airplanes.Live selected aircraft photo"
                  };
                }
                function detail_for_requested() {
                  var request = globe.flightAlertDetailRequest || null;
                  if (!request || !request.hex) return null;
                  var requestedHex = String(request.hex).toLowerCase();
                  var p = globe.planes && globe.planes[requestedHex] ? globe.planes[requestedHex] : null;
                  if (!p && SelectedPlane && String(SelectedPlane.icao || "").toLowerCase() === requestedHex) p = SelectedPlane;
                  if (!p) return null;
                  var db_flags = 0;
                  if (truthy_flag(p.military)) db_flags = db_flags | 1;
                  if (truthy_flag(p.pia)) db_flags = db_flags | 4;
                  if (truthy_flag(p.ladd)) db_flags = db_flags | 8;
                  var trace = p.fullTrace || p.recentTrace || {};
                  var route = route_info_for(p);
                  return {
                    requested_hex: requestedHex,
                    requested_callsign: cleaned_text(request.callsign),
                    requested_registration: cleaned_text(request.registration),
                    requested_elapsed_ms: Number(request.requestedElapsedMs) || 0,
                    hex: String(p.icao || requestedHex),
                    flight: p.flight || p.name || null,
                    registration: p.registration || trace.r || null,
                    type_code: p.icaoType || trace.t || null,
                    description: p.typeLong || trace.desc || p.typeDescription || null,
                    type_description: p.typeDescription || null,
                    owner_operator: p.ownOp || trace.ownOp || null,
                    operator_code: p.ownOpCode || trace.ownOpCode || null,
                    year: p.year || trace.year || null,
                    db_flags: db_flags,
                    dbinfo_loaded: !!p.dbinfoLoaded,
                    trace_loaded: !!p.fullTrace,
                    trace_source: p.fullTrace ? "Airplanes.Live trace_full" : (p.recentTrace ? "Airplanes.Live trace_recent" : null),
                    route: route.route,
                    route_source: route.route_source,
                    route_updated_epoch_sec: route.route_updated_epoch_sec,
                    route_airports: route.route_airports,
                    photo: photo_info_for(p)
                  };
                }
                return JSON.stringify({
                  ready: true,
                  now: now_sec,
                  first_fetch_done: !!(globe && globe.firstFetchDone),
                  pending_fetches: (typeof pendingFetches !== 'undefined') ? (Number(pendingFetches) || 0) : 0,
                  aircraft: rows,
                  detail: detail_for_requested()
                });
              } catch (e) {
                return JSON.stringify({ready:false, reason:String(e && e.message || e)});
              }
            })();
        """.trimIndent()
    }
}

data class GlobePhotoHint(
    val image_url: String,
    val page_url: String?,
    val photographer: String?,
    val source_name: String
)

private fun JSONObject.opt_string_or_null(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifEmpty { null }
}

private fun JSONObject.opt_long_or_null(key: String): Long? {
    return if (has(key) && !isNull(key)) optLong(key) else null
}

private fun JSONObject.opt_double_or_null(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key).takeIf { it.isFinite() }
}

private fun JSONObject.opt_int_or_null(key: String): Int? {
    return if (has(key) && !isNull(key)) optInt(key) else null
}

private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1_rad = Math.toRadians(lat1)
    val lat2_rad = Math.toRadians(lat2)
    val delta_lat = Math.toRadians(lat2 - lat1)
    val delta_lon = Math.toRadians(lon2 - lon1)
    val a = sin(delta_lat / 2.0).pow(2.0) + cos(lat1_rad) * cos(lat2_rad) * sin(delta_lon / 2.0).pow(2.0)
    return 2.0 * GLOBE_EARTH_RADIUS_M * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
}

private fun normalized_registration(value: String?): String? {
    return value
        ?.uppercase(Locale.US)
        ?.replace(Regex("[^A-Z0-9-]"), "")
        ?.trim('-')
        ?.takeIf { it.isNotBlank() && it != "NA" }
}

private fun first_present(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
}

private fun String?.compact_callsign(): String {
    return this?.replace(" ", "")?.uppercase(Locale.US).orEmpty()
}

private const val GLOBE_EARTH_RADIUS_M = 6371000.0
