package com.flightalert.ui.map

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.View
import com.flightalert.data.AircraftFeedClient
import com.flightalert.data.AircraftDetails
import com.flightalert.data.AircraftDetailsClient
import com.flightalert.data.FeedAircraft
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedStatus
import com.flightalert.data.OpenSkyClient
import com.flightalert.data.TrackPoint
import com.flightalert.service.AircraftAlertService
import com.flightalert.settings.FlightAlertSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

// Custom map cockpit: real map tiles, real aircraft feeds, and canvas UI that adapts to device shape.
class FlightMapView(context: Context) : View(context), LocationListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPath = Path()
    private val prefs: SharedPreferences = FlightAlertSettings.prefs(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val executor = Executors.newFixedThreadPool(4)
    private val tileCache = linkedMapOf<String, Bitmap>()
    private val requestedTiles = mutableSetOf<String>()
    private val aircraft = mutableListOf<Aircraft>()
    private val openSkyClient = OpenSkyClient(USER_AGENT)
    private val aircraftFeedClient = AircraftFeedClient(USER_AGENT)
    private val aircraftDetailsClient = AircraftDetailsClient(USER_AGENT)
    private val flightPathRequests = mutableSetOf<String>()

    private var locationPermissionGranted = false
    private var latestLocation: Location? = null
    private var zoom = readStoredZoom()
    private var units = UnitSystem.valueOf(prefs.getString(FlightAlertSettings.KEY_UNITS, UnitSystem.IMPERIAL.name) ?: UnitSystem.IMPERIAL.name)
    private var mapSource = readMapSource()
    private var pathNearbyFeet = prefs.getFloat(FlightAlertSettings.KEY_PATH_NEARBY_FEET, FlightAlertSettings.DEFAULT_PATH_NEARBY_FEET)
    private var alertsEnabled = prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true)
    private var alertDistanceFeet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, FlightAlertSettings.DEFAULT_ALERT_DISTANCE_FEET)
    private var alertAltitudeFeet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, FlightAlertSettings.DEFAULT_ALERT_ALTITUDE_FEET)
    private var settingsOpen = false
    private var detailsOpen = false
    private var aircraftDetails: AircraftDetails? = null
    private var aircraftDetailsStatus = "Select aircraft"
    private var aircraftPhoto: Bitmap? = null
    private var aircraftPhotoStatus = "Photo unavailable"
    private var detailsRequestInFlight = false
    private var aircraftFetchInFlight = false
    private var lastAircraftFetchMs = 0L
    private var lastTickerFetchMs = 0L
    private var lastAircraftDataEpochSec: Double? = null
    private var aircraftRefreshWaitingForViewport = false
    private var aircraftStatus = "Waiting for location"
    private var mapStatus = "Waiting for location"
    private var followingLocation = true
    private var manualCenterLat: Double? = null
    private var manualCenterLon: Double? = null
    private var selectedAircraftId: String? = null
    private var selectedFlightPathAircraftId: String? = null
    private var selectedFlightPath: List<TrackPoint>? = null
    private var selectedFlightPathVisible = false
    private var selectedPathFitRequested = false
    private val pathConflictTracks = linkedMapOf<String, List<TrackPoint>>()
    private val pathConflictRequests = mutableSetOf<String>()
    private var pathConflictSelectedId: String? = null
    private var pathConflictMatches = emptySet<String>()
    private var pathConflictStatus = "Unavailable"
    private var downX = 0f
    private var downY = 0f
    private var dragStarted = false
    private var dragBlocked = false
    private var dragStartCenter: WorldPoint? = null
    private var pinchInProgress = false
    private var lastPinchSpan = 0f
    private var lastPinchFocusX = 0f
    private var lastPinchFocusY = 0f
    private var safeInsetLeft = 0f
    private var safeInsetTop = 0f
    private var safeInsetRight = 0f
    private var safeInsetBottom = 0f

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTickerFetchMs >= AIRCRAFT_TICKER_FETCH_MS) {
                lastTickerFetchMs = now
                requestVisibleAircraftIfNeeded()
            }
            postInvalidateOnAnimation()
            postOnAnimation(this)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(MAP_EMPTY)
        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        strokePaint.style = Paint.Style.STROKE
        setupSystemInsets()
    }

    fun start() {
        startLocationUpdates()
        removeCallbacks(ticker)
        postOnAnimation(ticker)
    }

    fun stop() {
        removeCallbacks(ticker)
        if (locationPermissionGranted) {
            try {
                locationManager.removeUpdates(this)
            } catch (_: SecurityException) {
                locationPermissionGranted = false
            }
        }
    }

    fun setLocationPermissionGranted(granted: Boolean) {
        if (locationPermissionGranted == granted) return
        locationPermissionGranted = granted
        if (granted) {
            startLocationUpdates()
        } else {
            latestLocation = null
            aircraft.clear()
            aircraftStatus = "Location permission required"
            mapStatus = "Location permission required"
        }
        invalidate()
    }

    fun handleBackPress(): Boolean {
        if (detailsOpen) {
            detailsOpen = false
            invalidate()
            return true
        }
        if (!settingsOpen) return false
        settingsOpen = false
        invalidate()
        return true
    }

    override fun onLocationChanged(location: Location) {
        latestLocation = location
        mapStatus = "Loading map"
        applyInitialMavicRangeZoomIfNeeded()
        requestVisibleAircraftIfNeeded(force = true)
        invalidate()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = startLocationUpdates()

    override fun onProviderDisabled(provider: String) {
        if (latestLocation == null) {
            mapStatus = "Enable device location"
            aircraftStatus = "Enable device location"
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyInitialMavicRangeZoomIfNeeded()
        requestDeferredAircraftRefresh()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = PANEL
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Draw and hit-test inside system-bar-safe content so Android chrome never covers controls.
        val w = contentWidth()
        val h = contentHeight()
        val location = latestLocation

        canvas.save()
        canvas.translate(safeInsetLeft, safeInsetTop)
        canvas.clipRect(0f, 0f, w, h)
        paint.color = MAP_EMPTY
        canvas.drawRect(0f, 0f, w, h, paint)

        if (location == null) {
            drawNoLocationState(canvas, w, h)
        } else {
            val viewport = viewportFor(location, w, h)
            drawMapTiles(canvas, viewport)
            drawSelectedFlightPath(canvas, viewport)
            drawAircraft(canvas, viewport)
            drawOwnship(canvas, viewport, location)
        }

        drawTopStatus(canvas, w, h)
        drawRecenterButton(canvas, w, h)
        location?.let { drawFlightPathButtons(canvas, viewportFor(it, w, h), w, h) }
        drawSettingsButton(canvas, w, h)
        drawTrafficPanel(canvas, w, h)

        if (detailsOpen) {
            drawAircraftDetailsPanel(canvas, w, h)
        }
        if (settingsOpen) {
            drawSettingsPanel(canvas, w, h)
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = contentX(event.x)
        val y = contentY(event.y)
        if (!settingsOpen && !detailsOpen && event.pointerCount >= 2) {
            parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> beginPinch(event)
                MotionEvent.ACTION_MOVE -> updatePinch(event)
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> endPinch()
            }
            return true
        }
        if (pinchInProgress) {
            endPinch()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = x
                downY = y
                dragStarted = false
                dragBlocked = isOverlayOrControlHit(x, y)
                dragStartCenter = latestLocation?.let { viewportFor(it, contentWidth(), contentHeight()) }?.let {
                    WorldPoint(it.centerX, it.centerY)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!settingsOpen && !detailsOpen && !dragBlocked && dragStartCenter != null && latestLocation != null) {
                    val dx = x - downX
                    val dy = y - downY
                    if (!dragStarted && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        dragStarted = true
                    }
                    if (dragStarted) {
                        val start = dragStartCenter ?: return true
                        selectedPathFitRequested = false
                        setManualCenterFromWorld(start.x - dx, start.y - dy)
                        postInvalidateOnAnimation()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragStarted) {
                    requestVisibleAircraftIfNeeded(force = true)
                    return true
                }
                if (abs(x - downX) > dp(12) || abs(y - downY) > dp(12)) return true
                performClick()
                handleTap(x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragStarted = false
                dragStartCenter = null
                return true
            }
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isPointerScroll = event.action == MotionEvent.ACTION_SCROLL &&
            (event.source and InputDevice.SOURCE_CLASS_POINTER) != 0
        if (!settingsOpen && !detailsOpen && isPointerScroll && latestLocation != null) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (scroll != 0f) {
                // Hardware wheels/trackpads zoom around the cursor, matching mouse use in the emulator.
                val focusX = contentX(event.x).coerceIn(0f, contentWidth())
                val focusY = contentY(event.y).coerceIn(0f, contentHeight())
                val scaleFactor = 2.0.pow(scroll.toDouble() * 0.22)
                zoomAndPanDuringPinch(scaleFactor, focusX, focusY, focusX, focusY)
                requestVisibleAircraftIfNeeded(force = true)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!settingsOpen && !detailsOpen && latestLocation != null) {
            val zoomStep = when (keyCode) {
                KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_PLUS,
                KeyEvent.KEYCODE_NUMPAD_ADD -> 0.5
                KeyEvent.KEYCODE_MINUS,
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> -0.5
                else -> null
            }
            if (zoomStep != null) {
                // Keyboard zoom is centered in the open map area so panels do not steal the focal point.
                val focus = defaultMapFocus(contentWidth(), contentHeight())
                val scaleFactor = 2.0.pow(zoomStep)
                zoomAndPanDuringPinch(scaleFactor, focus.x, focus.y, focus.x, focus.y)
                requestVisibleAircraftIfNeeded(force = true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun beginPinch(event: MotionEvent) {
        pinchInProgress = true
        dragStarted = false
        dragBlocked = true
        dragStartCenter = null
        lastPinchSpan = pointerSpan(event)
        lastPinchFocusX = pointerFocusX(event)
        lastPinchFocusY = pointerFocusY(event)
        selectedPathFitRequested = false
    }

    private fun updatePinch(event: MotionEvent) {
        if (!pinchInProgress) beginPinch(event)
        val span = pointerSpan(event)
        val focusX = pointerFocusX(event)
        val focusY = pointerFocusY(event)
        if (span <= dp(20) || lastPinchSpan <= dp(20)) {
            lastPinchSpan = span
            lastPinchFocusX = focusX
            lastPinchFocusY = focusY
            return
        }

        val scaleFactor = (span / lastPinchSpan).toDouble()
        val moved = abs(focusX - lastPinchFocusX) > dp(0.5f) || abs(focusY - lastPinchFocusY) > dp(0.5f)
        if (abs(scaleFactor - 1.0) >= 0.01 || moved) {
            zoomAndPanDuringPinch(scaleFactor, lastPinchFocusX, lastPinchFocusY, focusX, focusY)
        }
        lastPinchSpan = span
        lastPinchFocusX = focusX
        lastPinchFocusY = focusY
    }

    private fun endPinch() {
        pinchInProgress = false
        lastPinchSpan = 0f
        lastPinchFocusX = 0f
        lastPinchFocusY = 0f
        dragStarted = false
        dragStartCenter = null
        parent?.requestDisallowInterceptTouchEvent(false)
        requestVisibleAircraftIfNeeded(force = true)
    }

    private fun zoomAndPanDuringPinch(scaleFactor: Double, oldFocusX: Float, oldFocusY: Float, newFocusX: Float, newFocusY: Float) {
        val location = latestLocation ?: return
        val oldViewport = viewportFor(location, contentWidth(), contentHeight())
        val anchorGeo = worldToLatLon(
            oldViewport.centerX - oldViewport.width / 2.0 + oldFocusX,
            oldViewport.centerY - oldViewport.height / 2.0 + oldFocusY,
            oldViewport.zoom
        )
        val nextZoom = (zoom + ln(scaleFactor) / ln(2.0)).coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())

        zoom = nextZoom
        val focusWorld = latLonToWorld(anchorGeo.lat, anchorGeo.lon, zoom)
        setManualCenterFromWorld(
            focusWorld.x + contentWidth() / 2.0 - newFocusX,
            focusWorld.y + contentHeight() / 2.0 - newFocusY
        )
        prefs.edit().putFloat(FlightAlertSettings.KEY_ZOOM, zoom.toFloat()).apply()
        postInvalidateOnAnimation()
    }

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun pointerFocusX(event: MotionEvent): Float {
        return contentX(if (event.pointerCount >= 2) (event.getX(0) + event.getX(1)) / 2f else event.x)
    }

    private fun pointerFocusY(event: MotionEvent): Float {
        return contentY(if (event.pointerCount >= 2) (event.getY(0) + event.getY(1)) / 2f else event.y)
    }

    private fun setupSystemInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val cutout = insets.displayCutout
                val useCutoutInsets = cutout != null && hasNonHolePunchCutout(cutout.boundingRects)
                updateSafeInsets(
                    max(bars.left, if (useCutoutInsets) cutout?.safeInsetLeft ?: 0 else 0),
                    max(bars.top, if (useCutoutInsets) cutout?.safeInsetTop ?: 0 else 0),
                    max(bars.right, if (useCutoutInsets) cutout?.safeInsetRight ?: 0 else 0),
                    max(bars.bottom, if (useCutoutInsets) cutout?.safeInsetBottom ?: 0 else 0)
                )
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { _, insets ->
                updateSafeInsets(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
                insets
            }
        }
        post { requestApplyInsets() }
    }

    private fun hasNonHolePunchCutout(cutouts: List<Rect>): Boolean {
        if (cutouts.isEmpty()) return false
        return cutouts.any { !isHolePunchCutout(it) }
    }

    private fun isHolePunchCutout(cutout: Rect): Boolean {
        val maxSize = max(cutout.width(), cutout.height()).toFloat()
        val minSize = min(cutout.width(), cutout.height()).toFloat()
        // Small, roughly round/square cutouts are hole-punch cameras; wide edge cutouts still get avoided.
        return maxSize <= dp(HOLE_PUNCH_MAX_SIZE_DP) && minSize >= maxSize * 0.55f
    }

    private fun updateSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val nextLeft = left.toFloat()
        val nextTop = top.toFloat()
        val nextRight = right.toFloat()
        val nextBottom = bottom.toFloat()
        if (safeInsetLeft == nextLeft && safeInsetTop == nextTop && safeInsetRight == nextRight && safeInsetBottom == nextBottom) return
        safeInsetLeft = nextLeft
        safeInsetTop = nextTop
        safeInsetRight = nextRight
        safeInsetBottom = nextBottom
        requestDeferredAircraftRefresh()
        invalidate()
    }

    private fun contentWidth(): Float = max(1f, width.toFloat() - safeInsetLeft - safeInsetRight)

    private fun contentHeight(): Float = max(1f, height.toFloat() - safeInsetTop - safeInsetBottom)

    private fun contentX(screenX: Float): Float = screenX - safeInsetLeft

    private fun contentY(screenY: Float): Float = screenY - safeInsetTop

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            locationPermissionGranted = false
            return
        }
        locationPermissionGranted = true
        try {
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                val last = locationManager.getLastKnownLocation(provider)
                if (last != null && isBetterLocation(last, latestLocation)) latestLocation = last
            }
            if (latestLocation != null) requestVisibleAircraftIfNeeded(force = true)
            if (latestLocation == null) {
                mapStatus = "Waiting for device location"
                aircraftStatus = "Waiting for device location"
            }
            if (providers.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, this)
            }
            if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 10f, this)
            }
        } catch (_: SecurityException) {
            locationPermissionGranted = false
            mapStatus = "Location permission required"
            aircraftStatus = "Location permission required"
        }
    }

    private fun hasLocationPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBetterLocation(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        return candidate.time > current.time || candidate.accuracy < current.accuracy
    }

    private fun viewportFor(location: Location, w: Float, h: Float): Viewport {
        val centerLat = if (followingLocation) location.latitude else manualCenterLat ?: location.latitude
        val centerLon = if (followingLocation) location.longitude else manualCenterLon ?: location.longitude
        val center = latLonToWorld(centerLat, centerLon, zoom)
        val focus = if (followingLocation) defaultMapFocus(w, h) else ScreenPoint(w / 2f, h / 2f)
        return Viewport(
            zoom = zoom,
            centerX = center.x + w / 2.0 - focus.x,
            centerY = center.y + h / 2.0 - focus.y,
            width = w,
            height = h
        )
    }

    private fun drawMapTiles(canvas: Canvas, viewport: Viewport) {
        paint.style = Paint.Style.FILL
        paint.color = MAP_EMPTY
        canvas.drawRect(0f, 0f, viewport.width, viewport.height, paint)

        val leftWorld = viewport.centerX - viewport.width / 2.0
        val topWorld = viewport.centerY - viewport.height / 2.0
        val tileZoom = viewport.zoom.toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
        val tileToViewportScale = 2.0.pow(viewport.zoom - tileZoom)
        val tileWorldScale = 1.0 / tileToViewportScale
        val firstTileX = floor(leftWorld * tileWorldScale / TILE_SIZE).toInt()
        val firstTileY = floor(topWorld * tileWorldScale / TILE_SIZE).toInt()
        val lastTileX = floor((leftWorld + viewport.width) * tileWorldScale / TILE_SIZE).toInt()
        val lastTileY = floor((topWorld + viewport.height) * tileWorldScale / TILE_SIZE).toInt()
        val maxTile = 1 shl tileZoom
        var loaded = 0
        var requested = 0

        for (ty in firstTileY..lastTileY) {
            if (ty < 0 || ty >= maxTile) continue
            for (txRaw in firstTileX..lastTileX) {
                val tx = ((txRaw % maxTile) + maxTile) % maxTile
                val key = "${mapSource.cacheKey}/$tileZoom/$tx/$ty"
                val screenX = (txRaw * TILE_SIZE * tileToViewportScale - leftWorld).toFloat()
                val screenY = (ty * TILE_SIZE * tileToViewportScale - topWorld).toFloat()
                val tileSizeOnScreen = (TILE_SIZE * tileToViewportScale).toFloat()
                val bitmap = getTileBitmap(tileZoom, tx, ty)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, RectF(screenX, screenY, screenX + tileSizeOnScreen, screenY + tileSizeOnScreen), null)
                    loaded++
                } else {
                    requested++
                    requestTile(tileZoom, tx, ty, key)
                    drawTilePlaceholder(canvas, screenX, screenY, tileSizeOnScreen)
                }
            }
        }
        mapStatus = if (requested == 0 && loaded > 0) "${mapSource.displayName} loaded" else "Loading ${mapSource.displayName.lowercase(Locale.US)} tiles"
    }

    private fun drawTilePlaceholder(canvas: Canvas, x: Float, y: Float, size: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(91, 121, 105)
        canvas.drawRect(x, y, x + size, y + size, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(10)
        textPaint.color = Color.argb(170, 236, 241, 231)
        canvas.drawText("Loading map", x + size / 2f, y + size / 2f, textPaint)
    }

    private fun drawMapColorFilter(canvas: Canvas, viewport: Viewport) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(58, 11, 42, 39)
        canvas.drawRect(0f, 0f, viewport.width, viewport.height, paint)
        paint.color = Color.argb(34, 42, 92, 105)
        canvas.drawRect(0f, 0f, viewport.width, viewport.height, paint)
    }

    private fun drawSelectedFlightPath(canvas: Canvas, viewport: Viewport) {
        val pathPoints = selectedPathPoints(visibleOnly = true) ?: return
        if (pathPoints.size < 2) return

        iconPath.reset()
        pathPoints.forEachIndexed { index, point ->
            val world = latLonToWorld(point.lat, point.lon, viewport.zoom)
            val sx = (world.x - viewport.centerX + viewport.width / 2.0).toFloat()
            val sy = (world.y - viewport.centerY + viewport.height / 2.0).toFloat()
            if (index == 0) iconPath.moveTo(sx, sy) else iconPath.lineTo(sx, sy)
        }
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(5)
        strokePaint.color = Color.argb(90, 10, 18, 19)
        canvas.drawPath(iconPath, strokePaint)
        strokePaint.strokeWidth = dp(2.4f)
        strokePaint.color = ACCENT_YELLOW
        canvas.drawPath(iconPath, strokePaint)
    }

    private fun getTileBitmap(z: Int, x: Int, y: Int): Bitmap? {
        val key = "${mapSource.cacheKey}/$z/$x/$y"
        tileCache[key]?.let { return it }
        val file = tileFile(z, x, y)
        if (file.exists() && file.length() > 0L && System.currentTimeMillis() - file.lastModified() < TILE_CACHE_MAX_AGE_MS) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                putTileInMemory(key, bitmap)
                return bitmap
            }
        }
        return null
    }

    private fun requestTile(z: Int, x: Int, y: Int, key: String) {
        synchronized(requestedTiles) {
            if (requestedTiles.contains(key)) return
            requestedTiles += key
        }
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                // TileSource is the only map switch; every background tile comes from a live public source.
                val url = URL(mapSource.tileUrl(z, x, y))
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val file = tileFile(z, x, y)
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                        synchronized(tileCache) { putTileInMemory(key, bitmap) }
                    }
                } else {
                    connection.errorStream?.close()
                    mapStatus = "Map tiles unavailable"
                }
            } catch (_: Exception) {
                mapStatus = "Map network unavailable"
            } finally {
                connection?.disconnect()
                synchronized(requestedTiles) { requestedTiles -= key }
                postInvalidate()
            }
        }
    }

    private fun putTileInMemory(key: String, bitmap: Bitmap) {
        tileCache[key] = bitmap
        while (tileCache.size > MAX_MEMORY_TILES) {
            val firstKey = tileCache.keys.firstOrNull() ?: break
            tileCache.remove(firstKey)
        }
    }

    private fun tileFile(z: Int, x: Int, y: Int): File {
        return File(context.cacheDir, "${mapSource.cacheKey}_tiles/$z/$x/$y.png")
    }

    private fun requestVisibleAircraftIfNeeded(force: Boolean = false) {
        val location = latestLocation ?: return
        if (!hasUsableViewport()) {
            aircraftRefreshWaitingForViewport = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (aircraftFetchInFlight) return
        val minFetchIntervalMs = if (force) AIRCRAFT_FORCE_REFRESH_MS else AIRCRAFT_REFRESH_MS
        if (now - lastAircraftFetchMs < minFetchIntervalMs) return
        aircraftFetchInFlight = true
        lastAircraftFetchMs = now

        val bounds = aircraftBoundsForCurrentViewport(location)
        executor.execute {
            try {
                val result = aircraftFeedClient.fetchAircraft(bounds.toFeedBounds(), location.latitude, location.longitude)
                if (result.status == FeedStatus.OK) {
                    val parsed = result.aircraft.map { it.toMapAircraft() }
                    Log.d(TAG, "Aircraft feed ${result.source.displayName}: ${parsed.size} aircraft")
                    synchronized(aircraft) {
                        aircraft.clear()
                        aircraft.addAll(parsed)
                    }
                    lastAircraftDataEpochSec = result.epochSec
                    aircraftStatus = if (parsed.isEmpty()) {
                        "No aircraft reported in current map area (${result.source.displayName})"
                    } else {
                        "Live aircraft updated via ${result.source.displayName}"
                    }
                } else {
                    aircraftStatus = when {
                        result.httpCode != null -> "Aircraft feed unavailable: HTTP ${result.httpCode}"
                        result.status == FeedStatus.RATE_LIMITED -> "Aircraft feed rate limited"
                        else -> "Aircraft feed unavailable"
                    }
                    Log.d(TAG, "Aircraft feed ${result.source.displayName}: ${result.status} http=${result.httpCode ?: "none"}")
                }
            } catch (_: Exception) {
                aircraftStatus = "Aircraft feed unavailable"
                Log.d(TAG, "Aircraft feed request failed")
            } finally {
                aircraftFetchInFlight = false
                postInvalidate()
            }
        }
    }

    private fun requestDeferredAircraftRefresh() {
        if (!aircraftRefreshWaitingForViewport || latestLocation == null || !hasUsableViewport()) return
        aircraftRefreshWaitingForViewport = false
        requestVisibleAircraftIfNeeded(force = true)
    }

    private fun hasUsableViewport(): Boolean {
        return width > 0 &&
            height > 0 &&
            width.toFloat() - safeInsetLeft - safeInsetRight >= dp(180) &&
            height.toFloat() - safeInsetTop - safeInsetBottom >= dp(180)
    }

    private fun requestFlightPath(icao24: String, force: Boolean = false) {
        val key = icao24.trim().lowercase(Locale.US)
        if (key.isEmpty()) return
        if (!force && selectedFlightPathAircraftId == key && selectedFlightPath != null) return
        synchronized(flightPathRequests) {
            if (flightPathRequests.contains(key)) return
            flightPathRequests += key
        }

        executor.execute {
            try {
                val points = openSkyClient.fetchTrack(key)
                post {
                    if (selectedAircraftId?.lowercase(Locale.US) == key) {
                        selectedFlightPathAircraftId = if (points.size >= 2) key else null
                        selectedFlightPath = points.takeIf { it.size >= 2 }
                        selectedFlightPathVisible = false
                        invalidate()
                    }
                }
            } catch (_: Exception) {
                post {
                    if (selectedAircraftId?.lowercase(Locale.US) == key) {
                        selectedFlightPathAircraftId = null
                        selectedFlightPath = null
                        selectedFlightPathVisible = false
                        invalidate()
                    }
                }
            } finally {
                synchronized(flightPathRequests) { flightPathRequests -= key }
            }
        }
    }

    private fun aircraftBoundsForCurrentViewport(location: Location): Bounds {
        if (!hasUsableViewport()) return aircraftBoundsAroundLocation(location)
        val viewport = viewportFor(location, contentWidth(), contentHeight())
        val bounds = boundsForViewport(viewport)
        return bounds ?: aircraftBoundsAroundLocation(location)
    }

    private fun boundsForViewport(viewport: Viewport): Bounds? {
        val padding = AIRCRAFT_BOUNDS_PADDING_PX
        val left = viewport.centerX - viewport.width / 2.0 - padding
        val right = viewport.centerX + viewport.width / 2.0 + padding
        val top = viewport.centerY - viewport.height / 2.0 - padding
        val bottom = viewport.centerY + viewport.height / 2.0 + padding
        val topLeft = worldToLatLon(left, top, viewport.zoom)
        val bottomRight = worldToLatLon(right, bottom, viewport.zoom)
        if (abs(topLeft.lon - bottomRight.lon) > 180.0) return null
        return Bounds(
            minLat = min(topLeft.lat, bottomRight.lat).coerceIn(-90.0, 90.0),
            minLon = min(topLeft.lon, bottomRight.lon).coerceIn(-180.0, 180.0),
            maxLat = max(topLeft.lat, bottomRight.lat).coerceIn(-90.0, 90.0),
            maxLon = max(topLeft.lon, bottomRight.lon).coerceIn(-180.0, 180.0)
        )
    }

    private fun aircraftBoundsAroundLocation(location: Location): Bounds {
        val radiusKm = when (zoom) {
            in 0.0..8.999 -> 90.0
            in 9.0..9.999 -> 65.0
            in 10.0..10.999 -> 45.0
            in 11.0..11.999 -> 28.0
            in 12.0..12.999 -> 16.0
            else -> 10.0
        }
        val latDelta = radiusKm / 111.0
        val lonDelta = radiusKm / (111.0 * max(0.25, cos(Math.toRadians(location.latitude))))
        return Bounds(
            minLat = (location.latitude - latDelta).coerceIn(-90.0, 90.0),
            maxLat = (location.latitude + latDelta).coerceIn(-90.0, 90.0),
            minLon = (location.longitude - lonDelta).coerceIn(-180.0, 180.0),
            maxLon = (location.longitude + lonDelta).coerceIn(-180.0, 180.0)
        )
    }

    private fun drawAircraft(canvas: Canvas, viewport: Viewport) {
        val snapshot = visibleAircraftSnapshot()
        val labeled = snapshot.take(LABEL_AIRCRAFT_COUNT).toSet()
        val selectedId = selectedAircraftId
        for (item in snapshot) {
            val estimated = estimatedAircraftPosition(item)
            val point = latLonToWorld(estimated.lat, estimated.lon, viewport.zoom)
            val sx = (point.x - viewport.centerX + viewport.width / 2.0).toFloat()
            val sy = (point.y - viewport.centerY + viewport.height / 2.0).toFloat()
            if (sx < -dp(32) || sx > viewport.width + dp(32) || sy < -dp(32) || sy > viewport.height + dp(32)) continue
            drawAircraftIcon(canvas, sx, sy, item, item.icao24 == selectedId)
            if (labeled.contains(item)) {
                drawAircraftLabel(canvas, sx, sy, item)
            }
        }
    }

    private fun drawAircraftIcon(canvas: Canvas, x: Float, y: Float, aircraft: Aircraft, selected: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(74, 16, 23, 25)
        canvas.drawCircle(x + dp(3), y + dp(4), dp(16), paint)
        val color = aircraftColor(aircraft)
        if (selected) {
            strokePaint.color = Color.argb(235, Color.red(ACCENT_GREEN), Color.green(ACCENT_GREEN), Color.blue(ACCENT_GREEN))
            strokePaint.strokeWidth = dp(2.6f)
            canvas.drawCircle(x, y, dp(24), strokePaint)
        }

        canvas.save()
        canvas.translate(x, y)
        if (aircraft.trackDeg != null && aircraftSymbol(aircraft) != AircraftSymbol.SURFACE) {
            canvas.rotate(aircraft.trackDeg.toFloat())
        }
        paint.color = color
        strokePaint.color = Color.argb(235, 20, 25, 27)
        strokePaint.strokeWidth = dp(1.2f)
        when (aircraftSymbol(aircraft)) {
            AircraftSymbol.ROTORCRAFT -> drawRotorcraftSymbol(canvas)
            AircraftSymbol.GLIDER -> drawGliderSymbol(canvas)
            AircraftSymbol.UAV -> drawUavSymbol(canvas)
            AircraftSymbol.SURFACE -> drawSurfaceSymbol(canvas)
            AircraftSymbol.HEAVY -> drawHeavyJetSymbol(canvas)
            AircraftSymbol.FIXED_WING -> drawFixedWingSymbol(canvas)
        }
        canvas.restore()
    }

    private fun drawAircraftLabel(canvas: Canvas, x: Float, y: Float, aircraft: Aircraft) {
        val callsign = aircraft.callsign.ifBlank { aircraft.icao24.uppercase(Locale.US) }
        val detail = formatAircraftDetail(aircraft)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(14)
        textPaint.color = Color.argb(210, 10, 14, 15)
        canvas.drawText(callsign, x + dp(22), y - dp(6), textPaint)
        textPaint.textSize = sp(12)
        canvas.drawText(detail, x + dp(22), y + dp(13), textPaint)

        textPaint.textSize = sp(14)
        textPaint.color = TEXT
        canvas.drawText(callsign, x + dp(20), y - dp(8), textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = Color.argb(230, Color.red(TEXT), Color.green(TEXT), Color.blue(TEXT))
        canvas.drawText(detail, x + dp(20), y + dp(11), textPaint)
    }

    private fun visibleAircraftSnapshot(): List<Aircraft> {
        val snapshot = synchronized(aircraft) { aircraft.toList() }
        if (!selectedFlightPathVisible || !hasSelectedFlightPath()) return snapshot
        val selectedId = selectedAircraftId?.lowercase(Locale.US) ?: return snapshot
        requestPathConflictTracks(snapshot)
        if (pathConflictStatus != "Verified") return snapshot
        return snapshot.filter { it.icao24.lowercase(Locale.US) == selectedId || pathConflictMatches.contains(it.icao24.lowercase(Locale.US)) }
    }

    private fun requestPathConflictTracks(snapshot: List<Aircraft>) {
        val selectedId = selectedAircraftId?.lowercase(Locale.US) ?: return
        val selectedPath = selectedFlightPath?.takeIf { it.size >= 2 } ?: return
        if (pathConflictSelectedId != selectedId) {
            pathConflictSelectedId = selectedId
            pathConflictTracks.clear()
            pathConflictRequests.clear()
            pathConflictMatches = emptySet()
            pathConflictStatus = "Loading"
        }

        val candidates = snapshot
            .map { it.icao24.lowercase(Locale.US) }
            .filter { it != selectedId && it.isNotBlank() }
            .take(MAX_CONFLICT_TRACKS)
        val missing = candidates.filter { it !in pathConflictTracks.keys && it !in pathConflictRequests }
        missing.forEach { requestConflictTrack(it) }
        updatePathConflictMatches(selectedPath, candidates)
    }

    private fun requestConflictTrack(icao24: String) {
        pathConflictRequests += icao24
        executor.execute {
            val points = try {
                openSkyClient.fetchTrack(icao24).takeIf { it.size >= 2 }
            } catch (_: Exception) {
                null
            }
            post {
                pathConflictRequests -= icao24
                if (points != null) {
                    pathConflictTracks[icao24] = points
                }
                val ids = synchronized(aircraft) { aircraft.map { item -> item.icao24.lowercase(Locale.US) } }
                selectedFlightPath?.let { updatePathConflictMatches(it, ids) }
                invalidate()
            }
        }
    }

    private fun updatePathConflictMatches(selectedPath: List<TrackPoint>, candidates: List<String>) {
        val matches = candidates
            .filter { id -> pathConflictTracks[id]?.let { hasPathConflict(selectedPath, it) } == true }
            .toSet()
        pathConflictMatches = matches
        pathConflictStatus = when {
            pathConflictRequests.isNotEmpty() -> "Loading"
            candidates.isEmpty() -> "Verified"
            pathConflictTracks.keys.containsAll(candidates) -> "Verified"
            pathConflictTracks.isNotEmpty() -> "Partial"
            else -> "Unverified"
        }
    }

    private fun hasPathConflict(selectedPath: List<TrackPoint>, otherPath: List<TrackPoint>): Boolean {
        val distanceLimit = feetToMeters(pathNearbyFeet.toDouble())
        for (selected in selectedPath) {
            for (other in otherPath) {
                if (abs(selected.epochSec - other.epochSec) > PATH_CONFLICT_TIME_WINDOW_SEC) continue
                if (distanceMeters(selected.lat, selected.lon, other.lat, other.lon) <= distanceLimit) return true
            }
        }
        return false
    }

    private fun drawFixedWingSymbol(canvas: Canvas) {
        iconPath.reset()
        iconPath.moveTo(0f, -dp(18))
        iconPath.lineTo(dp(4), -dp(4))
        iconPath.lineTo(dp(18), dp(1))
        iconPath.lineTo(dp(18), dp(5))
        iconPath.lineTo(dp(4), dp(4))
        iconPath.lineTo(dp(3), dp(14))
        iconPath.lineTo(dp(9), dp(18))
        iconPath.lineTo(dp(2), dp(17))
        iconPath.lineTo(0f, dp(13))
        iconPath.lineTo(-dp(2), dp(17))
        iconPath.lineTo(-dp(9), dp(18))
        iconPath.lineTo(-dp(3), dp(14))
        iconPath.lineTo(-dp(4), dp(4))
        iconPath.lineTo(-dp(18), dp(5))
        iconPath.lineTo(-dp(18), dp(1))
        iconPath.lineTo(-dp(4), -dp(4))
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        canvas.drawPath(iconPath, strokePaint)
    }

    private fun drawHeavyJetSymbol(canvas: Canvas) {
        iconPath.reset()
        iconPath.moveTo(0f, -dp(21))
        iconPath.lineTo(dp(6), -dp(5))
        iconPath.lineTo(dp(23), dp(1))
        iconPath.lineTo(dp(23), dp(6))
        iconPath.lineTo(dp(7), dp(6))
        iconPath.lineTo(dp(5), dp(17))
        iconPath.lineTo(dp(12), dp(21))
        iconPath.lineTo(dp(3), dp(20))
        iconPath.lineTo(0f, dp(15))
        iconPath.lineTo(-dp(3), dp(20))
        iconPath.lineTo(-dp(12), dp(21))
        iconPath.lineTo(-dp(5), dp(17))
        iconPath.lineTo(-dp(7), dp(6))
        iconPath.lineTo(-dp(23), dp(6))
        iconPath.lineTo(-dp(23), dp(1))
        iconPath.lineTo(-dp(6), -dp(5))
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        canvas.drawPath(iconPath, strokePaint)
        canvas.drawCircle(-dp(10), dp(7), dp(2), strokePaint)
        canvas.drawCircle(dp(10), dp(7), dp(2), strokePaint)
    }

    private fun drawRotorcraftSymbol(canvas: Canvas) {
        val body = RectF(-dp(8), -dp(7), dp(9), dp(8))
        canvas.drawOval(body, paint)
        canvas.drawOval(body, strokePaint)
        strokePaint.strokeWidth = dp(2.5f)
        canvas.drawLine(-dp(24), 0f, dp(24), 0f, strokePaint)
        canvas.drawLine(0f, -dp(22), 0f, dp(22), strokePaint)
        strokePaint.strokeWidth = dp(2)
        canvas.drawLine(dp(9), dp(1), dp(23), dp(9), strokePaint)
        canvas.drawLine(dp(21), dp(5), dp(25), dp(13), strokePaint)
        strokePaint.strokeWidth = dp(1.2f)
    }

    private fun drawGliderSymbol(canvas: Canvas) {
        iconPath.reset()
        iconPath.moveTo(0f, -dp(16))
        iconPath.lineTo(dp(3), dp(2))
        iconPath.lineTo(dp(27), dp(5))
        iconPath.lineTo(dp(4), dp(8))
        iconPath.lineTo(dp(2), dp(17))
        iconPath.lineTo(-dp(2), dp(17))
        iconPath.lineTo(-dp(4), dp(8))
        iconPath.lineTo(-dp(27), dp(5))
        iconPath.lineTo(-dp(3), dp(2))
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        canvas.drawPath(iconPath, strokePaint)
    }

    private fun drawUavSymbol(canvas: Canvas) {
        iconPath.reset()
        iconPath.moveTo(0f, -dp(13))
        iconPath.lineTo(dp(8), 0f)
        iconPath.lineTo(0f, dp(13))
        iconPath.lineTo(-dp(8), 0f)
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        canvas.drawPath(iconPath, strokePaint)
        strokePaint.strokeWidth = dp(2)
        canvas.drawLine(-dp(6), -dp(6), -dp(18), -dp(18), strokePaint)
        canvas.drawLine(dp(6), -dp(6), dp(18), -dp(18), strokePaint)
        canvas.drawLine(-dp(6), dp(6), -dp(18), dp(18), strokePaint)
        canvas.drawLine(dp(6), dp(6), dp(18), dp(18), strokePaint)
        listOf(
            -dp(20) to -dp(20),
            dp(20) to -dp(20),
            -dp(20) to dp(20),
            dp(20) to dp(20)
        ).forEach { (x, y) ->
            canvas.drawCircle(x, y, dp(5), paint)
            canvas.drawCircle(x, y, dp(5), strokePaint)
            canvas.drawLine(x - dp(5), y, x + dp(5), y, strokePaint)
        }
        strokePaint.strokeWidth = dp(1.2f)
    }
    private fun drawSurfaceSymbol(canvas: Canvas) {
        iconPath.reset()
        iconPath.moveTo(0f, -dp(15))
        iconPath.lineTo(dp(5), -dp(4))
        iconPath.lineTo(dp(18), dp(1))
        iconPath.lineTo(dp(15), dp(6))
        iconPath.lineTo(dp(4), dp(4))
        iconPath.lineTo(dp(2), dp(13))
        iconPath.lineTo(-dp(2), dp(13))
        iconPath.lineTo(-dp(4), dp(4))
        iconPath.lineTo(-dp(15), dp(6))
        iconPath.lineTo(-dp(18), dp(1))
        iconPath.lineTo(-dp(5), -dp(4))
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        canvas.drawPath(iconPath, strokePaint)
        strokePaint.strokeWidth = dp(2)
        canvas.drawLine(-dp(14), dp(18), dp(14), dp(18), strokePaint)
        canvas.drawCircle(-dp(8), dp(18), dp(2.2f), strokePaint)
        canvas.drawCircle(dp(8), dp(18), dp(2.2f), strokePaint)
        strokePaint.strokeWidth = dp(1.2f)
    }
    private fun drawOwnship(canvas: Canvas, viewport: Viewport, location: Location) {
        val point = latLonToWorld(location.latitude, location.longitude, viewport.zoom)
        val x = (point.x - viewport.centerX + viewport.width / 2.0).toFloat()
        val y = (point.y - viewport.centerY + viewport.height / 2.0).toFloat()
        if (x < -dp(80) || x > viewport.width + dp(80) || y < -dp(80) || y > viewport.height + dp(80)) return
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(58, 54, 180, 196)
        canvas.drawCircle(x, y, dp(28), paint)
        paint.color = Color.rgb(39, 48, 53)
        canvas.drawCircle(x, y, dp(20), paint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = Color.argb(210, 234, 242, 234)
        canvas.drawCircle(x, y, dp(20), strokePaint)

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(38f)
        paint.color = Color.rgb(236, 244, 237)
        iconPath.reset()
        iconPath.moveTo(0f, -dp(12))
        iconPath.lineTo(dp(8), dp(12))
        iconPath.lineTo(0f, dp(7))
        iconPath.lineTo(-dp(8), dp(12))
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        canvas.restore()
        drawSmallPill(canvas, x - dp(35), y + dp(30), dp(70), dp(22), "YOU", Color.rgb(39, 48, 53), Color.rgb(236, 244, 237))
    }

    private fun drawNoLocationState(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = MAP_EMPTY
        canvas.drawRect(0f, 0f, w, h, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(18)
        textPaint.color = TEXT
        val message = if (locationPermissionGranted) "Waiting for device location" else "Location permission required"
        canvas.drawText(message, w / 2f, h * 0.45f, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = MUTED
        canvas.drawText("No map or aircraft will be shown until real location data is available.", w / 2f, h * 0.45f + dp(24), textPaint)
    }

    private fun drawTopStatus(canvas: Canvas, w: Float, h: Float) {
        val rect = topStatusBounds(w, h)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, Color.red(PANEL), Color.green(PANEL), Color.blue(PANEL))
        canvas.drawRoundRect(rect, dp(8), dp(8), paint)
        strokePaint.strokeWidth = dp(1)
        strokePaint.color = PANEL_STROKE
        canvas.drawRoundRect(rect, dp(8), dp(8), strokePaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(19)
        textPaint.color = TEXT
        canvas.drawText("Flight Alert", rect.left + dp(16), rect.top + dp(27), textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = MUTED
        canvas.drawText(topSubtitle(), rect.left + dp(16), rect.top + dp(49), textPaint)

        val status = topTrafficStatus()
        drawStatusLabel(canvas, rect.right - dp(132), rect.top + dp(14), dp(116), dp(26), status.first, status.second)
        drawStatusLabel(canvas, rect.right - dp(132), rect.top + dp(45), dp(116), dp(17), String.format(Locale.US, "Z%.1f", zoom), ACCENT_YELLOW)
    }

    private fun topStatusBounds(w: Float, h: Float): RectF {
        val left = dp(12)
        val top = dp(12)
        val right = if (isWideLayout(w, h)) min(infoPanelBounds(w, h).left - dp(12), left + dp(620)) else w - dp(12)
        return RectF(left, top, right, top + dp(66))
    }

    private fun topSubtitle(): String {
        val location = latestLocation
        return if (location == null) {
            mapStatus
        } else if (!followingLocation) {
            val accuracy = if (location.hasAccuracy() && location.accuracy > 1f) " +/-${formatAccuracy(location.accuracy.toDouble())}" else ""
            "Map moved from your position$accuracy"
        } else {
            val accuracy = if (location.hasAccuracy() && location.accuracy > 1f) " +/-${formatAccuracy(location.accuracy.toDouble())}" else ""
            "Live map and ADS-B$accuracy"
        }
    }

    private fun topTrafficStatus(): Pair<String, Int> {
        val nearest = nearestAircraft()
        return when {
            nearest == null && aircraftStatus.startsWith("No aircraft reported") -> "NO TRAFFIC" to MUTED
            nearest == null -> "NO DATA" to MUTED
            displayDistanceMeters(nearest) <= feetToMeters(alertDistanceFeet.toDouble()) -> "TRAFFIC ALERT" to RED
            else -> "TRAFFIC LIVE" to ACCENT_GREEN
        }
    }

    private fun drawStatusLabel(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, label: String, color: Int) {
        val rect = RectF(x, y, x + width, y + height)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(34, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = if (height > dp(20)) sp(10) else sp(9)
        textPaint.color = color
        val metrics = textPaint.fontMetrics
        canvas.drawText(label, rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
        textPaint.isFakeBoldText = false
    }

    private fun drawRecenterButton(canvas: Canvas, w: Float, h: Float) {
        if (followingLocation || latestLocation == null) return
        val rect = recenterButtonBounds(w, h)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(235, 34, 48, 52)
        canvas.drawRoundRect(rect, dp(8), dp(8), paint)
        strokePaint.color = ACCENT_GREEN
        strokePaint.strokeWidth = dp(1.4f)
        canvas.drawRoundRect(rect, dp(8), dp(8), strokePaint)
        drawLocateIcon(canvas, rect.centerX(), rect.centerY(), ACCENT_GREEN)
    }

    private fun drawFlightPathButtons(canvas: Canvas, viewport: Viewport, w: Float, h: Float) {
        if (shouldShowPathButton(viewport)) {
            drawFlightPathButton(canvas, flightPathButtonBounds(w, h), "Path", ACCENT_YELLOW)
        }
        if (shouldShowClearPathButton()) {
            drawFlightPathButton(canvas, clearFlightPathButtonBounds(w, h), "Clear", RED)
        }
    }

    private fun drawFlightPathButton(canvas: Canvas, rect: RectF, label: String, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(235, 34, 48, 52)
        canvas.drawRoundRect(rect, dp(8), dp(8), paint)
        strokePaint.color = color
        strokePaint.strokeWidth = dp(1.4f)
        canvas.drawRoundRect(rect, dp(8), dp(8), strokePaint)
        if (label == "Clear") {
            drawClearIcon(canvas, rect.centerX(), rect.centerY(), color)
        } else {
            drawPathFitIcon(canvas, rect.centerX(), rect.centerY(), color)
        }
    }

    private fun actionAnchorButtonBounds(w: Float, h: Float): RectF = contextButtonBounds(w, h, 0)

    private fun recenterButtonBounds(w: Float, h: Float): RectF = contextButtonBounds(w, h, 0)

    private fun flightPathButtonBounds(w: Float, h: Float): RectF {
        val slot = if (!followingLocation && latestLocation != null) 1 else 0
        return contextButtonBounds(w, h, slot)
    }

    private fun clearFlightPathButtonBounds(w: Float, h: Float): RectF {
        val viewport = latestLocation?.let { viewportFor(it, w, h) }
        var slot = 0
        if (!followingLocation && latestLocation != null) slot++
        if (viewport != null && shouldShowPathButton(viewport)) slot++
        return contextButtonBounds(w, h, slot)
    }

    private fun contextButtonBounds(w: Float, h: Float, slot: Int): RectF {
        val settings = settingsButtonBounds(w, h)
        val size = dp(44)
        val gap = dp(10)
        val left = settings.right + gap + slot * (size + gap)
        return if (left + size <= w - dp(12)) {
            RectF(left, settings.top, left + size, settings.top + size)
        } else {
            val stackedLeft = settings.left + slot * (size + gap)
            val top = settings.top - size - gap
            RectF(stackedLeft, top, stackedLeft + size, top + size)
        }
    }
    private fun drawSettingsButton(canvas: Canvas, w: Float, h: Float) {
        val bounds = settingsButtonBounds(w, h)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(228, 43, 50, 54)
        canvas.drawRoundRect(bounds, dp(8), dp(8), paint)
        strokePaint.strokeWidth = dp(1)
        strokePaint.color = Color.argb(155, 230, 235, 225)
        canvas.drawRoundRect(bounds, dp(8), dp(8), strokePaint)
        drawGearIcon(canvas, bounds.centerX(), bounds.centerY() - dp(4), ACCENT_BLUE)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(8)
        textPaint.color = TEXT
        canvas.drawText("Settings", bounds.centerX(), bounds.bottom - dp(6), textPaint)
    }

    private fun settingsButtonBounds(w: Float, h: Float): RectF {
        val info = infoPanelBounds(w, h)
        val width = dp(84)
        val height = dp(44)
        val x = dp(12)
        val y = if (isWideLayout(w, h)) h - height - dp(14) else info.top - height - dp(12)
        return RectF(x, y, x + width, y + height)
    }

    private fun drawTrafficPanel(canvas: Canvas, w: Float, h: Float) {
        val rect = infoPanelBounds(w, h)
        val wide = isWideLayout(w, h)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(236, Color.red(PANEL), Color.green(PANEL), Color.blue(PANEL))
        canvas.drawRoundRect(rect, dp(8), dp(8), paint)
        strokePaint.color = PANEL_STROKE
        strokePaint.strokeWidth = dp(1)
        canvas.drawRoundRect(rect, dp(8), dp(8), strokePaint)

        val display = displayedTraffic()
        val target = display.aircraft
        var y = rect.top + if (wide) dp(32) else dp(27)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(13)
        textPaint.color = when {
            target == null -> MUTED
            display.selected -> ACCENT_BLUE
            else -> RED
        }
        val title = when {
            target == null -> "AIRCRAFT FEED"
            display.selected -> "SELECTED TRAFFIC"
            else -> "NEAREST TRAFFIC"
        }
        canvas.drawText(title, rect.left + dp(16), y, textPaint)

        if (target == null) {
            drawNoAircraftPanel(canvas, rect, y + if (wide) dp(60) else dp(38))
            return
        }

        y += if (wide) dp(44) else dp(32)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = if (wide) sp(29) else sp(24)
        textPaint.color = TEXT
        canvas.drawText(target.callsign, rect.left + dp(16), y, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = if (wide) sp(29) else sp(24)
        textPaint.color = trafficDistanceColor(target)
        canvas.drawText(formatDistance(displayDistanceMeters(target)), rect.right - dp(16), y, textPaint)

        if (wide) {
            y += dp(38)
            y = drawTrafficDetailRow(canvas, rect, y, "Altitude", formatAltitudeValue(target.altitudeM))
            y = drawTrafficDetailRow(canvas, rect, y, "Speed", formatSpeedValue(target.velocityMs))
            y = drawTrafficDetailRow(canvas, rect, y, "Track", formatTrack(target.trackDeg))
            y = drawTrafficDetailRow(canvas, rect, y, "Vertical rate", formatVerticalRate(target.verticalRateMs))
            y = drawTrafficDetailRow(canvas, rect, y, "Last contact", formatAge(target))
            y = drawTrafficDetailRow(canvas, rect, y, "Registration", target.registration ?: "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Type", target.typeCode ?: "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Military", if (target.isMilitary) "Tagged military" else "Not tagged")
            y = drawTrafficDetailRow(canvas, rect, y, "Military base origin", "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "ICAO", target.icao24.uppercase(Locale.US))
            drawTrafficDetailRow(canvas, rect, y, "Position", formatPosition(target))
        } else {
            y += dp(28)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = false
            textPaint.textSize = sp(13)
            textPaint.color = MUTED
            canvas.drawText(formatAltitudeValue(target.altitudeM), rect.left + dp(16), y, textPaint)
            canvas.drawText(formatSpeedValue(target.velocityMs), rect.left + rect.width() * 0.34f, y, textPaint)
            canvas.drawText(formatAge(target), rect.left + rect.width() * 0.60f, y, textPaint)

            y += dp(24)
            canvas.drawText(formatTrack(target.trackDeg), rect.left + dp(16), y, textPaint)
            canvas.drawText(formatVerticalRate(target.verticalRateMs), rect.left + rect.width() * 0.46f, y, textPaint)
            if (target.isMilitary) {
                y += dp(22)
                textPaint.isFakeBoldText = true
                textPaint.color = MILITARY_GRAY
                canvas.drawText("Tagged military", rect.left + dp(16), y, textPaint)
                textPaint.isFakeBoldText = false
            }
        }

    }

    private fun drawTrafficDetailRow(canvas: Canvas, rect: RectF, y: Float, label: String, value: String): Float {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(10)
        textPaint.color = MUTED
        canvas.drawText(label.uppercase(Locale.US), rect.left + dp(16), y, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(13)
        textPaint.color = TEXT
        drawFittedRightText(canvas, value, rect.right - dp(16), y, rect.width() * 0.56f, sp(13), sp(9))
        strokePaint.color = Color.argb(74, Color.red(PANEL_STROKE), Color.green(PANEL_STROKE), Color.blue(PANEL_STROKE))
        strokePaint.strokeWidth = dp(1)
        canvas.drawLine(rect.left + dp(16), y + dp(10), rect.right - dp(16), y + dp(10), strokePaint)
        textPaint.isFakeBoldText = false
        return y + dp(28)
    }

    private fun drawFittedRightText(canvas: Canvas, value: String, right: Float, y: Float, maxWidth: Float, startSize: Float, minSize: Float) {
        textPaint.textSize = startSize
        while (textPaint.textSize > minSize && textPaint.measureText(value) > maxWidth) {
            textPaint.textSize -= dp(0.5f)
        }
        val display = if (textPaint.measureText(value) <= maxWidth) value else ellipsize(value, maxWidth)
        canvas.drawText(display, right, y, textPaint)
    }

    private fun ellipsize(value: String, maxWidth: Float): String {
        if (value.length <= 3) return value
        var end = value.length
        while (end > 3 && textPaint.measureText(value.substring(0, end) + "...") > maxWidth) {
            end--
        }
        return value.substring(0, end) + "..."
    }

    private fun drawAircraftDetailsPanel(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 3, 8, 9)
        canvas.drawRect(0f, 0f, w, h, paint)

        val rect = detailsPanelBounds(w, h)
        paint.color = Color.rgb(16, 32, 29)
        canvas.drawRoundRect(rect, dp(8), dp(8), paint)
        strokePaint.strokeWidth = dp(1)
        strokePaint.color = PANEL_STROKE
        canvas.drawRoundRect(rect, dp(8), dp(8), strokePaint)
        drawChoiceButton(canvas, detailsCloseButtonBounds(rect), "Close", false)

        val aircraft = displayedTraffic().aircraft
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(22)
        textPaint.color = TEXT
        canvas.drawText(aircraft?.callsign ?: "Aircraft details", rect.left + dp(18), rect.top + dp(38), textPaint)

        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = MUTED
        canvas.drawText(aircraftDetailsStatus, rect.left + dp(18), rect.top + dp(60), textPaint)

        val photoBottom = rect.top + if (isWideLayout(w, h)) dp(204) else dp(236)
        val photoRect = RectF(rect.left + dp(18), rect.top + dp(78), rect.right - dp(18), photoBottom)
        paint.color = Color.rgb(22, 42, 38)
        canvas.drawRoundRect(photoRect, dp(6), dp(6), paint)
        val photo = aircraftPhoto
        if (photo != null) {
            val src = Rect(0, 0, photo.width, photo.height)
            canvas.drawBitmap(photo, src, aspectFitRect(photo.width, photo.height, photoRect), paint)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = sp(12)
            textPaint.color = MUTED
            canvas.drawText(aircraftPhotoStatus, photoRect.centerX(), photoRect.centerY(), textPaint)
        }

        val details = aircraftDetails
        var y = photoRect.bottom + dp(30)
        if (aircraft != null) {
            y = drawTrafficDetailRow(canvas, rect, y, "ICAO", aircraft.icao24.uppercase(Locale.US))
            y = drawTrafficDetailRow(canvas, rect, y, "Registration", details?.registration ?: aircraft.registration ?: "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Owner", details?.owner ?: "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Aircraft", formatAircraftType(details, aircraft))
            y = drawTrafficDetailRow(canvas, rect, y, "MFR year", details?.manufacturedYear ?: "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Registry source", details?.registrySource ?: "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Military", if (aircraft.isMilitary) "Tagged military" else "Not tagged")
            y = drawTrafficDetailRow(canvas, rect, y, "Military base origin", "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Route", details?.route ?: "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Destination", formatAirport(details?.destinationAirport))
            y = drawTrafficDetailRow(canvas, rect, y, "Flight time", "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Flightpath complete", "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Observed path span", formatObservedPathSpan(aircraft))
            drawTrafficDetailRow(canvas, rect, y, "Path conflict filter", formatPathConflictStatus())
        }
    }

    private fun aspectFitRect(sourceWidth: Int, sourceHeight: Int, outer: RectF): RectF {
        if (sourceWidth <= 0 || sourceHeight <= 0 || outer.width() <= 0f || outer.height() <= 0f) return RectF(outer)
        val sourceRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
        val outerRatio = outer.width() / outer.height()
        return if (sourceRatio > outerRatio) {
            val fittedHeight = outer.width() / sourceRatio
            val top = outer.centerY() - fittedHeight / 2f
            RectF(outer.left, top, outer.right, top + fittedHeight)
        } else {
            val fittedWidth = outer.height() * sourceRatio
            val left = outer.centerX() - fittedWidth / 2f
            RectF(left, outer.top, left + fittedWidth, outer.bottom)
        }
    }

    private fun detailsPanelBounds(w: Float, h: Float): RectF {
        val margin = dp(14)
        val width = if (isWideLayout(w, h)) min(dp(520), w - margin * 2f) else w - margin * 2f
        val height = min(h - margin * 2f, dp(720))
        return RectF((w - width) / 2f, (h - height) / 2f, (w + width) / 2f, (h + height) / 2f)
    }

    private fun detailsCloseButtonBounds(panel: RectF): RectF {
        return RectF(panel.right - dp(112), panel.top + dp(14), panel.right - dp(18), panel.top + dp(48))
    }

    private fun formatAircraftType(details: AircraftDetails?, aircraft: Aircraft): String {
        return listOfNotNull(details?.manufacturer, details?.type, details?.typeCode ?: aircraft.typeCode)
            .distinct()
            .joinToString(" ")
            .ifEmpty { "Unavailable" }
    }

    private fun formatAirport(airport: com.flightalert.data.AirportDetails?): String {
        return if (airport == null) {
            "Unavailable"
        } else {
            listOfNotNull(airport.name, airport.icao, airport.iata).joinToString(" / ")
        }
    }

    private fun formatObservedPathSpan(aircraft: Aircraft): String {
        val id = aircraft.icao24.lowercase(Locale.US)
        if (selectedFlightPathAircraftId != id) return "Unavailable"
        val path = selectedFlightPath?.takeIf { it.size >= 2 } ?: return "Unavailable"
        val start = path.minOf { it.epochSec }
        val end = path.maxOf { it.epochSec }
        val minutes = ((end - start) / 60.0).coerceAtLeast(0.0)
        return String.format(Locale.US, "%.0f min", minutes)
    }

    private fun formatPathConflictStatus(): String {
        return when (pathConflictStatus) {
            "Verified" -> "${pathConflictMatches.size} matches within ${formatFeetSetting(pathNearbyFeet)} / ${PATH_CONFLICT_TIME_WINDOW_SEC}s"
            "Loading" -> "Loading verified path data"
            "Partial" -> "Partial path data; not filtering"
            else -> "Unavailable; not filtering"
        }
    }

    private fun drawNoAircraftPanel(canvas: Canvas, rect: RectF, y: Float) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(20)
        textPaint.color = TEXT
        val headline = if (aircraftStatus.startsWith("No aircraft reported")) "No reported aircraft" else "No aircraft data"
        canvas.drawText(headline, rect.left + dp(16), y, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = MUTED
        canvas.drawText(aircraftStatus, rect.left + dp(16), y + dp(24), textPaint)
        lastAircraftDataEpochSec?.let {
            canvas.drawText("Data time ${it.toLong()}", rect.left + dp(16), y + dp(44), textPaint)
        }
    }

    private fun drawSettingsPanel(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(155, 3, 8, 9)
        canvas.drawRect(0f, 0f, w, h, paint)

        val rect = settingsPanelBounds(w, h)
        paint.color = Color.rgb(18, 31, 29)
        canvas.drawRoundRect(rect, dp(8), dp(8), paint)
        strokePaint.strokeWidth = dp(1)
        strokePaint.color = PANEL_STROKE
        canvas.drawRoundRect(rect, dp(8), dp(8), strokePaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(20)
        textPaint.color = TEXT
        canvas.drawText("Settings", rect.left + dp(18), rect.top + dp(34), textPaint)
        drawChoiceButton(canvas, closeButtonBounds(rect), "Close", false)

        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = MUTED
        canvas.drawText("Units", rect.left + dp(18), rect.top + dp(74), textPaint)

        drawChoiceButton(canvas, imperialButtonBounds(rect), "Miles / feet", units == UnitSystem.IMPERIAL)
        drawChoiceButton(canvas, metricButtonBounds(rect), "Kilometers / meters", units == UnitSystem.METRIC)

        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = MUTED
        canvas.drawText("Map", rect.left + dp(18), rect.top + dp(184), textPaint)
        drawChoiceButton(canvas, mapSourceButtonBounds(rect), if (mapSource == TileSource.SATELLITE) "Satellite imagery" else "Street map", mapSource == TileSource.SATELLITE)

        drawAdjusterRow(canvas, rect, rect.top + dp(258), "Path nearby filter", formatFeetSetting(pathNearbyFeet), pathFilterMinusBounds(rect), pathFilterPlusBounds(rect))
        drawChoiceButton(canvas, alertsToggleBounds(rect), if (alertsEnabled) "Hazard alerts on" else "Hazard alerts off", alertsEnabled)
        drawAdjusterRow(canvas, rect, rect.top + dp(388), "Alert distance", formatFeetSetting(alertDistanceFeet), alertDistanceMinusBounds(rect), alertDistancePlusBounds(rect))
        drawAdjusterRow(canvas, rect, rect.top + dp(478), "Alert vertical range", formatFeetSetting(alertAltitudeFeet), alertAltitudeMinusBounds(rect), alertAltitudePlusBounds(rect))

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = sp(11)
        textPaint.color = MUTED
        canvas.drawText("Map: ${mapSource.attribution}", rect.left + dp(18), rect.bottom - dp(38), textPaint)
        canvas.drawText("Aircraft and paths: live feed sources", rect.left + dp(18), rect.bottom - dp(18), textPaint)
    }
    private fun drawAdjusterRow(canvas: Canvas, panel: RectF, y: Float, label: String, value: String, minus: RectF, plus: RectF) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = MUTED
        canvas.drawText(label, panel.left + dp(18), y, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(13)
        textPaint.color = TEXT
        canvas.drawText(value, panel.centerX(), y + dp(32), textPaint)
        drawChoiceButton(canvas, minus, "-", false)
        drawChoiceButton(canvas, plus, "+", false)
    }

    private fun drawChoiceButton(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
        val color = if (selected) ACCENT_GREEN else Color.rgb(59, 68, 70)
        paint.style = Paint.Style.FILL
        paint.color = if (selected) Color.argb(55, Color.red(color), Color.green(color), Color.blue(color)) else Color.rgb(38, 47, 49)
        canvas.drawRoundRect(rect, dp(7), dp(7), paint)
        strokePaint.strokeWidth = dp(1.3f)
        strokePaint.color = color
        canvas.drawRoundRect(rect, dp(7), dp(7), strokePaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(12)
        textPaint.color = if (selected) ACCENT_GREEN else TEXT
        val metrics = textPaint.fontMetrics
        canvas.drawText(label, rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
        textPaint.isFakeBoldText = false
    }

    private fun handleTap(x: Float, y: Float) {
        if (detailsOpen) {
            if (detailsCloseButtonBounds(detailsPanelBounds(contentWidth(), contentHeight())).contains(x, y)) {
                detailsOpen = false
            }
            invalidate()
            return
        }

        if (settingsOpen) {
            val panel = settingsPanelBounds(contentWidth(), contentHeight())
            when {
                closeButtonBounds(panel).contains(x, y) -> settingsOpen = false
                imperialButtonBounds(panel).contains(x, y) -> setUnits(UnitSystem.IMPERIAL)
                metricButtonBounds(panel).contains(x, y) -> setUnits(UnitSystem.METRIC)
                mapSourceButtonBounds(panel).contains(x, y) -> toggleMapSource()
                pathFilterMinusBounds(panel).contains(x, y) -> setPathNearbyFeet(pathNearbyFeet - 1000f)
                pathFilterPlusBounds(panel).contains(x, y) -> setPathNearbyFeet(pathNearbyFeet + 1000f)
                alertsToggleBounds(panel).contains(x, y) -> setAlertsEnabled(!alertsEnabled)
                alertDistanceMinusBounds(panel).contains(x, y) -> setAlertDistanceFeet(alertDistanceFeet - 1000f)
                alertDistancePlusBounds(panel).contains(x, y) -> setAlertDistanceFeet(alertDistanceFeet + 1000f)
                alertAltitudeMinusBounds(panel).contains(x, y) -> setAlertAltitudeFeet(alertAltitudeFeet - 500f)
                alertAltitudePlusBounds(panel).contains(x, y) -> setAlertAltitudeFeet(alertAltitudeFeet + 500f)
            }
            invalidate()
            return
        }

        val w = contentWidth()
        val h = contentHeight()
        val viewport = latestLocation?.let { viewportFor(it, w, h) }
        when {
            recenterButtonBounds(w, h).contains(x, y) && !followingLocation -> recenterOnLocation()
            clearFlightPathButtonBounds(w, h).contains(x, y) && shouldShowClearPathButton() -> clearSelectedFlightPath()
            viewport != null && flightPathButtonBounds(w, h).contains(x, y) && shouldShowPathButton(viewport) -> showSelectedFlightPath()
            settingsButtonBounds(w, h).contains(x, y) -> settingsOpen = true
            infoPanelBounds(w, h).contains(x, y) -> displayedTraffic().aircraft?.let { openAircraftDetails(it) }
            !isOverlayOrControlHit(x, y) -> selectAircraftAt(x, y)
        }
        invalidate()
    }

    private fun recenterOnLocation() {
        followingLocation = true
        manualCenterLat = null
        manualCenterLon = null
        selectedAircraftId = null
        clearSelectedFlightPath()
        selectedPathFitRequested = false
        requestVisibleAircraftIfNeeded(force = true)
    }

    private fun selectAircraftAt(x: Float, y: Float) {
        val location = latestLocation ?: return
        val viewport = viewportFor(location, contentWidth(), contentHeight())
        val radius = dp(AIRCRAFT_TAP_RADIUS_DP)
        val radiusSquared = radius * radius
        val hit = visibleAircraftSnapshot()
            .mapNotNull { item ->
                val estimated = estimatedAircraftPosition(item)
                val point = latLonToWorld(estimated.lat, estimated.lon, viewport.zoom)
                val sx = (point.x - viewport.centerX + viewport.width / 2.0).toFloat()
                val sy = (point.y - viewport.centerY + viewport.height / 2.0).toFloat()
                val dx = sx - x
                val dy = sy - y
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared <= radiusSquared) AircraftHit(item, distanceSquared) else null
            }
            .minByOrNull { it.distanceSquared }
            ?.aircraft
        if (hit != null) {
            selectedAircraftId = hit.icao24
            selectedFlightPathAircraftId = null
            selectedFlightPath = null
            selectedFlightPathVisible = false
            selectedPathFitRequested = false
            resetPathConflictFilter()
            requestFlightPath(hit.icao24)
        }
    }

    private fun openAircraftDetails(aircraft: Aircraft) {
        detailsOpen = true
        aircraftDetails = null
        aircraftPhoto = null
        aircraftDetailsStatus = "Loading live aircraft details"
        aircraftPhotoStatus = "Loading exact-aircraft photo"
        requestAircraftDetails(aircraft)
    }

    private fun requestAircraftDetails(aircraft: Aircraft) {
        if (detailsRequestInFlight) return
        detailsRequestInFlight = true
        val requestedId = aircraft.icao24
        executor.execute {
            val details = aircraftDetailsClient.fetchDetails(aircraft.icao24, aircraft.callsign, aircraft.registration)
            post {
                if (displayedTraffic().aircraft?.icao24 == requestedId) {
                    aircraftDetails = details
                    aircraftDetailsStatus = if (details.owner == null && details.route == null && details.registration == null) {
                        "Metadata unavailable from configured APIs"
                    } else {
                        "Metadata from ${details.registrySource ?: "configured APIs"}"
                    }
                    aircraftPhotoStatus = "Exact photo unavailable; trying representative make/model photo"
                    invalidate()
                }
            }
            val photo = fetchAircraftPhoto(aircraft, details)
            post {
                detailsRequestInFlight = false
                if (displayedTraffic().aircraft?.icao24 != requestedId) return@post
                when (photo) {
                    is AircraftPhotoResult.Found -> {
                        aircraftPhoto = photo.bitmap
                        aircraftPhotoStatus = photo.note
                    }
                    is AircraftPhotoResult.Unavailable -> {
                        aircraftPhoto = null
                        aircraftPhotoStatus = photo.reason
                    }
                }
                invalidate()
            }
        }
    }

    private fun fetchAircraftPhoto(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult {
        val exactSources = listOfNotNull(
            "https://api.planespotters.net/pub/photos/hex/${aircraft.icao24.trim()}",
            details.registration?.let { "https://api.planespotters.net/pub/photos/reg/${it.trim()}" }
        )
        exactSources.forEach { apiUrl ->
            fetchPlanespottersPhotoUrl(apiUrl)?.let { imageUrl ->
                fetchBitmap(imageUrl)?.let { return AircraftPhotoResult.Found(it, "Exact aircraft photo from PlaneSpotters") }
            }
        }
        fetchBitmap("https://hexdb.io/hex-image-thumb?hex=${aircraft.icao24.trim()}")?.let {
            return AircraftPhotoResult.Found(it, "Exact aircraft photo from HexDB")
        }
        return fetchRepresentativePhoto(details)
            ?: AircraftPhotoResult.Unavailable("Exact and representative photos unavailable from configured APIs")
    }

    private fun fetchPlanespottersPhotoUrl(apiUrl: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            findFirstImageUrl(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchRepresentativePhoto(details: AircraftDetails): AircraftPhotoResult.Found? {
        val model = representativeModelName(details) ?: return null
        val queries = representativePhotoQueries(details, model)
        val url = queries.firstNotNullOfOrNull { query ->
            val encoded = URLEncoder.encode(query, "UTF-8")
            val apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch=$encoded&prop=imageinfo&iiprop=url|mime"
            fetchWikimediaImageUrl(apiUrl)
        } ?: return null
        val bitmap = fetchBitmap(url) ?: return null
        return AircraftPhotoResult.Found(bitmap, "Representative $model photo from Wikimedia Commons; not this exact aircraft")
    }

    private fun representativeModelName(details: AircraftDetails): String? {
        val code = details.typeCode?.uppercase(Locale.US)?.trim()
        val knownModel = when (code) {
            "A19N" -> "Airbus A319neo"
            "A20N" -> "Airbus A320neo"
            "A21N" -> "Airbus A321neo"
            "A319" -> "Airbus A319"
            "A320" -> "Airbus A320"
            "A321" -> "Airbus A321"
            "B37M" -> "Boeing 737 MAX 7"
            "B38M" -> "Boeing 737 MAX 8"
            "B39M" -> "Boeing 737 MAX 9"
            "B3XM" -> "Boeing 737 MAX 10"
            "B737" -> "Boeing 737"
            "B738" -> "Boeing 737-800"
            "B739" -> "Boeing 737-900"
            "B752" -> "Boeing 757-200"
            "B763" -> "Boeing 767-300"
            "B772" -> "Boeing 777-200"
            "B77W" -> "Boeing 777-300ER"
            "B788" -> "Boeing 787-8"
            "B789" -> "Boeing 787-9"
            "BCS1" -> "Airbus A220-100"
            "BCS3" -> "Airbus A220-300"
            "C172" -> "Cessna 172"
            "C182" -> "Cessna 182"
            "C208" -> "Cessna 208 Caravan"
            "CRJ7" -> "Bombardier CRJ700"
            "CRJ9" -> "Bombardier CRJ900"
            "E170" -> "Embraer 170"
            "E75L", "E75S" -> "Embraer 175"
            "E190" -> "Embraer 190"
            "E195" -> "Embraer 195"
            "PA28" -> "Piper PA-28 Cherokee"
            "SR20" -> "Cirrus SR20"
            "SR22" -> "Cirrus SR22"
            else -> null
        }
        if (knownModel != null) return knownModel
        return listOfNotNull(details.manufacturer, details.type ?: details.typeCode)
            .joinToString(" ")
            .trim()
            .ifEmpty { null }
    }

    private fun representativePhotoQueries(details: AircraftDetails, model: String): List<String> {
        return listOfNotNull(
            "\"$model\" aircraft",
            "$model aircraft",
            "\"$model\" airliner",
            details.type?.let { "\"$it\" aircraft" },
            details.typeCode?.let { "${details.manufacturer.orEmpty()} $it aircraft".trim() },
            details.typeCode?.let { "$it aircraft" }
        ).filter { it.isNotBlank() }.distinct()
    }

    private fun fetchWikimediaImageUrl(apiUrl: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            val pages = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONObject("query")
                ?.optJSONObject("pages")
                ?: return null
            val keys = pages.keys()
            while (keys.hasNext()) {
                val info = pages.optJSONObject(keys.next())?.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val mime = info.optString("mime")
                val url = info.optString("url")
                if (mime.startsWith("image/") && IMAGE_URL_PATTERN.containsMatchIn(url)) return url
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchBitmap(url: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            BitmapFactory.decodeStream(connection.inputStream)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun findFirstImageUrl(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    findFirstImageUrl(value.opt(keys.next()))?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findFirstImageUrl(value.opt(index))?.let { return it }
                }
                null
            }
            is String -> value.takeIf { it.startsWith("http", ignoreCase = true) && IMAGE_URL_PATTERN.containsMatchIn(it) }
            else -> null
        }
    }

    private fun showSelectedFlightPath() {
        selectedFlightPathVisible = true
        fitSelectedFlightPath()
        invalidate()
    }

    private fun clearSelectedFlightPath() {
        selectedFlightPathAircraftId = null
        selectedFlightPath = null
        selectedFlightPathVisible = false
        selectedPathFitRequested = false
        resetPathConflictFilter()
    }

    private fun resetPathConflictFilter() {
        pathConflictSelectedId = null
        pathConflictTracks.clear()
        pathConflictRequests.clear()
        pathConflictMatches = emptySet()
        pathConflictStatus = "Unavailable"
    }

    private fun fitSelectedFlightPath() {
        val bounds = selectedPathBounds() ?: return
        val w = contentWidth()
        val h = contentHeight()
        val usable = largestUnblockedMapRect(w, h).insetBy(dp(12))
        if (usable.width() <= dp(80) || usable.height() <= dp(80)) return

        val topLeft = latLonToWorld(bounds.maxLat, bounds.minLon, 0.0)
        val bottomRight = latLonToWorld(bounds.minLat, bounds.maxLon, 0.0)
        val pathWidthAtZoomZero = max(1.0, abs(bottomRight.x - topLeft.x))
        val pathHeightAtZoomZero = max(1.0, abs(bottomRight.y - topLeft.y))
        val widthFit = usable.width() / (pathWidthAtZoomZero * PATH_FIT_CONTEXT_MULTIPLIER)
        val heightFit = usable.height() / (pathHeightAtZoomZero * PATH_FIT_CONTEXT_MULTIPLIER)

        // Path mode should show the trip in context, not just cram the polyline against the panels.
        zoom = (ln(min(widthFit, heightFit)) / ln(2.0)).coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())
        prefs.edit().putFloat(FlightAlertSettings.KEY_ZOOM, zoom.toFloat()).apply()
        val centerLat = (bounds.minLat + bounds.maxLat) / 2.0
        val centerLon = normalizeLongitude((bounds.minLon + bounds.maxLon) / 2.0)
        val centerWorld = latLonToWorld(centerLat, centerLon, zoom)
        val focus = ScreenPoint(usable.centerX(), usable.centerY())
        val adjustedCenter = worldToLatLon(
            centerWorld.x + w / 2.0 - focus.x,
            centerWorld.y + h / 2.0 - focus.y,
            zoom
        )
        followingLocation = false
        manualCenterLat = adjustedCenter.lat
        manualCenterLon = adjustedCenter.lon
        selectedPathFitRequested = true
        requestVisibleAircraftIfNeeded(force = true)
    }

    private fun shouldShowPathButton(viewport: Viewport): Boolean {
        if (!hasSelectedFlightPath()) return false
        if (!selectedFlightPathVisible) return true
        val bounds = selectedPathBounds() ?: return false
        return !isPathBoundsVisible(viewport, bounds)
    }

    private fun shouldShowClearPathButton(): Boolean {
        return selectedFlightPathVisible && hasSelectedFlightPath()
    }

    private fun hasSelectedFlightPath(): Boolean {
        val id = selectedAircraftId?.lowercase(Locale.US) ?: return false
        return selectedFlightPathAircraftId == id && (selectedFlightPath?.size ?: 0) >= 2
    }

    private fun isPathBoundsVisible(viewport: Viewport, bounds: Bounds): Boolean {
        val usable = largestUnblockedMapRect(viewport.width, viewport.height).insetBy(dp(14))
        if (usable.width() <= 0f || usable.height() <= 0f) return false
        val corners = listOf(
            GeoPoint(bounds.minLat, bounds.minLon),
            GeoPoint(bounds.minLat, bounds.maxLon),
            GeoPoint(bounds.maxLat, bounds.minLon),
            GeoPoint(bounds.maxLat, bounds.maxLon)
        )
        return corners.all { point ->
            val world = latLonToWorld(point.lat, point.lon, viewport.zoom)
            val sx = (world.x - viewport.centerX + viewport.width / 2.0).toFloat()
            val sy = (world.y - viewport.centerY + viewport.height / 2.0).toFloat()
            usable.contains(sx, sy)
        }
    }

    private fun setManualCenterFromWorld(centerX: Double, centerY: Double) {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val wrappedX = ((centerX % scale) + scale) % scale
        val clampedY = centerY.coerceIn(0.0, scale)
        val center = worldToLatLon(wrappedX, clampedY, zoom)
        manualCenterLat = center.lat
        manualCenterLon = center.lon
        followingLocation = false
    }

    private fun isOverlayOrControlHit(x: Float, y: Float): Boolean {
        if (settingsOpen || detailsOpen) return true
        val w = contentWidth()
        val h = contentHeight()
        return (!followingLocation && recenterButtonBounds(w, h).contains(x, y)) ||
            (shouldShowPathButton(viewportFor(latestLocation ?: return true, w, h)) && flightPathButtonBounds(w, h).contains(x, y)) ||
            (shouldShowClearPathButton() && clearFlightPathButtonBounds(w, h).contains(x, y)) ||
            settingsButtonBounds(w, h).contains(x, y) ||
            infoPanelBounds(w, h).contains(x, y) ||
            topStatusBounds(w, h).contains(x, y)
    }

    private fun setUnits(next: UnitSystem) {
        units = next
        prefs.edit().putString(FlightAlertSettings.KEY_UNITS, units.name).apply()
    }


    private fun toggleMapSource() {
        mapSource = if (mapSource == TileSource.STREET) TileSource.SATELLITE else TileSource.STREET
        synchronized(tileCache) { tileCache.clear() }
        synchronized(requestedTiles) { requestedTiles.clear() }
        prefs.edit().putString(FlightAlertSettings.KEY_MAP_SOURCE, mapSource.name).apply()
        mapStatus = "Loading ${mapSource.displayName.lowercase(Locale.US)} tiles"
        invalidate()
    }

    private fun setPathNearbyFeet(next: Float) {
        pathNearbyFeet = next.coerceIn(500f, 30000f)
        prefs.edit().putFloat(FlightAlertSettings.KEY_PATH_NEARBY_FEET, pathNearbyFeet).apply()
    }

    private fun setAlertsEnabled(enabled: Boolean) {
        alertsEnabled = enabled
        prefs.edit().putBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, alertsEnabled).apply()
        if (alertsEnabled) {
            AircraftAlertService.start(context)
        } else {
            AircraftAlertService.stop(context)
        }
    }

    private fun setAlertDistanceFeet(next: Float) {
        alertDistanceFeet = next.coerceIn(500f, 60000f)
        prefs.edit().putFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, alertDistanceFeet).apply()
    }

    private fun setAlertAltitudeFeet(next: Float) {
        alertAltitudeFeet = next.coerceIn(100f, 10000f)
        prefs.edit().putFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, alertAltitudeFeet).apply()
    }

    private fun applyInitialMavicRangeZoomIfNeeded() {
        if (prefs.contains(FlightAlertSettings.KEY_ZOOM) || width <= 0 || height <= 0) return
        val focusArea = largestUnblockedMapRect(contentWidth(), contentHeight()).insetBy(dp(12))
        val targetSpanMeters = DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M * INITIAL_RANGE_MULTIPLIER
        val availablePixels = min(focusArea.width(), focusArea.height()).coerceAtLeast(dp(120)).toDouble()
        val metersPerPixel = targetSpanMeters / availablePixels
        val latitude = latestLocation?.latitude ?: 0.0
        zoom = log2((cos(Math.toRadians(latitude)) * EARTH_CIRCUMFERENCE_M) / (TILE_SIZE * metersPerPixel))
            .coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())
        prefs.edit().putFloat(FlightAlertSettings.KEY_ZOOM, zoom.toFloat()).apply()
    }

    private fun readStoredZoom(): Double {
        return when (val stored = prefs.all[FlightAlertSettings.KEY_ZOOM]) {
            is Float -> stored.toDouble()
            is Int -> stored.toDouble()
            is Long -> stored.toDouble()
            is String -> stored.toDoubleOrNull() ?: 10.0
            else -> 10.0
        }.coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())
    }


    private fun readMapSource(): TileSource {
        val stored = prefs.getString(FlightAlertSettings.KEY_MAP_SOURCE, TileSource.STREET.name) ?: TileSource.STREET.name
        return TileSource.entries.firstOrNull { it.name == stored } ?: TileSource.STREET
    }

    private fun settingsPanelBounds(w: Float, h: Float): RectF {
        val width = min(w - dp(32), dp(430))
        val height = min(h - dp(32), dp(610))
        val top = max(dp(16), (h - height) / 2f)
        return RectF((w - width) / 2f, top, (w + width) / 2f, top + height)
    }

    private fun imperialButtonBounds(panel: RectF) = RectF(panel.left + dp(18), panel.top + dp(88), panel.right - dp(18), panel.top + dp(122))

    private fun metricButtonBounds(panel: RectF) = RectF(panel.left + dp(18), panel.top + dp(130), panel.right - dp(18), panel.top + dp(164))

    private fun closeButtonBounds(panel: RectF) = RectF(panel.right - dp(112), panel.top + dp(14), panel.right - dp(18), panel.top + dp(48))

    private fun mapSourceButtonBounds(panel: RectF) = RectF(panel.left + dp(18), panel.top + dp(198), panel.right - dp(18), panel.top + dp(232))

    private fun pathFilterMinusBounds(panel: RectF) = adjusterMinusBounds(panel, panel.top + dp(274))

    private fun pathFilterPlusBounds(panel: RectF) = adjusterPlusBounds(panel, panel.top + dp(274))

    private fun alertsToggleBounds(panel: RectF) = RectF(panel.left + dp(18), panel.top + dp(326), panel.right - dp(18), panel.top + dp(360))

    private fun alertDistanceMinusBounds(panel: RectF) = adjusterMinusBounds(panel, panel.top + dp(404))

    private fun alertDistancePlusBounds(panel: RectF) = adjusterPlusBounds(panel, panel.top + dp(404))

    private fun alertAltitudeMinusBounds(panel: RectF) = adjusterMinusBounds(panel, panel.top + dp(494))

    private fun alertAltitudePlusBounds(panel: RectF) = adjusterPlusBounds(panel, panel.top + dp(494))
    private fun adjusterMinusBounds(panel: RectF, top: Float): RectF {
        return RectF(panel.left + dp(18), top, panel.left + dp(72), top + dp(38))
    }

    private fun adjusterPlusBounds(panel: RectF, top: Float): RectF {
        return RectF(panel.right - dp(72), top, panel.right - dp(18), top + dp(38))
    }

    private fun infoPanelBounds(w: Float, h: Float): RectF {
        val margin = dp(12)
        return if (isWideLayout(w, h)) {
            val panelWidth = min(dp(360), max(dp(300), w * 0.32f))
            RectF(w - margin - panelWidth, margin, w - margin, h - margin)
        } else {
            val panelHeight = min(dp(176), max(dp(152), h * 0.24f))
            RectF(margin, h - margin - panelHeight, w - margin, h - margin)
        }
    }

    private fun defaultMapFocus(w: Float, h: Float): ScreenPoint {
        val open = largestUnblockedMapRect(w, h)
        return ScreenPoint(open.centerX(), open.centerY())
    }

    private fun largestUnblockedMapRect(w: Float, h: Float): RectF {
        val obstacles = mapObstacles(w, h)
        val xs = mutableListOf(0f, w)
        val ys = mutableListOf(0f, h)
        obstacles.forEach {
            xs += it.left.coerceIn(0f, w)
            xs += it.right.coerceIn(0f, w)
            ys += it.top.coerceIn(0f, h)
            ys += it.bottom.coerceIn(0f, h)
        }
        val sortedX = xs.distinct().sorted()
        val sortedY = ys.distinct().sorted()
        var best = RectF(0f, 0f, w, h)
        var bestArea = -1f
        for (leftIndex in sortedX.indices) {
            for (rightIndex in leftIndex + 1 until sortedX.size) {
                for (topIndex in sortedY.indices) {
                    for (bottomIndex in topIndex + 1 until sortedY.size) {
                        val candidate = RectF(sortedX[leftIndex], sortedY[topIndex], sortedX[rightIndex], sortedY[bottomIndex])
                        val area = candidate.width() * candidate.height()
                        if (area <= bestArea) continue
                        if (obstacles.none { RectF.intersects(candidate, it) }) {
                            best = candidate
                            bestArea = area
                        }
                    }
                }
            }
        }
        return best
    }

    private fun mapObstacles(w: Float, h: Float): List<RectF> {
        val items = mutableListOf(
            topStatusBounds(w, h),
            infoPanelBounds(w, h),
            settingsButtonBounds(w, h)
        )
        if (!followingLocation && latestLocation != null) items += recenterButtonBounds(w, h)
        return items
    }

    private fun isWideLayout(w: Float, h: Float): Boolean = w > h * 1.15f

    private fun drawGearIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2)
        paint.color = color
        canvas.drawCircle(cx, cy, dp(8), paint)
        canvas.drawCircle(cx, cy, dp(3), paint)
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            canvas.drawLine(
                cx + cos(angle).toFloat() * dp(10),
                cy + sin(angle).toFloat() * dp(10),
                cx + cos(angle).toFloat() * dp(13),
                cy + sin(angle).toFloat() * dp(13),
                paint
            )
        }
    }

    private fun drawLocateIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2)
        paint.color = color
        canvas.drawCircle(cx, cy, dp(12), paint)
        canvas.drawLine(cx, cy - dp(18), cx, cy - dp(13), paint)
        canvas.drawLine(cx, cy + dp(13), cx, cy + dp(18), paint)
        canvas.drawLine(cx - dp(18), cy, cx - dp(13), cy, paint)
        canvas.drawLine(cx + dp(13), cy, cx + dp(18), cy, paint)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx, cy, dp(4), paint)
    }

    private fun drawPathFitIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2)
        paint.color = color
        val size = dp(15)
        canvas.drawRect(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f, paint)
        canvas.drawLine(cx - dp(12), cy - dp(12), cx - dp(6), cy - dp(12), paint)
        canvas.drawLine(cx - dp(12), cy - dp(12), cx - dp(12), cy - dp(6), paint)
        canvas.drawLine(cx + dp(12), cy + dp(12), cx + dp(6), cy + dp(12), paint)
        canvas.drawLine(cx + dp(12), cy + dp(12), cx + dp(12), cy + dp(6), paint)
    }

    private fun drawClearIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.4f)
        paint.color = color
        canvas.drawCircle(cx, cy, dp(12), paint)
        canvas.drawLine(cx - dp(6), cy - dp(6), cx + dp(6), cy + dp(6), paint)
        canvas.drawLine(cx + dp(6), cy - dp(6), cx - dp(6), cy + dp(6), paint)
    }

    private fun drawSmallPill(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, label: String, fill: Int, text: Int) {
        val rect = RectF(x, y, x + width, y + height)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(218, Color.red(fill), Color.green(fill), Color.blue(fill))
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(9)
        textPaint.color = text
        val metrics = textPaint.fontMetrics
        canvas.drawText(label, rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
        textPaint.isFakeBoldText = false
    }

    private fun nearestAircraft(): Aircraft? = synchronized(aircraft) { aircraft.firstOrNull() }

    private fun displayedTraffic(): TrafficDisplay {
        val snapshot = synchronized(aircraft) { aircraft.toList() }
        val selected = selectedAircraftId?.let { id -> snapshot.firstOrNull { it.icao24 == id } }
        return if (selected != null) {
            TrafficDisplay(selected, true)
        } else {
            TrafficDisplay(snapshot.firstOrNull(), false)
        }
    }

    private fun selectedPathPoints(visibleOnly: Boolean): List<GeoPoint>? {
        if (visibleOnly && !selectedFlightPathVisible) return null
        if (!hasSelectedFlightPath()) return null
        return selectedFlightPath?.map { GeoPoint(it.lat, it.lon) }
    }

    private fun selectedPathBounds(): Bounds? {
        val points = selectedPathPoints(visibleOnly = false)?.takeIf { it.size >= 2 } ?: return null
        return Bounds(
            minLat = points.minOf { it.lat },
            minLon = points.minOf { it.lon },
            maxLat = points.maxOf { it.lat },
            maxLon = points.maxOf { it.lon }
        )
    }

    private fun estimatedAircraftPosition(aircraft: Aircraft): GeoPoint {
        val speed = aircraft.velocityMs ?: return GeoPoint(aircraft.lat, aircraft.lon)
        val track = aircraft.trackDeg ?: return GeoPoint(aircraft.lat, aircraft.lon)
        if (aircraft.onGround == true) return GeoPoint(aircraft.lat, aircraft.lon)
        val reportTime = aircraft.positionTimeSec ?: aircraft.lastContactSec ?: return GeoPoint(aircraft.lat, aircraft.lon)
        val elapsed = (System.currentTimeMillis() / 1000.0 - reportTime).coerceIn(0.0, MAX_ESTIMATION_SECONDS)
        if (elapsed <= 0.0 || speed <= 0.5) return GeoPoint(aircraft.lat, aircraft.lon)
        // Smooth visual motion is predicted from the last report; network refresh stays rate-limited.
        return destinationPoint(aircraft.lat, aircraft.lon, track, speed * elapsed)
    }

    private fun destinationPoint(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): GeoPoint {
        val angularDistance = distanceM / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        return GeoPoint(Math.toDegrees(lat2), normalizeLongitude(Math.toDegrees(lon2)))
    }

    private fun displayDistanceMeters(aircraft: Aircraft): Double {
        val location = latestLocation ?: return aircraft.distanceM
        val estimated = estimatedAircraftPosition(aircraft)
        return distanceMeters(location.latitude, location.longitude, estimated.lat, estimated.lon)
    }

    private fun aircraftSymbol(aircraft: Aircraft): AircraftSymbol {
        if (aircraft.onGround == true) return AircraftSymbol.SURFACE
        return when (aircraft.category) {
            4, 5, 6 -> AircraftSymbol.HEAVY
            8 -> AircraftSymbol.ROTORCRAFT
            9, 10 -> AircraftSymbol.GLIDER
            14 -> AircraftSymbol.UAV
            16, 17, 18, 19, 20 -> AircraftSymbol.SURFACE
            else -> AircraftSymbol.FIXED_WING
        }
    }

    private fun trafficDistanceColor(aircraft: Aircraft): Int {
        return if (displayDistanceMeters(aircraft) <= feetToMeters(alertDistanceFeet.toDouble())) RED else ACCENT_GREEN
    }

    private fun formatAircraftDetail(aircraft: Aircraft): String {
        return "${formatDistance(displayDistanceMeters(aircraft))}  ${formatAltitudeValue(aircraft.altitudeM)}"
    }

    private fun formatDistance(meters: Double): String {
        return String.format(Locale.US, "%.1f %s", units.distanceMetersToDisplay(meters), units.distanceLabel)
    }

    private fun formatAltitudeValue(meters: Double?): String {
        return meters?.let {
            String.format(Locale.US, "%.0f %s", units.altitudeMetersToDisplay(it), units.altitudeLabel)
        } ?: "Unavailable"
    }

    private fun formatAccuracy(meters: Double): String {
        return if (units == UnitSystem.IMPERIAL) {
            String.format(Locale.US, "%.0f ft", meters * 3.28084)
        } else {
            String.format(Locale.US, "%.0f m", meters)
        }
    }

    private fun formatSpeed(ms: Double?): String {
        return ms?.let {
            String.format(Locale.US, "%.0f %s", units.speedMetersPerSecondToDisplay(it), units.speedLabel)
        } ?: "Speed unavailable"
    }

    private fun formatSpeedValue(ms: Double?): String {
        return ms?.let {
            String.format(Locale.US, "%.0f %s", units.speedMetersPerSecondToDisplay(it), units.speedLabel)
        } ?: "Unavailable"
    }

    private fun formatTrack(degrees: Double?): String {
        return degrees?.let { String.format(Locale.US, "%.0f deg", it) } ?: "Unavailable"
    }

    private fun formatVerticalRate(ms: Double?): String {
        return ms?.let {
            if (units == UnitSystem.IMPERIAL) {
                String.format(Locale.US, "%+.0f ft/min", it * 196.850394)
            } else {
                String.format(Locale.US, "%+.1f m/s", it)
            }
        } ?: "Unavailable"
    }

    private fun formatPosition(aircraft: Aircraft): String {
        val estimated = estimatedAircraftPosition(aircraft)
        return String.format(Locale.US, "%.4f, %.4f", estimated.lat, estimated.lon)
    }

    private fun formatAge(aircraft: Aircraft): String {
        val contact = aircraft.lastContactSec ?: aircraft.positionTimeSec ?: return "Age unavailable"
        val age = max(0.0, System.currentTimeMillis() / 1000.0 - contact)
        return "${age.toLong()}s old"
    }

    private fun formatFeetSetting(feet: Float): String {
        return if (units == UnitSystem.IMPERIAL) {
            String.format(Locale.US, "%.0f ft", feet)
        } else {
            String.format(Locale.US, "%.0f m", feetToMeters(feet.toDouble()))
        }
    }

    private fun aircraftColor(aircraft: Aircraft): Int {
        if (aircraft.isMilitary) return MILITARY_GRAY
        val altitude = aircraft.altitudeM
        return when {
            altitude == null -> ACCENT_BLUE
            altitude < 650.0 -> RED
            altitude < 1800.0 -> ACCENT_ORANGE
            aircraft.verticalRateMs != null && aircraft.verticalRateMs > 3.0 -> ACCENT_GREEN
            aircraft.verticalRateMs != null && aircraft.verticalRateMs < -3.0 -> ACCENT_PINK
            else -> ACCENT_BLUE
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private fun feetToMeters(feet: Double): Double = feet / 3.28084

    private fun latLonToWorld(lat: Double, lon: Double, z: Double): WorldPoint {
        val scale = TILE_SIZE * 2.0.pow(z)
        val sinLat = sin(Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878)))
        val x = (lon + 180.0) / 360.0 * scale
        val y = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)) * scale
        return WorldPoint(x, y)
    }

    private fun worldToLatLon(x: Double, y: Double, z: Double): GeoPoint {
        val scale = TILE_SIZE * 2.0.pow(z)
        val lon = x / scale * 360.0 - 180.0
        val mercator = Math.PI - 2.0 * Math.PI * y / scale
        val lat = Math.toDegrees(atan(sinh(mercator)))
        return GeoPoint(lat.coerceIn(-85.05112878, 85.05112878), normalizeLongitude(lon))
    }

    private fun normalizeLongitude(lon: Double): Double {
        return ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }

    private fun Bounds.toFeedBounds(): FeedBounds {
        return FeedBounds(minLat = minLat, minLon = minLon, maxLat = maxLat, maxLon = maxLon)
    }

    private fun FeedAircraft.toMapAircraft(): Aircraft {
        return Aircraft(
            icao24 = icao24,
            callsign = callsign,
            registration = registration,
            typeCode = typeCode,
            isMilitary = dbFlags?.let { it and DB_FLAG_MILITARY != 0 } == true,
            lat = lat,
            lon = lon,
            onGround = onGround,
            altitudeM = altitudeM,
            velocityMs = velocityMs,
            trackDeg = trackDeg,
            verticalRateMs = verticalRateMs,
            category = category,
            positionTimeSec = positionTimeSec,
            lastContactSec = lastContactSec,
            distanceM = distanceM
        )
    }

    private fun RectF.insetBy(amount: Float): RectF {
        return RectF(left + amount, top + amount, right - amount, bottom - amount)
    }

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Int): Float {
        val metrics = resources.displayMetrics
        return value * metrics.density * resources.configuration.fontScale
    }

    private data class GeoPoint(val lat: Double, val lon: Double)

    private data class ScreenPoint(val x: Float, val y: Float)

    private data class WorldPoint(val x: Double, val y: Double)

    private data class Viewport(
        val zoom: Double,
        val centerX: Double,
        val centerY: Double,
        val width: Float,
        val height: Float
    )

    private data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    private data class TrafficDisplay(val aircraft: Aircraft?, val selected: Boolean)

    private data class AircraftHit(val aircraft: Aircraft, val distanceSquared: Float)

    private sealed class AircraftPhotoResult {
        data class Found(val bitmap: Bitmap, val note: String) : AircraftPhotoResult()
        data class Unavailable(val reason: String) : AircraftPhotoResult()
    }

    private data class Aircraft(
        val icao24: String,
        val callsign: String,
        val registration: String?,
        val typeCode: String?,
        val isMilitary: Boolean,
        val lat: Double,
        val lon: Double,
        val onGround: Boolean?,
        val altitudeM: Double?,
        val velocityMs: Double?,
        val trackDeg: Double?,
        val verticalRateMs: Double?,
        val category: Int?,
        val positionTimeSec: Double?,
        val lastContactSec: Double?,
        val distanceM: Double
    )

    private enum class AircraftSymbol {
        FIXED_WING,
        HEAVY,
        ROTORCRAFT,
        GLIDER,
        UAV,
        SURFACE
    }


    private enum class TileSource(
        val cacheKey: String,
        val displayName: String,
        val attribution: String
    ) {
        // Providers stay explicit so the map background remains auditable and never becomes fake imagery.
        STREET("osm", "Street map", "OpenStreetMap tiles"),
        SATELLITE("esri_world_imagery", "Satellite", "Esri World Imagery");

        fun tileUrl(z: Int, x: Int, y: Int): String {
            return when (this) {
                STREET -> "https://tile.openstreetmap.org/$z/$x/$y.png"
                SATELLITE -> "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            }
        }
    }
    private enum class UnitSystem(
        val distanceLabel: String,
        val altitudeLabel: String,
        val speedLabel: String,
        val alertDistance: Double
    ) {
        IMPERIAL("mi", "ft", "mph", 2.0),
        METRIC("km", "m", "km/h", 3.2);

        fun distanceMetersToDisplay(meters: Double): Double {
            return if (this == IMPERIAL) meters / 1609.344 else meters / 1000.0
        }

        fun altitudeMetersToDisplay(meters: Double): Double {
            return if (this == IMPERIAL) meters * 3.28084 else meters
        }

        fun speedMetersPerSecondToDisplay(ms: Double): Double {
            return if (this == IMPERIAL) ms * 2.236936 else ms * 3.6
        }
    }

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 14
        const val MAX_MEMORY_TILES = 80
        const val LABEL_AIRCRAFT_COUNT = 4
        const val AIRCRAFT_REFRESH_MS = 30000L
        const val AIRCRAFT_FORCE_REFRESH_MS = 5000L
        const val AIRCRAFT_TICKER_FETCH_MS = 1000L
        const val AIRCRAFT_BOUNDS_PADDING_PX = 96.0
        const val PATH_FIT_CONTEXT_MULTIPLIER = 1.5
        const val PATH_CONFLICT_TIME_WINDOW_SEC = 180L
        const val MAX_CONFLICT_TRACKS = 12
        const val AIRCRAFT_TAP_RADIUS_DP = 34
        const val HOLE_PUNCH_MAX_SIZE_DP = 72
        const val DB_FLAG_MILITARY = 1
        const val MAX_ESTIMATION_SECONDS = 75.0
        const val EARTH_RADIUS_M = 6371000.0
        const val EARTH_CIRCUMFERENCE_M = 40075016.686
        const val DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M = 30000.0
        const val INITIAL_RANGE_MULTIPLIER = 1.25
        const val TILE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        const val USER_AGENT = "FlightAlertPrototype/0.1"
        const val TAG = "FlightAlert"

        val MAP_EMPTY: Int = Color.rgb(70, 102, 89)
        val PANEL: Int = Color.rgb(13, 29, 25)
        val PANEL_STROKE: Int = Color.rgb(39, 65, 55)
        val TEXT: Int = Color.rgb(232, 241, 229)
        val MUTED: Int = Color.rgb(159, 179, 165)
        val RED: Int = Color.rgb(238, 75, 65)
        val ACCENT_BLUE: Int = Color.rgb(105, 205, 244)
        val ACCENT_GREEN: Int = Color.rgb(91, 224, 166)
        val ACCENT_YELLOW: Int = Color.rgb(255, 213, 77)
        val ACCENT_ORANGE: Int = Color.rgb(255, 144, 82)
        val ACCENT_PINK: Int = Color.rgb(244, 115, 164)
        val MILITARY_GRAY: Int = Color.rgb(156, 165, 163)
        val IMAGE_URL_PATTERN = Regex("\\.(jpg|jpeg|png|webp)(\\?|$)", RegexOption.IGNORE_CASE)
    }
}
