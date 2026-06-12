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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
import com.flightalert.data.AviationAirspaceFeature
import com.flightalert.data.AviationAirportFeature
import com.flightalert.data.AviationGeoBounds
import com.flightalert.data.AviationLayerBounds
import com.flightalert.data.AviationLayerClient
import com.flightalert.data.AviationLayerKind
import com.flightalert.data.AviationLayerPoint
import com.flightalert.data.AviationLayerSnapshot
import com.flightalert.data.AviationLayerState
import com.flightalert.data.AviationLayerStatus
import com.flightalert.data.AviationOceanicTrack
import com.flightalert.data.FeedAircraft
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedResult
import com.flightalert.data.FeedSource
import com.flightalert.data.FeedStatus
import com.flightalert.data.FlightTrace
import com.flightalert.data.FlightTraceClient
import com.flightalert.data.GlobeWebAircraftSource
import com.flightalert.data.AircraftMetadataSeed
import com.flightalert.data.TraceSegment
import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.filters.AircraftFilterEngine
import com.flightalert.ui.map.filters.AircraftFilterState
import com.flightalert.ui.map.filters.AircraftTypeFilter
import com.flightalert.ui.map.filters.AltitudeFilter
import com.flightalert.ui.map.filters.DistanceFilter
import com.flightalert.ui.map.filters.FilterStats
import com.flightalert.ui.map.filters.FlightStatusFilter
import com.flightalert.ui.map.filters.ReportAgeFilter
import com.flightalert.ui.map.geo.MapProjection
import com.flightalert.ui.map.details.AircraftDetailCandidate
import com.flightalert.ui.map.details.WebDetailLookupContext
import com.flightalert.ui.map.impact.AircraftImpactEstimator
import com.flightalert.ui.map.impact.AircraftImpactPresenter
import com.flightalert.ui.map.impact.ImpactProfile
import com.flightalert.ui.map.impact.ImpactTrace
import com.flightalert.ui.map.model.Aircraft
import com.flightalert.ui.map.model.AircraftAppearance
import com.flightalert.ui.map.model.AircraftHit
import com.flightalert.ui.map.model.AircraftLabelStyle
import com.flightalert.ui.map.model.AltitudeColorPalette
import com.flightalert.ui.map.model.Bounds
import com.flightalert.ui.map.model.GeoPoint
import com.flightalert.ui.map.model.ScaleLabel
import com.flightalert.ui.map.model.ScreenPoint
import com.flightalert.ui.map.model.TrafficDisplay
import com.flightalert.ui.map.model.Viewport
import com.flightalert.ui.map.model.WorldPoint
import com.flightalert.ui.map.origin.OriginAerodrome
import com.flightalert.ui.map.photo.AircraftPhotoFetcher
import com.flightalert.ui.map.photo.AircraftPhotoResult
import com.flightalert.ui.map.photo.PhotoEvidence
import com.flightalert.ui.map.photo.PhotoQuality
import com.flightalert.ui.map.registry.AircraftRegistryResolver
import com.flightalert.ui.map.render.AircraftSymbolRenderer
import com.flightalert.ui.map.route.AircraftRoutePresenter
import com.flightalert.ui.map.route.AircraftRouteTraceContext
import com.flightalert.ui.map.route.CurrentRouteValidator
import com.flightalert.ui.map.settings.TileSource
import com.flightalert.ui.map.settings.UnitSystem
import com.flightalert.ui.map.symbols.AircraftSymbol
import com.flightalert.ui.map.symbols.AircraftSymbolClassifier
import com.flightalert.ui.map.usage.AircraftUsageAnalyzer
import com.flightalert.ui.map.usage.UsageStats
import com.flightalert.service.AircraftAlertService
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.settings.FlightAlertSettings.ThemeTreatment
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
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

// Custom map cockpit: real map tiles, real aircraft feeds, and canvas UI that adapts to device shape.
class FlightMapView(
    context: Context,
    private val globeWebAircraftSource: GlobeWebAircraftSource? = null
) : View(context), LocationListener {
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
    private val aircraftPhotoFetcher = AircraftPhotoFetcher(USER_AGENT)
    private val aviationLayerClient = AviationLayerClient(USER_AGENT)
    private val flightPathRequests = mutableSetOf<String>()

    private var locationPermissionGranted = false
    private var latestLocation: Location? = null
    private var zoom = readStoredZoom()
    private var units = UnitSystem.valueOf(prefs.getString(FlightAlertSettings.KEY_UNITS, UnitSystem.IMPERIAL.name) ?: UnitSystem.IMPERIAL.name)
    private var mapSource = readMapSource()
    private var mapLabelsEnabled = prefs.getBoolean(FlightAlertSettings.KEY_MAP_LABELS_ENABLED, FlightAlertSettings.DEFAULT_MAP_LABELS_ENABLED)
    private var aircraftFeedMode = FlightAlertSettings.readAircraftFeedMode(prefs)
    private var globeWebSourceEnabled = aircraftFeedMode.usesGlobe
    private var atcBoundariesLayerEnabled = prefs.getBoolean(FlightAlertSettings.KEY_LAYER_ATC_BOUNDARIES_ENABLED, FlightAlertSettings.DEFAULT_LAYER_ATC_BOUNDARIES_ENABLED)
    private var restrictedAirspacesLayerEnabled = prefs.getBoolean(FlightAlertSettings.KEY_LAYER_RESTRICTED_AIRSPACES_ENABLED, FlightAlertSettings.DEFAULT_LAYER_RESTRICTED_AIRSPACES_ENABLED)
    private var oceanicTracksLayerEnabled = prefs.getBoolean(FlightAlertSettings.KEY_LAYER_OCEANIC_TRACKS_ENABLED, FlightAlertSettings.DEFAULT_LAYER_OCEANIC_TRACKS_ENABLED)
    private var airportLabelsLayerEnabled = prefs.getBoolean(FlightAlertSettings.KEY_LAYER_AIRPORT_LABELS_ENABLED, FlightAlertSettings.DEFAULT_LAYER_AIRPORT_LABELS_ENABLED)
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
    private var aviationLayersOpen = false
    private var priorityTrackerOpen = false
    private var filtersOpen = false
    private var filterSearchFocused = false
    private var detailsOpen = false
    private var usageOpen = false
    private var environmentalImpactOpen = false
    private var impactMethodologyOpen = false
    private var aircraftDetails: AircraftDetails? = null
    private var aircraftDetailsStatus = "Select aircraft"
    private var aircraftDetailsLoading = false
    private var aircraftPhoto: Bitmap? = null
    private var aircraftPhotoStatus = "Photo unavailable"
    private var aircraftPhotoEvidence: PhotoEvidence? = null
    private var aircraftPhotoQuality: PhotoQuality? = null
    private var photoEvidenceOpen = false
    private var detailsRequestToken = 0L
    private var routeDiagnosticKey: String? = null
    private var aircraftFetchInFlight = false
    private var aircraftRefreshScheduled = false
    private var scheduledAircraftRefreshForce = false
    private var lastAircraftFetchMs = 0L
    private var aircraftFetchToken = 0L
    private var aviationLayerSnapshot: AviationLayerSnapshot? = null
    private var aviationLayerFetchInFlight = false
    private var lastAviationLayerFetchMs = 0L
    private var lastAviationLayerBounds: Bounds? = null
    private var lastAviationLayerSelectionKey = ""
    private var aviationLayerStatus = "Layers off"
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
        globeWebAircraftSource?.setEnabled(globeWebSourceEnabled)
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
        if (aviationLayersOpen) {
            aviationLayersOpen = false
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
            if (aviationLayersOpen) {
                aviationLayersOpen = false
                invalidate()
                return true
            }
            settingsOpen = false
            impactMethodologyOpen = false
            aviationLayersOpen = false
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
                requestAviationLayersIfNeeded(viewport)
                drawAviationLayers(this, viewport)
                drawPriorityRangeCircle(this, viewport, location)
                drawSelectedFlightPath(this, viewport)
                drawAircraft(this, viewport)
                drawOwnship(this, viewport, location)
            }

            drawTopStatus(this, w, h)
            drawRecenterButton(this, w, h)
            location?.let { drawFlightPathButtons(this, viewportFor(it, w, h), w, h) }
            drawSettingsButton(this, w, h)
            drawFiltersButton(this, w, h)
            drawTrafficPanel(this, w, h)

            if (detailsOpen || settingsOpen || priorityTrackerOpen || filtersOpen) {
                drawModalBackdrop(this, w, h)
            }
            if (detailsOpen) {
                drawAircraftDetailsPanel(this, w, h)
            }
            if (settingsOpen) {
                if (mapLabelsOpen) {
                    drawMapLabelsPanel(this, w, h)
                } else if (aviationLayersOpen) {
                    drawAviationLayersPanel(this, w, h)
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
                if (event.eventTime - event.downTime >= PHOTO_LONG_PRESS_MS && handleLongPress(x, y)) return true
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

    private fun viewportCenterLat(location: Location): Double {
        return if (followingLocation) location.latitude else manualCenterLat ?: location.latitude
    }

    private fun viewportCenterLon(location: Location): Double {
        return if (followingLocation) location.longitude else manualCenterLon ?: location.longitude
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

    private fun drawAviationLayers(canvas: Canvas, viewport: Viewport) {
        val snapshot = aviationLayerSnapshot ?: return
        val visibleBounds = boundsForViewport(viewport)?.toAviationLayerBounds()?.toGeoBounds() ?: return
        val layerLabelRects = mutableListOf<RectF>()
        if (restrictedAirspacesLayerEnabled) {
            drawAirspaceLayer(
                canvas = canvas,
                viewport = viewport,
                features = snapshot.restrictedAirspaces.filter { it.bounds.intersects(visibleBounds) },
                stroke = accentOrangeColor,
                fill = dangerColor,
                labelLimit = 3,
                labelRects = layerLabelRects
            )
        }
        if (atcBoundariesLayerEnabled) {
            drawAirspaceLayer(
                canvas = canvas,
                viewport = viewport,
                features = snapshot.atcBoundaries.filter { it.bounds.intersects(visibleBounds) },
                stroke = accentBlueColor,
                fill = accentBlueColor,
                labelLimit = 4,
                labelRects = layerLabelRects
            )
        }
        if (oceanicTracksLayerEnabled) {
            drawOceanicTracks(
                canvas = canvas,
                viewport = viewport,
                tracks = snapshot.oceanicTracks.filter { it.bounds.intersects(visibleBounds) },
                labelRects = layerLabelRects
            )
        }
        if (airportLabelsLayerEnabled) {
            drawAirportLabels(
                canvas = canvas,
                viewport = viewport,
                airports = snapshot.airports,
                labelRects = layerLabelRects
            )
        }
    }

    private fun drawAirspaceLayer(
        canvas: Canvas,
        viewport: Viewport,
        features: List<AviationAirspaceFeature>,
        stroke: Int,
        fill: Int,
        labelLimit: Int,
        labelRects: MutableList<RectF>
    ) {
        val visible = features
            .sortedBy { it.rings.sumOf { ring -> ring.size } }
            .take(MAX_DRAWN_AIRSPACE_FEATURES)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = dp(if (viewport.zoom >= 8.0) 1.6f else 1.1f)
        strokePaint.color = withAlpha(stroke, if (viewport.zoom >= 8.0) 205 else 150)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(fill, if (viewport.zoom >= 8.5) 18 else 9)

        var labelsDrawn = 0
        visible.forEach { feature ->
            feature.rings.take(MAX_DRAWN_RINGS_PER_FEATURE).forEach { ring ->
                val screenPoints = ringToScreenPoints(ring, viewport, maxPoints = MAX_DRAWN_AIRSPACE_POINTS_PER_RING)
                if (screenPoints.size < 3) return@forEach
                iconPath.reset()
                screenPoints.forEachIndexed { index, point ->
                    if (index == 0) iconPath.moveTo(point.x, point.y) else iconPath.lineTo(point.x, point.y)
                }
                iconPath.close()
                canvas.drawPath(iconPath, paint)
                canvas.drawPath(iconPath, strokePaint)
            }
            if (labelsDrawn < labelLimit && viewport.zoom >= AIRSPACE_LABEL_MIN_ZOOM) {
                if (drawAirspaceLabel(canvas, viewport, feature, labelRects)) {
                    labelsDrawn++
                }
            }
        }
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.strokeJoin = Paint.Join.MITER
    }

    private fun drawAirspaceLabel(
        canvas: Canvas,
        viewport: Viewport,
        feature: AviationAirspaceFeature,
        labelRects: MutableList<RectF>
    ): Boolean {
        val center = feature.bounds.centerPoint()
        val screen = aviationPointToScreen(center, viewport) ?: return false
        if (screen.x !in 0f..viewport.width || screen.y !in 0f..viewport.height) return false
        val label = listOfNotNull(feature.name, feature.type.takeIf { it != feature.name }).joinToString(" ")
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(10)
        textPaint.color = withAlpha(textColor, 205)
        val display = ellipsize(label, dp(124))
        val width = textPaint.measureText(display) + dp(12)
        val rect = RectF(screen.x - width / 2f, screen.y - dp(18), screen.x + width / 2f, screen.y + dp(2))
        if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return false
        val padded = rect.paddedCopy(dp(3))
        if (labelRects.any { RectF.intersects(padded, it) }) return false
        labelRects += padded
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(panelColor, 158)
        canvas.drawRoundRect(rect, dp(4), dp(4), paint)
        canvas.drawText(display, rect.centerX(), rect.bottom - dp(6), textPaint)
        textPaint.isFakeBoldText = false
        return true
    }

    private fun drawOceanicTracks(
        canvas: Canvas,
        viewport: Viewport,
        tracks: List<AviationOceanicTrack>,
        labelRects: MutableList<RectF>
    ) {
        if (viewport.zoom < OCEANIC_TRACK_MIN_ZOOM) return
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = dp(2.2f)
        strokePaint.color = withAlpha(accentPinkColor, 215)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(11)
        textPaint.color = accentPinkColor

        tracks.take(MAX_DRAWN_OCEANIC_TRACKS).forEach { track ->
            val points = ringToScreenPoints(track.points, viewport, maxPoints = MAX_DRAWN_OCEANIC_POINTS)
            if (points.size < 2) return@forEach
            iconPath.reset()
            points.forEachIndexed { index, point ->
                if (index == 0) iconPath.moveTo(point.x, point.y) else iconPath.lineTo(point.x, point.y)
            }
            canvas.drawPath(iconPath, strokePaint)
            val labelPoint = points.getOrNull(points.size / 2) ?: return@forEach
            canvas.drawCircle(labelPoint.x, labelPoint.y, dp(3), paint.apply {
                style = Paint.Style.FILL
                color = accentPinkColor
            })
            val labelWidth = textPaint.measureText(track.name)
            val labelRect = RectF(
                labelPoint.x - labelWidth / 2f,
                labelPoint.y - dp(24),
                labelPoint.x + labelWidth / 2f,
                labelPoint.y - dp(8)
            )
            val padded = labelRect.paddedCopy(dp(4))
            if (!labelRect.intersects(0f, 0f, viewport.width, viewport.height) ||
                labelRects.any { RectF.intersects(padded, it) }
            ) {
                return@forEach
            }
            labelRects += padded
            canvas.drawText(track.name, labelPoint.x, labelPoint.y - dp(8), textPaint)
        }
        textPaint.isFakeBoldText = false
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.strokeJoin = Paint.Join.MITER
    }

    private fun drawAirportLabels(
        canvas: Canvas,
        viewport: Viewport,
        airports: List<AviationAirportFeature>,
        labelRects: MutableList<RectF>
    ) {
        if (viewport.zoom < AIRPORT_LABEL_MIN_ZOOM) return
        val visible = airports
            .mapNotNull { airport -> aviationPointToScreen(AviationLayerPoint(airport.lat, airport.lon), viewport)?.let { airport to it } }
            .filter { (_, point) -> point.x in 0f..viewport.width && point.y in 0f..viewport.height }
            .sortedBy { (_, point) -> distanceFromScreenCenter(point, viewport) }
            .take(if (viewport.zoom >= 10.0) MAX_DRAWN_AIRPORT_LABELS else MAX_DRAWN_AIRPORT_LABELS_LOW_ZOOM)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(10)
        visible.forEach { (airport, point) ->
            val label = airport.ident.ifBlank { airport.name }
            val color = if (airport.military) militaryGrayColor else accentYellowColor
            val maxWidth = dp(if (viewport.zoom >= 10.0) 98 else 62)
            textPaint.color = color
            val display = ellipsize(label, maxWidth)
            val width = textPaint.measureText(display) + dp(10)
            val preferredLeft = if (point.x + dp(5) + width <= viewport.width - dp(2)) {
                point.x + dp(5)
            } else {
                point.x - dp(5) - width
            }
            val left = preferredLeft.coerceIn(dp(2), (viewport.width - width - dp(2)).coerceAtLeast(dp(2)))
            val top = (point.y - dp(16)).coerceIn(dp(2), viewport.height - dp(22))
            val rect = RectF(left, top, left + width, top + dp(20))
            if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return@forEach
            val padded = rect.paddedCopy(dp(3))
            if (labelRects.any { RectF.intersects(padded, it) }) return@forEach
            labelRects += padded
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(panelColor, 165)
            canvas.drawRoundRect(rect, dp(4), dp(4), paint)
            paint.color = color
            canvas.drawCircle(point.x, point.y, dp(3), paint)
            canvas.drawText(display, rect.left + dp(5), rect.bottom - dp(6), textPaint)
        }
        textPaint.isFakeBoldText = false
    }

    private fun ringToScreenPoints(points: List<AviationLayerPoint>, viewport: Viewport, maxPoints: Int): List<ScreenPoint> {
        if (points.isEmpty()) return emptyList()
        val step = max(1, points.size / maxPoints)
        val result = mutableListOf<ScreenPoint>()
        points.forEachIndexed { index, point ->
            if (index % step == 0 || index == points.lastIndex) {
                aviationPointToScreen(point, viewport)?.let { result += it }
            }
        }
        return result
    }

    private fun aviationPointToScreen(point: AviationLayerPoint, viewport: Viewport): ScreenPoint? {
        val world = latLonToWorld(point.lat, point.lon, viewport.zoom)
        var sx = (world.x - viewport.centerX + viewport.width / 2.0).toFloat()
        val worldSpan = (TILE_SIZE * 2.0.pow(viewport.zoom)).toFloat()
        while (sx < -worldSpan / 2f) sx += worldSpan
        while (sx > viewport.width + worldSpan / 2f) sx -= worldSpan
        val sy = (world.y - viewport.centerY + viewport.height / 2.0).toFloat()
        if (sx < -viewport.width || sx > viewport.width * 2f || sy < -viewport.height || sy > viewport.height * 2f) return null
        return ScreenPoint(sx, sy)
    }

    private fun distanceFromScreenCenter(point: ScreenPoint, viewport: Viewport): Float {
        val dx = point.x - viewport.width / 2f
        val dy = point.y - viewport.height / 2f
        return dx * dx + dy * dy
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

    private fun requestAviationLayersIfNeeded(viewport: Viewport, force: Boolean = false) {
        if (!hasAviationLayersEnabled()) {
            aviationLayerStatus = "Layers off"
            return
        }
        val queryBounds = aviationLayerBoundsForViewport(viewport) ?: return
        val visibleBounds = boundsForViewport(viewport) ?: queryBounds
        val selectionKey = aviationLayerSelectionKey()
        val now = SystemClock.elapsedRealtime()
        val existingBounds = lastAviationLayerBounds
        val needsFetch = force ||
            aviationLayerSnapshot == null ||
            selectionKey != lastAviationLayerSelectionKey ||
            existingBounds?.contains(visibleBounds) != true ||
            now - lastAviationLayerFetchMs >= AVIATION_LAYER_REFRESH_MS
        if (!needsFetch || aviationLayerFetchInFlight) return

        val includeAtc = atcBoundariesLayerEnabled
        val includeRestricted = restrictedAirspacesLayerEnabled
        val includeAirports = airportLabelsLayerEnabled
        val includeOceanic = oceanicTracksLayerEnabled
        val requestedKinds = activeAviationLayerKinds()
        aviationLayerFetchInFlight = true
        lastAviationLayerFetchMs = now
        aviationLayerStatus = "Loading aviation layers"
        executor.execute {
            val snapshot = try {
                aviationLayerClient.fetchLayers(
                    bounds = queryBounds.toAviationLayerBounds(),
                    includeAtcBoundaries = includeAtc,
                    includeRestrictedAirspaces = includeRestricted,
                    includeAirports = includeAirports,
                    includeOceanicTracks = includeOceanic
                )
            } catch (_: Exception) {
                null
            }
            post {
                aviationLayerFetchInFlight = false
                if (selectionKey != aviationLayerSelectionKey()) {
                    lastAviationLayerFetchMs = 0L
                    aviationLayerStatus = if (hasAviationLayersEnabled()) "Refreshing aviation layers" else "Layers off"
                    latestLocation?.let { requestAviationLayersIfNeeded(viewportFor(it, contentWidth(), contentHeight()), force = true) }
                    invalidate()
                    return@post
                }
                if (snapshot == null) {
                    aviationLayerStatus = if (aviationLayerSnapshot != null) {
                        "Aviation layers unavailable; keeping previous"
                    } else {
                        "Aviation layers unavailable"
                    }
                    invalidate()
                    return@post
                }
                val allUnavailable = requestedKinds.isNotEmpty() &&
                    requestedKinds.all { snapshot.statuses[it]?.state == AviationLayerState.UNAVAILABLE }
                val previous = aviationLayerSnapshot
                aviationLayerSnapshot = if (allUnavailable && previous != null) {
                    previous.copy(statuses = snapshot.statuses, fetchedAtMs = snapshot.fetchedAtMs)
                } else {
                    snapshot
                }
                lastAviationLayerBounds = queryBounds
                lastAviationLayerSelectionKey = selectionKey
                aviationLayerStatus = aviationLayerSummary(aviationLayerSnapshot, keptLastGood = allUnavailable && previous != null)
                invalidate()
            }
        }
    }

    private fun aviationLayerBoundsForViewport(viewport: Viewport): Bounds? {
        val padding = max(viewport.width, viewport.height) * AVIATION_LAYER_BOUNDS_PADDING_FRACTION
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

    private fun hasAviationLayersEnabled(): Boolean {
        return atcBoundariesLayerEnabled ||
            restrictedAirspacesLayerEnabled ||
            oceanicTracksLayerEnabled ||
            airportLabelsLayerEnabled
    }

    private fun activeAviationLayerKinds(): List<AviationLayerKind> {
        val kinds = mutableListOf<AviationLayerKind>()
        if (atcBoundariesLayerEnabled) kinds += AviationLayerKind.ATC_BOUNDARIES
        if (restrictedAirspacesLayerEnabled) kinds += AviationLayerKind.RESTRICTED_AIRSPACES
        if (oceanicTracksLayerEnabled) kinds += AviationLayerKind.OCEANIC_TRACKS
        if (airportLabelsLayerEnabled) kinds += AviationLayerKind.AIRPORTS
        return kinds
    }

    private fun aviationLayerSelectionKey(): String {
        return listOf(
            if (atcBoundariesLayerEnabled) "atc" else "",
            if (restrictedAirspacesLayerEnabled) "restricted" else "",
            if (oceanicTracksLayerEnabled) "oceanic" else "",
            if (airportLabelsLayerEnabled) "airports" else ""
        ).joinToString("|")
    }

    private fun aviationLayerSummary(snapshot: AviationLayerSnapshot?, keptLastGood: Boolean = false): String {
        if (!hasAviationLayersEnabled()) return "Layers off"
        snapshot ?: return "Waiting for aviation layers"
        val loaded = activeAviationLayerKinds().count { snapshot.statuses[it]?.state == AviationLayerState.LOADED }
        return when {
            keptLastGood -> "Network unavailable; showing last aviation layers"
            loaded > 0 -> "$loaded aviation layer${if (loaded == 1) "" else "s"} loaded"
            else -> "No aviation layer data in view"
        }
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
        val feedBounds = bounds.toFeedBounds()
        val feedMode = aircraftFeedMode
        val fetchToken = ++aircraftFetchToken
        val globeSource = globeWebAircraftSource?.takeIf { feedMode.usesGlobe }
        globeSource?.updateViewport(feedBounds, viewportCenterLat(location), viewportCenterLon(location), zoom)
        val exactSearch = filterSearchQuery.takeIf { it.isNotBlank() }
        executor.execute {
            try {
                when (feedMode) {
                    FlightAlertSettings.AircraftFeedMode.WEB -> {
                        val globeResult = globeSource?.latestSnapshot(feedBounds, location.latitude, location.longitude, exactSearch)
                        val result = globeResult ?: aircraftFeedClient.fetchAircraft(feedBounds, location.latitude, location.longitude, exactSearch)
                        applyAircraftFeedResult(result, fetchToken)
                    }
                    FlightAlertSettings.AircraftFeedMode.API -> {
                        applyAircraftFeedResult(
                            aircraftFeedClient.fetchAircraft(feedBounds, location.latitude, location.longitude, exactSearch),
                            fetchToken
                        )
                    }
                    FlightAlertSettings.AircraftFeedMode.HYBRID -> {
                        val apiResult = aircraftFeedClient.fetchAircraft(feedBounds, location.latitude, location.longitude, exactSearch)
                        if (apiResult.status == FeedStatus.OK) {
                            applyAircraftFeedResult(apiResult, fetchToken)
                        }
                        val globeResult = globeSource?.latestSnapshot(feedBounds, location.latitude, location.longitude, exactSearch)
                        when {
                            apiResult.status == FeedStatus.OK && globeResult?.status == FeedStatus.OK -> {
                                applyAircraftFeedResult(mergeHybridAircraftFeeds(apiResult, globeResult), fetchToken)
                            }
                            apiResult.status != FeedStatus.OK && globeResult?.status == FeedStatus.OK -> {
                                applyAircraftFeedResult(globeResult, fetchToken)
                            }
                            apiResult.status != FeedStatus.OK -> {
                                applyAircraftFeedResult(apiResult, fetchToken)
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (_: Exception) {
                if (aircraftFetchToken == fetchToken) {
                    aircraftStatus = "Aircraft feed unavailable"
                    Log.d(TAG, "Aircraft feed request failed")
                }
            } finally {
                if (aircraftFetchToken == fetchToken) {
                    aircraftFetchInFlight = false
                    postInvalidateOnAnimation()
                }
            }
        }
    }

    private fun applyAircraftFeedResult(result: FeedResult, fetchToken: Long): Boolean {
        if (aircraftFetchToken != fetchToken) return false
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
            postInvalidateOnAnimation()
            return true
        }

        aircraftStatus = when {
            result.httpCode != null -> "Aircraft feed unavailable: HTTP ${result.httpCode}"
            result.status == FeedStatus.RATE_LIMITED -> "Aircraft feed rate limited"
            else -> "Aircraft feed unavailable"
        }
        Log.d(TAG, "Aircraft feed ${result.source.displayName}: ${result.status} http=${result.httpCode ?: "none"}")
        postInvalidateOnAnimation()
        return false
    }

    private fun mergeHybridAircraftFeeds(apiResult: FeedResult, globeResult: FeedResult): FeedResult {
        val merged = linkedMapOf<String, FeedAircraft>()
        apiResult.aircraft.forEach { item ->
            merged[item.hybridFeedKey()] = item
        }
        globeResult.aircraft.forEach { webItem ->
            val key = webItem.hybridFeedKey()
            val apiItem = merged[key]
            merged[key] = if (apiItem == null) {
                webItem
            } else {
                apiItem.copy(
                    registration = apiItem.registration ?: webItem.registration,
                    typeCode = apiItem.typeCode ?: webItem.typeCode,
                    metadata = apiItem.metadata ?: webItem.metadata,
                    dbFlags = apiItem.dbFlags ?: webItem.dbFlags,
                    category = apiItem.category ?: webItem.category
                )
            }
        }
        return FeedResult(
            status = FeedStatus.OK,
            source = FeedSource.HYBRID,
            aircraft = merged.values.sortedBy { it.distanceM },
            epochSec = maxEpoch(apiResult.epochSec, globeResult.epochSec),
            queryCount = apiResult.queryCount + globeResult.queryCount,
            partialCoverage = apiResult.partialCoverage || globeResult.partialCoverage
        )
    }

    private fun FeedAircraft.hybridFeedKey(): String {
        val hex = icao24.trim().trimStart('~').lowercase(Locale.US)
        if (hex.isNotBlank()) return "hex:$hex"
        registration?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { return "reg:$it" }
        return "pos:${"%.4f".format(Locale.US, lat)}:${"%.4f".format(Locale.US, lon)}:${callsign.trim().uppercase(Locale.US)}"
    }

    private fun maxEpoch(first: Double?, second: Double?): Double? {
        return when {
            first == null -> second
            second == null -> first
            else -> max(first, second)
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
                        Log.d(TAG, "Flight trace icao=$key ${flightTraceDiagnostic(trace)}")
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
        val symbol = AircraftSymbolClassifier.symbolFor(aircraft)
        val typeScale = AircraftSymbolClassifier.sizeMultiplier(aircraft, symbol)
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
                AircraftSymbolRenderer.draw(this, symbol, shapeProgress, paint, strokePaint, ::dp)
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
        val callsign = aircraft.callsign.trim().ifBlank { aircraft.icao24.uppercase(Locale.US) }
        val detail = formatAircraftLabelDetail(aircraft)

        if (mapSource != TileSource.STREET) {
            drawSatelliteAircraftLabel(canvas, x, y, callsign, detail)
            return
        }

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(13)
        val maxTextWidth = min(dp(170), max(dp(86), contentWidth() - dp(48)))
        val displayCallsign = if (textPaint.measureText(callsign) <= maxTextWidth) callsign else ellipsize(callsign, maxTextWidth)
        val callsignWidth = textPaint.measureText(displayCallsign)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(11)
        val displayDetail = if (textPaint.measureText(detail) <= maxTextWidth) detail else ellipsize(detail, maxTextWidth)
        val detailWidth = textPaint.measureText(displayDetail)
        val textWidth = max(callsignWidth, detailWidth)
        val chipWidth = textWidth + dp(17)
        val chipHeight = dp(37)
        val minLeft = dp(4)
        val maxLeft = max(minLeft, contentWidth() - chipWidth - dp(4))
        val rightLeft = x + dp(20)
        val leftLeft = x - dp(20) - chipWidth
        val chipLeft = when {
            rightLeft <= maxLeft -> rightLeft
            leftLeft >= minLeft -> leftLeft
            else -> rightLeft
        }.coerceIn(minLeft, maxLeft)
        val chip = placedAircraftLabelRect(RectF(chipLeft, y - dp(25), chipLeft + chipWidth, y - dp(25) + chipHeight)) ?: return
        val style = streetAircraftLabelStyle(aircraft)
        val radius = dp(style.radiusDp)

        paint.style = Paint.Style.FILL
        paint.color = style.fill
        canvas.drawRoundRect(chip, radius, radius, paint)
        drawStreetAircraftLabelTreatment(canvas, chip, radius, style)

        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(style.strokeWidthDp)
        strokePaint.color = style.stroke
        canvas.drawRoundRect(chip, radius, radius, strokePaint)

        val textX = chip.left + dp(9)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(13)
        textPaint.color = style.title
        canvas.drawText(displayCallsign, textX, chip.top + dp(15), textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(11)
        textPaint.color = style.detail
        canvas.drawText(displayDetail, textX, chip.bottom - dp(7), textPaint)
    }

    private fun drawSatelliteAircraftLabel(canvas: Canvas, x: Float, y: Float, callsign: String, detail: String) {
        textPaint.textSize = sp(14)
        textPaint.isFakeBoldText = true
        val titleWidth = textPaint.measureText(callsign)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        val detailWidth = textPaint.measureText(detail)
        val labelWidth = max(titleWidth, detailWidth) + dp(4)
        val label = placedAircraftLabelRect(RectF(x + dp(20), y - dp(23), x + dp(20) + labelWidth, y + dp(18))) ?: return
        val labelX = label.left
        val titleY = label.top + dp(15)
        val detailY = label.top + dp(34)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(14)
        textPaint.color = withAlpha(scrimColor, 210)
        canvas.drawText(callsign, labelX + dp(2), titleY + dp(2), textPaint)
        textPaint.textSize = sp(12)
        canvas.drawText(detail, labelX + dp(2), detailY + dp(2), textPaint)

        textPaint.textSize = sp(14)
        textPaint.isFakeBoldText = true
        textPaint.color = textColor
        canvas.drawText(callsign, labelX, titleY, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = withAlpha(textColor, 230)
        canvas.drawText(detail, labelX, detailY, textPaint)
    }

    private fun placedAircraftLabelRect(preferred: RectF): RectF? {
        val margin = dp(4)
        val result = RectF(preferred)
        val avoidRects = aircraftLabelAvoidRects()
        clampAircraftLabelRect(result, margin)
        avoidRects.forEach { avoid ->
            if (RectF.intersects(result, avoid)) {
                val aboveTop = avoid.top - result.height() - dp(8)
                val belowTop = avoid.bottom + dp(8)
                val leftSide = avoid.left - result.width() - dp(8)
                val rightSide = avoid.right + dp(8)
                val verticalCandidates = if (result.centerY() < avoid.centerY()) {
                    listOf(aboveTop, belowTop)
                } else {
                    listOf(belowTop, aboveTop)
                }
                val horizontalCandidates = if (result.centerX() < avoid.centerX()) {
                    listOf(leftSide, rightSide)
                } else {
                    listOf(rightSide, leftSide)
                }
                val moved = verticalCandidates
                    .map { candidateTop -> RectF(result).apply { offsetTo(left, candidateTop) } }
                    .plus(horizontalCandidates.map { candidateLeft -> RectF(result).apply { offsetTo(candidateLeft, top) } })
                    .map { candidate -> candidate.apply { clampAircraftLabelRect(this, margin) } }
                    .firstOrNull { candidate -> avoidRects.none { other -> RectF.intersects(candidate, other) } }
                if (moved != null) {
                    result.set(moved)
                }
            }
        }
        return result.takeIf { label ->
            label.left >= margin &&
                label.top >= margin &&
                label.right <= contentWidth() - margin &&
                label.bottom <= contentHeight() - margin &&
                avoidRects.none { RectF.intersects(label, it) }
        }
    }

    private fun clampAircraftLabelRect(rect: RectF, margin: Float) {
        if (rect.right > contentWidth() - margin) {
            rect.offset(contentWidth() - margin - rect.right, 0f)
        }
        if (rect.left < margin) {
            rect.offset(margin - rect.left, 0f)
        }
        if (rect.bottom > contentHeight() - margin) {
            rect.offset(0f, contentHeight() - margin - rect.bottom)
        }
        if (rect.top < margin) {
            rect.offset(0f, margin - rect.top)
        }
    }

    private fun aircraftLabelAvoidRects(): List<RectF> {
        val w = contentWidth()
        val h = contentHeight()
        val padding = dp(8)
        return listOf(
            topStatusBounds(w, h).paddedCopy(padding),
            infoPanelBounds(w, h).paddedCopy(padding),
            settingsButtonBounds(w, h).paddedCopy(padding),
            filtersButtonBounds(w, h).paddedCopy(padding)
        )
    }

    private fun drawStreetAircraftLabelTreatment(canvas: Canvas, chip: RectF, radius: Float, style: AircraftLabelStyle) {
        paint.style = Paint.Style.FILL
        paint.color = style.accent
        canvas.drawRect(chip.left, chip.top + dp(4), chip.left + dp(3), chip.bottom - dp(4), paint)
        when (themeStyle.treatment) {
            ThemeTreatment.GLASS -> {
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = dp(0.65f)
                strokePaint.color = withAlpha(Color.WHITE, 90)
                canvas.drawLine(chip.left + dp(8), chip.top + dp(5), chip.right - dp(8), chip.top + dp(5), strokePaint)
            }
            ThemeTreatment.RADAR_GRID -> {
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = dp(0.7f)
                strokePaint.color = withAlpha(style.accent, 132)
                canvas.drawLine(chip.left + dp(8), chip.top + dp(5), chip.right - dp(8), chip.top + dp(5), strokePaint)
                canvas.drawLine(chip.left + dp(8), chip.bottom - dp(5), chip.right - dp(8), chip.bottom - dp(5), strokePaint)
            }
            ThemeTreatment.CRT_SCANLINE -> {
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = dp(0.55f)
                strokePaint.color = withAlpha(style.accent, 92)
                var lineY = chip.top + dp(8)
                while (lineY < chip.bottom - dp(4)) {
                    canvas.drawLine(chip.left + dp(6), lineY, chip.right - dp(6), lineY, strokePaint)
                    lineY += dp(9)
                }
            }
            ThemeTreatment.STORM_BAND -> {
                paint.color = withAlpha(style.accent, 52)
                canvas.drawRect(chip.left + dp(3), chip.top, chip.left + dp(6), chip.bottom, paint)
            }
            ThemeTreatment.DAYLIGHT_CARD,
            ThemeTreatment.PLAIN -> Unit
        }
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
        if (filtersRestrictAircraft()) return this
        val selected = selectedAircraftSnapshot ?: return this
        if (selectedAircraftId == null) return this
        if (any { it.icao24 == selected.icao24 }) return this
        return listOf(selected) + this
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

        val rightStatusLeft = rect.right - dp(132)
        val subtitleLeft = rect.left + dp(16)
        drawFittedLeftText(
            canvas = canvas,
            value = topSubtitle(),
            left = subtitleLeft,
            y = rect.top + dp(49),
            maxWidth = (rightStatusLeft - dp(12) - subtitleLeft).coerceAtLeast(dp(90)),
            startSize = sp(12),
            minSize = sp(9)
        )
        val status = topTrafficStatus()
        drawStatusLabel(canvas, rightStatusLeft, rect.top + dp(14), dp(116), dp(26), status.first, status.second)
        drawScaleLabel(canvas, rightStatusLeft, rect.top + dp(45), dp(116), dp(17))
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
        canvas.drawText(label, rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
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
            val rows = mutableListOf(
                "Altitude" to formatAltitudeValue(target.altitudeM),
                "Speed" to formatSpeedValue(target.velocityMs),
                "Track" to formatTrack(target.trackDeg),
                "Vertical rate" to formatVerticalRate(target.verticalRateMs),
                "Last contact" to formatAge(target),
                "Registration" to (target.registration ?: "Unavailable"),
                "Registry country" to registryCountryLabel(target),
                "Type" to (target.typeCode ?: "Unavailable")
            )
            if (target.isMilitary) {
                rows += "Military" to "Tagged military"
                val currentRouteDetails = currentFlightRouteDetails(
                    aircraftDetails.takeIf { selectedAircraftId == target.icao24 },
                    target
                )
                rows += "Origin status" to formatOriginStatus(target, currentRouteDetails)
            }
            rows += "ICAO" to target.icao24.uppercase(Locale.US)
            rows += "Position" to formatPosition(target)
            val rowHeight = trafficPanelRowHeight(rect, y, rows.size)
            rows.forEach { (label, value) ->
                if (y + rowHeight <= rect.bottom - dp(8)) {
                    y = drawTrafficDetailRow(canvas, rect, y, label, value, rowHeight)
                }
            }
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

    private fun trafficPanelRowHeight(rect: RectF, startY: Float, rowCount: Int): Float {
        if (rowCount <= 0) return dp(28)
        val available = (rect.bottom - dp(14) - startY).coerceAtLeast(dp(21))
        return (available / rowCount).coerceIn(dp(21), dp(28))
    }

    private fun drawTrafficDetailRow(canvas: Canvas, rect: RectF, y: Float, label: String, value: String, rowHeight: Float = dp(28)): Float {
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
        val dividerY = y + min(dp(10), rowHeight - dp(7))
        canvas.drawLine(rect.left + dp(16), dividerY, rect.right - dp(16), dividerY, strokePaint)
        textPaint.isFakeBoldText = false
        return y + rowHeight
    }

    private fun drawFittedRightText(canvas: Canvas, value: String, right: Float, y: Float, maxWidth: Float, startSize: Float, minSize: Float) {
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = startSize
        while (textPaint.textSize > minSize && textPaint.measureText(value) > maxWidth) {
            textPaint.textSize -= dp(0.5f)
        }
        val display = if (textPaint.measureText(value) <= maxWidth) value else ellipsize(value, maxWidth)
        canvas.drawText(display, right, y, textPaint)
    }

    private fun drawFittedLeftText(canvas: Canvas, value: String, left: Float, y: Float, maxWidth: Float, startSize: Float, minSize: Float) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = startSize
        while (textPaint.textSize > minSize && textPaint.measureText(value) > maxWidth) {
            textPaint.textSize -= dp(0.5f)
        }
        val display = if (textPaint.measureText(value) <= maxWidth) value else ellipsize(value, maxWidth)
        canvas.drawText(display, left, y, textPaint)
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
        val titleLeft = rect.left + dp(18)
        val titleRight = if (aircraft != null) {
            detailsUsageButtonBounds(rect).left - dp(10)
        } else {
            detailsCloseButtonBounds(rect).left - dp(10)
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(22)
        textPaint.color = textColor
        drawFittedLeftText(
            canvas = canvas,
            value = aircraft?.callsign ?: "Aircraft details",
            left = titleLeft,
            y = rect.top + dp(38),
            maxWidth = (titleRight - titleLeft).coerceAtLeast(dp(36)),
            startSize = sp(22),
            minSize = sp(12)
        )

        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText(ellipsize(aircraftDetailsStatus, rect.width() - dp(36)), rect.left + dp(18), rect.top + dp(60), textPaint)

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
                val routeDetails = currentFlightRouteDetails(details, aircraft)
                val routeContext = routeTraceContext(aircraft)
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "ICAO", aircraft.icao24.uppercase(Locale.US))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Registration", details?.registration ?: aircraft.registration ?: loadingOrUnavailable(detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Registry country", registryCountryLabel(aircraft, details, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Owner/Operator", AircraftRoutePresenter.detailsValue(details?.owner, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Aircraft", AircraftRoutePresenter.aircraftType(details, aircraft, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "MFR year", AircraftRoutePresenter.detailsValue(details?.manufacturedYear, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Registry source", AircraftRoutePresenter.detailsValue(details?.registrySource, detailsLoading))
                if (aircraft.isMilitary) {
                    y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Military", "Tagged military")
                    y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Origin status", formatOriginStatus(aircraft, routeDetails))
                }
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Route", AircraftRoutePresenter.value(routeDetails?.route, currentFlightRouteLoading(aircraft, detailsLoading)))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Origin", AircraftRoutePresenter.airport(routeDetails?.originAirport, currentFlightRouteLoading(aircraft, detailsLoading)))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Destination", AircraftRoutePresenter.airport(routeDetails?.destinationAirport, currentFlightRouteLoading(aircraft, detailsLoading)))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Path source", AircraftRoutePresenter.traceSource(routeContext))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Flight time", AircraftRoutePresenter.observedFlightTime(routeContext))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Route complete", AircraftRoutePresenter.routeCompletion(routeDetails, aircraft, routeContext, detailsLoading))
                y = drawAdaptiveDetailRow(canvas, detailsRowBounds(rect), y, "Observed path span", AircraftRoutePresenter.observedPathSpan(routeContext))
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
        val routeDetails = currentFlightRouteDetails(details, aircraft)
        val routeContext = routeTraceContext(aircraft)
        rows += "ICAO" to aircraft.icao24.uppercase(Locale.US)
        rows += "Registration" to (details?.registration ?: aircraft.registration ?: loadingOrUnavailable(detailsLoading))
        rows += "Registry country" to registryCountryLabel(aircraft, details, detailsLoading)
        rows += "Owner/Operator" to AircraftRoutePresenter.detailsValue(details?.owner, detailsLoading)
        rows += "Aircraft" to AircraftRoutePresenter.aircraftType(details, aircraft, detailsLoading)
        rows += "MFR year" to AircraftRoutePresenter.detailsValue(details?.manufacturedYear, detailsLoading)
        rows += "Registry source" to AircraftRoutePresenter.detailsValue(details?.registrySource, detailsLoading)
        if (aircraft.isMilitary) {
            rows += "Military" to "Tagged military"
            rows += "Origin status" to formatOriginStatus(aircraft, routeDetails)
        }
        rows += "Route" to AircraftRoutePresenter.value(routeDetails?.route, currentFlightRouteLoading(aircraft, detailsLoading))
        rows += "Origin" to AircraftRoutePresenter.airport(routeDetails?.originAirport, currentFlightRouteLoading(aircraft, detailsLoading))
        rows += "Destination" to AircraftRoutePresenter.airport(routeDetails?.destinationAirport, currentFlightRouteLoading(aircraft, detailsLoading))
        rows += "Path source" to AircraftRoutePresenter.traceSource(routeContext)
        rows += "Flight time" to AircraftRoutePresenter.observedFlightTime(routeContext)
        rows += "Route complete" to AircraftRoutePresenter.routeCompletion(routeDetails, aircraft, routeContext, detailsLoading)
        rows += "Observed path span" to AircraftRoutePresenter.observedPathSpan(routeContext)

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
        val profile = AircraftImpactPresenter.profileFor(aircraft, details)
        val trace = currentImpactTraceFor(aircraft)
        val traceLoading = isFlightPathLoading(aircraft)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12)
        textPaint.color = mutedColor
        canvas.drawText(ellipsize(AircraftImpactPresenter.status(profile, trace, detailsLoading, traceLoading), rect.width() - dp(36)), rect.left + dp(18), rect.top + dp(62), textPaint)

        val clip = detailsContentBounds(rect)
        val checkpoint = canvas.save()
        canvas.clipRect(clip)
        detailsRowsVisibleTop = clip.top + detailsScrollY
        detailsRowsVisibleBottom = clip.bottom + detailsScrollY - dp(2)
        canvas.translate(0f, -detailsScrollY)

        var y = rect.top + dp(98)
        y = drawImpactScoreSummary(canvas, rect, y, profile, trace, detailsLoading, traceLoading)
        y += dp(10)
        AircraftImpactPresenter.rows(aircraft, details, profile, trace, usageTraceFor(aircraft), detailsLoading, traceLoading, units).forEach { (label, value) ->
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
        trace: ImpactTrace?,
        detailsLoading: Boolean,
        traceLoading: Boolean
    ): Float {
        val left = rect.left + dp(18)
        val right = rect.right - dp(18)
        val loading = detailsLoading || traceLoading
        val scoreText = profile?.let { "${AircraftImpactEstimator.score(it)} / 100" } ?: loadingOrUnavailable(detailsLoading)
        val co2Text = when {
            profile != null && trace != null -> AircraftImpactPresenter.carbonRange(profile.carbonForHours(trace.hours))
            traceLoading -> "Loading"
            profile != null -> AircraftImpactPresenter.kgRange(profile.lowCo2KgPerHour(), profile.highCo2KgPerHour())
            else -> loadingOrUnavailable(loading)
        }

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(10)
        textPaint.color = mutedColor
        canvas.drawText(if (trace != null) "TRACE CO2 SO FAR" else "CLASS CO2 RATE", left, y, textPaint)
        canvas.drawText("CLASS INTENSITY SCORE", rect.centerX() + dp(8), y, textPaint)

        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(19)
        textPaint.color = textColor
        drawFittedLeftText(canvas, co2Text, left, y + dp(32), rect.width() * 0.56f, sp(19), sp(10))

        textPaint.textSize = sp(24)
        textPaint.color = if (profile == null) mutedColor else impactScoreColor(profile)
        drawFittedRightText(canvas, scoreText, right, y + dp(32), rect.width() * 0.34f, sp(24), sp(10))

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

        val stats = AircraftUsageAnalyzer.statsFor(trace)
        if (stats.flightCount == 0) {
            drawUsageCenteredMessage(canvas, "Unavailable: trace data does not contain usable completed or current flight segments.", RectF(rect.left + dp(18), rect.top + dp(96), rect.right - dp(18), rect.bottom - dp(24)))
            return
        }

        var y = rect.top + dp(102)
        y = drawUsageStatLine(canvas, rect, y, "Current week", "${stats.weekFlightCount} flights  ${AircraftUsageAnalyzer.formatHours(stats.weekHours)}")
        y = drawUsageStatLine(canvas, rect, y, "Trace window total", "${stats.flightCount} flights  ${AircraftUsageAnalyzer.formatHours(stats.totalHours)}")
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

    private fun RectF.paddedCopy(padding: Float): RectF {
        return RectF(left - padding, top - padding, right + padding, bottom + padding)
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
        val width = if (isWideLayout(w, h)) min(dp(800), w - margin * 2f) else w - margin * 2f
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

    private fun detailsImpactHitBounds(panel: RectF): RectF {
        return RectF(panel.left + dp(12), panel.bottom - dp(112), panel.right - dp(12), panel.bottom - dp(8))
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
            aircraftDetailsLoading
    }

    private fun isFlightPathLoading(aircraft: Aircraft): Boolean {
        val id = aircraft.icao24.lowercase(Locale.US)
        return synchronized(flightPathRequests) { id in flightPathRequests }
    }

    private fun currentFlightRouteDetails(details: AircraftDetails?, aircraft: Aircraft): AircraftDetails? {
        val routeDetails = details ?: return null
        if (!CurrentRouteValidator.hasRouteMetadata(routeDetails)) return null
        val validation = CurrentRouteValidator.evaluate(
            details = routeDetails,
            aircraftIcao24 = aircraft.icao24,
            aircraftCallsign = aircraft.callsign,
            selectedTraceAircraftId = selectedFlightPathAircraftId,
            traceSegments = selectedPathSegments(visibleOnly = false)
        )
        logRouteDiagnostic(validation.diagnostic)
        return routeDetails.takeIf { validation.accepted }
    }

    private fun logRouteDiagnostic(diagnostic: String) {
        if (routeDiagnosticKey == diagnostic) return
        routeDiagnosticKey = diagnostic
        Log.d(TAG, diagnostic)
    }

    private fun flightTraceDiagnostic(trace: FlightTrace?): String {
        if (trace == null) return "source=none points=0"
        val points = trace.allPoints.sortedBy { it.epochSec }
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        return "source=${trace.source.ifBlank { "unknown" }} points=${trace.pointCount} previous=${trace.previousPointCount} " +
            "first=${first?.latLonLabel() ?: "none"} last=${last?.latLonLabel() ?: "none"}"
    }

    private fun TrackPoint.latLonLabel(): String {
        return String.format(Locale.US, "%.4f,%.4f", lat, lon)
    }

    private fun currentFlightRouteLoading(aircraft: Aircraft, detailsLoading: Boolean): Boolean {
        return detailsLoading || isFlightPathLoading(aircraft)
    }

    private fun routeTraceContext(aircraft: Aircraft): AircraftRouteTraceContext {
        val id = aircraft.icao24.lowercase(Locale.US)
        return AircraftRouteTraceContext(
            aircraftId = id,
            selectedTraceAircraftId = selectedFlightPathAircraftId,
            trace = selectedFlightPath,
            segments = selectedPathSegments(visibleOnly = false),
            loading = isFlightPathLoading(aircraft)
        )
    }

    private fun currentImpactTraceFor(aircraft: Aircraft): ImpactTrace? {
        val segments = currentTraceSegmentsForImpact(aircraft) ?: return null
        val points = segments.flatMap { it.points }.takeIf { it.size >= 2 } ?: return null
        val start = points.minOf { it.epochSec }
        val end = points.maxOf { it.epochSec }
        val seconds = (end - start).coerceAtLeast(0L)
        val distance = AircraftRoutePresenter.traceDistanceMeters(segments)
        if (seconds <= 0L || distance <= 0.0) return null
        return ImpactTrace(
            distanceM = distance,
            hours = seconds / 3600.0,
            averageSpeedMs = distance / seconds,
            pointCount = points.size,
            source = selectedFlightPath?.source ?: "trace source"
        )
    }

    private fun currentTraceSegmentsForImpact(aircraft: Aircraft): List<TraceSegment>? {
        val id = aircraft.icao24.lowercase(Locale.US)
        if (selectedFlightPathAircraftId != id) return null
        return selectedPathSegments(visibleOnly = false)?.takeIf { segments ->
            segments.sumOf { it.points.size } >= 2
        }
    }

    private fun impactScoreColor(profile: ImpactProfile): Int {
        val score = AircraftImpactEstimator.score(profile)
        return when {
            score >= 76 -> dangerColor
            score >= 55 -> accentOrangeColor
            score >= 34 -> accentYellowColor
            else -> accentGreenColor
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

    private fun formatOriginStatus(aircraft: Aircraft, details: AircraftDetails?): String {
        if (!aircraft.isMilitary) return "Unavailable"
        val selectedStatus = militaryOriginStatus.takeIf { militaryOriginAircraftId == aircraft.icao24 && it != "Unavailable" }
        if (selectedStatus != null) return selectedStatus
        val origin = details?.originAirport ?: return "Unavailable"
        val label = AircraftRoutePresenter.airport(origin)
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

        drawSettingsSectionLabel(canvas, rect.left + dp(18), rect.top + dp(230), "Map")
        drawChoiceButton(canvas, mapSourceButtonBounds(rect), if (mapSource == TileSource.SATELLITE) "Satellite map" else "Street map", mapSource == TileSource.SATELLITE)
        drawChoiceButton(canvas, mapLabelsButtonBounds(rect), if (mapLabelsEnabled) "Street labels on" else "Street labels off", mapLabelsEnabled)
        drawChoiceButton(canvas, globeWebSourceButtonBounds(rect), aircraftFeedMode.displayName, true)
        drawChoiceButton(canvas, aviationLayersButtonBounds(rect), "Aviation layers", hasAviationLayersEnabled())

        drawSettingsSectionLabel(canvas, rect.left + dp(18), rect.top + dp(438), "Safety")
        drawChoiceButton(canvas, alertsToggleBounds(rect), if (alertsEnabled) "Hazard alerts on" else "Hazard alerts off", alertsEnabled)
        drawChoiceButton(canvas, priorityTrackerButtonBounds(rect), "Alert range and tracker", priorityTrackingEnabled)

        drawSettingsSectionLabel(canvas, rect.left + dp(18), rect.top + dp(566), "Reference")
        drawChoiceButton(canvas, impactMethodologyButtonBounds(rect), "Impact methodology", false)

        val footerTop = rect.bottom - dp(38)
        if (impactMethodologyButtonBounds(rect).bottom + dp(24) <= footerTop) {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = false
            textPaint.textSize = sp(11)
            textPaint.color = mutedColor
            canvas.drawText("Map: ${mapSource.attributionText(mapLabelsEnabled)}", rect.left + dp(18), rect.bottom - dp(38), textPaint)
            val sourceLabel = "Aircraft: ${aircraftSourcePreferenceLabel()}; paths: live trace sources"
            canvas.drawText(ellipsize(sourceLabel, rect.width() - dp(36)), rect.left + dp(18), rect.bottom - dp(18), textPaint)
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

        drawSettingsSectionLabel(canvas, left.left, rect.top + dp(174), "Map")
        drawChoiceButton(canvas, mapSourceButtonBounds(rect), if (mapSource == TileSource.SATELLITE) "Satellite" else "Street", mapSource == TileSource.SATELLITE)
        drawChoiceButton(canvas, mapLabelsButtonBounds(rect), if (mapLabelsEnabled) "Labels on" else "Labels off", mapLabelsEnabled)
        drawChoiceButton(canvas, globeWebSourceButtonBounds(rect), aircraftFeedMode.compactName, true)
        drawChoiceButton(canvas, aviationLayersButtonBounds(rect), "Layers", hasAviationLayersEnabled())

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

    private fun drawAviationLayersPanel(canvas: Canvas, w: Float, h: Float) {
        val rect = settingsPanelBounds(w, h)
        val compact = isCompactSettingsPanel(rect)
        drawPanelSurface(canvas, rect, panelAltColor, themeStyle.modalPanelAlpha)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(20)
        textPaint.color = textColor
        canvas.drawText("Layers", rect.left + dp(18), rect.top + dp(34), textPaint)
        drawChoiceButton(canvas, closeButtonBounds(rect), "Back", false)

        val statusRect = aviationLayerStatusBounds(rect)
        textPaint.isFakeBoldText = false
        textPaint.textSize = if (compact) sp(10) else sp(11)
        textPaint.color = mutedColor
        drawWrappedText(canvas, aviationLayerStatus, statusRect.left, statusRect.top, statusRect.width(), maxLines = if (compact) 1 else 2)

        drawLayerToggleButton(canvas, layerAtcButtonBounds(rect), "ATC boundaries", atcBoundariesLayerEnabled, AviationLayerKind.ATC_BOUNDARIES)
        drawLayerToggleButton(canvas, layerRestrictedButtonBounds(rect), "Restricted airspace", restrictedAirspacesLayerEnabled, AviationLayerKind.RESTRICTED_AIRSPACES)
        drawLayerToggleButton(canvas, layerOceanicButtonBounds(rect), "Oceanic tracks", oceanicTracksLayerEnabled, AviationLayerKind.OCEANIC_TRACKS)
        drawLayerToggleButton(canvas, layerAirportLabelsButtonBounds(rect), "Airport labels", airportLabelsLayerEnabled, AviationLayerKind.AIRPORTS)
    }

    private fun drawLayerToggleButton(canvas: Canvas, rect: RectF, label: String, enabled: Boolean, kind: AviationLayerKind) {
        val status = aviationLayerSnapshot?.statuses?.get(kind)
        val suffix = when {
            !enabled -> "off"
            status?.state == AviationLayerState.LOADED -> "on"
            status?.state == AviationLayerState.EMPTY -> "empty"
            status?.state == AviationLayerState.UNAVAILABLE -> "retry"
            aviationLayerFetchInFlight -> "loading"
            else -> "on"
        }
        drawChoiceButton(canvas, rect, "$label $suffix", enabled)
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

        AircraftImpactEstimator.sourceLabels.forEachIndexed { index, label ->
            drawChoiceButton(canvas, impactSourceButtonBounds(rect, index), label, false)
        }
    }

    private fun impactMethodologyItems(): List<Pair<String, String>> {
        return listOf(
            "Trace first" to "When a real trace is present, current-flight CO2 uses trace elapsed time and distance. App-session dots are never stored as a path.",
            "Carbon math" to "CO2 range = benchmark gal/hr range x trace hours x EIA kg CO2/gal. Jet fuel uses 9.75; avgas uses 8.31.",
            "Class score" to "0-100 is only class CO2/hr intensity on a log scale from 20 to 20,000 kg/hr. It is not a measured flight total.",
            "Aircraft class" to "Live web/feed type is used first, then registry/API metadata, then named public-page fallback only for missing model text.",
            "Context" to "The page also shows observed trace kg per mile/km and class cruise kg per nautical mile so the rate has scale.",
            "Not claimed" to "No exact engine fuel flow, payload, phase power, SAF blend, contrails, NOx, noise, or passenger allocation is inferred."
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
        drawFilterCycleRow(canvas, filterStatusButtonBounds(rect), "Status: ${flightStatusFilter.shortLabel}", flightStatusFilter != FlightStatusFilter.AIRBORNE)
        drawFilterCycleRow(canvas, filterAgeButtonBounds(rect), "Age: ${reportAgeFilter.shortLabel}", reportAgeFilter != ReportAgeFilter.ANY)
        drawFilterCycleRow(canvas, filterAlertButtonBounds(rect), if (alertVolumeFilter) "Alert volume only" else "Alert volume: off", alertVolumeFilter)
        drawChoiceButton(canvas, filterResetButtonBounds(rect), "Reset filters", filtersActive())
    }

    private fun drawCompactFiltersPanelContents(canvas: Canvas, rect: RectF) {
        drawFilterCycleRow(canvas, filterAircraftTypeButtonBounds(rect), "Type: ${aircraftTypeFilter.shortLabel}", aircraftTypeFilter != AircraftTypeFilter.ALL)
        drawFilterCycleRow(canvas, filterAltitudeButtonBounds(rect), "Alt: ${altitudeFilter.shortLabel}", altitudeFilter != AltitudeFilter.ANY)
        drawFilterCycleRow(canvas, filterDistanceButtonBounds(rect), "Range: ${distanceFilter.shortLabel}", distanceFilter != DistanceFilter.ANY)
        drawFilterCycleRow(canvas, filterStatusButtonBounds(rect), "Status: ${flightStatusFilter.shortLabel}", flightStatusFilter != FlightStatusFilter.AIRBORNE)
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
        val availableWidth = (rect.width() - dp(12)).coerceAtLeast(dp(4))
        val availableHeight = (rect.height() - dp(8)).coerceAtLeast(dp(4))
        while (textPaint.textSize > sp(8) &&
            (textPaint.measureText(label) > availableWidth || textPaint.fontMetrics.let { it.descent - it.ascent } > availableHeight)
        ) {
            textPaint.textSize -= dp(0.5f)
        }
        val display = if (textPaint.measureText(label) <= availableWidth) label else ellipsize(label, availableWidth)
        textPaint.color = if (selected) accentGreenColor else textColor
        val metrics = textPaint.fontMetrics
        canvas.drawText(display, rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textAlign = previousAlign
    }

    private fun handleLongPress(x: Float, y: Float): Boolean {
        if (!detailsOpen || usageOpen || environmentalImpactOpen || photoEvidenceOpen) return false
        val aircraft = displayedTraffic().aircraft ?: return false
        val details = aircraftDetails ?: return false
        val panel = detailsPanelBounds(contentWidth(), contentHeight())
        if (!currentDetailsPhotoBounds(panel, contentWidth(), contentHeight()).contains(x, y)) return false
        requestPhotoImprovement(aircraft, details)
        return true
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
                    aircraftDetailsLoading = false
                    photoEvidenceOpen = false
                    usageOpen = false
                    environmentalImpactOpen = false
                }
                detailsUsageButtonBounds(panel).contains(x, y) -> displayedTraffic().aircraft?.let { openAircraftUsage(it) }
                detailsImpactHitBounds(panel).contains(x, y) -> displayedTraffic().aircraft?.let { openAircraftImpact(it) }
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
                    else -> AircraftImpactEstimator.sourceUrls.forEachIndexed { index, url ->
                        if (impactSourceButtonBounds(panel, index).contains(x, y)) {
                            openUrl(url)
                        }
                    }
                }
                invalidate()
                return
            }
            if (aviationLayersOpen) {
                when {
                    closeButtonBounds(panel).contains(x, y) -> aviationLayersOpen = false
                    layerAtcButtonBounds(panel).contains(x, y) -> setAtcBoundariesLayerEnabled(!atcBoundariesLayerEnabled)
                    layerRestrictedButtonBounds(panel).contains(x, y) -> setRestrictedAirspacesLayerEnabled(!restrictedAirspacesLayerEnabled)
                    layerOceanicButtonBounds(panel).contains(x, y) -> setOceanicTracksLayerEnabled(!oceanicTracksLayerEnabled)
                    layerAirportLabelsButtonBounds(panel).contains(x, y) -> setAirportLabelsLayerEnabled(!airportLabelsLayerEnabled)
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
                    aviationLayersOpen = false
                }
                imperialButtonBounds(panel).contains(x, y) -> setUnits(UnitSystem.IMPERIAL)
                metricButtonBounds(panel).contains(x, y) -> setUnits(UnitSystem.METRIC)
                mapSourceButtonBounds(panel).contains(x, y) -> toggleMapSource()
                mapLabelsButtonBounds(panel).contains(x, y) -> mapLabelsOpen = true
                globeWebSourceButtonBounds(panel).contains(x, y) -> setAircraftFeedMode(aircraftFeedMode.next())
                aviationLayersButtonBounds(panel).contains(x, y) -> aviationLayersOpen = true
                themeButtonBounds(panel).contains(x, y) -> setVisualTheme(nextVisualTheme())
                alertsToggleBounds(panel).contains(x, y) -> setAlertsEnabled(!alertsEnabled)
                impactMethodologyButtonBounds(panel).contains(x, y) -> impactMethodologyOpen = true
                priorityTrackerButtonBounds(panel).contains(x, y) -> {
                    settingsOpen = false
                    impactMethodologyOpen = false
                    aviationLayersOpen = false
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
            aviationLayersOpen = false
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
        aircraftDetailsLoading = true
        aircraftPhoto = null
        aircraftPhotoEvidence = null
        aircraftPhotoQuality = null
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
        routeDiagnosticKey = null
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

    private fun requestPhotoImprovement(aircraft: Aircraft, details: AircraftDetails) {
        val requestedId = aircraft.icao24
        val requestToken = detailsRequestToken
        val previousStatus = aircraftPhotoStatus
        val previousQuality = aircraftPhotoQuality
        aircraftPhotoStatus = "Checking for improved photo"
        invalidate()
        executor.execute {
            val result = aircraftPhotoFetcher.fetch(aircraft, details)
            post {
                if (!isCurrentDetailsRequest(requestedId, requestToken)) return@post
                when (result) {
                    is AircraftPhotoResult.Found -> {
                        if (previousQuality == null || result.quality.rank > previousQuality.rank) {
                            aircraftPhoto = result.bitmap
                            aircraftPhotoStatus = result.note
                            aircraftPhotoEvidence = result.evidence
                            aircraftPhotoQuality = result.quality
                        } else {
                            aircraftPhotoStatus = previousStatus
                        }
                    }
                    is AircraftPhotoResult.Unavailable -> {
                        aircraftPhotoStatus = previousStatus
                    }
                }
                invalidate()
            }
        }
    }

    private fun requestAircraftDetails(aircraft: Aircraft) {
        val requestedId = aircraft.icao24
        val requestToken = ++detailsRequestToken
        val detailMode = aircraftFeedMode
        val lookup = webDetailLookupContext()
        val candidates = detailCandidatesForAircraft(aircraft, detailMode, lookup)
        val remaining = AtomicInteger(candidates.size)
        val photoInFlight = AtomicInteger(0)
        val photoFound = AtomicBoolean(false)
        val detailLock = Any()
        val photoKeys = mutableSetOf<String>()
        var mergedDetails: AircraftDetails? = null

        fun maybePostFinalPhotoUnavailable() {
            if (remaining.get() != 0 || photoInFlight.get() != 0 || photoFound.get()) return
            post {
                if (!isCurrentDetailsRequest(requestedId, requestToken) || aircraftPhoto != null) return@post
                aircraftPhoto = null
                aircraftPhotoStatus = "Exact, representative, and search photos unavailable"
                aircraftPhotoEvidence = null
                aircraftPhotoQuality = null
                invalidate()
            }
        }

        fun lookupPhoto(details: AircraftDetails) {
            if (photoFound.get()) return
            val key = photoLookupKey(aircraft, details)
            val shouldRun = synchronized(photoKeys) { photoKeys.add(key) }
            if (!shouldRun) {
                maybePostFinalPhotoUnavailable()
                return
            }
            photoInFlight.incrementAndGet()
            val photo = aircraftPhotoFetcher.fetch(aircraft, details)
            photoInFlight.decrementAndGet()
            when (photo) {
                is AircraftPhotoResult.Found -> {
                    if (photoFound.compareAndSet(false, true)) {
                        post { postAircraftPhoto(requestedId, requestToken, photo, allowReplace = false) }
                    }
                }
                is AircraftPhotoResult.Unavailable -> maybePostFinalPhotoUnavailable()
            }
        }

        candidates.forEach { candidate ->
            executor.execute {
                val details = runCatching { candidate.fetch() }.getOrNull()
                val merged = synchronized(detailLock) {
                    if (details != null) {
                        mergedDetails = mergedDetails?.let { mergeAircraftDetails(it, details) } ?: details
                    }
                    mergedDetails
                }
                val stillLoading = remaining.decrementAndGet() > 0
                if (merged != null) {
                    post { postAircraftDetails(requestedId, requestToken, merged, stillLoading) }
                    lookupPhoto(merged)
                } else if (!stillLoading) {
                    post { postAircraftDetailsUnavailable(requestedId, requestToken) }
                    maybePostFinalPhotoUnavailable()
                }
            }
        }
    }

    private fun postAircraftDetails(requestedId: String, requestToken: Long, details: AircraftDetails, stillLoading: Boolean) {
        if (!isCurrentDetailsRequest(requestedId, requestToken)) return
        aircraftDetails = details
        aircraftDetailsLoading = stillLoading
        aircraftDetailsStatus = when {
            stillLoading && !hasAircraftMetadata(details) -> "Loading aircraft details from remaining feed sources"
            stillLoading -> "Metadata from ${details.registrySource ?: "configured sources"}; checking remaining feed sources"
            !hasAircraftMetadata(details) -> "Metadata unavailable from configured sources"
            else -> "Metadata from ${details.registrySource ?: "configured sources"}"
        }
        if (aircraftPhoto == null) {
            aircraftPhotoStatus = "Searching real photo sources"
        }
        invalidate()
    }

    private fun postAircraftDetailsUnavailable(requestedId: String, requestToken: Long) {
        if (!isCurrentDetailsRequest(requestedId, requestToken)) return
        aircraftDetailsLoading = false
        if (aircraftDetails == null) {
            aircraftDetailsStatus = "Metadata unavailable from configured sources"
        }
        invalidate()
    }

    private fun detailCandidatesForAircraft(
        aircraft: Aircraft,
        mode: FlightAlertSettings.AircraftFeedMode,
        lookup: WebDetailLookupContext?
    ): List<AircraftDetailCandidate> {
        val api = AircraftDetailCandidate("API feed") {
            aircraftDetailsClient.fetchDetails(aircraft.icao24, aircraft.callsign, aircraft.registration)
        }
        val web = AircraftDetailCandidate("Web feed") {
            fetchWebFeedDetails(aircraft, lookup)
        }
        return when (mode) {
            FlightAlertSettings.AircraftFeedMode.API -> listOf(api)
            FlightAlertSettings.AircraftFeedMode.WEB -> listOf(web, api)
            FlightAlertSettings.AircraftFeedMode.HYBRID -> listOf(api, web)
        }
    }

    private fun fetchWebFeedDetails(aircraft: Aircraft, lookup: WebDetailLookupContext?): AircraftDetails? {
        val deadline = SystemClock.elapsedRealtime() + WEB_DETAIL_WAIT_MS
        while (SystemClock.elapsedRealtime() <= deadline) {
            val seed = findWebMetadataSeed(aircraft, lookup)
            if (seed != null) {
                return aircraftDetailsClient.fetchDetails(aircraft.icao24, aircraft.callsign, aircraft.registration, seed)
            }
            Thread.sleep(WEB_DETAIL_POLL_MS)
        }
        return null
    }

    private fun findWebMetadataSeed(aircraft: Aircraft, lookup: WebDetailLookupContext?): AircraftMetadataSeed? {
        aircraft.metadataSeed?.takeIf { it.hasDetails }?.let { return it }
        val source = globeWebAircraftSource?.takeIf { globeWebSourceEnabled } ?: return null
        val context = lookup ?: return null
        val searches = listOfNotNull(
            aircraft.icao24.takeIf { it.isNotBlank() },
            aircraft.registration?.takeIf { it.isNotBlank() },
            aircraft.callsign.takeIf { it.isNotBlank() }
        ).distinct()
        searches.forEach { search ->
            source.latestSnapshot(context.bounds, context.ownLat, context.ownLon, search)
                ?.aircraft
                ?.firstOrNull { it.matchesAircraft(aircraft) }
                ?.metadata
                ?.takeIf { it.hasDetails }
                ?.let { return it }
        }
        return source.latestSnapshot(context.bounds, context.ownLat, context.ownLon, exactSearch = null)
            ?.aircraft
            ?.firstOrNull { it.matchesAircraft(aircraft) }
            ?.metadata
            ?.takeIf { it.hasDetails }
    }

    private fun webDetailLookupContext(): WebDetailLookupContext? {
        val location = latestLocation ?: return null
        return WebDetailLookupContext(
            bounds = aircraftBoundsForCurrentViewport(location).toFeedBounds(),
            ownLat = location.latitude,
            ownLon = location.longitude
        )
    }

    private fun FeedAircraft.matchesAircraft(aircraft: Aircraft): Boolean {
        val feedHex = icao24.trim().trimStart('~').lowercase(Locale.US)
        val selectedHex = aircraft.icao24.trim().trimStart('~').lowercase(Locale.US)
        if (feedHex.isNotBlank() && selectedHex.isNotBlank() && feedHex == selectedHex) return true
        val feedRegistration = normalizedRegistration(registration)
        val selectedRegistration = normalizedRegistration(aircraft.registration)
        if (feedRegistration != null && selectedRegistration != null && feedRegistration == selectedRegistration) return true
        return callsign.compactCallsign().isNotBlank() &&
            callsign.compactCallsign() == aircraft.callsign.compactCallsign()
    }

    private fun String.compactCallsign(): String {
        return trim().replace(" ", "").uppercase(Locale.US)
    }

    private fun postAircraftPhoto(
        requestedId: String,
        requestToken: Long,
        photo: AircraftPhotoResult.Found,
        allowReplace: Boolean
    ) {
        if (!isCurrentDetailsRequest(requestedId, requestToken)) return
        val currentQuality = aircraftPhotoQuality
        if (!allowReplace && aircraftPhoto != null) return
        if (allowReplace && currentQuality != null && photo.quality.rank <= currentQuality.rank) {
            aircraftPhotoStatus = "Current photo is best available from checked sources"
            invalidate()
            return
        }
        aircraftPhoto = photo.bitmap
        aircraftPhotoStatus = photo.note
        aircraftPhotoEvidence = photo.evidence
        aircraftPhotoQuality = photo.quality
        invalidate()
    }

    private fun photoLookupKey(aircraft: Aircraft, details: AircraftDetails): String {
        return listOfNotNull(
            normalizedRegistration(details.registration ?: aircraft.registration),
            details.manufacturer?.trim()?.uppercase(Locale.US),
            details.type?.trim()?.uppercase(Locale.US),
            details.typeCode?.trim()?.uppercase(Locale.US),
            details.owner?.trim()?.uppercase(Locale.US)
        ).joinToString("|").ifBlank { aircraft.icao24.trim().lowercase(Locale.US) }
    }

    private fun mergeAircraftDetails(seed: AircraftDetails, enrichment: AircraftDetails): AircraftDetails {
        val source = listOfNotNull(seed.registrySource, enrichment.registrySource)
            .flatMap { it.split(" + ") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" + ")
            .ifEmpty { null }
        return AircraftDetails(
            icao24 = seed.icao24,
            registration = enrichment.registration ?: seed.registration,
            manufacturer = enrichment.manufacturer ?: seed.manufacturer,
            type = enrichment.type ?: seed.type,
            typeCode = enrichment.typeCode ?: seed.typeCode,
            owner = enrichment.owner ?: seed.owner,
            manufacturedYear = enrichment.manufacturedYear ?: seed.manufacturedYear,
            registrySource = source,
            operatorCode = enrichment.operatorCode ?: seed.operatorCode,
            route = enrichment.route ?: seed.route,
            routeUpdatedEpochSec = enrichment.routeUpdatedEpochSec ?: seed.routeUpdatedEpochSec,
            routeSource = enrichment.routeSource ?: seed.routeSource,
            originAirport = enrichment.originAirport ?: seed.originAirport,
            destinationAirport = enrichment.destinationAirport ?: seed.destinationAirport
        )
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
            details.owner != null
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

    private fun registryCountryLabel(aircraft: Aircraft, details: AircraftDetails? = null, loading: Boolean = false): String {
        val registration = normalizedRegistration(details?.registration ?: aircraft.registration)
        return AircraftRegistryResolver.labelFor(registration, aircraft.icao24) ?: loadingOrUnavailable(loading)
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

    private fun setAircraftFeedMode(mode: FlightAlertSettings.AircraftFeedMode) {
        if (aircraftFeedMode == mode) return
        aircraftFeedMode = mode
        globeWebSourceEnabled = mode.usesGlobe
        prefs.edit {
            putString(FlightAlertSettings.KEY_AIRCRAFT_FEED_MODE, aircraftFeedMode.name)
            putBoolean(FlightAlertSettings.KEY_GLOBE_WEB_SOURCE_ENABLED, globeWebSourceEnabled)
        }
        globeWebAircraftSource?.setEnabled(globeWebSourceEnabled)
        aircraftStatus = when (aircraftFeedMode) {
            FlightAlertSettings.AircraftFeedMode.WEB -> "Web feed enabled; waiting for validated snapshot"
            FlightAlertSettings.AircraftFeedMode.API -> "API feed enabled"
            FlightAlertSettings.AircraftFeedMode.HYBRID -> "Hybrid feed enabled; loading API plus web supplement"
        }
        requestVisibleAircraftIfNeeded(force = true)
        invalidate()
    }

    private fun setAtcBoundariesLayerEnabled(enabled: Boolean) {
        if (atcBoundariesLayerEnabled == enabled) return
        atcBoundariesLayerEnabled = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_ATC_BOUNDARIES_ENABLED, enabled) }
        onAviationLayersChanged()
    }

    private fun setRestrictedAirspacesLayerEnabled(enabled: Boolean) {
        if (restrictedAirspacesLayerEnabled == enabled) return
        restrictedAirspacesLayerEnabled = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_RESTRICTED_AIRSPACES_ENABLED, enabled) }
        onAviationLayersChanged()
    }

    private fun setOceanicTracksLayerEnabled(enabled: Boolean) {
        if (oceanicTracksLayerEnabled == enabled) return
        oceanicTracksLayerEnabled = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_OCEANIC_TRACKS_ENABLED, enabled) }
        onAviationLayersChanged()
    }

    private fun setAirportLabelsLayerEnabled(enabled: Boolean) {
        if (airportLabelsLayerEnabled == enabled) return
        airportLabelsLayerEnabled = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_AIRPORT_LABELS_ENABLED, enabled) }
        onAviationLayersChanged()
    }

    private fun onAviationLayersChanged() {
        if (!hasAviationLayersEnabled()) {
            aviationLayerSnapshot = null
            lastAviationLayerBounds = null
            aviationLayerStatus = "Layers off"
        } else {
            aviationLayerStatus = "Loading aviation layers"
            latestLocation?.let { requestAviationLayersIfNeeded(viewportFor(it, contentWidth(), contentHeight()), force = true) }
        }
        invalidate()
    }

    private fun aircraftSourcePreferenceLabel(): String {
        return aircraftFeedMode.displayName
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
        zoom = log2((cos(Math.toRadians(latitude)) * MapProjection.EARTH_CIRCUMFERENCE_M) / (TILE_SIZE * metersPerPixel))
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
        return readEnumSetting(FlightAlertSettings.KEY_FILTER_FLIGHT_STATUS, FlightStatusFilter.AIRBORNE)
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
        flightStatusFilter = FlightStatusFilter.AIRBORNE
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
        return AircraftFilterEngine.sanitizeSearch(value)
    }

    private fun filtersActive(): Boolean {
        return AircraftFilterEngine.isActive(currentFilterState())
    }

    private fun filtersInNormalAirborneMode(): Boolean {
        return AircraftFilterEngine.isNormalAirborneMode(currentFilterState())
    }

    private fun filtersRestrictAircraft(): Boolean {
        return AircraftFilterEngine.restrictsAircraft(currentFilterState())
    }

    private fun filterStats(): FilterStats {
        val all = allAircraftSnapshot()
        return AircraftFilterEngine.stats(
            aircraft = all,
            filters = currentFilterState(),
            nowEpochSec = System.currentTimeMillis() / 1000.0,
            distanceMeters = ::displayDistanceMeters,
            isHazardAircraft = ::isHazardAircraft
        )
    }

    private fun allAircraftSnapshot(): List<Aircraft> {
        return synchronized(aircraft) { aircraft.toList() }
    }

    private fun filteredAircraftSnapshot(): List<Aircraft> {
        return allAircraftSnapshot().filter { passesAircraftFilters(it) }
    }

    private fun pruneSelectionForFilters() {
        if (!filtersRestrictAircraft()) return
        val selectedId = selectedAircraftId?.lowercase(Locale.US) ?: return
        val selectedLive = allAircraftSnapshot().firstOrNull { it.icao24.lowercase(Locale.US) == selectedId }
        if (selectedLive == null) {
            selectedAircraftSnapshot?.takeIf { it.icao24.lowercase(Locale.US) == selectedId }?.let { snapshot ->
                if (passesAircraftFilters(snapshot)) return
            }
        } else if (passesAircraftFilters(selectedLive)) {
            return
        }
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
        return AircraftFilterEngine.passes(
            aircraft = item,
            filters = currentFilterState(),
            nowEpochSec = System.currentTimeMillis() / 1000.0,
            distanceMeters = ::displayDistanceMeters,
            isHazardAircraft = ::isHazardAircraft
        )
    }

    private fun currentFilterState(): AircraftFilterState {
        return AircraftFilterState(
            searchQuery = filterSearchQuery,
            aircraftType = aircraftTypeFilter,
            altitude = altitudeFilter,
            distance = distanceFilter,
            flightStatus = flightStatusFilter,
            reportAge = reportAgeFilter,
            alertVolumeOnly = alertVolumeFilter
        )
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
            return RectF(left.left, panel.top + dp(182), left.right, panel.top + dp(208))
        }
        return RectF(panel.left + dp(18), panel.top + dp(244), panel.right - dp(18), panel.top + dp(278))
    }

    private fun mapLabelsButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            return RectF(left.left, panel.top + dp(212), left.right, panel.top + dp(238))
        }
        return RectF(panel.left + dp(18), panel.top + dp(284), panel.right - dp(18), panel.top + dp(318))
    }

    private fun globeWebSourceButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            return RectF(left.left, panel.top + dp(242), left.right, panel.top + dp(268))
        }
        return RectF(panel.left + dp(18), panel.top + dp(324), panel.right - dp(18), panel.top + dp(358))
    }

    private fun aviationLayersButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            return RectF(left.left, panel.top + dp(272), left.right, panel.top + dp(298))
        }
        return RectF(panel.left + dp(18), panel.top + dp(364), panel.right - dp(18), panel.top + dp(398))
    }

    private fun themeButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val left = compactSettingsLeftColumn(panel)
            return RectF(left.left, panel.top + dp(138), left.right, panel.top + dp(168))
        }
        return RectF(panel.left + dp(18), panel.top + dp(168), panel.right - dp(18), panel.top + dp(202))
    }

    private fun alertsToggleBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val right = compactSettingsRightColumn(panel)
            return RectF(right.left, panel.top + dp(66), right.right, panel.top + dp(96))
        }
        return RectF(panel.left + dp(18), panel.top + dp(452), panel.right - dp(18), panel.top + dp(486))
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
            return RectF(right.left, panel.top + dp(102), right.right, panel.top + dp(132))
        }
        return RectF(panel.left + dp(18), panel.top + dp(492), panel.right - dp(18), panel.top + dp(526))
    }

    private fun impactMethodologyButtonBounds(panel: RectF): RectF {
        if (isCompactSettingsPanel(panel)) {
            val right = compactSettingsRightColumn(panel)
            return RectF(right.left, panel.top + dp(166), right.right, panel.top + dp(196))
        }
        return RectF(panel.left + dp(18), panel.top + dp(580), panel.right - dp(18), panel.top + dp(614))
    }

    private fun impactSourceButtonBounds(panel: RectF, index: Int): RectF {
        val gap = dp(8)
        val safeIndex = index.coerceIn(0, AircraftImpactEstimator.sourceLabels.lastIndex)
        return if (isCompactSettingsPanel(panel)) {
            val left = panel.left + dp(18)
            val buttonWidth = (panel.width() - dp(36) - gap * (AircraftImpactEstimator.sourceLabels.size - 1)) / AircraftImpactEstimator.sourceLabels.size
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

    private fun aviationLayerStatusBounds(panel: RectF): RectF {
        return if (isCompactSettingsPanel(panel)) {
            RectF(panel.left + dp(18), panel.top + dp(64), panel.right - dp(18), panel.top + dp(86))
        } else {
            RectF(panel.left + dp(18), panel.top + dp(72), panel.right - dp(18), panel.top + dp(116))
        }
    }

    private fun layerAtcButtonBounds(panel: RectF): RectF {
        return layerToggleBounds(panel, row = 0, rightColumn = false)
    }

    private fun layerRestrictedButtonBounds(panel: RectF): RectF {
        return layerToggleBounds(panel, row = if (isCompactSettingsPanel(panel)) 0 else 1, rightColumn = isCompactSettingsPanel(panel))
    }

    private fun layerOceanicButtonBounds(panel: RectF): RectF {
        return layerToggleBounds(panel, row = if (isCompactSettingsPanel(panel)) 1 else 2, rightColumn = false)
    }

    private fun layerAirportLabelsButtonBounds(panel: RectF): RectF {
        return layerToggleBounds(panel, row = if (isCompactSettingsPanel(panel)) 1 else 3, rightColumn = isCompactSettingsPanel(panel))
    }

    private fun layerToggleBounds(panel: RectF, row: Int, rightColumn: Boolean): RectF {
        return if (isCompactSettingsPanel(panel)) {
            val column = if (rightColumn) compactSettingsRightColumn(panel) else compactSettingsLeftColumn(panel)
            val top = panel.top + dp(104 + row * 44)
            RectF(column.left, top, column.right, top + dp(32))
        } else {
            val top = panel.top + dp(126 + row * 46)
            RectF(panel.left + dp(18), top, panel.right - dp(18), top + dp(36))
        }
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
            ?: selectedAircraftSnapshot?.takeIf { !filtersRestrictAircraft() && it.icao24 == selectedAircraftId }
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
        return MapProjection.destinationPoint(lat, lon, bearingDeg, distanceM)
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

    private fun trafficDistanceColor(aircraft: Aircraft): Int {
        return if (isHazardAircraft(aircraft)) dangerColor else accentGreenColor
    }

    private fun formatAircraftLabelDetail(aircraft: Aircraft): String {
        val altitude = aircraft.altitudeM?.let { formatAltitudeValue(it) } ?: "alt n/a"
        return "${formatDistance(displayDistanceMeters(aircraft))}  $altitude"
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

    private fun streetAircraftLabelStyle(aircraft: Aircraft): AircraftLabelStyle {
        val aircraftTint = aircraftColor(aircraft)
        return when (themeStyle.treatment) {
            ThemeTreatment.DAYLIGHT_CARD -> AircraftLabelStyle(
                fill = withAlpha(Color.WHITE, 236),
                stroke = withAlpha(mixColor(accentBlueColor, aircraftTint, 0.28f), 176),
                title = themeColors.streetLabelText,
                detail = themeColors.streetLabelMuted,
                accent = withAlpha(aircraftTint, 226),
                radiusDp = 5f,
                strokeWidthDp = 0.8f
            )
            ThemeTreatment.GLASS -> AircraftLabelStyle(
                fill = withAlpha(mixColor(Color.WHITE, accentBlueColor, 0.10f), 226),
                stroke = withAlpha(accentBlueColor, 168),
                title = themeColors.streetLabelText,
                detail = themeColors.streetLabelMuted,
                accent = withAlpha(aircraftTint, 228),
                radiusDp = 8f,
                strokeWidthDp = 0.9f
            )
            ThemeTreatment.RADAR_GRID -> AircraftLabelStyle(
                fill = withAlpha(mixColor(panelAltColor, Color.WHITE, 0.08f), 228),
                stroke = withAlpha(accentYellowColor, 170),
                title = accentYellowColor,
                detail = mutedColor,
                accent = withAlpha(aircraftTint, 232),
                radiusDp = 3f,
                strokeWidthDp = 0.8f
            )
            ThemeTreatment.STORM_BAND -> AircraftLabelStyle(
                fill = withAlpha(mixColor(panelAltColor, Color.WHITE, 0.07f), 228),
                stroke = withAlpha(accentBlueColor, 156),
                title = textColor,
                detail = mutedColor,
                accent = withAlpha(aircraftTint, 230),
                radiusDp = 4f,
                strokeWidthDp = 0.9f
            )
            ThemeTreatment.CRT_SCANLINE -> AircraftLabelStyle(
                fill = withAlpha(mixColor(panelAltColor, scrimColor, 0.30f), 232),
                stroke = withAlpha(accentGreenColor, 164),
                title = accentGreenColor,
                detail = mutedColor,
                accent = withAlpha(aircraftTint, 228),
                radiusDp = 2f,
                strokeWidthDp = 0.8f
            )
            ThemeTreatment.PLAIN -> AircraftLabelStyle(
                fill = withAlpha(mixColor(Color.WHITE, panelAltColor, 0.08f), 232),
                stroke = withAlpha(mixColor(panelStrokeColor, aircraftTint, 0.35f), 166),
                title = themeColors.streetLabelText,
                detail = themeColors.streetLabelMuted,
                accent = withAlpha(aircraftTint, 226),
                radiusDp = 5f,
                strokeWidthDp = 0.8f
            )
        }
    }
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return MapProjection.distanceMeters(lat1, lon1, lat2, lon2)
    }

    private fun feetToMeters(feet: Double): Double = feet / 3.28084

    private fun metersPerPixelAt(latitude: Double, z: Double): Double {
        return MapProjection.metersPerPixelAt(latitude, z)
    }

    private fun latLonToWorld(lat: Double, lon: Double, z: Double): WorldPoint {
        return MapProjection.latLonToWorld(lat, lon, z)
    }

    private fun worldToLatLon(x: Double, y: Double, z: Double): GeoPoint {
        return MapProjection.worldToLatLon(x, y, z)
    }

    private fun normalizeLongitude(lon: Double): Double {
        return MapProjection.normalizeLongitude(lon)
    }

    private fun Bounds.toFeedBounds(): FeedBounds {
        return FeedBounds(minLat = minLat, minLon = minLon, maxLat = maxLat, maxLon = maxLon)
    }

    private fun Bounds.toAviationLayerBounds(): AviationLayerBounds {
        return AviationLayerBounds(minLat = minLat, minLon = minLon, maxLat = maxLat, maxLon = maxLon)
    }

    private fun AviationLayerBounds.toGeoBounds(): AviationGeoBounds {
        return AviationGeoBounds(minLat = minLat, minLon = minLon, maxLat = maxLat, maxLon = maxLon)
    }

    private fun Bounds.contains(other: Bounds): Boolean {
        return minLat <= other.minLat &&
            minLon <= other.minLon &&
            maxLat >= other.maxLat &&
            maxLon >= other.maxLon
    }

    private fun AviationGeoBounds.centerPoint(): AviationLayerPoint {
        return AviationLayerPoint(
            lat = (minLat + maxLat) / 2.0,
            lon = normalizeLongitude((minLon + maxLon) / 2.0)
        )
    }

    private fun FeedAircraft.toMapAircraft(): Aircraft {
        return Aircraft(
            icao24 = icao24,
            callsign = callsign,
            registration = registration,
            typeCode = typeCode,
            metadataSeed = metadata,
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
                val glassAccent = if (selected) stroke else accentBlueColor
                val clip = Path()
                clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
                val save = canvas.save()
                canvas.clipPath(clip)

                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.bottom,
                    intArrayOf(
                        withAlpha(Color.WHITE, (alpha * 0.78f).roundToInt()),
                        withAlpha(glassAccent, (alpha * 0.24f).roundToInt()),
                        withAlpha(Color.WHITE, (alpha * 0.18f).roundToInt())
                    ),
                    floatArrayOf(0f, 0.56f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rect, paint)
                paint.shader = null

                val shineHeight = min(rect.height() * 0.42f, dp(18)).coerceAtLeast(dp(7))
                paint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.top + shineHeight,
                    withAlpha(Color.WHITE, (alpha * 0.64f).roundToInt()),
                    withAlpha(Color.WHITE, 0),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + shineHeight, paint)
                paint.shader = null

                paint.color = withAlpha(glassAccent, (alpha * 0.40f).roundToInt())
                canvas.drawRect(rect.left, rect.top + dp(2), rect.left + dp(2), rect.bottom - dp(2), paint)
                paint.color = withAlpha(Color.WHITE, (alpha * 0.18f).roundToInt())
                canvas.drawRect(rect.right - dp(1.2f), rect.top + radius * 0.44f, rect.right, rect.bottom - radius * 0.44f, paint)

                val inset = dp(1.1f)
                val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                val innerRadius = max(0f, radius - inset)
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = dp(0.75f)
                strokePaint.color = withAlpha(Color.WHITE, (alpha * 0.78f).roundToInt())
                canvas.drawRoundRect(inner, innerRadius, innerRadius, strokePaint)

                strokePaint.strokeWidth = dp(0.9f)
                val lineInset = min(radius * 0.86f, rect.width() * 0.22f).coerceAtLeast(dp(7))
                strokePaint.color = withAlpha(glassAccent, (alpha * 0.95f).roundToInt())
                canvas.drawLine(rect.left + lineInset, rect.top + dp(4), rect.right - lineInset, rect.top + dp(4), strokePaint)
                strokePaint.strokeWidth = dp(0.7f)
                strokePaint.color = withAlpha(Color.WHITE, (alpha * 0.34f).roundToInt())
                canvas.drawLine(rect.left + lineInset, rect.bottom - dp(4), rect.right - lineInset, rect.bottom - dp(4), strokePaint)
                canvas.restoreToCount(save)
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

    private fun drawModalBackdrop(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        val alpha = when (themeStyle.treatment) {
            ThemeTreatment.DAYLIGHT_CARD -> 96
            ThemeTreatment.GLASS -> 120
            ThemeTreatment.CRT_SCANLINE -> 148
            else -> 132
        }
        paint.color = withAlpha(scrimColor, alpha)
        canvas.drawRect(0f, 0f, w, h, paint)
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
                val clip = Path()
                clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
                val save = canvas.save()
                canvas.clipPath(clip)

                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.bottom,
                    intArrayOf(
                        withAlpha(Color.WHITE, (themeStyle.textureAlpha * 0.58f).roundToInt()),
                        withAlpha(accentBlueColor, (themeStyle.textureAlpha * 0.16f).roundToInt()),
                        withAlpha(Color.WHITE, (themeStyle.textureAlpha * 0.10f).roundToInt())
                    ),
                    floatArrayOf(0f, 0.56f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rect, paint)
                paint.shader = null

                val shineHeight = min(dp(78), max(dp(28), rect.height() * 0.16f))
                paint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.top + shineHeight,
                    withAlpha(Color.WHITE, (themeStyle.textureAlpha * 0.42f).roundToInt()),
                    withAlpha(Color.WHITE, 0),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + shineHeight, paint)
                paint.shader = null

                paint.color = withAlpha(accentBlueColor, (themeStyle.textureAlpha * 0.34f).roundToInt())
                canvas.drawRect(rect.left, rect.top + dp(4), rect.left + dp(3), rect.bottom - dp(4), paint)
                paint.color = withAlpha(Color.WHITE, (themeStyle.textureAlpha * 0.13f).roundToInt())
                canvas.drawRect(rect.right - dp(2), rect.top + radius * 0.5f, rect.right, rect.bottom - radius * 0.5f, paint)

                val inset = dp(1.4f)
                val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                val innerRadius = max(0f, radius - inset)
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = dp(0.8f)
                strokePaint.color = withAlpha(Color.WHITE, (themeStyle.textureAlpha * 0.58f).roundToInt())
                canvas.drawRoundRect(inner, innerRadius, innerRadius, strokePaint)

                val lineInset = max(dp(16), min(rect.width(), rect.height()) * 0.1f)
                strokePaint.strokeWidth = dp(0.9f)
                strokePaint.color = withAlpha(accentBlueColor, (themeStyle.textureAlpha * 1.05f).roundToInt())
                canvas.drawLine(rect.left + lineInset, rect.top + dp(8), rect.right - lineInset, rect.top + dp(8), strokePaint)
                strokePaint.strokeWidth = dp(0.75f)
                strokePaint.color = withAlpha(Color.WHITE, (themeStyle.textureAlpha * 0.38f).roundToInt())
                canvas.drawLine(rect.left + lineInset, rect.bottom - dp(9), rect.right - lineInset, rect.bottom - dp(9), strokePaint)
                canvas.restoreToCount(save)
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
        const val WEB_DETAIL_WAIT_MS = 9000L
        const val WEB_DETAIL_POLL_MS = 350L
        const val PHOTO_LONG_PRESS_MS = 550L
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
        const val AVIATION_LAYER_REFRESH_MS = 5L * 60L * 1000L
        const val AVIATION_LAYER_BOUNDS_PADDING_FRACTION = 0.75
        const val MAX_DRAWN_AIRSPACE_FEATURES = 80
        const val MAX_DRAWN_RINGS_PER_FEATURE = 4
        const val MAX_DRAWN_AIRSPACE_POINTS_PER_RING = 180
        const val MAX_DRAWN_AIRPORT_LABELS = 36
        const val MAX_DRAWN_AIRPORT_LABELS_LOW_ZOOM = 16
        const val MAX_DRAWN_OCEANIC_TRACKS = 16
        const val MAX_DRAWN_OCEANIC_POINTS = 24
        const val AIRSPACE_LABEL_MIN_ZOOM = 7.2
        const val AIRPORT_LABEL_MIN_ZOOM = 8.4
        const val OCEANIC_TRACK_MIN_ZOOM = 3.0
        const val PATH_FIT_CONTEXT_MULTIPLIER = 1.5
        const val PRIORITY_PANEL_ROWS = 5
        const val PROOF_QUOTE_LINES = 3
        const val PHOTO_PLACEHOLDER_LINES = 4
        const val PHOTO_CAPTION_MAX_LINES = 7
        const val DETAILS_ROW_MAX_LINES = 12
        const val DETAILS_EVIDENCE_LINE_MAX_LINES = 4
        const val DETAILS_PROOF_QUOTE_LINES = 12
        const val AIRCRAFT_TAP_RADIUS_DP = 42
        const val HOLE_PUNCH_MAX_SIZE_DP = 72
        const val DB_FLAG_MILITARY = 1
        const val MAX_ESTIMATION_SECONDS = 75.0
        const val PATH_TRACE_NEWER_THAN_FEED_SECONDS = 45L
        const val MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS = 180.0
        const val ALTITUDE_COLOR_MAX_FEET = 45000.0
        const val MIN_PROJECTED_PATH_CONNECTOR_M = 60.0
        const val RADAR_GRID_SPACING_DP = 36f
        const val ORIGIN_AERODROME_RADIUS_M = 9000.0
        const val DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M = 30000.0
        const val INITIAL_RANGE_MULTIPLIER = 1.25
        const val TILE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        const val USER_AGENT = "FlightAlertPrototype/0.1"
        const val TAG = "FlightAlert"

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
