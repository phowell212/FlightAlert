package com.flightalert.ui.map.traffic

import android.graphics.Color
import com.flightalert.settings.FlightAlertSettings.ThemeTreatment
import com.flightalert.settings.FlightAlertSettings.VisualTheme
import com.flightalert.ui.map.Aircraft
import kotlin.math.roundToInt

data class AltitudeColorPalette(
    val low: Int,
    val mid: Int,
    val high: Int
)

object AircraftColorResolver {
    fun aircraft_color(aircraft: Aircraft, theme: VisualTheme): Int {
        val altitude_feet = aircraft.altitude_m?.times(FEET_PER_METER)
        return if (aircraft.is_military) {
            military_altitude_color(altitude_feet, theme)
        } else {
            altitude_color(altitude_feet, theme)
        }
    }

    private fun altitude_color(altitude_feet: Double?, theme: VisualTheme): Int {
        val palette = altitude_color_palette(theme)
        val altitude = altitude_feet ?: return mix_color(palette.high, theme.colors.muted, 0.48f)
        val progress = (altitude / ALTITUDE_COLOR_MAX_FEET).toFloat().coerceIn(0f, 1f)
        return when {
            progress < 0.5f -> mix_color(palette.low, palette.mid, smooth_step(0f, 0.5f, progress))
            else -> mix_color(palette.mid, palette.high, smooth_step(0.5f, 1f, progress))
        }
    }

    private fun military_altitude_color(altitude_feet: Double?, theme: VisualTheme): Int {
        val progress = altitude_feet?.let { (it / ALTITUDE_COLOR_MAX_FEET).toFloat().coerceIn(0f, 1f) } ?: 0.55f
        val low_gray = mix_color(theme.colors.military, Color.WHITE, 0.34f)
        val high_gray = mix_color(theme.colors.military, theme.colors.scrim, 0.22f)
        return mix_color(low_gray, high_gray, progress)
    }

    private fun altitude_color_palette(theme: VisualTheme): AltitudeColorPalette {
        val colors = theme.colors
        return when (theme.style.treatment) {
            ThemeTreatment.RADAR_GRID -> AltitudeColorPalette(colors.accent_yellow, colors.accent_orange, colors.accent_blue)
            ThemeTreatment.CRT_SCANLINE -> AltitudeColorPalette(colors.accent_yellow, colors.accent_green, mix_color(colors.accent_blue, colors.muted, 0.25f))
            ThemeTreatment.DAYLIGHT_CARD -> AltitudeColorPalette(colors.danger, colors.accent_orange, colors.accent_blue)
            ThemeTreatment.STORM_BAND -> AltitudeColorPalette(colors.danger, colors.accent_pink, colors.accent_blue)
            ThemeTreatment.GLASS -> AltitudeColorPalette(colors.accent_orange, colors.accent_green, colors.accent_blue)
            ThemeTreatment.PLAIN -> AltitudeColorPalette(colors.danger, colors.accent_orange, colors.accent_blue)
        }
    }

    private fun smooth_step(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value >= edge1) 1f else 0f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress.coerceIn(0f, 1f)
    }

    private fun mix_color(start: Int, end: Int, progress: Float): Int {
        val t = progress.coerceIn(0f, 1f)
        return Color.rgb(
            lerp(Color.red(start).toFloat(), Color.red(end).toFloat(), t).roundToInt().coerceIn(0, 255),
            lerp(Color.green(start).toFloat(), Color.green(end).toFloat(), t).roundToInt().coerceIn(0, 255),
            lerp(Color.blue(start).toFloat(), Color.blue(end).toFloat(), t).roundToInt().coerceIn(0, 255)
        )
    }

    private const val FEET_PER_METER = 3.28084
    private const val ALTITUDE_COLOR_MAX_FEET = 45000.0
}
