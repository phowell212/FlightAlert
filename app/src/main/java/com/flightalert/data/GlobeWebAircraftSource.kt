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
class GlobeWebAircraftSource(context: Context, private val userAgent: String) {
    val webView: WebView = WebView(context)

    private val handler = Handler(Looper.getMainLooper())
    private var loaded = false
    private var hostResumed = false
    private var running = false

    @Volatile
    private var sourceEnabled = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var pendingViewport: GlobeViewport? = null
    private var viewportGeneration = 0L
    private var appliedViewportGeneration = 0L

    @Volatile
    private var latest: GlobeSnapshot? = null

    private val extractor = object : Runnable {
        override fun run() {
            if (!running) return
            extractAircraft()
            handler.postDelayed(this, EXTRACT_INTERVAL_MS)
        }
    }

    init {
        configureWebView()
    }

    fun start() {
        hostResumed = true
        startIfAllowed()
    }

    fun stop() {
        hostResumed = false
        stopActive(clearSnapshot = false, clearPage = false)
    }

    fun destroy() {
        destroyed = true
        stopActive(clearSnapshot = true, clearPage = true)
        webView.destroy()
    }

    fun setEnabled(enabled: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { setEnabled(enabled) }
            return
        }
        if (sourceEnabled == enabled) return
        sourceEnabled = enabled
        if (enabled) {
            webView.visibility = View.INVISIBLE
            startIfAllowed()
        } else {
            stopActive(clearSnapshot = true, clearPage = true)
        }
    }

    fun updateViewport(bounds: FeedBounds, centerLat: Double, centerLon: Double, zoom: Double) {
        if (!sourceEnabled || destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { updateViewport(bounds, centerLat, centerLon, zoom) }
            return
        }
        val viewport = GlobeViewport(
            bounds = bounds,
            centerLat = centerLat,
            centerLon = centerLon,
            zoom = zoom.coerceIn(MIN_GLOBE_ZOOM, MAX_GLOBE_ZOOM),
            generation = viewportGeneration
        )
        val previous = pendingViewport
        if (previous != null && previous.matches(viewport)) return
        viewportGeneration += 1L
        val next = viewport.copy(generation = viewportGeneration)
        pendingViewport = next
        latest = null
        if (loaded && running) applyViewport(next)
    }

    fun latestSnapshot(bounds: FeedBounds, ownLat: Double, ownLon: Double, exactSearch: String?): FeedResult? {
        if (!sourceEnabled || destroyed) return null
        val viewport = pendingViewport ?: return null
        val snapshot = latest ?: return null
        if (snapshot.viewportGeneration != viewport.generation) return null
        if (SystemClock.elapsedRealtime() - snapshot.receivedElapsedMs > MAX_SNAPSHOT_AGE_MS) return null
        if (snapshot.aircraft.isEmpty() && snapshot.partialCoverage) return null
        val normalizedSearch = exactSearch?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
        val normalizedBounds = normalizeBounds(bounds)
        val filtered = snapshot.aircraft
            .asSequence()
            .filter { aircraft -> normalizedSearch == null || aircraft.matchesSearch(normalizedSearch) }
            .filter { aircraft -> normalizedSearch != null || normalizedBounds.contains(aircraft.lat, aircraft.lon) }
            .map { aircraft -> aircraft.withDistanceFrom(ownLat, ownLon) }
            .sortedBy { it.distanceM }
            .toList()
        if (normalizedSearch != null && filtered.isEmpty()) return null
        return FeedResult(
            status = FeedStatus.OK,
            source = FeedSource.AIRPLANES_LIVE_GLOBE,
            aircraft = filtered,
            epochSec = snapshot.epochSec,
            queryCount = 0,
            partialCoverage = snapshot.partialCoverage
        )
    }

    private fun startIfAllowed() {
        if (destroyed || !sourceEnabled || !hostResumed || running) return
        running = true
        webView.visibility = View.INVISIBLE
        webView.onResume()
        if (!loaded || webView.url?.startsWith(AirplanesLiveHttp.GLOBE_BASE_URL) != true) {
            loaded = false
            webView.loadUrl("${AirplanesLiveHttp.GLOBE_BASE_URL}/")
        } else {
            pendingViewport?.let(::applyViewport)
        }
        handler.removeCallbacks(extractor)
        handler.postDelayed(extractor, FIRST_EXTRACT_DELAY_MS)
    }

    private fun stopActive(clearSnapshot: Boolean, clearPage: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stopActive(clearSnapshot, clearPage) }
            return
        }
        running = false
        handler.removeCallbacks(extractor)
        webView.onPause()
        if (clearSnapshot) {
            latest = null
            pendingViewport = null
            viewportGeneration = 0L
            appliedViewportGeneration = 0L
        }
        if (clearPage && !destroyed) {
            loaded = false
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.visibility = View.GONE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.alpha = 0f
        webView.visibility = View.GONE
        webView.isClickable = false
        webView.isFocusable = false
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            userAgentString = AirplanesLiveHttp.browserUserAgent(userAgent)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                loaded = sourceEnabled && url.startsWith(AirplanesLiveHttp.GLOBE_BASE_URL)
                pendingViewport?.takeIf { loaded }?.let(::applyViewport)
            }
        }
    }

    private fun applyViewport(viewport: GlobeViewport) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { applyViewport(viewport) }
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
            viewport.centerLat,
            viewport.centerLon,
            viewport.zoom,
            viewport.centerLon,
            viewport.centerLat,
            viewport.zoom
        )
        webView.evaluateJavascript(script) { result ->
            if (result == "true" && sourceEnabled && pendingViewport?.generation == viewport.generation) {
                appliedViewportGeneration = viewport.generation
            }
        }
    }

    private fun extractAircraft() {
        if (!sourceEnabled || !loaded || appliedViewportGeneration == 0L) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { extractAircraft() }
            return
        }
        val generation = appliedViewportGeneration
        webView.evaluateJavascript(EXTRACT_AIRCRAFT_JS) { value ->
            parseSnapshot(value, generation)?.let { snapshot ->
                if (sourceEnabled && pendingViewport?.generation == snapshot.viewportGeneration) {
                    latest = snapshot
                }
                Log.d(TAG, "Globe web source: ${snapshot.aircraft.size} aircraft, partial=${snapshot.partialCoverage}")
            }
        }
    }

    private fun parseSnapshot(value: String?, viewportGeneration: Long): GlobeSnapshot? {
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
            val nowSec = json.optDoubleOrNull("now") ?: (System.currentTimeMillis() / 1000.0)
            val rows = json.optJSONArray("aircraft") ?: return null
            val parsed = mutableListOf<FeedAircraft>()
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                parseAircraft(item, nowSec)?.let { parsed += it }
            }
            GlobeSnapshot(
                aircraft = parsed,
                epochSec = nowSec,
                receivedElapsedMs = SystemClock.elapsedRealtime(),
                partialCoverage = !json.optBoolean("firstFetchDone", false) || json.optInt("pendingFetches", 0) > 0,
                viewportGeneration = viewportGeneration
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAircraft(item: JSONObject, nowSec: Double): FeedAircraft? {
        val lat = item.optDoubleOrNull("lat") ?: return null
        val lon = item.optDoubleOrNull("lon") ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val hex = item.optStringOrNull("hex")?.trim()?.trimStart('~')?.lowercase(Locale.US) ?: return null
        if (!MODE_S_OR_NON_ICAO.matches(hex)) return null
        val positionTimeSec = item.optDoubleOrNull("positionTimeSec")
        if (positionTimeSec != null && nowSec - positionTimeSec > MAX_POSITION_AGE_SECONDS) return null
        val lastContactSec = item.optDoubleOrNull("lastContactSec")
        val altitude = item.opt("altitude")
        val onGround = altitude is String && altitude.equals("ground", ignoreCase = true)
        val altitudeFeet = when (altitude) {
            is Number -> altitude.toDouble()
            is String -> altitude.toDoubleOrNull() ?: if (onGround) 0.0 else null
            else -> null
        }
        val registration = item.optStringOrNull("registration")
        val callsign = item.optStringOrNull("flight") ?: registration ?: hex
        val typeCode = item.optStringOrNull("typeCode")
        val metadata = AircraftMetadataSeed(
            sourceName = FeedSource.AIRPLANES_LIVE_GLOBE.displayName,
            registration = registration,
            manufacturer = item.optStringOrNull("manufacturer"),
            type = item.optStringOrNull("description"),
            typeCode = typeCode,
            owner = item.optStringOrNull("ownerOperator"),
            manufacturedYear = item.optStringOrNull("year")?.take(4),
            operatorCode = item.optStringOrNull("operatorCode")
        ).takeIf { it.hasDetails }
        return FeedAircraft(
            icao24 = hex,
            callsign = callsign,
            registration = registration,
            typeCode = typeCode,
            metadata = metadata,
            dbFlags = item.optIntOrNull("dbFlags"),
            lat = lat,
            lon = lon,
            onGround = onGround,
            altitudeM = altitudeFeet?.let { it / FEET_PER_METER },
            velocityMs = item.optDoubleOrNull("speedKt")?.let { it * KNOTS_TO_METERS_PER_SECOND },
            trackDeg = item.optDoubleOrNull("trackDeg"),
            verticalRateMs = item.optDoubleOrNull("verticalRateFpm")?.let { it / FEET_PER_METER / 60.0 },
            category = categoryFromGlobe(item.optStringOrNull("category"), item.optStringOrNull("typeCode")),
            positionTimeSec = positionTimeSec,
            lastContactSec = lastContactSec,
            distanceM = 0.0
        )
    }

    private fun FeedAircraft.withDistanceFrom(ownLat: Double, ownLon: Double): FeedAircraft {
        return copy(distanceM = distanceMeters(ownLat, ownLon, lat, lon))
    }

    private fun FeedAircraft.matchesSearch(search: String): Boolean {
        return icao24.uppercase(Locale.US).contains(search) ||
            callsign.uppercase(Locale.US).replace(" ", "").contains(search.replace(" ", "")) ||
            registration?.uppercase(Locale.US)?.contains(search) == true
    }

    private fun GlobeBounds.contains(lat: Double, lon: Double): Boolean {
        return lat in minLat..maxLat && lon in minLon..maxLon
    }

    private fun normalizeBounds(bounds: FeedBounds): GlobeBounds {
        return GlobeBounds(
            minLat = min(bounds.minLat, bounds.maxLat).coerceIn(-90.0, 90.0),
            maxLat = max(bounds.minLat, bounds.maxLat).coerceIn(-90.0, 90.0),
            minLon = min(bounds.minLon, bounds.maxLon).coerceIn(-180.0, 180.0),
            maxLon = max(bounds.minLon, bounds.maxLon).coerceIn(-180.0, 180.0)
        )
    }

    private fun categoryFromGlobe(category: String?, typeCode: String?): Int? {
        val normalized = category?.trim()?.uppercase(Locale.US).orEmpty()
        if (normalized.startsWith("A7") || normalized.startsWith("B7")) return 8
        if (normalized.startsWith("B1")) return 9
        if (normalized.startsWith("B6")) return 14
        if (normalized.startsWith("C") || normalized.startsWith("D")) return 15
        val type = typeCode?.trim()?.uppercase(Locale.US).orEmpty()
        return when {
            type.startsWith("H") || type.startsWith("R") -> 8
            type.startsWith("GL") -> 9
            type.startsWith("UAV") || type.startsWith("DRON") -> 14
            else -> null
        }
    }

    private data class GlobeSnapshot(
        val aircraft: List<FeedAircraft>,
        val epochSec: Double,
        val receivedElapsedMs: Long,
        val partialCoverage: Boolean,
        val viewportGeneration: Long
    )

    private data class GlobeViewport(
        val bounds: FeedBounds,
        val centerLat: Double,
        val centerLon: Double,
        val zoom: Double,
        val generation: Long
    )

    private fun GlobeViewport.matches(other: GlobeViewport): Boolean {
        return abs(centerLat - other.centerLat) < VIEWPORT_LAT_LON_EPSILON &&
            abs(centerLon - other.centerLon) < VIEWPORT_LAT_LON_EPSILON &&
            abs(zoom - other.zoom) < VIEWPORT_ZOOM_EPSILON &&
            abs(bounds.minLat - other.bounds.minLat) < VIEWPORT_LAT_LON_EPSILON &&
            abs(bounds.maxLat - other.bounds.maxLat) < VIEWPORT_LAT_LON_EPSILON &&
            abs(bounds.minLon - other.bounds.minLon) < VIEWPORT_LAT_LON_EPSILON &&
            abs(bounds.maxLon - other.bounds.maxLon) < VIEWPORT_LAT_LON_EPSILON
    }

    private data class GlobeBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    private companion object {
        private const val TAG = "FlightAlert"
        private const val EXTRACT_INTERVAL_MS = 2500L
        private const val FIRST_EXTRACT_DELAY_MS = 3500L
        private const val MAX_SNAPSHOT_AGE_MS = 9000L
        private const val MAX_POSITION_AGE_SECONDS = 120.0
        private const val MIN_GLOBE_ZOOM = 2.0
        private const val MAX_GLOBE_ZOOM = 13.0
        private const val VIEWPORT_LAT_LON_EPSILON = 0.01
        private const val VIEWPORT_ZOOM_EPSILON = 0.12
        private const val FEET_PER_METER = 3.28084
        private const val KNOTS_TO_METERS_PER_SECOND = 0.514444
        private val MODE_S_OR_NON_ICAO = Regex("^[0-9a-f]{6,8}$")

        private val EXTRACT_AIRCRAFT_JS = """
            (function() {
              try {
                var globe = (typeof g !== 'undefined') ? g : null;
                if (!globe || !globe.planes) {
                  return JSON.stringify({ready:false, reason:"g.planes unavailable"});
                }
                var nowSec = (typeof now !== 'undefined') ? Number(now) : NaN;
                if (!isFinite(nowSec) || nowSec <= 0) nowSec = Date.now() / 1000;
                function truthyFlag(value) {
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
                  var positionTime = Number(p.position_time);
                  var lastMessage = Number(p.last_message_time);
                  var seenPos = isFinite(positionTime) ? nowSec - positionTime : Number(p.seen_pos);
                  if (!isFinite(seenPos) || seenPos > 120) continue;
                  var dbFlags = 0;
                  if (truthyFlag(p.military)) dbFlags = dbFlags | 1;
                  if (truthyFlag(p.pia)) dbFlags = dbFlags | 4;
                  if (truthyFlag(p.ladd)) dbFlags = dbFlags | 8;
                  rows.push({
                    hex: String(p.icao || ""),
                    flight: p.flight || p.name || null,
                    registration: p.registration || null,
                    manufacturer: p.manufacturer || p.manufacturerName || null,
                    typeCode: p.icaoType || null,
                    description: p.desc || p.description || p.typeDescription || null,
                    category: p.category || null,
                    ownerOperator: p.ownOp || p.ownerOperator || p.operator || p.owner || null,
                    operatorCode: p.ownOpCode || p.operatorCode || null,
                    year: p.year || p.mfrYear || p.manufacturedYear || null,
                    dbFlags: dbFlags,
                    lat: lat,
                    lon: lon,
                    altitude: p.altitude != null ? p.altitude : p.alt_baro,
                    speedKt: p.gs != null ? p.gs : p.speed,
                    trackDeg: p.track,
                    verticalRateFpm: p.vert_rate != null ? p.vert_rate : (p.baro_rate != null ? p.baro_rate : p.geom_rate),
                    positionTimeSec: isFinite(positionTime) ? positionTime : null,
                    lastContactSec: isFinite(lastMessage) ? lastMessage : null,
                    sourceType: p.dataSource || null
                  });
                }
                return JSON.stringify({
                  ready: true,
                  now: nowSec,
                  firstFetchDone: !!(globe && globe.firstFetchDone),
                  pendingFetches: (typeof pendingFetches !== 'undefined') ? (Number(pendingFetches) || 0) : 0,
                  aircraft: rows
                });
              } catch (e) {
                return JSON.stringify({ready:false, reason:String(e && e.message || e)});
              }
            })();
        """.trimIndent()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifEmpty { null }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key).takeIf { it.isFinite() }
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    return if (has(key) && !isNull(key)) optInt(key) else null
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val deltaLat = Math.toRadians(lat2 - lat1)
    val deltaLon = Math.toRadians(lon2 - lon1)
    val a = sin(deltaLat / 2.0).pow(2.0) + cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2.0).pow(2.0)
    return 2.0 * GLOBE_EARTH_RADIUS_M * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
}

private const val GLOBE_EARTH_RADIUS_M = 6371000.0
