@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import com.flightalert.VisualTheme
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.details.AircraftDetails
import com.flightalert.details.AirportDetails
import com.flightalert.details.AircraftTelemetryFormatter
import com.flightalert.flight.AircraftRoutePresenter
import com.flightalert.traffic.FilterStats
import java.util.Locale
import kotlin.math.min

class TrafficPanelRenderer(
    private val text_paint: Paint,
    private val stroke_paint: Paint,
    private val chrome: TrafficPanelChrome,
    private val with_alpha: (Int, Int) -> Int
) {
    fun draw_panel(
        canvas: Canvas,
        rect: RectF,
        wide: Boolean,
        style: TrafficPanelStyle,
        state: TrafficPanelState
    ) {
        chrome.draw_panel_surface(
            canvas,
            rect,
            style.visual_theme.colors.panel,
            style.visual_theme.style.info_panel_alpha
        )

        val y = rect.top + if (wide) dp(32) else dp(27)
        val model_label = (state.content as? TrafficPanelAircraftState)
            ?.model_label
            ?.takeIf { it != "Unavailable" }
        val content_width = rect.width() - dp(32)
        val model_width = if (model_label != null) {
            min(content_width * if (wide) 0.52f else 0.48f, if (wide) dp(230) else dp(190))
        } else {
            0f
        }
        val header_gap = if (model_label != null) dp(14) else 0f
        val title_width = content_width - model_width - header_gap
        val title_size = if (wide) sp(14) else sp(13)
        val model_size = if (wide) sp(15) else sp(13)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = title_size
        text_paint.color = state.title_color
        draw_fitted_left_text(
            canvas,
            state.title,
            rect.left + dp(16),
            y,
            title_width,
            title_size,
            sp(9)
        )
        if (model_label != null) {
            text_paint.color = style.visual_theme.colors.text
            draw_fitted_right_text(
                canvas,
                model_label,
                rect.right - dp(16),
                y,
                model_width,
                model_size,
                sp(9)
            )
        }

        when (val content = state.content) {
            is TrafficPanelEmptyState -> draw_empty_panel(
                canvas,
                rect,
                y + if (wide) dp(60) else dp(38),
                style,
                content
            )

            is TrafficPanelAircraftState -> draw_aircraft_panel(
                canvas,
                rect,
                wide,
                y,
                style,
                content
            )
        }
    }

    private fun draw_aircraft_panel(
        canvas: Canvas,
        rect: RectF,
        wide: Boolean,
        start_y: Float,
        style: TrafficPanelStyle,
        content: TrafficPanelAircraftState
    ) {
        var y = start_y + if (wide) dp(44) else dp(32)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        val main_text_size = if (wide) sp(30) else sp(24)
        text_paint.textSize = main_text_size
        text_paint.color = style.visual_theme.colors.text
        val horizontal_padding = dp(16)
        val main_gap = dp(if (wide) 18 else 14)
        val distance_width = (rect.width() * if (wide) 0.40f else 0.38f)
            .coerceAtLeast(dp(if (wide) 86 else 58))
        val callsign_width =
            (rect.width() - horizontal_padding * 2f - main_gap - distance_width).coerceAtLeast(dp(54))
        draw_fitted_left_text(
            canvas = canvas,
            value = content.callsign,
            left = rect.left + horizontal_padding,
            y = y,
            max_width = callsign_width,
            start_size = main_text_size,
            min_size = sp(12)
        )

        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.textSize = main_text_size
        text_paint.color = content.distance_color
        draw_fitted_right_text(
            canvas = canvas,
            value = content.distance_label,
            right = rect.right - horizontal_padding,
            y = y,
            max_width = distance_width,
            start_size = main_text_size,
            min_size = sp(12)
        )

        if (wide) {
            y += dp(38)
            draw_wide_detail_rows(canvas, rect, y, content.wide_rows, style)
            return
        }

        draw_compact_stat_grid(canvas, rect, y + dp(26), style, content.compact_rows)
    }

    private fun draw_empty_panel(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        style: TrafficPanelStyle,
        content: TrafficPanelEmptyState
    ) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(20)
        text_paint.color = style.visual_theme.colors.text
        draw_fitted_left_text(
            canvas,
            content.headline,
            rect.left + dp(16),
            y,
            rect.width() - dp(32),
            sp(20),
            sp(12)
        )
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        draw_fitted_left_text(
            canvas,
            content.message,
            rect.left + dp(16),
            y + dp(24),
            rect.width() - dp(32),
            sp(12),
            sp(9)
        )
        content.data_time_label?.let {
            draw_fitted_left_text(
                canvas,
                it,
                rect.left + dp(16),
                y + dp(44),
                rect.width() - dp(32),
                sp(12),
                sp(9)
            )
        }
    }

    private fun row_height(rect: RectF, start_y: Float, row_count: Int): Float {
        if (row_count <= 0) return dp(40)
        val available = (rect.bottom - dp(14) - start_y).coerceAtLeast(dp(21))
        return (available / row_count).coerceIn(dp(24), dp(40))
    }

    private fun draw_wide_detail_rows(
        canvas: Canvas,
        rect: RectF,
        top: Float,
        rows: List<TrafficPanelRow>,
        style: TrafficPanelStyle
    ) {
        val columns = if (rect.width() >= dp(300)) 2 else 1
        if (columns == 1) {
            var y = top
            val row_height = row_height(rect, y, rows.size)
            rows.forEach { row ->
                if (y + row_height <= rect.bottom - dp(8)) {
                    y = draw_detail_row(canvas, rect, y, row, row_height, style)
                }
            }
            return
        }

        val left = rect.left + dp(16)
        val right = rect.right - dp(16)
        val bottom = rect.bottom - dp(16)
        val route_rows = rows.filter { it.is_route_endpoint }
        val normal_rows = rows.filterNot { it.is_route_endpoint }
        val has_route_rows = route_rows.isNotEmpty()
        val route_row_height = dp(58)
        val route_height = route_rows.size * route_row_height
        val route_top = if (has_route_rows) (bottom - route_height).coerceAtLeast(top) else bottom
        val grid_bottom = if (has_route_rows) route_top - dp(8) else bottom
        val available = (grid_bottom - top).coerceAtLeast(dp(44))
        val min_row_height = if (has_route_rows) dp(42) else dp(54)
        val max_row_height = if (has_route_rows) dp(58) else dp(84)
        val line_capacity = (available / min_row_height).toInt().coerceAtLeast(1)
        val visible_rows = normal_rows.take(line_capacity * columns)
        val line_count = ((visible_rows.size + columns - 1) / columns).coerceAtLeast(1)
        val row_height = (available / line_count).coerceIn(min_row_height, max_row_height)
        val gap = dp(18)
        val column_width = ((right - left) - gap) / columns

        visible_rows.forEachIndexed { index, row ->
            val column = index % columns
            val line = index / columns
            val cell_left = left + column * (column_width + gap)
            val cell_top = top + line * row_height
            draw_wide_detail_cell(canvas, cell_left, cell_top, column_width, row_height, row, style)
        }
        route_rows.forEachIndexed { index, row ->
            val cell_top = route_top + index * route_row_height
            draw_wide_detail_cell(canvas, left, cell_top, right - left, route_row_height, row, style)
        }
    }

    private fun draw_wide_detail_cell(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        row_height: Float,
        row: TrafficPanelRow,
        style: TrafficPanelStyle
    ) {
        val spacious = row_height >= dp(76)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.color = style.visual_theme.colors.muted
        draw_fitted_left_text(
            canvas,
            row.label.uppercase(Locale.US),
            left,
            top + if (spacious) dp(14) else dp(11),
            width,
            if (spacious) sp(10) else sp(9),
            sp(7)
        )
        text_paint.isFakeBoldText = true
        text_paint.color = style.visual_theme.colors.text
        val value_lines = row.value.split('\n').take(2)
        value_lines.forEachIndexed { index, value ->
            draw_fitted_left_text(
                canvas,
                value,
                left,
                top + if (index == 0) {
                    if (spacious) dp(38) else dp(29)
                } else {
                    if (spacious) dp(60) else dp(45)
                },
                width,
                if (index == 0) {
                    if (spacious) sp(15) else sp(13)
                } else {
                    if (spacious) sp(12) else sp(11)
                },
                sp(8)
            )
        }
        stroke_paint.color = with_alpha(
            style.visual_theme.colors.panel_stroke,
            style.visual_theme.style.divider_alpha
        )
        stroke_paint.strokeWidth = dp(1)
        canvas.drawLine(left, top + row_height - dp(5), left + width, top + row_height - dp(5), stroke_paint)
        text_paint.isFakeBoldText = false
    }

    private val TrafficPanelRow.is_route_endpoint: Boolean
        get() = label == "From" || label == "To"

    private fun draw_detail_row(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        row: TrafficPanelRow,
        row_height: Float,
        style: TrafficPanelStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(10)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(row.label.uppercase(Locale.US), rect.left + dp(16), y, text_paint)

        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.text
        draw_fitted_right_text(
            canvas,
            row.value,
            rect.right - dp(16),
            y,
            rect.width() * 0.56f,
            sp(13),
            sp(9)
        )

        stroke_paint.color = with_alpha(
            style.visual_theme.colors.panel_stroke,
            style.visual_theme.style.divider_alpha
        )
        stroke_paint.strokeWidth = dp(1)
        val divider_y = y + min(dp(10), row_height - dp(7))
        canvas.drawLine(rect.left + dp(16), divider_y, rect.right - dp(16), divider_y, stroke_paint)
        text_paint.isFakeBoldText = false
        return y + row_height
    }

    private fun draw_fitted_right_text(
        canvas: Canvas,
        value: String,
        right: Float,
        y: Float,
        max_width: Float,
        start_size: Float,
        min_size: Float
    ) = draw_fitted_text(
        canvas,
        text_paint,
        value,
        right,
        y,
        max_width,
        start_size,
        min_size,
        Paint.Align.RIGHT,
        dp(0.5f),
        chrome::ellipsize
    )

    private fun draw_fitted_left_text(
        canvas: Canvas,
        value: String,
        left: Float,
        y: Float,
        max_width: Float,
        start_size: Float,
        min_size: Float
    ) = draw_fitted_text(
        canvas,
        text_paint,
        value,
        left,
        y,
        max_width,
        start_size,
        min_size,
        Paint.Align.LEFT,
        dp(0.5f),
        chrome::ellipsize
    )

    private fun draw_compact_stat_grid(
        canvas: Canvas,
        rect: RectF,
        top: Float,
        style: TrafficPanelStyle,
        rows: List<TrafficPanelRow>
    ) {
        if (rows.isEmpty()) return
        val left = rect.left + dp(16)
        val right = rect.right - dp(16)
        val bottom = rect.bottom - dp(16)
        val columns = when {
            rect.width() >= dp(520) -> 4
            rect.width() >= dp(300) -> 3
            else -> 2
        }
        val visible_rows = rows.take(columns * 2)
        val row_count = (visible_rows.size + columns - 1) / columns
        val column_gap = dp(if (columns == 3) 12 else 14)
        val row_gap = dp(8)
        val available_height = (bottom - top).coerceAtLeast(dp(34))
        val cell_height =
            ((available_height - row_gap * (row_count - 1)) / row_count).coerceIn(dp(34), dp(42))
        val grid_height = cell_height * row_count + row_gap * (row_count - 1)
        val grid_top = top + ((available_height - grid_height).coerceAtLeast(0f) * 0.5f)
        val cell_width = ((right - left) - column_gap * (columns - 1)) / columns

        visible_rows.forEachIndexed { index, row ->
            val column = index % columns
            val line = index / columns
            val cell_left = left + column * (cell_width + column_gap)
            val cell_top = grid_top + line * (cell_height + row_gap)
            text_paint.textAlign = Paint.Align.LEFT
            text_paint.isFakeBoldText = false
            text_paint.color = style.visual_theme.colors.muted
            draw_fitted_left_text(
                canvas,
                row.label.uppercase(Locale.US),
                cell_left,
                cell_top + dp(10),
                cell_width,
                sp(9),
                sp(7)
            )
            text_paint.isFakeBoldText = true
            text_paint.color = style.visual_theme.colors.text
            draw_fitted_left_text(
                canvas,
                row.value,
                cell_left,
                cell_top + dp(31),
                cell_width,
                sp(13),
                sp(8)
            )
        }
        text_paint.isFakeBoldText = false
    }

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = chrome.dp(value)

    private fun sp(value: Int): Float = sp(value.toFloat())

    private fun sp(value: Float): Float = chrome.sp(value)
}

data class TrafficPanelStyle(val visual_theme: VisualTheme)

data class TrafficPanelState(
    val title: String,
    val title_color: Int,
    val content: TrafficPanelContent
)

sealed interface TrafficPanelContent

data class TrafficPanelAircraftState(
    val callsign: String,
    val model_label: String,
    val distance_label: String,
    val distance_color: Int,
    val wide_rows: List<TrafficPanelRow>,
    val compact_rows: List<TrafficPanelRow>
) : TrafficPanelContent

data class TrafficPanelEmptyState(
    val headline: String,
    val message: String,
    val data_time_label: String?
) : TrafficPanelContent

data class TrafficPanelRow(val label: String, val value: String)

interface TrafficPanelChrome {
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int)
}

internal class TrafficPanelStateBuilder(
    private val telemetry_formatter: AircraftTelemetryFormatter,
    private val reported_distance_meters: (Aircraft) -> Double,
    private val traffic_distance_color: (Aircraft) -> Int,
    private val registry_country_label: (Aircraft) -> String,
    private val current_aircraft_details_for_panel: (Aircraft) -> AircraftDetails?,
    private val current_route_details_for_panel: (Aircraft) -> AircraftDetails?,
    private val format_origin_status: (Aircraft, AircraftDetails?) -> String
) {
    // Choose one aircraft summary; renderers only receive prepared text and colors.
    fun panel_state(
        display: TrafficDisplay,
        muted_color: Int,
        accent_blue_color: Int,
        danger_color: Int,
        filters_active: Boolean,
        filter_stats: FilterStats,
        aircraft_status: String,
        last_aircraft_data_epoch_sec: Double?
    ): TrafficPanelState {
        val target = display.aircraft
        return TrafficPanelState(
            title = panel_title(display, filters_active),
            title_color = when {
                target == null -> muted_color
                display.selected -> accent_blue_color
                else -> danger_color
            },
            content = target?.let { aircraft_panel_state(it) } ?: empty_panel_state(
                filter_stats = filter_stats,
                filters_active = filters_active,
                aircraft_status = aircraft_status,
                last_aircraft_data_epoch_sec = last_aircraft_data_epoch_sec
            )
        )
    }

    private fun panel_title(display: TrafficDisplay, filters_active: Boolean): String {
        return when {
            display.aircraft == null -> "AIRCRAFT FEED"
            display.selected -> "SELECTED TRAFFIC"
            filters_active -> "FILTERED TRAFFIC"
            else -> "NEAREST TRAFFIC"
        }
    }

    private fun aircraft_panel_state(target: Aircraft): TrafficPanelAircraftState {
        val details = current_aircraft_details_for_panel(target)
        val model = aircraft_display_model(target, details)
        val compact_rows = compact_rows(target, details)
        return TrafficPanelAircraftState(
            callsign = target.callsign_label,
            model_label = model,
            distance_label = telemetry_formatter.distance(reported_distance_meters(target)),
            distance_color = traffic_distance_color(target),
            wide_rows = panel_rows(target, details),
            compact_rows = compact_rows
        )
    }

    private fun compact_rows(target: Aircraft, details: AircraftDetails?): List<TrafficPanelRow> {
        val telemetry = panel_telemetry(target, details)
        val rows = mutableListOf(
            TrafficPanelRow("Altitude", telemetry_formatter.altitude_value(target.altitude_m)),
            TrafficPanelRow("Speed", telemetry_formatter.speed_value(target.velocity_ms)),
            TrafficPanelRow("Last contact", telemetry_formatter.age(target)),
            TrafficPanelRow("Origin", compact_registry_country_label(target)),
            TrafficPanelRow("Track", telemetry_formatter.track(target.track_deg)),
            TrafficPanelRow(
                "Vertical rate",
                telemetry_formatter.vertical_rate(target.vertical_rate_ms)
            )
        )
        target.registration?.let { rows += TrafficPanelRow("Registration", it) }
        telemetry?.squawk?.trim()?.takeIf { it.isNotBlank() }?.let {
            rows += TrafficPanelRow("Squawk", it)
        }
        return rows
    }

    private fun panel_rows(target: Aircraft, details: AircraftDetails?): List<TrafficPanelRow> {
        val route_details = current_route_details_for_panel(target)
        val telemetry = panel_telemetry(target, details)
        val route_origin = route_details?.origin_airport?.let { route_airport_label(it) }
        val route_destination = route_details?.destination_airport?.let { route_airport_label(it) }
        val rows = mutableListOf(
            TrafficPanelRow("Altitude", telemetry_formatter.altitude_value(target.altitude_m)),
            TrafficPanelRow("Speed", telemetry_formatter.speed_value(target.velocity_ms)),
            TrafficPanelRow("Origin", compact_registry_country_label(target)),
            TrafficPanelRow("Last contact", telemetry_formatter.age(target)),
            TrafficPanelRow("Track", telemetry_formatter.track(target.track_deg)),
            TrafficPanelRow(
                "Vertical rate",
                telemetry_formatter.vertical_rate(target.vertical_rate_ms)
            ),
            TrafficPanelRow("Registration", target.registration ?: "Unavailable"),
            airline_label(details)?.let { TrafficPanelRow("Airline", it) },
            details?.manufactured_year?.let { TrafficPanelRow("MFR year", it) },
            telemetry?.squawk?.trim()?.takeIf { it.isNotBlank() }?.let { TrafficPanelRow("Squawk", it) },
            message_rate_label(telemetry?.message_rate)?.let { TrafficPanelRow("Messages", it) }
        ).filterNotNull().toMutableList()
        if (route_origin != null || route_destination != null) {
            rows += TrafficPanelRow("From", route_origin ?: "Unavailable")
            rows += TrafficPanelRow("To", route_destination ?: "Unavailable")
        }
        if (target.is_military) {
            rows += TrafficPanelRow(
                "Origin status",
                format_origin_status(target, route_details)
            )
        }
        if (route_details?.origin_airport == null && route_details?.destination_airport == null) {
            route_details?.route?.let { rows += TrafficPanelRow("Route", it) }
        }
        rows += TrafficPanelRow("ICAO", target.icao24.uppercase(Locale.US))
        rows += TrafficPanelRow("Reported position", telemetry_formatter.reported_position(target))
        return rows
    }

    private fun aircraft_display_model(target: Aircraft, details: AircraftDetails? = null): String {
        return AircraftRoutePresenter.aircraft_type(details, target)
            .takeIf { it != "Unavailable" }
            ?: aircraft_category_label(target)
    }

    private fun aircraft_category_label(target: Aircraft): String {
        return when (AircraftSymbolClassifier.symbol_for(target)) {
            AircraftSymbol.AIRLINER -> "Airliner"
            AircraftSymbol.ROTORCRAFT -> "Rotorcraft"
            AircraftSymbol.GLIDER -> "Glider"
            AircraftSymbol.UAV -> "UAV"
            AircraftSymbol.SURFACE -> "Ground vehicle"
            AircraftSymbol.GENERAL_AVIATION -> "General aviation"
        }
    }

    private fun panel_telemetry(target: Aircraft, details: AircraftDetails?) =
        details?.telemetry?.with_fallback(target.telemetry) ?: target.telemetry

    private fun message_rate_label(value: Double?): String? {
        value ?: return null
        return String.format(Locale.US, "%.1f msg/s", value)
    }

    private fun airline_label(details: AircraftDetails?): String? {
        return details?.operator_code?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
    }

    private fun compact_registry_country_label(target: Aircraft): String {
        val label = registry_country_label(target)
        val flag = label.takeWhile { !it.isLetterOrDigit() }.trim()
        val country = label.drop(flag.length).trim()
        val short = country_iso3(country) ?: return label
        return if (flag.isBlank()) short else "$flag $short"
    }

    private fun route_airport_label(airport: AirportDetails): String {
        val code = listOfNotNull(airport.iata, airport.icao)
            .map { it.trim().uppercase(Locale.US) }
            .firstOrNull { it.isNotBlank() && it != "UNAVAILABLE" }
        val city = route_city_label(airport)
        val country = country_iso3(airport.country_code)
        val place = listOfNotNull(city, country).joinToString(", ")
        return when {
            code != null && place.isNotBlank() -> "$code\n$place"
            code != null -> code
            place.isNotBlank() -> place
            else -> "Unavailable"
        }
    }

    private fun route_city_label(airport: AirportDetails): String? {
        val raw = airport.region_name?.takeIf { it.isNotBlank() }
            ?: airport.name?.takeIf { it.isNotBlank() }
            ?: return null
        return abbreviate_city(
            raw.replace(Regex("\\([^)]*\\)"), " ")
                .replace(
                    Regex(
                        "\\b(International|Intl\\.?|Regional|Municipal|Executive|County|Metropolitan|National)?\\s*(Airport|Airfield|Aerodrome|Air Base|AFB)\\b",
                        RegexOption.IGNORE_CASE
                    ),
                    " "
                )
                .replace(Regex("\\s+"), " ")
                .trim(' ', '-', '/', ',')
        ).takeIf { it.isNotBlank() }
    }

    private fun abbreviate_city(value: String): String {
        return listOf(
            Regex("\\bFort\\b", RegexOption.IGNORE_CASE) to "Ft.",
            Regex("\\bSaint\\b", RegexOption.IGNORE_CASE) to "St.",
            Regex("\\bMount\\b", RegexOption.IGNORE_CASE) to "Mt."
        ).fold(value) { current, rule -> rule.first.replace(current, rule.second) }
    }

    private fun country_iso3(value: String?): String? {
        val cleaned = value?.trim()?.takeIf { it.isNotBlank() && it != "Unavailable" } ?: return null
        val upper = cleaned.uppercase(Locale.US)
        if (upper.length == 3 && upper.all { it in 'A'..'Z' }) return upper
        if (upper.length == 2 && upper.all { it in 'A'..'Z' }) {
            return iso3_for_region(upper)
        }
        val normalized = upper.replace(Regex("[^A-Z]+"), " ").trim()
        return COUNTRY_NAME_OVERRIDES[normalized] ?: Locale.getISOCountries()
            .firstNotNullOfOrNull { code ->
                val locale = Locale.Builder().setRegion(code).build()
                iso3_for_region(code)?.takeIf {
                    locale.getDisplayCountry(Locale.US).uppercase(Locale.US) == upper
                }
            }
    }

    private fun iso3_for_region(region: String): String? {
        return runCatching { Locale.Builder().setRegion(region).build().isO3Country }.getOrNull()
    }

    private companion object {
        val COUNTRY_NAME_OVERRIDES = mapOf(
            "UNITED STATES" to "USA",
            "UNITED KINGDOM" to "GBR",
            "COTE D IVOIRE" to "CIV",
            "CÔTE D IVOIRE" to "CIV",
            "SOUTH KOREA" to "KOR",
            "NORTH KOREA" to "PRK",
            "RUSSIA" to "RUS",
            "VIETNAM" to "VNM",
            "BOLIVIA" to "BOL",
            "VENEZUELA" to "VEN",
            "TANZANIA" to "TZA",
            "IRAN" to "IRN",
            "SYRIA" to "SYR",
            "LAOS" to "LAO",
            "MOLDOVA" to "MDA",
            "BRUNEI" to "BRN",
            "CZECH REPUBLIC" to "CZE",
            "UNITED ARAB EMIRATES" to "ARE"
        )
    }

    private fun empty_panel_state(
        filter_stats: FilterStats,
        filters_active: Boolean,
        aircraft_status: String,
        last_aircraft_data_epoch_sec: Double?
    ): TrafficPanelEmptyState {
        val filtered_to_none = filters_active && filter_stats.total > 0 && filter_stats.matched == 0
        return TrafficPanelEmptyState(
            headline = when {
                filtered_to_none -> "No filter matches"
                aircraft_status.startsWith("No aircraft reported") -> "No reported aircraft"
                else -> "No aircraft data"
            },
            message = if (filtered_to_none) filter_stats.summary else aircraft_status,
            data_time_label = last_aircraft_data_epoch_sec?.let { "Data time ${it.toLong()}" }
        )
    }
}
