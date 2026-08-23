@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import android.graphics.Color
import android.graphics.Paint
import com.flightalert.map.reference.FilterId
import com.flightalert.map.reference.OutlineVisibilityRule

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

internal data class DictionaryLineRecord(
    val source_layer: String,
    val source_kind: String,
    val class_name: String,
    val protected_area: Boolean,
    val layer_group: ReferenceDictionaryLayerGroup,
    val filter_id: FilterId? = null,
    val geometry: DictionaryGeometry,
    val visibility_rule: OutlineVisibilityRule? = null,
    val draw_order: Int = 0,
    val priority: Int = 0,
    val source_feature_id: ULong = 0uL,
    val dedupe_id: ULong? = null,
    val posting_world_wrap: Int = 0,
    val style: DictionaryLineStyle
)
