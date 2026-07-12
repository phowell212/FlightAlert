package com.flightalert.ui

import com.flightalert.map.FilterId
import com.flightalert.map.FilterState
import com.flightalert.map.ReferenceClassCatalog
import com.flightalert.map.ReferencePresentationPolicy
import com.flightalert.map.SemanticSubtype
import com.flightalert.map.SubtypeCatalogCounts
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceFiltersPanelIntegrationTest {
    private val textMeasurer = ReferenceFiltersTextMeasurer {
            text,
            textSizeSp,
            fontScale,
            _,
            _ ->
        text.length * textSizeSp * fontScale * 0.56f
    }

    @Test
    fun controllerMapsOnlyVerifiedInstalledCatalogRowsIntoTheMapTab() {
        val controller = ReferenceFiltersPanelController(textMeasurer)
        val catalog = installedCatalog(
            mapOf(
                SemanticSubtype.RIVER to count(4uL),
                SemanticSubtype.COASTLINE to count(2uL),
            ),
        )

        val verified = controller.plan(
            viewport = ReferenceFiltersViewport(900f, 420f, 1f),
            session = ReferenceFiltersPanelSession(selectedTab = ReferenceFiltersTab.MAP),
            catalog = catalog,
            filterState = FilterState.defaults().with_filter(FilterId.LABELS_RIVERS, false),
            trafficContentHeightDp = 258f,
        )
        val portrait = controller.plan(
            viewport = ReferenceFiltersViewport(360f, 700f, 1f),
            session = ReferenceFiltersPanelSession(selectedTab = ReferenceFiltersTab.MAP),
            catalog = catalog,
            filterState = FilterState.defaults(),
            trafficContentHeightDp = 626f,
        )
        val widePortrait = controller.plan(
            viewport = ReferenceFiltersViewport(900f, 1_200f, 1f),
            session = ReferenceFiltersPanelSession(selectedTab = ReferenceFiltersTab.MAP),
            catalog = catalog,
            filterState = FilterState.defaults(),
            trafficContentHeightDp = 1_126f,
        )
        val unavailable = controller.plan(
            viewport = ReferenceFiltersViewport(360f, 640f, 1f),
            session = ReferenceFiltersPanelSession(selectedTab = ReferenceFiltersTab.MAP),
            catalog = null,
            filterState = FilterState.defaults(),
            trafficContentHeightDp = 566f,
        )

        assertEquals(ReferenceFiltersPanelContent.MAP_FILTERS, verified.content)
        assertEquals(2, verified.columnCount)
        assertEquals(1, portrait.columnCount)
        assertEquals(1, widePortrait.columnCount)
        assertEquals(
            setOf("filter.labels.rivers", "filter.outlines.coastlines"),
            verified.controls.filter { it.kind == ReferenceControlKind.SWITCH }
                .filter { it.swatch != null }
                .map { it.actionId }
                .toSet(),
        )
        assertEquals(false, verified.control("filter.labels.rivers").onOff)
        verified.controls.filter { it.kind == ReferenceControlKind.SWITCH }.forEach {
            assertTrue("${it.actionId} is smaller than 48dp", it.bounds.height >= 48f)
        }
        assertEquals(ReferenceFiltersPanelContent.MAP_UNAVAILABLE, unavailable.content)
        assertTrue(unavailable.statusMessage?.text?.contains("catalog is missing") == true)
        assertTrue(unavailable.controls.none { it.kind == ReferenceControlKind.SWITCH })
    }

    @Test
    fun trafficSlotReportsItsOwnScrollableContentWithoutLosingTypedHeaderActions() {
        val controller = ReferenceFiltersPanelController(textMeasurer)

        val plan = controller.plan(
            viewport = ReferenceFiltersViewport(640f, 320f, 1f),
            session = ReferenceFiltersPanelSession(
                selectedTab = ReferenceFiltersTab.TRAFFIC,
                requestedScrollOffsetDp = 10_000f,
            ),
            catalog = null,
            filterState = FilterState.defaults(),
            trafficContentHeightDp = 258f,
        )

        assertEquals(ReferenceFiltersPanelContent.TRAFFIC_SLOT, plan.content)
        assertEquals(82f, plan.maxScrollOffsetDp)
        assertEquals(plan.maxScrollOffsetDp, plan.appliedScrollOffsetDp)
        assertEquals(ReferenceDpRect(12f, 50f, 628f, 308f), plan.trafficContentBounds)
        assertEquals(
            listOf(
                ReferenceFiltersPanelActions.BACK,
                ReferenceFiltersPanelActions.TRAFFIC_TAB,
                ReferenceFiltersPanelActions.MAP_TAB,
            ),
            plan.controls.map { it.actionId },
        )
        val scrollNode = plan.accessibilityNodes.single {
            it.role == ReferenceAccessibilityRole.SCROLL_CONTAINER
        }
        assertEquals(ReferenceAccessibilityCategory.TRAFFIC, scrollNode.category)
        assertEquals("Traffic filter controls", scrollNode.label)
        assertEquals("scroll.traffic.filters", scrollNode.stableId)
        assertTrue(ReferenceAccessibilityAction.SCROLL_BACKWARD in scrollNode.actions)
    }

    @Test
    fun partiallyVisibleMapRowRemainsTouchableOnlyInsideTheContentViewport() {
        val panel = ReferenceFiltersPanel(textMeasurer)
        val rows = (0 until 7).map { index ->
            filterRow("labels.class_$index", "Label class $index", index)
        }
        val plan = panel.plan(
            ReferenceFiltersPanelInput(
                viewport = ReferenceFiltersViewport(360f, 300f, 1f),
                selectedTab = ReferenceFiltersTab.MAP,
                mapCatalog = ReferenceMapFilterCatalogUi.Verified(rows),
                requestedScrollOffsetDp = 35f,
            )
        )
        val partial = plan.controls.first { control ->
            control.kind == ReferenceControlKind.SWITCH &&
                control.bounds.intersects(plan.contentViewport) &&
                !plan.contentViewport.contains(control.bounds)
        }
        val visibleY = maxOf(partial.bounds.top, plan.contentViewport.top) + 1f

        assertEquals(
            partial.intent,
            panel.actionAt(plan, (partial.bounds.left + partial.bounds.right) / 2f, visibleY),
        )
        assertNull(
            panel.actionAt(
                plan,
                (partial.bounds.left + partial.bounds.right) / 2f,
                plan.contentViewport.top - 1f,
            )
        )
    }

    @Test
    fun oversizedFocusedRowAlignsToViewportWithoutCrashing() {
        val panel = ReferenceFiltersPanel(textMeasurer)
        val longRow = filterRow(
            "labels.long_verified_class",
            "Exceptionally important protected waters and internationally sourced regional names",
            0,
        )
        val focusId = ReferenceFiltersPanelActions.filter(longRow.stableId)

        val plan = panel.plan(
            ReferenceFiltersPanelInput(
                viewport = ReferenceFiltersViewport(360f, 240f, 2f),
                selectedTab = ReferenceFiltersTab.MAP,
                mapCatalog = ReferenceMapFilterCatalogUi.Verified(listOf(longRow)),
                focusedActionId = focusId,
            )
        )

        val focused = plan.control(focusId)
        assertTrue(focused.bounds.height > plan.contentViewport.height)
        assertTrue(focused.bounds.intersects(plan.contentViewport))
        assertTrue(
            focused.bounds.top == plan.contentViewport.top ||
                focused.bounds.bottom == plan.contentViewport.bottom
        )
    }

    @Test
    fun largeFontHeaderAndTabsFitTheirTargetsAndSmallFontScaleIsAccepted() {
        fun unavailable(viewport: ReferenceFiltersViewport) = ReferenceFiltersPanel(textMeasurer).plan(
            ReferenceFiltersPanelInput(
                viewport = viewport,
                selectedTab = ReferenceFiltersTab.MAP,
                mapCatalog = ReferenceMapFilterCatalogUi.Unavailable("Map filters unavailable."),
            )
        )

        val large = unavailable(ReferenceFiltersViewport(280f, 240f, 2f))
        val small = unavailable(ReferenceFiltersViewport(360f, 640f, 0.85f))
        val narrow = unavailable(ReferenceFiltersViewport(128f, 320f, 1f))
        val narrowLarge = unavailable(ReferenceFiltersViewport(128f, 320f, 2f))

        assertTrue(requireNotNull(large.title).bounds.height <= large.control(ReferenceFiltersPanelActions.BACK).bounds.height)
        large.controls.filter { it.kind != ReferenceControlKind.SWITCH }.forEach { control ->
            assertTrue(control.bounds.height >= 48f)
            control.labelText?.let { assertTrue(control.bounds.contains(it.bounds)) }
        }
        assertEquals(ReferenceFiltersPanelContent.MAP_UNAVAILABLE, small.content)
        assertTrue(narrow.controls.filter { it.kind == ReferenceControlKind.TAB }.all { it.bounds.width >= 48f })
        narrowLarge.controls.filter { it.kind != ReferenceControlKind.SWITCH }.forEach { control ->
            assertTrue(control.bounds.contains(requireNotNull(control.labelText).bounds))
        }
    }

    @Test
    fun scrollingAccessibilityContainsOnlyVisibleClippedControls() {
        val rows = (0 until 8).map { index ->
            filterRow("labels.class_$index", "Label class $index", index)
        }
        val plan = ReferenceFiltersPanel(textMeasurer).plan(
            ReferenceFiltersPanelInput(
                viewport = ReferenceFiltersViewport(360f, 300f, 1f),
                selectedTab = ReferenceFiltersTab.MAP,
                mapCatalog = ReferenceMapFilterCatalogUi.Verified(rows),
            )
        )

        assertTrue(plan.maxScrollOffsetDp > 0f)
        plan.accessibilityNodes
            .filter { it.role == ReferenceAccessibilityRole.SWITCH }
            .forEach { node ->
                assertTrue(plan.contentViewport.contains(node.bounds))
                assertTrue(node.bounds.height > 0f)
            }
        assertTrue(
            plan.accessibilityNodes.count { it.role == ReferenceAccessibilityRole.SWITCH } <
                plan.controls.count { it.kind == ReferenceControlKind.SWITCH }
        )
    }

    @Test
    fun masterActionPreservesStoredChildrenAndProvidesCanonicalPersistenceBytes() {
        val controller = ReferenceFiltersPanelController(textMeasurer)
        val original = FilterState.defaults().with_filter(FilterId.LABELS_STREAMS, false)

        val result = controller.reduce(
            session = ReferenceFiltersPanelSession(selectedTab = ReferenceFiltersTab.MAP),
            filterState = original,
            intent = ReferencePanelIntent.ToggleMaster(ReferenceFilterSection.LABELS),
        )

        assertFalse(result.filterState.labels_master_enabled)
        assertFalse(result.filterState.stored_enabled(FilterId.LABELS_STREAMS))
        assertTrue(result.filterState.stored_enabled(FilterId.LABELS_RIVERS))
        assertTrue(result.filterStateChanged)
        assertNotNull(result.encodedFilterState)
        assertEquals(
            result.filterState,
            ReferenceFilterPreferences.decode(result.encodedFilterState),
        )

        val restored = controller.reduce(
            session = result.session,
            filterState = result.filterState,
            intent = ReferencePanelIntent.ToggleMaster(ReferenceFilterSection.LABELS),
        )
        assertTrue(restored.filterState.effectively_enabled(FilterId.LABELS_RIVERS))
        assertFalse(restored.filterState.effectively_enabled(FilterId.LABELS_STREAMS))
    }

    @Test
    fun exactFilterActionUsesStableIdAndUnknownIdsFailClosed() {
        val controller = ReferenceFiltersPanelController(textMeasurer)
        val original = FilterState.defaults()

        val toggled = controller.reduce(
            session = ReferenceFiltersPanelSession(selectedTab = ReferenceFiltersTab.MAP),
            filterState = original,
            intent = ReferencePanelIntent.ToggleFilter("outlines.coastlines"),
        )
        val unknown = controller.reduce(
            session = toggled.session,
            filterState = toggled.filterState,
            intent = ReferencePanelIntent.ToggleFilter("outlines.future_verified_class"),
        )

        assertTrue(toggled.filterState.stored_enabled(FilterId.OUTLINES_COASTLINES))
        assertEquals(
            ReferenceFiltersPanelActions.filter("outlines.coastlines"),
            toggled.session.focusedActionId,
        )
        assertEquals(toggled.filterState, unknown.filterState)
        assertFalse(unknown.filterStateChanged)
        assertNull(unknown.encodedFilterState)
    }

    @Test
    fun tabFocusScrollAndDismissIntentsUpdateOnlyPanelSessionState() {
        val controller = ReferenceFiltersPanelController(textMeasurer)
        val originalState = FilterState.defaults()
        val originalSession = ReferenceFiltersPanelSession(
            selectedTab = ReferenceFiltersTab.TRAFFIC,
            requestedScrollOffsetDp = 17f,
        )

        val selected = controller.reduce(
            originalSession,
            originalState,
            ReferencePanelIntent.SelectTab(ReferenceFiltersTab.MAP),
        )
        val focused = controller.reduce(
            selected.session,
            selected.filterState,
            ReferencePanelIntent.MoveFocus(ReferenceFiltersPanelActions.LABELS_MASTER),
        )
        val scrolled = controller.reduce(
            focused.session,
            focused.filterState,
            ReferencePanelIntent.SetScrollOffset(123f),
        )
        val dismissed = controller.reduce(
            scrolled.session,
            scrolled.filterState,
            ReferencePanelIntent.Dismiss,
        )

        assertEquals(ReferenceFiltersTab.MAP, selected.session.selectedTab)
        assertEquals(0f, selected.session.requestedScrollOffsetDp)
        assertEquals(ReferenceFiltersPanelActions.MAP_TAB, selected.session.focusedActionId)
        assertTrue(selected.clearTrafficSearchFocus)
        assertEquals(ReferenceFiltersPanelActions.LABELS_MASTER, focused.session.focusedActionId)
        assertTrue(focused.clearTrafficSearchFocus)
        assertEquals(123f, scrolled.session.requestedScrollOffsetDp)
        assertTrue(dismissed.dismissRequested)
        assertTrue(dismissed.clearTrafficSearchFocus)
        assertEquals(originalState, dismissed.filterState)
        assertNull(dismissed.encodedFilterState)
    }

    private fun ReferenceFiltersPanelPlan.control(actionId: String): ReferenceControlPlan =
        this.controls.single { it.actionId == actionId }

    private fun ReferenceDpRect.intersects(other: ReferenceDpRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun installedCatalog(
        suppliedCounts: Map<SemanticSubtype, SubtypeCatalogCounts>,
    ): ReferenceClassCatalog {
        val completeCounts = SemanticSubtype.entries.associateWith { subtype ->
            suppliedCounts[subtype] ?: count(0uL)
        }
        val semanticSha = "a".repeat(64)
        val contractSha = "b".repeat(64)
        val policySha = ReferencePresentationPolicy.canonical_policy_sha256
        val bytes = ReferencePresentationPolicy.canonical_class_catalog_bytes(
            semanticSha,
            contractSha,
            policySha,
            completeCounts,
        )
        val catalogSha = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return ReferenceClassCatalog.from_installed_bytes(
            catalog_bytes = bytes,
            expected_catalog_sha256 = catalogSha,
            expected_renderer_semantic_stream_sha256 = semanticSha,
            expected_renderer_contract_sha256 = contractSha,
            expected_presentation_policy_sha256 = policySha,
        )
    }

    private fun count(distinctFeatures: ULong): SubtypeCatalogCounts = SubtypeCatalogCounts(
        distinct_feature_count = distinctFeatures,
        canonical_variant_count = distinctFeatures,
        posting_count = distinctFeatures,
    )

    private fun filterRow(stableId: String, title: String, order: Int): ReferenceFilterRowUi =
        ReferenceFilterRowUi(
            stableId = stableId,
            title = title,
            section = ReferenceFilterSection.LABELS,
            sortOrder = order,
            storedEnabled = true,
            swatch = ReferenceLabelStyleSwatch(
                colorArgb = 0xff78afc7.toInt(),
                haloArgb = 0xff071419.toInt(),
                fontWeight = 500,
                italic = false,
                letterSpacingEm = 0.025f,
            ),
        )
}
