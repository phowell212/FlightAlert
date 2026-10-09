package com.flightalert.fundamentals

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.flightalert.VisualTheme
import com.flightalert.FlightAlertSettings
import com.flightalert.details.*
import com.flightalert.map.aviation_layers.AviationSelectionPresentation
import com.flightalert.ui.TrafficPanelState
import com.flightalert.ui.TrafficPanelAircraftState
import com.flightalert.ui.TrafficPanelEmptyState

/** Native scrolling content keeps aircraft lookup work outside the map drawing pass. */
internal class FundamentalsDetailsView(context: Context, private val map: FundamentalsMapView) : LinearLayout(context) {
    private val theme = FlightAlertSettings.read_visual_theme(context)
    private val ui = FundamentalsUiStyle(context, theme)
    private val colors = theme.colors
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size; setTextColor(colors.text)
        includeFontPadding = false
        typeface = Typeface.create(theme.style.font_family, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setPadding(0, dp(4), 0, dp(4))
    }
    private val pageTitle = label(18f, true)
    private val status = label(10f)
    private val photo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val photoNote = label(10f)
    private val evidence = Button(context).apply { ui.button(this); text = "Photo source"; contentDescription = "Inspect photo evidence" }
    private val openImage = Button(context).apply { ui.button(this); text = "Open source image"; contentDescription = "Open photo image" }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val gallery = LinearLayout(context).apply { orientation = VERTICAL }
    private val valueViews = mutableListOf<TextView>()
    private var rowKeys: List<Pair<String, Boolean>> = emptyList()
    private var compact = false
    private var rowsCompact = false
    private var rowsGrid = false
    private var lastRows: List<AircraftDetailsRow> = emptyList()
    fun setCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        if (rowsGrid) showRows(lastRows, true)
    }
    private var photoBitmap: Bitmap? = null
    private var photoEvidence: PhotoEvidence? = null
    private var galleryItems: List<AircraftPhotoGalleryItem> = emptyList()
    private var aircraftKey: String? = null
    private var usageStats: UsageStats? = null
    private val usageGraph = object : View(context) {
        private val renderer = AircraftDetailsPanelRenderer(Paint(Paint.ANTI_ALIAS_FLAG),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }, Paint(Paint.ANTI_ALIAS_FLAG), object : AircraftDetailsPanelChrome {
                override fun dp(value: Float) = value * resources.displayMetrics.density
                override fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
                override fun control_radius() = dp(7f)
                override fun ellipsize(value: String, max_width: Float) = value
                override fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int) = Unit
                override fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean) = Unit
            })
        override fun onDraw(canvas: Canvas) {
            usageStats?.takeIf { it.buckets.isNotEmpty() }?.let {
                renderer.draw_usage_graph(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()), it,
                    AircraftDetailsPanelStyle(theme))
            }
        }
    }

    init {
        orientation = VERTICAL
        addView(pageTitle); addView(status); addView(photo, LayoutParams(-1, dp(160)))
        addView(photoNote); addView(evidence); addView(openImage); addView(gallery); addView(rows)
        addView(usageGraph, LayoutParams(-1, dp(190)))
        evidence.setOnClickListener { map.openEvidence(photoEvidence) }
        photo.setOnClickListener { map.openPhotos() }
        photo.setOnLongClickListener { map.openEvidence(photoEvidence); photoEvidence != null }
    }

    fun show(state: FundamentalsSelectionReadout) {
        if (aircraftKey != state.aircraftKey) {
            aircraftKey = state.aircraftKey
            photoBitmap = null; photoEvidence = null; photo.setImageDrawable(null)
            galleryItems = emptyList(); gallery.removeAllViews()
        }
        visibility = if (state.page.is_open) View.VISIBLE else View.GONE
        usageGraph.visibility = GONE
        if (!state.page.is_open) return
        var detailRows: List<AircraftDetailsRow> = emptyList()
        gallery.visibility = GONE
        photo.visibility = GONE; photoNote.visibility = GONE; evidence.visibility = GONE
        openImage.visibility = GONE
        pageTitle.visibility = VISIBLE; status.visibility = VISIBLE
        status.text = state.status
        when (val content = state.content) {
            is AircraftDetailsMainState -> {
                pageTitle.text = "Aircraft details"; detailRows = content.rows
                photoNote.visibility = VISIBLE; photoNote.text = state.photo.status
                val bitmap = state.photo.bitmap
                if (bitmap != null) {
                    photo.visibility = VISIBLE
                    if (bitmap !== photoBitmap) {
                        val next = BitmapDrawable(resources, bitmap)
                        val previous = photoBitmap?.let { BitmapDrawable(resources, it) }
                        if (previous == null) photo.setImageDrawable(next) else {
                            val transition = TransitionDrawable(arrayOf(previous, next)).apply { isCrossFadeEnabled = true }
                            photo.setImageDrawable(transition); transition.startTransition(180)
                        }
                        photoBitmap = bitmap
                    }
                }
                photoEvidence = state.photoEvidence
                evidence.visibility = if (photoEvidence != null) VISIBLE else GONE
            }
            is AircraftUsagePanelState -> {
                pageTitle.text = "Flight usage"; status.text = content.status
                detailRows = content.stat_rows + listOfNotNull(content.unavailable_message?.let { AircraftDetailsRow("Trace", it) })
                if (usageStats != content.stats) { usageStats = content.stats; usageGraph.invalidate() }
                usageGraph.visibility = if (content.stats?.buckets?.isNotEmpty() == true) VISIBLE else GONE
                usageGraph.contentDescription = content.stats?.buckets?.joinToString("; ") { "${it.label}: ${it.flights} flights, ${"%.1f".format(it.hours)} trace hours" }
            }
            is AircraftImpactPanelState -> {
                pageTitle.text = "Environmental impact"; status.text = content.status
                detailRows = listOf(AircraftDetailsRow(content.score_label, content.score_text),
                    AircraftDetailsRow("CO2 estimate", content.co2_text)) + content.rows
            }
            is AircraftPhotoEvidencePanelState -> {
                pageTitle.text = "Photo evidence"
                val source = content.evidence
                status.text = source?.source_name ?: "Unavailable"
                detailRows = source?.let { listOf(AircraftDetailsRow("Source page", it.page_url),
                    AircraftDetailsRow("Search query", it.search_query), AircraftDetailsRow("Source evidence", it.quote),
                    AircraftDetailsRow("Matched terms", it.matched_terms.joinToString(", "))) }.orEmpty()
                evidence.text = "Open source page"
                evidence.visibility = if (source != null) VISIBLE else GONE
                evidence.setOnClickListener {
                    source?.page_url?.let(::openUrl)
                }
                openImage.visibility = if (source != null) VISIBLE else GONE
                openImage.setOnClickListener { source?.image_url?.let(::openUrl) }
            }
            is AircraftPhotoGalleryPanelState -> {
                pageTitle.text = "Aircraft photos"; status.text = content.status; gallery.visibility = VISIBLE
                if (galleryItems != content.items) {
                    galleryItems = content.items; gallery.removeAllViews()
                    for (item in content.items) {
                        gallery.addView(label(12f, true).apply { text = item.title })
                        gallery.addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(item.bitmap) }, LayoutParams(-1, dp(160)))
                        gallery.addView(label(10f).apply { text = "${item.caption}\n${item.quality.name.lowercase()} · ${item.view_type.name.lowercase()}" })
                        if (item.evidence != null) gallery.addView(Button(context).apply { ui.button(this); text = "Photo source"; setOnClickListener { map.openEvidence(item.evidence) } })
                    }
                }
            }
            null -> { pageTitle.text = "Aircraft unavailable" }
        }
        if (state.content !is AircraftPhotoEvidencePanelState) {
            evidence.text = "Photo source"; evidence.setOnClickListener { map.openEvidence(photoEvidence) }
        }
        showRows(detailRows)
        if (state.content is AircraftImpactPanelState) valueViews.firstOrNull()?.setTextColor(state.content.score_color)
    }

    fun showAviation(state: AviationSelectionPresentation) {
        visibility = VISIBLE
        photo.visibility = GONE; photoNote.visibility = GONE; evidence.visibility = GONE
        openImage.visibility = GONE
        gallery.visibility = GONE; usageGraph.visibility = GONE
        pageTitle.visibility = GONE
        status.visibility = VISIBLE
        status.text = state.provenance.banner
        showRows(state.rows.map { AircraftDetailsRow(it.label, it.value) } + listOfNotNull(
            AircraftDetailsRow("Source", state.provenance.source),
            AircraftDetailsRow("Observed UTC", state.provenance.observed_utc),
            state.provenance.latest_refresh?.let { AircraftDetailsRow("Latest refresh", it) }))
    }

    fun showTraffic(state: TrafficPanelState, wide: Boolean) {
        visibility = VISIBLE
        photo.visibility = GONE; photoNote.visibility = GONE; evidence.visibility = GONE
        openImage.visibility = GONE; gallery.visibility = GONE; usageGraph.visibility = GONE
        pageTitle.visibility = GONE
        status.visibility = GONE
        when (val content = state.content) {
            is TrafficPanelAircraftState -> {
                showRows((if (wide) content.wide_rows else content.compact_rows).map { AircraftDetailsRow(it.label, it.value) }, grid = true)
            }
            is TrafficPanelEmptyState -> {
                status.visibility = VISIBLE
                status.text = content.message
                showRows(content.data_time_label?.let { listOf(AircraftDetailsRow("Data time", it)) }.orEmpty())
            }
        }
    }

    private fun showRows(detailRows: List<AircraftDetailsRow>, grid: Boolean = false) {
        lastRows = detailRows
        val keys = detailRows.map { it.label to it.section }
        if (keys != rowKeys || grid != rowsGrid || (grid && compact != rowsCompact)) {
            rowKeys = keys; rowsGrid = grid; rowsCompact = compact
            rows.removeAllViews(); valueViews.clear()
            fun cell(row: AircraftDetailsRow, parent: LinearLayout) {
                parent.addView(label(if (row.section) 14f else if (grid) 9f else 10f, row.section).apply {
                    text = if (grid) row.label.uppercase(java.util.Locale.US) else row.label
                    setTextColor(if (row.section) colors.accent_green else colors.muted)
                    setPadding(0, if (row.section) dp(14) else dp(3), 0, dp(2))
                })
                parent.addView(label(if (grid) if (compact) 12f else 14f else 12f, grid).also {
                    it.setPadding(0, 0, 0, dp(if (compact && grid) 3 else 8)); valueViews.add(it)
                })
            }
            if (grid) {
                detailRows.chunked(2).forEach { pair ->
                    val line = LinearLayout(context)
                    pair.forEach { row ->
                        val column = LinearLayout(context).apply {
                            orientation = VERTICAL; minimumHeight = dp(if (compact) 32 else 46)
                            setPadding(0, 0, dp(8), 0)
                        }
                        cell(row, column)
                        line.addView(column, LayoutParams(0, -2, 1f))
                    }
                    if (pair.size == 1) line.addView(View(context), LayoutParams(0, 1, 1f))
                    rows.addView(line, LayoutParams(-1, -2))
                    rows.addView(View(context).apply {
                        setBackgroundColor(ui.alpha(colors.panel_stroke, theme.style.divider_alpha))
                    }, LayoutParams(-1, dp(1)))
                }
            } else detailRows.forEach { cell(it, rows) }
        }
        detailRows.forEachIndexed { index, row ->
            if (valueViews[index].text.toString() != row.value) valueViews[index].text = row.value
            valueViews[index].setTextColor(colors.text)
        }
    }

    private fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme == "https" || uri.scheme == "http") runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}
