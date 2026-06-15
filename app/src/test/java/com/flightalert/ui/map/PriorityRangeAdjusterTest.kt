package com.flightalert.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PriorityRangeAdjusterTest {
    @Test
    fun imperial_long_press_changes_horizontal_range_by_clean_feet() {
        val result = PriorityRangeAdjuster.adjusted_feet(
            current_feet = 5000f,
            value = PriorityRangeValue.HORIZONTAL,
            direction = 1f,
            long_press = true,
            units = UnitSystem.IMPERIAL
        )

        assertEquals(15000f, result, 0.01f)
    }

    @Test
    fun imperial_long_press_changes_vertical_range_by_clean_feet() {
        val result = PriorityRangeAdjuster.adjusted_feet(
            current_feet = 1000f,
            value = PriorityRangeValue.VERTICAL,
            direction = 1f,
            long_press = true,
            units = UnitSystem.IMPERIAL
        )

        assertEquals(10000f, result, 0.01f)
    }

    @Test
    fun metric_long_press_changes_horizontal_range_by_clean_meters() {
        val result_feet = PriorityRangeAdjuster.adjusted_feet(
            current_feet = meters_to_feet(1500.0).toFloat(),
            value = PriorityRangeValue.HORIZONTAL,
            direction = 1f,
            long_press = true,
            units = UnitSystem.METRIC
        )

        assertEquals(4500.0, feet_to_meters(result_feet.toDouble()), 0.01)
    }

    @Test
    fun metric_tap_snaps_to_clean_meter_grid_from_legacy_feet_value() {
        val result_feet = PriorityRangeAdjuster.adjusted_feet(
            current_feet = 5000f,
            value = PriorityRangeValue.HORIZONTAL,
            direction = 1f,
            long_press = false,
            units = UnitSystem.METRIC
        )

        assertEquals(1750.0, feet_to_meters(result_feet.toDouble()), 0.01)
    }

    @Test
    fun metric_vertical_long_press_uses_clean_meter_step_and_respects_maximum() {
        val result_feet = PriorityRangeAdjuster.adjusted_feet(
            current_feet = meters_to_feet(2500.0).toFloat(),
            value = PriorityRangeValue.VERTICAL,
            direction = 1f,
            long_press = true,
            units = UnitSystem.METRIC
        )

        assertEquals(3000.0, feet_to_meters(result_feet.toDouble()), 0.01)
    }

    @Test
    fun metric_decrement_respects_minimum() {
        val result_feet = PriorityRangeAdjuster.adjusted_feet(
            current_feet = meters_to_feet(250.0).toFloat(),
            value = PriorityRangeValue.HORIZONTAL,
            direction = -1f,
            long_press = true,
            units = UnitSystem.METRIC
        )

        assertEquals(200.0, feet_to_meters(result_feet.toDouble()), 0.01)
    }

    private fun feet_to_meters(feet: Double): Double = feet / FEET_PER_METER

    private fun meters_to_feet(meters: Double): Double = meters * FEET_PER_METER

    private companion object {
        const val FEET_PER_METER = 3.28084
    }
}
