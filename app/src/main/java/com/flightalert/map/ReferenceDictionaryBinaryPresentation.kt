package com.flightalert.map

internal data class ReferenceDictionaryBinaryLabelVisibility(
    val minimumZoomCenti: Int,
    val fullAlphaZoomCenti: Int,
    val textSizeMilliSp: Int,
    val maximumBendCentiDegrees: Int,
    val letterSpacingMilliEm: Int,
)

internal sealed interface ReferenceDictionaryBinaryDrawModel {
    val filterId: FilterId
    val subtype: SemanticSubtype
    val geometry: ReferenceDictionaryBinaryGeometry
    val resolvedStyle: ResolvedStyleDetails

    data class Label(
        override val filterId: FilterId,
        override val subtype: SemanticSubtype,
        override val geometry: ReferenceDictionaryBinaryGeometry,
        override val resolvedStyle: ResolvedStyleDetails,
        val style: StyleSpec,
        val visibility: ReferenceDictionaryBinaryLabelVisibility,
        val text: SourcedMapText,
        val lineLabel: Boolean,
        val featureId: ULong,
        val dedupeId: ULong,
        val labelCandidateId: ULong,
        val priority: Int,
        val prominenceTier: ProminenceTier,
        val visibilityRule: LabelVisibilityRule,
        val repeatSpacingPx: Int,
    ) : ReferenceDictionaryBinaryDrawModel

    data class Outline(
        override val filterId: FilterId,
        override val subtype: SemanticSubtype,
        override val geometry: ReferenceDictionaryBinaryGeometry,
        override val resolvedStyle: ResolvedStyleDetails,
        val style: StyleSpec,
        val visibility: OutlineVisibilityRule,
        val featureId: ULong,
        val dedupeId: ULong,
        val drawOrder: Int,
        val priority: Int,
    ) : ReferenceDictionaryBinaryDrawModel
}

/** Converts verified binary records into the one typed presentation policy used by the app. */
internal object ReferenceDictionaryBinaryPresenter {
    private val filterBySubtype: Map<SemanticSubtype, FilterId> by lazy {
        buildMap {
            ReferencePresentationTables.all_filter_specs().forEach { specification ->
                specification.subtypes.forEach { subtype ->
                    require(put(subtype, specification.filter_id) == null) {
                        "semantic subtype belongs to more than one filter"
                    }
                }
            }
        }.also { mapping ->
            require(mapping.keys == SemanticSubtype.entries.toSet()) {
                "typed reference filter ownership is incomplete"
            }
        }
    }

    fun present(record: ReferenceDictionaryBinaryRecord): ReferenceDictionaryBinaryDrawModel {
        val subtype = record.subtype
        val filterId = filterBySubtype.getValue(subtype)
        val style = ReferencePresentationPolicy.style_spec_for_subtype(subtype)
        val resolved = ReferencePresentationPolicy.resolved_style_for_subtype(subtype)
        return if (record.featureKind == ReferenceDictionaryBinaryFeatureKind.LABEL) {
            require(subtype.is_label) { "binary label uses an outline subtype" }
            requireLayerGroup(record.layerGroup, subtype)
            val rule = ReferencePresentationTables.visibility_rule(
                subtype,
                record.placement.prominenceTier,
            )
            require(
                record.minimumZoomCenti == rule.min_zoom_centi &&
                    record.fullAlphaZoomCenti == rule.full_alpha_zoom_centi &&
                    record.fadeOutZoomCenti == ReferencePresentationPolicy.label_fade_out_zoom_centi &&
                    record.maximumZoomCenti == ReferencePresentationPolicy.label_display_max_zoom_centi,
            ) { "binary label visibility differs from the pinned presentation policy" }
            ReferenceDictionaryBinaryDrawModel.Label(
                filterId = filterId,
                subtype = subtype,
                geometry = record.geometry,
                resolvedStyle = resolved,
                style = style,
                visibility = ReferenceDictionaryBinaryLabelVisibility(
                    rule.min_zoom_centi,
                    rule.full_alpha_zoom_centi,
                    rule.text_size_milli_sp,
                    rule.max_bend_centi_degrees,
                    rule.letter_spacing_milli_em,
                ),
                text = requireNotNull(record.sourcedText) {
                    "binary label lacks sourced text"
                },
                lineLabel = record.geometry.kind == ReferenceDictionaryBinaryGeometryKind.PATH,
                featureId = record.featureId,
                dedupeId = record.dedupeId,
                labelCandidateId = record.placement.labelCandidateId,
                priority = record.priority,
                prominenceTier = record.placement.prominenceTier,
                visibilityRule = rule,
                repeatSpacingPx = record.placement.spacingPx,
            )
        } else {
            require(!subtype.is_label) { "binary outline uses a label subtype" }
            val rule = ReferencePresentationPolicy.outline_visibility_rule(subtype)
            require(
                record.minimumZoomCenti == rule.min_zoom_centi &&
                    record.fullAlphaZoomCenti == rule.full_alpha_zoom_centi &&
                    record.fadeOutZoomCenti == rule.fade_out_zoom_centi &&
                    record.maximumZoomCenti == rule.max_zoom_centi &&
                    record.drawOrder == rule.draw_order &&
                    record.priority == rule.priority,
            ) { "binary outline visibility differs from the pinned presentation policy" }
            ReferenceDictionaryBinaryDrawModel.Outline(
                filterId = filterId,
                subtype = subtype,
                geometry = record.geometry,
                resolvedStyle = resolved,
                style = style,
                visibility = rule,
                featureId = record.featureId,
                dedupeId = record.dedupeId,
                drawOrder = record.drawOrder,
                priority = record.priority,
            )
        }
    }

    private fun requireLayerGroup(
        actual: ReferenceDictionaryBinaryLayerGroup,
        subtype: SemanticSubtype,
    ) {
        val expected = when (subtype) {
            SemanticSubtype.COUNTRY_TERRITORY,
            SemanticSubtype.FIRST_ORDER_REGION,
            SemanticSubtype.SECOND_LOCAL_REGION -> ReferenceDictionaryBinaryLayerGroup.REGIONS

            SemanticSubtype.CAPITAL_MAJOR_CITY,
            SemanticSubtype.CITY_TOWN,
            SemanticSubtype.LOCAL_PLACE,
            SemanticSubtype.ISLAND_ISLET -> ReferenceDictionaryBinaryLayerGroup.PLACES

            SemanticSubtype.OCEAN_SEA,
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR,
            SemanticSubtype.RIVER,
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.CANAL_CHANNEL,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE -> ReferenceDictionaryBinaryLayerGroup.WATER

            SemanticSubtype.PROTECTED_LAND -> ReferenceDictionaryBinaryLayerGroup.PUBLIC_LANDS
            else -> return
        }
        require(actual == expected) { "binary label layer group differs from its subtype" }
    }
}
