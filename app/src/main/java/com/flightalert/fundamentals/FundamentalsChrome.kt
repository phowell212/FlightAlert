package com.flightalert.fundamentals

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flightalert.FlightAlertSettings
import com.flightalert.map.ScaleLabel
import com.flightalert.map.reference.labels.ReferenceScreenRect

internal data class FundamentalsReadout(
    val location: String, val traffic: String, val trafficReady: Boolean, val partial: Boolean,
    val trafficUnavailable: Boolean,
    val visible: Int, val extent: String, val tiles: String, val dictionary: String,
    val scale: ScaleLabel, val selected: Boolean, val aircraft: FundamentalsAircraftDetail?,
    val selection: FundamentalsSelectionReadout, val attribution: String,
    val aviation: com.flightalert.map.aviation_layers.AviationSelectionPresentation?, val mapStatus: String,
    val trafficPanel: com.flightalert.ui.TrafficPanelState,
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
    private fun dp(n: Float) = (n * resources.displayMetrics.density).toInt()
    private fun alpha(color: Int, alpha: Int) = (color and 0xffffff) or (alpha shl 24)
    private fun text(size: Float, color: Int = colors.text, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(theme.style.font_family, if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }
    private val ui = FundamentalsUiStyle(context, theme)
    private fun controlBackground() = ui.choiceBackground()
    private var headerSubtitle = "Live map · Alerts off"
    private var headerTraffic = "CONNECTING" to colors.accent_yellow
    private var headerScale = ScaleLabel(0f, "")
    private val top = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            ui.renderer.draw_top_status(canvas, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()),
                headerSubtitle, headerTraffic, headerScale, ui.style)
        }
    }.apply { isClickable = true }
    private var filtersActive = false
    private lateinit var filterButton: Button
    private val controls = LinearLayout(context)
    private val info = ScrollView(context).apply {
        isFillViewport = true
        background = ui.panel(colors.panel, theme.style.info_panel_alpha)
        isClickable = true
    }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(10f), dp(16f), dp(12f))
    }
    private val heading = text(14f, colors.accent_green, true)
    private val title = text(24f, bold = true).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setAutoSizeTextTypeUniformWithConfiguration(14, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private val model = text(13f, bold = true).apply {
        gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private val distance = text(24f, bold = true).apply {
        gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setAutoSizeTextTypeUniformWithConfiguration(14, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private val titleRow = object : LinearLayout(context) {
        override fun getAccessibilityClassName(): CharSequence = if (isClickable) Button::class.java.name else super.getAccessibilityClassName()
    }.apply { gravity = Gravity.CENTER_VERTICAL }
    private val identity = text(11f, colors.muted)
    private val facts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val values = mutableListOf<TextView>()
    private val close = Button(context).apply {
        text = "×"
        textSize = 22f
        setTextColor(colors.text)
        contentDescription = "Close aircraft details"
        setPadding(0, 0, 0, 0)
        minimumWidth = 0
        minimumHeight = 0
        background = controlBackground()
        setOnClickListener { map.closeAircraftPanel() }
    }
    private val health = text(10f, colors.muted).apply { setPadding(0, dp(8f), 0, 0) }
    private val attribution = text(9f, colors.muted).apply { setPadding(0, dp(8f), 0, 0) }
    private val aircraftActions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val richerDetails = FundamentalsDetailsView(context, map)
    private val traceStatus = text(10f, colors.muted)
    private val actionButtons = mutableMapOf<String, Button>()
    private var detailPage = com.flightalert.details.AircraftDetailsPage.CLOSED
    private var selected = false
    private var inspector = false
    private var selectedKey: String? = null
    private var inspectorTitle: String? = null
    private var wide = false

    init {
        setBackgroundColor(colors.system_bar)
        addView(map)
        addView(top)
        fun icon(description: String, width: Float, draw: (Canvas, android.graphics.RectF) -> Unit,
                 action: () -> Unit): Button = Button(context).apply {
            text = ""; contentDescription = description
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            background = RippleDrawable(ColorStateList.valueOf(alpha(colors.accent_blue, 48)),
                ui.drawing { canvas, rect -> rect.inset(0f, dp(2f).toFloat()); draw(canvas, rect) }, null)
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
            controls.addView(this, LinearLayout.LayoutParams(dp(width), -1).apply {
                if (controls.childCount > 0) leftMargin = dp(10f)
            })
        }
        icon("Open app settings", 84f,
            { canvas, rect -> ui.renderer.draw_settings_button(canvas, rect, ui.style) }, onSettings)
        filterButton = icon("Open map controls", 84f,
            { canvas, rect -> ui.renderer.draw_filters_button(canvas, rect, filtersActive, ui.style) }, onMap)
        icon("My location: recenter map or enable location", 44f,
            { canvas, rect -> ui.renderer.draw_recenter_button(canvas, rect, ui.style) }, onRecenter)
        addView(controls)

        val headingRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        headingRow.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        headingRow.addView(model, LinearLayout.LayoutParams(0, -2, .8f))
        headingRow.addView(close, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        body.addView(headingRow)
        titleRow.addView(title, LinearLayout.LayoutParams(0, -2, .62f))
        titleRow.addView(distance, LinearLayout.LayoutParams(0, -2, .38f).apply { leftMargin = dp(12f) })
        titleRow.contentDescription = "Open nearest aircraft details"
        titleRow.setOnClickListener { if (!selected && !inspector) map.openNearestDetails() }
        body.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f); bottomMargin = dp(8f) })
        body.addView(identity)
        listOf("ALTITUDE", "SPEED", "DISTANCE", "POSITION AGE").chunked(2).forEach { labels ->
            val row = LinearLayout(context)
            labels.forEach { label ->
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(12f), dp(8f), dp(8f))
                }
                cell.addView(text(10f, colors.muted, true).apply { text = label })
                cell.addView(text(18f, bold = true).also { values.add(it) })
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            facts.addView(row)
            facts.addView(View(context).apply { setBackgroundColor(alpha(colors.panel_stroke, theme.style.divider_alpha)) },
                LinearLayout.LayoutParams(-1, dp(1f)))
        }
        fun aircraftAction(name: String, description: String, action: () -> Unit) = Button(context).apply {
            ui.button(this)
            text = name; contentDescription = description; textSize = 11f
            minimumWidth = 0; minimumHeight = 0
            setPadding(dp(3f), 0, dp(3f), 0); background = controlBackground()
            setOnClickListener { action() }; actionButtons[name] = this
        }
        fun aircraftActionRow(vararg actions: Button) {
            val row = LinearLayout(context)
            for (action in actions) row.addView(action, LinearLayout.LayoutParams(0, dp(42f), 1f).apply {
                leftMargin = if (row.childCount == 0) 0 else dp(5f)
            })
            aircraftActions.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5f) })
        }
        aircraftActionRow(aircraftAction("Details", "Open aircraft details", map::openDetails),
            aircraftAction("Path", "Show flight path", map::showPath),
            aircraftAction("History", "Toggle previous flights", map::toggleHistory))
        aircraftActionRow(aircraftAction("Usage", "Open aircraft usage", map::openUsage),
            aircraftAction("Impact", "Open aircraft impact", map::openImpact),
            aircraftAction("Photos", "Open aircraft photo gallery", map::openPhotos))
        aircraftActionRow(aircraftAction("Fit path", "Fit sourced flight path", map::fitSelectedPath),
            aircraftAction("Clear path", "Clear visible flight path", map::clearPath))
        aircraftActions.addView(traceStatus)
        body.addView(aircraftActions)
        body.addView(facts)
        body.addView(richerDetails)
        body.addView(health)
        body.addView(attribution)
        info.addView(body)
        addView(info)
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
            top.contentDescription = "Flight Alert. $subtitle. $status. Map scale: ${readout.scale.label}"
            top.invalidate()
        }
        val active = com.flightalert.traffic.aircraft_filters_active(map.aircraftFilters())
        if (filtersActive != active) { filtersActive = active; filterButton.background.invalidateSelf() }
        val aircraft = readout.aircraft
        val aviation = readout.aviation
        val nearest = readout.trafficPanel.content as? com.flightalert.ui.TrafficPanelAircraftState
        heading.update(if (aviation != null) "AVIATION FEATURE" else if (readout.selected) "SELECTED AIRCRAFT" else readout.trafficPanel.title)
        heading.setTextColor(if (aviation != null || readout.selected) colors.accent_blue else readout.trafficPanel.title_color)
        title.update(aviation?.title ?: aircraft?.callsign ?: if (readout.selected) "Aircraft unavailable" else nearest?.callsign ?:
            (readout.trafficPanel.content as? com.flightalert.ui.TrafficPanelEmptyState)?.headline ?: "Aircraft feed")
        model.update(nearest?.model_label ?: "")
        model.visibility = if (!readout.selected && aviation == null && nearest?.model_label != null) VISIBLE else GONE
        distance.update(if (readout.selected) aircraft?.distance ?: "Unavailable" else nearest?.distance_label ?: "")
        distance.setTextColor(if (readout.selected) colors.muted else nearest?.distance_color ?: colors.muted)
        distance.visibility = if (aviation == null && (aircraft != null || nearest != null)) VISIBLE else GONE
        identity.update(aviation?.subtitle ?: aircraft?.identity ?: "")
        identity.visibility = if (readout.selected || aviation != null) VISIBLE else GONE
        titleRow.isClickable = !readout.selected && aviation == null && nearest != null
        titleRow.contentDescription = if (titleRow.isClickable) "Open nearest aircraft details" else null
        facts.visibility = if (aircraft == null || readout.selection.page.is_open) GONE else VISIBLE
        aircraftActions.visibility = if (readout.selected) VISIBLE else GONE
        traceStatus.update(readout.selection.pathStatus)
        actionButtons.getValue("Path").isEnabled = !readout.selection.pathStatus.startsWith("Loading")
        actionButtons.getValue("History").isEnabled = readout.selection.pathVisible && readout.selection.historyAvailable
        actionButtons.getValue("History").text = if (readout.selection.historyVisible) "Hide history" else "History"
        actionButtons.getValue("Photos").isEnabled = readout.selection.photosAvailable
        actionButtons.getValue("Fit path").visibility = if (readout.selection.pathVisible) VISIBLE else GONE
        actionButtons.getValue("Clear path").visibility = if (readout.selection.pathVisible) VISIBLE else GONE
        richerDetails.show(readout.selection)
        if (aviation != null) richerDetails.showAviation(aviation)
        else if (!readout.selected) richerDetails.showTraffic(readout.trafficPanel, wide)
        close.visibility = if (readout.selected || aviation != null) VISIBLE else GONE
        close.contentDescription = if (aviation != null) "Close aviation details" else "Close aircraft details"
        if (aircraft != null) listOf(aircraft.altitude, aircraft.speed, aircraft.distance, aircraft.age)
            .forEachIndexed { index, value -> values[index].update(value) }
        health.update("${readout.visible} aircraft · ${readout.extent}\n${readout.location}\n${readout.traffic} · ${readout.tiles}\n${readout.dictionary}\n${readout.mapStatus}")
        attribution.update("${readout.attribution}\nAircraft feeds: ADSB.lol · Airplanes.Live\nReferences: © OpenStreetMap contributors")
        if (selected != readout.selected || detailPage != readout.selection.page || selectedKey != readout.selection.aircraftKey || inspectorTitle != aviation?.title) {
            selected = readout.selected; detailPage = readout.selection.page
            selectedKey = readout.selection.aircraftKey; inspectorTitle = aviation?.title; inspector = aviation != null
            info.scrollTo(0, 0); requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val innerW = (w - paddingLeft - paddingRight).coerceAtLeast(1)
        val innerH = (h - paddingTop - paddingBottom).coerceAtLeast(1)
        fun exact(n: Int) = MeasureSpec.makeMeasureSpec(n.coerceAtLeast(1), MeasureSpec.EXACTLY)
        val gap = dp(12f)
        // A side card needs enough room for both the map controls and readable text.
        val layoutWide = innerW > innerH * 1.15f && innerW >= dp(680f)
        if (wide != layoutWide) {
            title.setAutoSizeTextTypeUniformWithConfiguration(14, if (layoutWide) 30 else 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            distance.setAutoSizeTextTypeUniformWithConfiguration(14, if (layoutWide) 26 else 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        wide = layoutWide
        val infoW = if (wide) minOf(dp(360f), maxOf(dp(300f), (innerW * .32f).toInt())) else innerW - 2 * gap
        val mapHudW = if (wide) innerW - infoW - 3 * gap else innerW - 2 * gap
        map.measure(exact(innerW), exact(innerH))
        top.measure(exact(minOf(dp(620f), mapHudW)), exact(dp(66f)))
        controls.measure(MeasureSpec.makeMeasureSpec(mapHudW, MeasureSpec.AT_MOST), exact(dp(48f)))
        val cardH = if (wide) innerH - 2 * gap else minOf(
            dp(if (detailPage.is_open || inspector) 500f else if (selected) 380f else 176f),
            (innerH - top.measuredHeight - dp(136f)).coerceAtLeast(dp(96f)))
        richerDetails.setCompact(!wide)
        info.measure(exact(infoW), exact(cardH))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = paddingLeft
        val topEdge = paddingTop
        val right = width - paddingRight
        val bottom = height - paddingBottom
        val gap = dp(12f)
        map.layout(left, topEdge, right, bottom)
        top.layout(left + gap, topEdge + gap, left + gap + top.measuredWidth, topEdge + gap + top.measuredHeight)
        val infoLeft = if (wide) right - gap - info.measuredWidth else left + gap
        val infoTop = if (wide) topEdge + gap else bottom - gap - info.measuredHeight
        info.layout(infoLeft, infoTop, infoLeft + info.measuredWidth, infoTop + info.measuredHeight)
        val controlsBottom = if (wide) bottom - gap else infoTop - gap
        controls.layout(left + gap, controlsBottom - controls.measuredHeight, left + gap + controls.measuredWidth, controlsBottom)
        // Recenter targets the open map area, as in the full app.
        val openRight = if (wide) info.left - gap else right
        map.setMapFocus((left + openRight) / 2f - left, (top.bottom + controls.top) / 2f - topEdge)
        map.setLabelAvoidance(listOf(top, info, controls).map {
            ReferenceScreenRect((it.left - left).toDouble(), (it.top - topEdge).toDouble(),
                (it.right - left).toDouble(), (it.bottom - topEdge).toDouble()).padded(dp(8f).toDouble())
        })
    }
}
