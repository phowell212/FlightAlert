package com.flightalert.fundamentals

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.flightalert.ThemeTreatment
import com.flightalert.FlightAlertSettings
import com.flightalert.map.MapMeasurementFormatter
import com.flightalert.map.UnitSystem
import com.flightalert.map.reference.CatalogControlStatus
import com.flightalert.map.reference.FilterId
import com.flightalert.map.reference.FilterKind
import com.flightalert.map.reference.ReferencePresentationPolicy
import com.flightalert.traffic.AircraftFilterState
import com.flightalert.traffic.AircraftTypeFilter
import com.flightalert.traffic.AltitudeFilter
import com.flightalert.traffic.DistanceFilter
import com.flightalert.traffic.FlightStatusFilter
import com.flightalert.traffic.ReportAgeFilter
import com.flightalert.traffic.sanitize_aircraft_filter_search
import java.util.Locale
import kotlin.math.roundToInt

/** Native controls remain outside the continuously redrawn map surface. Changes apply immediately. */
internal fun showFundamentalsMapControls(context: Context, map: FundamentalsMapView,
                                        aircraftTab: Boolean = false,
                                        onLayers: (Boolean, Boolean) -> Unit) {
    val theme = FlightAlertSettings.read_visual_theme(context)
    val themed = ContextThemeWrapper(context, if (theme.style.treatment == ThemeTreatment.DAYLIGHT_CARD)
        android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert)
    val ui = FundamentalsUiStyle(context, theme)
    val colors = theme.colors
    fun dp(value: Int) = (value * themed.resources.displayMetrics.density).roundToInt()
    val content = LinearLayout(themed).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), dp(16))
        isFocusableInTouchMode = true
    }
    val pages = List(3) { LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL } }
    pages.forEach { content.addView(it) }
    var body = pages[0]
    fun text(value: String, size: Float = 14f, bold: Boolean = false) = TextView(themed).apply {
        text = value
        textSize = size
        includeFontPadding = false
        setTextColor(if (bold) colors.accent_green else colors.text)
        typeface = Typeface.create(theme.style.font_family, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setPadding(0, dp(if (bold) 18 else 6), 0, dp(4))
        body.addView(this)
    }
    fun check(title: String, value: Boolean, changed: (Boolean) -> Unit): CheckBox = CheckBox(themed).apply {
        text = title
        textSize = 12f
        typeface = Typeface.create(theme.style.font_family, Typeface.NORMAL)
        setTextColor(colors.text)
        buttonTintList = ColorStateList.valueOf(colors.accent_green)
        minHeight = dp(44)
        isChecked = value
        body.addView(this)
        setOnCheckedChangeListener { _, checked -> changed(checked) }
    }
    fun <T> selector(title: String, values: List<T>, initial: T,
                     label: (T) -> String, changed: (T) -> Unit): Spinner {
        val heading = text(title)
        return Spinner(themed).apply {
            id = View.generateViewId()
            heading.labelFor = id
            contentDescription = title
            minimumHeight = dp(44)
            adapter = ArrayAdapter(themed, android.R.layout.simple_spinner_item, values.map(label)).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(values.indexOf(initial).coerceAtLeast(0))
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
            var current = initial
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val next = values[position]
                    if (next != current) {
                        current = next
                        changed(next)
                    }
                }
            }
        }
    }
    fun slider(heading: TextView, maximum: Int, initial: Int, changed: (Int) -> Unit) = SeekBar(themed).apply {
        id = View.generateViewId()
        heading.labelFor = id
        max = maximum
        progress = initial.coerceIn(0, maximum)
        progressTintList = ColorStateList.valueOf(colors.accent_green)
        thumbTintList = ColorStateList.valueOf(colors.accent_green)
        minimumHeight = dp(44)
        body.addView(this, LinearLayout.LayoutParams(-1, -2))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) changed(progress)
            }
        })
    }

    text("Map", 16f, true)
    var refreshRangeLabel: () -> Unit = {}
    selector("Units", UnitSystem.entries, map.units(), {
        "${it.name.lowercase(Locale.US).replaceFirstChar { character -> character.titlecase(Locale.US) }} · " +
            "${it.distance_label}, ${it.altitude_label}, ${it.speed_label}"
    }) { units -> map.setUnits(units); refreshRangeLabel() }
    check("Follow my location", map.following(), map::setFollowing)
    text("Following recenters the map when a location fix arrives. Dragging the map stops following.", 12f)

    text("Aviation layers", 16f, true)
    val aviation = map.aviationLayers()
    fun updateAviation(restricted: Boolean? = null, atc: Boolean? = null,
                       tracks: Boolean? = null, airports: Boolean? = null) {
        val current = map.aviationLayers()
        map.setAviationLayers(restricted ?: current.restricted_airspaces_enabled,
            atc ?: current.atc_boundaries_enabled, tracks ?: current.oceanic_tracks_enabled,
            airports ?: current.airport_labels_enabled)
    }
    check("Restricted airspaces", aviation.restricted_airspaces_enabled) { updateAviation(restricted = it) }
    check("ATC boundaries", aviation.atc_boundaries_enabled) { updateAviation(atc = it) }
    check("Oceanic tracks", aviation.oceanic_tracks_enabled) { updateAviation(tracks = it) }
    check("Airport labels", aviation.airport_labels_enabled) { updateAviation(airports = it) }
    text("Layer data and airport labels appear at their supported map zoom levels.", 12f)

    body = pages[1]
    text("Aircraft filters", 16f, true)
    val initial = map.aircraftFilters()
    val searchHeading = text("Search callsign, registration, ICAO or type")
    val search = EditText(themed).apply {
        id = View.generateViewId()
        searchHeading.labelFor = id
        contentDescription = searchHeading.text
        hint = "Aircraft search"
        setTextColor(colors.text)
        setHintTextColor(colors.muted)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(18))
        setText(initial.search_query)
        body.addView(this, LinearLayout.LayoutParams(-1, -2))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                val sanitized = sanitize_aircraft_filter_search(editable.toString())
                if (editable.toString() != sanitized) {
                    setText(sanitized)
                    setSelection(sanitized.length)
                }
                map.setAircraftFilters(map.aircraftFilters().copy(search_query = sanitized))
            }
        })
    }
    val aircraftType = selector("Aircraft type", AircraftTypeFilter.entries, initial.aircraft_type,
        { it.short_label }) { map.setAircraftFilters(map.aircraftFilters().copy(aircraft_type = it)) }
    val altitude = selector("Altitude (thresholds in feet)", AltitudeFilter.entries, initial.altitude, {
        when (it) {
            AltitudeFilter.ANY -> "Any altitude"
            AltitudeFilter.BELOW_1000 -> "Below 1,000 ft"
            AltitudeFilter.FROM_1000_TO_5000 -> "1,000 to below 5,000 ft"
            AltitudeFilter.FROM_5000_TO_18000 -> "5,000 to below 18,000 ft"
            AltitudeFilter.ABOVE_18000 -> "18,000 ft and above"
            AltitudeFilter.UNKNOWN -> "Altitude unavailable"
        }
    }) { map.setAircraftFilters(map.aircraftFilters().copy(altitude = it)) }
    val distance = selector("Distance from my location (thresholds in miles)", DistanceFilter.entries,
        initial.distance, {
            when (it) {
                DistanceFilter.ANY -> "Any distance"
                DistanceFilter.WITHIN_5 -> "Within 5 mi"
                DistanceFilter.WITHIN_10 -> "Within 10 mi"
                DistanceFilter.WITHIN_25 -> "Within 25 mi"
                DistanceFilter.BEYOND_25 -> "More than 25 mi"
            }
        }) { map.setAircraftFilters(map.aircraftFilters().copy(distance = it)) }
    val flightStatus = selector("Flight status", FlightStatusFilter.entries, initial.flight_status,
        { it.short_label }) { map.setAircraftFilters(map.aircraftFilters().copy(flight_status = it)) }
    val reportAge = selector("Report age", ReportAgeFilter.entries, initial.report_age,
        { it.short_label }) { map.setAircraftFilters(map.aircraftFilters().copy(report_age = it)) }
    val alertVolume = check("Alert volume only", initial.alert_volume_only) {
        map.setAircraftFilters(map.aircraftFilters().copy(alert_volume_only = it))
    }
    text("Requires fresh location and aircraft altitude. Unknown membership is excluded; this filter does not enable notifications.", 12f)
    body.addView(Button(themed).apply {
        ui.button(this)
        text = "Reset aircraft filters"
        setOnClickListener {
            map.setAircraftFilters(AircraftFilterState("", AircraftTypeFilter.ALL, AltitudeFilter.ANY,
                DistanceFilter.ANY, FlightStatusFilter.AIRBORNE, ReportAgeFilter.ANY, false))
            search.setText("")
            aircraftType.setSelection(AircraftTypeFilter.ALL.ordinal)
            altitude.setSelection(AltitudeFilter.ANY.ordinal)
            distance.setSelection(DistanceFilter.ANY.ordinal)
            flightStatus.setSelection(FlightStatusFilter.AIRBORNE.ordinal)
            reportAge.setSelection(ReportAgeFilter.ANY.ordinal)
            alertVolume.isChecked = false
        }
    })

    body = pages[2]
    text("Dictionary categories", 16f, true)
    val catalog = map.referenceClassCatalog()
    val references = map.referenceFilters()
    val borders = check("Dictionary borders", references.outlines_master_enabled) { enabled ->
        onLayers(enabled, map.referenceFilters().labels_master_enabled)
    }
    check("Dictionary labels", references.labels_master_enabled) { enabled ->
        onLayers(borders.isChecked, enabled)
    }
    text(when {
        catalog == null -> "Dictionary catalog loading or unavailable. Settings apply when dictionary data is available."
        catalog.status == CatalogControlStatus.AVAILABLE -> "Verified installed dictionary catalog. Visibility also depends on map zoom."
        else -> "Dictionary catalog unavailable: ${catalog.reason}"
    }, 12f)
    text("Category settings apply beneath these dictionary master switches.", 12f)
    for (kind in FilterKind.entries) {
        text(if (kind == FilterKind.LABEL) "Labels" else "Outlines", 14f, true)
        FilterId.entries.filter { ReferencePresentationPolicy.filter_spec(it).kind == kind }.forEach { id ->
            check(ReferencePresentationPolicy.filter_spec(id).title, references.stored_enabled(id)) {
                map.setReferenceFilter(id, it)
            }
        }
    }
    val labelHeading = text("Dictionary label size: ${(map.labelScale() * 100).roundToInt()}%")
    slider(labelHeading, 15, ((map.labelScale() - 1f) / 0.05f).roundToInt()) { value ->
        map.setLabelScale(1f + value * 0.05f)
        labelHeading.text = "Dictionary label size: ${(map.labelScale() * 100).roundToInt()}%"
    }

    body = pages[0]
    text("Priority range preview", 16f, true)
    check("Show range preview", map.rangePreview(), map::setRangePreview).isEnabled = !map.alerts.settings().priorityEnabled
    text("This circle previews the priority queue radius around my location. It does not enable alerts. " +
        "When the queue is on, Show alert ring in Settings controls the circle. " +
        "Hazard alerts use the separate horizontal and vertical limits in Settings.", 12f)
    val formatter = MapMeasurementFormatter { map.units() }
    val radiusHeading = text("")
    refreshRangeLabel = {
        radiusHeading.text = "Priority queue radius: ${formatter.format_distance(map.rangeFeet() / 3.28084)}"
    }
    refreshRangeLabel()
    slider(radiusHeading, 1055, ((map.rangeFeet() - 500f) / 500f).roundToInt()) { value ->
        map.setRangeFeet(500f + value * 500f)
        refreshRangeLabel()
    }

    val scroll = ScrollView(themed).apply { addView(content) }
    val tabs = RadioGroup(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), dp(4), dp(20), dp(4))
    }
    listOf("Map", "Aircraft", "Dictionary").forEachIndexed { index, title ->
        tabs.addView(RadioButton(themed).apply {
            id = View.generateViewId()
            ui.button(this); buttonDrawable = null
            text = title
            isChecked = index == if (aircraftTab) 1 else 0
            setOnClickListener {
                pages.forEachIndexed { pageIndex, page -> page.visibility = if (pageIndex == index) View.VISIBLE else View.GONE }
                scroll.scrollTo(0, 0)
                content.requestFocus()
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (index > 0) leftMargin = dp(10) })
        pages[index].visibility = if (index == if (aircraftTab) 1 else 0) View.VISIBLE else View.GONE
    }
    val root = FundamentalsDialogLayout(themed).apply {
        orientation = LinearLayout.VERTICAL
        addView(tabs)
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    val dialog = AlertDialog.Builder(themed).setTitle("Map controls").setView(root)
        .setPositiveButton("Done", null).create()
    root.onResize = { if (dialog.isShowing) ui.dialog(dialog, fixedHeight = true) }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    dialog.show()
    ui.dialog(dialog, fixedHeight = true)
    content.requestFocus()
}
