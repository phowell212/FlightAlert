package com.flightalert.ui.map.traffic

import android.graphics.Color
import com.flightalert.settings.FlightAlertSettings.ThemeTreatment
import com.flightalert.settings.FlightAlertSettings.VisualTheme
import com.flightalert.ui.map.Aircraft
import kotlin.math.roundToInt

data class AltitudeColorPalette(
    val aggressive: Int,
    val aggressive_shade: Int,
    val distinct: Int,
    val calmest: Int
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
        val altitude = altitude_feet ?: return mix_color(palette.calmest, theme.colors.muted, 0.48f)
        return when {
            altitude < LOW_ALTITUDE_FEET -> {
                val progress = altitude_progress(altitude, 0.0, LOW_ALTITUDE_FEET)
                mix_color(palette.aggressive, palette.aggressive_shade, progress)
            }
            altitude < MID_ALTITUDE_FEET -> {
                val progress = altitude_progress(altitude, LOW_ALTITUDE_FEET, MID_ALTITUDE_FEET)
                mix_color(palette.aggressive_shade, palette.distinct, progress)
            }
            else -> {
                val progress = altitude_progress(altitude, MID_ALTITUDE_FEET, HIGH_ALTITUDE_FEET)
                mix_color(palette.distinct, palette.calmest, progress)
            }
        }
    }

    private fun military_altitude_color(altitude_feet: Double?, theme: VisualTheme): Int {
        val progress = altitude_feet?.let { altitude_progress(it, 0.0, HIGH_ALTITUDE_FEET) } ?: 0.55f
        val low_gray = mix_color(theme.colors.military, Color.WHITE, 0.34f)
        val high_gray = mix_color(theme.colors.military, theme.colors.scrim, 0.22f)
        return mix_color(low_gray, high_gray, progress)
    }

    private fun altitude_color_palette(theme: VisualTheme): AltitudeColorPalette {
        val colors = theme.colors
        return when (theme.style.treatment) {
            ThemeTreatment.RADAR_GRID -> AltitudeColorPalette(
                aggressive = colors.accent_orange,
                aggressive_shade = colors.accent_yellow,
                distinct = colors.accent_green,
                calmest = colors.accent_blue
            )
            ThemeTreatment.CRT_SCANLINE -> AltitudeColorPalette(
                aggressive = colors.danger,
                aggressive_shade = colors.accent_yellow,
                distinct = colors.accent_green,
                calmest = mix_color(colors.accent_blue, colors.muted, 0.25f)
            )
            ThemeTreatment.DAYLIGHT_CARD -> AltitudeColorPalette(
                aggressive = colors.danger,
                aggressive_shade = colors.accent_orange,
                distinct = colors.accent_green,
                calmest = colors.accent_blue
            )
            ThemeTreatment.STORM_BAND -> AltitudeColorPalette(
                aggressive = colors.danger,
                aggressive_shade = colors.accent_pink,
                distinct = colors.accent_green,
                calmest = colors.accent_blue
            )
            ThemeTreatment.GLASS -> AltitudeColorPalette(
                aggressive = colors.accent_orange,
                aggressive_shade = colors.accent_yellow,
                distinct = colors.accent_green,
                calmest = colors.accent_blue
            )
            ThemeTreatment.PLAIN -> AltitudeColorPalette(
                aggressive = colors.danger,
                aggressive_shade = colors.accent_orange,
                distinct = colors.accent_green,
                calmest = colors.accent_blue
            )
        }
    }

    private fun altitude_progress(altitude_feet: Double, lower_feet: Double, upper_feet: Double): Float {
        return smooth_step(0f, 1f, ((altitude_feet - lower_feet) / (upper_feet - lower_feet)).toFloat())
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
    private const val LOW_ALTITUDE_FEET = 5000.0
    private const val MID_ALTITUDE_FEET = 25000.0
    private const val HIGH_ALTITUDE_FEET = 45000.0
}
