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
    private var alertsEnabled = prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true)
    private var alertDistanceFeet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, FlightAlertSettings.DEFAULT_ALERT_DISTANCE_FEET)
    private var alertAltitudeFeet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, FlightAlertSettings.DEFAULT_ALERT_ALTITUDE_FEET)
    private var priorityTrackingEnabled = prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
    private var priorityRangeFeet = prefs.getFloat(FlightAlertSettings.KEY_PRIORITY_RANGE_FEET, FlightAlertSettings.DEFAULT_PRIORITY_RANGE_FEET)
    private var priorityAltitudeBelowFeet = prefs.getFloat(FlightAlertSettings.KEY_PRIORITY_ALTITUDE_BELOW_FEET, FlightAlertSettings.DEFAULT_PRIORITY_ALTITUDE_BELOW_FEET)
    private var settingsOpen = false
    private var priorityTrackerOpen = false
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
    private var selectedAircraftSnapshot: Aircraft? = null
    private var selectedFlightPathAircraftId: String? = null
    private var selectedFlightPath: List<TrackPoint>? = null
    private var selectedFlightPathVisible = false
    private var selectedPathFitRequested = false
    private var militaryOriginAircraftId: String? = null
    private var militaryOriginStatus = "Unavailable"
    private var militaryOriginRequestKey: String? = null
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
        if (priorityTrackerOpen) {
            priorityTrackerOpen = false
            invalidate()
            return true
        }
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
            drawMapColorFilter(canvas, viewport)
            drawPriorityRangeCircle(canvas, viewport, location)
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
        if (priorityTrackerOpen) {
            drawPriorityTrackerPanel(canvas, w, h)
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = contentX(event.x)
        val y = contentY(event.y)
        if (!settingsOpen && !detailsOpen && !priorityTrackerOpen && event.pointerCount >= 2) {
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
                if (!settingsOpen && !detailsOpen && !priorityTrackerOpen && !dragBlocked && dragStartCenter != null && latestLocation != null) {
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
        if (!settingsOpen && !detailsOpen && !priorityTrackerOpen && isPointerScroll && latestLocation != null) {
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
        if (!settingsOpen && !detailsOpen && !priorityTrackerOpen && latestLocation != null) {
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

    private fun drawPriorityRangeCircle(canvas: Canvas, viewport: Viewport, location: Location) {
        if (!priorityTrackingEnabled) return
        val ownship = latLonToWorld(location.latitude, location.longitude, viewport.zoom)
        val cx = (ownship.x - viewport.centerX + viewport.width / 2.0).toFloat()
        val cy = (ownship.y - viewport.centerY + viewport.height / 2.0).toFloat()
        val metersPerPixel = metersPerPixelAt(location.latitude, viewport.zoom).coerceAtLeast(0.01)
        val radiusPx = (feetToMeters(priorityRangeFeet.toDouble()) / metersPerPixel).toFloat()
        if (radiusPx <= 1f) return

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(26, Color.red(ACCENT_GREEN), Color.green(ACCENT_GREEN), Color.blue(ACCENT_GREEN))
        canvas.drawCircle(cx, cy, radiusPx, paint)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(1.8f)
        strokePaint.color = Color.argb(185, Color.red(ACCENT_GREEN), Color.green(ACCENT_GREEN), Color.blue(ACCENT_GREEN))
        canvas.drawCircle(cx, cy, radiusPx, strokePaint)
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
                    selectedAircraftId?.let { selectedId ->
                        parsed.firstOrNull { it.icao24 == selectedId }?.let { selectedAircraftSnapshot = it }
                    }
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
                        displayedTraffic().aircraft?.let { requestMilitaryOriginIfNeeded(it) }
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
        if (!hasUsableViewport()) return aircraftBoundsAroundLocation(location).withPriorityBounds(location)
        val viewport = viewportFor(location, contentWidth(), contentHeight())
        val bounds = boundsForViewport(viewport)
        return (bounds ?: aircraftBoundsAroundLocation(location)).withPriorityBounds(location)
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
        return aircraftBoundsAroundLocation(location, radiusKm * 1000.0)
    }

    private fun aircraftBoundsAroundLocation(location: Location, radiusMeters: Double): Bounds {
        val radiusKm = radiusMeters / 1000.0
        val latDelta = radiusKm / 111.0
        val lonDelta = radiusKm / (111.0 * max(0.25, cos(Math.toRadians(location.latitude))))
        return Bounds(
            minLat = (location.latitude - latDelta).coerceIn(-90.0, 90.0),
            maxLat = (location.latitude + latDelta).coerceIn(-90.0, 90.0),
            minLon = (location.longitude - lonDelta).coerceIn(-180.0, 180.0),
            maxLon = (location.longitude + lonDelta).coerceIn(-180.0, 180.0)
        )
    }

    private fun Bounds.withPriorityBounds(location: Location): Bounds {
        if (!priorityTrackingEnabled) return this
        val priorityBounds = aircraftBoundsAroundLocation(location, feetToMeters(priorityRangeFeet.toDouble()))
        return Bounds(
            minLat = min(minLat, priorityBounds.minLat),
            minLon = min(minLon, priorityBounds.minLon),
            maxLat = max(maxLat, priorityBounds.maxLat),
            maxLon = max(maxLon, priorityBounds.maxLon)
        )
    }

    private fun drawAircraft(canvas: Canvas, viewport: Viewport) {
        val snapshot = visibleAircraftSnapshot()
        val labeled = snapshot.take(labelAircraftCount()).toSet()
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
        val iconScale = aircraftIconScale()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(74, 16, 23, 25)
        canvas.drawCircle(x + dp(3) * iconScale, y + dp(4) * iconScale, dp(16) * iconScale, paint)
        val color = aircraftColor(aircraft)
        if (selected) {
            strokePaint.color = Color.argb(235, Color.red(ACCENT_GREEN), Color.green(ACCENT_GREEN), Color.blue(ACCENT_GREEN))
            strokePaint.strokeWidth = dp(2.6f)
            canvas.drawCircle(x, y, dp(24) * iconScale, strokePaint)
        }

        canvas.save()
        canvas.translate(x, y)
        canvas.scale(iconScale, iconScale)
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

    private fun aircraftIconScale(): Float {
        return when {
            zoom < 7.5 -> 0.42f
            zoom < 8.5 -> 0.52f
            zoom < 9.5 -> 0.65f
            zoom < 11.0 -> 0.82f
            else -> 1f
        }
    }

    private fun labelAircraftCount(): Int {
        return when {
            zoom < 11.0 -> 0
            zoom < 12.0 -> 1
            zoom < 13.0 -> 2
            else -> LABEL_AIRCRAFT_COUNT
        }
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
        if (!selectedFlightPathVisible || !hasSelectedFlightPath()) return snapshot.withSelectedFallback()
        val selectedId = selectedAircraftId?.lowercase(Locale.US) ?: return snapshot
        return snapshot.filter { item ->
            item.icao24.lowercase(Locale.US) == selectedId || isExtremePriority(item)
        }.withSelectedFallback()
    }

    private fun List<Aircraft>.withSelectedFallback(): List<Aircraft> {
        val selected = selectedAircraftSnapshot ?: return this
        if (selectedAircraftId == null) return this
        if (any { it.icao24 == selected.icao24 }) return this
        return listOf(selected) + this
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
        val hazard = synchronized(aircraft) { aircraft.any { isHazardAircraft(it) } }
        return when {
            nearest == null && aircraftStatus.startsWith("No aircraft reported") -> "NO TRAFFIC" to MUTED
            nearest == null -> "NO DATA" to MUTED
            hazard -> "TRAFFIC ALERT" to RED
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
            if (target.isMilitary) {
                y = drawTrafficDetailRow(canvas, rect, y, "Military", "Tagged military")
                y = drawTrafficDetailRow(canvas, rect, y, "Origin status", formatOriginStatus(target, aircraftDetails.takeIf { selectedAircraftId == target.icao24 }))
            }
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
            drawPhotoCaption(canvas, photoRect, aircraftPhotoStatus)
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
            if (aircraft.isMilitary) {
                y = drawTrafficDetailRow(canvas, rect, y, "Military", "Tagged military")
                y = drawTrafficDetailRow(canvas, rect, y, "Origin status", formatOriginStatus(aircraft, details))
            }
            y = drawTrafficDetailRow(canvas, rect, y, "Route", details?.route ?: "Unavailable")
            y = drawTrafficDetailRow(canvas, rect, y, "Origin", formatAirport(details?.originAirport))
            y = drawTrafficDetailRow(canvas, rect, y, "Destination", formatAirport(details?.destinationAirport))
            y = drawTrafficDetailRow(canvas, rect, y, "Flight time", formatObservedFlightTime(aircraft))
            y = drawTrafficDetailRow(canvas, rect, y, "Route complete", formatRouteCompletion(details, aircraft))
            y = drawTrafficDetailRow(canvas, rect, y, "Observed path span", formatObservedPathSpan(aircraft))
        }
    }

    private fun drawPhotoCaption(canvas: Canvas, photoRect: RectF, caption: String) {
        if (caption.isBlank()) return
        val captionRect = RectF(photoRect.left, photoRect.bottom - dp(26), photoRect.right, photoRect.bottom)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, Color.red(PANEL), Color.green(PANEL), Color.blue(PANEL))
        canvas.drawRoundRect(captionRect, dp(4), dp(4), paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(10)
        textPaint.color = TEXT
        val fitted = ellipsize(caption, captionRect.width() - dp(14))
        val metrics = textPaint.fontMetrics
        canvas.drawText(fitted, captionRect.centerX(), captionRect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
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

    private fun formatObservedFlightTime(aircraft: Aircraft): String {
        val id = aircraft.icao24.lowercase(Locale.US)
        if (selectedFlightPathAircraftId != id) return "Unavailable"
        val path = selectedFlightPath?.takeIf { it.size >= 2 } ?: return "Unavailable"
        val start = path.minOf { it.epochSec }
        val latest = max(path.maxOf { it.epochSec }.toDouble(), System.currentTimeMillis() / 1000.0)
        return String.format(Locale.US, "Observed %.0f min", ((latest - start) / 60.0).coerceAtLeast(0.0))
    }

    private fun formatRouteCompletion(details: AircraftDetails?, aircraft: Aircraft): String {
        val origin = details?.originAirport
        val destination = details?.destinationAirport
        val originLat = origin?.latitude ?: return "Unavailable"
        val originLon = origin.longitude ?: return "Unavailable"
        val destLat = destination?.latitude ?: return "Unavailable"
        val destLon = destination.longitude ?: return "Unavailable"
        val total = distanceMeters(originLat, originLon, destLat, destLon)
        if (total < 1000.0) return "Unavailable"
        val current = estimatedAircraftPosition(aircraft)
        val completed = (distanceMeters(originLat, originLon, current.lat, current.lon) / total * 100.0).coerceIn(0.0, 100.0)
        return String.format(Locale.US, "~%.0f%% direct-route", completed)
    }

    private fun formatOriginStatus(aircraft: Aircraft, details: AircraftDetails?): String {
        if (!aircraft.isMilitary) return "Unavailable"
        val selectedStatus = militaryOriginStatus.takeIf { militaryOriginAircraftId == aircraft.icao24 && it != "Unavailable" }
        if (selectedStatus != null) return selectedStatus
        val origin = details?.originAirport ?: return "Unavailable"
        val label = formatAirport(origin)
        return if (isMilitaryAirportName(origin.name, origin.icao)) {
            "Military base: $label"
        } else {
            "Route origin: $label"
        }
    }

    private fun isMilitaryAerodrome(tags: JSONObject?, name: String?, icao: String?): Boolean {
        if (isMilitaryAirportName(name, icao)) return true
        if (tags == null) return false
        val combined = listOf(
            tags.optString("military"),
            tags.optString("aerodrome"),
            tags.optString("aerodrome:type"),
            tags.optString("operator"),
            tags.optString("operator:type"),
            tags.optString("owner")
        ).joinToString(" ").uppercase(Locale.US)
        return MILITARY_AERODROME_KEYWORDS.any { combined.contains(it) }
    }

    private fun isMilitaryAirportName(name: String?, icao: String?): Boolean {
        val combined = listOfNotNull(name, icao).joinToString(" ").uppercase(Locale.US)
        if (combined.isBlank()) return false
        return MILITARY_AERODROME_KEYWORDS.any { combined.contains(it) }
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

        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = MUTED
        canvas.drawText("Alerts", rect.left + dp(18), rect.top + dp(258), textPaint)
        drawChoiceButton(canvas, alertsToggleBounds(rect), if (alertsEnabled) "Hazard alerts on" else "Hazard alerts off", alertsEnabled)
        drawAdjusterRow(canvas, rect, rect.top + dp(348), "Alert distance", formatFeetSetting(alertDistanceFeet), alertDistanceMinusBounds(rect), alertDistancePlusBounds(rect))
        drawAdjusterRow(canvas, rect, rect.top + dp(438), "Alert vertical range", formatFeetSetting(alertAltitudeFeet), alertAltitudeMinusBounds(rect), alertAltitudePlusBounds(rect))
        drawChoiceButton(canvas, priorityTrackerButtonBounds(rect), "Priority tracker", priorityTrackingEnabled)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = sp(11)
        textPaint.color = MUTED
        canvas.drawText("Map: ${mapSource.attribution}", rect.left + dp(18), rect.bottom - dp(38), textPaint)
        canvas.drawText("Aircraft and paths: live feed sources", rect.left + dp(18), rect.bottom - dp(18), textPaint)
    }

    private fun drawPriorityTrackerPanel(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(165, 3, 8, 9)
        canvas.drawRect(0f, 0f, w, h, paint)

        val rect = priorityTrackerPanelBounds(w, h)
        paint.color = Color.rgb(18, 31, 29)
        canvas.drawRoundRect(rect, dp(8), dp(8), paint)
        strokePaint.strokeWidth = dp(1)
        strokePaint.color = PANEL_STROKE
        canvas.drawRoundRect(rect, dp(8), dp(8), strokePaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(20)
        textPaint.color = TEXT
        canvas.drawText("Priority tracker", rect.left + dp(18), rect.top + dp(34), textPaint)
        drawChoiceButton(canvas, priorityCloseButtonBounds(rect), "Close", false)

        drawChoiceButton(canvas, priorityTrackingToggleBounds(rect), if (priorityTrackingEnabled) "Tracking on" else "Tracking off", priorityTrackingEnabled)
        drawAdjusterRow(canvas, rect, rect.top + dp(142), "Tracking range", formatRangeSetting(priorityRangeFeet), priorityRangeMinusBounds(rect), priorityRangePlusBounds(rect))
        drawAdjusterRow(canvas, rect, rect.top + dp(232), "Priority below", formatFeetSetting(priorityAltitudeBelowFeet), priorityAltitudeBelowMinusBounds(rect), priorityAltitudeBelowPlusBounds(rect))

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = MUTED
        canvas.drawText("Aircraft in range", rect.left + dp(18), rect.top + dp(324), textPaint)

        val rows = priorityAircraftSnapshot()
        if (rows.isEmpty()) {
            textPaint.isFakeBoldText = true
            textPaint.textSize = sp(17)
            textPaint.color = TEXT
            canvas.drawText(if (priorityTrackingEnabled) "No aircraft in range" else "Tracking is off", rect.left + dp(18), rect.top + dp(366), textPaint)
            textPaint.isFakeBoldText = false
            return
        }

        var y = rect.top + dp(362)
        rows.take(PRIORITY_PANEL_ROWS).forEach { item ->
            y = drawPriorityAircraftRow(canvas, rect, y, item)
        }
        if (rows.size > PRIORITY_PANEL_ROWS) {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = false
            textPaint.textSize = sp(11)
            textPaint.color = MUTED
            canvas.drawText("+${rows.size - PRIORITY_PANEL_ROWS} more", rect.left + dp(18), y + dp(8), textPaint)
        }
    }

    private fun drawPriorityAircraftRow(canvas: Canvas, panel: RectF, y: Float, aircraft: Aircraft): Float {
        val extreme = isExtremePriority(aircraft)
        val row = RectF(panel.left + dp(18), y - dp(22), panel.right - dp(18), y + dp(34))
        paint.style = Paint.Style.FILL
        paint.color = if (extreme) Color.argb(60, Color.red(RED), Color.green(RED), Color.blue(RED)) else Color.argb(38, 255, 255, 255)
        canvas.drawRoundRect(row, dp(6), dp(6), paint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(15)
        textPaint.color = if (extreme) RED else TEXT
        canvas.drawText(aircraft.registration ?: aircraft.callsign, row.left + dp(10), y, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = sp(13)
        textPaint.color = TEXT
        canvas.drawText(formatAltitudeValue(aircraft.altitudeM), row.right - dp(10), y, textPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(11)
        textPaint.color = MUTED
        canvas.drawText("${formatDistance(displayDistanceMeters(aircraft))}  ${formatAge(aircraft)}", row.left + dp(10), y + dp(20), textPaint)
        return y + dp(64)
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
        if (priorityTrackerOpen) {
            val panel = priorityTrackerPanelBounds(contentWidth(), contentHeight())
            when {
                priorityCloseButtonBounds(panel).contains(x, y) -> priorityTrackerOpen = false
                priorityTrackingToggleBounds(panel).contains(x, y) -> setPriorityTrackingEnabled(!priorityTrackingEnabled)
                priorityRangeMinusBounds(panel).contains(x, y) -> setPriorityRangeFeet(priorityRangeFeet - priorityRangeStepFeet())
                priorityRangePlusBounds(panel).contains(x, y) -> setPriorityRangeFeet(priorityRangeFeet + priorityRangeStepFeet())
                priorityAltitudeBelowMinusBounds(panel).contains(x, y) -> setPriorityAltitudeBelowFeet(priorityAltitudeBelowFeet - priorityAltitudeStepFeet())
                priorityAltitudeBelowPlusBounds(panel).contains(x, y) -> setPriorityAltitudeBelowFeet(priorityAltitudeBelowFeet + priorityAltitudeStepFeet())
            }
            invalidate()
            return
        }

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
                alertsToggleBounds(panel).contains(x, y) -> setAlertsEnabled(!alertsEnabled)
                alertDistanceMinusBounds(panel).contains(x, y) -> setAlertDistanceFeet(alertDistanceFeet - 1000f)
                alertDistancePlusBounds(panel).contains(x, y) -> setAlertDistanceFeet(alertDistanceFeet + 1000f)
                alertAltitudeMinusBounds(panel).contains(x, y) -> setAlertAltitudeFeet(alertAltitudeFeet - 500f)
                alertAltitudePlusBounds(panel).contains(x, y) -> setAlertAltitudeFeet(alertAltitudeFeet + 500f)
                priorityTrackerButtonBounds(panel).contains(x, y) -> {
                    settingsOpen = false
                    priorityTrackerOpen = true
                }
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
        selectedAircraftSnapshot = null
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
            selectAircraft(hit)
        }
    }

    private fun openAircraftDetails(aircraft: Aircraft) {
        selectAircraft(aircraft)
        detailsOpen = true
        aircraftDetails = null
        aircraftPhoto = null
        aircraftDetailsStatus = "Loading live aircraft details"
        aircraftPhotoStatus = "Loading exact-aircraft photo"
        requestAircraftDetails(aircraft)
    }

    private fun selectAircraft(aircraft: Aircraft) {
        selectedAircraftId = aircraft.icao24
        selectedAircraftSnapshot = aircraft
        selectedFlightPathAircraftId = null
        selectedFlightPath = null
        selectedFlightPathVisible = false
        selectedPathFitRequested = false
        militaryOriginAircraftId = aircraft.icao24
        militaryOriginStatus = if (aircraft.isMilitary) "Waiting for flight path origin" else "Unavailable"
        militaryOriginRequestKey = null
        requestFlightPath(aircraft.icao24)
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

    private fun requestMilitaryOriginIfNeeded(aircraft: Aircraft) {
        val key = aircraft.icao24.lowercase(Locale.US)
        if (!aircraft.isMilitary || selectedAircraftId?.lowercase(Locale.US) != key) return
        val path = selectedFlightPath?.takeIf { selectedFlightPathAircraftId == key && it.size >= 2 } ?: return
        val firstPoint = path.minByOrNull { it.epochSec } ?: return
        val requestKey = "${key}:${firstPoint.epochSec}:${"%.4f".format(Locale.US, firstPoint.lat)}:${"%.4f".format(Locale.US, firstPoint.lon)}"
        if (militaryOriginRequestKey == requestKey) return

        militaryOriginRequestKey = requestKey
        militaryOriginAircraftId = aircraft.icao24
        militaryOriginStatus = "Checking track origin"
        executor.execute {
            val status = lookupOriginAerodrome(firstPoint)
            post {
                if (selectedAircraftId?.lowercase(Locale.US) == key && militaryOriginRequestKey == requestKey) {
                    militaryOriginStatus = status
                    invalidate()
                }
            }
        }
    }

    private fun lookupOriginAerodrome(point: TrackPoint): String {
        val query = """
            [out:json][timeout:8];
            (
              node(around:${ORIGIN_AERODROME_RADIUS_M.toInt()},${point.lat},${point.lon})["aeroway"="aerodrome"];
              way(around:${ORIGIN_AERODROME_RADIUS_M.toInt()},${point.lat},${point.lon})["aeroway"="aerodrome"];
              relation(around:${ORIGIN_AERODROME_RADIUS_M.toInt()},${point.lat},${point.lon})["aeroway"="aerodrome"];
            );
            out center tags 20;
        """.trimIndent()
        val apiUrl = "https://overpass-api.de/api/interpreter?data=${URLEncoder.encode(query, "UTF-8")}"
        val elements = fetchJsonObject(apiUrl)?.optJSONArray("elements") ?: return "Origin lookup unavailable"
        val candidates = mutableListOf<OriginAerodrome>()
        for (index in 0 until elements.length()) {
            val item = elements.optJSONObject(index) ?: continue
            val center = item.optJSONObject("center")
            val lat = if (item.has("lat")) item.optDouble("lat") else center?.optDouble("lat") ?: continue
            val lon = if (item.has("lon")) item.optDouble("lon") else center?.optDouble("lon") ?: continue
            val distanceM = distanceMeters(point.lat, point.lon, lat, lon)
            if (distanceM > ORIGIN_AERODROME_RADIUS_M) continue
            val tags = item.optJSONObject("tags")
            val name = tags?.optString("name")?.trim()?.ifEmpty { null }
            val icao = tags?.optString("icao")?.trim()?.ifEmpty { null }
            candidates += OriginAerodrome(
                name = name,
                icao = icao,
                distanceM = distanceM,
                military = isMilitaryAerodrome(tags, name, icao)
            )
        }

        val nearest = candidates.minByOrNull { it.distanceM } ?: return "Track origin not matched to an aerodrome"
        val label = nearest.label()
        return if (nearest.military) {
            "Military base: $label"
        } else {
            "Track origin: $label (civilian/other)"
        }
    }

    private fun fetchAircraftPhoto(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult {
        val registration = normalizedRegistration(details.registration ?: aircraft.registration)
        fetchAdsbDbPhotoUrls(aircraft.icao24, registration).forEach { imageUrl ->
            fetchBitmap(imageUrl)?.let { return AircraftPhotoResult.Found(it, "Exact aircraft photo from ADSBdb") }
        }
        if (registration != null) {
            fetchJetPhotosExactImageUrls(registration).forEach { imageUrl ->
                fetchBitmap(imageUrl)?.let { return AircraftPhotoResult.Found(it, "Exact aircraft photo from JetPhotos; registration verified") }
            }
        }
        val exactSources = listOfNotNull(
            "https://api.planespotters.net/pub/photos/hex/${aircraft.icao24.trim()}",
            (details.registration ?: aircraft.registration)?.let { "https://api.planespotters.net/pub/photos/reg/${it.trim()}" }
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

    private fun fetchAdsbDbPhotoUrls(icao24: String, registration: String?): List<String> {
        val keys = listOfNotNull(icao24.trim().takeIf { it.isNotBlank() }, registration).distinct()
        return keys.flatMap { key ->
            val encoded = URLEncoder.encode(key, "UTF-8")
            val json = fetchJsonObject("https://api.adsbdb.com/v0/aircraft/$encoded") ?: return@flatMap emptyList()
            val aircraft = json.optJSONObject("response")?.optJSONObject("aircraft") ?: return@flatMap emptyList()
            listOfNotNull(
                aircraft.optString("url_photo").takeIf { it.startsWith("https://", ignoreCase = true) },
                aircraft.optString("url_photo_thumbnail").takeIf { it.startsWith("https://", ignoreCase = true) }
            )
        }.distinct()
    }

    private fun fetchJetPhotosExactImageUrls(registration: String): List<String> {
        val encoded = URLEncoder.encode(registration, "UTF-8")
        val apiUrl = "https://jp.rewis.workers.dev/?page=1&sort-order=1&keywords=$encoded&keywords-type=registration&keywords-contain=0"
        val photos = fetchJsonObject(apiUrl)?.optJSONArray("photos") ?: return emptyList()
        val urls = mutableListOf<String>()
        for (index in 0 until photos.length()) {
            val item = photos.optJSONObject(index) ?: continue
            val foundRegistration = normalizedRegistration(item.optString("registration"))
            if (foundRegistration != registration) continue
            listOf(item.optString("imageUrl"), item.optString("thumbnailUrl")).forEach { url ->
                if (url.startsWith("https://", ignoreCase = true) && IMAGE_URL_PATTERN.containsMatchIn(url)) urls += url
            }
        }
        return urls.distinct()
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
        queries.forEach { query ->
            val encoded = URLEncoder.encode(query, "UTF-8")
            val apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch=$encoded&prop=imageinfo&iiprop=url|mime"
            fetchWikimediaImageUrls(apiUrl).take(MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY).forEach { url ->
                fetchBitmap(url)?.let { bitmap ->
                    return AircraftPhotoResult.Found(bitmap, "Representative $model photo from Wikimedia Commons; not this exact aircraft")
                }
            }
        }

        queries.forEach { query ->
            fetchOpenverseImageUrls(query).take(MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY).forEach { url ->
                fetchBitmap(url)?.let { bitmap ->
                    return AircraftPhotoResult.Found(bitmap, "Representative $model photo from Openverse; not this exact aircraft")
                }
            }
        }

        queries.forEach { query ->
            fetchWikipediaPageImageUrls(query).take(MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY).forEach { url ->
                fetchBitmap(url)?.let { bitmap ->
                    return AircraftPhotoResult.Found(bitmap, "Representative $model photo from Wikipedia; not this exact aircraft")
                }
            }
        }
        return null
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
            "B78X" -> "Boeing 787-10"
            "B744" -> "Boeing 747-400"
            "B748" -> "Boeing 747-8"
            "BCS1" -> "Airbus A220-100"
            "BCS3" -> "Airbus A220-300"
            "AT72" -> "ATR 72"
            "AT76" -> "ATR 72-600"
            "BE20" -> "Beechcraft King Air 200"
            "BE30" -> "Beechcraft King Air 300"
            "BE33" -> "Beechcraft Bonanza"
            "BE35" -> "Beechcraft Bonanza"
            "BE36" -> "Beechcraft Bonanza"
            "BE40" -> "Beechjet 400"
            "BE55" -> "Beechcraft Baron"
            "BE58" -> "Beechcraft Baron"
            "BE76" -> "Beechcraft Duchess"
            "B350" -> "Beechcraft King Air 350"
            "C150" -> "Cessna 150"
            "C152" -> "Cessna 152"
            "C172" -> "Cessna 172"
            "C177" -> "Cessna 177 Cardinal"
            "C180" -> "Cessna 180"
            "C182" -> "Cessna 182"
            "C185" -> "Cessna 185"
            "C206" -> "Cessna 206"
            "C208" -> "Cessna 208 Caravan"
            "C210" -> "Cessna 210"
            "C25A" -> "Cessna Citation CJ2"
            "C25B" -> "Cessna Citation CJ3"
            "C25C" -> "Cessna Citation CJ4"
            "C310" -> "Cessna 310"
            "C414" -> "Cessna 414"
            "C421" -> "Cessna 421"
            "C525" -> "Cessna CitationJet"
            "C56X" -> "Cessna Citation Excel"
            "C680" -> "Cessna Citation Sovereign"
            "C700" -> "Cessna Citation Longitude"
            "CL30" -> "Bombardier Challenger 300"
            "CL35" -> "Bombardier Challenger 350"
            "CL60" -> "Bombardier Challenger 600"
            "CRJ7" -> "Bombardier CRJ700"
            "CRJ9" -> "Bombardier CRJ900"
            "DA40" -> "Diamond DA40"
            "DA42" -> "Diamond DA42"
            "DH8D" -> "De Havilland Canada Dash 8 Q400"
            "E170" -> "Embraer 170"
            "E75L", "E75S" -> "Embraer 175"
            "E190" -> "Embraer 190"
            "E195" -> "Embraer 195"
            "E50P" -> "Embraer Phenom 100"
            "E55P" -> "Embraer Phenom 300"
            "F2TH" -> "Dassault Falcon 2000"
            "F900" -> "Dassault Falcon 900"
            "FA50" -> "Dassault Falcon 50"
            "GL5T" -> "Bombardier Global 5000"
            "GL7T" -> "Bombardier Global 7500"
            "GLEX" -> "Bombardier Global Express"
            "GLF4" -> "Gulfstream IV"
            "GLF5" -> "Gulfstream V"
            "GLF6" -> "Gulfstream G650"
            "H25B" -> "Hawker 800"
            "LJ35" -> "Learjet 35"
            "LJ45" -> "Learjet 45"
            "P28A" -> "Piper PA-28 Cherokee"
            "PA28" -> "Piper PA-28 Cherokee"
            "PA30" -> "Piper PA-30 Twin Comanche"
            "PA31" -> "Piper PA-31 Navajo"
            "PA32" -> "Piper PA-32 Cherokee Six"
            "PA34" -> "Piper PA-34 Seneca"
            "PA44" -> "Piper PA-44 Seminole"
            "P46T" -> "Piper PA-46 Malibu"
            "PC12" -> "Pilatus PC-12"
            "R44" -> "Robinson R44"
            "R66" -> "Robinson R66"
            "S22T" -> "Cirrus SR22T"
            "SF34" -> "Saab 340"
            "SR20" -> "Cirrus SR20"
            "SR22" -> "Cirrus SR22"
            "TBM7" -> "Socata TBM 700"
            "TBM8" -> "Socata TBM 850"
            "TBM9" -> "Daher TBM 900"
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
            "\"$model\" aircraft photo",
            "$model aircraft photo",
            "\"$model\" airplane",
            "$model airplane",
            "\"$model\" airliner",
            "\"$model\" in flight",
            details.type?.let { "\"$it\" aircraft" },
            details.typeCode?.let { "${details.manufacturer.orEmpty()} $it aircraft".trim() },
            details.typeCode?.let { "$it aircraft" }
        ).filter { it.isNotBlank() }.distinct()
    }

    private fun fetchWikimediaImageUrls(apiUrl: String): List<String> {
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
                return emptyList()
            }
            val pages = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONObject("query")
                ?.optJSONObject("pages")
                ?: return emptyList()
            val keys = pages.keys()
            val urls = mutableListOf<String>()
            while (keys.hasNext()) {
                val info = pages.optJSONObject(keys.next())?.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val mime = info.optString("mime")
                val url = info.optString("url")
                if (mime.startsWith("image/") && IMAGE_URL_PATTERN.containsMatchIn(url)) urls += url
            }
            urls
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchOpenverseImageUrls(query: String): List<String> {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val apiUrl = "https://api.openverse.org/v1/images/?format=json&page_size=12&mature=false&q=$encoded"
            connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 9000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return emptyList()
            }
            val results = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONArray("results")
                ?: return emptyList()
            val urls = mutableListOf<String>()
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                val title = item.optString("title").lowercase(Locale.US)
                if (
                    url.startsWith("http", ignoreCase = true) &&
                    IMAGE_URL_PATTERN.containsMatchIn(url) &&
                    !title.contains("logo") &&
                    !title.contains("diagram")
                ) {
                    urls += url
                }
            }
            urls
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchWikipediaPageImageUrls(query: String): List<String> {
        val encoded = URLEncoder.encode("$query aircraft", "UTF-8")
        val apiUrl = "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=$encoded&gsrlimit=6&prop=pageimages&piprop=thumbnail|original&pithumbsize=1100"
        val pages = fetchJsonObject(apiUrl)
            ?.optJSONObject("query")
            ?.optJSONObject("pages")
            ?: return emptyList()
        val urls = mutableListOf<String>()
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            listOfNotNull(
                page.optJSONObject("original")?.optString("source"),
                page.optJSONObject("thumbnail")?.optString("source")
            ).forEach { url ->
                if (url.startsWith("https://", ignoreCase = true) && IMAGE_URL_PATTERN.containsMatchIn(url)) urls += url
            }
        }
        return urls.distinct()
    }

    private fun fetchJsonObject(url: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 9000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
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

    private fun normalizedRegistration(value: String?): String? {
        return value
            ?.uppercase(Locale.US)
            ?.replace("PHOTOS", "")
            ?.replace(Regex("[^A-Z0-9-]"), "")
            ?.trim('-')
            ?.takeIf { it.isNotBlank() && it != "NA" }
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
        if (settingsOpen || detailsOpen || priorityTrackerOpen) return true
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

    private fun setAlertsEnabled(enabled: Boolean) {
        alertsEnabled = enabled
        prefs.edit().putBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, alertsEnabled).apply()
        updateMonitoringService()
    }

    private fun setAlertDistanceFeet(next: Float) {
        alertDistanceFeet = next.coerceIn(500f, 60000f)
        prefs.edit().putFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, alertDistanceFeet).apply()
        updateMonitoringService()
    }

    private fun setAlertAltitudeFeet(next: Float) {
        alertAltitudeFeet = next.coerceIn(100f, 10000f)
        prefs.edit().putFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, alertAltitudeFeet).apply()
        updateMonitoringService()
    }

    private fun setPriorityTrackingEnabled(enabled: Boolean) {
        priorityTrackingEnabled = enabled
        prefs.edit().putBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, priorityTrackingEnabled).apply()
        updateMonitoringService()
        requestVisibleAircraftIfNeeded(force = true)
    }

    private fun setPriorityRangeFeet(next: Float) {
        priorityRangeFeet = next.coerceIn(1000f, 528000f)
        prefs.edit().putFloat(FlightAlertSettings.KEY_PRIORITY_RANGE_FEET, priorityRangeFeet).apply()
        updateMonitoringService()
        requestVisibleAircraftIfNeeded(force = true)
    }

    private fun setPriorityAltitudeBelowFeet(next: Float) {
        priorityAltitudeBelowFeet = next.coerceIn(100f, 60000f)
        prefs.edit().putFloat(FlightAlertSettings.KEY_PRIORITY_ALTITUDE_BELOW_FEET, priorityAltitudeBelowFeet).apply()
        updateMonitoringService()
    }

    private fun priorityRangeStepFeet(): Float {
        return if (priorityRangeFeet >= 5280f) 5280f else 1000f
    }

    private fun priorityAltitudeStepFeet(): Float = 500f

    private fun updateMonitoringService() {
        if (alertsEnabled || priorityTrackingEnabled) {
            AircraftAlertService.start(context)
        } else {
            AircraftAlertService.stop(context)
        }
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
        val height = min(h - dp(32), dp(660))
        val top = max(dp(16), (h - height) / 2f)
        return RectF((w - width) / 2f, top, (w + width) / 2f, top + height)
    }

    private fun imperialButtonBounds(panel: RectF) = RectF(panel.left + dp(18), panel.top + dp(88), panel.right - dp(18), panel.top + dp(122))

    private fun metricButtonBounds(panel: RectF) = RectF(panel.left + dp(18), panel.top + dp(130), panel.right - dp(18), panel.top + dp(164))

    private fun closeButtonBounds(panel: RectF) = RectF(panel.right - dp(112), panel.top + dp(14), panel.right - dp(18), panel.top + dp(48))

    private fun mapSourceButtonBounds(panel: RectF) = RectF(panel.left + dp(18), panel.top + dp(198), panel.right - dp(18), panel.top + dp(232))

    private fun alertsToggleBounds(panel: RectF) = RectF(panel.left + dp(18), panel.top + dp(274), panel.right - dp(18), panel.top + dp(308))

    private fun alertDistanceMinusBounds(panel: RectF) = adjusterMinusBounds(panel, panel.top + dp(364))

    private fun alertDistancePlusBounds(panel: RectF) = adjusterPlusBounds(panel, panel.top + dp(364))

    private fun alertAltitudeMinusBounds(panel: RectF) = adjusterMinusBounds(panel, panel.top + dp(454))

    private fun alertAltitudePlusBounds(panel: RectF) = adjusterPlusBounds(panel, panel.top + dp(454))

    private fun priorityTrackerButtonBounds(panel: RectF) = RectF(panel.left + dp(18), panel.top + dp(548), panel.right - dp(18), panel.top + dp(586))

    private fun priorityTrackerPanelBounds(w: Float, h: Float): RectF {
        val width = min(w - dp(32), dp(430))
        val height = min(h - dp(32), dp(660))
        val top = max(dp(16), (h - height) / 2f)
        return RectF((w - width) / 2f, top, (w + width) / 2f, top + height)
    }

    private fun priorityCloseButtonBounds(panel: RectF) = RectF(panel.right - dp(112), panel.top + dp(14), panel.right - dp(18), panel.top + dp(48))

    private fun priorityTrackingToggleBounds(panel: RectF) = RectF(panel.left + dp(18), panel.top + dp(72), panel.right - dp(18), panel.top + dp(110))

    private fun priorityRangeMinusBounds(panel: RectF) = adjusterMinusBounds(panel, panel.top + dp(158))

    private fun priorityRangePlusBounds(panel: RectF) = adjusterPlusBounds(panel, panel.top + dp(158))

    private fun priorityAltitudeBelowMinusBounds(panel: RectF) = adjusterMinusBounds(panel, panel.top + dp(248))

    private fun priorityAltitudeBelowPlusBounds(panel: RectF) = adjusterPlusBounds(panel, panel.top + dp(248))

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
            ?: selectedAircraftSnapshot?.takeIf { it.icao24 == selectedAircraftId }
        return if (selected != null) {
            TrafficDisplay(selected, true)
        } else {
            TrafficDisplay(snapshot.firstOrNull(), false)
        }
    }

    private fun priorityAircraftSnapshot(): List<Aircraft> {
        if (!priorityTrackingEnabled) return emptyList()
        return synchronized(aircraft) { aircraft.toList() }
            .filter { displayDistanceMeters(it) <= feetToMeters(priorityRangeFeet.toDouble()) }
            .sortedWith(compareByDescending<Aircraft> { isExtremePriority(it) }.thenBy { it.altitudeM ?: Double.MAX_VALUE }.thenBy { displayDistanceMeters(it) })
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

    private fun verticalSeparationFeet(aircraft: Aircraft): Double? {
        val location = latestLocation ?: return null
        val aircraftAltitude = aircraft.altitudeM ?: return null
        if (!location.hasAltitude()) return null
        return abs(aircraftAltitude * 3.28084 - location.altitude * 3.28084)
    }

    private fun isHazardAircraft(aircraft: Aircraft): Boolean {
        val separation = verticalSeparationFeet(aircraft) ?: return false
        return displayDistanceMeters(aircraft) <= feetToMeters(alertDistanceFeet.toDouble()) &&
            separation <= alertAltitudeFeet
    }

    private fun isExtremePriority(aircraft: Aircraft): Boolean {
        val altitudeM = aircraft.altitudeM ?: return false
        return priorityTrackingEnabled &&
            displayDistanceMeters(aircraft) <= feetToMeters(priorityRangeFeet.toDouble()) &&
            altitudeM * 3.28084 <= priorityAltitudeBelowFeet
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
        return if (isHazardAircraft(aircraft)) RED else ACCENT_GREEN
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

    private fun formatRangeSetting(feet: Float): String {
        return if (units == UnitSystem.IMPERIAL && feet >= 5280f) {
            String.format(Locale.US, "%.1f mi", feet / 5280f)
        } else {
            formatFeetSetting(feet)
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

    private fun metersPerPixelAt(latitude: Double, z: Double): Double {
        return cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))) * EARTH_CIRCUMFERENCE_M / (TILE_SIZE * 2.0.pow(z))
    }

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

    private data class OriginAerodrome(
        val name: String?,
        val icao: String?,
        val distanceM: Double,
        val military: Boolean
    ) {
        fun label(): String = listOfNotNull(name, icao)
            .distinct()
            .joinToString(" / ")
            .ifEmpty { "Unnamed aerodrome" }
    }

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
        const val PRIORITY_PANEL_ROWS = 5
        const val MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY = 4
        const val AIRCRAFT_TAP_RADIUS_DP = 34
        const val HOLE_PUNCH_MAX_SIZE_DP = 72
        const val DB_FLAG_MILITARY = 1
        const val MAX_ESTIMATION_SECONDS = 75.0
        const val EARTH_RADIUS_M = 6371000.0
        const val EARTH_CIRCUMFERENCE_M = 40075016.686
        const val ORIGIN_AERODROME_RADIUS_M = 9000.0
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
        val MILITARY_AERODROME_KEYWORDS = listOf(
            "AIR FORCE",
            "AFB",
            "NAVAL AIR",
            "NAVAL STATION",
            "NAS ",
            "JOINT BASE",
            "ARMY AIRFIELD",
            " AAF",
            "MARINE CORPS",
            "MCAS",
            "AIR NATIONAL GUARD",
            "COAST GUARD",
            "CGAS",
            "MILITARY",
            "DEFENCE",
            "DEFENSE"
        )
    }
}
