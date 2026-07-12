package com.flightalert.ui

import com.flightalert.map.CatalogControlStatus
import com.flightalert.map.FilterId
import com.flightalert.map.FilterKind
import com.flightalert.map.FilterState
import com.flightalert.map.FontSlant
import com.flightalert.map.LinePattern
import com.flightalert.map.ReferenceClassCatalog
import com.flightalert.map.ReferencePresentationPolicy
import com.flightalert.map.SemanticSubtype
import java.util.Collections

object ReferenceFilterCatalogUiMapper {
    fun map(
        catalog: ReferenceClassCatalog?,
        filterState: FilterState,
    ): ReferenceMapFilterCatalogUi {
        if (catalog == null) {
            return ReferenceMapFilterCatalogUi.Unavailable(
                "Map reference filters are unavailable because the installed reference catalog is missing.",
            )
        }
        if (catalog.status == CatalogControlStatus.UNAVAILABLE) {
            return ReferenceMapFilterCatalogUi.Unavailable(
                "Map reference filters are unavailable because the installed reference catalog is not verified: " +
                    catalog.reason,
            )
        }

        val countsBySubtype = catalog.subtype_counts.toMap()
        val rows = FilterId.entries.mapNotNull { filterId ->
            val specification = ReferencePresentationPolicy.filter_spec(filterId)
            val representativeSubtype = specification.subtypes.firstOrNull { subtype ->
                countsBySubtype.getValue(subtype).distinct_feature_count > 0uL
            } ?: return@mapNotNull null
            ReferenceFilterRowUi(
                stableId = filterId.stable_id,
                title = specification.title,
                section = specification.kind.toSection(),
                sortOrder = filterId.ordinal,
                storedEnabled = filterState.stored_enabled(filterId),
                swatch = swatchFor(specification.kind, representativeSubtype),
            )
        }
        return ReferenceMapFilterCatalogUi.Verified(
            Collections.unmodifiableList(rows),
        )
    }

    private fun swatchFor(
        kind: FilterKind,
        subtype: SemanticSubtype,
    ): ReferenceStyleSwatch {
        val style = ReferencePresentationPolicy.style_spec_for_subtype(subtype)
        val resolved = ReferencePresentationPolicy.resolved_style_for_subtype(subtype)
        return when (kind) {
            FilterKind.LABEL -> {
                require(subtype.is_label && style.font_slant != FontSlant.NOT_APPLICABLE) {
                    "label filter representative must use a label presentation style"
                }
                ReferenceLabelStyleSwatch(
                    colorArgb = resolved.color_argb.toInt(),
                    haloArgb = resolved.halo_argb.toInt(),
                    fontWeight = style.font_weight,
                    italic = style.font_slant == FontSlant.ITALIC,
                    letterSpacingEm = style.letter_spacing_milli_em / 1_000f,
                )
            }
            FilterKind.OUTLINE -> {
                require(!subtype.is_label && style.line_width_milli_dp > 0) {
                    "outline filter representative must use an outline presentation style"
                }
                ReferenceOutlineStyleSwatch(
                    colorArgb = resolved.color_argb.toInt(),
                    haloArgb = resolved.halo_argb.toInt(),
                    lineWidthDp = style.line_width_milli_dp / 1_000f,
                    pattern = style.line_pattern.toUiPattern(),
                )
            }
        }
    }

    private fun FilterKind.toSection(): ReferenceFilterSection = when (this) {
        FilterKind.LABEL -> ReferenceFilterSection.LABELS
        FilterKind.OUTLINE -> ReferenceFilterSection.OUTLINES
    }

    private fun LinePattern.toUiPattern(): ReferenceOutlinePattern = when (this) {
        LinePattern.SOLID -> ReferenceOutlinePattern.SOLID
        LinePattern.LONG_DASH -> ReferenceOutlinePattern.LONG_DASH
        LinePattern.SHORT_DASH -> ReferenceOutlinePattern.SHORT_DASH
        LinePattern.DASH_DOT -> ReferenceOutlinePattern.DASH_DOT
        LinePattern.DOT -> ReferenceOutlinePattern.DOT
        LinePattern.NONE -> error("outline filter representative cannot use a no-line pattern")
    }
}
