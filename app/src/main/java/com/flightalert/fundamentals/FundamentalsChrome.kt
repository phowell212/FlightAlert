package com.flightalert.fundamentals

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flightalert.FlightAlertSettings
import com.flightalert.details.*
import com.flightalert.map.ScaleLabel
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.ui.*

internal data class FundamentalsReadout(
    val location: String, val traffic: String, val trafficReady: Boolean, val partial: Boolean,
    val trafficUnavailable: Boolean,
    val visible: Int, val extent: String, val tiles: String, val dictionary: String,
    val scale: ScaleLabel, val selected: Boolean, val aircraft: FundamentalsAircraftDetail?,
    val selection: FundamentalsSelectionReadout, val attribution: String,
    val aviation: com.flightalert.map.aviation_layers.AviationSelectionPresentation?, val mapStatus: String,
    val trafficPanel: TrafficPanelState,
    val monitoringRequested: Boolean,
)

internal data class FundamentalsAircraftDetail(
    val callsign: String, val identity: String, val altitude: String,
    val speed: String, val distance: String, val age: String,
)

/** Native HUD views retain their display lists while the map animates beneath them. */
internal class FundamentalsChrome(
    context: Context,
    private val map: FundamentalsMapView,
    onRecenter: () -> Unit,
    onSettings: () -> Unit,
    onMap: () -> Unit,
) : FrameLayout(context) {
    private val theme = FlightAlertSettings.read_visual_theme(context)
    private val colors = theme.colors
    private val ui = FundamentalsUiStyle(context, theme)
    private fun dp(n: Float) = ui.dp(n)
    private fun text(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size; setTextColor(colors.text); includeFontPadding = false
        typeface = Typeface.create(theme.style.font_family, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun action(label: String, description: String, run: () -> Unit) = Button(context).apply {
        ui.button(this); text = label; contentDescription = description
        setOnClickListener { run() }
    }
    private var headerSubtitle = "Live map · Alerts off"
    private var headerTraffic = "CONNECTING" to colors.accent_yellow
    private var headerScale = ScaleLabel(0f, "")
    private var statusText = ""
    private val top = object : View(context) {
        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
        override fun onDraw(canvas: Canvas) {
            ui.renderer.draw_top_status(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()),
                headerSubtitle, headerTraffic, headerScale, ui.style)
        }
    }.apply { isFocusable = true; setOnClickListener { showMapStatus() } }
    private var filtersActive = false
    private var historyVisible = false
    private lateinit var filterButton: Button
    private lateinit var pathButton: Button
    private lateinit var historyButton: Button
    private lateinit var clearPathButton: Button
    private val controls = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec)
            val gap = dp(if (compactHud) 6f else 10f)
            var x = 0; var rows = 1
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue
                val childWidth = (if (compactHud) dp(44f) else child.layoutParams.width).coerceAtMost(available)
                if (x > 0 && x + childWidth > available) { x = 0; rows++ }
                child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(48f), MeasureSpec.EXACTLY))
                x += childWidth + gap
            }
            setMeasuredDimension(available, rows * dp(48f) + (rows - 1) * gap)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val gap = dp(if (compactHud) 6f else 10f)
            var x = 0; var y = height - dp(48f)
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue
                if (x > 0 && x + child.measuredWidth > width) { x = 0; y -= dp(48f) + gap }
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                x += child.measuredWidth + gap
            }
        }
    }
    private val summaryPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(theme.style.font_family, Typeface.NORMAL)
    }
    private val summaryRenderer = TrafficPanelRenderer(summaryPaint, Paint(Paint.ANTI_ALIAS_FLAG),
        object : TrafficPanelChrome {
            override fun dp(value: Float) = value * resources.displayMetrics.density
            override fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
            override fun ellipsize(value: String, max_width: Float) = android.text.TextUtils.ellipsize(
                value, summaryPaint, max_width.coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString()
            override fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int) =
                ui.renderer.draw_panel_surface(canvas, rect, fill, alpha, ui.style)
        }, ui::alpha)
    private var trafficPanel: TrafficPanelState? = null
    private val info = object : View(context) {
        private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent) = performClick()
        })
        override fun onTouchEvent(event: MotionEvent): Boolean =
            if (isClickable) taps.onTouchEvent(event) else super.onTouchEvent(event)
        override fun performClick(): Boolean = super.performClick()
        override fun getAccessibilityClassName(): CharSequence =
            if (isClickable) Button::class.java.name else super.getAccessibilityClassName()
        override fun onDraw(canvas: Canvas) {
            trafficPanel?.let {
                summaryRenderer.draw_panel(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    wide, TrafficPanelStyle(theme), it)
            }
        }
    }.apply {
        isFocusable = true
        setOnClickListener { if (selected) map.openDetails() else map.openNearestDetails() }
    }
    private val modalTitle = text(22f, true).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setAutoSizeTextTypeUniformWithConfiguration(12, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private val usage = action("Usage", "Open aircraft usage", map::openUsage)
    private val close = action("Close", "Close aircraft details", map::closeAircraftPanel)
    private val impact = action("Environmental impact", "Open aircraft impact", map::openImpact)
    private val richerDetails = FundamentalsDetailsView(context, map)
    private val detailScroll = ScrollView(context).apply { isFillViewport = false; addView(richerDetails) }
    private val modal = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = ui.panel()
        setPadding(dp(18f), dp(14f), dp(18f), dp(16f)); isClickable = true
    }
    private val overlay = FrameLayout(context).apply { visibility = GONE; isClickable = true }
    private var detailPage = AircraftDetailsPage.CLOSED
    private var selected = false
    private var selectedKey: String? = null
    private var inspectorTitle: String? = null
    private var wide = false
    private var compactHud = false

    init {
        setBackgroundColor(colors.system_bar)
        addView(map); addView(top)
        fun icon(description: String, width: Float, draw: (Canvas, RectF) -> Unit,
                 run: () -> Unit): Button = Button(context).apply {
            text = ""; contentDescription = description
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            background = RippleDrawable(ColorStateList.valueOf(ui.alpha(colors.accent_blue, 48)),
                ui.drawing { canvas, rect -> rect.inset(0f, dp(2f).toFloat()); draw(canvas, rect) }, null)
            setPadding(0, 0, 0, 0); setOnClickListener { run() }
            controls.addView(this, LayoutParams(dp(width), dp(48f)))
        }
        icon("Open app settings", 84f,
            { canvas, rect -> ui.renderer.draw_settings_button(canvas, rect, ui.style) }, onSettings)
        filterButton = icon("Open map controls", 84f,
            { canvas, rect -> ui.renderer.draw_filters_button(canvas, rect, filtersActive, ui.style) }, onMap)
        pathButton = icon("Show flight path", 44f,
            { canvas, rect -> ui.renderer.draw_flight_path_button(canvas, rect, "Path", colors.accent_yellow, ui.style) }, map::showPath)
        icon("My location: recenter map or enable location", 44f,
            { canvas, rect -> ui.renderer.draw_recenter_button(canvas, rect, ui.style) }, onRecenter)
        historyButton = icon("Toggle previous flights", 44f,
            { canvas, rect -> ui.renderer.draw_flight_path_button(canvas, rect, "History",
                if (historyVisible) colors.accent_green else colors.accent_blue, ui.style) }, map::toggleHistory)
        clearPathButton = icon("Clear visible flight path", 44f,
            { canvas, rect -> ui.renderer.draw_flight_path_button(canvas, rect, "Clear", colors.danger, ui.style) }, map::clearPath)
        pathButton.visibility = GONE; historyButton.visibility = GONE; clearPathButton.visibility = GONE
        addView(controls); addView(info)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(modalTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10f) })
        header.addView(usage, LinearLayout.LayoutParams(dp(76f), dp(44f)).apply { rightMargin = dp(10f) })
        header.addView(close, LinearLayout.LayoutParams(dp(76f), dp(44f)))
        modal.addView(header, LinearLayout.LayoutParams(-1, -2))
        modal.addView(detailScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8f); bottomMargin = dp(8f) })
        modal.addView(impact, LinearLayout.LayoutParams(-1, dp(44f)))
        overlay.addView(modal, LayoutParams(-1, -1, Gravity.CENTER))
        addView(overlay)
    }

    fun show(readout: FundamentalsReadout) {
        fun TextView.update(value: String) { if (text.toString() != value) text = value }
        val subtitle = if (readout.monitoringRequested) "Live map · Monitoring requested" else "Live map · Alerts off"
        val status = when {
            readout.trafficUnavailable -> "UNAVAILABLE"
            !readout.trafficReady -> "CONNECTING"
            readout.partial -> "PARTIAL FEED"
            else -> "TRAFFIC LIVE"
        }
        val traffic = status to if (readout.trafficReady && !readout.partial) colors.accent_green else colors.accent_yellow
        if (headerSubtitle != subtitle || headerTraffic != traffic || headerScale != readout.scale) {
            headerSubtitle = subtitle; headerTraffic = traffic; headerScale = readout.scale
            top.contentDescription = "Flight Alert. $subtitle. $status. Map scale: ${readout.scale.label}. Open map status and sources"
            top.invalidate()
        }
        statusText = "${readout.visible} aircraft · ${readout.extent}\n${readout.location}\n${readout.traffic}\n${readout.tiles}\n${readout.dictionary}\n${readout.mapStatus}" +
            (if (readout.selected) "\n${readout.selection.pathStatus}" else "") +
            "\n\n${readout.attribution}\nAircraft feeds: ADSB.lol · Airplanes.Live\nReferences: © OpenStreetMap contributors"
        val active = com.flightalert.traffic.aircraft_filters_active(map.aircraftFilters())
        if (filtersActive != active) { filtersActive = active; filterButton.background.invalidateSelf() }
        if (trafficPanel != readout.trafficPanel) {
            trafficPanel = readout.trafficPanel
            info.contentDescription = when (val content = readout.trafficPanel.content) {
                is TrafficPanelAircraftState -> "${readout.trafficPanel.title}. ${content.callsign}. ${content.model_label}. ${content.distance_label}. " +
                    content.wide_rows.joinToString(". ") { "${it.label}: ${it.value}" } + ". Open aircraft details"
                is TrafficPanelEmptyState -> "${content.headline}. ${content.message}. ${content.data_time_label.orEmpty()}"
            }
            info.isClickable = readout.trafficPanel.content is TrafficPanelAircraftState
            info.invalidate()
        }
        val selection = readout.selection
        pathButton.visibility = if (readout.selected) VISIBLE else GONE
        pathButton.isEnabled = !selection.pathStatus.startsWith("Loading")
        pathButton.alpha = if (pathButton.isEnabled) 1f else .45f
        pathButton.contentDescription = (if (selection.pathVisible) "Fit sourced flight path" else "Show flight path") + ". ${selection.pathStatus}"
        pathButton.tooltipText = selection.pathStatus
        historyButton.visibility = if (selection.pathVisible && selection.historyAvailable) VISIBLE else GONE
        if (historyVisible != selection.historyVisible) {
            historyVisible = selection.historyVisible; historyButton.background.invalidateSelf()
        }
        historyButton.contentDescription = if (historyVisible) "Hide previous flights" else "Show previous flights"
        clearPathButton.visibility = if (selection.pathVisible) VISIBLE else GONE
        val aviation = readout.aviation
        val modalOpen = selection.page.is_open || aviation != null
        val main = selection.page == AircraftDetailsPage.MAIN && aviation == null
        overlay.visibility = if (modalOpen) VISIBLE else GONE
        usage.visibility = if (main && readout.selected) VISIBLE else GONE
        impact.visibility = if (main && readout.selected) VISIBLE else GONE
        val secondary = aviation == null && selection.page.is_open && !main &&
            selection.page.overlay !is AircraftDetailsOverlay.PHOTO_EVIDENCE
        close.update(if (secondary) "Back" else "Close")
        close.contentDescription = when {
            aviation != null -> "Close aviation details"
            secondary -> "Back to aircraft details"
            selection.page.overlay is AircraftDetailsOverlay.PHOTO_EVIDENCE -> "Close photo evidence"
            else -> "Close aircraft details"
        }
        modalTitle.update(aviation?.title ?: when (val content = selection.content) {
            is AircraftDetailsMainState -> content.title
            is AircraftUsagePanelState -> "Flight usage"
            is AircraftImpactPanelState -> "Environmental impact"
            is AircraftPhotoGalleryPanelState -> "Aircraft photos"
            is AircraftPhotoEvidencePanelState -> "Photo evidence"
            null -> "Aircraft details"
        })
        if (aviation != null) richerDetails.showAviation(aviation) else richerDetails.show(selection)
        listOf(map, top, info, controls).forEach {
            it.importantForAccessibility = if (modalOpen) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        if (selected != readout.selected || detailPage != selection.page || selectedKey != selection.aircraftKey || inspectorTitle != aviation?.title) {
            selected = readout.selected; detailPage = selection.page
            selectedKey = selection.aircraftKey; inspectorTitle = aviation?.title
            detailScroll.scrollTo(0, 0); requestLayout()
        }
    }

    private fun showMapStatus() {
        val content = text(12f).apply { text = statusText; setPadding(dp(18f), dp(12f), dp(18f), dp(12f)) }
        val scroll = ScrollView(context).apply { addView(content) }
        val dialog = AlertDialog.Builder(context).setTitle("Map status and sources").setView(scroll)
            .setPositiveButton("Close", null).create()
        dialog.show(); ui.dialog(dialog)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val innerW = (w - paddingLeft - paddingRight).coerceAtLeast(1)
        val innerH = (h - paddingTop - paddingBottom).coerceAtLeast(1)
        fun exact(n: Int) = MeasureSpec.makeMeasureSpec(n.coerceAtLeast(1), MeasureSpec.EXACTLY)
        val layoutWide = innerW > innerH * 1.15f && innerW >= dp(520f)
        if (wide != layoutWide) info.invalidate()
        wide = layoutWide
        compactHud = !wide && innerH < dp(400f)
        val gap = dp(if (compactHud) 6f else 12f)
        val infoW = if (wide) minOf(dp(360f), maxOf(dp(300f), (innerW * .32f).toInt())) else innerW - 2 * gap
        val mapHudW = if (wide) innerW - infoW - 3 * gap else innerW - 2 * gap
        map.measure(exact(innerW), exact(innerH))
        top.measure(exact(minOf(dp(620f), mapHudW)), exact(dp(66f)))
        controls.measure(exact(mapHudW), exact(dp(48f)))
        val cardH = if (wide) innerH - 2 * gap else dp(176f)
        info.measure(exact(infoW), exact(cardH))
        if (overlay.visibility != GONE) {
            val modalWide = innerW > innerH * 1.15f
            richerDetails.setCompact(!modalWide)
            val params = modal.layoutParams as LayoutParams
            params.width = if (modalWide) minOf(dp(800f), innerW - dp(28f)) else innerW - dp(28f)
            params.height = minOf(innerH - dp(28f), dp(if (modalWide) 390f else 720f))
            overlay.measure(exact(innerW), exact(innerH))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = paddingLeft; val topEdge = paddingTop
        val right = width - paddingRight; val bottom = height - paddingBottom
        val gap = dp(if (compactHud) 6f else 12f)
        map.layout(left, topEdge, right, bottom)
        top.layout(left + gap, topEdge + gap, left + gap + top.measuredWidth, topEdge + gap + top.measuredHeight)
        val infoLeft = if (wide) right - gap - info.measuredWidth else left + gap
        val infoTop = if (wide) topEdge + gap else bottom - gap - info.measuredHeight
        info.layout(infoLeft, infoTop, infoLeft + info.measuredWidth, infoTop + info.measuredHeight)
        val controlsBottom = if (wide) bottom - gap else infoTop - gap
        controls.layout(left + gap, controlsBottom - controls.measuredHeight, left + gap + controls.measuredWidth, controlsBottom)
        if (overlay.visibility != GONE) overlay.layout(left, topEdge, right, bottom)
        val openRight = if (wide) info.left - gap else right
        map.setMapFocus((left + openRight) / 2f - left, (top.bottom + controls.top) / 2f - topEdge)
        map.setLabelAvoidance(listOf(top, info, controls).map {
            ReferenceScreenRect((it.left - left).toDouble(), (it.top - topEdge).toDouble(),
                (it.right - left).toDouble(), (it.bottom - topEdge).toDouble()).padded(dp(8f).toDouble())
        })
    }
}
