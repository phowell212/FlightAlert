@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import android.graphics.Color
import android.graphics.Paint


internal data class DictionaryLineStyle(
    val color: Int,
    val stroke_width_dp: Float,
    val halo_width_dp: Float,
    val alpha: Int,
    val halo_alpha: Int,
    val halo_color: Int = Color.rgb(4, 10, 14),
    val dash_pattern_dp: List<Float> = emptyList(),
    val dash_phase_dp: Float = 0f,
    val stroke_cap: Paint.Cap = Paint.Cap.ROUND,
    val stroke_join: Paint.Join = Paint.Join.ROUND,
)