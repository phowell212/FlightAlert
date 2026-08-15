@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale
import kotlin.math.min
import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.AirportDetails
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.details.AircraftDetailsSession
import com.flightalert.details.AircraftRoutePresenter
import com.flightalert.details.AircraftTelemetryFormatter
import com.flightalert.draw_fitted_text
import com.flightalert.traffic.FilterStats



internal class TrafficPanelStateBuilder(
    private val telemetry_formatter: AircraftTelemetryFormatter,
    private val reported_distance_meters: (Aircraft) -> Double,
    private val traffic_distance_color: (Aircraft) -> Int,
    private val details_session: AircraftDetailsSession
) {

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
        val details = details_session.aircraft_details_for_panel(target)
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
        val route_details = details_session.current_route_details_for_panel(target)
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
                details_session.format_origin_status(target, route_details)
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
        val label = details_session.registry_country_label(target)
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
            return ISO3_BY_ALPHA2[upper]
        }
        val normalized = upper.replace(Regex("[^A-Z]+"), " ").trim()
        return COUNTRY_NAME_OVERRIDES[normalized] ?: ISO3_BY_DISPLAY_COUNTRY[upper]
    }

    private companion object {
        val ISO3_BY_ALPHA2 = Locale.getISOCountries().associateWith { code ->
            Locale.Builder().setRegion(code).build().isO3Country
        }
        val ISO3_BY_DISPLAY_COUNTRY = buildMap {
            Locale.getISOCountries().forEach { code ->
                val locale = Locale.Builder().setRegion(code).build()
                putIfAbsent(
                    locale.getDisplayCountry(Locale.US).uppercase(Locale.US),
                    ISO3_BY_ALPHA2.getValue(code)
                )
            }
        }
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

