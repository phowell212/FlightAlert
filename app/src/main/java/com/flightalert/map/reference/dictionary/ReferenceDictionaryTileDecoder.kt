@file:Suppress("FunctionName", "LocalVariableName", "PropertyName", "ArrayInDataClass")

package com.flightalert.map.reference.dictionary

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.flightalert.map.*
import com.flightalert.map.reference.*
import com.flightalert.map.reference.labels.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

internal class ReferenceDictionaryTileDecoder {
    fun decode(
        payload: ReferenceDictionaryTilePayload,
        outline_visibility_centizoom: Int? = null,
        request_is_relevant: () -> Boolean,
    ): ParsedDictionaryTile? {
        if (!request_is_relevant()) return null
        if (payload.runtime_schema == ReferenceDictionaryRuntimeSchema.BINARY_V3 ||
            payload.runtime_schema == ReferenceDictionaryRuntimeSchema.RENDER_TILE_V1
        ) {
            return parse_binary_tile_payload(
                payload,
                outline_visibility_centizoom,
                request_is_relevant,
            )
        }
        val root = JSONObject(payload.raw_json)
        if (!request_is_relevant()) return null
        val records = root.optJSONObject("records") ?: JSONObject()
        val hot_fields = payload.runtime_schema == ReferenceDictionaryRuntimeSchema.HOT_JSON_V2
        val parsed = ParsedDictionaryTile(
            coordinate = payload.coordinate,
            boundaries = parse_line_records(
                records = records.optJSONArray("boundaries"),
                hot_fields = hot_fields
            ),
            labels = parse_label_records(
                records = records.optJSONArray("labels"),
                z = payload.coordinate.z,
                hot_fields = hot_fields
            )
        )
        return if (request_is_relevant()) parsed else null
    }

    private fun parse_binary_tile_payload(
        payload: ReferenceDictionaryTilePayload,
        outline_visibility_centizoom: Int?,
        request_is_relevant: () -> Boolean,
    ): ParsedDictionaryTile? {
        val decoded = when (payload.runtime_schema) {
            ReferenceDictionaryRuntimeSchema.BINARY_V3 -> {
                if (!request_is_relevant()) return null
                val tile = ReferenceDictionaryBinaryTileCodec.decode(
                    payload.coordinate,
                    payload.raw_bytes,
                )
                if (request_is_relevant()) tile else return null
            }

            ReferenceDictionaryRuntimeSchema.RENDER_TILE_V1 ->
                ReferenceDictionaryRenderTileCodec.decodeIfRelevant(
                    payload.coordinate,
                    payload.raw_bytes,
                    outlineVisibilityCentizoom = outline_visibility_centizoom,
                    requestIsRelevant = request_is_relevant,
                ) ?: return null

            else -> error("reference payload is not a binary render tile")
        }
        val boundaries = ArrayList<DictionaryLineRecord>()
        val labels = ArrayList<DictionaryLabelRecord>()
        decoded.records.forEachIndexed { index, record ->
            if (
                index % PRESENTATION_CANCELLATION_INTERVAL == 0 &&
                !request_is_relevant()
            ) {
                return null
            }
            when (val model = ReferenceDictionaryBinaryPresenter.present(record)) {
                is ReferenceDictionaryBinaryDrawModel.Label -> {
                    val resolved = model.resolvedStyle
                    val text_size_sp = model.visibility.textSizeMilliSp / 1_000f
                    val presentation_subtype =
                        ReferenceLabelRuntimePresentationPolicy.presentationSubtype(
                            model.subtype,
                            model.text.primaryText,
                        )
                    val runtime_typography = ReferenceLabelRuntimePresentationPolicy.typography(
                        presentation_subtype,
                        model.prominenceTier,
                        model.completeGeometryMeasureBucket,
                    )
                    val source_kind = binary_source_kind(model.subtype)
                    val geometry = if (
                        model.geometry.kind == ReferenceDictionaryBinaryGeometryKind.POINT
                    ) {
                        null
                    } else {
                        binary_geometry(model.geometry, request_is_relevant) ?: return null
                    }
                    labels += DictionaryLabelRecord(
                        text = model.text.primaryText,
                        source_layer = "",
                        source_kind = source_kind,
                        line_label = model.lineLabel,
                        drawable = true,
                        protected_area = model.subtype == SemanticSubtype.PROTECTED_LAND,
                        layer_group = binary_layer_group(model.filterId),
                        filter_id = model.filterId,
                        priority = model.priority,
                        color = resolved.color_argb.toInt(),
                        base_text_size_sp = text_size_sp,
                        alpha = alpha_milli_to_byte(resolved.alpha_milli),
                        halo_color = resolved.halo_argb.toInt(),
                        halo_width_dp = text_size_sp * resolved.halo_width_milli_em / 1_000f,
                        halo_alpha = alpha_milli_to_byte(resolved.halo_alpha_milli),
                        typeface_style = binary_typeface_style(model.style),
                        letter_spacing_em = model.visibility.letterSpacingMilliEm / 1_000f,
                        rotation = 0f,
                        rank = null,
                        min_zoom = model.visibility.minimumZoomCenti / 100.0,
                        max_zoom = ReferencePresentationPolicy.label_display_max_zoom_centi / 100.0,
                        anchor = if (model.geometry.kind == ReferenceDictionaryBinaryGeometryKind.POINT) {
                            DictionaryPoint(
                                model.geometry.localCoordinates[0],
                                model.geometry.localCoordinates[1],
                            )
                        } else {
                            null
                        },
                        geometry = geometry,
                        sourced_text = model.text,
                        dedupe_key = model.labelCandidateId.toString(16),
                        is_water = binary_water_label(model.subtype),
                        candidate_id = model.labelCandidateId,
                        source_feature_id = model.featureId,
                        presentation_subtype = presentation_subtype,
                        prominence_tier = model.prominenceTier,
                        complete_geometry_measure_bucket = model.completeGeometryMeasureBucket,
                        low_zoom_country_rank = if (
                            model.subtype == SemanticSubtype.COUNTRY_TERRITORY
                        ) {
                            reference_low_zoom_country_rank(model.featureId)
                        } else {
                            UNKNOWN_LOW_ZOOM_COUNTRY_RANK
                        },
                        runtime_typography = runtime_typography,
                        visibility_rule = model.visibilityRule,
                        repeat_spacing_px = model.repeatSpacingPx,
                        posting_world_wrap = record.postingWorldWrap,
                    )
                }

                is ReferenceDictionaryBinaryDrawModel.Outline -> {
                    val resolved = model.resolvedStyle
                    val geometry = binary_geometry(
                        model.geometry,
                        request_is_relevant,
                    ) ?: return null
                    boundaries += DictionaryLineRecord(
                        source_layer = "",
                        source_kind = binary_source_kind(model.subtype),
                        class_name = model.subtype.name,
                        protected_area = model.subtype == SemanticSubtype.PROTECTED_AREA_OUTLINE,
                        layer_group = binary_layer_group(model.filterId),
                        filter_id = model.filterId,
                        geometry = geometry,
                        visibility_rule = if (
                            model.subtype == SemanticSubtype.STATE_PROVINCE_BOUNDARY
                        ) {
                            model.visibility.copy(full_alpha_zoom_centi = 700)
                        } else {
                            model.visibility
                        },
                        draw_order = if (
                            model.subtype == SemanticSubtype.INTERNATIONAL_BOUNDARY
                        ) {
                            24
                        } else {
                            model.drawOrder
                        },
                        priority = model.priority,
                        source_feature_id = model.featureId,
                        dedupe_id = model.dedupeId,
                        posting_world_wrap = record.postingWorldWrap,
                        style = boundary_display_style(
                            model.subtype,
                            DictionaryLineStyle(
                                color = resolved.color_argb.toInt(),
                                stroke_width_dp = model.style.line_width_milli_dp / 1_000f,
                                halo_width_dp = resolved.line_halo_width_milli_dp / 1_000f,
                                alpha = alpha_milli_to_byte(resolved.alpha_milli),
                                halo_alpha = alpha_milli_to_byte(resolved.halo_alpha_milli),
                                halo_color = resolved.halo_argb.toInt(),
                                dash_pattern_dp = resolved.dash_milli_dp.map { it / 1_000f },
                                dash_phase_dp = resolved.dash_phase_milli_dp / 1_000f,
                                stroke_cap = if (resolved.line_cap == "butt") {
                                    Paint.Cap.BUTT
                                } else {
                                    Paint.Cap.ROUND
                                },
                                stroke_join = if (resolved.line_join == "miter") {
                                    Paint.Join.MITER
                                } else {
                                    Paint.Join.ROUND
                                },
                            ),
                        ),
                    )
                }
            }
        }
        if (!request_is_relevant()) return null
        boundaries.sortBy { it.style.stroke_width_dp }
        if (!request_is_relevant()) return null
        return ParsedDictionaryTile(
            coordinate = decoded.coordinate,
            boundaries = boundaries,
            labels = labels,
        )
    }

    // Keep the verified dictionary policy immutable; only its final on-screen treatment changes.
    private fun boundary_display_style(
        subtype: SemanticSubtype,
        canonical_style: DictionaryLineStyle,
    ): DictionaryLineStyle = when (subtype) {
        SemanticSubtype.INTERNATIONAL_BOUNDARY -> canonical_style.copy(
            color = Color.rgb(238, 242, 244),
            stroke_width_dp = 1.55f,
            halo_width_dp = 0.9f,
            alpha = 230,
            halo_alpha = 166,
            dash_pattern_dp = emptyList(),
        )

        SemanticSubtype.STATE_PROVINCE_BOUNDARY -> canonical_style.copy(
            color = Color.rgb(210, 218, 222),
            stroke_width_dp = 0.75f,
            halo_width_dp = 0.15f,
            alpha = 120,
            halo_alpha = 25,
            dash_pattern_dp = listOf(0.9f, 2.7f),
            stroke_cap = Paint.Cap.ROUND,
        )

        else -> canonical_style
    }

    private fun binary_geometry(
        geometry: ReferenceDictionaryBinaryGeometry,
        request_is_relevant: () -> Boolean,
    ): DictionaryGeometry? {
        val rings = ArrayList<DictionaryRing>(geometry.partOffsets.size)
        val total_points = geometry.localCoordinates.size / 2
        geometry.partOffsets.forEachIndexed { index, start ->
            if (!request_is_relevant()) return null
            val end = if (index + 1 < geometry.partOffsets.size) {
                geometry.partOffsets[index + 1]
            } else {
                total_points
            }
            val point_count = end - start
            if (point_count > 0) {
                rings += DictionaryRing(
                    points = geometry.localCoordinates.copyOfRange(start * 2, end * 2),
                    point_count = point_count,
                )
            }
        }
        return DictionaryGeometry(
            rings = rings,
            bounds = DictionaryBounds(
                geometry.localBounds[0],
                geometry.localBounds[1],
                geometry.localBounds[2],
                geometry.localBounds[3],
            ),
        )
    }

    private fun binary_layer_group(filter_id: FilterId): ReferenceDictionaryLayerGroup {
        return when (filter_id) {
            FilterId.LABELS_PLACES,
            FilterId.LABELS_ISLANDS -> ReferenceDictionaryLayerGroup.PLACES

            FilterId.LABELS_MAJOR_WATER,
            FilterId.LABELS_RIVERS,
            FilterId.LABELS_STREAMS,
            FilterId.LABELS_CANALS,
            FilterId.OUTLINES_COASTLINES,
            FilterId.OUTLINES_WATER_BOUNDARIES -> ReferenceDictionaryLayerGroup.WATER

            FilterId.LABELS_PROTECTED_LANDS,
            FilterId.OUTLINES_PROTECTED_AREAS -> ReferenceDictionaryLayerGroup.PUBLIC_LANDS

            else -> ReferenceDictionaryLayerGroup.REGIONS
        }
    }

    private fun binary_source_kind(subtype: SemanticSubtype): String {
        return when (subtype) {
            SemanticSubtype.OCEAN_SEA,
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR,
            SemanticSubtype.RIVER,
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.CANAL_CHANNEL,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE,
            SemanticSubtype.COASTLINE,
            SemanticSubtype.WATERSHED_WATER_BOUNDARY -> "water"

            SemanticSubtype.COUNTRY_TERRITORY,
            SemanticSubtype.FIRST_ORDER_REGION,
            SemanticSubtype.SECOND_LOCAL_REGION,
            SemanticSubtype.INTERNATIONAL_BOUNDARY,
            SemanticSubtype.STATE_PROVINCE_BOUNDARY,
            SemanticSubtype.COUNTY_LOCAL_BOUNDARY,
            SemanticSubtype.OTHER_ADMIN_BOUNDARY -> "admin"

            else -> "place"
        }
    }

    private fun binary_water_label(subtype: SemanticSubtype): Boolean {
        return subtype in setOf(
            SemanticSubtype.OCEAN_SEA,
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR,
            SemanticSubtype.RIVER,
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.CANAL_CHANNEL,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE,
        )
    }

    private fun binary_typeface_style(style: StyleSpec): Int {
        val bold = style.font_weight >= 600
        val italic = style.font_slant == FontSlant.ITALIC
        return when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
    }

    private fun alpha_milli_to_byte(value: Int): Int {
        return ((value.coerceIn(0, 1_000) * 255) + 500) / 1_000
    }

    private fun parse_line_records(
        records: JSONArray?,
        hot_fields: Boolean
    ): List<DictionaryLineRecord> {
        if (records == null || records.length() <= 0) return emptyList()
        return buildList(records.length()) {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val source_layer = record.optString("sourceLayer", "")
                val baked_layer_group = if (hot_fields) record.layer_group_or_null() else null
                val source_kind = record.optString("sourceKind", "")
                    .ifBlank { source_kind_for_group(baked_layer_group) ?: "" }
                val class_name = record.optString("class", "")
                val protected_area = record.optional_boolean("protectedArea")
                    ?: if (baked_layer_group != null) {
                        baked_layer_group == ReferenceDictionaryLayerGroup.PUBLIC_LANDS
                    } else {
                        protected_area_line(
                            source_layer = source_layer,
                            source_kind = source_kind,
                            class_name = class_name
                        )
                    }
                val layer_group = baked_layer_group ?: line_layer_group(
                    source_layer = source_layer,
                    source_kind = source_kind,
                    class_name = class_name,
                    protected_area = protected_area
                )
                val geometry = parse_geometry(record.optJSONObject("geometry")) ?: continue
                add(
                    DictionaryLineRecord(
                        source_layer = source_layer,
                        source_kind = source_kind,
                        class_name = class_name,
                        protected_area = protected_area,
                        layer_group = layer_group,
                        geometry = geometry,
                        style = line_style_for(
                            source_layer = source_layer,
                            source_kind = source_kind,
                            class_name = class_name,
                            layer_group = layer_group
                        )
                    )
                )
            }
        }
    }

    private fun parse_label_records(
        records: JSONArray?,
        z: Int,
        hot_fields: Boolean
    ): List<DictionaryLabelRecord> {
        if (records == null || records.length() <= 0) return emptyList()
        return buildList(records.length()) {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val text = record.optString("text", "").trim()
                if (text.isEmpty()) continue
                val source_layer = record.optString("sourceLayer", "")
                val baked_layer_group = if (hot_fields) record.layer_group_or_null() else null
                val source_kind = record.optString("sourceKind", "")
                    .ifBlank { source_kind_for_group(baked_layer_group) ?: "" }
                val label_placement = record.optString("labelPlacement", "")
                    .ifBlank { if (record.optBoolean("lineLabel", false)) "line" else "" }
                val rank = record.optional_int("rank")
                val class_name = record.optString("class", "")
                val protected_area = record.optional_boolean("protectedArea")
                    ?: if (baked_layer_group != null) {
                        baked_layer_group == ReferenceDictionaryLayerGroup.PUBLIC_LANDS
                    } else {
                        protected_area_label(
                            text = text,
                            source_layer = source_layer,
                            source_kind = source_kind,
                            class_name = class_name
                        )
                    }
                val layer_group = baked_layer_group ?: label_layer_group(
                    source_kind = source_kind,
                    source_layer = source_layer,
                    protected_area = protected_area
                )
                if (!should_draw_label(
                        text = text,
                        source_kind = source_kind,
                        source_layer = source_layer,
                        label_placement = label_placement,
                        rank = rank,
                        protected_area = protected_area,
                        z = z
                    )
                ) {
                    continue
                }
                val line_label = label_placement == "line"
                val anchor = parse_point(record.optJSONObject("anchor"))
                val geometry = if (line_label || anchor == null) {
                    parse_geometry(record.optJSONObject("geometry"))
                } else {
                    null
                }
                val style_template = label_style_template_for(
                    text = text,
                    source_kind = source_kind,
                    source_layer = source_layer,
                    label_placement = label_placement,
                    rank = rank,
                    protected_area = protected_area,
                    z = z
                )
                add(
                    DictionaryLabelRecord(
                        text = text,
                        source_layer = source_layer,
                        source_kind = source_kind,
                        line_label = line_label,
                        drawable = true,
                        protected_area = protected_area,
                        layer_group = layer_group,
                        priority = record.optional_int("priority") ?: label_priority(
                            text = text,
                            source_kind = source_kind,
                            source_layer = source_layer,
                            label_placement = label_placement,
                            rank = rank,
                            protected_area = protected_area,
                            z = z
                        ),
                        color = style_template.color,
                        base_text_size_sp = style_template.text_size_sp,
                        alpha = style_template.alpha,
                        halo_width_dp = style_template.halo_width_dp,
                        halo_alpha = style_template.halo_alpha,
                        typeface_style = style_template.typeface_style,
                        rotation = if (source_kind == "water" && geometry?.bounds != null) {
                            label_rotation(geometry.bounds)
                        } else {
                            0f
                        },
                        rank = rank,
                        min_zoom = record.optional_double("minZoom")
                            ?: record.optional_double("minzoom"),
                        max_zoom = record.optional_double("maxZoom")
                            ?: record.optional_double("maxzoom"),
                        anchor = anchor,
                        geometry = geometry,
                        dedupe_key = "$source_kind:${text.lowercase()}",
                        is_water = source_kind == "water",
                    )
                )
            }
        }
    }

    private fun parse_geometry(geometry: JSONObject?): DictionaryGeometry? {
        if (geometry == null) return null
        val rings_json = geometry.optJSONArray("rings")
        val rings = if (rings_json != null) {
            buildList(rings_json.length()) {
                for (index in 0 until rings_json.length()) {
                    parse_ring(rings_json.optJSONArray(index))?.let { add(it) }
                }
            }
        } else {
            emptyList()
        }
        val bounds = geometry.optJSONObject("bounds")?.let { bounds_json ->
            DictionaryBounds(
                min_x = bounds_json.optDouble("minX").toFloat(),
                min_y = bounds_json.optDouble("minY").toFloat(),
                max_x = bounds_json.optDouble("maxX").toFloat(),
                max_y = bounds_json.optDouble("maxY").toFloat()
            )
        }
        return DictionaryGeometry(rings = rings, bounds = bounds)
    }

    private fun parse_ring(ring: JSONArray?): DictionaryRing? {
        if (ring == null || ring.length() < 2) return null
        val points = FloatArray(ring.length() * 2)
        var count = 0
        for (index in 0 until ring.length()) {
            val point = ring.optJSONArray(index) ?: continue
            points[count * 2] = point.optDouble(0).toFloat()
            points[count * 2 + 1] = point.optDouble(1).toFloat()
            count++
        }
        if (count < 2) return null
        return DictionaryRing(points = points.copyOf(count * 2), point_count = count)
    }

    private fun parse_point(point: JSONObject?): DictionaryPoint? {
        if (point == null) return null
        return DictionaryPoint(
            x = point.optDouble("x").toFloat(),
            y = point.optDouble("y").toFloat()
        )
    }

    private fun should_draw_label(
        text: String,
        source_kind: String,
        source_layer: String,
        label_placement: String,
        rank: Int?,
        protected_area: Boolean,
        z: Int
    ): Boolean {
        if (text.length > MAX_LABEL_TEXT_LENGTH) return false
        if (source_kind == "transportation") return false
        if (source_layer.contains("Road", ignoreCase = true)) return false
        if (source_layer.contains("Spot elevation", ignoreCase = true)) return false
        if (ROAD_SHIELD_LABEL.matches(text) && source_layer != "Graticule/label") {
            return false
        }
        if (z <= 8 && source_layer.contains("Graticule")) return false
        if (source_kind == "water") {
            if (label_placement == "line") return true
            return rank == null || rank <= water_point_rank_limit(z)
        }
        if (source_kind == "admin") return rank == null || rank <= admin_rank_limit(z)
        if (source_kind == "county") return z <= 9 && (rank == null || rank <= 24)
        if (protected_area) {
            return rank == null || rank <= protected_area_rank_limit(z)
        }
        if (source_kind == "place") {
            val limit = place_rank_limit(z, source_layer)
            return (rank == null && source_layer.contains("City", ignoreCase = true)) ||
                    (rank != null && rank <= limit)
        }
        return rank != null && rank <= 24
    }

    private fun place_rank_limit(z: Int, source_layer: String): Int {
        val base = when {
            z <= 8 -> 12
            z <= 10 -> 22
            z == 11 -> 34
            else -> 46
        }
        return if (source_layer.contains("City", ignoreCase = true)) base + 14 else base
    }

    private fun water_point_rank_limit(z: Int): Int {
        return when {
            z <= 8 -> 28
            z <= 10 -> 20
            else -> 34
        }
    }

    private fun admin_rank_limit(z: Int): Int {
        return if (z <= 8) 36 else 24
    }

    private fun protected_area_rank_limit(z: Int): Int {
        return when {
            z <= 8 -> 18
            z <= 10 -> 32
            else -> 48
        }
    }

    private fun label_priority(
        text: String,
        source_kind: String,
        source_layer: String,
        label_placement: String,
        rank: Int?,
        protected_area: Boolean,
        z: Int
    ): Int {
        val hierarchy_rank = hierarchy_rank(
            source_kind = source_kind,
            source_layer = source_layer,
            rank = rank
        )
        var priority = when {
            protected_area -> 172
            source_kind == "water" -> if (label_placement == "line") 38 else 128
            source_kind == "admin" -> 56
            source_kind == "place" && source_layer.contains("City", ignoreCase = true) -> 86
            source_kind == "place" -> 158
            source_kind == "county" -> 138
            else -> 260
        }
        priority += hierarchy_rank
        if (text.length <= 4) priority += 72
        if (z <= 8 && source_kind == "place") priority += 18
        return priority
    }

    private fun label_style_template_for(
        text: String,
        source_kind: String,
        source_layer: String,
        label_placement: String,
        rank: Int?,
        protected_area: Boolean,
        z: Int
    ): DictionaryLabelStyleTemplate {
        val tier = hierarchy_tier(
            source_kind = source_kind,
            source_layer = source_layer,
            rank = rank
        )
        if (protected_area) {
            return DictionaryLabelStyleTemplate(
                color = Color.rgb(178, 213, 174),
                text_size_sp = hierarchy_size(12.4f, tier + 1, 0.45f),
                alpha = hierarchy_alpha(196, tier + 1, 12),
                halo_width_dp = hierarchy_halo(2.05f, tier + 1),
                halo_alpha = hierarchy_alpha(198, tier + 1, 12),
                typeface_style = Typeface.NORMAL
            )
        }
        return when (source_kind) {
            "water" -> DictionaryLabelStyleTemplate(
                color = Color.rgb(172, 222, 247),
                text_size_sp = if (label_placement == "line") {
                    hierarchy_size(14.2f, tier, 0.8f)
                } else {
                    hierarchy_size(12.6f, tier, 0.55f)
                },
                alpha = hierarchy_alpha(238, tier, 14),
                halo_width_dp = hierarchy_halo(2.25f, tier),
                halo_alpha = hierarchy_alpha(226, tier, 10),
                typeface_style = Typeface.ITALIC
            )

            "county" -> DictionaryLabelStyleTemplate(
                color = Color.rgb(222, 232, 240),
                text_size_sp = hierarchy_size(12.4f, tier, 0.45f),
                alpha = hierarchy_alpha(186, tier + 1, 16),
                halo_width_dp = hierarchy_halo(2.0f, tier + 1),
                halo_alpha = hierarchy_alpha(190, tier + 1, 14),
                typeface_style = Typeface.NORMAL
            )

            "admin" -> DictionaryLabelStyleTemplate(
                color = Color.rgb(238, 246, 252),
                text_size_sp = hierarchy_size(14.8f, tier, 0.75f),
                alpha = hierarchy_alpha(230, tier, 12),
                halo_width_dp = hierarchy_halo(2.45f, tier),
                halo_alpha = hierarchy_alpha(224, tier, 10),
                typeface_style = Typeface.BOLD
            )

            "place" -> DictionaryLabelStyleTemplate(
                color = Color.rgb(248, 251, 255),
                text_size_sp = hierarchy_size(
                    if (source_layer.contains("City", ignoreCase = true)) 14.3f else 12.7f,
                    tier,
                    0.7f
                ),
                alpha = hierarchy_alpha(238, tier, 14),
                halo_width_dp = hierarchy_halo(2.5f, tier),
                halo_alpha = hierarchy_alpha(230, tier, 10),
                typeface_style = if (tier <= 1) Typeface.BOLD else Typeface.NORMAL
            )

            else -> DictionaryLabelStyleTemplate(
                color = Color.rgb(242, 247, 251),
                text_size_sp = if (z >= 12) 12.5f else 13.5f,
                alpha = 226,
                halo_width_dp = 2.4f,
                halo_alpha = 220,
                typeface_style = Typeface.NORMAL
            )
        }
    }

    private fun hierarchy_rank(
        source_kind: String,
        source_layer: String,
        rank: Int?
    ): Int {
        if (rank != null) return rank.coerceIn(0, 160)
        return when {
            source_kind == "admin" -> 10
            source_kind == "water" -> 18
            source_kind == "place" && source_layer.contains("City", ignoreCase = true) -> 24
            source_kind == "county" -> 46
            else -> 80
        }
    }

    private fun hierarchy_tier(
        source_kind: String,
        source_layer: String,
        rank: Int?
    ): Int {
        val normalized_rank = hierarchy_rank(source_kind, source_layer, rank)
        return when {
            normalized_rank <= 10 -> 0
            normalized_rank <= 24 -> 1
            normalized_rank <= 46 -> 2
            else -> 3
        }
    }

    private fun hierarchy_size(base_sp: Float, tier: Int, step_sp: Float): Float {
        return (base_sp - tier.coerceIn(0, 3) * step_sp).coerceAtLeast(10.8f)
    }

    private fun hierarchy_alpha(base: Int, tier: Int, step: Int): Int {
        return (base - tier.coerceIn(0, 4) * step).coerceIn(128, 245)
    }

    private fun hierarchy_halo(base_dp: Float, tier: Int): Float {
        return (base_dp - tier.coerceIn(0, 3) * 0.18f).coerceAtLeast(1.7f)
    }

    private fun protected_area_label(
        text: String,
        source_layer: String,
        source_kind: String,
        class_name: String = ""
    ): Boolean {
        if (source_kind == "water" || source_kind == "county") return false
        val text_terms = reference_terms(text)
        val metadata_terms = reference_terms(source_layer, source_kind, class_name)
        if (protected_area_terms(text_terms) || protected_area_terms(metadata_terms)) {
            return true
        }
        return text_terms.endsWith(" park ") ||
                text_terms.contains(" parks ") ||
                text_terms.contains(" wildlife ") ||
                text_terms.contains(" parkway ") ||
                text_terms.contains(" gameland ") ||
                text_terms.contains(" gamelands ") ||
                metadata_terms.contains(" park ") ||
                metadata_terms.contains(" parks ") ||
                metadata_terms.contains(" wildlife ") ||
                metadata_terms.contains(" parkway ") ||
                metadata_terms.contains(" forest ") ||
                metadata_terms.contains(" forests ") ||
                metadata_terms.contains(" gameland ") ||
                metadata_terms.contains(" gamelands ")
    }

    private fun protected_area_line(
        source_layer: String,
        source_kind: String,
        class_name: String
    ): Boolean {
        if (source_kind == "water" || source_kind == "county") return false
        val terms = reference_terms(source_layer, source_kind, class_name)
        return protected_area_terms(terms) ||
                terms.contains(" park ") ||
                terms.contains(" parks ") ||
                terms.contains(" wildlife ") ||
                terms.contains(" parkway ") ||
                terms.contains(" forest ") ||
                terms.contains(" forests ") ||
                terms.contains(" gameland ") ||
                terms.contains(" gamelands ")
    }

    private fun protected_area_terms(terms: String): Boolean {
        return terms.contains(" wildlife refuge ") ||
                terms.contains(" national wildlife ") ||
                terms.contains(" wildlife area ") ||
                terms.contains(" wildlife areas ") ||
                terms.contains(" wildlife sanctuary ") ||
                terms.contains(" wildlife sanctuaries ") ||
                terms.contains(" refuge ") ||
                terms.contains(" national park ") ||
                terms.contains(" national parks ") ||
                terms.contains(" national capital parks ") ||
                terms.contains(" state park ") ||
                terms.contains(" county park ") ||
                terms.contains(" regional park ") ||
                terms.contains(" military park ") ||
                terms.contains(" memorial parkway ") ||
                terms.contains(" national forest ") ||
                terms.contains(" state forest ") ||
                terms.contains(" forest preserve ") ||
                terms.contains(" nature preserve ") ||
                terms.contains(" preserve ") ||
                terms.contains(" wilderness ") ||
                terms.contains(" national monument ") ||
                terms.contains(" national battlefield ") ||
                terms.contains(" national historic ") ||
                terms.contains(" national seashore ") ||
                terms.contains(" national seashores ") ||
                terms.contains(" recreation area ") ||
                terms.contains(" management area ") ||
                terms.contains(" conservation ") ||
                terms.contains(" natural area ") ||
                terms.contains(" game land ") ||
                terms.contains(" game lands ") ||
                terms.contains(" gameland ") ||
                terms.contains(" gamelands ") ||
                terms.contains(" state gameland ") ||
                terms.contains(" state gamelands ") ||
                terms.contains(" game refuge ") ||
                terms.contains(" wildlife management ")
    }

    private fun reference_terms(vararg values: String): String {
        val compact = REFERENCE_TOKEN_SEPARATOR
            .replace(values.joinToString(" ").lowercase(), " ")
            .trim()
        return if (compact.isEmpty()) " " else " $compact "
    }

    private fun JSONObject.layer_group_or_null(): ReferenceDictionaryLayerGroup? {
        val raw = optString("layerGroup")
            .ifBlank { optString("group") }
            .ifBlank { optString("g") }
            .trim()
            .uppercase()
        return when (raw) {
            "PLACES", "PLACE" -> ReferenceDictionaryLayerGroup.PLACES
            "WATER", "HYDRO" -> ReferenceDictionaryLayerGroup.WATER
            "REGIONS", "REGION", "ADMIN", "BOUNDARIES", "BORDERS" ->
                ReferenceDictionaryLayerGroup.REGIONS
            "PUBLIC_LANDS", "PUBLICLANDS", "PARKS", "PARK_FOREST" ->
                ReferenceDictionaryLayerGroup.PUBLIC_LANDS
            "TRANSPORTATION", "TRANSPORT", "STREETS", "ROADS" ->
                ReferenceDictionaryLayerGroup.TRANSPORTATION
            "OTHER" -> ReferenceDictionaryLayerGroup.OTHER
            else -> null
        }
    }

    private fun source_kind_for_group(group: ReferenceDictionaryLayerGroup?): String? {
        return when (group) {
            ReferenceDictionaryLayerGroup.PLACES -> "place"
            ReferenceDictionaryLayerGroup.WATER -> "water"
            ReferenceDictionaryLayerGroup.REGIONS -> "admin"
            ReferenceDictionaryLayerGroup.PUBLIC_LANDS -> "place"
            ReferenceDictionaryLayerGroup.TRANSPORTATION -> "transportation"
            ReferenceDictionaryLayerGroup.OTHER,
            null -> null
        }
    }

    private fun label_layer_group(
        source_kind: String,
        source_layer: String,
        protected_area: Boolean
    ): ReferenceDictionaryLayerGroup {
        if (protected_area) return ReferenceDictionaryLayerGroup.PUBLIC_LANDS
        return when {
            source_kind == "water" -> ReferenceDictionaryLayerGroup.WATER
            source_kind == "admin" || source_kind == "county" -> ReferenceDictionaryLayerGroup.REGIONS
            source_kind == "place" -> ReferenceDictionaryLayerGroup.PLACES
            source_layer.contains("City", ignoreCase = true) -> ReferenceDictionaryLayerGroup.PLACES
            source_layer.contains("Admin", ignoreCase = true) -> ReferenceDictionaryLayerGroup.REGIONS
            else -> ReferenceDictionaryLayerGroup.PLACES
        }
    }

    private fun line_layer_group(
        source_layer: String,
        source_kind: String,
        class_name: String,
        protected_area: Boolean
    ): ReferenceDictionaryLayerGroup {
        if (protected_area) return ReferenceDictionaryLayerGroup.PUBLIC_LANDS
        if (water_line_feature(source_layer, source_kind, class_name)) {
            return ReferenceDictionaryLayerGroup.WATER
        }
        return when (source_kind) {
            "water" -> ReferenceDictionaryLayerGroup.WATER
            "admin", "county" -> ReferenceDictionaryLayerGroup.REGIONS
            else -> ReferenceDictionaryLayerGroup.REGIONS
        }
    }

    private fun water_line_feature(
        source_layer: String,
        source_kind: String,
        class_name: String
    ): Boolean {
        if (source_kind == "water") return true
        val terms = reference_terms(source_layer, source_kind, class_name)
        return terms.contains(" water ") ||
                terms.contains(" waterbody ") ||
                terms.contains(" water bodies ") ||
                terms.contains(" ocean ") ||
                terms.contains(" sea ") ||
                terms.contains(" bay ") ||
                terms.contains(" lake ") ||
                terms.contains(" river ") ||
                terms.contains(" stream ") ||
                terms.contains(" coastline ") ||
                terms.contains(" shoreline ") ||
                terms.contains(" marine ") ||
                terms.contains(" hydro ")
    }

    private fun line_style_for(
        source_layer: String,
        source_kind: String,
        class_name: String,
        layer_group: ReferenceDictionaryLayerGroup
    ): DictionaryLineStyle {
        val source = source_layer.lowercase()
        val normalized_class = class_name.lowercase()
        return when {
            layer_group == ReferenceDictionaryLayerGroup.WATER ||
                    water_line_feature(source_layer, source_kind, class_name) -> DictionaryLineStyle(
                color = Color.rgb(166, 214, 236),
                stroke_width_dp = 0.58f,
                halo_width_dp = 0.82f,
                alpha = 112,
                halo_alpha = 56
            )

            source.contains("admin0") || normalized_class.contains("country") -> DictionaryLineStyle(
                color = Color.WHITE,
                stroke_width_dp = 1.65f,
                halo_width_dp = 2.25f,
                alpha = 202,
                halo_alpha = 148
            )

            source.contains("admin1") || normalized_class.contains("state") -> DictionaryLineStyle(
                color = Color.rgb(250, 253, 255),
                stroke_width_dp = 1.3f,
                halo_width_dp = 1.95f,
                alpha = 184,
                halo_alpha = 126
            )

            source.contains("admin2") || source_kind == "county" -> DictionaryLineStyle(
                color = Color.rgb(238, 246, 252),
                stroke_width_dp = 0.82f,
                halo_width_dp = 1.35f,
                alpha = 124,
                halo_alpha = 82
            )

            source.contains("forest") || source.contains("park") -> DictionaryLineStyle(
                color = Color.rgb(145, 220, 156),
                stroke_width_dp = 0.62f,
                halo_width_dp = 1.05f,
                alpha = 82,
                halo_alpha = 58
            )

            else -> DictionaryLineStyle(
                color = Color.rgb(232, 244, 255),
                stroke_width_dp = 0.8f,
                halo_width_dp = 1.25f,
                alpha = 112,
                halo_alpha = 74
            )
        }
    }

    private fun label_rotation(bounds: DictionaryBounds): Float {
        val dx = bounds.max_x - bounds.min_x
        val dy = bounds.max_y - bounds.min_y
        if (dx < 40f || dy < 40f) return 0f
        return (atan2(dy, dx) * 180f / Math.PI.toFloat() - 12f).coerceIn(-24f, 24f)
    }

    private fun JSONObject.optional_int(key: String): Int? {
        if (isNull(key)) return null
        return optDouble(key).takeIf { it.isFinite() }?.roundToInt()
    }

    private fun JSONObject.optional_double(key: String): Double? {
        if (isNull(key)) return null
        return optDouble(key).takeIf { it.isFinite() }
    }

    private fun JSONObject.optional_boolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return optBoolean(key)
    }


    private companion object {
        const val PRESENTATION_CANCELLATION_INTERVAL = 64
        const val MAX_LABEL_TEXT_LENGTH = 48
        const val MAP_LABEL_TEXT_SCALE_MIN = 1f
        const val MAP_LABEL_TEXT_SCALE_MAX = 1.75f
        const val LEGACY_LABEL_HALO_WIDTH_MILLI_EM = 220
        val REFERENCE_TOKEN_SEPARATOR = Regex("[^a-z0-9]+")
        val ROAD_SHIELD_LABEL = Regex("^\\d+[A-Z]?$")
    }
}
