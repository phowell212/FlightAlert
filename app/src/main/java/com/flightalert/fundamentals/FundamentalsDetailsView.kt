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
import com.flightalert.FlightAlertSettings
import com.flightalert.details.*
import com.flightalert.map.aviation_layers.AviationSelectionPresentation

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
    private val status = label(10f)
    private val photo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Open aircraft photo gallery"
    }
    private val photoNote = label(10f)
    private val photos = Button(context).apply {
        ui.button(this); text = "Photos"; contentDescription = "Open aircraft photo gallery"
        setOnClickListener { map.openPhotos() }
    }
    private val evidence = Button(context).apply { ui.button(this); text = "Photo source"; contentDescription = "Inspect photo evidence" }
    private val openImage = Button(context).apply { ui.button(this); text = "Open source image"; contentDescription = "Open photo image" }
    private val content = LinearLayout(context)
    private val photoColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val gallery = LinearLayout(context).apply { orientation = VERTICAL }
    private val valueViews = mutableListOf<TextView>()
    private var rowKeys: List<Pair<String, Boolean>> = emptyList()
    private var compact = false
    private var rowsColumns = false
    private var mainDetails = false
    private var bodyWide: Boolean? = null
    private var lastRows: List<AircraftDetailsRow> = emptyList()
    fun setCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        layoutBody()
        showRows(lastRows)
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
        photoColumn.addView(status); photoColumn.addView(photo, LayoutParams(-1, dp(160)))
        photoColumn.addView(photoNote); photoColumn.addView(photos)
        photoColumn.addView(evidence); photoColumn.addView(openImage)
        content.addView(photoColumn); content.addView(rows)
        addView(content, LayoutParams(-1, -2)); addView(gallery)
        addView(usageGraph, LayoutParams(-1, dp(190)))
        layoutBody()
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
        mainDetails = state.content is AircraftDetailsMainState
        layoutBody()
        gallery.visibility = GONE
        photo.visibility = GONE; photoNote.visibility = GONE; evidence.visibility = GONE
        photos.visibility = GONE; openImage.visibility = GONE
        status.visibility = VISIBLE
        status.text = state.status
        when (val content = state.content) {
            is AircraftDetailsMainState -> {
                detailRows = content.rows
                photoNote.visibility = VISIBLE; photoNote.text = state.photo.status
                photos.visibility = if (state.photosAvailable) VISIBLE else GONE
                photo.isClickable = state.photosAvailable
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
                status.text = content.status
                detailRows = content.stat_rows + listOfNotNull(content.unavailable_message?.let { AircraftDetailsRow("Trace", it) })
                if (usageStats != content.stats) { usageStats = content.stats; usageGraph.invalidate() }
                usageGraph.visibility = if (content.stats?.buckets?.isNotEmpty() == true) VISIBLE else GONE
                usageGraph.contentDescription = content.stats?.buckets?.joinToString("; ") { "${it.label}: ${it.flights} flights, ${"%.1f".format(it.hours)} trace hours" }
            }
            is AircraftImpactPanelState -> {
                status.text = content.status
                detailRows = listOf(AircraftDetailsRow(content.score_label, content.score_text),
                    AircraftDetailsRow("CO2 estimate", content.co2_text)) + content.rows
            }
            is AircraftPhotoEvidencePanelState -> {
                val source = content.evidence
                status.text = source?.source_name ?: "Unavailable"
                detailRows = source?.let { listOf(AircraftDetailsRow("Source page", it.page_url),
                    AircraftDetailsRow("Search query", it.search_query), AircraftDetailsRow("Source evidence", it.quote),
                    AircraftDetailsRow("Matched terms", it.matched_terms.joinToString(", "))) }.orEmpty()
                evidence.text = "Open source page"
                evidence.contentDescription = "Open photo source page"
                evidence.visibility = if (source != null) VISIBLE else GONE
                evidence.setOnClickListener {
                    source?.page_url?.let(::openUrl)
                }
                openImage.visibility = if (source != null) VISIBLE else GONE
                openImage.setOnClickListener { source?.image_url?.let(::openUrl) }
            }
            is AircraftPhotoGalleryPanelState -> {
                status.text = content.status; gallery.visibility = VISIBLE
                if (galleryItems != content.items) {
                    galleryItems = content.items; gallery.removeAllViews()
                    for (item in content.items) {
                        gallery.addView(label(12f, true).apply { text = item.title })
                        gallery.addView(ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(item.bitmap)
                            contentDescription = item.title
                        }, LayoutParams(-1, dp(160)))
                        gallery.addView(label(10f).apply { text = "${item.caption}\n${item.quality.name.lowercase()} · ${item.view_type.name.lowercase()}" })
                        if (item.evidence != null) gallery.addView(Button(context).apply { ui.button(this); text = "Photo source"; setOnClickListener { map.openEvidence(item.evidence) } })
                    }
                }
            }
            null -> status.text = "Aircraft unavailable"
        }
        if (state.content !is AircraftPhotoEvidencePanelState) {
            evidence.text = "Photo source"; evidence.contentDescription = "Inspect photo evidence"
            evidence.setOnClickListener { map.openEvidence(photoEvidence) }
        }
        showRows(detailRows)
        if (state.content is AircraftImpactPanelState) valueViews.firstOrNull()?.setTextColor(state.content.score_color)
    }

    fun showAviation(state: AviationSelectionPresentation) {
        visibility = VISIBLE
        mainDetails = false; layoutBody()
        photo.visibility = GONE; photoNote.visibility = GONE; evidence.visibility = GONE
        photos.visibility = GONE; openImage.visibility = GONE
        gallery.visibility = GONE; usageGraph.visibility = GONE
        status.visibility = VISIBLE
        status.text = state.provenance.banner
        showRows(state.rows.map { AircraftDetailsRow(it.label, it.value) } + listOfNotNull(
            AircraftDetailsRow("Source", state.provenance.source),
            AircraftDetailsRow("Observed UTC", state.provenance.observed_utc),
            state.provenance.latest_refresh?.let { AircraftDetailsRow("Latest refresh", it) }))
    }

    private fun layoutBody() {
        val wide = mainDetails && !compact
        if (bodyWide == wide) return
        bodyWide = wide
        content.orientation = if (wide) HORIZONTAL else VERTICAL
        photoColumn.layoutParams = if (wide) LayoutParams(0, -2, .38f) else LayoutParams(-1, -2)
        rows.layoutParams = (if (wide) LayoutParams(0, -2, .62f) else LayoutParams(-1, -2)).apply {
            leftMargin = if (wide) dp(18) else 0
        }
    }

    private fun showRows(detailRows: List<AircraftDetailsRow>) {
        lastRows = detailRows
        val keys = detailRows.map { it.label to it.section }
        val columns = mainDetails && !compact
        if (keys != rowKeys || columns != rowsColumns) {
            rowKeys = keys; rowsColumns = columns
            rows.removeAllViews(); valueViews.clear()
            rows.orientation = if (columns) HORIZONTAL else VERTICAL
            fun cell(row: AircraftDetailsRow, parent: LinearLayout) {
                parent.addView(label(if (row.section) 14f else 10f, row.section).apply {
                    text = row.label
                    setTextColor(if (row.section) colors.accent_green else colors.muted)
                    setPadding(0, if (row.section) dp(14) else dp(3), 0, dp(2))
                })
                parent.addView(label(12f).also {
                    it.setPadding(0, 0, 0, dp(8)); valueViews.add(it)
                })
            }
            if (columns) {
                val split = if (detailRows.size <= 2) detailRows.size else detailRows.indices
                    .filter { it > 0 && detailRows[it].section }
                    .minByOrNull { kotlin.math.abs(it - detailRows.size / 2) } ?: (detailRows.size + 1) / 2
                listOf(detailRows.take(split), detailRows.drop(split)).forEachIndexed { index, columnRows ->
                    val column = LinearLayout(context).apply { orientation = VERTICAL }
                    columnRows.forEach { cell(it, column) }
                    rows.addView(column, LayoutParams(0, -2, 1f).apply { leftMargin = if (index == 0) 0 else dp(16) })
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
