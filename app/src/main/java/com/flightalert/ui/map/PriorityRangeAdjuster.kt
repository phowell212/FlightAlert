package com.flightalert.ui.map

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

enum class PriorityRangeValue {
    HORIZONTAL,
    VERTICAL
}

object PriorityRangeAdjuster {
    fun adjusted_feet(
        current_feet: Float,
        value: PriorityRangeValue,
        direction: Float,
        long_press: Boolean,
        units: UnitSystem
    ): Float {
        return if (units == UnitSystem.METRIC) {
            adjusted_metric_feet(current_feet, value, direction, long_press)
        } else {
            adjusted_imperial_feet(current_feet, value, direction, long_press)
        }
    }

    private fun adjusted_metric_feet(
        current_feet: Float,
        value: PriorityRangeValue,
        direction: Float,
        long_press: Boolean
    ): Float {
        val current_meters = feet_to_meters(current_feet.toDouble())
        val normal_step_meters = when (value) {
            PriorityRangeValue.HORIZONTAL -> 250.0
            PriorityRangeValue.VERTICAL -> 100.0
        }
        val action_step_meters = if (long_press) 3000.0 else normal_step_meters
        val min_meters = when (value) {
            PriorityRangeValue.HORIZONTAL -> 200.0
            PriorityRangeValue.VERTICAL -> 30.0
        }
        val max_meters = when (value) {
            PriorityRangeValue.HORIZONTAL -> 18_000.0
            PriorityRangeValue.VERTICAL -> 3_000.0
        }
        return meters_to_feet(
            stepped_display_value(
                current = current_meters,
                direction = direction.toDouble(),
                normal_step = normal_step_meters,
                action_step = action_step_meters,
                min_value = min_meters,
                max_value = max_meters
            )
        ).toFloat()
    }

    private fun adjusted_imperial_feet(
        current_feet: Float,
        value: PriorityRangeValue,
        direction: Float,
        long_press: Boolean
    ): Float {
        val normal_step_feet = when (value) {
            PriorityRangeValue.HORIZONTAL -> 1000.0
            PriorityRangeValue.VERTICAL -> 500.0
        }
        val action_step_feet = if (long_press) 10_000.0 else normal_step_feet
        val min_feet = when (value) {
            PriorityRangeValue.HORIZONTAL -> 500.0
            PriorityRangeValue.VERTICAL -> 100.0
        }
        val max_feet = when (value) {
            PriorityRangeValue.HORIZONTAL -> 60_000.0
            PriorityRangeValue.VERTICAL -> 10_000.0
        }
        return stepped_display_value(
            current = current_feet.toDouble(),
            direction = direction.toDouble(),
            normal_step = normal_step_feet,
            action_step = action_step_feet,
            min_value = min_feet,
            max_value = max_feet
        ).toFloat()
    }

    private fun stepped_display_value(
        current: Double,
        direction: Double,
        normal_step: Double,
        action_step: Double,
        min_value: Double,
        max_value: Double
    ): Double {
        val current_on_grid = grid_aligned_value(current, normal_step)
        val aligned = if (direction > 0.0) {
            floor(current_on_grid / normal_step) * normal_step
        } else {
            ceil(current_on_grid / normal_step) * normal_step
        }
        return (aligned + direction * action_step).coerceIn(min_value, max_value)
    }

    private fun grid_aligned_value(current: Double, step: Double): Double {
        val nearest = round(current / step) * step
        return if (kotlin.math.abs(current - nearest) < GRID_EPSILON) nearest else current
    }

    private fun feet_to_meters(feet: Double): Double = feet / FEET_PER_METER

    private fun meters_to_feet(meters: Double): Double = meters * FEET_PER_METER

    private const val FEET_PER_METER = 3.28084

    private const val GRID_EPSILON = 0.05
}
