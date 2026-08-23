package com.flightalert.ui

import com.flightalert.map.reference.CatalogControlStatus
import com.flightalert.map.reference.FilterId
import com.flightalert.map.reference.FilterKind
import com.flightalert.map.reference.FontSlant
import com.flightalert.map.reference.LinePattern
import com.flightalert.map.reference.ReferencePresentationPolicy
import com.flightalert.map.reference.SemanticSubtype
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.dictionary.ReferenceClassCatalog
import java.util.Collections



class ReferenceFiltersPanelController(
    textMeasurer: ReferenceFiltersTextMeasurer,
) {
    private val panel = ReferenceFiltersPanel(textMeasurer)

    fun plan(
        viewport: ReferenceFiltersViewport,
        session: ReferenceFiltersPanelSession,
        catalog: ReferenceClassCatalog?,
        filterState: FilterState,
        trafficContentHeightDp: Float,
    ): ReferenceFiltersPanelPlan = panel.plan(
        ReferenceFiltersPanelInput(
            viewport = viewport,
            selectedTab = session.selectedTab,
            mapCatalog = mapCatalog(catalog, filterState),
            labelsMasterEnabled = filterState.labels_master_enabled,
            outlinesMasterEnabled = filterState.outlines_master_enabled,
            trafficContentHeightDp = trafficContentHeightDp,
            requestedScrollOffsetDp = session.requestedScrollOffsetDp,
            focusedActionId = session.focusedActionId,
        )
    )

    fun keyIntent(
        plan: ReferenceFiltersPanelPlan,
        session: ReferenceFiltersPanelSession,
        key: ReferencePanelKey,
    ): ReferencePanelIntent? = panel.keyIntent(plan, session.focusedActionId, key)

    private fun mapCatalog(
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

    fun reduce(
        session: ReferenceFiltersPanelSession,
        filterState: FilterState,
        intent: ReferencePanelIntent,
    ): ReferenceFiltersPanelControllerResult {
        var nextSession = session
        var nextFilterState = filterState
        var dismissRequested = false
        when (intent) {
            ReferencePanelIntent.Dismiss -> dismissRequested = true
            is ReferencePanelIntent.SelectTab -> {
                nextSession = session.copy(
                    selectedTab = intent.tab,
                    requestedScrollOffsetDp = 0f,
                    focusedActionId = when (intent.tab) {
                        ReferenceFiltersTab.TRAFFIC -> REFERENCE_FILTER_ACTION_TRAFFIC_TAB
                        ReferenceFiltersTab.MAP -> REFERENCE_FILTER_ACTION_MAP_TAB
                    },
                )
            }
            is ReferencePanelIntent.ToggleMaster -> {
                nextFilterState = when (intent.section) {
                    ReferenceFilterSection.LABELS -> filterState.with_labels_master(
                        !filterState.labels_master_enabled
                    )
                    ReferenceFilterSection.OUTLINES -> filterState.with_outlines_master(
                        !filterState.outlines_master_enabled
                    )
                }
                nextSession = session.copy(
                    focusedActionId = when (intent.section) {
                        ReferenceFilterSection.LABELS -> REFERENCE_FILTER_ACTION_LABELS_MASTER
                        ReferenceFilterSection.OUTLINES -> REFERENCE_FILTER_ACTION_OUTLINES_MASTER
                    },
                )
            }
            is ReferencePanelIntent.ToggleFilter -> {
                FilterId.from_stable_id(intent.stableFilterId)?.let { filterId ->
                    nextFilterState = filterState.with_filter(
                        filterId,
                        !filterState.stored_enabled(filterId),
                    )
                    nextSession = session.copy(
                        focusedActionId = reference_filter_action(filterId.stable_id),
                    )
                }
            }
            is ReferencePanelIntent.MoveFocus -> {
                nextSession = session.copy(focusedActionId = intent.actionId)
            }
            is ReferencePanelIntent.SetScrollOffset -> {
                require(intent.offsetDp.isFinite() && intent.offsetDp >= 0f) {
                    "panel scroll offset must be finite and nonnegative"
                }
                nextSession = session.copy(requestedScrollOffsetDp = intent.offsetDp)
            }
        }
        val filterStateChanged = nextFilterState != filterState
        return ReferenceFiltersPanelControllerResult(
            session = nextSession,
            filterState = nextFilterState,
            dismissRequested = dismissRequested,
            clearTrafficSearchFocus = intent is ReferencePanelIntent.Dismiss ||
                intent is ReferencePanelIntent.SelectTab ||
                intent is ReferencePanelIntent.MoveFocus,
            filterStateChanged = filterStateChanged,
            encodedFilterState = if (filterStateChanged) {
                ReferenceFilterPreferences.encode(nextFilterState)
            } else {
                null
            },
        )
    }
}
