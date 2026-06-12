package com.flightalert.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

object FlightAlertSettings {
    const val PREFS_NAME = "flight_alert"
    const val KEY_UNITS = "units"
    const val KEY_ZOOM = "zoom"
    const val KEY_ALERTS_ENABLED = "alerts_enabled"
    const val KEY_ALERT_DISTANCE_FEET = "alert_distance_feet"
    const val KEY_ALERT_ALTITUDE_FEET = "alert_altitude_feet"
    const val KEY_PRIORITY_TRACKING_ENABLED = "priority_tracking_enabled"
    const val KEY_PRIORITY_RANGE_FEET = "priority_range_feet"
    const val KEY_PRIORITY_RANGE_CIRCLE_VISIBLE = "priority_range_circle_visible"
    const val KEY_MAP_SOURCE = "map_source"
    const val KEY_MAP_LABELS_ENABLED = "map_labels_enabled"
    const val KEY_AIRCRAFT_FEED_MODE = "aircraft_feed_mode"
    const val KEY_GLOBE_WEB_SOURCE_ENABLED = "globe_web_source_enabled"
    const val KEY_LAYER_ATC_BOUNDARIES_ENABLED = "layer_atc_boundaries_enabled"
    const val KEY_LAYER_RESTRICTED_AIRSPACES_ENABLED = "layer_restricted_airspaces_enabled"
    const val KEY_LAYER_OCEANIC_TRACKS_ENABLED = "layer_oceanic_tracks_enabled"
    const val KEY_LAYER_AIRPORT_LABELS_ENABLED = "layer_airport_labels_enabled"
    const val KEY_VISUAL_THEME = "visual_theme"
    const val KEY_FILTER_SEARCH_QUERY = "filter_search_query"
    const val KEY_FILTER_AIRCRAFT_TYPE = "filter_aircraft_type"
    const val KEY_FILTER_ALTITUDE = "filter_altitude"
    const val KEY_FILTER_DISTANCE = "filter_distance"
    const val KEY_FILTER_FLIGHT_STATUS = "filter_flight_status"
    const val KEY_FILTER_REPORT_AGE = "filter_report_age"
    const val KEY_FILTER_ALERT_VOLUME = "filter_alert_volume"

    const val DEFAULT_ALERT_DISTANCE_FEET = 5000f
    const val DEFAULT_ALERT_ALTITUDE_FEET = 1000f
    const val DEFAULT_PRIORITY_RANGE_FEET = 52800f
    const val DEFAULT_PRIORITY_RANGE_CIRCLE_VISIBLE = true
    const val DEFAULT_MAP_LABELS_ENABLED = true
    const val DEFAULT_GLOBE_WEB_SOURCE_ENABLED = true
    const val DEFAULT_LAYER_ATC_BOUNDARIES_ENABLED = false
    const val DEFAULT_LAYER_RESTRICTED_AIRSPACES_ENABLED = false
    const val DEFAULT_LAYER_OCEANIC_TRACKS_ENABLED = false
    const val DEFAULT_LAYER_AIRPORT_LABELS_ENABLED = false

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun readVisualTheme(prefs: SharedPreferences): VisualTheme {
        val stored = prefs.getString(KEY_VISUAL_THEME, VisualTheme.COCKPIT.name) ?: VisualTheme.COCKPIT.name
        return VisualTheme.entries.firstOrNull { it.name == stored } ?: VisualTheme.COCKPIT
    }

    fun readVisualTheme(context: Context): VisualTheme = readVisualTheme(prefs(context))

    fun readAircraftFeedMode(prefs: SharedPreferences): AircraftFeedMode {
        val stored = prefs.getString(KEY_AIRCRAFT_FEED_MODE, null)
        if (stored != null) {
            return AircraftFeedMode.entries.firstOrNull { it.name == stored } ?: AircraftFeedMode.HYBRID
        }
        if (prefs.contains(KEY_GLOBE_WEB_SOURCE_ENABLED)) {
            return if (prefs.getBoolean(KEY_GLOBE_WEB_SOURCE_ENABLED, DEFAULT_GLOBE_WEB_SOURCE_ENABLED)) {
                AircraftFeedMode.WEB
            } else {
                AircraftFeedMode.API
            }
        }
        return AircraftFeedMode.HYBRID
    }

    fun readAircraftFeedMode(context: Context): AircraftFeedMode = readAircraftFeedMode(prefs(context))

    enum class AircraftFeedMode(
        val displayName: String,
        val compactName: String,
        val usesGlobe: Boolean
    ) {
        WEB("Web feed", "Web feed", true),
        API("API feed", "API feed", false),
        HYBRID("Hybrid feed", "Hybrid", true);

        fun next(): AircraftFeedMode = when (this) {
            WEB -> API
            API -> HYBRID
            HYBRID -> WEB
        }
    }

    data class ThemeColors(
        val mapEmpty: Int,
        val panel: Int,
        val panelAlt: Int,
        val panelStroke: Int,
        val controlFill: Int,
        val controlStroke: Int,
        val buttonFill: Int,
        val buttonStroke: Int,
        val selectedFillAlpha: Int,
        val rowFillAlpha: Int,
        val scrim: Int,
        val text: Int,
        val muted: Int,
        val danger: Int,
        val accentBlue: Int,
        val accentGreen: Int,
        val accentYellow: Int,
        val accentOrange: Int,
        val accentPink: Int,
        val military: Int,
        val systemBar: Int,
        val streetLabelText: Int,
        val streetLabelMuted: Int,
        val photoSurface: Int,
        val pathShadow: Int
    )

    data class ThemeStyle(
        val treatment: ThemeTreatment,
        val fontFamily: String,
        val panelCornerDp: Float,
        val controlCornerDp: Float,
        val panelStrokeDp: Float,
        val controlStrokeDp: Float,
        val topPanelAlpha: Int,
        val infoPanelAlpha: Int,
        val modalPanelAlpha: Int,
        val controlAlpha: Int,
        val dividerAlpha: Int,
        val textureAlpha: Int,
        val headingScale: Float = 1f
    )

    enum class ThemeTreatment {
        PLAIN,
        GLASS,
        RADAR_GRID,
        DAYLIGHT_CARD,
        STORM_BAND,
        CRT_SCANLINE
    }

    enum class VisualTheme(
        val displayName: String,
        val shortName: String,
        val colors: ThemeColors,
        val style: ThemeStyle
    ) {
        COCKPIT(
            displayName = "Cockpit",
            shortName = "Cockpit",
            colors = ThemeColors(
                mapEmpty = Color.rgb(70, 102, 89),
                panel = Color.rgb(13, 29, 25),
                panelAlt = Color.rgb(18, 31, 29),
                panelStroke = Color.rgb(39, 65, 55),
                controlFill = Color.rgb(43, 50, 54),
                controlStroke = Color.rgb(230, 235, 225),
                buttonFill = Color.rgb(38, 47, 49),
                buttonStroke = Color.rgb(59, 68, 70),
                selectedFillAlpha = 55,
                rowFillAlpha = 38,
                scrim = Color.rgb(3, 8, 9),
                text = Color.rgb(232, 241, 229),
                muted = Color.rgb(159, 179, 165),
                danger = Color.rgb(238, 75, 65),
                accentBlue = Color.rgb(105, 205, 244),
                accentGreen = Color.rgb(91, 224, 166),
                accentYellow = Color.rgb(255, 213, 77),
                accentOrange = Color.rgb(255, 144, 82),
                accentPink = Color.rgb(244, 115, 164),
                military = Color.rgb(156, 165, 163),
                systemBar = Color.rgb(13, 29, 25),
                streetLabelText = Color.rgb(20, 42, 37),
                streetLabelMuted = Color.rgb(56, 83, 75),
                photoSurface = Color.rgb(22, 42, 38),
                pathShadow = Color.rgb(10, 18, 19)
            ),
            style = ThemeStyle(
                treatment = ThemeTreatment.PLAIN,
                fontFamily = "sans",
                panelCornerDp = 8f,
                controlCornerDp = 7f,
                panelStrokeDp = 1f,
                controlStrokeDp = 1.3f,
                topPanelAlpha = 220,
                infoPanelAlpha = 236,
                modalPanelAlpha = 255,
                controlAlpha = 235,
                dividerAlpha = 74,
                textureAlpha = 0
            )
        ),
        TOWER(
            displayName = "Tower Glass",
            shortName = "Tower",
            colors = ThemeColors(
                mapEmpty = Color.rgb(61, 84, 92),
                panel = Color.rgb(14, 25, 32),
                panelAlt = Color.rgb(20, 35, 44),
                panelStroke = Color.rgb(53, 88, 101),
                controlFill = Color.rgb(37, 48, 57),
                controlStroke = Color.rgb(209, 226, 230),
                buttonFill = Color.rgb(33, 44, 53),
                buttonStroke = Color.rgb(79, 99, 110),
                selectedFillAlpha = 58,
                rowFillAlpha = 42,
                scrim = Color.rgb(5, 10, 14),
                text = Color.rgb(238, 246, 244),
                muted = Color.rgb(163, 184, 187),
                danger = Color.rgb(242, 82, 77),
                accentBlue = Color.rgb(125, 206, 255),
                accentGreen = Color.rgb(98, 232, 187),
                accentYellow = Color.rgb(250, 214, 93),
                accentOrange = Color.rgb(255, 158, 92),
                accentPink = Color.rgb(242, 126, 178),
                military = Color.rgb(172, 181, 183),
                systemBar = Color.rgb(10, 22, 29),
                streetLabelText = Color.rgb(18, 40, 48),
                streetLabelMuted = Color.rgb(55, 80, 87),
                photoSurface = Color.rgb(24, 43, 51),
                pathShadow = Color.rgb(8, 16, 20)
            ),
            style = ThemeStyle(
                treatment = ThemeTreatment.GLASS,
                fontFamily = "sans-serif-light",
                panelCornerDp = 14f,
                controlCornerDp = 12f,
                panelStrokeDp = 1.2f,
                controlStrokeDp = 1.1f,
                topPanelAlpha = 204,
                infoPanelAlpha = 224,
                modalPanelAlpha = 255,
                controlAlpha = 232,
                dividerAlpha = 62,
                textureAlpha = 52,
                headingScale = 1.04f
            )
        ),
        RADAR(
            displayName = "Radar Scope",
            shortName = "Radar",
            colors = ThemeColors(
                mapEmpty = Color.rgb(78, 83, 62),
                panel = Color.rgb(24, 25, 18),
                panelAlt = Color.rgb(34, 34, 24),
                panelStroke = Color.rgb(86, 78, 43),
                controlFill = Color.rgb(49, 48, 37),
                controlStroke = Color.rgb(229, 218, 183),
                buttonFill = Color.rgb(43, 42, 32),
                buttonStroke = Color.rgb(91, 84, 56),
                selectedFillAlpha = 62,
                rowFillAlpha = 44,
                scrim = Color.rgb(10, 10, 7),
                text = Color.rgb(247, 239, 211),
                muted = Color.rgb(190, 177, 132),
                danger = Color.rgb(255, 91, 74),
                accentBlue = Color.rgb(111, 190, 223),
                accentGreen = Color.rgb(119, 224, 153),
                accentYellow = Color.rgb(255, 198, 76),
                accentOrange = Color.rgb(255, 147, 67),
                accentPink = Color.rgb(233, 119, 150),
                military = Color.rgb(181, 174, 151),
                systemBar = Color.rgb(24, 25, 18),
                streetLabelText = Color.rgb(54, 50, 28),
                streetLabelMuted = Color.rgb(93, 83, 51),
                photoSurface = Color.rgb(43, 41, 27),
                pathShadow = Color.rgb(18, 15, 8)
            ),
            style = ThemeStyle(
                treatment = ThemeTreatment.RADAR_GRID,
                fontFamily = "monospace",
                panelCornerDp = 3f,
                controlCornerDp = 3f,
                panelStrokeDp = 1f,
                controlStrokeDp = 1f,
                topPanelAlpha = 228,
                infoPanelAlpha = 242,
                modalPanelAlpha = 255,
                controlAlpha = 238,
                dividerAlpha = 96,
                textureAlpha = 44,
                headingScale = 0.96f
            )
        ),
        DAYLIGHT(
            displayName = "VFR Chart",
            shortName = "Chart",
            colors = ThemeColors(
                mapEmpty = Color.rgb(117, 150, 153),
                panel = Color.rgb(236, 242, 238),
                panelAlt = Color.rgb(225, 235, 232),
                panelStroke = Color.rgb(123, 153, 148),
                controlFill = Color.rgb(224, 233, 232),
                controlStroke = Color.rgb(70, 101, 101),
                buttonFill = Color.rgb(218, 228, 227),
                buttonStroke = Color.rgb(121, 145, 145),
                selectedFillAlpha = 72,
                rowFillAlpha = 68,
                scrim = Color.rgb(20, 30, 33),
                text = Color.rgb(21, 38, 40),
                muted = Color.rgb(80, 102, 99),
                danger = Color.rgb(213, 57, 53),
                accentBlue = Color.rgb(0, 121, 176),
                accentGreen = Color.rgb(0, 144, 112),
                accentYellow = Color.rgb(177, 129, 0),
                accentOrange = Color.rgb(208, 93, 31),
                accentPink = Color.rgb(205, 70, 128),
                military = Color.rgb(91, 101, 100),
                systemBar = Color.rgb(21, 38, 40),
                streetLabelText = Color.rgb(24, 48, 49),
                streetLabelMuted = Color.rgb(71, 98, 97),
                photoSurface = Color.rgb(207, 222, 220),
                pathShadow = Color.rgb(239, 245, 240)
            ),
            style = ThemeStyle(
                treatment = ThemeTreatment.DAYLIGHT_CARD,
                fontFamily = "sans-serif",
                panelCornerDp = 5f,
                controlCornerDp = 4f,
                panelStrokeDp = 0.8f,
                controlStrokeDp = 1f,
                topPanelAlpha = 236,
                infoPanelAlpha = 245,
                modalPanelAlpha = 255,
                controlAlpha = 245,
                dividerAlpha = 108,
                textureAlpha = 36,
                headingScale = 0.98f
            )
        ),
        STORM(
            displayName = "Stormscope",
            shortName = "Storm",
            colors = ThemeColors(
                mapEmpty = Color.rgb(62, 70, 83),
                panel = Color.rgb(18, 21, 30),
                panelAlt = Color.rgb(27, 31, 43),
                panelStroke = Color.rgb(62, 72, 93),
                controlFill = Color.rgb(42, 46, 57),
                controlStroke = Color.rgb(214, 219, 231),
                buttonFill = Color.rgb(37, 41, 52),
                buttonStroke = Color.rgb(78, 86, 106),
                selectedFillAlpha = 58,
                rowFillAlpha = 42,
                scrim = Color.rgb(7, 8, 12),
                text = Color.rgb(237, 241, 250),
                muted = Color.rgb(166, 174, 190),
                danger = Color.rgb(255, 84, 88),
                accentBlue = Color.rgb(125, 184, 255),
                accentGreen = Color.rgb(93, 222, 175),
                accentYellow = Color.rgb(245, 205, 83),
                accentOrange = Color.rgb(255, 146, 93),
                accentPink = Color.rgb(238, 117, 190),
                military = Color.rgb(172, 176, 185),
                systemBar = Color.rgb(16, 19, 27),
                streetLabelText = Color.rgb(29, 35, 48),
                streetLabelMuted = Color.rgb(69, 79, 98),
                photoSurface = Color.rgb(31, 36, 49),
                pathShadow = Color.rgb(8, 10, 16)
            ),
            style = ThemeStyle(
                treatment = ThemeTreatment.STORM_BAND,
                fontFamily = "sans-serif-medium",
                panelCornerDp = 2f,
                controlCornerDp = 6f,
                panelStrokeDp = 1.4f,
                controlStrokeDp = 1.2f,
                topPanelAlpha = 232,
                infoPanelAlpha = 244,
                modalPanelAlpha = 252,
                controlAlpha = 238,
                dividerAlpha = 84,
                textureAlpha = 50,
                headingScale = 1.02f
            )
        ),
        TERMINAL(
            displayName = "CRT Terminal",
            shortName = "CRT",
            colors = ThemeColors(
                mapEmpty = Color.rgb(47, 80, 68),
                panel = Color.rgb(5, 24, 17),
                panelAlt = Color.rgb(9, 34, 24),
                panelStroke = Color.rgb(40, 102, 72),
                controlFill = Color.rgb(25, 48, 39),
                controlStroke = Color.rgb(183, 229, 197),
                buttonFill = Color.rgb(19, 43, 33),
                buttonStroke = Color.rgb(57, 115, 82),
                selectedFillAlpha = 58,
                rowFillAlpha = 40,
                scrim = Color.rgb(1, 9, 6),
                text = Color.rgb(222, 255, 225),
                muted = Color.rgb(132, 188, 143),
                danger = Color.rgb(255, 80, 69),
                accentBlue = Color.rgb(99, 206, 229),
                accentGreen = Color.rgb(91, 246, 132),
                accentYellow = Color.rgb(229, 235, 92),
                accentOrange = Color.rgb(255, 152, 74),
                accentPink = Color.rgb(235, 101, 167),
                military = Color.rgb(151, 180, 159),
                systemBar = Color.rgb(5, 24, 17),
                streetLabelText = Color.rgb(13, 54, 37),
                streetLabelMuted = Color.rgb(48, 101, 73),
                photoSurface = Color.rgb(13, 47, 32),
                pathShadow = Color.rgb(3, 14, 8)
            ),
            style = ThemeStyle(
                treatment = ThemeTreatment.CRT_SCANLINE,
                fontFamily = "monospace",
                panelCornerDp = 0f,
                controlCornerDp = 2f,
                panelStrokeDp = 1.1f,
                controlStrokeDp = 1f,
                topPanelAlpha = 238,
                infoPanelAlpha = 248,
                modalPanelAlpha = 255,
                controlAlpha = 238,
                dividerAlpha = 96,
                textureAlpha = 58,
                headingScale = 0.94f
            )
        )
    }
}
