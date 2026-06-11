package com.flightalert.ui.map

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import com.flightalert.data.AircraftFeedClient
import com.flightalert.data.AircraftDetails
import com.flightalert.data.AircraftDetailsClient
import com.flightalert.data.FeedAircraft
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedStatus
import com.flightalert.data.FlightTrace
import com.flightalert.data.FlightTraceClient
import com.flightalert.data.TraceSegment
import com.flightalert.data.TrackPoint
import com.flightalert.service.AircraftAlertService
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.settings.FlightAlertSettings.ThemeTreatment
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
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
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

// Custom map cockpit: real map tiles, real aircraft feeds, and canvas UI that adapts to device shape.
class FlightMapView(context: Context) : View(context), LocationListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPath = Path()
    private val prefs: SharedPreferences = FlightAlertSettings.prefs(context)
    private var visualTheme = FlightAlertSettings.readVisualTheme(prefs)
    private val themeColors get() = visualTheme.colors
    private val themeStyle get() = visualTheme.style
    private val mapEmptyColor get() = themeColors.mapEmpty
    private val panelColor get() = themeColors.panel
    private val panelAltColor get() = themeColors.panelAlt
    private val panelStrokeColor get() = themeColors.panelStroke
    private val controlFillColor get() = themeColors.controlFill
    private val controlStrokeColor get() = themeColors.controlStroke
    private val buttonFillColor get() = themeColors.buttonFill
    private val buttonStrokeColor get() = themeColors.buttonStroke
    private val scrimColor get() = themeColors.scrim
    private val textColor get() = themeColors.text
    private val mutedColor get() = themeColors.muted
    private val dangerColor get() = themeColors.danger
    private val accentBlueColor get() = themeColors.accentBlue
    private val accentGreenColor get() = themeColors.accentGreen
    private val accentYellowColor get() = themeColors.accentYellow
    private val accentOrangeColor get() = themeColors.accentOrange
    private val accentPinkColor get() = themeColors.accentPink
    private val militaryGrayColor get() = themeColors.military
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val executor = Executors.newFixedThreadPool(4)
    private val tileCache = linkedMapOf<String, Bitmap>()
    private val requestedTiles = mutableSetOf<String>()
    private val aircraft = mutableListOf<Aircraft>()
    private val aircraftAppearances = mutableMapOf<String, AircraftAppearance>()
    private val flightTraceClient = FlightTraceClient(USER_AGENT)
    private val aircraftFeedClient = AircraftFeedClient(USER_AGENT)
    private val aircraftDetailsClient = AircraftDetailsClient(USER_AGENT)
    private val flightPathRequests = mutableSetOf<String>()

    private var locationPermissionGranted = false
    private var latestLocation: Location? = null
    private var zoom = readStoredZoom()
    private var units = UnitSystem.valueOf(prefs.getString(FlightAlertSettings.KEY_UNITS, UnitSystem.IMPERIAL.name) ?: UnitSystem.IMPERIAL.name)
    private var mapSource = readMapSource()
    private var mapLabelsEnabled = prefs.getBoolean(FlightAlertSettings.KEY_MAP_LABELS_ENABLED, FlightAlertSettings.DEFAULT_MAP_LABELS_ENABLED)
    private var alertsEnabled = prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true)
    private var alertDistanceFeet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, FlightAlertSettings.DEFAULT_ALERT_DISTANCE_FEET)
    private var alertAltitudeFeet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, FlightAlertSettings.DEFAULT_ALERT_ALTITUDE_FEET)
    private var priorityTrackingEnabled = prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
    private var priorityRangeFeet = prefs.getFloat(FlightAlertSettings.KEY_PRIORITY_RANGE_FEET, FlightAlertSettings.DEFAULT_PRIORITY_RANGE_FEET)
    private var priorityRangeCircleVisible = prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_RANGE_CIRCLE_VISIBLE, FlightAlertSettings.DEFAULT_PRIORITY_RANGE_CIRCLE_VISIBLE)
    private var filterSearchQuery = sanitizeFilterSearch(prefs.getString(FlightAlertSettings.KEY_FILTER_SEARCH_QUERY, "").orEmpty())
    private var aircraftTypeFilter = readAircraftTypeFilter()
    private var altitudeFilter = readAltitudeFilter()
    private var distanceFilter = readDistanceFilter()
    private var flightStatusFilter = readFlightStatusFilter()
    private var reportAgeFilter = readReportAgeFilter()
    private var alertVolumeFilter = prefs.getBoolean(FlightAlertSettings.KEY_FILTER_ALERT_VOLUME, false)
    private var settingsOpen = false
    private var mapLabelsOpen = false
    private var priorityTrackerOpen = false
    private var filtersOpen = false
    private var filterSearchFocused = false
    private var detailsOpen = false
    private var usageOpen = false
    private var environmentalImpactOpen = false
    private var impactMethodologyOpen = false
    private var aircraftDetails: AircraftDetails? = null
    private var aircraftDetailsStatus = "Select aircraft"
    private var aircraftPhoto: Bitmap? = null
    private var aircraftPhotoStatus = "Photo unavailable"
    private var aircraftPhotoEvidence: PhotoEvidence? = null
    private var photoEvidenceOpen = false
    private var detailsRequestToken = 0L
    private var aircraftFetchInFlight = false
    private var aircraftRefreshScheduled = false
    private var scheduledAircraftRefreshForce = false
    private var lastAircraftFetchMs = 0L
    private var lastTickerFetchMs = 0L
    private var lastAircraftDataEpochSec: Double? = null
    private var aircraftRefreshWaitingForViewport = false
    private var markerDotBlend = 0f
    private var lastMarkerBlendFrameMs = 0L
    private var aircraftStatus = "Waiting for location"
    private var mapStatus = "Waiting for location"
    private var followingLocation = true
    private var manualCenterLat: Double? = null
    private var manualCenterLon: Double? = null
    private var selectedAircraftId: String? = null
    private var selectedAircraftSnapshot: Aircraft? = null
    private var selectedFlightPathAircraftId: String? = null
    private var selectedFlightPath: FlightTrace? = null
    private var selectedFlightPathVisible = false
    private var selectedPreviousFlightsVisible = false
    private var selectedPathFitRequested = false
    private var militaryOriginAircraftId: String? = null
    private var militaryOriginStatus = "Unavailable"
    private var militaryOriginRequestKey: String? = null
    private var downX = 0f
    private var downY = 0f
    private var detailsScrollY = 0f
    private var detailsMaxScrollY = 0f
    private var detailsScrollStartY = 0f
    private var detailsScrollStartOffset = 0f
    private var detailsRowsVisibleTop = 0f
    private var detailsRowsVisibleBottom = Float.MAX_VALUE
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
        setBackgroundColor(mapEmptyColor)
        updateHostSystemBars()
        applyThemeTypeface()
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
        if (photoEvidenceOpen) {
            photoEvidenceOpen = false
            invalidate()
            return true
        }
        if (priorityTrackerOpen) {
            priorityTrackerOpen = false
            invalidate()
            return true
        }
        if (filtersOpen) {
            filtersOpen = false
            clearFilterSearchFocus()
            invalidate()
            return true
        }
        if (mapLabelsOpen) {
            mapLabelsOpen = false
            invalidate()
            return true
        }
        if (detailsOpen) {
            if (environmentalImpactOpen) {
                environmentalImpactOpen = false
                invalidate()
                return true
            }
            if (usageOpen) {
                usageOpen = false
                invalidate()
                return true
            }
            detailsOpen = false
            photoEvidenceOpen = false
            usageOpen = false
            environmentalImpactOpen = false
            invalidate()
            return true
        }
        if (settingsOpen) {
            if (impactMethodologyOpen) {
                impactMethodologyOpen = false
                invalidate()
                return true
            }
            settingsOpen = false
            impactMethodologyOpen = false
            invalidate()
            return true
        }
        if (selectedFlightPathVisible) {
            selectedFlightPathVisible = false
            selectedPreviousFlightsVisible = false
            selectedPathFitRequested = false
            invalidate()
            return true
        }
        if (!followingLocation && latestLocation != null) {
            recenterOnLocation()
            return true
        }
        return false
    }

    override fun onLocationChanged(location: Location) {
        latestLocation = location
        mapStatus = "Loading map"
        applyInitialMavicRangeZoomIfNeeded()
        requestVisibleAircraftIfNeeded(force = true)
        invalidate()
    }


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
        paint.color = themeColors.systemBar
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Draw and hit-test inside system-bar-safe content so Android chrome never covers controls.
        val w = contentWidth()
        val h = contentHeight()
        val location = latestLocation

        canvas.withTranslation(safeInsetLeft, safeInsetTop) {
            clipRect(0f, 0f, w, h)
            paint.color = mapEmptyColor
            drawRect(0f, 0f, w, h, paint)

            if (location == null) {
                drawNoLocationState(this, w, h)
            } else {
                val viewport = viewportFor(location, w, h)
                drawMapTiles(this, viewport)
                drawPriorityRangeCircle(this, viewport, location)
                drawSelectedFlightPath(this, viewport)
                drawAircraft(this, viewport)
                drawOwnship(this, viewport, location)
                drawPriorityRangeCircle(this, viewport, location, outlineOnly = true)
            }

            drawTopStatus(this, w, h)
            drawRecenterButton(this, w, h)
            location?.let { drawFlightPathButtons(this, viewportFor(it, w, h), w, h) }
            drawSettingsButton(this, w, h)
            drawFiltersButton(this, w, h)
            drawTrafficPanel(this, w, h)

            if (detailsOpen) {
                drawAircraftDetailsPanel(this, w, h)
            }
            if (settingsOpen) {
                if (mapLabelsOpen) {
                    drawMapLabelsPanel(this, w, h)
                } else if (impactMethodologyOpen) {
                    drawImpactMethodologyPanel(this, w, h)
                } else {
                    drawSettingsPanel(this, w, h)
                }
            }
            if (priorityTrackerOpen) {
                drawPriorityTrackerPanel(this, w, h)
            }
            if (filtersOpen) {
                drawFiltersPanel(this, w, h)
            }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = contentX(event.x)
        val y = contentY(event.y)
        if (!settingsOpen && !detailsOpen && !priorityTrackerOpen && !filtersOpen && event.pointerCount >= 2) {
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
                detailsScrollStartY = y
                detailsScrollStartOffset = detailsScrollY
                dragStarted = false
                dragBlocked = isOverlayOrControlHit(x, y)
                dragStartCenter = latestLocation?.let { viewportFor(it, contentWidth(), contentHeight()) }?.let {
                    WorldPoint(it.centerX, it.centerY)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (detailsOpen && !settingsOpen && !priorityTrackerOpen && !filtersOpen && detailsMaxScrollY > 0f) {
                    val dy = y - detailsScrollStartY
                    if (!dragStarted && abs(dy) > dp(6)) dragStarted = true
                    if (dragStarted) {
                        detailsScrollY = (detailsScrollStartOffset - dy).coerceIn(0f, detailsMaxScrollY)
                        postInvalidateOnAnimation()
                    }
                    return true
                }
                if (!settingsOpen && !detailsOpen && !priorityTrackerOpen && !filtersOpen && !dragBlocked && dragStartCenter != null && latestLocation != null) {
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

    override fun onCheckIsTextEditor(): Boolean {
        return filtersOpen && filterSearchFocused
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!filtersOpen || !filterSearchFocused) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEARCH
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                appendFilterSearchText(text?.toString().orEmpty())
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                deleteFilterSearchCharacter()
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                return handleFilterSearchKey(event.keyCode, event)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                if (actionCode == EditorInfo.IME_ACTION_SEARCH || actionCode == EditorInfo.IME_ACTION_DONE) {
                    submitFilterSearch()
                    return true
                }
                return super.performEditorAction(actionCode)
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isPointerScroll = event.action == MotionEvent.ACTION_SCROLL &&
            (event.source and InputDevice.SOURCE_CLASS_POINTER) != 0
        if (!settingsOpen && !detailsOpen && !priorityTrackerOpen && !filtersOpen && isPointerScroll && latestLocation != null) {
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
        if (filtersOpen && filterSearchFocused && handleFilterSearchKey(keyCode, event)) {
            return true
        }
        if (!settingsOpen && !detailsOpen && !priorityTrackerOpen && !filtersOpen && latestLocation != null) {
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
        prefs.edit { putFloat(FlightAlertSettings.KEY_ZOOM, zoom.toFloat()) }
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
                val cutoutInsets = if (cutout != null && hasNonHolePunchCutout(cutout.boundingRects)) cutout else null
                updateSafeInsets(
                    max(bars.left, cutoutInsets?.safeInsetLeft ?: 0),
                    max(bars.top, cutoutInsets?.safeInsetTop ?: 0),
                    max(bars.right, cutoutInsets?.safeInsetRight ?: 0),
                    max(bars.bottom, cutoutInsets?.safeInsetBottom ?: 0)
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
        paint.color = mapEmptyColor
        canvas.drawRect(0f, 0f, viewport.width, viewport.height, paint)

        val leftWorld = viewport.centerX - viewport.width / 2.0
        val topWorld = viewport.centerY - viewport.height / 2.0
        val tileZoom = viewport.zoom.toInt().coerceIn(MIN_ZOOM, mapSource.maxNativeZoom)
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
            if (ty !in 0 until maxTile) continue
            for (txRaw in firstTileX..lastTileX) {
                val tx = ((txRaw % maxTile) + maxTile) % maxTile
                val key = "${mapTileCacheKey()}/$tileZoom/$tx/$ty"
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
        val labelNote = if (mapSource == TileSource.STREET && !mapLabelsEnabled) " no-label" else ""
        mapStatus = if (requested == 0 && loaded > 0) {
            "${mapSource.displayName}$labelNote loaded"
        } else {
            "Loading ${mapSource.displayName.lowercase(Locale.US)}$labelNote tiles"
        }
    }

    private fun drawTilePlaceholder(canvas: Canvas, x: Float, y: Float, size: Float) {
        paint.style = Paint.Style.FILL
        paint.color = panelAltColor
        canvas.drawRect(x, y, x + size, y + size, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(10)
        textPaint.color = withAlpha(textColor, 170)
        canvas.drawText("Loading map", x + size / 2f, y + size / 2f, textPaint)
    }

    private fun shouldDrawPriorityRangeCircle(): Boolean {
        return priorityTrackerOpen || (alertsEnabled && priorityRangeCircleVisible)
    }

    private fun drawPriorityRangeCircle(canvas: Canvas, viewport: Viewport, location: Location, outlineOnly: Boolean = false) {
        if (!shouldDrawPriorityRangeCircle()) return
        val ownship = latLonToWorld(location.latitude, location.longitude, viewport.zoom)
        val cx = (ownship.x - viewport.centerX + viewport.width / 2.0).toFloat()
        val cy = (ownship.y - viewport.centerY + viewport.height / 2.0).toFloat()
        val metersPerPixel = metersPerPixelAt(location.latitude, viewport.zoom).coerceAtLeast(0.01)
        val radiusPx = (feetToMeters(alertDistanceFeet.toDouble()) / metersPerPixel).toFloat()
        if (radiusPx <= 1f) return

        val previewOnly = !alertsEnabled || !priorityRangeCircleVisible
        if (!outlineOnly) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(if (previewOnly) 18 else 26, Color.red(accentGreenColor), Color.green(accentGreenColor), Color.blue(accentGreenColor))
            canvas.drawCircle(cx, cy, radiusPx, paint)
        }
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(if (outlineOnly) 2.2f else 1.8f)
        val strokeAlpha = when {
            outlineOnly && previewOnly -> 155
            outlineOnly -> 230
            previewOnly -> 130
            else -> 185
        }
        strokePaint.color = Color.argb(strokeAlpha, Color.red(accentGreenColor), Color.green(accentGreenColor), Color.blue(accentGreenColor))
        canvas.drawCircle(cx, cy, radiusPx, strokePaint)
    }

    private fun drawSelectedFlightPath(canvas: Canvas, viewport: Viewport) {
        val segments = selectedPathSegments(visibleOnly = true) ?: return
        if (selectedPreviousFlightsVisible) drawPreviousFlightPaths(canvas, viewport)

        iconPath.reset()
        val worldSpan = TILE_SIZE * 2.0.pow(viewport.zoom)
        var hasDrawableSegment = false
        segments.forEach { segment ->
            var previousScreenX: Float? = null
            segment.points.forEachIndexed { index, point ->
                val world = latLonToWorld(point.lat, point.lon, viewport.zoom)
                var sx = (world.x - viewport.centerX + viewport.width / 2.0).toFloat()
                val sy = (world.y - viewport.centerY + viewport.height / 2.0).toFloat()
                previousScreenX?.let { lastX ->
                    val span = worldSpan.toFloat()
                    while (sx - lastX > span / 2f) sx -= span
                    while (lastX - sx > span / 2f) sx += span
                }
                if (index == 0) {
                    iconPath.moveTo(sx, sy)
                } else {
                    iconPath.lineTo(sx, sy)
                    hasDrawableSegment = true
                }
                previousScreenX = sx
            }
        }
        if (!hasDrawableSegment) return
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = dp(5)
        strokePaint.color = withAlpha(themeColors.pathShadow, 90)
        canvas.drawPath(iconPath, strokePaint)
        strokePaint.strokeWidth = dp(2.4f)
        strokePaint.color = accentYellowColor
        canvas.drawPath(iconPath, strokePaint)
        drawSelectedPathProjection(canvas, viewport)
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.strokeJoin = Paint.Join.MITER
        strokePaint.pathEffect = null
    }

    private fun drawPreviousFlightPaths(canvas: Canvas, viewport: Viewport) {
        val segments = previousFlightSegments(visibleOnly = true) ?: return
        iconPath.reset()
        val worldSpan = TILE_SIZE * 2.0.pow(viewport.zoom)
        var hasDrawableSegment = false
        segments.forEach { segment ->
            var previousScreenX: Float? = null
            segment.points.forEachIndexed { index, point ->
                val world = latLonToWorld(point.lat, point.lon, viewport.zoom)
                var sx = (world.x - viewport.centerX + viewport.width / 2.0).toFloat()
                val sy = (world.y - viewport.centerY + viewport.height / 2.0).toFloat()
                previousScreenX?.let { lastX ->
                    val span = worldSpan.toFloat()
                    while (sx - lastX > span / 2f) sx -= span
                    while (lastX - sx > span / 2f) sx += span
                }
                if (index == 0) {
                    iconPath.moveTo(sx, sy)
                } else {
                    iconPath.lineTo(sx, sy)
                    hasDrawableSegment = true
                }
                previousScreenX = sx
            }
        }
        if (!hasDrawableSegment) return
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.pathEffect = DashPathEffect(floatArrayOf(dp(8), dp(8)), 0f)
        strokePaint.strokeWidth = dp(4.2f)
        strokePaint.color = withAlpha(themeColors.pathShadow, 72)
        canvas.drawPath(iconPath, strokePaint)
        strokePaint.strokeWidth = dp(1.9f)
        strokePaint.color = withAlpha(accentBlueColor, 190)
        canvas.drawPath(iconPath, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.strokeJoin = Paint.Join.MITER
        strokePaint.pathEffect = null
    }

    private fun drawSelectedPathProjection(canvas: Canvas, viewport: Viewport) {
        val points = selectedSegmentPoints(visibleOnly = true) ?: return
        val last = points.maxByOrNull { it.epochSec } ?: return
        val projected = selectedPathProjectedEndpoint() ?: return
        val distance = distanceMeters(last.lat, last.lon, projected.lat, projected.lon)
        if (distance < MIN_PROJECTED_PATH_CONNECTOR_M) return

        val start = latLonToWorld(last.lat, last.lon, viewport.zoom)
        val end = latLonToWorld(projected.lat, projected.lon, viewport.zoom)
        val worldSpan = TILE_SIZE * 2.0.pow(viewport.zoom)
        val startX = (start.x - viewport.centerX + viewport.width / 2.0).toFloat()
        var endX = (end.x - viewport.centerX + viewport.width / 2.0).toFloat()
        while (endX - startX > worldSpan / 2.0) endX -= worldSpan.toFloat()
        while (startX - endX > worldSpan / 2.0) endX += worldSpan.toFloat()
        val startY = (start.y - viewport.centerY + viewport.height / 2.0).toFloat()
        val endY = (end.y - viewport.centerY + viewport.height / 2.0).toFloat()

        iconPath.reset()
        iconPath.moveTo(startX, startY)
        iconPath.lineTo(endX, endY)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.pathEffect = DashPathEffect(floatArrayOf(dp(9), dp(7)), 0f)
        strokePaint.strokeWidth = dp(2.2f)
        strokePaint.color = withAlpha(accentYellowColor, 185)
        canvas.drawPath(iconPath, strokePaint)
        strokePaint.pathEffect = null
    }

    private fun getTileBitmap(z: Int, x: Int, y: Int): Bitmap? {
        val key = "${mapTileCacheKey()}/$z/$x/$y"
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
                val url = httpsUrl(mapSource.tileUrl(z, x, y, mapLabelsEnabled)) ?: run {
                    mapStatus = "Map tiles unavailable"
                    return@execute
                }
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

    private fun mapTileCacheKey(): String {
        return mapSource.cacheKey(mapLabelsEnabled)
    }

    private fun tileFile(z: Int, x: Int, y: Int): File {
        return File(context.cacheDir, "${mapTileCacheKey()}_tiles/$z/$x/$y.png")
    }

    private fun requestVisibleAircraftIfNeeded(force: Boolean = false) {
        val location = latestLocation ?: return
        if (!hasUsableViewport()) {
            aircraftRefreshWaitingForViewport = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (aircraftFetchInFlight) {
            scheduleVisibleAircraftRefresh(AIRCRAFT_IN_FLIGHT_RETRY_MS, force)
            return
        }
        val minFetchIntervalMs = if (force) AIRCRAFT_FORCE_REFRESH_MS else AIRCRAFT_REFRESH_MS
        if (now - lastAircraftFetchMs < minFetchIntervalMs) {
            if (force) scheduleVisibleAircraftRefresh(minFetchIntervalMs - (now - lastAircraftFetchMs), true)
            return
        }
        aircraftFetchInFlight = true
        lastAircraftFetchMs = now

        val bounds = aircraftBoundsForCurrentViewport(location)
        val exactSearch = filterSearchQuery.takeIf { it.isNotBlank() }
        executor.execute {
            try {
                val result = aircraftFeedClient.fetchAircraft(bounds.toFeedBounds(), location.latitude, location.longitude, exactSearch)
                if (result.status == FeedStatus.OK) {
                    val parsed = result.aircraft.map { it.toMapAircraft() }
                    updateAircraftAppearances(parsed)
                    selectedAircraftId?.let { selectedId ->
                        parsed.firstOrNull { it.icao24 == selectedId }?.let { selectedAircraftSnapshot = it }
                    }
                    pruneSelectionForFilters()
                    val coverage = feedCoverageLabel(result.queryCount, result.partialCoverage)
                    Log.d(TAG, "Aircraft feed ${result.source.displayName}: ${parsed.size} aircraft$coverage")
                    synchronized(aircraft) {
                        aircraft.clear()
                        aircraft.addAll(parsed)
                    }
                    lastAircraftDataEpochSec = result.epochSec
                    aircraftStatus = if (parsed.isEmpty()) {
                        "No aircraft reported in current map area (${result.source.displayName}$coverage)"
                    } else {
                        "Live aircraft updated via ${result.source.displayName}$coverage"
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
                postInvalidateOnAnimation()
            }
        }
    }

    private fun scheduleVisibleAircraftRefresh(delayMs: Long, force: Boolean) {
        scheduledAircraftRefreshForce = scheduledAircraftRefreshForce || force
        if (aircraftRefreshScheduled) return
        aircraftRefreshScheduled = true
        postDelayed({
            val shouldForce = scheduledAircraftRefreshForce
            aircraftRefreshScheduled = false
            scheduledAircraftRefreshForce = false
            requestVisibleAircraftIfNeeded(force = shouldForce)
        }, delayMs.coerceAtLeast(0L))
    }

    private fun requestDeferredAircraftRefresh() {
        if (!aircraftRefreshWaitingForViewport || latestLocation == null || !hasUsableViewport()) return
        aircraftRefreshWaitingForViewport = false
        requestVisibleAircraftIfNeeded(force = true)
    }

    private fun feedCoverageLabel(queryCount: Int, partialCoverage: Boolean): String {
        return when {
            queryCount > 1 && partialCoverage -> " ($queryCount areas, partial wide-area coverage)"
            queryCount > 1 -> " ($queryCount areas)"
            partialCoverage -> " (partial wide-area coverage)"
            else -> ""
        }
    }

    private fun updateAircraftAppearances(nextAircraft: List<Aircraft>) {
        val now = SystemClock.elapsedRealtime()
        synchronized(aircraftAppearances) {
            val activeKeys = nextAircraft.mapTo(mutableSetOf()) { it.appearanceKey() }
            aircraftAppearances.keys.retainAll(activeKeys)
            val newAircraft = nextAircraft.filter { it.appearanceKey() !in aircraftAppearances }
            newAircraft.forEach { item ->
                val key = item.appearanceKey()
                aircraftAppearances[key] = AircraftAppearance(
                    firstSeenMs = now,
                    delayMs = 0L
                )
            }
        }
    }

    private fun aircraftAppearanceProgress(aircraft: Aircraft): Float {
        val appearance = synchronized(aircraftAppearances) { aircraftAppearances[aircraft.appearanceKey()] } ?: return 1f
        val elapsed = SystemClock.elapsedRealtime() - appearance.firstSeenMs - appearance.delayMs
        return smoothStep(0f, AIRCRAFT_APPEAR_DURATION_MS.toFloat(), elapsed.toFloat())
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
                val selectedAircraft = selectedAircraftSnapshot?.takeIf { it.icao24.lowercase(Locale.US) == key }
                val livePoint = selectedAircraft?.toTrackPoint()
                val trace = flightTraceClient.fetchFlightTrace(
                    icao24 = key,
                    livePoint = livePoint,
                    typeCode = selectedAircraft?.typeCode,
                    category = selectedAircraft?.category
                )
                post {
                    if (selectedAircraftId?.lowercase(Locale.US) == key) {
                        selectedFlightPathAircraftId = if (trace != null) key else null
                        selectedFlightPath = trace
                        selectedFlightPathVisible = false
                        selectedPreviousFlightsVisible = false
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
                        selectedPreviousFlightsVisible = false
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
        var combined = this
        if (alertsEnabled) {
            combined = combined.union(aircraftBoundsAroundLocation(location, feetToMeters(alertDistanceFeet.toDouble())))
        }
        if (priorityTrackingEnabled) {
            combined = combined.union(aircraftBoundsAroundLocation(location, feetToMeters(priorityRangeFeet.toDouble())))
        }
        return combined
    }

    private fun Bounds.union(other: Bounds): Bounds {
        return Bounds(
            minLat = min(minLat, other.minLat),
            minLon = min(minLon, other.minLon),
            maxLat = max(maxLat, other.maxLat),
            maxLon = max(maxLon, other.maxLon)
        )
    }

    private fun drawAircraft(canvas: Canvas, viewport: Viewport) {
        val snapshot = visibleAircraftSnapshot()
        val markerBlend = smoothedAircraftMarkerDotBlend(snapshot, viewport)
        val labeled = snapshot.take(labelAircraftCount(markerBlend)).toSet()
        val selectedId = selectedAircraftId
        for (item in snapshot) {
            val estimated = displayAircraftPosition(item)
            val point = latLonToWorld(estimated.lat, estimated.lon, viewport.zoom)
            val sx = (point.x - viewport.centerX + viewport.width / 2.0).toFloat()
            val sy = (point.y - viewport.centerY + viewport.height / 2.0).toFloat()
            if (sx < -dp(32) || sx > viewport.width + dp(32) || sy < -dp(32) || sy > viewport.height + dp(32)) continue
            drawAircraftIcon(canvas, sx, sy, item, item.icao24 == selectedId, markerBlend, aircraftAppearanceProgress(item))
            if (labeled.contains(item)) {
                drawAircraftLabel(canvas, sx, sy, item)
            }
        }
    }

    private fun drawAircraftIcon(canvas: Canvas, x: Float, y: Float, aircraft: Aircraft, selected: Boolean, markerBlend: Float, appearProgress: Float) {
        val blend = markerBlend.coerceIn(0f, 1f)
        val appear = appearProgress.coerceIn(0f, 1f)
        val shapeProgress = smoothStep(0f, 1f, 1f - blend)
        val enterScale = 0.18f + 0.82f * appear
        val alpha = (appear * 255).toInt().coerceIn(0, 255)
        val symbol = aircraftSymbol(aircraft)
        val typeScale = aircraftSizeMultiplier(aircraft, symbol)
        val iconScale = max(aircraftIconScale(), AIRCRAFT_DOT_SCALE_FLOOR * blend) * typeScale * enterScale
        val color = aircraftColor(aircraft)
        if (alpha > 4) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(scrimColor, (74 * appear).toInt().coerceIn(0, 74))
            canvas.drawCircle(
                x + dp(2f + 1f * shapeProgress) * iconScale,
                y + dp(2.5f + 1.5f * shapeProgress) * iconScale,
                dp(5f + 11f * shapeProgress) * iconScale,
                paint
            )
            if (selected) {
                strokePaint.color = Color.argb((235 * appear).toInt().coerceIn(0, 235), Color.red(accentGreenColor), Color.green(accentGreenColor), Color.blue(accentGreenColor))
                strokePaint.strokeWidth = dp(2.6f)
                canvas.drawCircle(x, y, dp(11f + 13f * shapeProgress) * iconScale, strokePaint)
            }

            canvas.withTranslation(x, y) {
                scale(iconScale, iconScale)
                if (aircraft.trackDeg != null && symbol != AircraftSymbol.SURFACE) {
                    rotate(aircraft.trackDeg.toFloat())
                }
                paint.color = withAlpha(color, alpha)
                strokePaint.color = withAlpha(scrimColor, (235 * appear).toInt().coerceIn(0, 235))
                strokePaint.strokeWidth = dp(1.2f)
                when (symbol) {
                    AircraftSymbol.ROTORCRAFT -> drawRotorcraftSymbol(this, shapeProgress)
                    AircraftSymbol.GLIDER -> drawGliderSymbol(this, shapeProgress)
                    AircraftSymbol.UAV -> drawUavSymbol(this, shapeProgress)
                    AircraftSymbol.SURFACE -> drawSurfaceSymbol(this, shapeProgress)
                    AircraftSymbol.AIRLINER -> drawAirlinerSymbol(this, shapeProgress)
                    AircraftSymbol.GENERAL_AVIATION -> drawGeneralAviationSymbol(this, shapeProgress)
                }
            }
        }
        paint.alpha = 255
        strokePaint.alpha = 255
    }

    private fun aircraftIconScale(): Float {
        val zoomProgress = smoothStep(AIRCRAFT_SCALE_ZOOM_MIN, AIRCRAFT_SCALE_ZOOM_MAX, zoom.toFloat())
        return AIRCRAFT_SCALE_MIN + (AIRCRAFT_SCALE_MAX - AIRCRAFT_SCALE_MIN) * zoomProgress
    }

    private fun aircraftMarkerDotBlend(count: Int, viewport: Viewport): Float {
        val zoomDotBlend = 1f - smoothStep(AIRCRAFT_DOT_ZOOM_FULL, AIRCRAFT_DOT_ZOOM_SYMBOL, zoom.toFloat())
        val densityPerTenThousandPx = count / max(1f, viewport.width * viewport.height / 10000f)
        val densityDotBlend = smoothStep(AIRCRAFT_DOT_DENSITY_START, AIRCRAFT_DOT_DENSITY_FULL, densityPerTenThousandPx)
        val combinedBlend = 1f - (1f - zoomDotBlend) * (1f - densityDotBlend)
        return smoothStep(0f, 1f, combinedBlend)
    }

    private fun smoothedAircraftMarkerDotBlend(snapshot: List<Aircraft>, viewport: Viewport): Float {
        val visibleCount = visibleAircraftCount(snapshot, viewport)
        val target = aircraftMarkerDotBlend(visibleCount, viewport)
        val now = SystemClock.elapsedRealtime()
        val last = lastMarkerBlendFrameMs
        lastMarkerBlendFrameMs = now
        if (last == 0L) {
            markerDotBlend = target
            return markerDotBlend
        }

        val dt = (now - last).coerceIn(0L, 50L).toFloat() / 1000f
        val maxStep = AIRCRAFT_MARKER_BLEND_UNITS_PER_SEC * dt
        markerDotBlend = when {
            target > markerDotBlend -> min(target, markerDotBlend + maxStep)
            target < markerDotBlend -> max(target, markerDotBlend - maxStep)
            else -> markerDotBlend
        }
        if (abs(markerDotBlend - target) > 0.001f) {
            postInvalidateOnAnimation()
        }
        return markerDotBlend
    }

    private fun visibleAircraftCount(snapshot: List<Aircraft>, viewport: Viewport): Int {
        var count = 0
        snapshot.forEach { item ->
            val estimated = estimatedAircraftPosition(item)
            val point = latLonToWorld(estimated.lat, estimated.lon, viewport.zoom)
            val sx = (point.x - viewport.centerX + viewport.width / 2.0).toFloat()
            val sy = (point.y - viewport.centerY + viewport.height / 2.0).toFloat()
            if (sx >= -dp(32) && sx <= viewport.width + dp(32) && sy >= -dp(32) && sy <= viewport.height + dp(32)) {
                count++
            }
        }
        return count
    }

    private fun labelAircraftCount(markerBlend: Float): Int {
        if (markerBlend > 0.35f) return 0
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
        val labelX = x + dp(20)
        val titleY = y - dp(8)
        val detailY = y + dp(11)

        if (mapSource == TileSource.STREET) {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = true
            textPaint.textSize = sp(14)
            val callsignWidth = textPaint.measureText(callsign)
            textPaint.isFakeBoldText = false
            textPaint.textSize = sp(12)
            val detailWidth = textPaint.measureText(detail)
            val chip = RectF(
                labelX - dp(7),
                titleY - dp(17),
                labelX + max(callsignWidth, detailWidth) + dp(8),
                detailY + dp(7)
            )
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(panelAltColor, if (themeStyle.treatment == ThemeTreatment.PLAIN) 214 else themeStyle.controlAlpha)
            val chipRadius = if (themeStyle.treatment == ThemeTreatment.PLAIN) dp(5) else controlRadius().coerceAtMost(dp(8))
            canvas.drawRoundRect(chip, chipRadius, chipRadius, paint)
            strokePaint.strokeWidth = dp(0.8f)
            strokePaint.color = withAlpha(panelStrokeColor, if (themeStyle.treatment == ThemeTreatment.PLAIN) 150 else themeStyle.dividerAlpha + 50)
            canvas.drawRoundRect(chip, chipRadius, chipRadius, strokePaint)
        } else {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = true
            textPaint.textSize = sp(14)
            textPaint.color = withAlpha(scrimColor, 210)
            canvas.drawText(callsign, x + dp(22), y - dp(6), textPaint)
            textPaint.textSize = sp(12)
            canvas.drawText(detail, x + dp(22), y + dp(13), textPaint)
        }

        textPaint.textSize = sp(14)
        textPaint.isFakeBoldText = true
        textPaint.color = if (mapSource == TileSource.STREET) themeColors.streetLabelText else textColor
        canvas.drawText(callsign, labelX, titleY, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = if (mapSource == TileSource.STREET) themeColors.streetLabelMuted else withAlpha(textColor, 230)
        canvas.drawText(detail, labelX, detailY, textPaint)
    }

    private fun visibleAircraftSnapshot(): List<Aircraft> {
        val snapshot = filteredAircraftSnapshot()
        if (!selectedFlightPathVisible || !hasSelectedFlightPath()) return snapshot.withSelectedFallback()
        val selectedId = selectedAircraftId?.lowercase(Locale.US) ?: return snapshot
        return snapshot.filter { item ->
            item.icao24.lowercase(Locale.US) == selectedId || isExtremePriority(item)
        }.withSelectedFallback()
    }

    private fun List<Aircraft>.withSelectedFallback(): List<Aircraft> {
        if (filtersActive()) return this
        val selected = selectedAircraftSnapshot ?: return this
        if (selectedAircraftId == null) return this
        if (any { it.icao24 == selected.icao24 }) return this
        return listOf(selected) + this
    }

    private fun drawMorphedPolygon(canvas: Canvas, target: List<ScreenPoint>, progress: Float) {
        val p = smoothStep(0f, 1f, progress.coerceIn(0f, 1f))
        iconPath.reset()
        target.forEachIndexed { index, point ->
            val angle = atan2(point.y.toDouble(), point.x.toDouble())
            val startX = (cos(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            val startY = (sin(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            val x = lerp(startX, point.x, p)
            val y = lerp(startY, point.y, p)
            if (index == 0) {
                iconPath.moveTo(dp(x), dp(y))
            } else {
                iconPath.lineTo(dp(x), dp(y))
            }
        }
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        canvas.drawPath(iconPath, strokePaint)
    }

    private fun drawGeneralAviationSymbol(canvas: Canvas, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val target = listOf(
            ScreenPoint(0f, -17f),
            ScreenPoint(3.2f, -4.2f),
            ScreenPoint(17f, 0.8f),
            ScreenPoint(15.2f, 4.4f),
            ScreenPoint(3.3f, 3.4f),
            ScreenPoint(2.2f, 13.8f),
            ScreenPoint(8.4f, 17.2f),
            ScreenPoint(0f, 12.7f),
            ScreenPoint(-8.4f, 17.2f),
            ScreenPoint(-2.2f, 13.8f),
            ScreenPoint(-3.3f, 3.4f),
            ScreenPoint(-15.2f, 4.4f),
            ScreenPoint(-17f, 0.8f),
            ScreenPoint(-3.2f, -4.2f)
        )
        drawMorphedPolygon(canvas, target, p)
    }

    private fun drawAirlinerSymbol(canvas: Canvas, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val target = listOf(
            ScreenPoint(0f, -21f),
            ScreenPoint(5.5f, -4.8f),
            ScreenPoint(23.5f, 0.8f),
            ScreenPoint(21.5f, 5.8f),
            ScreenPoint(7.2f, 6.2f),
            ScreenPoint(5.1f, 16.5f),
            ScreenPoint(13.5f, 21f),
            ScreenPoint(2.8f, 17.2f),
            ScreenPoint(0f, 13.5f),
            ScreenPoint(-2.8f, 17.2f),
            ScreenPoint(-13.5f, 21f),
            ScreenPoint(-5.1f, 16.5f),
            ScreenPoint(-7.2f, 6.2f),
            ScreenPoint(-21.5f, 5.8f),
            ScreenPoint(-23.5f, 0.8f),
            ScreenPoint(-5.5f, -4.8f)
        )
        drawMorphedPolygon(canvas, target, p)

        val engine = smoothStep(0.48f, 1f, p)
        if (engine > 0f) {
            canvas.drawCircle(-dp(11.8f * engine), dp(5.6f * engine), dp(2.2f * engine), strokePaint)
            canvas.drawCircle(dp(11.8f * engine), dp(5.6f * engine), dp(2.2f * engine), strokePaint)
        }
    }

    private fun drawRotorcraftSymbol(canvas: Canvas, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val body = smoothStep(0f, 0.55f, p)
        val rotor = smoothStep(0.25f, 1f, p)
        val tail = smoothStep(0.45f, 1f, p)
        val bodyRect = RectF(-dp(4f + 4f * body), -dp(4f + 3f * body), dp(4f + 5f * body), dp(4f + 4f * body))
        canvas.drawOval(bodyRect, paint)
        canvas.drawOval(bodyRect, strokePaint)
        strokePaint.strokeWidth = dp(2.5f)
        canvas.drawLine(-dp(5f + 19f * rotor), 0f, dp(5f + 19f * rotor), 0f, strokePaint)
        canvas.drawLine(0f, -dp(5f + 17f * rotor), 0f, dp(5f + 17f * rotor), strokePaint)
        strokePaint.strokeWidth = dp(2)
        canvas.drawLine(dp(5f + 4f * body), dp(1), dp(7f + 16f * tail), dp(4f + 5f * tail), strokePaint)
        canvas.drawLine(dp(8f + 13f * tail), dp(3f + 2f * tail), dp(9f + 16f * tail), dp(5f + 8f * tail), strokePaint)
        strokePaint.strokeWidth = dp(1.2f)
    }

    private fun drawGliderSymbol(canvas: Canvas, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val target = listOf(
            ScreenPoint(0f, -16f),
            ScreenPoint(3f, 2f),
            ScreenPoint(27f, 5f),
            ScreenPoint(4f, 8f),
            ScreenPoint(1.8f, 17f),
            ScreenPoint(-1.8f, 17f),
            ScreenPoint(-4f, 8f),
            ScreenPoint(-27f, 5f),
            ScreenPoint(-3f, 2f)
        )
        drawMorphedPolygon(canvas, target, p)
    }

    private fun drawUavSymbol(canvas: Canvas, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val body = smoothStep(0f, 0.55f, p)
        val arms = smoothStep(0.2f, 1f, p)
        val rotors = smoothStep(0.55f, 1f, p)
        iconPath.reset()
        iconPath.moveTo(0f, -dp(5f + 8f * body))
        iconPath.lineTo(dp(3f + 5f * body), 0f)
        iconPath.lineTo(0f, dp(5f + 8f * body))
        iconPath.lineTo(-dp(3f + 5f * body), 0f)
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        canvas.drawPath(iconPath, strokePaint)
        strokePaint.strokeWidth = dp(2)
        val armInner = dp(4f + 2f * body)
        val armOuter = dp(7f + 11f * arms)
        canvas.drawLine(-armInner, -armInner, -armOuter, -armOuter, strokePaint)
        canvas.drawLine(armInner, -armInner, armOuter, -armOuter, strokePaint)
        canvas.drawLine(-armInner, armInner, -armOuter, armOuter, strokePaint)
        canvas.drawLine(armInner, armInner, armOuter, armOuter, strokePaint)
        listOf(
            -dp(8f + 12f * arms) to -dp(8f + 12f * arms),
            dp(8f + 12f * arms) to -dp(8f + 12f * arms),
            -dp(8f + 12f * arms) to dp(8f + 12f * arms),
            dp(8f + 12f * arms) to dp(8f + 12f * arms)
        ).forEach { (x, y) ->
            canvas.drawCircle(x, y, dp(1.5f + 3.5f * rotors), paint)
            canvas.drawCircle(x, y, dp(1.5f + 3.5f * rotors), strokePaint)
            canvas.drawLine(x - dp(5f * rotors), y, x + dp(5f * rotors), y, strokePaint)
        }
        strokePaint.strokeWidth = dp(1.2f)
    }

    private fun drawSurfaceSymbol(canvas: Canvas, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val body = smoothStep(0f, 0.6f, p)
        val gear = smoothStep(0.55f, 1f, p)
        val target = listOf(
            ScreenPoint(0f, -15f),
            ScreenPoint(5f, -4f),
            ScreenPoint(18f, 1f),
            ScreenPoint(15f, 6f),
            ScreenPoint(4f, 4f),
            ScreenPoint(2f, 13f),
            ScreenPoint(-2f, 13f),
            ScreenPoint(-4f, 4f),
            ScreenPoint(-15f, 6f),
            ScreenPoint(-18f, 1f),
            ScreenPoint(-5f, -4f)
        )
        drawMorphedPolygon(canvas, target, p)
        strokePaint.strokeWidth = dp(2)
        canvas.drawLine(-dp(5f + 9f * gear), dp(8f + 10f * body), dp(5f + 9f * gear), dp(8f + 10f * body), strokePaint)
        canvas.drawCircle(-dp(3f + 5f * gear), dp(8f + 10f * body), dp(2.2f * gear), strokePaint)
        canvas.drawCircle(dp(3f + 5f * gear), dp(8f + 10f * body), dp(2.2f * gear), strokePaint)
        strokePaint.strokeWidth = dp(1.2f)
    }
    private fun drawOwnship(canvas: Canvas, viewport: Viewport, location: Location) {
        val point = latLonToWorld(location.latitude, location.longitude, viewport.zoom)
        val x = (point.x - viewport.centerX + viewport.width / 2.0).toFloat()
        val y = (point.y - viewport.centerY + viewport.height / 2.0).toFloat()
        if (x < -dp(80) || x > viewport.width + dp(80) || y < -dp(80) || y > viewport.height + dp(80)) return
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(accentBlueColor, 58)
        canvas.drawCircle(x, y, dp(28), paint)
        paint.color = if (themeStyle.treatment == ThemeTreatment.PLAIN) controlFillColor else withAlpha(controlFillColor, themeStyle.controlAlpha)
        canvas.drawCircle(x, y, dp(20), paint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = withAlpha(textColor, 210)
        canvas.drawCircle(x, y, dp(20), strokePaint)

        canvas.withTranslation(x, y) {
            rotate(38f)
            paint.color = textColor
            iconPath.reset()
            iconPath.moveTo(0f, -dp(12))
            iconPath.lineTo(dp(8), dp(12))
            iconPath.lineTo(0f, dp(7))
            iconPath.lineTo(-dp(8), dp(12))
            iconPath.close()
            drawPath(iconPath, paint)
        }
        drawYouPill(canvas, x - dp(35), y + dp(30), dp(70), dp(22), controlFillColor, textColor)
    }

    private fun drawNoLocationState(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = mapEmptyColor
        canvas.drawRect(0f, 0f, w, h, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(18)
        textPaint.color = textColor
        val message = if (locationPermissionGranted) "Waiting for device location" else "Location permission required"
        canvas.drawText(message, w / 2f, h * 0.45f, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText("No map or aircraft will be shown until real location data is available.", w / 2f, h * 0.45f + dp(24), textPaint)
    }

    private fun drawTopStatus(canvas: Canvas, w: Float, h: Float) {
        val rect = topStatusBounds(w, h)
        drawPanelSurface(canvas, rect, panelColor, themeStyle.topPanelAlpha)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(19f * themeStyle.headingScale)
        textPaint.color = textColor
        canvas.drawText("Flight Alert", rect.left + dp(16), rect.top + dp(27), textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText(topSubtitle(), rect.left + dp(16), rect.top + dp(49), textPaint)

        val status = topTrafficStatus()
        drawStatusLabel(canvas, rect.right - dp(132), rect.top + dp(14), dp(116), dp(26), status.first, status.second)
        drawScaleLabel(canvas, rect.right - dp(132), rect.top + dp(45), dp(116), dp(17))
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
        val total = synchronized(aircraft) { aircraft.size }
        return when {
            hazard -> "TRAFFIC ALERT" to dangerColor
            filtersActive() && total > 0 && nearest == null -> "FILTERED" to accentOrangeColor
            nearest == null && aircraftStatus.startsWith("No aircraft reported") -> "NO TRAFFIC" to mutedColor
            nearest == null -> "NO DATA" to mutedColor
            filtersActive() -> "FILTERED" to accentOrangeColor
            else -> "TRAFFIC LIVE" to accentGreenColor
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
        canvas.drawText("YOU", rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
        textPaint.isFakeBoldText = false
    }

    private fun drawScaleLabel(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        val rect = RectF(x, y, x + width, y + height)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(34, Color.red(accentYellowColor), Color.green(accentYellowColor), Color.blue(accentYellowColor))
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)

        val scale = currentMapScale(width * 0.42f)
        val label = scale.label
        val lineLeft = rect.left + dp(9)
        val lineWidth = scale.pixels.coerceIn(dp(18), width * 0.44f)
        val lineRight = lineLeft + lineWidth
        val lineY = rect.centerY()
        strokePaint.color = accentYellowColor
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawLine(lineLeft, lineY, lineRight, lineY, strokePaint)
        canvas.drawLine(lineLeft, lineY - dp(3), lineLeft, lineY + dp(3), strokePaint)
        canvas.drawLine(lineRight, lineY - dp(3), lineRight, lineY + dp(3), strokePaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(9)
        textPaint.color = accentYellowColor
        val metrics = textPaint.fontMetrics
        canvas.drawText(label, lineRight + dp(7), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
        textPaint.isFakeBoldText = false
    }

    private fun currentMapScale(targetPixels: Float): ScaleLabel {
                val centerLat = latestLocation
            ?.let { viewportFor(it, contentWidth(), contentHeight()) }
            ?.let { worldToLatLon(it.centerX, it.centerY, it.zoom).lat }
            ?: 0.0
        val metersPerPixel = metersPerPixelAt(centerLat, zoom).coerceAtLeast(0.0001)
        val rawMeters = metersPerPixel * targetPixels
        val distanceMeters = if (units == UnitSystem.IMPERIAL) {
            niceImperialScaleMeters(rawMeters)
        } else {
            niceMetricScaleMeters(rawMeters)
        }
        return ScaleLabel((distanceMeters / metersPerPixel).toFloat(), formatScaleDistance(distanceMeters))
    }

    private fun niceImperialScaleMeters(rawMeters: Double): Double {
        val rawFeet = rawMeters * 3.28084
        return if (rawFeet < 5280.0) {
            niceScaleValue(rawFeet).coerceAtLeast(1.0) / 3.28084
        } else {
            niceScaleValue(rawFeet / 5280.0).coerceAtLeast(0.1) * 1609.344
        }
    }

    private fun niceMetricScaleMeters(rawMeters: Double): Double {
        return if (rawMeters < 1000.0) {
            niceScaleValue(rawMeters).coerceAtLeast(1.0)
        } else {
            niceScaleValue(rawMeters / 1000.0).coerceAtLeast(0.1) * 1000.0
        }
    }

    private fun niceScaleValue(raw: Double): Double {
        if (raw <= 0.0 || raw.isNaN()) return 1.0
        val exponent = floor(log10(raw))
        val base = 10.0.pow(exponent)
        val fraction = raw / base
        val niceFraction = when {
            fraction < 1.5 -> 1.0
            fraction < 3.5 -> 2.0
            fraction < 7.5 -> 5.0
            else -> 10.0
        }
        return niceFraction * base
    }

    private fun formatScaleDistance(meters: Double): String {
        return if (units == UnitSystem.IMPERIAL) {
            val feet = meters * 3.28084
            if (feet < 5280.0) {
                "${feet.roundToInt()} ft"
            } else {
                val miles = feet / 5280.0
                if (miles < 10.0) String.format(Locale.US, "%.1f mi", miles) else "${miles.roundToInt()} mi"
            }
        } else if (meters < 1000.0) {
            "${meters.roundToInt()} m"
        } else {
            val km = meters / 1000.0
            if (km < 10.0) String.format(Locale.US, "%.1f km", km) else "${km.roundToInt()} km"
        }
    }

    private fun drawRecenterButton(canvas: Canvas, w: Float, h: Float) {
        if (followingLocation || latestLocation == null) return
        val rect = recenterButtonBounds(w, h)
        drawContextControl(canvas, rect, accentGreenColor)
        drawLocateIcon(canvas, rect.centerX(), rect.centerY(), accentGreenColor)
    }

    private fun drawFlightPathButtons(canvas: Canvas, viewport: Viewport, w: Float, h: Float) {
        if (shouldShowPathButton(viewport)) {
            drawFlightPathButton(canvas, flightPathButtonBounds(w, h), "Path", accentYellowColor)
        }
        if (shouldShowPreviousFlightsButton()) {
            val color = if (selectedPreviousFlightsVisible) accentGreenColor else accentBlueColor
            drawFlightPathButton(canvas, previousFlightsButtonBounds(w, h), "History", color)
        }
        if (shouldShowClearPathButton()) {
            drawFlightPathButton(canvas, clearFlightPathButtonBounds(w, h), "Clear", dangerColor)
        }
    }

    private fun drawFlightPathButton(canvas: Canvas, rect: RectF, label: String, color: Int) {
        drawContextControl(canvas, rect, color)
        when (label) {
            "Clear" -> drawClearIcon(canvas, rect.centerX(), rect.centerY(), color)
            "History" -> drawHistoryIcon(canvas, rect.centerX(), rect.centerY(), color)
            else -> drawPathFitIcon(canvas, rect.centerX(), rect.centerY(), color)
        }
    }

    private fun recenterButtonBounds(w: Float, h: Float): RectF {
        val slot = if (hasSelectedFlightPath()) 1 else 0
        return contextButtonBounds(w, h, slot)
    }

    private fun flightPathButtonBounds(w: Float, h: Float): RectF {
        return contextButtonBounds(w, h, 0)
    }

    private fun previousFlightsButtonBounds(w: Float, h: Float): RectF {
        var slot = 0
        if (hasSelectedFlightPath()) slot++
        if (!followingLocation && latestLocation != null) slot++
        return contextButtonBounds(w, h, slot)
    }

    private fun clearFlightPathButtonBounds(w: Float, h: Float): RectF {
        var slot = 0
        if (hasSelectedFlightPath()) slot++
        if (!followingLocation && latestLocation != null) slot++
        if (shouldShowPreviousFlightsButton()) slot++
        return contextButtonBounds(w, h, slot)
    }

    private fun contextButtonBounds(w: Float, h: Float, slot: Int): RectF {
        val anchor = filtersButtonBounds(w, h)
        val size = dp(44)
        val gap = dp(10)
        val left = anchor.right + gap + slot * (size + gap)
        return if (left + size <= w - dp(12)) {
            RectF(left, anchor.top, left + size, anchor.top + size)
        } else {
            val stackedLeft = anchor.left + slot * (size + gap)
            val top = anchor.top - size - gap
            RectF(stackedLeft, top, stackedLeft + size, top + size)
        }
    }

    private fun drawSettingsButton(canvas: Canvas, w: Float, h: Float) {
        val bounds = settingsButtonBounds(w, h)
        val stroke = if (themeStyle.treatment == ThemeTreatment.PLAIN) withAlpha(controlStrokeColor, 155) else controlStrokeColor
        val fillAlpha = if (themeStyle.treatment == ThemeTreatment.PLAIN) 228 else themeStyle.controlAlpha
        val strokeWidth = if (themeStyle.treatment == ThemeTreatment.PLAIN) 1f else themeStyle.controlStrokeDp
        drawControlSurface(canvas, bounds, withAlpha(controlFillColor, fillAlpha), stroke, strokeWidthDp = strokeWidth)
        drawGearIcon(canvas, bounds.centerX(), bounds.centerY() - dp(4), accentBlueColor)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(8)
        textPaint.color = textColor
        canvas.drawText("Settings", bounds.centerX(), bounds.bottom - dp(6), textPaint)
    }

    private fun drawFiltersButton(canvas: Canvas, w: Float, h: Float) {
        val bounds = filtersButtonBounds(w, h)
        val active = filtersActive()
        val stroke = when {
            active -> accentOrangeColor
            themeStyle.treatment == ThemeTreatment.PLAIN -> withAlpha(controlStrokeColor, 155)
            else -> controlStrokeColor
        }
        val fillAlpha = if (themeStyle.treatment == ThemeTreatment.PLAIN) 228 else themeStyle.controlAlpha
        val fill = if (active) withAlpha(accentOrangeColor, themeColors.selectedFillAlpha) else withAlpha(controlFillColor, fillAlpha)
        val strokeWidth = if (themeStyle.treatment == ThemeTreatment.PLAIN) 1f else themeStyle.controlStrokeDp
        drawControlSurface(canvas, bounds, fill, stroke, active, strokeWidth)
        drawFilterIcon(canvas, bounds.centerX(), bounds.centerY() - dp(4), if (active) accentOrangeColor else accentBlueColor)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(8)
        textPaint.color = if (active) accentOrangeColor else textColor
        canvas.drawText(if (active) "Filtered" else "Filters", bounds.centerX(), bounds.bottom - dp(6), textPaint)
    }

    private fun settingsButtonBounds(w: Float, h: Float): RectF {
        val info = infoPanelBounds(w, h)
        val width = dp(84)
        val height = dp(44)
        val x = dp(12)
        val y = if (isWideLayout(w, h)) h - height - dp(14) else info.top - height - dp(12)
        return RectF(x, y, x + width, y + height)
    }

    private fun filtersButtonBounds(w: Float, h: Float): RectF {
        val settings = settingsButtonBounds(w, h)
        val gap = dp(10)
        val width = dp(84)
        return RectF(settings.right + gap, settings.top, settings.right + gap + width, settings.bottom)
    }

    private fun drawTrafficPanel(canvas: Canvas, w: Float, h: Float) {
        val rect = infoPanelBounds(w, h)
        val wide = isWideLayout(w, h)
        drawPanelSurface(canvas, rect, panelColor, themeStyle.infoPanelAlpha)

        val display = displayedTraffic()
        val target = display.aircraft
        var y = rect.top + if (wide) dp(32) else dp(27)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(13)
        textPaint.color = when {
            target == null -> mutedColor
            display.selected -> accentBlueColor
            else -> dangerColor
        }
        val title = when {
            target == null -> "AIRCRAFT FEED"
            display.selected -> "SELECTED TRAFFIC"
            filtersActive() -> "FILTERED TRAFFIC"
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
        textPaint.color = textColor
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
            textPaint.color = mutedColor
            canvas.drawText(formatAltitudeValue(target.altitudeM), rect.left + dp(16), y, textPaint)
            canvas.drawText(formatSpeedValue(target.velocityMs), rect.left + rect.width() * 0.34f, y, textPaint)
            canvas.drawText(formatAge(target), rect.left + rect.width() * 0.60f, y, textPaint)

            y += dp(24)
            canvas.drawText(formatTrack(target.trackDeg), rect.left + dp(16), y, textPaint)
            canvas.drawText(formatVerticalRate(target.verticalRateMs), rect.left + rect.width() * 0.46f, y, textPaint)
            if (target.isMilitary) {
                y += dp(22)
                textPaint.isFakeBoldText = true
                textPaint.color = militaryGrayColor
                canvas.drawText("Tagged military", rect.left + dp(16), y, textPaint)
                textPaint.isFakeBoldText = false
            }
        }

    }

    private fun drawTrafficDetailRow(canvas: Canvas, rect: RectF, y: Float, label: String, value: String): Float {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(10)
        textPaint.color = mutedColor
        canvas.drawText(label.uppercase(Locale.US), rect.left + dp(16), y, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(13)
        textPaint.color = textColor
        drawFittedRightText(canvas, value, rect.right - dp(16), y, rect.width() * 0.56f, sp(13), sp(9))
        strokePaint.color = withAlpha(panelStrokeColor, themeStyle.dividerAlpha)
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
        val rect = detailsPanelBounds(w, h)
        if (environmentalImpactOpen) {
            drawAircraftImpactPanel(canvas, rect)
            return
        }
        if (usageOpen) {
            drawAircraftUsagePanel(canvas, rect)
            return
        }
        if (photoEvidenceOpen) {
            drawPhotoEvidencePanel(canvas, rect)
            return
        }
        drawPanelSurface(canvas, rect, panelAltColor, themeStyle.modalPanelAlpha)
        drawChoiceButton(canvas, detailsCloseButtonBounds(rect), "Close", false)

        val aircraft = displayedTraffic().aircraft
        if (aircraft != null) {
            drawChoiceButton(canvas, detailsUsageButtonBounds(rect), "Usage", hasUsageTraceFor(aircraft))
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(22)
        textPaint.color = textColor
        canvas.drawText(aircraft?.callsign ?: "Aircraft details", rect.left + dp(18), rect.top + dp(38), textPaint)

        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText(aircraftDetailsStatus, rect.left + dp(18), rect.top + dp(60), textPaint)

        val wide = isWideLayout(w, h)
        val details = aircraftDetails
        val clip = detailsContentBounds(rect, reserveBottom = if (aircraft != null) dp(64) else dp(12))
        val checkpoint = canvas.save()
        canvas.clipRect(clip)
        detailsRowsVisibleTop = clip.top + detailsScrollY
        detailsRowsVisibleBottom = clip.bottom + detailsScrollY - dp(2)
        canvas.translate(0f, -detailsScrollY)
        val contentBottom = if (wide && aircraft != null) {
            drawWideAircraftDetails(canvas, rect, aircraft, details)
        } else {
            val photoRect = detailsPhotoBounds(rect, wide)
            drawAircraftPhotoBlock(canvas, photoRect)

            var y = photoRect.bottom + dp(30)
            if (aircraft != null) {
                val detailsLoading = isDetailsLoadingFor(aircraft)
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "ICAO", aircraft.icao24.uppercase(Locale.US))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Registration", details?.registration ?: aircraft.registration ?: loadingOrUnavailable(detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Owner", detailsValue(details?.owner, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Aircraft", formatAircraftType(details, aircraft, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "MFR year", detailsValue(details?.manufacturedYear, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Registry source", detailsValue(details?.registrySource, detailsLoading))
                if (aircraft.isMilitary) {
                    y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Military", "Tagged military")
                    y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Origin status", formatOriginStatus(aircraft, details))
                }
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Route", detailsValue(details?.route, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Origin", formatAirport(details?.originAirport, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Destination", formatAirport(details?.destinationAirport, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Path source", formatTraceSource(aircraft))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Flight time", formatObservedFlightTime(aircraft))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Route complete", formatRouteCompletion(details, aircraft))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Observed path span", formatObservedPathSpan(aircraft))
            }
            y
        }
        canvas.restoreToCount(checkpoint)
        updateDetailsScrollBounds(contentBottom - clip.top + dp(12), clip.height())
        drawDetailsScrollIndicator(canvas, clip)
        if (aircraft != null) {
            drawChoiceButton(canvas, detailsImpactButtonBounds(rect), "Environmental impact", false)
        }
    }

    private fun drawWideAircraftDetails(canvas: Canvas, rect: RectF, aircraft: Aircraft, details: AircraftDetails?): Float {
        val left = RectF(rect.left + dp(18), rect.top + dp(78), rect.left + rect.width() * 0.38f, rect.bottom - dp(18))
        val gap = dp(18)
        val rightLeft = left.right + gap
        val rightWidth = rect.right - dp(18) - rightLeft
        val colGap = dp(16)
        val colWidth = (rightWidth - colGap) / 2f
        val colA = RectF(rightLeft, rect.top + dp(88), rightLeft + colWidth, rect.bottom - dp(18))
        val colB = RectF(colA.right + colGap, colA.top, rect.right - dp(18), colA.bottom)

        drawAircraftPhotoBlock(canvas, detailsPhotoBounds(left, true))

        val rows = mutableListOf<Pair<String, String>>()
        val detailsLoading = isDetailsLoadingFor(aircraft)
        rows += "ICAO" to aircraft.icao24.uppercase(Locale.US)
        rows += "Registration" to (details?.registration ?: aircraft.registration ?: loadingOrUnavailable(detailsLoading))
        rows += "Owner" to detailsValue(details?.owner, detailsLoading)
        rows += "Aircraft" to formatAircraftType(details, aircraft, detailsLoading)
        rows += "MFR year" to detailsValue(details?.manufacturedYear, detailsLoading)
        rows += "Registry source" to detailsValue(details?.registrySource, detailsLoading)
        if (aircraft.isMilitary) {
            rows += "Military" to "Tagged military"
            rows += "Origin status" to formatOriginStatus(aircraft, details)
        }
        rows += "Route" to detailsValue(details?.route, detailsLoading)
        rows += "Origin" to formatAirport(details?.originAirport, detailsLoading)
        rows += "Destination" to formatAirport(details?.destinationAirport, detailsLoading)
        rows += "Path source" to formatTraceSource(aircraft)
        rows += "Flight time" to formatObservedFlightTime(aircraft)
        rows += "Route complete" to formatRouteCompletion(details, aircraft)
        rows += "Observed path span" to formatObservedPathSpan(aircraft)

        val split = (rows.size + 1) / 2
        var yA = colA.top
        rows.take(split).forEach { (label, value) ->
            yA = drawAdaptiveDetailRow(canvas, colA, yA, label, value, compact = true)
        }
        var yB = colB.top
        rows.drop(split).forEach { (label, value) ->
            yB = drawAdaptiveDetailRow(canvas, colB, yB, label, value, compact = true)
        }
        return maxOf(detailsPhotoBounds(left, true).bottom, yA, yB)
    }

    private fun drawAircraftImpactPanel(canvas: Canvas, rect: RectF) {
        drawPanelSurface(canvas, rect, panelAltColor, themeStyle.modalPanelAlpha)
        drawChoiceButton(canvas, detailsCloseButtonBounds(rect), "Back", false)

        val aircraft = displayedTraffic().aircraft
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(21)
        textPaint.color = textColor
        canvas.drawText("Environmental impact", rect.left + dp(18), rect.top + dp(38), textPaint)

        if (aircraft == null) {
            drawUsageCenteredMessage(canvas, "Unavailable: no selected live aircraft.", RectF(rect.left + dp(18), rect.top + dp(86), rect.right - dp(18), rect.bottom - dp(24)))
            return
        }

        val details = aircraftDetails
        val detailsLoading = isDetailsLoadingFor(aircraft)
        val profile = impactProfileFor(aircraft, details)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText(ellipsize(impactStatus(profile, detailsLoading), rect.width() - dp(36)), rect.left + dp(18), rect.top + dp(62), textPaint)

        val clip = detailsContentBounds(rect)
        val checkpoint = canvas.save()
        canvas.clipRect(clip)
        detailsRowsVisibleTop = clip.top + detailsScrollY
        detailsRowsVisibleBottom = clip.bottom + detailsScrollY - dp(2)
        canvas.translate(0f, -detailsScrollY)

        var y = rect.top + dp(98)
        y = drawImpactScoreSummary(canvas, rect, y, profile, detailsLoading)
        y += dp(10)
        impactRows(aircraft, details, profile, detailsLoading).forEach { (label, value) ->
            y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, label, value)
        }

        canvas.restoreToCount(checkpoint)
        updateDetailsScrollBounds(y - clip.top + dp(12), clip.height())
        drawDetailsScrollIndicator(canvas, clip)
    }

    private fun drawImpactScoreSummary(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        profile: ImpactProfile?,
        loading: Boolean
    ): Float {
        val left = rect.left + dp(18)
        val right = rect.right - dp(18)
        val scoreText = profile?.let { "${impactScore(it)} / 100" } ?: loadingOrUnavailable(loading)
        val co2Text = profile?.let { formatImpactKgRange(it.lowCo2KgPerHour(), it.highCo2KgPerHour()) } ?: loadingOrUnavailable(loading)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(10)
        textPaint.color = mutedColor
        canvas.drawText("CARBON IMPACT SCORE", left, y, textPaint)
        canvas.drawText("ESTIMATED CO2 PER HOUR", rect.centerX() + dp(8), y, textPaint)

        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(28)
        textPaint.color = if (profile == null) mutedColor else impactScoreColor(profile)
        canvas.drawText(scoreText, left, y + dp(34), textPaint)

        textPaint.textSize = sp(15)
        textPaint.color = textColor
        drawFittedRightText(canvas, co2Text, right, y + dp(32), rect.width() * 0.46f, sp(15), sp(9))

        strokePaint.color = withAlpha(panelStrokeColor, themeStyle.dividerAlpha + 36)
        strokePaint.strokeWidth = dp(1)
        canvas.drawLine(left, y + dp(48), right, y + dp(48), strokePaint)
        textPaint.isFakeBoldText = false
        return y + dp(70)
    }

    private fun drawAircraftUsagePanel(canvas: Canvas, rect: RectF) {
        drawPanelSurface(canvas, rect, panelAltColor, themeStyle.modalPanelAlpha)
        drawChoiceButton(canvas, detailsCloseButtonBounds(rect), "Back", false)

        val aircraft = displayedTraffic().aircraft
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(21)
        textPaint.color = textColor
        canvas.drawText("Aircraft usage", rect.left + dp(18), rect.top + dp(38), textPaint)

        if (aircraft == null) {
            drawUsageCenteredMessage(canvas, "Unavailable: no selected live aircraft.", RectF(rect.left + dp(18), rect.top + dp(86), rect.right - dp(18), rect.bottom - dp(24)))
            return
        }

        val trace = usageTraceFor(aircraft)
        val loading = trace == null && isFlightPathLoading(aircraft)
        val status = when {
            trace != null -> "Trace-derived from ${trace.source}"
            loading -> "Loading trace usage"
            else -> "Unavailable from trace source"
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText(ellipsize(status, rect.width() - dp(36)), rect.left + dp(18), rect.top + dp(62), textPaint)

        if (trace == null) {
            val message = if (loading) "Loading" else "Unavailable: no real trace history was retrieved for this aircraft."
            drawUsageCenteredMessage(canvas, message, RectF(rect.left + dp(18), rect.top + dp(96), rect.right - dp(18), rect.bottom - dp(24)))
            return
        }

        val stats = usageStats(trace)
        if (stats.flightCount == 0) {
            drawUsageCenteredMessage(canvas, "Unavailable: trace data does not contain usable completed or current flight segments.", RectF(rect.left + dp(18), rect.top + dp(96), rect.right - dp(18), rect.bottom - dp(24)))
            return
        }

        var y = rect.top + dp(102)
        y = drawUsageStatLine(canvas, rect, y, "Current week", "${stats.weekFlightCount} flights  ${formatUsageHours(stats.weekHours)}")
        y = drawUsageStatLine(canvas, rect, y, "Trace window total", "${stats.flightCount} flights  ${formatUsageHours(stats.totalHours)}")
        y = drawUsageStatLine(canvas, rect, y, "Trace window", stats.windowLabel)

        val graph = RectF(rect.left + dp(18), y + dp(14), rect.right - dp(18), rect.bottom - dp(28))
        drawUsageGraph(canvas, graph, stats)
    }

    private fun drawUsageCenteredMessage(canvas: Canvas, message: String, rect: RectF) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(13)
        textPaint.color = mutedColor
        drawCenteredWrappedText(canvas, message, rect)
    }

    private fun drawUsageStatLine(canvas: Canvas, rect: RectF, y: Float, label: String, value: String): Float {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(10)
        textPaint.color = mutedColor
        canvas.drawText(label.uppercase(Locale.US), rect.left + dp(18), y, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(13)
        textPaint.color = textColor
        drawFittedRightText(canvas, value, rect.right - dp(18), y, rect.width() * 0.56f, sp(13), sp(9))
        strokePaint.color = withAlpha(panelStrokeColor, themeStyle.dividerAlpha)
        strokePaint.strokeWidth = dp(1)
        canvas.drawLine(rect.left + dp(18), y + dp(10), rect.right - dp(18), y + dp(10), strokePaint)
        textPaint.isFakeBoldText = false
        return y + dp(28)
    }

    private fun drawUsageGraph(canvas: Canvas, rect: RectF, stats: UsageStats) {
        if (rect.height() < dp(96) || rect.width() < dp(180)) return
        paint.style = Paint.Style.FILL
        paint.color = if (themeStyle.treatment == ThemeTreatment.PLAIN) withAlpha(panelColor, 120) else withAlpha(panelColor, 150)
        val radius = controlRadius().coerceAtMost(dp(8))
        canvas.drawRoundRect(rect, radius, radius, paint)
        strokePaint.color = withAlpha(panelStrokeColor, themeStyle.dividerAlpha + 44)
        strokePaint.strokeWidth = dp(1)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val chart = RectF(rect.left + dp(16), rect.top + dp(28), rect.right - dp(16), rect.bottom - dp(28))
        val maxHours = max(1.0, stats.buckets.maxOf { it.hours })
        val maxFlights = max(1, stats.buckets.maxOf { it.flights })
        val step = chart.width() / max(1, stats.buckets.size - 1)
        val barWidth = min(dp(18), step * 0.38f)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(11)
        textPaint.color = textColor
        canvas.drawText("7-day trace usage", rect.left + dp(14), rect.top + dp(18), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(10)
        textPaint.color = mutedColor
        canvas.drawText("hours line / flights bars", rect.right - dp(14), rect.top + dp(18), textPaint)

        strokePaint.color = withAlpha(mutedColor, 90)
        strokePaint.strokeWidth = dp(1)
        canvas.drawLine(chart.left, chart.bottom, chart.right, chart.bottom, strokePaint)

        var previous: ScreenPoint? = null
        stats.buckets.forEachIndexed { index, bucket ->
            val x = chart.left + step * index
            val barHeight = (chart.height() * (bucket.flights.toFloat() / maxFlights)).coerceAtLeast(if (bucket.flights > 0) dp(3) else 0f)
            paint.color = withAlpha(accentBlueColor, if (bucket.flights > 0) 150 else 42)
            canvas.drawRoundRect(RectF(x - barWidth / 2f, chart.bottom - barHeight, x + barWidth / 2f, chart.bottom), dp(2), dp(2), paint)

            val point = ScreenPoint(x, chart.bottom - (chart.height() * (bucket.hours / maxHours)).toFloat())
            previous?.let { old ->
                strokePaint.color = accentYellowColor
                strokePaint.strokeWidth = dp(2)
                canvas.drawLine(old.x, old.y, point.x, point.y, strokePaint)
            }
            paint.color = accentYellowColor
            canvas.drawCircle(point.x, point.y, dp(3), paint)
            previous = point

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.isFakeBoldText = false
            textPaint.textSize = sp(9)
            textPaint.color = mutedColor
            canvas.drawText(bucket.label, x, rect.bottom - dp(10), textPaint)
        }
    }

    private fun drawAircraftPhotoBlock(canvas: Canvas, photoRect: RectF) {
        paint.color = themeColors.photoSurface
        canvas.drawRoundRect(photoRect, controlRadius().coerceAtMost(dp(10)), controlRadius().coerceAtMost(dp(10)), paint)
        val photo = aircraftPhoto
        if (photo != null) {
            val src = Rect(0, 0, photo.width, photo.height)
            canvas.drawBitmap(photo, src, aspectFitRect(photo.width, photo.height, photoRect), paint)
            drawPhotoCaption(canvas, photoRect, aircraftPhotoStatus)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = sp(11)
            textPaint.color = mutedColor
            drawCenteredWrappedText(canvas, aircraftPhotoStatus, photoRect.insetCopy(dp(14), dp(8)))
        }
    }

    private fun drawAdaptiveDetailRow(canvas: Canvas, rect: RectF, y: Float, label: String, value: String, compact: Boolean = false): Float {
        val labelSize = if (compact) sp(9) else sp(10)
        val valueSize = if (compact) sp(12) else sp(13)
        val minValueSize = if (compact) sp(8) else sp(9)
        val oneLineWidth = rect.width() * if (compact) 0.62f else 0.56f
        val right = rect.right - if (compact) 0f else dp(16)
        val left = rect.left + if (compact) 0f else dp(16)
        textPaint.textSize = valueSize
        val rowBottom = if (textPaint.measureText(value) <= oneLineWidth) {
            y + dp(if (compact) 25 else 28)
        } else {
            val lines = wrappedTextLines(value, right - left, DETAILS_ROW_MAX_LINES)
            y + dp(if (compact) 18 else 21) + lines.size * (if (compact) dp(16) else dp(18)) + dp(if (compact) 8 else 10)
        }
        if (rowBottom > detailsRowsVisibleBottom || y < detailsRowsVisibleTop) return rowBottom

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = labelSize
        textPaint.color = mutedColor
        canvas.drawText(label.uppercase(Locale.US), rect.left + if (compact) 0f else dp(16), y, textPaint)

        textPaint.isFakeBoldText = true
        textPaint.textSize = valueSize
        textPaint.color = textColor
        if (textPaint.measureText(value) <= oneLineWidth) {
            textPaint.textAlign = Paint.Align.RIGHT
            drawFittedRightText(canvas, value, right, y, oneLineWidth, valueSize, minValueSize)
            strokePaint.color = withAlpha(panelStrokeColor, if (compact) max(36, themeStyle.dividerAlpha - 10) else themeStyle.dividerAlpha)
            strokePaint.strokeWidth = dp(1)
            canvas.drawLine(left, y + dp(if (compact) 9 else 10), right, y + dp(if (compact) 9 else 10), strokePaint)
            textPaint.isFakeBoldText = false
            return rowBottom
        }

        textPaint.textAlign = Paint.Align.LEFT
        val availableWidth = right - left
        val lines = wrappedTextLines(value, availableWidth, DETAILS_ROW_MAX_LINES)
        var cy = y + dp(if (compact) 18 else 21)
        val lineHeight = if (compact) dp(16) else dp(18)
        lines.forEach { line ->
            canvas.drawText(line, left, cy, textPaint)
            cy += lineHeight
        }
        strokePaint.color = withAlpha(panelStrokeColor, if (compact) max(36, themeStyle.dividerAlpha - 10) else themeStyle.dividerAlpha)
        strokePaint.strokeWidth = dp(1)
        canvas.drawLine(left, cy - dp(6), right, cy - dp(6), strokePaint)
        textPaint.isFakeBoldText = false
        return rowBottom
    }

    private fun drawPhotoEvidencePanel(canvas: Canvas, rect: RectF) {
        val evidence = aircraftPhotoEvidence
        drawPanelSurface(canvas, rect, panelAltColor, themeStyle.modalPanelAlpha)
        drawChoiceButton(canvas, detailsCloseButtonBounds(rect), "Close", false)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(21)
        textPaint.color = textColor
        canvas.drawText("Photo verification", rect.left + dp(18), rect.top + dp(38), textPaint)

        if (evidence == null) {
            textPaint.isFakeBoldText = false
            textPaint.textSize = sp(13)
            textPaint.color = mutedColor
            canvas.drawText("No search-engine verification is attached to this photo.", rect.left + dp(18), rect.top + dp(78), textPaint)
            return
        }

        val wide = rect.width() > rect.height()
        val imageRect = if (wide) {
            RectF(rect.left + dp(18), rect.top + dp(72), rect.left + rect.width() * 0.44f, rect.bottom - dp(80))
        } else {
            RectF(rect.left + dp(18), rect.top + dp(72), rect.right - dp(18), rect.top + dp(270))
        }
        val clip = detailsContentBounds(rect, reserveBottom = dp(72))
        val checkpoint = canvas.save()
        canvas.clipRect(clip)
        canvas.translate(0f, -detailsScrollY)
        drawAircraftPhotoBlock(canvas, imageRect)

        val textLeft = if (wide) imageRect.right + dp(22) else rect.left + dp(18)
        var y = if (wide) rect.top + dp(82) else imageRect.bottom + dp(32)
        val textRect = RectF(textLeft, y, rect.right - dp(18), rect.bottom - dp(82))

        y = drawEvidenceTextLine(canvas, textRect, y, "Source", evidence.sourceName)
        y = drawEvidenceTextLine(canvas, textRect, y, "Search", evidence.searchQuery)
        y = drawEvidenceTextLine(canvas, textRect, y, "Matched", evidence.matchedTerms.joinToString(", ").ifBlank { "Unavailable" })

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText("Verification quote", textRect.left, y + dp(10), textPaint)
        textPaint.textSize = sp(13)
        textPaint.color = textColor
        val quoteBottom = drawWrappedText(canvas, evidence.quote, textRect.left, y + dp(34), textRect.width(), maxLines = DETAILS_PROOF_QUOTE_LINES)
        val contentBottom = maxOf(imageRect.bottom, quoteBottom) + dp(14)
        canvas.restoreToCount(checkpoint)
        updateDetailsScrollBounds(contentBottom - clip.top, clip.height())
        drawDetailsScrollIndicator(canvas, clip)

        evidence.imageUrl.takeIf { it.isNotBlank() }?.let {
            drawChoiceButton(canvas, photoImageSourceButtonBounds(rect), "Open image", false)
        }
        evidence.pageUrl.takeIf { it.isNotBlank() }?.let {
            drawChoiceButton(canvas, photoPageSourceButtonBounds(rect), "Open source", false)
        }
    }

    private fun drawEvidenceTextLine(canvas: Canvas, rect: RectF, y: Float, label: String, value: String): Float {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(10)
        textPaint.color = mutedColor
        canvas.drawText(label.uppercase(Locale.US), rect.left, y, textPaint)
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(13)
        textPaint.color = textColor
        val lines = wrappedTextLines(value, rect.width(), DETAILS_EVIDENCE_LINE_MAX_LINES)
        var cy = y + dp(20)
        lines.forEach { line ->
            canvas.drawText(line, rect.left, cy, textPaint)
            cy += dp(18)
        }
        textPaint.isFakeBoldText = false
        return cy + dp(10)
    }

    private fun drawWrappedText(canvas: Canvas, value: String, x: Float, y: Float, width: Float, maxLines: Int = PROOF_QUOTE_LINES): Float {
        var cy = y
        wrappedTextLines(value, width, maxLines).forEach { line ->
            canvas.drawText(line, x, cy, textPaint)
            cy += dp(19)
        }
        return cy
    }

    private fun drawPhotoCaption(canvas: Canvas, photoRect: RectF, caption: String) {
        if (caption.isBlank()) return
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(10)
        val maxWidth = photoRect.width() - dp(18)
        val lineHeight = dp(13)
        val maxLines = max(1, floor((photoRect.height() - dp(12)) / lineHeight).toInt()).coerceAtMost(PHOTO_CAPTION_MAX_LINES)
        val lines = wrappedTextLines(caption, maxWidth, maxLines)
        val captionHeight = (dp(12) + lines.size * lineHeight).coerceAtMost(photoRect.height() - dp(8))
        val captionRect = RectF(photoRect.left, photoRect.bottom - captionHeight, photoRect.right, photoRect.bottom)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(panelColor, if (themeStyle.treatment == ThemeTreatment.PLAIN) 190 else 216)
        val radius = if (themeStyle.treatment == ThemeTreatment.PLAIN) dp(4) else controlRadius().coerceAtMost(dp(8))
        canvas.drawRoundRect(captionRect, radius, radius, paint)
        textPaint.color = textColor
        val metrics = textPaint.fontMetrics
        var y = captionRect.top + dp(6) - metrics.ascent
        lines.forEach { line ->
            canvas.drawText(line, captionRect.centerX(), y, textPaint)
            y += lineHeight
        }
    }

    private fun drawCenteredWrappedText(canvas: Canvas, value: String, rect: RectF) {
        val lines = wrappedTextLines(value, rect.width(), PHOTO_PLACEHOLDER_LINES)
        val metrics = textPaint.fontMetrics
        val lineHeight = textPaint.fontSpacing
        val blockHeight = lineHeight * lines.size
        var y = rect.centerY() - blockHeight / 2f - metrics.ascent
        lines.forEach { line ->
            canvas.drawText(line, rect.centerX(), y, textPaint)
            y += lineHeight
        }
    }

    private fun wrappedTextLines(value: String, width: Float, maxLines: Int): List<String> {
        val words = value.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var line = ""
        fun pushLine(nextLine: String): Boolean {
            if (lines.size >= maxLines) return false
            lines += nextLine
            return true
        }
        words.forEach { word ->
            val candidate = if (line.isBlank()) word else "$line $word"
            if (textPaint.measureText(candidate) <= width) {
                line = candidate
            } else {
                if (line.isNotBlank() && !pushLine(line)) return lines
                line = ""
                if (textPaint.measureText(word) <= width) {
                    line = word
                } else {
                    splitLongWord(word, width).forEach { segment ->
                        if (!pushLine(segment)) return lines
                    }
                }
            }
        }
        if (line.isNotBlank() && lines.size < maxLines) lines += line
        return lines
    }

    private fun splitLongWord(word: String, width: Float): List<String> {
        val parts = mutableListOf<String>()
        var part = ""
        word.forEach { char ->
            val candidate = part + char
            if (candidate.length > 1 && textPaint.measureText(candidate) > width) {
                parts += part
                part = char.toString()
            } else {
                part = candidate
            }
        }
        if (part.isNotBlank()) parts += part
        return parts
    }

    private fun RectF.insetCopy(dx: Float, dy: Float): RectF {
        return RectF(left + dx, top + dy, right - dx, bottom - dy)
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

    private fun detailsPhotoBounds(area: RectF, wide: Boolean): RectF {
        val hasPhoto = aircraftPhoto != null
        return if (wide) {
            val height = if (hasPhoto) min(area.height(), dp(280)) else min(area.height(), dp(104))
            RectF(area.left + dp(18), area.top, area.right - dp(18), area.top + height)
        } else {
            val height = if (hasPhoto) dp(206) else dp(86)
            RectF(area.left + dp(18), area.top + dp(78), area.right - dp(18), area.top + dp(78) + height)
        }
    }

    private fun detailsContentBounds(panel: RectF, reserveBottom: Float = dp(12)): RectF {
        return RectF(panel.left, panel.top + dp(70), panel.right, panel.bottom - reserveBottom)
    }

    private fun detailsRowBounds(panel: RectF): RectF {
        return RectF(panel.left, panel.top, panel.right, panel.bottom)
    }

    private fun updateDetailsScrollBounds(contentHeight: Float, viewportHeight: Float) {
        detailsMaxScrollY = max(0f, contentHeight - viewportHeight)
        detailsScrollY = detailsScrollY.coerceIn(0f, detailsMaxScrollY)
    }

    private fun drawDetailsScrollIndicator(canvas: Canvas, clip: RectF) {
        if (detailsMaxScrollY <= dp(2)) return
        val track = RectF(clip.right - dp(6), clip.top + dp(8), clip.right - dp(3), clip.bottom - dp(8))
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(textColor, 54)
        canvas.drawRoundRect(track, dp(2), dp(2), paint)
        val thumbHeight = (track.height() * (clip.height() / (clip.height() + detailsMaxScrollY))).coerceIn(dp(24), track.height())
        val top = track.top + (track.height() - thumbHeight) * (detailsScrollY / detailsMaxScrollY)
        paint.color = withAlpha(accentBlueColor, 170)
        canvas.drawRoundRect(RectF(track.left, top, track.right, top + thumbHeight), dp(2), dp(2), paint)
    }

    private fun detailsPanelBounds(w: Float, h: Float): RectF {
        val margin = dp(14)
        val width = if (isWideLayout(w, h)) min(dp(680), w - margin * 2f) else w - margin * 2f
        val height = if (isWideLayout(w, h)) min(h - margin * 2f, dp(390)) else min(h - margin * 2f, dp(720))
        return RectF((w - width) / 2f, (h - height) / 2f, (w + width) / 2f, (h + height) / 2f)
    }

    private fun detailsCloseButtonBounds(panel: RectF): RectF {
        return RectF(panel.right - dp(112), panel.top + dp(14), panel.right - dp(18), panel.top + dp(48))
    }

    private fun detailsUsageButtonBounds(panel: RectF): RectF {
        return RectF(panel.right - dp(214), panel.top + dp(14), panel.right - dp(122), panel.top + dp(48))
    }

    private fun detailsImpactButtonBounds(panel: RectF): RectF {
        return RectF(panel.left + dp(18), panel.bottom - dp(52), panel.right - dp(18), panel.bottom - dp(16))
    }

    private fun photoImageSourceButtonBounds(panel: RectF): RectF {
        return RectF(panel.left + dp(18), panel.bottom - dp(58), panel.left + dp(138), panel.bottom - dp(18))
    }

    private fun photoPageSourceButtonBounds(panel: RectF): RectF {
        return RectF(panel.left + dp(150), panel.bottom - dp(58), panel.left + dp(286), panel.bottom - dp(18))
    }

    private fun currentDetailsPhotoBounds(panel: RectF, w: Float, h: Float): RectF {
        return if (isWideLayout(w, h)) {
            val left = RectF(panel.left + dp(18), panel.top + dp(78), panel.left + panel.width() * 0.38f, panel.bottom - dp(18))
            detailsPhotoBounds(left, true)
        } else {
            detailsPhotoBounds(panel, false)
        }
    }

    private fun openUrl(url: String) {
        val safeUrl = httpsUrl(url) ?: return
        val intent = Intent(Intent.ACTION_VIEW, safeUrl.toString().toUri())
        runCatching { context.startActivity(intent) }
    }

    private fun detailsValue(value: String?, loading: Boolean): String {
        return value ?: loadingOrUnavailable(loading)
    }

    private fun loadingOrUnavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }

    private fun isDetailsLoadingFor(aircraft: Aircraft): Boolean {
        return detailsOpen &&
            selectedAircraftId == aircraft.icao24 &&
            aircraftDetails == null &&
            aircraftDetailsStatus.startsWith("Loading", ignoreCase = true)
    }

    private fun isFlightPathLoading(aircraft: Aircraft): Boolean {
        val id = aircraft.icao24.lowercase(Locale.US)
        return synchronized(flightPathRequests) { id in flightPathRequests }
    }

    private fun formatAircraftType(details: AircraftDetails?, aircraft: Aircraft, loading: Boolean = false): String {
        return listOfNotNull(details?.manufacturer, details?.type, details?.typeCode ?: aircraft.typeCode)
            .distinct()
            .joinToString(" ")
            .ifEmpty { loadingOrUnavailable(loading) }
    }

    private fun formatAirport(airport: com.flightalert.data.AirportDetails?, loading: Boolean = false): String {
        return if (airport == null) {
            loadingOrUnavailable(loading)
        } else {
            listOfNotNull(airport.name, airport.icao, airport.iata).joinToString(" / ")
        }
    }

    private fun formatObservedPathSpan(aircraft: Aircraft): String {
        val id = aircraft.icao24.lowercase(Locale.US)
        if (selectedFlightPathAircraftId != id) return loadingOrUnavailable(isFlightPathLoading(aircraft))
        val points = selectedSegmentPoints(visibleOnly = false) ?: return loadingOrUnavailable(isFlightPathLoading(aircraft))
        val start = points.minOf { it.epochSec }
        val end = points.maxOf { it.epochSec }
        val minutes = ((end - start) / 60.0).coerceAtLeast(0.0)
        return String.format(Locale.US, "%.0f min", minutes)
    }

    private fun formatTraceSource(aircraft: Aircraft): String {
        val id = aircraft.icao24.lowercase(Locale.US)
        val trace = selectedFlightPath?.takeIf { selectedFlightPathAircraftId == id } ?: return loadingOrUnavailable(isFlightPathLoading(aircraft))
        val history = trace.previousSegments.size.takeIf { it > 0 }?.let { count -> ", $count prior ${if (count == 1) "flight" else "flights"}" }.orEmpty()
        return "${trace.source}, ${trace.pointCount} pts$history"
    }

    private fun formatObservedFlightTime(aircraft: Aircraft): String {
        val id = aircraft.icao24.lowercase(Locale.US)
        if (selectedFlightPathAircraftId != id) return loadingOrUnavailable(isFlightPathLoading(aircraft))
        val points = selectedSegmentPoints(visibleOnly = false) ?: return loadingOrUnavailable(isFlightPathLoading(aircraft))
        val start = points.minOf { it.epochSec }
        val latest = max(points.maxOf { it.epochSec }.toDouble(), System.currentTimeMillis() / 1000.0)
        return String.format(Locale.US, "Observed %.0f min", ((latest - start) / 60.0).coerceAtLeast(0.0))
    }

    private fun formatRouteCompletion(details: AircraftDetails?, aircraft: Aircraft): String {
        if (details == null && isDetailsLoadingFor(aircraft)) return "Loading"
        if (isFlightPathLoading(aircraft)) return "Loading"
        val origin = details?.originAirport
        val destination = details?.destinationAirport
        val originLat = origin?.latitude ?: return "Unavailable"
        val originLon = origin.longitude ?: return "Unavailable"
        val destLat = destination?.latitude ?: return "Unavailable"
        val destLon = destination.longitude ?: return "Unavailable"
        val total = distanceMeters(originLat, originLon, destLat, destLon)
        if (total < 1000.0) return "Unavailable"
        traceBasedRouteCompletion(aircraft, originLat, originLon, destLat, destLon, total)?.let {
            return String.format(Locale.US, "~%.0f%% observed track", it)
        }
        val completed = (distanceMeters(originLat, originLon, aircraft.lat, aircraft.lon) / total * 100.0).coerceIn(0.0, 100.0)
        return String.format(Locale.US, "~%.0f%% direct estimate", completed)
    }

    private fun traceBasedRouteCompletion(
        aircraft: Aircraft,
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        directRouteMeters: Double
    ): Double? {
        val id = aircraft.icao24.lowercase(Locale.US)
        if (selectedFlightPathAircraftId != id) return null
        val segments = selectedPathSegments(visibleOnly = false) ?: return null
        if (segments.sumOf { it.points.size } < 2) return null
        val observedMeters = traceDistanceMeters(segments)
        if (observedMeters < 1000.0) return null
        val first = segments.firstOrNull()?.points?.firstOrNull() ?: return null
        val firstFromOriginMeters = distanceMeters(originLat, originLon, first.lat, first.lon)
        val creditedDepartureMeters = firstFromOriginMeters.takeIf {
            it <= max(25000.0, directRouteMeters * 0.12)
        } ?: 0.0
        val last = segments.lastOrNull()?.points?.lastOrNull() ?: return null
        val remainingMeters = distanceMeters(last.lat, last.lon, destLat, destLon)
        val totalEstimatedMeters = creditedDepartureMeters + observedMeters + remainingMeters
        if (totalEstimatedMeters < 1000.0) return null
        return ((creditedDepartureMeters + observedMeters) / totalEstimatedMeters * 100.0).coerceIn(0.0, 100.0)
    }

    private fun traceDistanceMeters(segments: List<TraceSegment>): Double {
        var distance = 0.0
        for (segment in segments) {
            val points = segment.points
            for (index in 1 until points.size) {
                val previous = points[index - 1]
                val current = points[index]
                distance += distanceMeters(previous.lat, previous.lon, current.lat, current.lon)
            }
        }
        return distance
    }

    private fun impactRows(
        aircraft: Aircraft,
        details: AircraftDetails?,
        profile: ImpactProfile?,
        detailsLoading: Boolean
    ): List<Pair<String, String>> {
        val loading = detailsLoading || (profile == null && isDetailsLoadingFor(aircraft))
        return listOf(
            "Data basis" to impactDataBasis(aircraft, details, detailsLoading),
            "Aircraft class" to (profile?.label ?: loadingOrUnavailable(loading)),
            "Fuel class" to (profile?.fuelLabel() ?: loadingOrUnavailable(loading)),
            "Estimated fuel burn" to (profile?.fuelBurnLabel() ?: loadingOrUnavailable(loading)),
            "CO2 factor" to (profile?.co2FactorLabel() ?: loadingOrUnavailable(loading)),
            "Live state" to formatImpactLiveState(aircraft),
            "Current trace" to formatCurrentTraceImpact(aircraft, profile, detailsLoading),
            "Current trace CO2" to formatCurrentTraceCarbon(aircraft, profile, detailsLoading),
            "7-day trace CO2" to formatWeeklyTraceCarbon(aircraft, profile, detailsLoading),
            "Trace window CO2" to formatTraceWindowCarbon(aircraft, profile, detailsLoading),
            "Benchmark context" to (profile?.let { impactComparison(it) } ?: loadingOrUnavailable(loading)),
            "Piston benchmark" to formatImpactBenchmark(IMPACT_PISTON_SINGLE),
            "Business jet benchmark" to formatImpactBenchmark(IMPACT_LIGHT_JET),
            "Airliner benchmark" to formatImpactBenchmark(IMPACT_NARROW_BODY),
            "Lead note" to impactLeadNote(profile, loading),
            "Not included" to "Exact engine power, passenger load, SAF blend, route-specific phase, noise, contrails, and NOx are not inferred from the live feed.",
            "Certainty" to (profile?.confidence ?: loadingOrUnavailable(loading))
        )
    }

    private fun impactStatus(profile: ImpactProfile?, loading: Boolean): String {
        return when {
            profile != null -> "Class estimate; not an exact fuel-burn measurement"
            loading -> "Loading aircraft metadata"
            else -> "Unavailable: aircraft type or fuel class is not supported"
        }
    }

    private fun impactDataBasis(aircraft: Aircraft, details: AircraftDetails?, loading: Boolean): String {
        val code = impactTypeCode(aircraft, details)
        val model = listOfNotNull(details?.manufacturer, details?.type).joinToString(" ").ifBlank { null }
        val category = aircraft.category?.let { "ADS-B category $it" }
        val source = when {
            details?.registrySource != null -> details.registrySource
            aircraft.typeCode != null || aircraft.category != null -> "live aircraft feed"
            loading -> "Loading"
            else -> "Unavailable"
        }
        return listOfNotNull(code?.let { "type $it" }, model, category, source).joinToString("; ").ifEmpty { loadingOrUnavailable(loading) }
    }

    private fun formatImpactLiveState(aircraft: Aircraft): String {
        val state = when (aircraft.onGround) {
            true -> "Ground"
            false -> "Airborne"
            null -> "State unavailable"
        }
        return "$state, ${formatAltitudeValue(aircraft.altitudeM)}, ${formatSpeedValue(aircraft.velocityMs)}, report ${formatAge(aircraft)}"
    }

    private fun formatCurrentTraceImpact(aircraft: Aircraft, profile: ImpactProfile?, loading: Boolean): String {
        if (isFlightPathLoading(aircraft)) return "Loading"
        if (profile == null) return loadingOrUnavailable(loading)
        val segments = currentTraceSegmentsForImpact(aircraft) ?: return "Unavailable"
        val points = segments.flatMap { it.points }
        val start = points.minOf { it.epochSec }
        val end = points.maxOf { it.epochSec }
        val hours = ((end - start) / 3600.0).coerceAtLeast(0.0)
        if (hours <= 0.0) return "Unavailable"
        return "${formatDistance(traceDistanceMeters(segments))}, ${formatUsageHours(hours)} from ${selectedFlightPath?.source ?: "trace source"}"
    }

    private fun formatCurrentTraceCarbon(aircraft: Aircraft, profile: ImpactProfile?, loading: Boolean): String {
        if (isFlightPathLoading(aircraft)) return "Loading"
        if (profile == null) return loadingOrUnavailable(loading)
        val hours = currentTraceHoursForImpact(aircraft) ?: return "Unavailable"
        return "~${formatImpactKgCompact(profile.midCo2KgPerHour() * hours)} kg CO2 over ${formatUsageHours(hours)}"
    }

    private fun formatWeeklyTraceCarbon(aircraft: Aircraft, profile: ImpactProfile?, loading: Boolean): String {
        if (isFlightPathLoading(aircraft)) return "Loading"
        if (profile == null) return loadingOrUnavailable(loading)
        val trace = usageTraceFor(aircraft) ?: return "Unavailable"
        val stats = usageStats(trace)
        if (stats.weekHours <= 0.0 || stats.weekFlightCount == 0) return "Unavailable"
        val kg = profile.midCo2KgPerHour() * stats.weekHours
        return "~${formatImpactKgCompact(kg)} kg CO2 over ${formatUsageHours(stats.weekHours)} (${stats.weekFlightCount} flights)"
    }

    private fun formatTraceWindowCarbon(aircraft: Aircraft, profile: ImpactProfile?, loading: Boolean): String {
        if (isFlightPathLoading(aircraft)) return "Loading"
        if (profile == null) return loadingOrUnavailable(loading)
        val trace = usageTraceFor(aircraft) ?: return "Unavailable"
        val stats = usageStats(trace)
        if (stats.totalHours <= 0.0 || stats.flightCount == 0) return "Unavailable"
        val kg = profile.midCo2KgPerHour() * stats.totalHours
        return "~${formatImpactKgCompact(kg)} kg CO2 over ${formatUsageHours(stats.totalHours)} (${stats.flightCount} trace flights)"
    }

    private fun currentTraceSegmentsForImpact(aircraft: Aircraft): List<TraceSegment>? {
        val id = aircraft.icao24.lowercase(Locale.US)
        if (selectedFlightPathAircraftId != id) return null
        return selectedPathSegments(visibleOnly = false)?.takeIf { segments ->
            segments.sumOf { it.points.size } >= 2
        }
    }

    private fun currentTraceHoursForImpact(aircraft: Aircraft): Double? {
        val points = currentTraceSegmentsForImpact(aircraft)
            ?.flatMap { it.points }
            ?.takeIf { it.size >= 2 }
            ?: return null
        val hours = (points.maxOf { it.epochSec } - points.minOf { it.epochSec }) / 3600.0
        return hours.takeIf { it > 0.0 }
    }

    private fun impactComparison(profile: ImpactProfile): String {
        val current = profile.midCo2KgPerHour()
        val piston = IMPACT_PISTON_SINGLE.midCo2KgPerHour()
        val narrow = IMPACT_NARROW_BODY.midCo2KgPerHour()
        return "${formatImpactMultiplier(current, piston)} a piston-single benchmark; ${formatImpactMultiplier(current, narrow)} an A320/B737-class benchmark"
    }

    private fun formatImpactBenchmark(profile: ImpactProfile): String {
        return "${profile.examples}: ${formatImpactKgRange(profile.lowCo2KgPerHour(), profile.highCo2KgPerHour())}"
    }

    private fun impactLeadNote(profile: ImpactProfile?, loading: Boolean): String {
        return when (profile?.fuel) {
            ImpactFuel.AVGAS -> "Piston avgas may be leaded; exact fuel and unleaded status are unavailable and not included in the carbon score."
            ImpactFuel.JET -> "Jet-fuel carbon is scored; leaded-avgas exposure is not applicable to this class estimate."
            null -> loadingOrUnavailable(loading)
        }
    }

    private fun impactProfileFor(aircraft: Aircraft, details: AircraftDetails?): ImpactProfile? {
        val code = impactTypeCode(aircraft, details)?.uppercase(Locale.US).orEmpty()
        val text = impactSearchText(aircraft, details)
        if (code.isBlank() && text.isBlank()) return null
        if (aircraft.category == 14 || text.contains("UAV") || text.contains("DRONE")) return null
        if (aircraft.category == 9 || code.startsWith("GL") || text.contains("GLIDER")) return null
        if (aircraft.onGround == true && aircraft.category != null && aircraft.category in listOf(16, 17, 18, 19, 20)) return null

        return when {
            isSmallPistonAircraft(code, text) -> IMPACT_PISTON_SINGLE
            isPistonRotorcraft(code, text) -> IMPACT_PISTON_ROTOR
            isTurbineRotorcraft(code, text) -> IMPACT_TURBINE_ROTOR
            isMilitaryTransportAircraft(code, text) -> IMPACT_HEAVY
            isTacticalJetAircraft(code, text) -> IMPACT_TACTICAL_JET
            isHeavyAircraft(code, text) -> IMPACT_HEAVY
            isLargeAirlinerAircraft(code, text) -> IMPACT_NARROW_BODY
            isRegionalAircraft(code, text) -> IMPACT_REGIONAL
            isTurbopropAircraft(code, text) -> IMPACT_TURBOPROP
            isLightJetAircraft(code, text) -> IMPACT_LIGHT_JET
            aircraft.category in listOf(4, 5, 6) -> IMPACT_NARROW_BODY
            aircraft.category == 8 -> null
            isAirlinerTypeCode(code) -> IMPACT_NARROW_BODY
            else -> null
        }
    }

    private fun impactTypeCode(aircraft: Aircraft, details: AircraftDetails?): String? {
        return listOfNotNull(details?.typeCode, aircraft.typeCode)
            .map { it.trim().uppercase(Locale.US) }
            .firstOrNull { it.isNotBlank() }
    }

    private fun impactSearchText(aircraft: Aircraft, details: AircraftDetails?): String {
        return listOfNotNull(aircraft.typeCode, details?.typeCode, details?.manufacturer, details?.type)
            .joinToString(" ")
            .uppercase(Locale.US)
    }

    private fun isSmallPistonAircraft(code: String, text: String): Boolean {
        return SMALL_PISTON_IMPACT_PREFIXES.any { code.startsWith(it) } ||
            SMALL_PISTON_IMPACT_TEXT.any { text.contains(it) }
    }

    private fun isPistonRotorcraft(code: String, text: String): Boolean {
        return PISTON_ROTOR_IMPACT_PREFIXES.any { code.startsWith(it) } ||
            PISTON_ROTOR_IMPACT_TEXT.any { text.contains(it) }
    }

    private fun isTurbineRotorcraft(code: String, text: String): Boolean {
        return TURBINE_ROTOR_IMPACT_PREFIXES.any { code.startsWith(it) } ||
            TURBINE_ROTOR_IMPACT_TEXT.any { text.contains(it) }
    }

    private fun isTurbopropAircraft(code: String, text: String): Boolean {
        return TURBOPROP_IMPACT_PREFIXES.any { code.startsWith(it) } ||
            TURBOPROP_IMPACT_TEXT.any { text.contains(it) }
    }

    private fun isRegionalAircraft(code: String, text: String): Boolean {
        return REGIONAL_IMPACT_PREFIXES.any { code.startsWith(it) } ||
            REGIONAL_IMPACT_TEXT.any { text.contains(it) }
    }

    private fun isLightJetAircraft(code: String, text: String): Boolean {
        return LIGHT_JET_IMPACT_PREFIXES.any { code.startsWith(it) } ||
            LIGHT_JET_IMPACT_TEXT.any { text.contains(it) }
    }

    private fun isLargeAirlinerAircraft(code: String, text: String): Boolean {
        return NARROW_BODY_IMPACT_PREFIXES.any { code.startsWith(it) } ||
            NARROW_BODY_IMPACT_TEXT.any { text.contains(it) }
    }

    private fun isHeavyAircraft(code: String, text: String): Boolean {
        return HEAVY_IMPACT_PREFIXES.any { code.startsWith(it) } ||
            HEAVY_IMPACT_TEXT.any { text.contains(it) }
    }

    private fun isMilitaryTransportAircraft(code: String, text: String): Boolean {
        return MILITARY_TRANSPORT_IMPACT_PREFIXES.any { code.startsWith(it) } ||
            MILITARY_TRANSPORT_IMPACT_TEXT.any { text.contains(it) }
    }

    private fun isTacticalJetAircraft(code: String, text: String): Boolean {
        return TACTICAL_JET_IMPACT_PREFIXES.any { code.startsWith(it) } ||
            TACTICAL_JET_IMPACT_TEXT.any { text.contains(it) }
    }

    private fun impactScore(profile: ImpactProfile): Int {
        val normalized = ((log10(profile.midCo2KgPerHour()) - log10(IMPACT_SCORE_MIN_KG_CO2_PER_HOUR)) /
            (log10(IMPACT_SCORE_MAX_KG_CO2_PER_HOUR) - log10(IMPACT_SCORE_MIN_KG_CO2_PER_HOUR)))
            .coerceIn(0.0, 1.0)
        return (normalized * 100.0).roundToInt()
    }

    private fun impactScoreColor(profile: ImpactProfile): Int {
        val score = impactScore(profile)
        return when {
            score >= 76 -> dangerColor
            score >= 55 -> accentOrangeColor
            score >= 34 -> accentYellowColor
            else -> accentGreenColor
        }
    }

    private fun formatImpactKgRange(low: Double, high: Double): String {
        return "~${formatImpactKgCompact(low)}-${formatImpactKgCompact(high)} kg CO2/hr"
    }

    private fun formatImpactKgCompact(kg: Double): String {
        return if (kg >= 1000.0) {
            String.format(Locale.US, "%,.0f", kg)
        } else {
            String.format(Locale.US, "%.0f", kg)
        }
    }

    private fun formatImpactMultiplier(value: Double, baseline: Double): String {
        if (baseline <= 0.0) return "Unavailable"
        val ratio = value / baseline
        return when {
            ratio >= 10.0 -> String.format(Locale.US, "~%.0fx", ratio)
            ratio >= 1.0 -> String.format(Locale.US, "~%.1fx", ratio)
            else -> String.format(Locale.US, "~%.2fx", ratio)
        }
    }

    private fun hasUsageTraceFor(aircraft: Aircraft): Boolean {
        return usageTraceFor(aircraft) != null
    }

    private fun usageTraceFor(aircraft: Aircraft): FlightTrace? {
        val id = aircraft.icao24.lowercase(Locale.US)
        val trace = selectedFlightPath ?: return null
        if (selectedFlightPathAircraftId != id) return null
        if (trace.segments.isEmpty() && trace.previousSegments.isEmpty()) return null
        return trace
    }

    private fun usageStats(trace: FlightTrace): UsageStats {
        val flights = (trace.previousSegments + trace.segments)
            .mapNotNull { usageFlight(it) }
            .sortedBy { it.startEpochSec }
        if (flights.isEmpty()) {
            return UsageStats(emptyList(), 0, 0.0, 0, 0.0, "Unavailable")
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone)
            .toEpochSecond()
        val now = System.currentTimeMillis() / 1000L
        val weekFlights = flights.filter { it.endEpochSec >= weekStart && it.startEpochSec <= now }
        val weekHours = weekFlights.sumOf { overlapHours(it, weekStart, now) }

        val buckets = (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            val start = day.atStartOfDay(zone).toEpochSecond()
            val end = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
            val dayFlights = flights.filter { it.endEpochSec >= start && it.startEpochSec < end }
            UsageBucket(
                label = day.format(USAGE_DAY_FORMATTER),
                flights = dayFlights.size,
                hours = dayFlights.sumOf { overlapHours(it, start, end) }
            )
        }

        val first = flights.first().startEpochSec
        val last = flights.maxOf { it.endEpochSec }
        return UsageStats(
            buckets = buckets,
            flightCount = flights.size,
            totalHours = flights.sumOf { it.hours },
            weekFlightCount = weekFlights.size,
            weekHours = weekHours,
            windowLabel = "${formatUsageDate(first, zone)}-${formatUsageDate(last, zone)}"
        )
    }

    private fun usageFlight(segment: TraceSegment): UsageFlight? {
        val points = segment.points
        if (points.size < 2) return null
        val start = points.first().epochSec
        val end = points.last().epochSec
        val duration = end - start
        if (duration < MIN_USAGE_SEGMENT_SECONDS) return null
        val distance = traceDistanceMeters(listOf(segment))
        if (distance < MIN_USAGE_SEGMENT_DISTANCE_M) return null
        return UsageFlight(start, end, duration / 3600.0)
    }

    private fun overlapHours(flight: UsageFlight, startEpochSec: Long, endEpochSec: Long): Double {
        val start = max(flight.startEpochSec, startEpochSec)
        val end = min(flight.endEpochSec, endEpochSec)
        return max(0L, end - start) / 3600.0
    }

    private fun formatUsageHours(hours: Double): String {
        return if (hours < 10.0) {
            String.format(Locale.US, "%.1f h", hours)
        } else {
            String.format(Locale.US, "%.0f h", hours)
        }
    }

    private fun formatUsageDate(epochSec: Long, zone: ZoneId): String {
        return Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate().format(USAGE_DATE_FORMATTER)
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
        val stats = filterStats()
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(20)
        textPaint.color = textColor
        val headline = when {
            filtersActive() && stats.total > 0 && stats.matched == 0 -> "No filter matches"
            aircraftStatus.startsWith("No aircraft reported") -> "No reported aircraft"
            else -> "No aircraft data"
        }
        canvas.drawText(headline, rect.left + dp(16), y, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        val message = if (filtersActive() && stats.total > 0 && stats.matched == 0) stats.summary else aircraftStatus
        canvas.drawText(message, rect.left + dp(16), y + dp(24), textPaint)
        lastAircraftDataEpochSec?.let {
            canvas.drawText("Data time ${it.toLong()}", rect.left + dp(16), y + dp(44), textPaint)
        }
    }

    private fun drawSettingsPanel(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        val rect = settingsPanelBounds(w, h)
        drawPanelSurface(canvas, rect, panelAltColor, themeStyle.modalPanelAlpha)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(20)
        textPaint.color = textColor
        canvas.drawText("Settings", rect.left + dp(18), rect.top + dp(34), textPaint)
        drawChoiceButton(canvas, closeButtonBounds(rect), "Close", false)

        if (isCompactSettingsPanel(rect)) {
            drawCompactSettingsPanelContents(canvas, rect)
            return
        }

        drawSettingsSectionLabel(canvas, rect.left + dp(18), rect.top + dp(74), "Display")
        drawChoiceButton(canvas, imperialButtonBounds(rect), "Miles / feet", units == UnitSystem.IMPERIAL)
        drawChoiceButton(canvas, metricButtonBounds(rect), "Kilometers / meters", units == UnitSystem.METRIC)
        drawChoiceButton(canvas, themeButtonBounds(rect), "Theme: ${visualTheme.displayName}", true)

        drawSettingsSectionLabel(canvas, rect.left + dp(18), rect.top + dp(238), "Map")
        drawChoiceButton(canvas, mapSourceButtonBounds(rect), if (mapSource == TileSource.SATELLITE) "Satellite map" else "Street map", mapSource == TileSource.SATELLITE)
        drawChoiceButton(canvas, mapLabelsButtonBounds(rect), if (mapLabelsEnabled) "Street labels on" else "Street labels off", mapLabelsEnabled)

        drawSettingsSectionLabel(canvas, rect.left + dp(18), rect.top + dp(362), "Safety")
        drawChoiceButton(canvas, alertsToggleBounds(rect), if (alertsEnabled) "Hazard alerts on" else "Hazard alerts off", alertsEnabled)
        drawChoiceButton(canvas, priorityTrackerButtonBounds(rect), "Alert range and tracker", priorityTrackingEnabled)

        drawSettingsSectionLabel(canvas, rect.left + dp(18), rect.top + dp(486), "Reference")
        drawChoiceButton(canvas, impactMethodologyButtonBounds(rect), "Impact methodology", false)

        val footerTop = rect.bottom - dp(38)
        if (impactMethodologyButtonBounds(rect).bottom + dp(24) <= footerTop) {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = false
            textPaint.textSize = sp(11)
            textPaint.color = mutedColor
            canvas.drawText("Map: ${mapSource.attributionText(mapLabelsEnabled)}", rect.left + dp(18), rect.bottom - dp(38), textPaint)
            canvas.drawText("Aircraft and paths: live feed sources", rect.left + dp(18), rect.bottom - dp(18), textPaint)
        }
    }

    private fun drawSettingsSectionLabel(canvas: Canvas, x: Float, y: Float, label: String) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText(label, x, y, textPaint)
    }

    private fun drawCompactSettingsPanelContents(canvas: Canvas, rect: RectF) {
        val left = compactSettingsLeftColumn(rect)
        val right = compactSettingsRightColumn(rect)

        drawSettingsSectionLabel(canvas, left.left, rect.top + dp(58), "Display")
        drawChoiceButton(canvas, imperialButtonBounds(rect), "Miles / feet", units == UnitSystem.IMPERIAL)
        drawChoiceButton(canvas, metricButtonBounds(rect), "Kilometers / meters", units == UnitSystem.METRIC)
        drawChoiceButton(canvas, themeButtonBounds(rect), "Theme: ${visualTheme.shortName}", true)

        drawSettingsSectionLabel(canvas, left.left, rect.top + dp(194), "Map")
        drawChoiceButton(canvas, mapSourceButtonBounds(rect), if (mapSource == TileSource.SATELLITE) "Satellite" else "Street", mapSource == TileSource.SATELLITE)
        drawChoiceButton(canvas, mapLabelsButtonBounds(rect), if (mapLabelsEnabled) "Labels on" else "Labels off", mapLabelsEnabled)

        drawSettingsSectionLabel(canvas, right.left, rect.top + dp(58), "Safety")
        drawChoiceButton(canvas, alertsToggleBounds(rect), if (alertsEnabled) "Hazard alerts on" else "Hazard alerts off", alertsEnabled)
        drawChoiceButton(canvas, priorityTrackerButtonBounds(rect), "Alert range", priorityTrackingEnabled)

        drawSettingsSectionLabel(canvas, right.left, rect.top + dp(158), "Reference")
        drawChoiceButton(canvas, impactMethodologyButtonBounds(rect), "Impact method", false)
    }

    private fun drawMapLabelsPanel(canvas: Canvas, w: Float, h: Float) {
        val rect = settingsPanelBounds(w, h)
        drawPanelSurface(canvas, rect, panelAltColor, themeStyle.modalPanelAlpha)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(20)
        textPaint.color = textColor
        canvas.drawText("Map labels", rect.left + dp(18), rect.top + dp(34), textPaint)
        drawChoiceButton(canvas, closeButtonBounds(rect), "Back", false)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = if (isCompactSettingsPanel(rect)) sp(11) else sp(12)
        textPaint.color = mutedColor
        val labelY = if (isCompactSettingsPanel(rect)) rect.top + dp(74) else rect.top + dp(82)
        canvas.drawText("Street map labels", rect.left + dp(18), labelY, textPaint)

        drawChoiceButton(canvas, mapLabelsOnButtonBounds(rect), "Labels on", mapLabelsEnabled)
        drawChoiceButton(canvas, mapLabelsOffButtonBounds(rect), "Labels off", !mapLabelsEnabled)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = if (isCompactSettingsPanel(rect)) sp(10) else sp(11)
        textPaint.color = mutedColor
        val sourceY = if (isCompactSettingsPanel(rect)) rect.top + dp(154) else rect.top + dp(216)
        val sourceText = if (mapLabelsEnabled) {
            "Current: OpenStreetMap labeled tiles"
        } else {
            "Current: CARTO no-label tiles"
        }
        canvas.drawText(sourceText, rect.left + dp(18), sourceY, textPaint)
    }

    private fun drawImpactMethodologyPanel(canvas: Canvas, w: Float, h: Float) {
        val rect = settingsPanelBounds(w, h)
        val compact = isCompactSettingsPanel(rect)
        drawPanelSurface(canvas, rect, panelAltColor, themeStyle.modalPanelAlpha)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(20)
        textPaint.color = textColor
        canvas.drawText("Impact methodology", rect.left + dp(18), rect.top + dp(34), textPaint)
        drawChoiceButton(canvas, closeButtonBounds(rect), "Back", false)

        val items = impactMethodologyItems()
        if (compact) {
            val left = RectF(rect.left + dp(18), rect.top + dp(64), rect.centerX() - dp(10), rect.bottom - dp(62))
            val right = RectF(rect.centerX() + dp(10), left.top, rect.right - dp(18), left.bottom)
            drawMethodologyColumn(canvas, left, items.take(3))
            drawMethodologyColumn(canvas, right, items.drop(3))
        } else {
            var y = rect.top + dp(72)
            val textRect = RectF(rect.left + dp(18), y, rect.right - dp(18), rect.bottom - dp(104))
            items.forEach { item ->
                y = drawMethodologyItem(canvas, textRect, y, item.first, item.second, maxLines = 3)
            }
        }

        IMPACT_SOURCE_LABELS.forEachIndexed { index, label ->
            drawChoiceButton(canvas, impactSourceButtonBounds(rect, index), label, false)
        }
    }

    private fun impactMethodologyItems(): List<Pair<String, String>> {
        return listOf(
            "Score" to "0-100 log scale from estimated kg CO2/hr. The endpoints are 20 and 20,000 kg CO2/hr, clamped.",
            "CO2 math" to "Fuel burn benchmark x EIA CO2 per gallon. Jet fuel uses 9.75 kg/gal; aviation gasoline uses 8.31 kg/gal.",
            "Aircraft class" to "The selected aircraft type code, make, and model pick a broad benchmark class. Unknown classes show Loading, then Unavailable.",
            "Trace totals" to "Flight totals use real trace time only. The app does not store session positions and relabel them as a flight path.",
            "Not claimed" to "Exact engine fuel flow, payload, passenger emissions, SAF blend, contrails, NOx, and noise are not inferred.",
            "Lead note" to "Piston avgas may be leaded. Lead exposure is noted separately and is not part of the carbon score."
        )
    }

        private fun drawMethodologyColumn(
        canvas: Canvas,
        rect: RectF,
        items: List<Pair<String, String>>
    ) {
        var y = rect.top
        items.forEach { item ->
            y = drawMethodologyItem(canvas, rect, y, item.first, item.second, maxLines = 2)
        }
    }

    private fun drawMethodologyItem(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        label: String,
        body: String,
        maxLines: Int
    ): Float {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(11)
        textPaint.color = textColor
        canvas.drawText(label, rect.left, y, textPaint)

        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(11)
        textPaint.color = mutedColor
        val bottom = drawWrappedText(canvas, body, rect.left, y + dp(18), rect.width(), maxLines = maxLines)
        return bottom + dp(12)
    }

    private fun drawFiltersPanel(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        val rect = settingsPanelBounds(w, h)
        val compact = isCompactSettingsPanel(rect)
        drawPanelSurface(canvas, rect, panelAltColor, themeStyle.modalPanelAlpha)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(20)
        textPaint.color = textColor
        canvas.drawText("Filters", rect.left + dp(18), rect.top + dp(34), textPaint)
        drawChoiceButton(canvas, closeButtonBounds(rect), "Close", false)

        drawFilterSearchControl(canvas, rect)

        if (compact) {
            drawCompactFiltersPanelContents(canvas, rect)
        } else {
            drawPortraitFiltersPanelContents(canvas, rect)
        }

        val stats = filterStats()
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(if (compact) 10 else 11)
        textPaint.color = mutedColor
        val statsY = if (compact) rect.bottom - dp(18) else rect.bottom - dp(22)
        canvas.drawText(stats.summary, rect.left + dp(18), statsY, textPaint)
    }

    private fun drawFilterSearchControl(canvas: Canvas, panel: RectF) {
        val search = filterSearchBoxBounds(panel)
        val stroke = if (filterSearchFocused) accentGreenColor else buttonStrokeColor
        val fill = if (themeStyle.treatment == ThemeTreatment.PLAIN) buttonFillColor else withAlpha(buttonFillColor, themeStyle.controlAlpha)
        drawControlSurface(canvas, search, fill, stroke, filterSearchFocused)

        val display = filterSearchQuery.ifBlank { "Callsign, reg, ICAO hex" }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = filterSearchQuery.isNotBlank()
        textPaint.textSize = sp(13)
        textPaint.color = if (filterSearchQuery.isBlank()) mutedColor else textColor
        val maxWidth = search.width() - dp(24)
        canvas.drawText(ellipsize(display, maxWidth), search.left + dp(12), search.centerY() + dp(5), textPaint)
        if (filterSearchFocused && SystemClock.elapsedRealtime() % 1000L < 560L) {
            val visibleText = if (filterSearchQuery.isBlank()) "" else ellipsize(filterSearchQuery, maxWidth)
            val caretX = (search.left + dp(12) + textPaint.measureText(visibleText)).coerceAtMost(search.right - dp(12))
            strokePaint.color = accentGreenColor
            strokePaint.strokeWidth = dp(1.2f)
            canvas.drawLine(caretX, search.top + dp(9), caretX, search.bottom - dp(9), strokePaint)
            postInvalidateOnAnimation()
        }
        textPaint.isFakeBoldText = false

        drawChoiceButton(canvas, filterSearchFindButtonBounds(panel), "Find live", false)
        drawChoiceButton(canvas, filterSearchClearButtonBounds(panel), "Clear", false)
    }

    private fun drawPortraitFiltersPanelContents(canvas: Canvas, rect: RectF) {
        drawFilterCycleRow(canvas, filterAircraftTypeButtonBounds(rect), "Type: ${aircraftTypeFilter.shortLabel}", aircraftTypeFilter != AircraftTypeFilter.ALL)
        drawFilterCycleRow(canvas, filterAltitudeButtonBounds(rect), "Alt: ${altitudeFilter.shortLabel}", altitudeFilter != AltitudeFilter.ANY)
        drawFilterCycleRow(canvas, filterDistanceButtonBounds(rect), "Range: ${distanceFilter.shortLabel}", distanceFilter != DistanceFilter.ANY)
        drawFilterCycleRow(canvas, filterStatusButtonBounds(rect), "Status: ${flightStatusFilter.shortLabel}", flightStatusFilter != FlightStatusFilter.ANY)
        drawFilterCycleRow(canvas, filterAgeButtonBounds(rect), "Age: ${reportAgeFilter.shortLabel}", reportAgeFilter != ReportAgeFilter.ANY)
        drawFilterCycleRow(canvas, filterAlertButtonBounds(rect), if (alertVolumeFilter) "Alert volume only" else "Alert volume: off", alertVolumeFilter)
        drawChoiceButton(canvas, filterResetButtonBounds(rect), "Reset filters", filtersActive())
    }

    private fun drawCompactFiltersPanelContents(canvas: Canvas, rect: RectF) {
        drawFilterCycleRow(canvas, filterAircraftTypeButtonBounds(rect), "Type: ${aircraftTypeFilter.shortLabel}", aircraftTypeFilter != AircraftTypeFilter.ALL)
        drawFilterCycleRow(canvas, filterAltitudeButtonBounds(rect), "Alt: ${altitudeFilter.shortLabel}", altitudeFilter != AltitudeFilter.ANY)
        drawFilterCycleRow(canvas, filterDistanceButtonBounds(rect), "Range: ${distanceFilter.shortLabel}", distanceFilter != DistanceFilter.ANY)
        drawFilterCycleRow(canvas, filterStatusButtonBounds(rect), "Status: ${flightStatusFilter.shortLabel}", flightStatusFilter != FlightStatusFilter.ANY)
        drawFilterCycleRow(canvas, filterAgeButtonBounds(rect), "Age: ${reportAgeFilter.shortLabel}", reportAgeFilter != ReportAgeFilter.ANY)
        drawFilterCycleRow(canvas, filterAlertButtonBounds(rect), if (alertVolumeFilter) "Alert volume only" else "Alert volume: off", alertVolumeFilter)
        drawChoiceButton(canvas, filterResetButtonBounds(rect), "Reset", filtersActive())
    }

    private fun drawFilterCycleRow(canvas: Canvas, bounds: RectF, label: String, selected: Boolean) {
        drawChoiceButton(canvas, bounds, label, selected)
    }

    private fun drawPriorityTrackerPanel(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        val rect = priorityTrackerPanelBounds(w, h)
        drawPanelSurface(canvas, rect, panelAltColor, themeStyle.modalPanelAlpha)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(20)
        textPaint.color = textColor
        canvas.drawText("Priority tracker", rect.left + dp(18), rect.top + dp(34), textPaint)
        drawChoiceButton(canvas, priorityCloseButtonBounds(rect), "Close", false)

        if (isCompactSettingsPanel(rect)) {
            drawCompactPriorityTrackerContents(canvas, rect)
            return
        }

        drawChoiceButton(canvas, priorityTrackingToggleBounds(rect), if (priorityTrackingEnabled) "Queue on" else "Queue off", priorityTrackingEnabled)
        drawChoiceButton(canvas, priorityRingToggleBounds(rect), if (priorityRangeCircleVisible) "Alert ring on" else "Alert ring off", priorityRangeCircleVisible)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText("Alert range", rect.left + dp(18), rect.top + dp(136), textPaint)
        drawAdjusterRow(canvas, rect, rect.top + dp(162), "Horizontal", formatFeetSetting(alertDistanceFeet), alertDistanceMinusBounds(rect), alertDistancePlusBounds(rect))
        drawAdjusterRow(canvas, rect, rect.top + dp(250), "Vertical", formatFeetSetting(alertAltitudeFeet), alertAltitudeMinusBounds(rect), alertAltitudePlusBounds(rect))

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText("Aircraft in queue", rect.left + dp(18), rect.top + dp(344), textPaint)

        val rows = priorityAircraftSnapshot()
        if (rows.isEmpty()) {
            textPaint.isFakeBoldText = true
            textPaint.textSize = sp(17)
            textPaint.color = textColor
            canvas.drawText(if (priorityTrackingEnabled) "No aircraft in queue" else "Queue is off", rect.left + dp(18), rect.top + dp(386), textPaint)
            textPaint.isFakeBoldText = false
            return
        }

        var y = rect.top + dp(382)
        rows.take(PRIORITY_PANEL_ROWS).forEach { item ->
            y = drawPriorityAircraftRow(canvas, rect, y, item)
        }
        if (rows.size > PRIORITY_PANEL_ROWS) {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = false
            textPaint.textSize = sp(11)
            textPaint.color = mutedColor
            canvas.drawText("+${rows.size - PRIORITY_PANEL_ROWS} more", rect.left + dp(18), y + dp(8), textPaint)
        }
    }

    private fun drawCompactPriorityTrackerContents(canvas: Canvas, rect: RectF) {
        val leftArea = priorityAlertControlArea(rect)
        val right = compactSettingsRightColumn(rect)
        val rows = priorityAircraftSnapshot()

        drawChoiceButton(canvas, priorityTrackingToggleBounds(rect), if (priorityTrackingEnabled) "Queue on" else "Queue off", priorityTrackingEnabled)
        drawChoiceButton(canvas, priorityRingToggleBounds(rect), if (priorityRangeCircleVisible) "Alert ring on" else "Alert ring off", priorityRangeCircleVisible)
        drawAdjusterRow(canvas, leftArea, rect.top + dp(118), "Horizontal", formatFeetSetting(alertDistanceFeet), alertDistanceMinusBounds(rect), alertDistancePlusBounds(rect))
        drawAdjusterRow(canvas, leftArea, rect.top + dp(186), "Vertical", formatFeetSetting(alertAltitudeFeet), alertAltitudeMinusBounds(rect), alertAltitudePlusBounds(rect))

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(11)
        textPaint.color = mutedColor
        canvas.drawText("Aircraft in queue", right.left, rect.top + dp(58), textPaint)

        if (rows.isEmpty()) {
            textPaint.isFakeBoldText = true
            textPaint.textSize = sp(16)
            textPaint.color = textColor
            canvas.drawText(if (priorityTrackingEnabled) "No aircraft in queue" else "Queue is off", right.left, rect.top + dp(94), textPaint)
            textPaint.isFakeBoldText = false
            return
        }

        var y = rect.top + dp(94)
        rows.take(3).forEach { item ->
            y = drawPriorityAircraftRow(canvas, RectF(right.left - dp(18), rect.top, rect.right, rect.bottom), y, item)
        }
        if (rows.size > 3) {
            textPaint.isFakeBoldText = false
            textPaint.textSize = sp(11)
            textPaint.color = mutedColor
            canvas.drawText("+${rows.size - 3} more", right.left, y + dp(4), textPaint)
        }
    }

    private fun drawPriorityAircraftRow(canvas: Canvas, panel: RectF, y: Float, aircraft: Aircraft): Float {
        val extreme = isExtremePriority(aircraft)
        val row = RectF(panel.left + dp(18), y - dp(22), panel.right - dp(18), y + dp(34))
        paint.style = Paint.Style.FILL
        paint.color = if (extreme) withAlpha(dangerColor, 60) else withAlpha(textColor, themeColors.rowFillAlpha)
        val rowRadius = if (themeStyle.treatment == ThemeTreatment.PLAIN) dp(6) else controlRadius().coerceAtLeast(dp(1))
        canvas.drawRoundRect(row, rowRadius, rowRadius, paint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(15)
        textPaint.color = if (extreme) dangerColor else textColor
        canvas.drawText(aircraft.registration ?: aircraft.callsign, row.left + dp(10), y, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = sp(13)
        textPaint.color = textColor
        canvas.drawText(formatAltitudeValue(aircraft.altitudeM), row.right - dp(10), y, textPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(11)
        textPaint.color = mutedColor
        canvas.drawText("${formatDistance(displayDistanceMeters(aircraft))}  ${formatAge(aircraft)}", row.left + dp(10), y + dp(20), textPaint)
        return y + dp(64)
    }

    private fun drawAdjusterRow(canvas: Canvas, panel: RectF, y: Float, label: String, value: String, minus: RectF, plus: RectF) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText(label, panel.left + dp(18), y, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(13)
        textPaint.color = textColor
        canvas.drawText(value, panel.centerX(), y + dp(32), textPaint)
        drawChoiceButton(canvas, minus, "-", false)
        drawChoiceButton(canvas, plus, "+", false)
    }

    private fun drawChoiceButton(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
        val previousAlign = textPaint.textAlign
        val color = if (selected) accentGreenColor else buttonStrokeColor
        paint.style = Paint.Style.FILL
        val fill = if (selected) {
            withAlpha(color, themeColors.selectedFillAlpha)
        } else if (themeStyle.treatment == ThemeTreatment.PLAIN) {
            buttonFillColor
        } else {
            withAlpha(buttonFillColor, themeStyle.controlAlpha)
        }
        drawControlSurface(canvas, rect, fill, color, selected)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(12)
        while (textPaint.textSize > sp(8) && textPaint.measureText(label) > rect.width() - dp(12)) {
            textPaint.textSize -= dp(0.5f)
        }
        textPaint.color = if (selected) accentGreenColor else textColor
        val metrics = textPaint.fontMetrics
        canvas.drawText(label, rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textAlign = previousAlign
    }

    private fun handleTap(x: Float, y: Float) {
        if (filtersOpen) {
            val panel = settingsPanelBounds(contentWidth(), contentHeight())
            when {
                closeButtonBounds(panel).contains(x, y) -> {
                    filtersOpen = false
                    clearFilterSearchFocus()
                }
                filterSearchBoxBounds(panel).contains(x, y) -> focusFilterSearch()
                filterSearchFindButtonBounds(panel).contains(x, y) -> submitFilterSearch()
                filterSearchClearButtonBounds(panel).contains(x, y) -> setFilterSearchQuery("")
                filterAircraftTypeButtonBounds(panel).contains(x, y) -> setAircraftTypeFilter(aircraftTypeFilter.next())
                filterAltitudeButtonBounds(panel).contains(x, y) -> setAltitudeFilter(altitudeFilter.next())
                filterDistanceButtonBounds(panel).contains(x, y) -> setDistanceFilter(distanceFilter.next())
                filterStatusButtonBounds(panel).contains(x, y) -> setFlightStatusFilter(flightStatusFilter.next())
                filterAgeButtonBounds(panel).contains(x, y) -> setReportAgeFilter(reportAgeFilter.next())
                filterAlertButtonBounds(panel).contains(x, y) -> setAlertVolumeFilter(!alertVolumeFilter)
                filterResetButtonBounds(panel).contains(x, y) -> resetFilters()
                else -> clearFilterSearchFocus()
            }
            invalidate()
            return
        }

        if (priorityTrackerOpen) {
            val panel = priorityTrackerPanelBounds(contentWidth(), contentHeight())
            when {
                priorityCloseButtonBounds(panel).contains(x, y) -> priorityTrackerOpen = false
                priorityTrackingToggleBounds(panel).contains(x, y) -> setPriorityTrackingEnabled(!priorityTrackingEnabled)
                priorityRingToggleBounds(panel).contains(x, y) -> setPriorityRangeCircleVisible(!priorityRangeCircleVisible)
                alertDistanceMinusBounds(panel).contains(x, y) -> setAlertDistanceFeet(alertDistanceFeet - 1000f)
                alertDistancePlusBounds(panel).contains(x, y) -> setAlertDistanceFeet(alertDistanceFeet + 1000f)
                alertAltitudeMinusBounds(panel).contains(x, y) -> setAlertAltitudeFeet(alertAltitudeFeet - 500f)
                alertAltitudePlusBounds(panel).contains(x, y) -> setAlertAltitudeFeet(alertAltitudeFeet + 500f)
            }
            invalidate()
            return
        }

        if (detailsOpen) {
            val panel = detailsPanelBounds(contentWidth(), contentHeight())
            val evidence = aircraftPhotoEvidence
            when {
                environmentalImpactOpen && detailsCloseButtonBounds(panel).contains(x, y) -> environmentalImpactOpen = false
                environmentalImpactOpen -> Unit
                usageOpen && detailsCloseButtonBounds(panel).contains(x, y) -> usageOpen = false
                usageOpen -> Unit
                photoEvidenceOpen && detailsCloseButtonBounds(panel).contains(x, y) -> {
                    photoEvidenceOpen = false
                    detailsScrollY = 0f
                }
                photoEvidenceOpen && evidence?.imageUrl?.isNotBlank() == true && photoImageSourceButtonBounds(panel).contains(x, y) -> openUrl(evidence.imageUrl)
                photoEvidenceOpen && evidence?.pageUrl?.isNotBlank() == true && photoPageSourceButtonBounds(panel).contains(x, y) -> openUrl(evidence.pageUrl)
                photoEvidenceOpen -> Unit
                detailsCloseButtonBounds(panel).contains(x, y) -> {
                    detailsOpen = false
                    photoEvidenceOpen = false
                    usageOpen = false
                    environmentalImpactOpen = false
                }
                detailsUsageButtonBounds(panel).contains(x, y) -> displayedTraffic().aircraft?.let { openAircraftUsage(it) }
                detailsImpactButtonBounds(panel).contains(x, y) -> displayedTraffic().aircraft?.let { openAircraftImpact(it) }
                aircraftPhotoEvidence != null && currentDetailsPhotoBounds(panel, contentWidth(), contentHeight()).contains(x, y) -> {
                    photoEvidenceOpen = true
                    detailsScrollY = 0f
                }
            }
            invalidate()
            return
        }

        if (settingsOpen) {
            val panel = settingsPanelBounds(contentWidth(), contentHeight())
            if (impactMethodologyOpen) {
                when {
                    closeButtonBounds(panel).contains(x, y) -> impactMethodologyOpen = false
                    else -> IMPACT_SOURCE_URLS.forEachIndexed { index, url ->
                        if (impactSourceButtonBounds(panel, index).contains(x, y)) {
                            openUrl(url)
                        }
                    }
                }
                invalidate()
                return
            }
            if (mapLabelsOpen) {
                when {
                    closeButtonBounds(panel).contains(x, y) -> mapLabelsOpen = false
                    mapLabelsOnButtonBounds(panel).contains(x, y) -> setMapLabelsEnabled(true)
                    mapLabelsOffButtonBounds(panel).contains(x, y) -> setMapLabelsEnabled(false)
                }
                invalidate()
                return
            }
            when {
                closeButtonBounds(panel).contains(x, y) -> {
                    settingsOpen = false
                    impactMethodologyOpen = false
                }
                imperialButtonBounds(panel).contains(x, y) -> setUnits(UnitSystem.IMPERIAL)
                metricButtonBounds(panel).contains(x, y) -> setUnits(UnitSystem.METRIC)
                mapSourceButtonBounds(panel).contains(x, y) -> toggleMapSource()
                mapLabelsButtonBounds(panel).contains(x, y) -> mapLabelsOpen = true
                themeButtonBounds(panel).contains(x, y) -> setVisualTheme(nextVisualTheme())
                alertsToggleBounds(panel).contains(x, y) -> setAlertsEnabled(!alertsEnabled)
                impactMethodologyButtonBounds(panel).contains(x, y) -> impactMethodologyOpen = true
                priorityTrackerButtonBounds(panel).contains(x, y) -> {
                    settingsOpen = false
                    impactMethodologyOpen = false
                    priorityTrackerOpen = true
                }
            }
            invalidate()
            return
        }

        val w = contentWidth()
        val h = contentHeight()
        val viewport = latestLocation?.let { viewportFor(it, w, h) }
        val pathButtonHit = viewport != null && flightPathButtonBounds(w, h).contains(x, y)
        when {
            previousFlightsButtonBounds(w, h).contains(x, y) && shouldShowPreviousFlightsButton() -> togglePreviousFlights()
            clearFlightPathButtonBounds(w, h).contains(x, y) && shouldShowClearPathButton() -> clearSelectedFlightPath()
            pathButtonHit && hasSelectedFlightPath() -> showSelectedFlightPath()
            recenterButtonBounds(w, h).contains(x, y) && !followingLocation -> recenterOnLocation()
            settingsButtonBounds(w, h).contains(x, y) -> settingsOpen = true
            filtersButtonBounds(w, h).contains(x, y) -> {
            filtersOpen = true
            settingsOpen = false
            mapLabelsOpen = false
            impactMethodologyOpen = false
            }
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
                val estimated = displayAircraftPosition(item)
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
        usageOpen = false
        environmentalImpactOpen = false
        photoEvidenceOpen = false
        detailsScrollY = 0f
        detailsMaxScrollY = 0f
        aircraftDetails = null
        aircraftPhoto = null
        aircraftPhotoEvidence = null
        aircraftDetailsStatus = "Loading live aircraft details"
        aircraftPhotoStatus = "Loading exact-aircraft photo"
        requestAircraftDetails(aircraft)
    }

    private fun openAircraftUsage(aircraft: Aircraft) {
        if (selectedAircraftId != aircraft.icao24) {
            selectAircraft(aircraft)
        } else if (!hasSelectedFlightPath() && !isFlightPathLoading(aircraft)) {
            requestFlightPath(aircraft.icao24)
        }
        usageOpen = true
        environmentalImpactOpen = false
        photoEvidenceOpen = false
        detailsScrollY = 0f
        detailsMaxScrollY = 0f
    }

    private fun openAircraftImpact(aircraft: Aircraft) {
        if (selectedAircraftId != aircraft.icao24) {
            selectAircraft(aircraft)
        } else if (!hasSelectedFlightPath() && !isFlightPathLoading(aircraft)) {
            requestFlightPath(aircraft.icao24)
        }
        environmentalImpactOpen = true
        usageOpen = false
        photoEvidenceOpen = false
        detailsScrollY = 0f
        detailsMaxScrollY = 0f
    }

    private fun selectAircraft(aircraft: Aircraft) {
        selectedAircraftId = aircraft.icao24
        selectedAircraftSnapshot = aircraft
        selectedFlightPathAircraftId = null
        selectedFlightPath = null
        selectedFlightPathVisible = false
        selectedPreviousFlightsVisible = false
        selectedPathFitRequested = false
        usageOpen = false
        environmentalImpactOpen = false
        militaryOriginAircraftId = aircraft.icao24
        militaryOriginStatus = if (aircraft.isMilitary) "Waiting for flight path origin" else "Unavailable"
        militaryOriginRequestKey = null
        requestFlightPath(aircraft.icao24)
    }

    private fun requestAircraftDetails(aircraft: Aircraft) {
        val requestedId = aircraft.icao24
        val requestToken = ++detailsRequestToken
        executor.execute {
            val details = aircraftDetailsClient.fetchDetails(aircraft.icao24, aircraft.callsign, aircraft.registration)
            post {
                if (isCurrentDetailsRequest(requestedId, requestToken)) {
                    aircraftDetails = details
                    aircraftDetailsStatus = if (!hasAircraftMetadata(details)) {
                        "Metadata unavailable from configured APIs"
                    } else {
                        "Metadata from ${details.registrySource ?: "configured APIs"}"
                    }
                    aircraftPhotoStatus = "Searching real photo sources"
                    invalidate()
                }
            }
            val photo = fetchAircraftPhoto(aircraft, details)
            post {
                if (!isCurrentDetailsRequest(requestedId, requestToken)) return@post
                when (photo) {
                    is AircraftPhotoResult.Found -> {
                        aircraftPhoto = photo.bitmap
                        aircraftPhotoStatus = photo.note
                        aircraftPhotoEvidence = photo.evidence
                    }
                    is AircraftPhotoResult.Unavailable -> {
                        aircraftPhoto = null
                        aircraftPhotoStatus = photo.reason
                        aircraftPhotoEvidence = null
                    }
                }
                invalidate()
            }
        }
    }

    private fun isCurrentDetailsRequest(requestedId: String, requestToken: Long): Boolean {
        return detailsOpen &&
            detailsRequestToken == requestToken &&
            displayedTraffic().aircraft?.icao24 == requestedId
    }

    private fun hasAircraftMetadata(details: AircraftDetails): Boolean {
        return details.registration != null ||
            details.manufacturer != null ||
            details.type != null ||
            details.typeCode != null ||
            details.owner != null ||
            details.route != null ||
            details.originAirport != null ||
            details.destinationAirport != null
    }

    private fun requestMilitaryOriginIfNeeded(aircraft: Aircraft) {
        val key = aircraft.icao24.lowercase(Locale.US)
        if (!aircraft.isMilitary || selectedAircraftId?.lowercase(Locale.US) != key) return
        val firstPoint = selectedPathSegments(visibleOnly = false)
            ?.firstOrNull()
            ?.points
            ?.firstOrNull()
            ?: return
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
            ?: fetchVerifiedGenericSearchPhoto(aircraft, details)
            ?: fetchInvestigableSearchPhoto(aircraft, details)
            ?: AircraftPhotoResult.Unavailable("Exact, representative, and search photos unavailable")
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
        val safeUrl = httpsUrl(apiUrl) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safeUrl.openConnection() as HttpURLConnection).apply {
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
        representativeModelNames(details).forEach { model ->
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
                fetchWikipediaPageImageUrls(query).take(MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY).forEach { url ->
                    fetchBitmap(url)?.let { bitmap ->
                        return AircraftPhotoResult.Found(bitmap, "Representative $model photo from Wikipedia; not this exact aircraft")
                    }
                }
            }
        }
        return null
    }

    private fun fetchVerifiedGenericSearchPhoto(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult.Found? {
        val registration = normalizedRegistration(details.registration ?: aircraft.registration)
        val exactTerms = listOfNotNull(registration, aircraft.icao24.takeIf { it.isNotBlank() })
        exactPhotoQueries(registration, aircraft.icao24).forEach { query ->
            val candidates = fetchWikimediaSearchImageCandidates(query) +
                fetchOpenverseImageCandidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
            candidates.distinctBy { it.imageUrl }.forEach { candidate ->
                verifiedSearchPhoto(
                    candidate = candidate,
                    query = query,
                    note = "Verified exact-aircraft search result for ${registration ?: aircraft.icao24.uppercase(Locale.US)}",
                    verificationTerms = exactTerms
                )?.let { return it }
            }
        }

        representativeModelNames(details).forEach { model ->
            val verificationTerms = photoVerificationTerms(details, model)
            representativePhotoQueries(details, model).forEach { query ->
                val candidates = fetchWikimediaSearchImageCandidates(query) +
                    fetchOpenverseImageCandidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
                candidates.distinctBy { it.imageUrl }.forEach { candidate ->
                    verifiedSearchPhoto(
                        candidate = candidate,
                        query = query,
                        note = "Verified search result for $model; not this exact aircraft",
                        verificationTerms = verificationTerms
                    )?.let { return it }
                }
            }
        }
        return null
    }

    private fun fetchInvestigableSearchPhoto(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult.Found? {
        val registration = normalizedRegistration(details.registration ?: aircraft.registration)
        val exactQueries = exactPhotoQueries(registration, aircraft.icao24)
        val representativeQueries = representativeModelNames(details).flatMap { representativePhotoQueries(details, it) }
        (exactQueries + representativeQueries).distinct().forEach { query ->
            val candidates = fetchWikimediaSearchImageCandidates(query) +
                fetchOpenverseImageCandidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
            candidates.distinctBy { it.imageUrl }.forEach { candidate ->
                val bitmap = fetchBitmap(candidate.imageUrl) ?: return@forEach
                val evidence = PhotoEvidence(
                    sourceName = candidate.sourceName,
                    imageUrl = candidate.imageUrl,
                    pageUrl = candidate.pageUrl,
                    searchQuery = query,
                    quote = candidate.investigationText(),
                    matchedTerms = emptyList()
                )
                return AircraftPhotoResult.Found(
                    bitmap,
                    "Unverified search result; tap photo to inspect source",
                    evidence
                )
            }
        }
        return null
    }

    private fun verifiedSearchPhoto(
        candidate: SearchImageCandidate,
        query: String,
        note: String,
        verificationTerms: List<String>
    ): AircraftPhotoResult.Found? {
        val quote = fetchVerificationQuote(candidate.pageUrl, verificationTerms)
            ?: candidate.verificationText?.let { quoteFromText(it, verificationTerms) }
            ?: return null
        val bitmap = fetchBitmap(candidate.imageUrl) ?: return null
        val evidence = PhotoEvidence(
            sourceName = candidate.sourceName,
            imageUrl = candidate.imageUrl,
            pageUrl = candidate.pageUrl,
            searchQuery = query,
            quote = quote.text,
            matchedTerms = quote.matchedTerms
        )
        return AircraftPhotoResult.Found(
            bitmap,
            note,
            evidence
        )
    }

    private fun SearchImageCandidate.investigationText(): String {
        val titleText = title.takeIf { it.isNotBlank() }?.let { "Title: $it" }
        val metadataText = verificationText?.takeIf { it.isNotBlank() }?.let { "Metadata: $it" }
        return listOfNotNull(titleText, metadataText, "Source page: $pageUrl")
            .joinToString("  ")
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
            "AA1" -> "Grumman American AA-1 Yankee"
            "AA5" -> "Grumman American AA-5 Traveler"
            "AG5B" -> "American General AG-5B Tiger"
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
            "C120" -> "Cessna 120"
            "C140" -> "Cessna 140"
            "C150" -> "Cessna 150"
            "C152" -> "Cessna 152"
            "C172" -> "Cessna 172"
            "C175" -> "Cessna 175 Skylark"
            "C177" -> "Cessna 177 Cardinal"
            "C180" -> "Cessna 180"
            "C182" -> "Cessna 182"
            "C195" -> "Cessna 195"
            "C185" -> "Cessna 185"
            "C206" -> "Cessna 206"
            "C208" -> "Cessna 208 Caravan"
            "C210" -> "Cessna 210"
            "C337" -> "Cessna 337 Skymaster"
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
            "GA7" -> "Grumman American GA-7 Cougar"
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
            "M20P" -> "Mooney M20"
            "M20T" -> "Mooney M20"
            "RV6" -> "Van's RV-6"
            "RV7" -> "Van's RV-7"
            "RV8" -> "Van's RV-8"
            "RV9" -> "Van's RV-9"
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

    private fun representativeModelNames(details: AircraftDetails): List<String> {
        val base = representativeModelName(details)
        val type = details.type?.trim()
        val manufacturer = details.manufacturer?.trim()
        val aliases = mutableListOf<String>()
        if (base != null) aliases += base
        if (manufacturer != null && type != null) {
            aliases += "$manufacturer $type"
            manufacturerAliases(manufacturer).forEach { alias -> aliases += "$alias $type" }
            typeModelAliases(type).forEach { aliasType ->
                aliases += "$manufacturer $aliasType"
                manufacturerAliases(manufacturer).forEach { alias -> aliases += "$alias $aliasType" }
            }
        }
        type?.let { aliases += it }
        return aliases
            .map { normalizeAircraftSearchName(it) }
            .filter { it.length >= 3 }
            .distinct()
    }

    private fun manufacturerAliases(manufacturer: String): List<String> {
        val normalized = manufacturer.uppercase(Locale.US)
        return when {
            "GRUMMAN" in normalized -> listOf("Grumman American", "Grumman")
            "AMERICAN" in normalized && "GENERAL" in normalized -> listOf("American General", "Grumman American")
            "CESSNA" in normalized -> listOf("Cessna")
            "PIPER" in normalized -> listOf("Piper")
            "BEECH" in normalized -> listOf("Beechcraft", "Beech")
            "CIRRUS" in normalized -> listOf("Cirrus")
            "DIAMOND" in normalized -> listOf("Diamond")
            "MOONEY" in normalized -> listOf("Mooney")
            "ROBINSON" in normalized -> listOf("Robinson")
            "VANS" in normalized || "VAN'S" in normalized -> listOf("Van's", "Vans")
            "PILATUS" in normalized -> listOf("Pilatus")
            else -> emptyList()
        }
    }

    private fun typeModelAliases(type: String): List<String> {
        val normalized = type.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), " ").trim()
        val compact = normalized.replace(" ", "")
        return when {
            compact == "AA1" || compact.startsWith("AA1") -> listOf("AA-1", "AA1", "American Yankee")
            compact.startsWith("AA5") -> listOf("AA-5", "AA5", "Cheetah", "Tiger")
            compact.startsWith("GA7") -> listOf("GA-7", "GA7", "Cougar")
            compact.startsWith("C120") -> listOf("120")
            compact.startsWith("C140") -> listOf("140")
            compact.startsWith("C150") -> listOf("150")
            compact.startsWith("C152") -> listOf("152")
            compact.startsWith("C172") -> listOf("172 Skyhawk", "172")
            compact.startsWith("C177") -> listOf("177 Cardinal", "177")
            compact.startsWith("C182") -> listOf("182 Skylane", "182")
            compact.startsWith("C185") -> listOf("185 Skywagon", "185")
            compact.startsWith("C206") -> listOf("206 Stationair", "206")
            compact.startsWith("C210") -> listOf("210 Centurion", "210")
            compact.startsWith("C337") -> listOf("337 Skymaster", "337")
            compact.startsWith("BE33") -> listOf("Bonanza")
            compact.startsWith("BE35") -> listOf("Bonanza")
            compact.startsWith("BE36") -> listOf("Bonanza")
            compact.startsWith("BE55") -> listOf("Baron")
            compact.startsWith("BE58") -> listOf("Baron")
            compact.startsWith("BE20") -> listOf("King Air 200", "King Air")
            compact.startsWith("B350") -> listOf("King Air 350", "King Air")
            compact.startsWith("PA28") -> listOf("PA-28 Cherokee", "PA-28")
            compact.startsWith("PA32") -> listOf("PA-32 Cherokee Six", "PA-32")
            compact.startsWith("PA34") -> listOf("PA-34 Seneca", "PA-34")
            compact.startsWith("PA44") -> listOf("PA-44 Seminole", "PA-44")
            compact.startsWith("PA46") || compact.startsWith("P46T") -> listOf("PA-46 Malibu", "PA-46 Mirage", "PA-46 Meridian", "PA-46")
            compact.startsWith("SR20") -> listOf("SR20")
            compact.startsWith("SR22") || compact.startsWith("S22T") -> listOf("SR22", "SR22T")
            compact.startsWith("DA40") -> listOf("DA40")
            compact.startsWith("DA42") -> listOf("DA42")
            compact.startsWith("M20") -> listOf("M20")
            compact.startsWith("RV6") -> listOf("RV-6", "RV6")
            compact.startsWith("RV7") -> listOf("RV-7", "RV7")
            compact.startsWith("RV8") -> listOf("RV-8", "RV8")
            compact.startsWith("RV9") -> listOf("RV-9", "RV9")
            compact.startsWith("R44") -> listOf("R44 Raven", "R44")
            compact.startsWith("R66") -> listOf("R66")
            compact.startsWith("PC12") -> listOf("PC-12", "PC12")
            else -> emptyList()
        }
    }

    private fun normalizeAircraftSearchName(value: String): String {
        return value
            .replace(Regex("\\bINC\\.?\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bCORP\\.?\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bAVN\\.?\\b", RegexOption.IGNORE_CASE), " Aviation ")
            .replace(Regex("\\s+"), " ")
            .trim()
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

    private fun exactPhotoQueries(registration: String?, icao24: String): List<String> {
        return listOfNotNull(
            registration?.let { "\"$it\" aircraft photo" },
            registration?.let { "$it aircraft" },
            icao24.takeIf { it.isNotBlank() }?.let { "\"${it.uppercase(Locale.US)}\" aircraft" }
        ).distinct()
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
                if (mime.startsWith("image/") && isAllowedHttpsImageUrl(url)) urls += url
            }
            urls
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchOpenverseImageCandidates(query: String): List<SearchImageCandidate> {
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
            val candidates = mutableListOf<SearchImageCandidate>()
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val imageUrl = item.optString("url").trim()
                val pageUrl = item.optString("foreign_landing_url").trim()
                val title = item.optString("title").trim()
                val lowerTitle = title.lowercase(Locale.US)
                if (
                    isAllowedHttpsImageUrl(imageUrl) &&
                    pageUrl.startsWith("https://", ignoreCase = true) &&
                    !lowerTitle.contains("logo") &&
                    !lowerTitle.contains("diagram")
                ) {
                    val tags = item.optJSONArray("tags")?.let { tagArray ->
                        (0 until tagArray.length()).mapNotNull { tagIndex ->
                            tagArray.optJSONObject(tagIndex)?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
                        }
                    }.orEmpty()
                    candidates += SearchImageCandidate(
                        imageUrl = imageUrl,
                        pageUrl = pageUrl,
                        sourceName = item.optString("provider").trim().ifEmpty { "Openverse source" },
                        title = title,
                        verificationText = listOf(
                            title,
                            item.optString("creator").trim(),
                            tags.take(8).joinToString(" ")
                        ).filter { it.isNotBlank() }.joinToString(" ")
                    )
                }
            }
            candidates.distinctBy { it.imageUrl }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchWikimediaSearchImageCandidates(query: String): List<SearchImageCandidate> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch=$encoded&prop=imageinfo&iiprop=url|mime|extmetadata"
        val pages = fetchJsonObject(apiUrl)
            ?.optJSONObject("query")
            ?.optJSONObject("pages")
            ?: return emptyList()
        val candidates = mutableListOf<SearchImageCandidate>()
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            val title = page.optString("title").removePrefix("File:").trim()
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val mime = info.optString("mime")
            val imageUrl = info.optString("url").trim()
            val pageUrl = info.optString("descriptionurl").trim()
            if (!mime.startsWith("image/") || !isAllowedHttpsImageUrl(imageUrl)) continue
            if (!pageUrl.startsWith("https://", ignoreCase = true)) continue
            val metadata = info.optJSONObject("extmetadata")
            val metadataText = listOfNotNull(
                title,
                metadata?.optJSONObject("ObjectName")?.optString("value"),
                metadata?.optJSONObject("ImageDescription")?.optString("value"),
                metadata?.optJSONObject("Categories")?.optString("value")
            ).joinToString(" ")
            candidates += SearchImageCandidate(
                imageUrl = imageUrl,
                pageUrl = pageUrl,
                sourceName = "Wikimedia Commons",
                title = title,
                verificationText = normalizeHtmlText(metadataText)
            )
        }
        return candidates.distinctBy { it.imageUrl }.take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
    }

    private fun fetchVerificationQuote(pageUrl: String, terms: List<String>): VerificationQuote? {
        val html = fetchText(pageUrl) ?: return null
        return quoteFromText(normalizeHtmlText(html), terms)
    }

    private fun quoteFromText(text: String, terms: List<String>): VerificationQuote? {
        val upper = text.uppercase(Locale.US)
        val matched = terms.filter { term -> upper.contains(term.uppercase(Locale.US)) }.distinct()
        if (matched.isEmpty()) return null
        val firstIndex = matched.mapNotNull { term ->
            val index = upper.indexOf(term.uppercase(Locale.US))
            if (index >= 0) index else null
        }.minOrNull() ?: return null
        val start = max(0, firstIndex - 120)
        val end = min(text.length, firstIndex + 180)
        return VerificationQuote(
            text = text.substring(start, end).trim(),
            matchedTerms = matched.take(4)
        )
    }

    private fun photoVerificationTerms(details: AircraftDetails, model: String): List<String> {
        val familyTerms = listOfNotNull(
            Regex("""Airbus A\d{3}""").find(model)?.value,
            Regex("""Boeing \d{3}""").find(model)?.value,
            Regex("""Cessna \d{3}""").find(model)?.value,
            Regex("""Piper PA-\d{2}""").find(model)?.value,
            Regex("""Embraer \d{3}""").find(model)?.value
        )
        return listOfNotNull(
            model,
            model.substringAfterLast(" ").takeIf { it.length >= 3 && it.any(Char::isDigit) },
            details.type,
            details.typeCode
        ).plus(familyTerms)
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
    }

    private fun normalizeHtmlText(html: String): String {
        return html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
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
                if (isAllowedHttpsImageUrl(url)) urls += url
            }
        }
        return urls.distinct()
    }

    private fun fetchJsonObject(url: String): JSONObject? {
        val safeUrl = httpsUrl(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safeUrl.openConnection() as HttpURLConnection).apply {
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

    private fun fetchText(url: String): String? {
        val safeUrl = httpsUrl(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safeUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = PHOTO_TEXT_READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchBitmap(url: String): Bitmap? {
        if (!isAllowedHttpsImageUrl(url)) return null
        val safeUrl = httpsUrl(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safeUrl.openConnection() as HttpURLConnection).apply {
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
            is String -> value.takeIf { isAllowedHttpsImageUrl(it) }
            else -> null
        }
    }

    private fun isAllowedHttpsImageUrl(value: String?): Boolean {
        val url = value?.trim() ?: return false
        return url.startsWith("https://", ignoreCase = true) && IMAGE_URL_PATTERN.containsMatchIn(url)
    }

    private fun httpsUrl(value: String): URL? {
        return try {
            URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
        } catch (_: Exception) {
            null
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
        if (!hasSelectedFlightPath()) return
        displayedTraffic().aircraft?.let { selectedAircraftSnapshot = it }
        selectedFlightPathVisible = true
        fitSelectedFlightPath()
        invalidate()
    }

    private fun clearSelectedFlightPath() {
        selectedFlightPathAircraftId = null
        selectedFlightPath = null
        selectedFlightPathVisible = false
        selectedPreviousFlightsVisible = false
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
        prefs.edit { putFloat(FlightAlertSettings.KEY_ZOOM, zoom.toFloat()) }
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

    private fun shouldShowPreviousFlightsButton(): Boolean {
        return selectedFlightPathVisible && hasPreviousFlightSegments()
    }

    private fun hasPreviousFlightSegments(): Boolean {
        val id = selectedAircraftId?.lowercase(Locale.US) ?: return false
        val trace = selectedFlightPath ?: return false
        return selectedFlightPathAircraftId == id && trace.previousSegments.isNotEmpty()
    }

    private fun togglePreviousFlights() {
        if (!shouldShowPreviousFlightsButton()) return
        selectedPreviousFlightsVisible = !selectedPreviousFlightsVisible
        selectedPathFitRequested = false
        if (selectedPreviousFlightsVisible) fitSelectedFlightPath()
    }

    private fun hasSelectedFlightPath(): Boolean {
        val id = selectedAircraftId?.lowercase(Locale.US) ?: return false
        val trace = selectedFlightPath ?: return false
        return selectedFlightPathAircraftId == id && trace.segments.isNotEmpty()
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
        if (settingsOpen || detailsOpen || priorityTrackerOpen || filtersOpen) return true
        val w = contentWidth()
        val h = contentHeight()
        return (!followingLocation && recenterButtonBounds(w, h).contains(x, y)) ||
            (shouldShowPathButton(viewportFor(latestLocation ?: return true, w, h)) && flightPathButtonBounds(w, h).contains(x, y)) ||
            (shouldShowPreviousFlightsButton() && previousFlightsButtonBounds(w, h).contains(x, y)) ||
            (shouldShowClearPathButton() && clearFlightPathButtonBounds(w, h).contains(x, y)) ||
            settingsButtonBounds(w, h).contains(x, y) ||
            filtersButtonBounds(w, h).contains(x, y) ||
            infoPanelBounds(w, h).contains(x, y) ||
            topStatusBounds(w, h).contains(x, y)
    }

    private fun setUnits(next: UnitSystem) {
        units = next
        prefs.edit { putString(FlightAlertSettings.KEY_UNITS, units.name) }
    }

    private fun nextVisualTheme(): FlightAlertSettings.VisualTheme {
        val themes = FlightAlertSettings.VisualTheme.entries
        return themes[(visualTheme.ordinal + 1) % themes.size]
    }

    private fun setVisualTheme(next: FlightAlertSettings.VisualTheme) {
        visualTheme = next
        prefs.edit { putString(FlightAlertSettings.KEY_VISUAL_THEME, visualTheme.name) }
        setBackgroundColor(mapEmptyColor)
        applyThemeTypeface()
        updateHostSystemBars()
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun updateHostSystemBars() {
        val window = (context as? Activity)?.window ?: return
        window.statusBarColor = themeColors.systemBar
        window.navigationBarColor = themeColors.systemBar
    }


    private fun toggleMapSource() {
        mapSource = if (mapSource == TileSource.STREET) TileSource.SATELLITE else TileSource.STREET
        synchronized(tileCache) { tileCache.clear() }
        synchronized(requestedTiles) { requestedTiles.clear() }
        prefs.edit { putString(FlightAlertSettings.KEY_MAP_SOURCE, mapSource.name) }
        mapStatus = "Loading ${mapSource.displayName.lowercase(Locale.US)} tiles"
        invalidate()
    }

    private fun setMapLabelsEnabled(enabled: Boolean) {
        if (mapLabelsEnabled == enabled) return
        mapLabelsEnabled = enabled
        synchronized(tileCache) { tileCache.clear() }
        synchronized(requestedTiles) { requestedTiles.clear() }
        prefs.edit { putBoolean(FlightAlertSettings.KEY_MAP_LABELS_ENABLED, mapLabelsEnabled) }
        mapStatus = "Loading ${mapSource.displayName.lowercase(Locale.US)} tiles"
        invalidate()
    }

    private fun setAlertsEnabled(enabled: Boolean) {
        alertsEnabled = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, alertsEnabled) }
        updateMonitoringService()
    }

    private fun setAlertDistanceFeet(next: Float) {
        alertDistanceFeet = next.coerceIn(500f, 60000f)
        prefs.edit { putFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, alertDistanceFeet) }
        updateMonitoringService()
        requestVisibleAircraftIfNeeded(force = true)
    }

    private fun setAlertAltitudeFeet(next: Float) {
        alertAltitudeFeet = next.coerceIn(100f, 10000f)
        prefs.edit { putFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, alertAltitudeFeet) }
        updateMonitoringService()
        requestVisibleAircraftIfNeeded(force = true)
    }

    private fun setPriorityTrackingEnabled(enabled: Boolean) {
        priorityTrackingEnabled = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, priorityTrackingEnabled) }
        updateMonitoringService()
        requestVisibleAircraftIfNeeded(force = true)
    }

    private fun setPriorityRangeCircleVisible(visible: Boolean) {
        priorityRangeCircleVisible = visible
        prefs.edit { putBoolean(FlightAlertSettings.KEY_PRIORITY_RANGE_CIRCLE_VISIBLE, priorityRangeCircleVisible) }
    }

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
        prefs.edit { putFloat(FlightAlertSettings.KEY_ZOOM, zoom.toFloat()) }
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

    private inline fun <reified T : Enum<T>> readEnumSetting(key: String, default: T): T {
        val stored = prefs.getString(key, default.name) ?: default.name
        return enumValues<T>().firstOrNull { it.name == stored } ?: default
    }

    private fun readAircraftTypeFilter(): AircraftTypeFilter {
        return readEnumSetting(FlightAlertSettings.KEY_FILTER_AIRCRAFT_TYPE, AircraftTypeFilter.ALL)
    }

    private fun readAltitudeFilter(): AltitudeFilter {
        return readEnumSetting(FlightAlertSettings.KEY_FILTER_ALTITUDE, AltitudeFilter.ANY)
    }

    private fun readDistanceFilter(): DistanceFilter {
        return readEnumSetting(FlightAlertSettings.KEY_FILTER_DISTANCE, DistanceFilter.ANY)
    }

    private fun readFlightStatusFilter(): FlightStatusFilter {
        return readEnumSetting(FlightAlertSettings.KEY_FILTER_FLIGHT_STATUS, FlightStatusFilter.ANY)
    }

    private fun readReportAgeFilter(): ReportAgeFilter {
        return readEnumSetting(FlightAlertSettings.KEY_FILTER_REPORT_AGE, ReportAgeFilter.ANY)
    }

    private fun setFilterSearchQuery(value: String) {
        val sanitized = sanitizeFilterSearch(value)
        if (filterSearchQuery == sanitized) return
        filterSearchQuery = sanitized
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_SEARCH_QUERY, filterSearchQuery) }
        onFiltersChanged()
    }

    private fun setAircraftTypeFilter(next: AircraftTypeFilter) {
        aircraftTypeFilter = next
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_AIRCRAFT_TYPE, next.name) }
        onFiltersChanged()
    }

    private fun setAltitudeFilter(next: AltitudeFilter) {
        altitudeFilter = next
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_ALTITUDE, next.name) }
        onFiltersChanged()
    }

    private fun setDistanceFilter(next: DistanceFilter) {
        distanceFilter = next
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_DISTANCE, next.name) }
        onFiltersChanged()
    }

    private fun setFlightStatusFilter(next: FlightStatusFilter) {
        flightStatusFilter = next
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_FLIGHT_STATUS, next.name) }
        onFiltersChanged()
    }

    private fun setReportAgeFilter(next: ReportAgeFilter) {
        reportAgeFilter = next
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_REPORT_AGE, next.name) }
        onFiltersChanged()
    }

    private fun setAlertVolumeFilter(enabled: Boolean) {
        alertVolumeFilter = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_FILTER_ALERT_VOLUME, enabled) }
        onFiltersChanged()
    }

    private fun resetFilters() {
        filterSearchQuery = ""
        aircraftTypeFilter = AircraftTypeFilter.ALL
        altitudeFilter = AltitudeFilter.ANY
        distanceFilter = DistanceFilter.ANY
        flightStatusFilter = FlightStatusFilter.ANY
        reportAgeFilter = ReportAgeFilter.ANY
        alertVolumeFilter = false
        prefs.edit {
            putString(FlightAlertSettings.KEY_FILTER_SEARCH_QUERY, filterSearchQuery)
            putString(FlightAlertSettings.KEY_FILTER_AIRCRAFT_TYPE, aircraftTypeFilter.name)
            putString(FlightAlertSettings.KEY_FILTER_ALTITUDE, altitudeFilter.name)
            putString(FlightAlertSettings.KEY_FILTER_DISTANCE, distanceFilter.name)
            putString(FlightAlertSettings.KEY_FILTER_FLIGHT_STATUS, flightStatusFilter.name)
            putString(FlightAlertSettings.KEY_FILTER_REPORT_AGE, reportAgeFilter.name)
            putBoolean(FlightAlertSettings.KEY_FILTER_ALERT_VOLUME, alertVolumeFilter)
        }
        onFiltersChanged()
    }

    private fun onFiltersChanged() {
        pruneSelectionForFilters()
        invalidate()
    }

    private fun submitFilterSearch() {
        clearFilterSearchFocus()
        pruneSelectionForFilters()
        requestVisibleAircraftIfNeeded(force = true)
        invalidate()
    }

    private fun focusFilterSearch() {
        filterSearchFocused = true
        requestFocus()
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun clearFilterSearchFocus() {
        if (!filterSearchFocused) return
        filterSearchFocused = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun appendFilterSearchText(value: String) {
        if (value.isBlank()) return
        setFilterSearchQuery(filterSearchQuery + value)
    }

    private fun deleteFilterSearchCharacter() {
        if (filterSearchQuery.isEmpty()) return
        setFilterSearchQuery(filterSearchQuery.dropLast(1))
    }

    private fun handleFilterSearchKey(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                deleteFilterSearchCharacter()
                true
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                submitFilterSearch()
                true
            }
            else -> {
                val code = event.unicodeChar
                if (code > 0 && !event.isCtrlPressed && !event.isAltPressed) {
                    appendFilterSearchText(code.toChar().toString())
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun sanitizeFilterSearch(value: String): String {
        return value
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() || it == '-' }
            .take(MAX_FILTER_SEARCH_CHARS)
    }

    private fun filtersActive(): Boolean {
        return filterSearchQuery.isNotBlank() ||
            aircraftTypeFilter != AircraftTypeFilter.ALL ||
            altitudeFilter != AltitudeFilter.ANY ||
            distanceFilter != DistanceFilter.ANY ||
            flightStatusFilter != FlightStatusFilter.ANY ||
            reportAgeFilter != ReportAgeFilter.ANY ||
            alertVolumeFilter
    }

    private fun filterStats(): FilterStats {
        val all = allAircraftSnapshot()
        val matched = all.count { passesAircraftFilters(it) }
        val summary = when {
            !filtersActive() -> "${all.size} live aircraft in current feed"
            all.isEmpty() -> "No live aircraft in current feed"
            matched == 0 -> "0 of ${all.size} live aircraft match filters"
            else -> "$matched of ${all.size} live aircraft match filters"
        }
        return FilterStats(all.size, matched, summary)
    }

    private fun allAircraftSnapshot(): List<Aircraft> {
        return synchronized(aircraft) { aircraft.toList() }
    }

    private fun filteredAircraftSnapshot(): List<Aircraft> {
        return allAircraftSnapshot().filter { passesAircraftFilters(it) }
    }

    private fun pruneSelectionForFilters() {
        if (!filtersActive()) return
        val selectedId = selectedAircraftId?.lowercase(Locale.US) ?: return
        val selectedLive = allAircraftSnapshot().firstOrNull { it.icao24.lowercase(Locale.US) == selectedId }
        if (selectedLive != null && passesAircraftFilters(selectedLive)) return
        selectedAircraftId = null
        selectedAircraftSnapshot = null
        clearSelectedFlightPath()
        if (detailsOpen) {
            detailsOpen = false
            photoEvidenceOpen = false
            usageOpen = false
            environmentalImpactOpen = false
        }
    }

    private fun passesAircraftFilters(item: Aircraft): Boolean {
        if (!matchesSearchFilter(item)) return false
        if (!matchesTypeFilter(item)) return false
        if (!matchesAltitudeFilter(item)) return false
        if (!matchesDistanceFilter(item)) return false
        if (!matchesFlightStatusFilter(item)) return false
        if (!matchesReportAgeFilter(item)) return false
        if (alertVolumeFilter && !isHazardAircraft(item)) return false
        return true
    }

    private fun matchesSearchFilter(item: Aircraft): Boolean {
        val query = filterSearchQuery
        if (query.isBlank()) return true
        return listOf(
            item.callsign,
            item.registration.orEmpty(),
            item.icao24,
            item.typeCode.orEmpty()
        ).any { sanitizeFilterSearch(it).contains(query) }
    }

    private fun matchesTypeFilter(item: Aircraft): Boolean {
        val symbol = aircraftSymbol(item)
        return when (aircraftTypeFilter) {
            AircraftTypeFilter.ALL -> true
            AircraftTypeFilter.AIRPLANES -> symbol == AircraftSymbol.AIRLINER || symbol == AircraftSymbol.GENERAL_AVIATION
            AircraftTypeFilter.ROTORCRAFT -> symbol == AircraftSymbol.ROTORCRAFT
            AircraftTypeFilter.GLIDER -> symbol == AircraftSymbol.GLIDER
            AircraftTypeFilter.UAV -> symbol == AircraftSymbol.UAV
            AircraftTypeFilter.SURFACE -> symbol == AircraftSymbol.SURFACE
            AircraftTypeFilter.MILITARY -> item.isMilitary
        }
    }

    private fun matchesAltitudeFilter(item: Aircraft): Boolean {
        if (altitudeFilter == AltitudeFilter.ANY) return true
        val feet = item.altitudeM?.times(FEET_PER_METER)
        return when (altitudeFilter) {
            AltitudeFilter.ANY -> true
            AltitudeFilter.BELOW_1000 -> feet != null && feet < 1000.0
            AltitudeFilter.FROM_1000_TO_5000 -> feet != null && feet >= 1000.0 && feet < 5000.0
            AltitudeFilter.FROM_5000_TO_18000 -> feet != null && feet >= 5000.0 && feet < 18000.0
            AltitudeFilter.ABOVE_18000 -> feet != null && feet >= 18000.0
            AltitudeFilter.UNKNOWN -> feet == null
        }
    }

    private fun matchesDistanceFilter(item: Aircraft): Boolean {
        val meters = displayDistanceMeters(item)
        return when (distanceFilter) {
            DistanceFilter.ANY -> true
            DistanceFilter.WITHIN_5 -> meters <= 5.0 * METERS_PER_STATUTE_MILE
            DistanceFilter.WITHIN_10 -> meters <= 10.0 * METERS_PER_STATUTE_MILE
            DistanceFilter.WITHIN_25 -> meters <= 25.0 * METERS_PER_STATUTE_MILE
            DistanceFilter.BEYOND_25 -> meters > 25.0 * METERS_PER_STATUTE_MILE
        }
    }

    private fun matchesFlightStatusFilter(item: Aircraft): Boolean {
        return when (flightStatusFilter) {
            FlightStatusFilter.ANY -> true
            FlightStatusFilter.AIRBORNE -> item.onGround == false
            FlightStatusFilter.ON_GROUND -> item.onGround == true
            FlightStatusFilter.UNKNOWN -> item.onGround == null
        }
    }

    private fun matchesReportAgeFilter(item: Aircraft): Boolean {
        val age = contactAgeSeconds(item)
        return when (reportAgeFilter) {
            ReportAgeFilter.ANY -> true
            ReportAgeFilter.FRESH_30 -> age != null && age <= 30.0
            ReportAgeFilter.STALE_60 -> age != null && age >= 60.0
            ReportAgeFilter.UNKNOWN -> age == null
        }
    }

    private fun contactAgeSeconds(item: Aircraft): Double? {
        val contact = item.lastContactSec ?: item.positionTimeSec ?: return null
        return max(0.0, System.currentTimeMillis() / 1000.0 - contact)
    }

    private fun settingsPanelBounds(w: Float, h: Float): RectF {
        val compact = w > h && h < dp(500)
        val narrowPortrait = !compact && w < dp(430)
        val width = if (compact) min(w - dp(24), dp(860)) else min(w - dp(32), dp(430))
        val height = when {
            compact -> min(h - dp(16), dp(320))
            narrowPortrait -> h - dp(32)
            else -> min(h - dp(32), dp(660))
        }
        val top = max(if (compact) dp(8) else dp(16), (h - height) / 2f)
        return RectF((w - width) / 2f, top, (w + width) / 2f, top + height)
    }

    private fun imperialButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            return RectF(left.left, panel.top + dp(66), left.right, panel.top + dp(96))
        }
        return RectF(panel.left + dp(18), panel.top + dp(88), panel.right - dp(18), panel.top + dp(122))
    }

    private fun metricButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            return RectF(left.left, panel.top + dp(102), left.right, panel.top + dp(132))
        }
        return RectF(panel.left + dp(18), panel.top + dp(130), panel.right - dp(18), panel.top + dp(164))
    }

    private fun closeButtonBounds(panel: RectF) = RectF(panel.right - dp(112), panel.top + dp(14), panel.right - dp(18), panel.top + dp(48))

    private fun mapSourceButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            return RectF(left.left, panel.top + dp(162), left.right, panel.top + dp(192))
        }
        return RectF(panel.left + dp(18), panel.top + dp(178), panel.right - dp(18), panel.top + dp(210))
    }

    private fun mapLabelsButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            return RectF(left.left, panel.top + dp(198), left.right, panel.top + dp(228))
        }
        return RectF(panel.left + dp(18), panel.top + dp(218), panel.right - dp(18), panel.top + dp(250))
    }

    private fun themeButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            return RectF(left.left, panel.top + dp(246), left.right, panel.top + dp(276))
        }
        return RectF(panel.left + dp(18), panel.top + dp(288), panel.right - dp(18), panel.top + dp(320))
    }

    private fun alertsToggleBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val right = compactSettingsRightColumn(panel)
            return RectF(right.left, panel.top + dp(66), right.right, panel.top + dp(96))
        }
        return RectF(panel.left + dp(18), panel.top + dp(348), panel.right - dp(18), panel.top + dp(380))
    }

    private fun alertDistanceMinusBounds(panel: RectF): RectF {
        return if (isCompactSettingsPanel(panel)) {
            adjusterMinusBounds(priorityAlertControlArea(panel), panel.top + dp(132))
        } else {
            adjusterMinusBounds(panel, panel.top + dp(178))
        }
    }

    private fun alertDistancePlusBounds(panel: RectF): RectF {
        return if (isCompactSettingsPanel(panel)) {
            adjusterPlusBounds(priorityAlertControlArea(panel), panel.top + dp(132))
        } else {
            adjusterPlusBounds(panel, panel.top + dp(178))
        }
    }

    private fun alertAltitudeMinusBounds(panel: RectF): RectF {
        return if (isCompactSettingsPanel(panel)) {
            adjusterMinusBounds(priorityAlertControlArea(panel), panel.top + dp(200))
        } else {
            adjusterMinusBounds(panel, panel.top + dp(266))
        }
    }

    private fun alertAltitudePlusBounds(panel: RectF): RectF {
        return if (isCompactSettingsPanel(panel)) {
            adjusterPlusBounds(priorityAlertControlArea(panel), panel.top + dp(200))
        } else {
            adjusterPlusBounds(panel, panel.top + dp(266))
        }
    }

    private fun priorityTrackerButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val right = compactSettingsRightColumn(panel)
            return RectF(right.left, panel.top + dp(112), right.right, panel.top + dp(142))
        }
        return RectF(panel.left + dp(18), panel.top + dp(406), panel.right - dp(18), panel.top + dp(444))
    }

    private fun impactMethodologyButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val right = compactSettingsRightColumn(panel)
            return RectF(right.left, panel.top + dp(154), right.right, panel.top + dp(184))
        }
        return RectF(panel.left + dp(18), panel.top + dp(454), panel.right - dp(18), panel.top + dp(492))
    }

    private fun impactSourceButtonBounds(panel: RectF, index: Int): RectF {
        val gap = dp(8)
        val safeIndex = index.coerceIn(0, IMPACT_SOURCE_LABELS.lastIndex)
        return if (isCompactSettingsPanel(panel)) {
            val left = panel.left + dp(18)
            val buttonWidth = (panel.width() - dp(36) - gap * (IMPACT_SOURCE_LABELS.size - 1)) / IMPACT_SOURCE_LABELS.size
            val x = left + safeIndex * (buttonWidth + gap)
            RectF(x, panel.bottom - dp(46), x + buttonWidth, panel.bottom - dp(16))
        } else {
            val columns = 3
            val row = safeIndex / columns
            val column = safeIndex % columns
            val left = panel.left + dp(18)
            val buttonWidth = (panel.width() - dp(36) - gap * (columns - 1)) / columns
            val x = left + column * (buttonWidth + gap)
            val y = panel.bottom - dp(92) + row * (dp(34) + gap)
            RectF(x, y, x + buttonWidth, y + dp(34))
        }
    }

    private fun mapLabelsOnButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = panel.left + dp(18)
            val right = panel.centerX() - dp(5)
            return RectF(left, panel.top + dp(92), right, panel.top + dp(126))
        }
        return RectF(panel.left + dp(18), panel.top + dp(102), panel.right - dp(18), panel.top + dp(138))
    }

    private fun mapLabelsOffButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = panel.centerX() + dp(5)
            val right = panel.right - dp(18)
            return RectF(left, panel.top + dp(92), right, panel.top + dp(126))
        }
        return RectF(panel.left + dp(18), panel.top + dp(148), panel.right - dp(18), panel.top + dp(184))
    }

    private fun filterSearchBoxBounds(panel: RectF): RectF {
        return if (isCompactSettingsPanel(panel)) {
            RectF(panel.left + dp(18), panel.top + dp(62), panel.right - dp(210), panel.top + dp(96))
        } else {
            RectF(panel.left + dp(18), panel.top + dp(74), panel.right - dp(18), panel.top + dp(112))
        }
    }

    private fun filterSearchFindButtonBounds(panel: RectF): RectF {
        return if (isCompactSettingsPanel(panel)) {
            RectF(panel.right - dp(200), panel.top + dp(62), panel.right - dp(112), panel.top + dp(96))
        } else {
            RectF(panel.left + dp(18), panel.top + dp(122), panel.centerX() - dp(5), panel.top + dp(156))
        }
    }

    private fun filterSearchClearButtonBounds(panel: RectF): RectF {
        return if (isCompactSettingsPanel(panel)) {
            RectF(panel.right - dp(102), panel.top + dp(62), panel.right - dp(18), panel.top + dp(96))
        } else {
            RectF(panel.centerX() + dp(5), panel.top + dp(122), panel.right - dp(18), panel.top + dp(156))
        }
    }

    private fun filterAircraftTypeButtonBounds(panel: RectF): RectF {
        return filterButtonBounds(panel, row = 0, rightColumn = false)
    }

    private fun filterAltitudeButtonBounds(panel: RectF): RectF {
        return filterButtonBounds(panel, row = 1, rightColumn = false)
    }

    private fun filterDistanceButtonBounds(panel: RectF): RectF {
        return filterButtonBounds(panel, row = 2, rightColumn = false)
    }

    private fun filterStatusButtonBounds(panel: RectF): RectF {
        return filterButtonBounds(panel, row = if (isCompactSettingsPanel(panel)) 0 else 3, rightColumn = isCompactSettingsPanel(panel))
    }

    private fun filterAgeButtonBounds(panel: RectF): RectF {
        return filterButtonBounds(panel, row = if (isCompactSettingsPanel(panel)) 1 else 4, rightColumn = isCompactSettingsPanel(panel))
    }

    private fun filterAlertButtonBounds(panel: RectF): RectF {
        return filterButtonBounds(panel, row = if (isCompactSettingsPanel(panel)) 2 else 5, rightColumn = isCompactSettingsPanel(panel))
    }

    private fun filterResetButtonBounds(panel: RectF): RectF {
        return if (isCompactSettingsPanel(panel)) {
            RectF(panel.right - dp(126), panel.bottom - dp(52), panel.right - dp(18), panel.bottom - dp(22))
        } else {
            RectF(panel.left + dp(18), panel.bottom - dp(74), panel.right - dp(18), panel.bottom - dp(38))
        }
    }

    private fun filterButtonBounds(panel: RectF, row: Int, rightColumn: Boolean): RectF {
        return if (isCompactSettingsPanel(panel)) {
            val column = if (rightColumn) compactSettingsRightColumn(panel) else compactSettingsLeftColumn(panel)
            val top = panel.top + dp(120 + row * 46)
            RectF(column.left, top, column.right, top + dp(32))
        } else {
            val rowHeight = dp(36)
            val start = filterSearchClearButtonBounds(panel).bottom + dp(20)
            val resetTop = filterResetButtonBounds(panel).top
            val available = (resetTop - start - rowHeight * 6).coerceAtLeast(dp(30))
            val gap = (available / 5f).coerceIn(dp(6), dp(16))
            val top = start + row * (rowHeight + gap)
            RectF(panel.left + dp(18), top, panel.right - dp(18), top + rowHeight)
        }
    }

    private fun priorityTrackerPanelBounds(w: Float, h: Float): RectF {
        return settingsPanelBounds(w, h)
    }

    private fun priorityCloseButtonBounds(panel: RectF) = RectF(panel.right - dp(112), panel.top + dp(14), panel.right - dp(18), panel.top + dp(48))

    private fun priorityTrackingToggleBounds(panel: RectF): RectF {
        val gap = dp(10)
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            val right = left.left + (left.width() - gap) / 2f
            return RectF(left.left, panel.top + dp(66), right, panel.top + dp(96))
        }
        val left = panel.left + dp(18)
        val right = panel.centerX() - gap / 2f
        return RectF(left, panel.top + dp(72), right, panel.top + dp(110))
    }

    private fun priorityRingToggleBounds(panel: RectF): RectF {
        val gap = dp(10)
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            val buttonLeft = left.left + (left.width() + gap) / 2f
            return RectF(buttonLeft, panel.top + dp(66), left.right, panel.top + dp(96))
        }
        val buttonLeft = panel.centerX() + gap / 2f
        return RectF(buttonLeft, panel.top + dp(72), panel.right - dp(18), panel.top + dp(110))
    }

    private fun isCompactSettingsPanel(panel: RectF): Boolean {
        return panel.width() > dp(620) && panel.height() < dp(380)
    }

    private fun compactSettingsLeftColumn(panel: RectF): RectF {
        return RectF(panel.left + dp(18), panel.top, panel.left + panel.width() * 0.49f, panel.bottom)
    }

    private fun compactSettingsRightColumn(panel: RectF): RectF {
        return RectF(panel.left + panel.width() * 0.54f, panel.top, panel.right - dp(18), panel.bottom)
    }


    private fun priorityAlertControlArea(panel: RectF): RectF {
        return if (isCompactSettingsPanel(panel)) {
            RectF(panel.left, panel.top, panel.left + panel.width() * 0.49f, panel.bottom)
        } else {
            panel
        }
    }

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
            settingsButtonBounds(w, h),
            filtersButtonBounds(w, h)
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

    private fun drawFilterIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.2f)
        paint.color = color
        iconPath.reset()
        iconPath.moveTo(cx - dp(13), cy - dp(10))
        iconPath.lineTo(cx + dp(13), cy - dp(10))
        iconPath.lineTo(cx + dp(4), cy)
        iconPath.lineTo(cx + dp(4), cy + dp(10))
        iconPath.lineTo(cx - dp(4), cy + dp(14))
        iconPath.lineTo(cx - dp(4), cy)
        iconPath.close()
        canvas.drawPath(iconPath, paint)
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

    private fun drawHistoryIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.1f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        iconPath.reset()
        iconPath.moveTo(cx - dp(12), cy + dp(8))
        iconPath.cubicTo(cx - dp(5), cy - dp(11), cx + dp(6), cy + dp(12), cx + dp(12), cy - dp(8))
        canvas.drawPath(iconPath, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - dp(12), cy + dp(8), dp(3), paint)
        canvas.drawCircle(cx, cy, dp(2.6f), paint)
        canvas.drawCircle(cx + dp(12), cy - dp(8), dp(3), paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    private fun drawClearIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.4f)
        paint.color = color
        canvas.drawCircle(cx, cy, dp(12), paint)
        canvas.drawLine(cx - dp(6), cy - dp(6), cx + dp(6), cy + dp(6), paint)
        canvas.drawLine(cx + dp(6), cy - dp(6), cx - dp(6), cy + dp(6), paint)
    }

    private fun drawYouPill(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, fill: Int, text: Int) {
        val rect = RectF(x, y, x + width, y + height)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(218, Color.red(fill), Color.green(fill), Color.blue(fill))
        val radius = if (themeStyle.treatment == ThemeTreatment.PLAIN) height / 2f else controlRadius().coerceAtMost(height / 2f)
        canvas.drawRoundRect(rect, radius, radius, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(9)
        textPaint.color = text
        val metrics = textPaint.fontMetrics
        canvas.drawText("YOU", rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
        textPaint.isFakeBoldText = false
    }

    private fun nearestAircraft(): Aircraft? = filteredAircraftSnapshot().firstOrNull()

    private fun displayedTraffic(): TrafficDisplay {
        val snapshot = filteredAircraftSnapshot()
        val selected = selectedAircraftId?.let { id -> snapshot.firstOrNull { it.icao24 == id } }
            ?: selectedAircraftSnapshot?.takeIf { !filtersActive() && it.icao24 == selectedAircraftId }
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

        private fun selectedPathPoints(): List<GeoPoint>? {
        return selectedSegmentPoints(visibleOnly = false)?.map { GeoPoint(it.lat, it.lon) }
    }

    private fun selectedSegmentPoints(visibleOnly: Boolean): List<TrackPoint>? {
        return selectedPathSegments(visibleOnly)
            ?.flatMap { it.points }
            ?.takeIf { it.size >= 2 }
    }

    private fun selectedPathSegments(visibleOnly: Boolean): List<TraceSegment>? {
        if (visibleOnly && !selectedFlightPathVisible) return null
        if (!hasSelectedFlightPath()) return null
        return selectedFlightPath?.segments?.takeIf { it.isNotEmpty() }
    }

        private fun previousFlightPoints(): List<GeoPoint>? {
        return previousFlightSegments(visibleOnly = false)
            ?.flatMap { segment -> segment.points.map { GeoPoint(it.lat, it.lon) } }
            ?.takeIf { it.size >= 2 }
    }

    private fun previousFlightSegments(visibleOnly: Boolean): List<TraceSegment>? {
        if (visibleOnly && (!selectedFlightPathVisible || !selectedPreviousFlightsVisible)) return null
        if (!hasPreviousFlightSegments()) return null
        return selectedFlightPath?.previousSegments?.takeIf { it.isNotEmpty() }
    }

    private fun selectedPathBounds(): Bounds? {
        val points = selectedPathPoints()?.toMutableList() ?: return null
        if (selectedPreviousFlightsVisible) previousFlightPoints()?.let { points += it }
        selectedPathProjectedEndpoint()?.let { points += it }
        if (points.size < 2) return null
        return Bounds(
            minLat = points.minOf { it.lat },
            minLon = points.minOf { it.lon },
            maxLat = points.maxOf { it.lat },
            maxLon = points.maxOf { it.lon }
        )
    }

    private fun displayAircraftPosition(aircraft: Aircraft): GeoPoint {
        return selectedPathDisplayEndpoint(aircraft) ?: estimatedAircraftPosition(aircraft)
    }

    private fun selectedPathProjectedEndpoint(): GeoPoint? {
        val aircraft = selectedAircraftSnapshot?.takeIf { selectedAircraftId == it.icao24 } ?: return null
        if (!selectedFlightPathVisible || !hasSelectedFlightPath()) return null
        val reportSec = aircraft.positionTimeSec ?: aircraft.lastContactSec ?: return null
        val ageSeconds = System.currentTimeMillis() / 1000.0 - reportSec
        if (ageSeconds > MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS) return null
        return displayAircraftPosition(aircraft)
    }

    private fun selectedPathDisplayEndpoint(aircraft: Aircraft): GeoPoint? {
        if (!selectedFlightPathVisible || selectedAircraftId != aircraft.icao24 || !hasSelectedFlightPath()) return null
        val last = selectedSegmentPoints(visibleOnly = false)?.maxByOrNull { it.epochSec } ?: return null
        val reportSec = (aircraft.positionTimeSec ?: aircraft.lastContactSec)?.toLong()
        if (reportSec == null || last.epochSec > reportSec + PATH_TRACE_NEWER_THAN_FEED_SECONDS) {
            return GeoPoint(last.lat, last.lon)
        }
        return estimatedAircraftPosition(aircraft)
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

    private fun Aircraft.toTrackPoint(): TrackPoint? {
        val epochSec = (positionTimeSec ?: lastContactSec)?.toLong() ?: return null
        if (epochSec <= 0L) return null
        return TrackPoint(
            lat = lat,
            lon = lon,
            epochSec = epochSec,
            altitudeM = altitudeM,
            trackDeg = trackDeg,
            onGround = onGround
        )
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
        return priorityTrackingEnabled && alertsEnabled && isHazardAircraft(aircraft)
    }

    private fun aircraftSymbol(aircraft: Aircraft): AircraftSymbol {
        if (aircraft.onGround == true) return AircraftSymbol.SURFACE
        return when (aircraft.category) {
            4, 5, 6 -> AircraftSymbol.AIRLINER
            8 -> AircraftSymbol.ROTORCRAFT
            9, 10 -> AircraftSymbol.GLIDER
            14 -> AircraftSymbol.UAV
            16, 17, 18, 19, 20 -> AircraftSymbol.SURFACE
            else -> if (isAirlinerTypeCode(aircraft.typeCode)) AircraftSymbol.AIRLINER else AircraftSymbol.GENERAL_AVIATION
        }
    }

    private fun isAirlinerTypeCode(typeCode: String?): Boolean {
        val code = typeCode?.uppercase(Locale.US)?.trim() ?: return false
        return AIRLINER_TYPE_PREFIXES.any { code.startsWith(it) }
    }

    private fun aircraftSizeMultiplier(aircraft: Aircraft, symbol: AircraftSymbol): Float {
        val code = aircraft.typeCode?.uppercase(Locale.US)?.trim().orEmpty()
        return when (symbol) {
            AircraftSymbol.AIRLINER -> when {
                HEAVY_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 1.18f
                LARGE_AIRLINER_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 1.10f
                REGIONAL_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 0.98f
                else -> 1.05f
            }
            AircraftSymbol.GENERAL_AVIATION -> when {
                LIGHT_JET_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 1.04f
                SMALL_GENERAL_AVIATION_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 0.86f
                else -> 1.0f
            }
            AircraftSymbol.ROTORCRAFT -> 0.9f
            AircraftSymbol.GLIDER -> 0.82f
            AircraftSymbol.UAV -> 0.74f
            AircraftSymbol.SURFACE -> 0.86f
        }
    }

    private fun trafficDistanceColor(aircraft: Aircraft): Int {
        return if (isHazardAircraft(aircraft)) dangerColor else accentGreenColor
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
        val altitudeFeet = aircraft.altitudeM?.times(3.28084)
        if (aircraft.isMilitary) return militaryAltitudeColor(altitudeFeet)
        return altitudeColor(altitudeFeet)
    }

    private fun altitudeColor(altitudeFeet: Double?): Int {
        val palette = altitudeColorPalette()
        val altitude = altitudeFeet ?: return mixColor(palette.high, mutedColor, 0.48f)
        val progress = (altitude / ALTITUDE_COLOR_MAX_FEET).toFloat().coerceIn(0f, 1f)
        return when {
            progress < 0.5f -> mixColor(palette.low, palette.mid, smoothStep(0f, 0.5f, progress))
            else -> mixColor(palette.mid, palette.high, smoothStep(0.5f, 1f, progress))
        }
    }

    private fun militaryAltitudeColor(altitudeFeet: Double?): Int {
        val progress = altitudeFeet?.let { (it / ALTITUDE_COLOR_MAX_FEET).toFloat().coerceIn(0f, 1f) }
            ?: 0.55f
        val lowGray = mixColor(militaryGrayColor, Color.WHITE, 0.34f)
        val highGray = mixColor(militaryGrayColor, scrimColor, 0.22f)
        return mixColor(lowGray, highGray, progress)
    }

    private fun altitudeColorPalette(): AltitudeColorPalette {
        return when (themeStyle.treatment) {
            ThemeTreatment.RADAR_GRID -> AltitudeColorPalette(accentYellowColor, accentOrangeColor, accentBlueColor)
            ThemeTreatment.CRT_SCANLINE -> AltitudeColorPalette(accentYellowColor, accentGreenColor, mixColor(accentBlueColor, mutedColor, 0.25f))
            ThemeTreatment.DAYLIGHT_CARD -> AltitudeColorPalette(dangerColor, accentOrangeColor, accentBlueColor)
            ThemeTreatment.STORM_BAND -> AltitudeColorPalette(dangerColor, accentPinkColor, accentBlueColor)
            ThemeTreatment.GLASS -> AltitudeColorPalette(accentOrangeColor, accentGreenColor, accentBlueColor)
            ThemeTreatment.PLAIN -> AltitudeColorPalette(dangerColor, accentOrangeColor, accentBlueColor)
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
        return sp(value.toFloat())
    }

    private fun sp(value: Float): Float {
        val metrics = resources.displayMetrics
        return value * metrics.density * resources.configuration.fontScale
    }

    private fun panelRadius(): Float = dp(themeStyle.panelCornerDp)

    private fun controlRadius(): Float = dp(themeStyle.controlCornerDp)

    private fun applyThemeTypeface() {
        textPaint.typeface = Typeface.create(themeStyle.fontFamily, Typeface.NORMAL)
    }

    private fun drawContextControl(canvas: Canvas, rect: RectF, stroke: Int) {
        val alpha = if (themeStyle.treatment == ThemeTreatment.PLAIN) 235 else themeStyle.controlAlpha
        val strokeWidth = if (themeStyle.treatment == ThemeTreatment.PLAIN) 1.4f else themeStyle.controlStrokeDp
        drawControlSurface(canvas, rect, withAlpha(controlFillColor, alpha), stroke, strokeWidthDp = strokeWidth)
    }

    private fun drawControlSurface(
        canvas: Canvas,
        rect: RectF,
        fill: Int,
        stroke: Int,
        selected: Boolean = false,
        strokeWidthDp: Float = themeStyle.controlStrokeDp
    ) {
        val radius = controlRadius()
        if (themeStyle.treatment == ThemeTreatment.DAYLIGHT_CARD) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(scrimColor, 28)
            canvas.drawRoundRect(RectF(rect.left + dp(1), rect.top + dp(2), rect.right + dp(1), rect.bottom + dp(2)), radius, radius, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(rect, radius, radius, paint)
        drawControlTreatment(canvas, rect, radius, stroke, selected)

        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(strokeWidthDp)
        strokePaint.color = stroke
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    private fun drawControlTreatment(canvas: Canvas, rect: RectF, radius: Float, stroke: Int, selected: Boolean) {
        if (themeStyle.textureAlpha <= 0) return
        val alpha = if (selected) max(themeStyle.textureAlpha, 46) else themeStyle.textureAlpha
        when (themeStyle.treatment) {
            ThemeTreatment.PLAIN -> Unit
                        ThemeTreatment.GLASS -> {
                val inset = dp(1.2f)
                val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                val innerRadius = max(0f, radius - inset)
                val lineInset = max(dp(8), min(inner.width(), inner.height()) * 0.18f)
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(textColor, (alpha * 0.58f).roundToInt())
                canvas.drawRoundRect(RectF(inner.left, inner.top, inner.right, inner.centerY()), innerRadius, innerRadius, paint)
                paint.color = withAlpha(scrimColor, (alpha * 0.34f).roundToInt())
                canvas.drawRoundRect(RectF(inner.left, inner.centerY(), inner.right, inner.bottom), innerRadius, innerRadius, paint)
                strokePaint.strokeWidth = dp(0.7f)
                strokePaint.color = withAlpha(accentBlueColor, (alpha * 1.05f).roundToInt())
                canvas.drawLine(inner.left + lineInset, inner.top + dp(6), inner.right - lineInset, inner.top + dp(6), strokePaint)
                strokePaint.color = withAlpha(textColor, (alpha * 0.5f).roundToInt())
                canvas.drawLine(inner.left + lineInset, inner.bottom - dp(6), inner.right - lineInset, inner.bottom - dp(6), strokePaint)
            }
            ThemeTreatment.RADAR_GRID -> {
                strokePaint.strokeWidth = dp(1f)
                strokePaint.color = withAlpha(stroke, alpha)
                val inset = dp(5)
                val tick = min(rect.width(), rect.height()) * 0.22f
                canvas.drawLine(rect.left + inset, rect.top + inset, rect.left + inset + tick, rect.top + inset, strokePaint)
                canvas.drawLine(rect.left + inset, rect.top + inset, rect.left + inset, rect.top + inset + tick, strokePaint)
                canvas.drawLine(rect.right - inset - tick, rect.top + inset, rect.right - inset, rect.top + inset, strokePaint)
                canvas.drawLine(rect.right - inset, rect.top + inset, rect.right - inset, rect.top + inset + tick, strokePaint)
                canvas.drawLine(rect.left + inset, rect.bottom - inset, rect.left + inset + tick, rect.bottom - inset, strokePaint)
                canvas.drawLine(rect.left + inset, rect.bottom - inset - tick, rect.left + inset, rect.bottom - inset, strokePaint)
                canvas.drawLine(rect.right - inset - tick, rect.bottom - inset, rect.right - inset, rect.bottom - inset, strokePaint)
                canvas.drawLine(rect.right - inset, rect.bottom - inset - tick, rect.right - inset, rect.bottom - inset, strokePaint)
            }
            ThemeTreatment.DAYLIGHT_CARD -> {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(stroke, alpha)
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + dp(3), paint)
                paint.color = withAlpha(accentPinkColor, (alpha * 0.65f).roundToInt())
                canvas.drawRect(rect.left, rect.bottom - dp(2), rect.right, rect.bottom, paint)
            }
            ThemeTreatment.STORM_BAND -> {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(stroke, alpha)
                canvas.drawRect(rect.left, rect.top, rect.left + dp(4), rect.bottom, paint)
                strokePaint.strokeWidth = dp(1.2f)
                strokePaint.color = withAlpha(accentOrangeColor, (alpha * 0.85f).roundToInt())
                canvas.drawLine(rect.right - dp(15), rect.top + dp(7), rect.right - dp(7), rect.bottom - dp(7), strokePaint)
            }
            ThemeTreatment.CRT_SCANLINE -> {
                strokePaint.strokeWidth = dp(0.7f)
                strokePaint.color = withAlpha(accentGreenColor, (alpha * 0.9f).roundToInt())
                var y = rect.top + dp(5)
                while (y < rect.bottom - dp(2)) {
                    canvas.drawLine(rect.left + dp(3), y, rect.right - dp(3), y, strokePaint)
                    y += dp(10)
                }
                strokePaint.strokeWidth = dp(0.8f)
                strokePaint.color = withAlpha(textColor, (alpha * 0.55f).roundToInt())
                canvas.drawRect(rect.left + dp(3), rect.top + dp(3), rect.right - dp(3), rect.bottom - dp(3), strokePaint)
            }
        }
    }

    private fun drawPanelSurface(canvas: Canvas, rect: RectF, fill: Int = panelColor, alpha: Int = themeStyle.infoPanelAlpha) {
        val radius = panelRadius()
        if (themeStyle.treatment == ThemeTreatment.DAYLIGHT_CARD) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(scrimColor, 42)
            canvas.drawRoundRect(RectF(rect.left + dp(2), rect.top + dp(3), rect.right + dp(2), rect.bottom + dp(3)), radius, radius, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = withAlpha(fill, alpha)
        canvas.drawRoundRect(rect, radius, radius, paint)
        drawPanelTreatment(canvas, rect, radius)

        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(themeStyle.panelStrokeDp)
        strokePaint.color = panelStrokeColor
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    private fun drawEvenRadarGrid(canvas: Canvas, rect: RectF) {
        val inset = dp(2)
        val step = dp(RADAR_GRID_SPACING_DP)
        val left = rect.left + inset
        val top = rect.top + inset
        val right = rect.right - inset
        val bottom = rect.bottom - inset
        val width = right - left
        val height = bottom - top
        if (width < step || height < step) return

        val columns = max(1, floor(width / step).toInt())
        val rows = max(1, floor(height / step).toInt())
        val gridWidth = columns * step
        val gridHeight = rows * step
        val startX = left + (width - gridWidth) / 2f
        val startY = top + (height - gridHeight) / 2f

        for (index in 0..columns) {
            val x = startX + index * step
            if (x > left + 0.5f && x < right - 0.5f) {
                canvas.drawLine(x, top, x, bottom, strokePaint)
            }
        }
        for (index in 0..rows) {
            val y = startY + index * step
            if (y > top + 0.5f && y < bottom - 0.5f) {
                canvas.drawLine(left, y, right, y, strokePaint)
            }
        }
    }

    private fun drawPanelTreatment(canvas: Canvas, rect: RectF, radius: Float) {
        if (themeStyle.textureAlpha <= 0) return
        when (themeStyle.treatment) {
            ThemeTreatment.PLAIN -> Unit
                        ThemeTreatment.GLASS -> {
                val inset = dp(1.4f)
                val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                val innerRadius = max(0f, radius - inset)
                val lineInset = max(dp(14), min(inner.width(), inner.height()) * 0.1f)
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(textColor, themeStyle.textureAlpha)
                canvas.drawRoundRect(RectF(inner.left, inner.top, inner.right, min(inner.bottom, inner.top + dp(46))), innerRadius, innerRadius, paint)
                paint.color = withAlpha(scrimColor, (themeStyle.textureAlpha * 0.72f).roundToInt())
                val shadeHeight = min(dp(62), inner.height() * 0.35f)
                canvas.drawRoundRect(RectF(inner.left, inner.bottom - shadeHeight, inner.right, inner.bottom), innerRadius, innerRadius, paint)
                strokePaint.strokeWidth = dp(0.8f)
                strokePaint.color = withAlpha(accentBlueColor, (themeStyle.textureAlpha * 1.45f).roundToInt())
                canvas.drawLine(inner.left + lineInset, inner.top + dp(8), inner.right - lineInset, inner.top + dp(8), strokePaint)
                strokePaint.color = withAlpha(textColor, (themeStyle.textureAlpha * 0.65f).roundToInt())
                canvas.drawLine(inner.left + lineInset, inner.bottom - dp(9), inner.right - lineInset, inner.bottom - dp(9), strokePaint)
            }
            ThemeTreatment.RADAR_GRID -> {
                strokePaint.strokeWidth = dp(0.6f)
                strokePaint.color = withAlpha(accentYellowColor, themeStyle.textureAlpha)
                drawEvenRadarGrid(canvas, rect)
            }
            ThemeTreatment.DAYLIGHT_CARD -> {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(accentBlueColor, themeStyle.textureAlpha)
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + dp(3), paint)
            }
            ThemeTreatment.STORM_BAND -> {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(accentBlueColor, themeStyle.textureAlpha)
                canvas.drawRect(rect.left, rect.top, rect.left + dp(5), rect.bottom, paint)
                paint.color = withAlpha(textColor, (themeStyle.textureAlpha * 0.5f).toInt())
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + dp(2), paint)
            }
            ThemeTreatment.CRT_SCANLINE -> {
                strokePaint.strokeWidth = dp(0.7f)
                strokePaint.color = withAlpha(accentGreenColor, themeStyle.textureAlpha)
                var y = rect.top + dp(6)
                while (y < rect.bottom) {
                    canvas.drawLine(rect.left + dp(2), y, rect.right - dp(2), y, strokePaint)
                    y += dp(12)
                }
            }
        }
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value >= edge1) 1f else 0f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress.coerceIn(0f, 1f)
    }

    private fun mixColor(start: Int, end: Int, progress: Float): Int {
        val t = progress.coerceIn(0f, 1f)
        return Color.rgb(
            lerp(Color.red(start).toFloat(), Color.red(end).toFloat(), t).roundToInt().coerceIn(0, 255),
            lerp(Color.green(start).toFloat(), Color.green(end).toFloat(), t).roundToInt().coerceIn(0, 255),
            lerp(Color.blue(start).toFloat(), Color.blue(end).toFloat(), t).roundToInt().coerceIn(0, 255)
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
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

    private data class FilterStats(val total: Int, val matched: Int, val summary: String)

    private data class UsageFlight(val startEpochSec: Long, val endEpochSec: Long, val hours: Double)

    private data class UsageBucket(val label: String, val flights: Int, val hours: Double)

    private data class UsageStats(
        val buckets: List<UsageBucket>,
        val flightCount: Int,
        val totalHours: Double,
        val weekFlightCount: Int,
        val weekHours: Double,
        val windowLabel: String
    )

    private data class ImpactProfile(
        val label: String,
        val examples: String,
        val fuel: ImpactFuel,
        val lowGallonsPerHour: Double,
        val highGallonsPerHour: Double,
        val confidence: String
    ) {
        fun midpointGallonsPerHour(): Double = (lowGallonsPerHour + highGallonsPerHour) / 2.0

        fun lowCo2KgPerHour(): Double = lowGallonsPerHour * fuel.co2KgPerGallon

        fun highCo2KgPerHour(): Double = highGallonsPerHour * fuel.co2KgPerGallon

        fun midCo2KgPerHour(): Double = midpointGallonsPerHour() * fuel.co2KgPerGallon

        fun fuelLabel(): String = "${fuel.displayName}; exact fuel unavailable"

        fun fuelBurnLabel(): String = String.format(Locale.US, "~%.0f-%.0f gal/hr class benchmark", lowGallonsPerHour, highGallonsPerHour)

        fun co2FactorLabel(): String = String.format(Locale.US, "%.2f kg CO2/gal from EIA coefficient", fuel.co2KgPerGallon)
    }

    private enum class ImpactFuel(val displayName: String, val co2KgPerGallon: Double) {
        JET("Probable jet fuel", 9.75),
        AVGAS("Probable aviation gasoline", 8.31)
    }

    private data class AltitudeColorPalette(
        val low: Int,
        val mid: Int,
        val high: Int
    )

    private data class ScaleLabel(val pixels: Float, val label: String)

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
        data class Found(val bitmap: Bitmap, val note: String, val evidence: PhotoEvidence? = null) : AircraftPhotoResult()
        data class Unavailable(val reason: String) : AircraftPhotoResult()
    }

    private data class PhotoEvidence(
        val sourceName: String,
        val imageUrl: String,
        val pageUrl: String,
        val searchQuery: String,
        val quote: String,
        val matchedTerms: List<String>
    )

    private data class SearchImageCandidate(
        val imageUrl: String,
        val pageUrl: String,
        val sourceName: String,
        val title: String = "",
        val verificationText: String? = null
    )

    private data class VerificationQuote(
        val text: String,
        val matchedTerms: List<String>
    )

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

    private fun Aircraft.appearanceKey(): String {
        return icao24.ifBlank { "${"%.4f".format(Locale.US, lat)}:${"%.4f".format(Locale.US, lon)}:$callsign" }
    }

    private data class AircraftAppearance(val firstSeenMs: Long, val delayMs: Long)

    private enum class AircraftSymbol {
        GENERAL_AVIATION,
        AIRLINER,
        ROTORCRAFT,
        GLIDER,
        UAV,
        SURFACE
    }

    private enum class AircraftTypeFilter(val shortLabel: String) {
        ALL("All"),
        AIRPLANES("Airplanes"),
        ROTORCRAFT("Rotor"),
        GLIDER("Glider"),
        UAV("UAV"),
        SURFACE("Surface"),
        MILITARY("Military");

        fun next(): AircraftTypeFilter = entries[(ordinal + 1) % entries.size]
    }

    private enum class AltitudeFilter(val shortLabel: String) {
        ANY("Any"),
        BELOW_1000("<1k ft"),
        FROM_1000_TO_5000("1k-5k ft"),
        FROM_5000_TO_18000("5k-18k ft"),
        ABOVE_18000("18k+ ft"),
        UNKNOWN("Unknown");

        fun next(): AltitudeFilter = entries[(ordinal + 1) % entries.size]
    }

    private enum class DistanceFilter(val shortLabel: String) {
        ANY("Any"),
        WITHIN_5("<5 mi"),
        WITHIN_10("<10 mi"),
        WITHIN_25("<25 mi"),
        BEYOND_25(">25 mi");

        fun next(): DistanceFilter = entries[(ordinal + 1) % entries.size]
    }

    private enum class FlightStatusFilter(val shortLabel: String) {
        ANY("Any"),
        AIRBORNE("Airborne"),
        ON_GROUND("Ground"),
        UNKNOWN("Unknown");

        fun next(): FlightStatusFilter = entries[(ordinal + 1) % entries.size]
    }

    private enum class ReportAgeFilter(val shortLabel: String) {
        ANY("Any"),
        FRESH_30("Fresh <=30s"),
        STALE_60("Stale >=60s"),
        UNKNOWN("Unknown");

        fun next(): ReportAgeFilter = entries[(ordinal + 1) % entries.size]
    }


    private enum class TileSource(
        val baseCacheKey: String,
        val displayName: String,
        private val baseAttribution: String,
        val maxNativeZoom: Int
    ) {
        // Providers stay explicit so the map background remains auditable and never becomes fake imagery.
        STREET("osm", "Street map", "OpenStreetMap / CARTO tiles", 19),
        SATELLITE("esri_world_imagery", "Satellite", "Esri World Imagery", 19);

        fun cacheKey(labelsEnabled: Boolean): String {
            return when (this) {
                STREET -> if (labelsEnabled) baseCacheKey else "carto_voyager_nolabels"
                SATELLITE -> baseCacheKey
            }
        }

        fun attributionText(labelsEnabled: Boolean): String {
            return when (this) {
                STREET -> if (labelsEnabled) baseAttribution else "CARTO no-label tiles, OpenStreetMap data"
                SATELLITE -> baseAttribution
            }
        }

        fun tileUrl(z: Int, x: Int, y: Int, labelsEnabled: Boolean): String {
            return when (this) {
                STREET -> if (labelsEnabled) {
                    "https://tile.openstreetmap.org/$z/$x/$y.png"
                } else {
                    "https://basemaps.cartocdn.com/rastertiles/voyager_nolabels/$z/$x/$y.png"
                }
                SATELLITE -> "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            }
        }
    }

    private enum class UnitSystem(
        val distanceLabel: String,
        val altitudeLabel: String,
        val speedLabel: String
    ) {
        IMPERIAL("mi", "ft", "mph"),
        METRIC("km", "m", "km/h");

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
        const val MAX_ZOOM = 21
        const val MAX_MEMORY_TILES = 80
        const val LABEL_AIRCRAFT_COUNT = 4
        const val AIRCRAFT_REFRESH_MS = 15000L
        const val AIRCRAFT_FORCE_REFRESH_MS = 350L
        const val AIRCRAFT_IN_FLIGHT_RETRY_MS = 180L
        const val AIRCRAFT_TICKER_FETCH_MS = 1000L
        const val AIRCRAFT_BOUNDS_PADDING_PX = 96.0
        const val AIRCRAFT_APPEAR_DURATION_MS = 420L
        const val AIRCRAFT_SCALE_ZOOM_MIN = 6.4f
        const val AIRCRAFT_SCALE_ZOOM_MAX = 12.2f
        const val AIRCRAFT_SCALE_MIN = 0.38f
        const val AIRCRAFT_SCALE_MAX = 1.0f
        const val AIRCRAFT_DOT_SCALE_FLOOR = 0.7f
        const val AIRCRAFT_MORPH_SEED_RADIUS_DP = 4.6f
        const val AIRCRAFT_DOT_ZOOM_FULL = 6.0f
        const val AIRCRAFT_DOT_ZOOM_SYMBOL = 10.0f
        const val AIRCRAFT_DOT_DENSITY_START = 0.75f
        const val AIRCRAFT_DOT_DENSITY_FULL = 2.4f
        const val AIRCRAFT_MARKER_BLEND_UNITS_PER_SEC = 4.2f
        const val PATH_FIT_CONTEXT_MULTIPLIER = 1.5
        const val PRIORITY_PANEL_ROWS = 5
        const val MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY = 4
        const val MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY = 5
        const val PROOF_QUOTE_LINES = 3
        const val PHOTO_PLACEHOLDER_LINES = 4
        const val PHOTO_CAPTION_MAX_LINES = 7
        const val DETAILS_ROW_MAX_LINES = 12
        const val DETAILS_EVIDENCE_LINE_MAX_LINES = 4
        const val DETAILS_PROOF_QUOTE_LINES = 12
        const val PHOTO_TEXT_READ_TIMEOUT_MS = 9000
        const val AIRCRAFT_TAP_RADIUS_DP = 42
        const val HOLE_PUNCH_MAX_SIZE_DP = 72
        const val DB_FLAG_MILITARY = 1
        const val MAX_ESTIMATION_SECONDS = 75.0
        const val PATH_TRACE_NEWER_THAN_FEED_SECONDS = 45L
        const val MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS = 180.0
        const val ALTITUDE_COLOR_MAX_FEET = 45000.0
        const val FEET_PER_METER = 3.28084
        const val METERS_PER_STATUTE_MILE = 1609.344
        const val MAX_FILTER_SEARCH_CHARS = 18
        const val MIN_USAGE_SEGMENT_SECONDS = 180L
        const val MIN_USAGE_SEGMENT_DISTANCE_M = 5000.0
        const val MIN_PROJECTED_PATH_CONNECTOR_M = 60.0
        const val RADAR_GRID_SPACING_DP = 36f
        const val EARTH_RADIUS_M = 6371000.0
        const val EARTH_CIRCUMFERENCE_M = 40075016.686
        const val ORIGIN_AERODROME_RADIUS_M = 9000.0
        const val DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M = 30000.0
        const val INITIAL_RANGE_MULTIPLIER = 1.25
        const val TILE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        const val USER_AGENT = "FlightAlertPrototype/0.1"
        const val TAG = "FlightAlert"

        val IMAGE_URL_PATTERN = Regex("\\.(jpg|jpeg|png|webp)(\\?|$)", RegexOption.IGNORE_CASE)
        val USAGE_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.US)
        val USAGE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        const val IMPACT_SCORE_MIN_KG_CO2_PER_HOUR = 20.0
        const val IMPACT_SCORE_MAX_KG_CO2_PER_HOUR = 20000.0

        val IMPACT_PISTON_SINGLE = ImpactProfile(
            label = "Piston general aviation",
            examples = "C172/SR22/PA-28",
            fuel = ImpactFuel.AVGAS,
            lowGallonsPerHour = 8.0,
            highGallonsPerHour = 18.0,
            confidence = "Medium: type class is supported, exact engine/fuel flow is unavailable."
        )
        val IMPACT_PISTON_ROTOR = ImpactProfile(
            label = "Piston rotorcraft",
            examples = "R22/R44",
            fuel = ImpactFuel.AVGAS,
            lowGallonsPerHour = 10.0,
            highGallonsPerHour = 22.0,
            confidence = "Medium-low: rotorcraft fuel flow varies strongly by engine and mission."
        )
        val IMPACT_TURBINE_ROTOR = ImpactProfile(
            label = "Turbine rotorcraft",
            examples = "H60/AW139/S-76",
            fuel = ImpactFuel.JET,
            lowGallonsPerHour = 60.0,
            highGallonsPerHour = 220.0,
            confidence = "Medium-low: rotorcraft fuel flow varies strongly by engine and mission."
        )
        val IMPACT_TURBOPROP = ImpactProfile(
            label = "Turboprop or commuter aircraft",
            examples = "PC-12/C208/B350",
            fuel = ImpactFuel.JET,
            lowGallonsPerHour = 40.0,
            highGallonsPerHour = 160.0,
            confidence = "Medium: broad turbine-prop benchmark, not exact aircraft fuel flow."
        )
        val IMPACT_LIGHT_JET = ImpactProfile(
            label = "Business or light jet",
            examples = "Citation/Phenom/Learjet",
            fuel = ImpactFuel.JET,
            lowGallonsPerHour = 90.0,
            highGallonsPerHour = 350.0,
            confidence = "Medium: type class is supported, exact engine/fuel flow is unavailable."
        )
        val IMPACT_REGIONAL = ImpactProfile(
            label = "Regional airline aircraft",
            examples = "CRJ/E175/Dash 8",
            fuel = ImpactFuel.JET,
            lowGallonsPerHour = 220.0,
            highGallonsPerHour = 650.0,
            confidence = "Medium: class benchmark only; load factor and route phase are unavailable."
        )
        val IMPACT_NARROW_BODY = ImpactProfile(
            label = "Large narrow-body airliner",
            examples = "A320/B737/A321",
            fuel = ImpactFuel.JET,
            lowGallonsPerHour = 550.0,
            highGallonsPerHour = 950.0,
            confidence = "Medium: class benchmark only; load factor and route phase are unavailable."
        )
        val IMPACT_HEAVY = ImpactProfile(
            label = "Heavy transport or wide-body aircraft",
            examples = "B777/A350/C-17",
            fuel = ImpactFuel.JET,
            lowGallonsPerHour = 1200.0,
            highGallonsPerHour = 3200.0,
            confidence = "Medium-low: heavy-aircraft fuel flow depends heavily on model, weight, and mission."
        )
        val IMPACT_TACTICAL_JET = ImpactProfile(
            label = "High-performance tactical jet",
            examples = "F-16/F-18/F-35/T-38",
            fuel = ImpactFuel.JET,
            lowGallonsPerHour = 700.0,
            highGallonsPerHour = 2200.0,
            confidence = "Low: military mission profile and power setting are unavailable."
        )

        val IMPACT_SOURCE_LABELS = listOf("EIA CO2", "EPA Hub", "ICAO", "FAA AEDT", "EPA Lead")
        val IMPACT_SOURCE_URLS = listOf(
            "https://www.eia.gov/environment/emissions/co2_vol_mass.php",
            "https://www.epa.gov/climateleadership/ghg-emission-factors-hub",
            "https://www.icao.int/environmental-protection/environmental-tools/icec",
            "https://aedt.faa.gov/",
            "https://www.epa.gov/regulations-emissions-vehicles-and-engines/regulations-lead-emissions-aircraft"
        )

        val SMALL_PISTON_IMPACT_PREFIXES = listOf(
            "BE23", "BE33", "BE35", "BE36", "C150", "C152", "C162", "C170", "C172", "C175",
            "C177", "C180", "C182", "C185", "C206", "C207", "C210", "DA40", "DA42", "P28A",
            "PA28", "PA32", "PA34", "SR20", "SR22"
        )
        val SMALL_PISTON_IMPACT_TEXT = listOf(
            "CESSNA 172", "CESSNA 182", "CESSNA 206", "PIPER PA-28", "PIPER PA28",
            "PIPER CHEROKEE", "PIPER ARCHER", "CIRRUS SR20", "CIRRUS SR22", "DIAMOND DA40",
            "BEECH BONANZA"
        )
        val PISTON_ROTOR_IMPACT_PREFIXES = listOf("R22", "R44")
        val PISTON_ROTOR_IMPACT_TEXT = listOf("ROBINSON R22", "ROBINSON R44")
        val TURBINE_ROTOR_IMPACT_PREFIXES = listOf(
            "H60", "AS3", "AS5", "EC30", "EC35", "EC45", "B06", "B407", "B429", "S76", "S92", "A109", "A139", "AW13"
        )
        val TURBINE_ROTOR_IMPACT_TEXT = listOf(
            "BLACK HAWK", "SIKORSKY S-76", "SIKORSKY S-92", "AW139", "BELL 407", "BELL 429",
            "EUROCOPTER", "AIRBUS HELICOPTERS"
        )
        val TURBOPROP_IMPACT_PREFIXES = listOf("C208", "PC12", "TBM", "B350", "BE20", "BE30", "P46T", "DHC6")
        val TURBOPROP_IMPACT_TEXT = listOf("CARAVAN", "PC-12", "KING AIR", "TBM", "TWIN OTTER")
        val LIGHT_JET_IMPACT_PREFIXES = listOf("C25", "C510", "C52", "C55", "C56", "C68", "E50", "E55", "E545", "GLF", "H25", "LJ", "PRM", "SF50", "CL30")
        val LIGHT_JET_IMPACT_TEXT = listOf("CITATION", "PHENOM", "LEARJET", "GULFSTREAM", "PILATUS PC-24", "VISION JET", "CHALLENGER 300")
        val REGIONAL_IMPACT_PREFIXES = listOf("AT4", "AT7", "CRJ", "DH8", "E17", "E19", "E70", "E75", "F70", "F90", "SB20")
        val REGIONAL_IMPACT_TEXT = listOf("CRJ", "EMBRAER 170", "EMBRAER 175", "DASH 8", "ATR 42", "ATR 72", "SAAB 2000")
        val NARROW_BODY_IMPACT_PREFIXES = listOf("A19", "A20", "A21", "A30", "A31", "A32", "B37", "B38", "B39", "B70", "B71", "B72", "B73", "B75", "B76", "BCS", "MD8", "MD9")
        val NARROW_BODY_IMPACT_TEXT = listOf("AIRBUS A320", "AIRBUS A321", "BOEING 737", "BOEING 757", "BOEING 767", "A220", "737 MAX")
        val HEAVY_IMPACT_PREFIXES = listOf("A33", "A34", "A35", "A38", "B74", "B77", "B78", "DC10", "MD11", "A124", "IL76")
        val HEAVY_IMPACT_TEXT = listOf("A330", "A340", "A350", "A380", "747", "777", "787", "MD-11", "C-17", "C-5", "IL-76", "AN-124")
        val MILITARY_TRANSPORT_IMPACT_PREFIXES = listOf("C17", "C5", "C130", "A400", "K35", "KC10", "R135")
        val MILITARY_TRANSPORT_IMPACT_TEXT = listOf("GLOBEMASTER", "GALAXY", "HERCULES", "ATLAS", "STRATOTANKER", "EXTENDER")
        val TACTICAL_JET_IMPACT_PREFIXES = listOf("A10", "F15", "F16", "F18", "F22", "F35", "T38", "EUFI", "TOR")
        val TACTICAL_JET_IMPACT_TEXT = listOf("F-15", "F-16", "F-18", "F-22", "F-35", "T-38", "TYPHOON", "TORNADO", "WARTHOG")

        val HEAVY_SIZE_TYPE_PREFIXES = listOf(
            "A34",
            "A35",
            "A38",
            "B74",
            "B77",
            "B78",
            "C5",
            "C17",
            "DC10",
            "MD11"
        )
        val LARGE_AIRLINER_SIZE_TYPE_PREFIXES = listOf(
            "A30",
            "A31",
            "A32",
            "A33",
            "B70",
            "B71",
            "B72",
            "B73",
            "B75",
            "B76",
            "B79"
        )
        val REGIONAL_SIZE_TYPE_PREFIXES = listOf(
            "AT4",
            "AT7",
            "BCS",
            "CRJ",
            "DH8",
            "E17",
            "E19",
            "E70",
            "E75",
            "F70",
            "F90"
        )
        val LIGHT_JET_SIZE_TYPE_PREFIXES = listOf(
            "C25",
            "C55",
            "C56",
            "E50",
            "E55",
            "GLF",
            "LJ",
            "PRM",
            "SF50"
        )
        val SMALL_GENERAL_AVIATION_SIZE_TYPE_PREFIXES = listOf(
            "BE23",
            "BE35",
            "BE36",
            "C15",
            "C17",
            "C18",
            "C19",
            "C20",
            "C21",
            "DA40",
            "DA42",
            "P28",
            "PA2",
            "SR20",
            "SR22"
        )
        val AIRLINER_TYPE_PREFIXES = listOf(
            "A19",
            "A20",
            "A21",
            "A30",
            "A31",
            "A32",
            "A33",
            "A34",
            "A35",
            "A38",
            "AT4",
            "AT7",
            "B37",
            "B38",
            "B39",
            "B70",
            "B71",
            "B72",
            "B73",
            "B74",
            "B75",
            "B76",
            "B77",
            "B78",
            "BCS",
            "CRJ",
            "DH8",
            "E17",
            "E19",
            "E29",
            "E70",
            "E75",
            "F70",
            "F90",
            "MD8",
            "MD9"
        )
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
