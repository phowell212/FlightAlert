@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import android.graphics.Color
import com.flightalert.map.SourcedMapText
import com.flightalert.map.reference.FilterId
import com.flightalert.map.reference.LabelVisibilityRule
import com.flightalert.map.reference.ProminenceTier
import com.flightalert.map.reference.SemanticSubtype
import com.flightalert.map.reference.labels.ReferenceLabelRuntimeTypography
import com.flightalert.map.reference.labels.UNKNOWN_LOW_ZOOM_COUNTRY_RANK


internal data class DictionaryLabelRecord(
    val text: String,
    val source_layer: String,
    val source_kind: String,
    val line_label: Boolean,
    val drawable: Boolean,
    val protected_area: Boolean,
    val layer_group: ReferenceDictionaryLayerGroup,
    val filter_id: FilterId? = null,
    val priority: Int,
    val color: Int,
    val base_text_size_sp: Float,
    val alpha: Int,
    val halo_color: Int = Color.rgb(7, 20, 25),
    val halo_width_dp: Float,
    val halo_alpha: Int,
    val typeface_style: Int,
    val letter_spacing_em: Float = 0f,
    val rotation: Float,
    val rank: Int?,
    val min_zoom: Double?,
    val max_zoom: Double?,
    val anchor: DictionaryPoint?,
    val geometry: DictionaryGeometry?,
    val sourced_text: SourcedMapText? = null,
    val dedupe_key: String? = null,
    val is_water: Boolean? = null,
    val candidate_id: ULong? = null,
    val source_feature_id: ULong? = null,
    val presentation_subtype: SemanticSubtype? = null,
    val prominence_tier: ProminenceTier? = null,
    val complete_geometry_measure_bucket: Int = 0,
    val low_zoom_country_rank: Int = UNKNOWN_LOW_ZOOM_COUNTRY_RANK,
    val runtime_typography: ReferenceLabelRuntimeTypography? = null,
    val visibility_rule: LabelVisibilityRule? = null,
    val repeat_spacing_px: Int? = null,
    val posting_world_wrap: Int = 0,
) {
    fun visible_at(zoom: Double): Boolean {
        if (min_zoom != null && zoom + 0.01 < min_zoom) return false
        if (max_zoom != null && zoom - 0.01 > max_zoom) return false
        return true
    }
}