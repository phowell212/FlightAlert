package com.flightalert.ui

import kotlin.math.max
import kotlin.math.min

internal class ReferenceFiltersPanelLayout(
    private val textMeasurer: ReferenceFiltersTextMeasurer,
) {
    fun plan(input: ReferenceFiltersPanelInput): ReferenceFiltersPanelPlan {
        require(input.requestedScrollOffsetDp.isFinite() && input.requestedScrollOffsetDp >= 0f) {
            "requested scroll offset must be finite and nonnegative"
        }
        val viewport = input.viewport
        val chromeGeometry = chromeGeometry(viewport)
        val title = titlePlan(viewport, chromeGeometry)
        val headerControls = headerControls(input, chromeGeometry)
        val contentViewport = ReferenceDpRect(
            MARGIN,
            chromeGeometry.contentTop,
            viewport.widthDp - MARGIN,
            viewport.heightDp - MARGIN,
        )
        require(contentViewport.height >= MIN_CONTENT_HEIGHT) {
            "viewport is too short for the filters panel"
        }

        if (input.selectedTab == ReferenceFiltersTab.TRAFFIC) {
            val trafficContentHeight = max(input.trafficContentHeightDp, contentViewport.height)
            val maxScroll = (trafficContentHeight - contentViewport.height).coerceAtLeast(0f)
            val scroll = input.requestedScrollOffsetDp.coerceIn(0f, maxScroll)
            return finishPlan(
                content = ReferenceFiltersPanelContent.TRAFFIC_SLOT,
                controls = headerControls,
                sections = emptyList(),
                title = title,
                contentViewport = contentViewport,
                statusMessage = null,
                trafficContentBounds = ReferenceDpRect(
                    contentViewport.left,
                    contentViewport.top - scroll,
                    contentViewport.right,
                    contentViewport.top - scroll + trafficContentHeight,
                ),
                columnCount = 1,
                maxScrollOffsetDp = maxScroll,
                appliedScrollOffsetDp = scroll,
            )
        }

        return when (val catalog = input.mapCatalog) {
            is ReferenceMapFilterCatalogUi.Unavailable -> {
                val status = statusText(catalog.message, contentViewport, viewport.fontScale)
                finishStatusPlan(
                    input = input,
                    content = ReferenceFiltersPanelContent.MAP_UNAVAILABLE,
                    title = title,
                    headerControls = headerControls,
                    contentViewport = contentViewport,
                    statusMessage = status,
                )
            }
            is ReferenceMapFilterCatalogUi.Verified -> planVerified(
                input = input,
                rows = catalog.rows,
                title = title,
                headerControls = headerControls,
                contentViewport = contentViewport,
            )
        }
    }

    private fun planVerified(
        input: ReferenceFiltersPanelInput,
        rows: List<ReferenceFilterRowUi>,
        title: ReferenceTextPlan?,
        headerControls: List<ReferenceControlPlan>,
        contentViewport: ReferenceDpRect,
    ): ReferenceFiltersPanelPlan {
        val sortedRows = rows.sortedWith(
            compareBy(ReferenceFilterRowUi::section, ReferenceFilterRowUi::sortOrder, ReferenceFilterRowUi::stableId)
        )
        if (sortedRows.isEmpty()) {
            val status = statusText(
                "No map label or outline classes are present in this verified package.",
                contentViewport,
                input.viewport.fontScale,
            )
            return finishStatusPlan(
                input = input,
                content = ReferenceFiltersPanelContent.MAP_FILTERS,
                title = title,
                headerControls = headerControls,
                contentViewport = contentViewport,
                statusMessage = status,
            )
        }

        val rowsBySection = ReferenceFilterSection.entries.associateWith { section ->
            sortedRows.filter { it.section == section }
        }
        val useTwoColumns = canUseTwoColumns(
            contentViewport = contentViewport,
            fontScale = input.viewport.fontScale,
            rowsBySection = rowsBySection,
            input = input,
        )
        val sectionDrafts = if (useTwoColumns) {
            buildTwoColumnSections(input, rowsBySection, contentViewport)
        } else {
            buildOneColumnSections(input, rowsBySection, contentViewport)
        }
        val contentHeight = sectionDrafts.maxOfOrNull { it.bounds.bottom } ?: 0f
        val maxScroll = (contentHeight - contentViewport.height).coerceAtLeast(0f)
        var scroll = input.requestedScrollOffsetDp.coerceIn(0f, maxScroll)
        val focusedDraft = input.focusedActionId?.let { focusedId ->
            sectionDrafts.asSequence()
                .flatMap { it.controls.asSequence() }
                .firstOrNull { it.actionId == focusedId }
        }
        if (focusedDraft != null) {
            scroll = when {
                focusedDraft.bounds.height > contentViewport.height -> focusedDraft.bounds.top
                focusedDraft.bounds.top < scroll -> focusedDraft.bounds.top
                focusedDraft.bounds.bottom > scroll + contentViewport.height -> {
                    focusedDraft.bounds.bottom - contentViewport.height
                }
                else -> scroll
            }.coerceIn(0f, maxScroll)
        }

        val translatedSections = sectionDrafts.map { draft ->
            draft.translate(contentViewport.top - scroll)
        }
        val contentControls = translatedSections.flatMap(SectionDraft::controls)
        return finishPlan(
            content = ReferenceFiltersPanelContent.MAP_FILTERS,
            controls = headerControls + contentControls,
            sections = translatedSections.map { it.sectionPlan() },
            title = title,
            contentViewport = contentViewport,
            statusMessage = null,
            trafficContentBounds = null,
            columnCount = if (useTwoColumns) 2 else 1,
            maxScrollOffsetDp = maxScroll,
            appliedScrollOffsetDp = scroll,
        )
    }

    private fun canUseTwoColumns(
        contentViewport: ReferenceDpRect,
        fontScale: Float,
        rowsBySection: Map<ReferenceFilterSection, List<ReferenceFilterRowUi>>,
        input: ReferenceFiltersPanelInput,
    ): Boolean {
        if (input.viewport.heightDp >= input.viewport.widthDp) return false
        if (contentViewport.width < TWO_COLUMN_MIN_WIDTH) return false
        if (rowsBySection.values.any(List<ReferenceFilterRowUi>::isEmpty)) return false
        val columnWidth = (contentViewport.width - COLUMN_GAP) / 2f
        if (columnWidth < MIN_COLUMN_WIDTH) return false
        return runCatching {
            for (section in ReferenceFilterSection.entries) {
                buildSection(
                    input = input,
                    section = section,
                    rows = rowsBySection.getValue(section),
                    column = ReferenceDpRect(0f, 0f, columnWidth, 0f),
                    startY = 0f,
                    columnIndex = section.ordinal,
                    maxLabelLines = 2,
                    fontScale = fontScale,
                )
            }
        }.isSuccess
    }

    private fun buildOneColumnSections(
        input: ReferenceFiltersPanelInput,
        rowsBySection: Map<ReferenceFilterSection, List<ReferenceFilterRowUi>>,
        contentViewport: ReferenceDpRect,
    ): List<SectionDraft> {
        val result = mutableListOf<SectionDraft>()
        var top = 0f
        for (section in ReferenceFilterSection.entries) {
            val rows = rowsBySection.getValue(section)
            if (rows.isEmpty()) continue
            val draft = buildSection(
                input = input,
                section = section,
                rows = rows,
                column = ReferenceDpRect(contentViewport.left, 0f, contentViewport.right, 0f),
                startY = top,
                columnIndex = 0,
                maxLabelLines = null,
                fontScale = input.viewport.fontScale,
            )
            result += draft
            top = draft.bounds.bottom + SECTION_GAP
        }
        return result
    }

    private fun buildTwoColumnSections(
        input: ReferenceFiltersPanelInput,
        rowsBySection: Map<ReferenceFilterSection, List<ReferenceFilterRowUi>>,
        contentViewport: ReferenceDpRect,
    ): List<SectionDraft> {
        val columnWidth = (contentViewport.width - COLUMN_GAP) / 2f
        return ReferenceFilterSection.entries.map { section ->
            val left = if (section == ReferenceFilterSection.LABELS) {
                contentViewport.left
            } else {
                contentViewport.left + columnWidth + COLUMN_GAP
            }
            buildSection(
                input = input,
                section = section,
                rows = rowsBySection.getValue(section),
                column = ReferenceDpRect(left, 0f, left + columnWidth, 0f),
                startY = 0f,
                columnIndex = section.ordinal,
                maxLabelLines = 2,
                fontScale = input.viewport.fontScale,
            )
        }
    }

    private fun buildSection(
        input: ReferenceFiltersPanelInput,
        section: ReferenceFilterSection,
        rows: List<ReferenceFilterRowUi>,
        column: ReferenceDpRect,
        startY: Float,
        columnIndex: Int,
        maxLabelLines: Int?,
        fontScale: Float,
    ): SectionDraft {
        val sectionTitle = section.title()
        val headingFit = fitText(
            sectionTitle,
            column.width,
            SECTION_TEXT_SP,
            MIN_SECTION_TEXT_SP,
            fontScale,
            700,
            false,
            2,
        )
        val headingHeight = headingFit.height + HEADING_VERTICAL_PADDING
        val heading = textPlan(
            text = sectionTitle,
            fitted = headingFit,
            bounds = ReferenceDpRect(column.left, startY, column.right, startY + headingHeight),
        )
        var top = heading.bounds.bottom + CONTROL_GAP
        val masterEnabled = section.masterEnabled(input)
        val masterActionId = section.masterActionId()
        val controls = mutableListOf<ReferenceControlPlan>()
        controls += buildSwitchControl(
            actionId = masterActionId,
            intent = ReferencePanelIntent.ToggleMaster(section),
            label = if (section == ReferenceFilterSection.LABELS) "All labels" else "All outlines",
            stateLabel = if (masterEnabled) "On" else "Off",
            onOff = masterEnabled,
            sectionMasterEnabled = masterEnabled,
            swatch = null,
            category = section.accessibilityCategory(),
            column = column,
            top = top,
            maxLabelLines = maxLabelLines,
            fontScale = fontScale,
        )
        top = controls.last().bounds.bottom + CONTROL_GAP
        rows.forEach { row ->
            val stateLabel = when {
                row.storedEnabled && !masterEnabled -> "On; ${sectionTitle} master off"
                row.storedEnabled -> "On"
                else -> "Off"
            }
            controls += buildSwitchControl(
                actionId = ReferenceFiltersPanelActions.filter(row.stableId),
                intent = ReferencePanelIntent.ToggleFilter(row.stableId),
                label = row.title,
                stateLabel = stateLabel,
                onOff = row.storedEnabled,
                sectionMasterEnabled = masterEnabled,
                swatch = row.swatch,
                category = section.accessibilityCategory(),
                column = column,
                top = top,
                maxLabelLines = maxLabelLines,
                fontScale = fontScale,
            )
            top = controls.last().bounds.bottom + CONTROL_GAP
        }
        return SectionDraft(
            section = section,
            title = sectionTitle,
            bounds = ReferenceDpRect(column.left, startY, column.right, top - CONTROL_GAP),
            heading = heading,
            masterActionId = masterActionId,
            rowActionIds = controls.drop(1).map(ReferenceControlPlan::actionId),
            controls = controls,
            columnIndex = columnIndex,
        )
    }

    private fun buildSwitchControl(
        actionId: String,
        intent: ReferencePanelIntent,
        label: String,
        stateLabel: String,
        onOff: Boolean,
        sectionMasterEnabled: Boolean,
        swatch: ReferenceStyleSwatch?,
        category: ReferenceAccessibilityCategory,
        column: ReferenceDpRect,
        top: Float,
        maxLabelLines: Int?,
        fontScale: Float,
    ): ReferenceControlPlan {
        val horizontalPadding = CONTROL_HORIZONTAL_PADDING
        val stateWidth = (column.width * STATE_WIDTH_FRACTION).coerceIn(MIN_STATE_WIDTH, MAX_STATE_WIDTH)
        val swatchSpace = if (swatch == null) 0f else SWATCH_WIDTH + SWATCH_GAP
        val labelLeft = column.left + horizontalPadding + swatchSpace
        val stateLeft = column.right - horizontalPadding - stateWidth
        val labelRight = stateLeft - LABEL_STATE_GAP
        require(labelRight - labelLeft >= MIN_TEXT_WIDTH) { "control is too narrow for text" }
        val labelStyle = swatch as? ReferenceLabelStyleSwatch
        val labelFit = fitText(
            label,
            labelRight - labelLeft,
            CONTROL_TEXT_SP,
            MIN_NARROW_CONTROL_TEXT_SP,
            fontScale,
            labelStyle?.fontWeight ?: 500,
            labelStyle?.italic ?: false,
            maxLabelLines,
        )
        val stateFit = fitText(
            stateLabel,
            stateWidth,
            STATE_TEXT_SP,
            MIN_STATE_TEXT_SP,
            fontScale,
            700,
            false,
            null,
        )
        val controlHeight = max(
            MIN_TARGET_SIZE,
            max(labelFit.height, stateFit.height) + 2f * CONTROL_VERTICAL_PADDING,
        )
        val bounds = ReferenceDpRect(column.left, top, column.right, top + controlHeight)
        val labelBounds = ReferenceDpRect(
            labelLeft,
            top + (controlHeight - labelFit.height) / 2f,
            labelRight,
            top + (controlHeight + labelFit.height) / 2f,
        )
        val stateBounds = ReferenceDpRect(
            stateLeft,
            top + (controlHeight - stateFit.height) / 2f,
            column.right - horizontalPadding,
            top + (controlHeight + stateFit.height) / 2f,
        )
        val swatchBounds = swatch?.let {
            ReferenceDpRect(
                column.left + horizontalPadding,
                top + (controlHeight - SWATCH_HEIGHT) / 2f,
                column.left + horizontalPadding + SWATCH_WIDTH,
                top + (controlHeight + SWATCH_HEIGHT) / 2f,
            )
        }
        return ReferenceControlPlan(
            actionId = actionId,
            kind = ReferenceControlKind.SWITCH,
            label = label,
            bounds = bounds,
            category = category,
            onOff = onOff,
            stateLabel = stateLabel,
            swatch = swatch,
            sectionMasterEnabled = sectionMasterEnabled,
            labelText = textPlan(label, labelFit, labelBounds),
            stateText = textPlan(stateLabel, stateFit, stateBounds),
            swatchBounds = swatchBounds,
            intent = intent,
        )
    }

    private fun chromeGeometry(viewport: ReferenceFiltersViewport): PanelChromeGeometry {
        val maximumHeaderHeight = (viewport.heightDp - CHROME_NON_HEADER_HEIGHT) / 2f
        val desiredHeaderHeight = max(
            MIN_TARGET_SIZE,
            TITLE_TEXT_SP * viewport.fontScale * LINE_HEIGHT_MULTIPLIER,
        )
        val headerHeight = min(desiredHeaderHeight, maximumHeaderHeight)
        val headerTop = MARGIN
        val headerBottom = headerTop + headerHeight
        val tabsTop = headerBottom + HEADER_GAP
        val tabsBottom = tabsTop + headerHeight
        return PanelChromeGeometry(
            headerTop = headerTop,
            headerBottom = headerBottom,
            tabsTop = tabsTop,
            tabsBottom = tabsBottom,
            contentTop = tabsBottom + HEADER_GAP,
        )
    }

    private fun titlePlan(
        viewport: ReferenceFiltersViewport,
        geometry: PanelChromeGeometry,
    ): ReferenceTextPlan? {
        val left = MARGIN + BACK_WIDTH + HEADER_TITLE_GAP
        val right = viewport.widthDp - MARGIN
        if (right - left < MIN_TEXT_WIDTH) return null
        val heightLimitedSize = geometry.headerHeight /
            (viewport.fontScale * LINE_HEIGHT_MULTIPLIER)
        val preferredSize = min(TITLE_TEXT_SP, heightLimitedSize)
        val minimumSize = min(MIN_TITLE_TEXT_SP, preferredSize)
        val fitted = runCatching {
            fitText(
                "Filters",
                right - left,
                preferredSize,
                minimumSize,
                viewport.fontScale,
                700,
                false,
                1,
            )
        }.getOrNull() ?: return null
        val top = geometry.headerTop + (geometry.headerHeight - fitted.height) / 2f
        return textPlan(
            text = "Filters",
            fitted = fitted,
            bounds = ReferenceDpRect(left, top, right, top + fitted.height),
        )
    }

    private fun headerControls(
        input: ReferenceFiltersPanelInput,
        geometry: PanelChromeGeometry,
    ): List<ReferenceControlPlan> {
        val viewport = input.viewport
        val backBounds = ReferenceDpRect(
            MARGIN,
            geometry.headerTop,
            MARGIN + BACK_WIDTH,
            geometry.headerBottom,
        )
        val back = headerControl(
            actionId = ReferenceFiltersPanelActions.BACK,
            kind = ReferenceControlKind.BUTTON,
            label = "Close",
            bounds = backBounds,
            category = ReferenceAccessibilityCategory.PANEL,
            selected = null,
            fontScale = viewport.fontScale,
            intent = ReferencePanelIntent.Dismiss,
        )
        val tabWidth = (viewport.widthDp - 2f * MARGIN - TAB_GAP) / 2f
        val trafficBounds = ReferenceDpRect(
            MARGIN,
            geometry.tabsTop,
            MARGIN + tabWidth,
            geometry.tabsBottom,
        )
        val mapBounds = ReferenceDpRect(
            trafficBounds.right + TAB_GAP,
            geometry.tabsTop,
            viewport.widthDp - MARGIN,
            geometry.tabsBottom,
        )
        return listOf(
            back,
            headerControl(
                ReferenceFiltersPanelActions.TRAFFIC_TAB,
                ReferenceControlKind.TAB,
                "Traffic",
                trafficBounds,
                ReferenceAccessibilityCategory.TRAFFIC,
                input.selectedTab == ReferenceFiltersTab.TRAFFIC,
                viewport.fontScale,
                ReferencePanelIntent.SelectTab(ReferenceFiltersTab.TRAFFIC),
            ),
            headerControl(
                ReferenceFiltersPanelActions.MAP_TAB,
                ReferenceControlKind.TAB,
                "Map",
                mapBounds,
                ReferenceAccessibilityCategory.MAP,
                input.selectedTab == ReferenceFiltersTab.MAP,
                viewport.fontScale,
                ReferencePanelIntent.SelectTab(ReferenceFiltersTab.MAP),
            ),
        )
    }

    private fun headerControl(
        actionId: String,
        kind: ReferenceControlKind,
        label: String,
        bounds: ReferenceDpRect,
        category: ReferenceAccessibilityCategory,
        selected: Boolean?,
        fontScale: Float,
        intent: ReferencePanelIntent,
    ): ReferenceControlPlan {
        val heightLimitedSize = bounds.height / (fontScale * LINE_HEIGHT_MULTIPLIER)
        val preferredSize = min(CONTROL_TEXT_SP, heightLimitedSize)
        val minimumSize = min(MIN_NARROW_HEADER_TEXT_SP, preferredSize)
        val horizontalPadding = min(
            CONTROL_HORIZONTAL_PADDING,
            ((bounds.width - MIN_HEADER_LABEL_WIDTH) / 2f).coerceAtLeast(0f),
        )
        val fit = fitText(
            label,
            bounds.width - 2f * horizontalPadding,
            preferredSize,
            minimumSize,
            fontScale,
            600,
            false,
            1,
        )
        val textBounds = ReferenceDpRect(
            bounds.left + horizontalPadding,
            bounds.top + (bounds.height - fit.height) / 2f,
            bounds.right - horizontalPadding,
            bounds.top + (bounds.height + fit.height) / 2f,
        )
        return ReferenceControlPlan(
            actionId = actionId,
            kind = kind,
            label = label,
            bounds = bounds,
            category = category,
            selected = selected,
            labelText = textPlan(label, fit, textBounds),
            intent = intent,
        )
    }

    private fun statusText(
        message: String,
        contentViewport: ReferenceDpRect,
        fontScale: Float,
    ): ReferenceTextPlan {
        val fit = fitText(
            message,
            contentViewport.width - 2f * STATUS_PADDING,
            STATUS_TEXT_SP,
            MIN_NARROW_STATUS_TEXT_SP,
            fontScale,
            500,
            false,
            null,
        )
        return textPlan(
            message,
            fit,
            ReferenceDpRect(
                contentViewport.left + STATUS_PADDING,
                contentViewport.top + STATUS_PADDING,
                contentViewport.right - STATUS_PADDING,
                contentViewport.top + STATUS_PADDING + fit.height,
            ),
        )
    }

    private fun finishPlan(
        content: ReferenceFiltersPanelContent,
        controls: List<ReferenceControlPlan>,
        sections: List<ReferenceFilterSectionPlan>,
        title: ReferenceTextPlan?,
        contentViewport: ReferenceDpRect,
        statusMessage: ReferenceTextPlan?,
        trafficContentBounds: ReferenceDpRect?,
        columnCount: Int,
        maxScrollOffsetDp: Float,
        appliedScrollOffsetDp: Float,
    ): ReferenceFiltersPanelPlan {
        val nodes = controls.mapNotNull { control ->
            val nodeBounds = if (control.kind == ReferenceControlKind.SWITCH) {
                control.bounds.intersection(contentViewport) ?: return@mapNotNull null
            } else {
                control.bounds
            }
            ReferenceAccessibilityNode(
                virtualId = stableVirtualId(control.actionId),
                stableId = control.actionId,
                bounds = nodeBounds,
                role = when (control.kind) {
                    ReferenceControlKind.BUTTON -> ReferenceAccessibilityRole.BUTTON
                    ReferenceControlKind.TAB -> ReferenceAccessibilityRole.TAB
                    ReferenceControlKind.SWITCH -> ReferenceAccessibilityRole.SWITCH
                },
                category = control.category,
                label = control.label,
                selected = control.selected,
                onOff = control.onOff,
                stateDescription = control.stateLabel,
                actionId = control.actionId,
                actions = setOf(ReferenceAccessibilityAction.CLICK),
            )
        }.toMutableList()
        statusMessage?.bounds?.intersection(contentViewport)?.let { statusBounds ->
            val status = requireNotNull(statusMessage)
            val stableId = if (content == ReferenceFiltersPanelContent.MAP_UNAVAILABLE) {
                "status.map.unavailable"
            } else {
                "status.map.empty_verified_catalog"
            }
            nodes += ReferenceAccessibilityNode(
                virtualId = stableVirtualId(stableId),
                stableId = stableId,
                bounds = statusBounds,
                role = ReferenceAccessibilityRole.STATUS,
                category = ReferenceAccessibilityCategory.MAP,
                label = status.text,
                selected = null,
                onOff = null,
                stateDescription = null,
                actionId = null,
                actions = emptySet(),
            )
        }
        if (maxScrollOffsetDp > 0f) {
            val traffic = content == ReferenceFiltersPanelContent.TRAFFIC_SLOT
            val scrollNodeId = if (traffic) TRAFFIC_SCROLL_NODE_ID else MAP_SCROLL_NODE_ID
            nodes += ReferenceAccessibilityNode(
                virtualId = stableVirtualId(scrollNodeId),
                stableId = scrollNodeId,
                bounds = contentViewport,
                role = ReferenceAccessibilityRole.SCROLL_CONTAINER,
                category = if (traffic) {
                    ReferenceAccessibilityCategory.TRAFFIC
                } else {
                    ReferenceAccessibilityCategory.MAP
                },
                label = if (traffic) "Traffic filter controls" else "Map filter controls",
                selected = null,
                onOff = null,
                stateDescription = null,
                actionId = null,
                actions = buildSet {
                    if (appliedScrollOffsetDp < maxScrollOffsetDp) {
                        add(ReferenceAccessibilityAction.SCROLL_FORWARD)
                    }
                    if (appliedScrollOffsetDp > 0f) {
                        add(ReferenceAccessibilityAction.SCROLL_BACKWARD)
                    }
                },
            )
        }
        require(nodes.map(ReferenceAccessibilityNode::virtualId).distinct().size == nodes.size) {
            "accessibility virtual ID collision"
        }
        return ReferenceFiltersPanelPlan(
            content = content,
            controls = controls,
            sections = sections,
            accessibilityNodes = nodes,
            statusMessage = statusMessage,
            trafficContentBounds = trafficContentBounds,
            title = title,
            contentViewport = contentViewport,
            columnCount = columnCount,
            maxScrollOffsetDp = maxScrollOffsetDp,
            appliedScrollOffsetDp = appliedScrollOffsetDp,
        )
    }

    private fun finishStatusPlan(
        input: ReferenceFiltersPanelInput,
        content: ReferenceFiltersPanelContent,
        title: ReferenceTextPlan?,
        headerControls: List<ReferenceControlPlan>,
        contentViewport: ReferenceDpRect,
        statusMessage: ReferenceTextPlan,
    ): ReferenceFiltersPanelPlan {
        val contentHeight = statusMessage.bounds.bottom - contentViewport.top + STATUS_PADDING
        val maxScroll = (contentHeight - contentViewport.height).coerceAtLeast(0f)
        val scroll = input.requestedScrollOffsetDp.coerceIn(0f, maxScroll)
        return finishPlan(
            content = content,
            controls = headerControls,
            sections = emptyList(),
            title = title,
            contentViewport = contentViewport,
            statusMessage = statusMessage.translateY(-scroll),
            trafficContentBounds = null,
            columnCount = 1,
            maxScrollOffsetDp = maxScroll,
            appliedScrollOffsetDp = scroll,
        )
    }

    private fun fitText(
        text: String,
        maxWidth: Float,
        preferredSizeSp: Float,
        minimumSizeSp: Float,
        fontScale: Float,
        fontWeight: Int,
        italic: Boolean,
        maxLines: Int?,
    ): FittedText {
        require(text.isNotBlank()) { "visible text must not be blank" }
        require(maxWidth > 0f) { "visible text width must be positive" }
        var size = preferredSizeSp
        while (size + TEXT_SIZE_EPSILON >= minimumSizeSp) {
            val lines = wrapText(text, maxWidth, size, fontScale, fontWeight, italic)
            if (lines != null && (maxLines == null || lines.size <= maxLines)) {
                val lineHeight = size * fontScale * LINE_HEIGHT_MULTIPLIER
                return FittedText(
                    lines = lines,
                    textSizeSp = size,
                    fontScale = fontScale,
                    fontWeight = fontWeight,
                    italic = italic,
                    lineHeight = lineHeight,
                )
            }
            size -= TEXT_SIZE_STEP
        }
        throw IllegalArgumentException("text cannot fit without clipping: $text")
    }

    private fun wrapText(
        text: String,
        maxWidth: Float,
        textSizeSp: Float,
        fontScale: Float,
        fontWeight: Int,
        italic: Boolean,
    ): List<String>? {
        val words = text.trim().split(Regex("\\s+"))
        if (words.any { measure(it, textSizeSp, fontScale, fontWeight, italic) > maxWidth }) {
            return null
        }
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate, textSizeSp, fontScale, fontWeight, italic) <= maxWidth) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun measure(
        text: String,
        textSizeSp: Float,
        fontScale: Float,
        fontWeight: Int,
        italic: Boolean,
    ): Float {
        val value = textMeasurer.measure(text, textSizeSp, fontScale, fontWeight, italic)
        require(value.isFinite() && value >= 0f) { "text measurement must be finite and nonnegative" }
        return value
    }

    private fun textPlan(
        text: String,
        fitted: FittedText,
        bounds: ReferenceDpRect,
    ): ReferenceTextPlan = ReferenceTextPlan(
        text = text,
        lines = fitted.lines,
        textSizeSp = fitted.textSizeSp,
        bounds = bounds,
        fontScale = fitted.fontScale,
        fontWeight = fitted.fontWeight,
        italic = fitted.italic,
    )

    private fun stableVirtualId(stableId: String): Int {
        var hash = FNV_OFFSET_BASIS
        stableId.forEach { character ->
            hash = hash xor character.code
            hash *= FNV_PRIME
        }
        return (hash and Int.MAX_VALUE).coerceAtLeast(1)
    }

    private fun ReferenceFilterSection.title(): String = when (this) {
        ReferenceFilterSection.LABELS -> "Labels"
        ReferenceFilterSection.OUTLINES -> "Outlines"
    }

    private fun ReferenceFilterSection.masterEnabled(input: ReferenceFiltersPanelInput): Boolean =
        when (this) {
            ReferenceFilterSection.LABELS -> input.labelsMasterEnabled
            ReferenceFilterSection.OUTLINES -> input.outlinesMasterEnabled
        }

    private fun ReferenceFilterSection.masterActionId(): String = when (this) {
        ReferenceFilterSection.LABELS -> ReferenceFiltersPanelActions.LABELS_MASTER
        ReferenceFilterSection.OUTLINES -> ReferenceFiltersPanelActions.OUTLINES_MASTER
    }

    private fun ReferenceFilterSection.accessibilityCategory(): ReferenceAccessibilityCategory =
        when (this) {
            ReferenceFilterSection.LABELS -> ReferenceAccessibilityCategory.LABELS
            ReferenceFilterSection.OUTLINES -> ReferenceAccessibilityCategory.OUTLINES
        }

    private data class PanelChromeGeometry(
        val headerTop: Float,
        val headerBottom: Float,
        val tabsTop: Float,
        val tabsBottom: Float,
        val contentTop: Float,
    ) {
        val headerHeight: Float get() = headerBottom - headerTop
    }

    private data class FittedText(
        val lines: List<String>,
        val textSizeSp: Float,
        val fontScale: Float,
        val fontWeight: Int,
        val italic: Boolean,
        val lineHeight: Float,
    ) {
        val height: Float get() = lines.size * lineHeight
    }

    private data class SectionDraft(
        val section: ReferenceFilterSection,
        val title: String,
        val bounds: ReferenceDpRect,
        val heading: ReferenceTextPlan,
        val masterActionId: String,
        val rowActionIds: List<String>,
        val controls: List<ReferenceControlPlan>,
        val columnIndex: Int,
    )

    private fun SectionDraft.translate(deltaY: Float): SectionDraft = copy(
        bounds = bounds.translateY(deltaY),
        heading = heading.translateY(deltaY),
        controls = controls.map { it.translateY(deltaY) },
    )

    private fun SectionDraft.sectionPlan(): ReferenceFilterSectionPlan =
        ReferenceFilterSectionPlan(
            section = section,
            title = title,
            bounds = bounds,
            masterActionId = masterActionId,
            rowActionIds = rowActionIds,
            heading = heading,
            columnIndex = columnIndex,
        )

    private fun ReferenceDpRect.translateY(deltaY: Float): ReferenceDpRect =
        ReferenceDpRect(left, top + deltaY, right, bottom + deltaY)

    private fun ReferenceTextPlan.translateY(deltaY: Float): ReferenceTextPlan =
        copy(bounds = bounds.translateY(deltaY))

    private fun ReferenceControlPlan.translateY(deltaY: Float): ReferenceControlPlan = copy(
        bounds = bounds.translateY(deltaY),
        labelText = labelText?.translateY(deltaY),
        stateText = stateText?.translateY(deltaY),
        swatchBounds = swatchBounds?.translateY(deltaY),
    )

    private companion object {
        const val MARGIN = 12f
        const val BACK_WIDTH = 96f
        const val HEADER_GAP = 12f
        const val HEADER_TITLE_GAP = 8f
        const val TAB_GAP = 8f
        const val MIN_CONTENT_HEIGHT = 48f
        const val CHROME_NON_HEADER_HEIGHT = 96f
        const val MIN_TARGET_SIZE = 48f
        const val TWO_COLUMN_MIN_WIDTH = 696f
        const val MIN_COLUMN_WIDTH = 320f
        const val COLUMN_GAP = 16f
        const val SECTION_GAP = 16f
        const val CONTROL_GAP = 8f
        const val CONTROL_HORIZONTAL_PADDING = 12f
        const val CONTROL_VERTICAL_PADDING = 8f
        const val LABEL_STATE_GAP = 8f
        const val STATE_WIDTH_FRACTION = 0.25f
        const val MIN_STATE_WIDTH = 72f
        const val MAX_STATE_WIDTH = 120f
        const val SWATCH_WIDTH = 36f
        const val SWATCH_HEIGHT = 24f
        const val SWATCH_GAP = 8f
        const val MIN_TEXT_WIDTH = 48f
        const val MIN_HEADER_LABEL_WIDTH = 48f
        const val HEADING_VERTICAL_PADDING = 8f
        const val STATUS_PADDING = 12f
        const val CONTROL_TEXT_SP = 15f
        const val MIN_NARROW_CONTROL_TEXT_SP = 8f
        const val STATE_TEXT_SP = 13f
        const val MIN_STATE_TEXT_SP = 12f
        const val SECTION_TEXT_SP = 17f
        const val MIN_SECTION_TEXT_SP = 14f
        const val STATUS_TEXT_SP = 15f
        const val MIN_NARROW_STATUS_TEXT_SP = 5f
        const val TITLE_TEXT_SP = 20f
        const val MIN_TITLE_TEXT_SP = 15f
        const val MIN_NARROW_HEADER_TEXT_SP = 6f
        const val LINE_HEIGHT_MULTIPLIER = 1.24f
        const val TEXT_SIZE_STEP = 0.5f
        const val TEXT_SIZE_EPSILON = 0.001f
        const val FNV_OFFSET_BASIS = -0x7ee3623b
        const val FNV_PRIME = 0x01000193
        const val TRAFFIC_SCROLL_NODE_ID = "scroll.traffic.filters"
        const val MAP_SCROLL_NODE_ID = "scroll.map.filters"
    }
}
