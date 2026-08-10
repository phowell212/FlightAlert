@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import android.graphics.Color
import android.graphics.Paint
import com.flightalert.map.SourcedMapText
import com.flightalert.map.SourcedMapTextPresentationPlan
import com.flightalert.map.reference.FilterId
import com.flightalert.map.reference.LabelVisibilityRule
import com.flightalert.map.reference.OutlineVisibilityRule
import com.flightalert.map.reference.ProminenceTier
import com.flightalert.map.reference.SemanticSubtype
import com.flightalert.map.reference.labels.ReferenceLabelCollisionShape
import com.flightalert.map.reference.labels.ReferenceLabelLayoutCandidate
import com.flightalert.map.reference.labels.ReferenceLabelOccurrenceId
import com.flightalert.map.reference.labels.ReferenceLabelRuntimeTypography
import com.flightalert.map.reference.labels.ReferencePathLabelPoint
import com.flightalert.map.reference.labels.UNKNOWN_LOW_ZOOM_COUNTRY_RANK

internal fun tile_request_priority(
    tx: Int,
    ty: Int,
    center_x: Int,
    center_y: Int,
): Int {
    val dx = tx - center_x
    val dy = ty - center_y
    return dx * dx + dy * dy
}

internal fun scaled_alpha_byte(
    base_alpha: Int,
    visibility_alpha_milli: Int,
    frame_alpha: Int = 255,
): Int {
    val visible_alpha = (
        base_alpha.coerceIn(0, 255) * visibility_alpha_milli.coerceIn(0, 1_000) + 500
        ) / 1_000
    return (visible_alpha * frame_alpha.coerceIn(0, 255) + 127) / 255
}

internal fun dictionary_tile_zoom(viewport_zoom: Double, zooms: Set<Int>): Int? =
    ReferenceDictionaryLodPolicy.select(viewport_zoom, zooms)

internal data class VisibleDictionaryTile(
    val z: Int,
    val x: Int,
    val draw_x: Int,
    val y: Int,
    val core_visible: Boolean,
    val request_priority: Int,
    val outline_visibility_centizoom: Int? = null,
) {
    fun cache_key(): String {
        val visibility = outline_visibility_centizoom ?: return "$z/$x/$y"
        return "$z/$x/$y@outline-$visibility"
    }

    fun low_zoom_profile(): Boolean =
        outline_visibility_centizoom == ReferenceDictionaryLodPolicy.LOW_ZOOM_OUTLINE_PROFILE
}

internal data class DictionaryTileDrawRef(
    val tile: ParsedDictionaryTile,
    val draw_x: Int,
    val core_visible: Boolean,
)

internal data class DictionaryLineRecordRef(
    val tile: DictionaryTileDrawRef,
    val record: DictionaryLineRecord,
)

internal data class DictionaryLabelRecordRef(
    val tile: DictionaryTileDrawRef,
    val record: DictionaryLabelRecord,
    val layout_priority: Int,
    val layout_feature_id: ULong,
    val encounter_order: Int,
)

internal data class ParsedDictionaryTile(
    val coordinate: ReferenceDictionaryTileCoordinate,
    val boundaries: List<DictionaryLineRecord>,
    val labels: List<DictionaryLabelRecord>
)

internal data class PackageSnapshot(
    val info: ReferenceDictionaryPackageInfo,
    val catalog: ReferenceClassCatalog,
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

internal data class DictionaryGeometry(
    val rings: List<DictionaryRing>,
    val bounds: DictionaryBounds?
)

internal data class DictionaryRing(
    val points: FloatArray,
    val point_count: Int
)

internal data class DictionaryBounds(
    val min_x: Float,
    val min_y: Float,
    val max_x: Float,
    val max_y: Float
)

internal data class DictionaryPoint(
    val x: Float,
    val y: Float
)

internal data class DictionaryScreenPoint(
    val x: Float,
    val y: Float
)

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

internal data class DictionaryLabelStyle(
    val color: Int,
    val text_size: Float,
    val alpha: Int,
    val halo_color: Int,
    val halo_width_dp: Float,
    val halo_width_milli_em: Int,
    val halo_alpha: Int,
    val font_weight: Int,
    val italic: Boolean,
    val letter_spacing_em: Float,
)

internal data class DictionaryLabelStyleTemplate(
    val color: Int,
    val text_size_sp: Float,
    val alpha: Int,
    val halo_width_dp: Float,
    val halo_alpha: Int,
    val typeface_style: Int
)

internal data class DictionaryLabelCandidate(
    val text: String,
    val source_kind: String,
    val presentation: SourcedMapTextPresentationPlan?,
    val style: DictionaryLabelStyle,
    override val occurrenceId: ReferenceLabelOccurrenceId,
    override val featureId: ULong,
    override val priority: Int,
    override val placementRank: Int,
    override val protectedArea: Boolean,
    override val waterLine: Boolean,
    override val anchor: ReferencePathLabelPoint,
    override val collisionShape: ReferenceLabelCollisionShape,
    val rotation: Float = 0f,
    val path_points: List<ReferencePathLabelPoint>? = null,
) : ReferenceLabelLayoutCandidate {
    val x: Float get() = anchor.x.toFloat()
    val y: Float get() = anchor.y.toFloat()
}

internal data class ReferenceDrawCounts(
    val boundaries: Int,
    val labels: Int
)

internal enum class ReferenceDictionaryLayerGroup {
    PLACES,
    WATER,
    REGIONS,
    PUBLIC_LANDS,
    TRANSPORTATION,
    OTHER
}

internal data class ReferenceDictionaryLayerGroups(
    val places: Boolean,
    val water: Boolean,
    val regions: Boolean,
    val public_lands: Boolean
) {
    fun enabled(group: ReferenceDictionaryLayerGroup): Boolean {
        return when (group) {
            ReferenceDictionaryLayerGroup.PLACES -> places
            ReferenceDictionaryLayerGroup.WATER -> water
            ReferenceDictionaryLayerGroup.REGIONS -> regions
            ReferenceDictionaryLayerGroup.PUBLIC_LANDS -> public_lands
            ReferenceDictionaryLayerGroup.TRANSPORTATION,
            ReferenceDictionaryLayerGroup.OTHER -> false
        }
    }
}

internal data class RetainedReferenceOptions(
    val labels_enabled: Boolean,
    val borders_enabled: Boolean,
    val place_labels_enabled: Boolean,
    val water_labels_enabled: Boolean,
    val region_labels_enabled: Boolean,
    val public_lands_enabled: Boolean,
    val filter_mask_key: Int,
    val label_text_scale_key: Int
)

