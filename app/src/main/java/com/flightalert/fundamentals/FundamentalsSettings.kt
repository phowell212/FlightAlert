package com.flightalert.fundamentals

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.flightalert.VisualTheme
import com.flightalert.FlightAlertSettings
import com.flightalert.aircraft.AlertAircraft
import com.flightalert.alerts.MonitoringNotificationHider
import com.flightalert.alerts.MonitoringNotificationHiderStatus
import com.flightalert.details.AircraftImpactEstimator
import com.flightalert.map.MapMeasurementFormatter
import com.flightalert.map.UnitSystem
import com.flightalert.ui.FlightMapPanelRenderer
import com.flightalert.ui.PriorityRangeValue
import kotlin.math.roundToInt

private enum class FundamentalsSettingsPage { ROOT, DISPLAY, ALERTS, RANGE, INFO }

/** Native settings do not add work to map drawing. Changes use the existing persisted owners. */
internal fun showFundamentalsSettings(
    context: Context,
    theme: VisualTheme,
    units: UnitSystem,
    alerts: FundamentalsAlerts,
    onTheme: (VisualTheme) -> Unit,
    onUnits: (UnitSystem) -> Unit,
    onMap: () -> Unit,
    onNotifications: () -> Unit,
    onNotificationAccess: () -> Unit,
    onDictionary: () -> Unit,
    onSelectAircraft: (String) -> Unit,
    appOpenedElapsedMs: Long = SystemClock.elapsedRealtime(),
): AlertDialog {
    val themed = ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert)
    var currentTheme = theme
    var currentUnits = units
    var page = FundamentalsSettingsPage.ROOT
    var refreshingControls = false
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    val root = FundamentalsDialogLayout(themed).apply { orientation = LinearLayout.VERTICAL }
    val dialog = AlertDialog.Builder(themed).setView(root).create()
    val handler = Handler(Looper.getMainLooper())
    val formatter = MapMeasurementFormatter { currentUnits }
    var refresh: () -> Unit = {}
    lateinit var render: () -> Unit
    fun back() {
        if (page == FundamentalsSettingsPage.ROOT) dialog.dismiss()
        else {
            page = if (page == FundamentalsSettingsPage.RANGE) FundamentalsSettingsPage.ALERTS
                else FundamentalsSettingsPage.ROOT
            render()
        }
    }

    render = {
        val colors = currentTheme.colors
        val style = currentTheme.style
        root.removeAllViews()
        root.setPadding(dp(18), dp(8), dp(18), dp(12))
        val ui = FundamentalsUiStyle(context, currentTheme)
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        ui.dialog(dialog, fixedHeight = true)
        fun label(value: String, heading: Boolean = false, parent: LinearLayout): TextView = TextView(themed).apply {
            text = value
            textSize = if (heading) 14f * style.heading_scale else 12f
            includeFontPadding = false
            setTextColor(if (heading) colors.accent_green else colors.text)
            typeface = Typeface.create(style.font_family, if (heading) Typeface.BOLD else Typeface.NORMAL)
            setPadding(0, dp(if (heading) 14 else 6), 0, dp(6))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        fun button(value: String, parent: LinearLayout, action: () -> Unit): Button = Button(themed).apply {
            text = value
            isAllCaps = false
            ui.button(this)
            minHeight = dp(44)
            minimumWidth = 0
            setPadding(dp(12), dp(8), dp(12), dp(8))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            setOnClickListener { action() }
        }
        val header = LinearLayout(themed).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        val title = TextView(themed).apply {
            text = when (page) {
                FundamentalsSettingsPage.ROOT -> "Settings"
                FundamentalsSettingsPage.DISPLAY -> "Display"
                FundamentalsSettingsPage.ALERTS -> "Alerts"
                FundamentalsSettingsPage.RANGE -> "Notification Range"
                FundamentalsSettingsPage.INFO -> "Impact Methodology"
            }
            textSize = 20f * style.heading_scale
            setTextColor(colors.text)
            typeface = Typeface.create(style.font_family, Typeface.BOLD)
            setPadding(0, dp(8), dp(8), dp(8))
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        button(if (page == FundamentalsSettingsPage.ROOT) "Close" else "Back", header, ::back)
            .layoutParams = LinearLayout.LayoutParams(dp(94), dp(44))
        val body = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(themed).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun open(next: FundamentalsSettingsPage) { page = next; render() }
        fun check(value: String, initial: Boolean, changed: (Boolean) -> Unit): CheckBox = CheckBox(themed).apply {
            text = value
            textSize = 12f
            setTextColor(colors.text)
            typeface = Typeface.create(style.font_family, Typeface.NORMAL)
            buttonTintList = ColorStateList.valueOf(colors.accent_green)
            minHeight = dp(44)
            isChecked = initial
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnCheckedChangeListener { _, enabled ->
                if (!refreshingControls) { changed(enabled); refresh() }
            }
        }
        refresh = {}
        when (page) {
            FundamentalsSettingsPage.ROOT -> {
                val compact = context.resources.configuration.screenWidthDp > context.resources.configuration.screenHeightDp &&
                    context.resources.configuration.screenHeightDp < 500
                val items = listOf(
                    Triple("Display", "Display: units and theme", { open(FundamentalsSettingsPage.DISPLAY) }),
                    Triple("Map", "Map: labels, layers and aircraft filters", onMap),
                    Triple("Alerts", "Alerts: hazards and notifications", { open(FundamentalsSettingsPage.ALERTS) }),
                    Triple("Info", "Info: impact methodology", { open(FundamentalsSettingsPage.INFO) }))
                items.chunked(if (compact) 2 else 1).forEach { group ->
                    val row = LinearLayout(themed)
                    body.addView(row, LinearLayout.LayoutParams(-1, -2))
                    group.forEachIndexed { index, (short, full, action) ->
                        button(if (compact) short else full, row, action).apply {
                            contentDescription = full
                            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                                topMargin = dp(8); if (index > 0) leftMargin = dp(10)
                            }
                        }
                    }
                }
                button("Dictionary downloads", body, onDictionary)
                val status = label("", parent = body)
                refresh = { status.text = alerts.evaluation().status }
            }
            FundamentalsSettingsPage.DISPLAY -> {
                label("Units", true, body)
                val unitChoices = RadioGroup(themed).apply { orientation = LinearLayout.HORIZONTAL }
                body.addView(unitChoices)
                for (choice in UnitSystem.entries) {
                    unitChoices.addView(RadioButton(themed).apply {
                        id = View.generateViewId()
                        text = if (choice == UnitSystem.IMPERIAL) "Miles / feet · mph" else "Kilometers / meters · km/h"
                        ui.button(this); buttonDrawable = null
                        textSize = 12f
                        buttonTintList = ColorStateList.valueOf(colors.accent_green)
                        minHeight = dp(44)
                        isChecked = choice == currentUnits
                        setOnClickListener {
                            if (choice != currentUnits) { currentUnits = choice; onUnits(choice) }
                        }
                    }, RadioGroup.LayoutParams(0, -2, 1f).apply {
                        if (choice != UnitSystem.entries.first()) leftMargin = dp(10)
                    })
                }
                label("Theme", true, body)
                VisualTheme.entries.chunked(2).forEach { choices ->
                    val row = LinearLayout(themed)
                    body.addView(row, LinearLayout.LayoutParams(-1, -2))
                    choices.forEachIndexed { index, choice ->
                        row.addView(RadioButton(themed).apply {
                            ui.button(this); buttonDrawable = null
                            text = choice.display_name
                            isChecked = choice == currentTheme
                            setOnClickListener {
                                if (choice != currentTheme) { currentTheme = choice; onTheme(choice); render() }
                            }
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                            topMargin = dp(6); if (index > 0) leftMargin = dp(10)
                        })
                    }
                }
            }
            FundamentalsSettingsPage.ALERTS -> {
                val hazards = check("Hazard alerts", alerts.settings().alertsEnabled, alerts::setAlertsEnabled)
                label("Hazard decisions need both horizontal distance and vertical separation from fresh aircraft and my location. Missing or stale data is unknown.", parent = body)
                button("Notification range and aircraft queue", body) { open(FundamentalsSettingsPage.RANGE) }
                val status = label("", parent = body)
                val notificationStatus = label("", parent = body)
                val notifications = button("Android notification settings", body, onNotifications)
                val watcher = label("", parent = body)
                val access = button("Open notification access", body, onNotificationAccess)
                label("The notification watcher can hide the silent monitoring notification. Android controls notification access.", parent = body)
                refresh = {
                    refreshingControls = true
                    hazards.isChecked = alerts.settings().alertsEnabled
                    refreshingControls = false
                    status.text = alerts.evaluation().status
                    val notificationsAllowed = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .areNotificationsEnabled()
                    notificationStatus.text = if (notificationsAllowed) "Android notifications: enabled" else "Android notifications: disabled"
                    notifications.text = if (notificationsAllowed) "Android notification settings" else "Enable Android notifications"
                    val watcherStatus = MonitoringNotificationHider.status(context, appOpenedElapsedMs)
                    watcher.text = "Notification watcher: ${watcherStatus.name}"
                    watcher.setTextColor(when (watcherStatus) {
                        MonitoringNotificationHiderStatus.UP -> colors.accent_green
                        MonitoringNotificationHiderStatus.BOOTING -> colors.accent_orange
                        MonitoringNotificationHiderStatus.DOWN -> colors.danger
                    })
                    access.text = if (MonitoringNotificationHider.is_enabled(context)) "Notification access enabled · manage" else "Open notification access"
                }
            }
            FundamentalsSettingsPage.RANGE -> {
                val queueEnabled = check("Notification queue", alerts.settings().priorityEnabled, alerts::setPriorityEnabled)
                val ring = check("Show alert ring", alerts.settings().ringVisible, alerts::setRingVisible)
                label("The queue uses a horizontal radius. A queue entry does not establish a hazard; hazard alerts also require vertical separation.", parent = body)
                fun adjuster(title: String, value: PriorityRangeValue): TextView {
                    label(title, true, body)
                    val row = LinearLayout(themed).apply { gravity = Gravity.CENTER_VERTICAL }
                    body.addView(row, LinearLayout.LayoutParams(-1, -2))
                    fun adjustment(direction: Float): Button {
                        fun adjust(longPress: Boolean) { alerts.adjustRange(value, direction, longPress, currentUnits); refresh() }
                        return button(if (direction < 0) "−" else "+", row) { adjust(false) }.apply {
                            contentDescription = "${if (direction < 0) "Decrease" else "Increase"} $title"
                            layoutParams = LinearLayout.LayoutParams(dp(56), -2)
                            setOnLongClickListener { adjust(true); true }
                        }
                    }
                    adjustment(-1f)
                    val amount = TextView(themed).apply {
                        textSize = 18f
                        setTextColor(colors.text)
                        typeface = Typeface.create(style.font_family, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        minHeight = dp(44)
                        setPadding(dp(8), 0, dp(8), 0)
                    }
                    row.addView(amount, LinearLayout.LayoutParams(0, -2, 1f))
                    adjustment(1f)
                    return amount
                }
                val horizontal = adjuster("Hazard horizontal radius", PriorityRangeValue.HORIZONTAL)
                val vertical = adjuster("Hazard vertical separation", PriorityRangeValue.VERTICAL)
                label("Tap ± for one step. Hold for a larger step in the selected units.", parent = body)
                val priorityRadius = label("", parent = body)
                button("Map controls: notification queue radius", body, onMap)
                val status = label("", parent = body)
                label("Aircraft in queue", true, body)
                val empty = label("", parent = body)
                val queue = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
                body.addView(queue)
                var keys = emptyList<String>()
                val rows = mutableListOf<Button>()
                refresh = {
                    val settings = alerts.settings()
                    val evaluation = alerts.evaluation()
                    refreshingControls = true
                    queueEnabled.isChecked = settings.priorityEnabled
                    ring.isChecked = settings.ringVisible
                    refreshingControls = false
                    horizontal.text = formatter.format_feet_setting(settings.horizontalFeet)
                    vertical.text = formatter.format_feet_setting(settings.verticalFeet)
                    priorityRadius.text = "Notification queue radius: ${formatter.format_feet_setting(settings.priorityFeet)}"
                    status.text = evaluation.status
                    val aircraft = if (settings.priorityEnabled) evaluation.queue else emptyList()
                    empty.text = if (!settings.priorityEnabled) "Queue is off"
                        else if (aircraft.isEmpty()) "No current queue entries. ${evaluation.status}"
                        else "Tap an aircraft to select it on the map."
                    val nextKeys = aircraft.map { it.icao24 }
                    if (nextKeys != keys) {
                        queue.removeAllViews()
                        rows.clear()
                        aircraft.forEach { item ->
                            rows.add(button("", queue) { dialog.dismiss(); onSelectAircraft(item.icao24) })
                        }
                        keys = nextKeys
                    }
                    aircraft.forEachIndexed { index, item ->
                        val text = notificationAircraftText(item, formatter, item.icao24.lowercase(java.util.Locale.US) in evaluation.unknownKeys)
                        if (rows[index].text.toString() != text) rows[index].text = text
                    }
                }
            }
            FundamentalsSettingsPage.INFO -> {
                FlightMapPanelRenderer.impact_methodology_items().forEach { (title, explanation) ->
                    label(title, true, body)
                    label(explanation, parent = body)
                }
                label("Sources", true, body)
                AircraftImpactEstimator.source_labels.zip(AircraftImpactEstimator.source_urls).forEach { (title, url) ->
                    button(title, body) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { Toast.makeText(context, "Browser unavailable", Toast.LENGTH_SHORT).show() }
                    }.contentDescription = "Open $title source in browser"
                    label(url, parent = body).setTextColor(colors.muted)
                }
            }
        }
        refresh()
    }

    val tick = object : Runnable {
        override fun run() {
            if (!dialog.isShowing) return
            if (dialog.window?.decorView?.hasWindowFocus() == true) refresh()
            handler.postDelayed(this, 1000L)
        }
    }
    val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) {
            val savedUnits = FlightAlertSettings.read_unit_system(FlightAlertSettings.prefs(context))
            if (savedUnits != currentUnits) { currentUnits = savedUnits; render() }
            else refresh()
        }
    }
    val invokedBack = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { back() } else null
    dialog.setOnKeyListener { _, code, event ->
        if (code == KeyEvent.KEYCODE_BACK && page != FundamentalsSettingsPage.ROOT) {
            if (event.action == KeyEvent.ACTION_UP) back()
            true
        } else false
    }
    dialog.setOnDismissListener {
        handler.removeCallbacks(tick)
        dialog.window?.decorView?.viewTreeObserver?.let { if (it.isAlive) it.removeOnWindowFocusChangeListener(focus) }
        if (Build.VERSION.SDK_INT >= 33 && invokedBack != null) dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(invokedBack)
    }
    root.onResize = { if (dialog.isShowing) render() }
    render()
    dialog.show()
    FundamentalsUiStyle(context, currentTheme).dialog(dialog, fixedHeight = true)
    dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(focus)
    if (Build.VERSION.SDK_INT >= 33 && invokedBack != null) dialog.onBackInvokedDispatcher
        .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, invokedBack)
    handler.post(tick)
    return dialog
}

private fun notificationAircraftText(aircraft: AlertAircraft, formatter: MapMeasurementFormatter, unknownHazard: Boolean): String {
    val identity = aircraft.callsign.ifBlank { aircraft.registration?.takeIf { it.isNotBlank() } ?: aircraft.icao24 }
    val distance = aircraft.distance_feet.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { formatter.format_distance(it / 3.28084) } ?: "Unavailable"
    val altitude = formatter.format_altitude(aircraft.altitude_feet.takeIf { it.isFinite() }?.div(3.28084))
    val separation = formatter.format_altitude(aircraft.vertical_separation_feet?.takeIf { it.isFinite() }?.div(3.28084))
    val age = aircraft.contact_age_seconds.takeIf { it.isFinite() && it >= 0.0 }?.roundToInt()?.let { "${it}s" } ?: "Unavailable"
    val hazard = if (aircraft.is_hazard) " · Hazard" else if (unknownHazard) " · Hazard unknown" else ""
    return "$identity · ${aircraft.icao24}$hazard\n$distance away · Altitude $altitude\nVertical separation $separation · Report age $age"
}
