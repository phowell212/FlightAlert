package com.flightalert.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceLabelRuntimePresentationBindingTest {
    @Test
    fun rendererAdmitsOnlyCoreVisibleLabelRefsInCompletePriorityFeatureBlocks() {
        val source = renderer_source()
        val drawLabels = source_section(source, "private fun draw_labels", "private fun line_label_candidates")
        val drawReferenceContent =
            source_section(source, "private fun draw_reference_content", "private fun draw_boundary_records")
        val missingContracts = mutableListOf<String>()
        fun expectContract(description: String, satisfied: Boolean) {
            if (!satisfied) missingContracts += description
        }

        expectContract(
            "draw refs carry core_visible",
            source.contains("core_visible = tile.core_visible"),
        )
        val tileLoopIndex = drawLabels.indexOf("for (tile in tiles)")
        val coreOnlyIndex = drawLabels.indexOf("if (!tile.core_visible) continue", tileLoopIndex)
        val recordLoopIndex = drawLabels.indexOf("for (record in tile.tile.labels)", tileLoopIndex)
        expectContract("labels skip padded refs before records", tileLoopIndex >= 0 &&
            coreOnlyIndex > tileLoopIndex && recordLoopIndex > coreOnlyIndex)
        expectContract("boundaries keep padded refs", !drawReferenceContent.contains("core_visible"))

        val admissionPolicyIndex = drawLabels.indexOf("label_record_visible_for_admission(")
        val viewportGuardIndex = drawLabels.indexOf("label_record_intersects_viewport(")
        val recordRefIndex = drawLabels.indexOf("DictionaryLabelRecordRef(")
        expectContract("cheap guards precede record refs", admissionPolicyIndex >= 0 &&
            viewportGuardIndex >= 0 && recordRefIndex > admissionPolicyIndex &&
            recordRefIndex > viewportGuardIndex)
        expectContract(
            "admission uses final rounded paint alpha",
            drawLabels.contains("ReferenceLabelRuntimePresentationPolicy.hasVisiblePaintAlpha("),
        )

        val lowerDrawLabels = drawLabels.lowercase()
        val sortIndex = listOf("sortwith(", "sortedwith(")
            .map(lowerDrawLabels::indexOf)
            .firstOrNull { it >= 0 } ?: -1
        val blockLoopIndex = if (sortIndex >= 0) {
            Regex("\\bwhile\\s*\\(").find(lowerDrawLabels, sortIndex)?.range?.first ?: -1
        } else {
            -1
        }
        val sortContract = if (sortIndex >= 0 && blockLoopIndex > sortIndex) {
            lowerDrawLabels.substring(sortIndex, blockLoopIndex)
        } else {
            ""
        }
        val priorityIndex = sortContract.indexOf("priority")
        val featureIndex = sortContract.indexOf("feature", priorityIndex.coerceAtLeast(0))
        val encounterIndex = sortContract.indexOf("encounter", featureIndex.coerceAtLeast(0))
        expectContract("sort is priority/feature/encounter", priorityIndex >= 0 &&
            featureIndex > priorityIndex && encounterIndex > featureIndex)

        val acceptIndex = drawLabels.indexOf(
            "accept_label_candidates(viewport, label_avoid_rects)",
            blockLoopIndex.coerceAtLeast(0),
        )
        val thresholdIndex = drawLabels.indexOf(
            "if (label_candidates.size >= selection_threshold)",
            blockLoopIndex.coerceAtLeast(0),
        )
        val budgetStopIndex = drawLabels.indexOf(
            "if (accepted_labels.size >= budget) break",
            acceptIndex.coerceAtLeast(0),
        )
        val blockContract = if (blockLoopIndex >= 0 && acceptIndex > blockLoopIndex) {
            lowerDrawLabels.substring(blockLoopIndex, acceptIndex)
        } else {
            ""
        }
        val generatesCandidates = blockContract.contains("line_label_candidates(") ||
            blockContract.contains("point_label_candidate(")
        expectContract("complete blocks feed exponential prefix selection before budget stop", generatesCandidates &&
            Regex("\\b(?:while|do)\\b").findAll(blockContract).count() >= 2 &&
            thresholdIndex > blockLoopIndex && acceptIndex > thresholdIndex &&
            budgetStopIndex > acceptIndex &&
            drawLabels.contains("ReferenceLabelAdmissionPolicy.initial_threshold(budget)") &&
            drawLabels.contains("ReferenceLabelAdmissionPolicy.next_threshold(label_candidates.size)"))
        expectContract(
            "the final incomplete prefix is selected once",
            drawLabels.contains("last_selected_candidate_count != label_candidates.size") &&
                drawLabels.lastIndexOf("accept_label_candidates(viewport, label_avoid_rects)") >
                budgetStopIndex,
        )
        expectContract(
            "temporary record refs are released after admission",
            "label_record_refs.clear()".toRegex(RegexOption.LITERAL)
                .findAll(drawLabels)
                .count() >= 2,
        )

        assertTrue(
            "missing renderer label-admission contracts:\n - " +
                missingContracts.joinToString("\n - "),
            missingContracts.isEmpty(),
        )
    }

    @Test
    fun rendererDelegatesDictionaryLodSelectionToThePurePolicy() {
        val source = renderer_source()
        val dictionaryLodSelection = source_section(
            source,
            "private fun dictionary_tile_zoom",
            "private fun ready_empty_stats",
        )

        assertTrue(
            "dictionary_tile_zoom must delegate to the pure LOD policy",
            dictionaryLodSelection.contains(
                "ReferenceDictionaryLodPolicy.select(viewport_zoom, zooms)",
            ),
        )
    }

    @Test
    fun binaryLabelsCarrySemanticInputsIntoThePhonePresentationPolicy() {
        val source = renderer_source()
        val binary_mapping = source_section(
            source,
            "private fun parse_binary_tile_payload",
            "private fun binary_geometry",
        )

        assertTrue(binary_mapping.contains("ReferenceLabelRuntimePresentationPolicy.presentationSubtype("))
        assertTrue(binary_mapping.contains("presentation_subtype = presentation_subtype"))
        assertTrue(binary_mapping.contains("source_feature_id = model.featureId"))
        assertTrue(binary_mapping.contains("source_kind = binary_source_kind(model.subtype)"))
        assertTrue(binary_mapping.contains("prominence_tier = model.prominenceTier"))
        assertTrue(
            binary_mapping.contains(
                "complete_geometry_measure_bucket = model.completeGeometryMeasureBucket",
            ),
        )
        assertTrue(binary_mapping.contains("ReferenceLabelRuntimePresentationPolicy.typography("))
        assertTrue(binary_mapping.contains("model.completeGeometryMeasureBucket"))
        assertTrue(binary_mapping.contains("runtime_typography = runtime_typography"))
        assertTrue(source.contains("ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli("))
        assertTrue(source.contains("ReferencePresentationPolicy.centizoom(zoom)"))
    }

    @Test
    fun resolvedPhoneTypographyDrivesRealPaintAndHaloInputs() {
        val source = renderer_source()
        val label_style = source_section(
            source,
            "private fun label_style_for",
            "private fun label_style_template_for",
        )

        assertTrue(label_style.contains("runtime_typography?.textSizeMilliSp"))
        assertTrue(label_style.contains("runtime_visibility_alpha_milli"))
        assertTrue(label_style.contains("runtime_typography?.haloWidthMilliEm"))
        assertTrue(label_style.contains("runtime_typography?.fontWeight"))
        assertTrue(label_style.contains("runtime_typography?.italic"))
        assertTrue(label_style.contains("runtime_typography?.letterSpacingMilliEm"))
        assertTrue(label_style.contains("alpha = scaled_alpha(record.alpha)"))
        assertTrue(label_style.contains("halo_alpha = scaled_alpha(record.halo_alpha)"))
        assertTrue(label_style.contains("runtime_typography == null"))
        assertTrue(label_style.contains("record.halo_width_dp"))
        assertTrue(label_style.contains("else {\n                0f"))
        assertTrue(source.contains("Typeface.create(Typeface.DEFAULT, font_weight, italic)"))
        assertTrue(source.contains("text_size * style.halo_width_milli_em / 1_000f"))
        assertFalse(source.contains("text_size * 0.22f"))
        val line_candidates = source_section(
            source,
            "private fun line_label_candidates",
            "private fun point_label_candidate",
        )
        assertTrue(
            line_candidates.contains(
                "label_halo_width_px(fitted_style, fitted_style.text_size)",
            ),
        )
        val point_candidates = source_section(
            source,
            "private fun point_label_candidate",
            "private fun accept_label_candidates",
        )
        assertTrue(point_candidates.contains("label_halo_width_px(style, style.text_size)"))
    }

    @Test
    fun sharedEnglishLineStaysSmallerItalicAndNormalWeight() {
        val source = renderer_source()
        val measurement_paths = source_section(
            source,
            "private fun line_label_candidates",
            "private fun accept_label_candidates",
        )
        val drawing_paths = source_section(
            source,
            "private fun draw_label_candidate",
            "private fun draw_text_on_path",
        )

        val sourced = SourcedMapTextPolicy.create(
            primary = "東京",
            primarySourceFieldId = 1uL,
            declaredEnglish = "Tokyo",
            englishSourceFieldId = 2uL,
        )
        val presentation = SourcedMapTextPresentation.plan(sourced, 20f)

        assertTrue(source.contains("const val ENGLISH_FONT_WEIGHT = 400"))
        assertTrue(source.contains("const val ENGLISH_ITALIC = true"))
        assertTrue(source.contains("english.textSize"))
        assertTrue(measurement_paths.contains("ENGLISH_FONT_WEIGHT"))
        assertTrue(measurement_paths.contains("ENGLISH_ITALIC"))
        assertTrue(drawing_paths.contains("ENGLISH_FONT_WEIGHT"))
        assertTrue(drawing_paths.contains("ENGLISH_ITALIC"))
        assertEquals(15.2f, presentation.english!!.textSize, 0.001f)
        assertTrue(presentation.english.forceItalic)
        assertFalse(source.contains("italic_typeface_style"))
    }

    @Test
    fun waterFeatureRepeatsAreSeparatedForThePhoneViewport() {
        val source = renderer_source()

        assertTrue(source.contains("WATER_LINE_LABEL_REPEAT_DISTANCE_PX = 520f"))
        assertTrue(source.contains("waterRepeatDistancePx = WATER_LINE_LABEL_REPEAT_DISTANCE_PX"))
        assertTrue(source.contains("featureId = record.source_feature_id ?: candidate_id"))
        assertTrue(source.contains("singleWaterLabelPerFeature ="))
        assertTrue(source.contains("viewport.zoom <= WATER_SINGLE_LABEL_MAX_ZOOM"))
    }

    @Test
    fun repeatedBinaryTileMembershipsAreDeduplicatedOnlyAfterPathPlanningSucceeds() {
        val source = renderer_source()
        val drawLabels = source_section(
            source,
            "private fun draw_labels",
            "private fun line_label_candidates",
        )

        assertTrue(source.contains("planned_label_candidate_ids"))
        assertTrue(drawLabels.contains("planned_label_candidate_ids.clear()"))
        assertTrue(drawLabels.contains("record.candidate_id in planned_label_candidate_ids"))
        assertTrue(drawLabels.contains("val planned_candidates = line_label_candidates("))
        assertTrue(drawLabels.contains("if (planned_candidates.isNotEmpty())"))
        assertTrue(drawLabels.contains("record.candidate_id?.let(planned_label_candidate_ids::add)"))
        assertTrue(
            drawLabels.indexOf("val planned_candidates = line_label_candidates(") <
                drawLabels.indexOf("record.candidate_id?.let(planned_label_candidate_ids::add)"),
        )
    }

    @Test
    fun duplicateLineMembershipCannotSkipTheRecordCursorAdvance() {
        val source = renderer_source()
        val drawLabels = source_section(
            source,
            "private fun draw_labels",
            "private fun line_label_candidates",
        )
        val duplicateGate = drawLabels.indexOf("record.candidate_id in planned_label_candidate_ids")
        val cursorAdvance = drawLabels.indexOf("record_index++", startIndex = duplicateGate)

        assertTrue("missing duplicate line-membership gate", duplicateGate >= 0)
        assertTrue("missing record cursor advance after duplicate gate", cursorAdvance > duplicateGate)
        assertFalse(
            "duplicate line membership must not continue before advancing the record cursor",
            drawLabels.substring(duplicateGate, cursorAdvance).contains("continue"),
        )
    }

    @Test
    fun lineGeometryIsPreparedOnceBeforeTextFitAttempts() {
        val source = renderer_source()
        val lineCandidates = source_section(
            source,
            "private fun line_label_candidates",
            "private fun point_label_candidate",
        )
        val preparation = lineCandidates.indexOf("ReferenceVisiblePathProjector.prepare(")
        val attemptLoop = lineCandidates.indexOf("for (attempt_index in 0 until")

        assertTrue("missing visible-path preparation", preparation >= 0)
        assertTrue("visible path must be prepared before text-fit attempts", preparation < attemptLoop)
        assertFalse(lineCandidates.contains("List(ring.point_count)"))
        assertTrue(lineCandidates.contains("ReferencePathLabelPlanner.planPrepared("))
    }

    @Test
    fun rawPathProjectionMaterializesPointsOnlyAfterAVisibleSegmentIsFound() {
        val source = File(
            "app/src/main/java/com/flightalert/map/ReferenceVisiblePathProjector.kt",
        ).readText()
        val preparePart = source_section(
            source,
            "private fun preparePart",
            "private fun prepareProjectedPart",
        )
        val visibleClip = preparePart.indexOf(
            "if (length * (clip.endFraction - clip.startFraction) > epsilon)",
        )
        val firstPointMaterialization = preparePart.indexOf("ReferencePathLabelPoint(")

        assertTrue("missing retained visible-segment branch", visibleClip >= 0)
        assertTrue(
            "raw vertices must stay primitive until a visible segment is retained",
            firstPointMaterialization > visibleClip,
        )
        assertFalse("raw projection must not allocate through a per-vertex point helper", preparePart.contains("fun point("))
    }

    @Test
    fun curvedSubpathExtractionDoesNotRebuildPreparedSegmentsPerPlacement() {
        val source = File(
            "app/src/main/java/com/flightalert/map/ReferencePathLabelPlanner.kt",
        ).readText()
        val centeredSubpath = source_section(
            source,
            "private fun centeredSubpath",
            "private fun addCapsuleSupportIntervals",
        )

        assertTrue(centeredSubpath.contains("fullLength: Double"))
        assertFalse(centeredSubpath.contains("completePartGeometry("))
        assertFalse(centeredSubpath.contains("ReferencePreparedPathSegment("))
    }

    @Test
    fun curvedFailoverQueueBuildsExactBoundariesWithoutCollectionPipelines() {
        val source = File(
            "app/src/main/java/com/flightalert/map/ReferencePathLabelPlanner.kt",
        ).readText()
        val queueBuilder = source_section(
            source,
            "private fun createFailoverQueue",
            "fun plan(request:",
        )

        assertTrue(queueBuilder.contains("LongArray("))
        assertTrue(queueBuilder.contains("java.util.Arrays.sort(boundaries)"))
        assertFalse(queueBuilder.contains("fastSpans.map"))
        assertFalse(queueBuilder.contains("listOf(lowerQ8, upperQ8)"))
        assertFalse(queueBuilder.contains(".distinct()"))
        assertFalse(queueBuilder.contains(".sorted()"))
    }

    @Test
    fun curvedClearanceChecksSegmentsAndRectangleCornersWithoutTemporaryCollections() {
        val source = File(
            "app/src/main/java/com/flightalert/map/ReferencePathLabelPlanner.kt",
        ).readText()
        val clearance = source_section(
            source,
            "private fun minimumClearance",
            "private fun placement(",
        )

        assertTrue(clearance.contains("for (segmentIndex in 0 until path.lastIndex)"))
        assertTrue(clearance.contains("rect.left, rect.top"))
        assertFalse(clearance.contains("zipWithNext"))
        assertFalse(clearance.contains("val corners = listOf"))
        assertFalse(clearance.contains("ReferencePathLabelPoint(rect."))
    }

    @Test
    fun sourceSupportQueriesUseThePreparedExactSegmentIndex() {
        val source = File(
            "app/src/main/java/com/flightalert/map/ReferencePathLabelPlanner.kt",
        ).readText()
        val sourceSupport = source_section(
            source,
            "private fun segmentHasSourceSupport",
            "private fun simplifyPresentationPath",
        )

        assertTrue(sourceSupport.contains("part.supportIndex.queryInto("))
        assertTrue(sourceSupport.contains("part.supportIndex.matchAt("))
        assertTrue(sourceSupport.contains("workspace.segmentQueryScratch"))
        assertFalse(sourceSupport.contains("part.supportIndex.query("))
        assertFalse(sourceSupport.contains("sourceStart = ReferencePathLabelPoint("))
        assertFalse(sourceSupport.contains("sourceEnd = ReferencePathLabelPoint("))
        assertFalse(sourceSupport.contains("for (index in 0 until sourcePath.lastIndex)"))
    }

    @Test
    fun sourceSupportMergesIntervalsOnlineAndStopsAtExactWholeCoverage() {
        val source = File(
            "app/src/main/java/com/flightalert/map/ReferencePathLabelPlanner.kt",
        ).readText()
        val sourceSupport = source_section(
            source,
            "private fun segmentHasSourceSupport",
            "private fun simplifyPresentationPath",
        )

        assertTrue(sourceSupport.contains("if (workspace.coversWholeOnline()) return true"))
        assertTrue(sourceSupport.contains("return workspace.coversWholeAfterAll()"))
        assertFalse(sourceSupport.contains("workspace.sort()"))
    }

    @Test
    fun tangentFallbackUsesPrimitiveChecksBeforeMaterializingAnAcceptedPath() {
        val source = File(
            "app/src/main/java/com/flightalert/map/ReferencePathLabelPlanner.kt",
        ).readText()
        val tangent = source_section(
            source,
            "private fun tangentPlacements",
            "private fun tangentOffsets",
        )
        val clearance = tangent.indexOf("minimumClearanceForSegment(")
        val support = tangent.indexOf("segmentHasSourceSupport(")
        val materialization = tangent.indexOf("val path = listOf(")

        assertTrue("tangent clearance must be checked before source support", clearance >= 0 && clearance < support)
        assertTrue("accepted tangent path must be materialized only after primitive checks", support < materialization)
        assertTrue(tangent.contains("ReferenceTangentViewportSupport.isGuaranteed(request)"))
    }

    @Test
    fun sourceSupportPrunesTreeBranchesByTheExactSourceOrdinalWindow() {
        val planner = File(
            "app/src/main/java/com/flightalert/map/ReferencePathLabelPlanner.kt",
        ).readText()
        val sourceSupport = source_section(
            planner,
            "private fun segmentHasSourceSupport",
            "private fun simplifyPresentationPath",
        )
        val index = File(
            "app/src/main/java/com/flightalert/map/ReferenceScreenSegmentAabbIndex.kt",
        ).readText()

        assertTrue(sourceSupport.contains("ReferenceSourceSegmentOrdinalWindow.startInclusive("))
        assertTrue(sourceSupport.contains("ReferenceSourceSegmentOrdinalWindow.endExclusive("))
        assertTrue(sourceSupport.contains("sourceOrdinalStartInclusive = sourceOrdinalStartInclusive"))
        assertTrue(sourceSupport.contains("sourceOrdinalEndExclusive = sourceOrdinalEndExclusive"))
        assertTrue(index.contains("minimumSourceOrdinal"))
        assertTrue(index.contains("maximumSourceOrdinal"))
        val ordinalPrune = index.indexOf("node.maximumSourceOrdinal < sourceOrdinalStartInclusive")
        val spatialPrune = index.indexOf("!node.bounds.intersects")
        assertTrue("ordinal-disjoint nodes must be pruned before spatial work", ordinalPrune >= 0)
        assertTrue("ordinal pruning must precede spatial pruning", ordinalPrune < spatialPrune)
    }

    @Test
    fun curvedOffsetsRejectClearanceBeforeRunningExactSourceSupport() {
        val source = File(
            "app/src/main/java/com/flightalert/map/ReferencePathLabelPlanner.kt",
        ).readText()
        val curved = source_section(
            source,
            "private fun curvedPlacementAtCenter",
            "private fun curvedNormalOffsets",
        )
        val clearance = curved.indexOf("minimumClearance(")
        val support = curved.indexOf("pathHasSourceSupport(")

        assertTrue("curved clearance must be checked before source support", clearance >= 0)
        assertTrue("curved clearance must precede source support", clearance < support)
    }

    @Test
    fun tangentViewportGuaranteeIsHoistedAndAccountsForClearanceTolerance() {
        val source = File(
            "app/src/main/java/com/flightalert/map/ReferencePathLabelPlanner.kt",
        ).readText()
        val tangent = source_section(
            source,
            "private fun tangentPlacements",
            "private fun tangentOffsets",
        )
        val guarantee = source_section(
            source,
            "internal object ReferenceTangentViewportSupport",
            "internal object ReferenceLocalPathBend",
        )

        val hoisted = tangent.indexOf("val sourceSupportGuaranteedByViewport =")
        val centerLoop = tangent.indexOf("centerLoop@ for")
        assertTrue("viewport guarantee must be computed once", hoisted >= 0 && hoisted < centerLoop)
        assertTrue(guarantee.contains("request.edgeClearancePx - Epsilon"))
        assertTrue(guarantee.contains("request.maximumTangentSourceDistancePx >= hypot"))
        assertFalse(guarantee.contains("request.maximumTangentSourceDistancePx + Epsilon"))
    }

    @Test
    fun waterPathsRetrySmallerReadableTextButNeverFloatAwayFromTheirSource() {
        val source = renderer_source()
        val line_candidates = source_section(
            source,
            "private fun line_label_candidates",
            "private fun point_label_candidate",
        )

        assertTrue(line_candidates.contains("ReferenceLineLabelFitPolicy.attemptCount(is_water)"))
        assertTrue(line_candidates.contains("ReferenceLineLabelFitPolicy.textSizePx("))
        assertTrue(line_candidates.contains("ReferenceLineLabelFitPolicy.acceptAttempt("))
        assertTrue(line_candidates.contains("tangent_fallback_request"))
        assertTrue(line_candidates.contains("allowTangentFallback = !is_water"))
        assertTrue(line_candidates.contains("allowCurvedPlacement = false"))
        assertTrue(line_candidates.contains("MIN_WATER_LINE_TEXT_SIZE_SP * label_text_scale"))
        assertTrue(line_candidates.contains("maximumTangentOffsetPx = if (is_water)"))
        assertTrue(line_candidates.contains("maximumTangentSourceDistancePx = if (is_water)"))
        assertTrue(line_candidates.contains("maximumCurvedSourceDistancePx = if (is_water)"))
        assertTrue(
            line_candidates.contains(
                "collision_radius * WATER_CURVED_SOURCE_DISTANCE_FRACTION",
            ),
        )
        assertTrue(source.contains("WATER_CURVED_SOURCE_DISTANCE_FRACTION = 1f"))
        assertTrue(line_candidates.contains("collision_radius.toDouble()"))
        assertTrue(line_candidates.contains("viewport_diagonal"))
    }

    @Test
    fun retainedFrameStoresTheRevisionCapturedBeforeItsTileSnapshot() {
        val source = renderer_source()
        val draw = source_section(source, "    fun draw(", "    fun content_revision()")
        val snapshotRevision =
            draw.indexOf("val draw_content_revision = content_revision.get()")
        val tileSnapshot = draw.indexOf("for (tile in visible_tiles)")
        val renderCall = draw.indexOf("val rendered = render_retained_frame(")

        assertTrue("revision must be captured before cached tiles", snapshotRevision >= 0)
        assertTrue("tile snapshot must follow revision capture", tileSnapshot > snapshotRevision)
        assertTrue("retained render must follow the tile snapshot", renderCall > tileSnapshot)
        assertTrue(
            "retained render must receive the tile snapshot revision",
            draw.substring(renderCall).contains(
                "content_revision_snapshot = draw_content_revision",
            ),
        )

        val render = source_section(
            source,
            "private fun render_retained_frame",
            "private fun draw_live_labels",
        )
        assertTrue(render.contains("content_revision_snapshot: Long"))
        assertTrue(render.contains("content_revision = content_revision_snapshot"))
        assertFalse(
            "a revision read after drawing can bless a stale retained bitmap",
            render.contains("content_revision = content_revision.get()"),
        )
    }

    @Test
    fun boundaryFragmentsAreBatchedBeforeCanvasStrokes() {
        val source = renderer_source()
        val content = source_section(
            source,
            "private fun draw_reference_content",
            "private fun draw_labels",
        )
        assertTrue(content.contains("draw_boundary_records("))

        val batches = source_section(
            source,
            "private fun draw_boundary_records",
            "private fun draw_labels",
        )
        assertTrue(batches.contains("BoundaryPathBatchKey("))
        assertTrue(batches.contains("append_ring_path("))
        assertEquals(2, Regex("canvas\\.drawPath\\(").findAll(batches).count())

        val append = source_section(
            source,
            "private fun append_ring_path",
            "private fun draw_labels",
        )
        val pointLoop = source_section(
            append,
            "for (index in 0 until ring.point_count)",
            "return true",
        )
        assertFalse(pointLoop.contains("project_point("))
    }

    @Test
    fun motionReusesTheSettledLabelLayoutInsteadOfReplanningIt() {
        val source = renderer_source()
        val live = source_section(
            source,
            "private fun draw_live_labels",
            "private fun draw_retained_labels",
        )
        val reuse = live.indexOf("if (reuse_only")
        val planning = live.indexOf("draw_labels(")
        assertTrue("motion reuse must be checked before label planning", reuse >= 0)
        assertTrue("label planning must remain the settled fallback", planning > reuse)
        assertTrue(live.contains("retained_labels"))

        val retained = source_section(
            source,
            "private fun draw_retained_labels",
            "private fun draw_retained_label_candidate",
        )
        assertFalse("motion drawing must not run label selection", retained.contains("draw_labels("))
        assertTrue(retained.contains("zoom_scale"))
    }

    @Test
    fun motionDoesNotShrinkAnOldLabelLayoutAcrossWideZoomChanges() {
        val source = renderer_source()
        val live = source_section(
            source,
            "private fun draw_live_labels",
            "private fun draw_retained_labels",
        )

        assertTrue(live.contains("MAX_RETAINED_LABEL_ZOOM_DELTA"))
        assertTrue(live.contains("kotlin.math.abs(retained.viewport.zoom - viewport.zoom)"))
        assertTrue(source.contains("const val MAX_RETAINED_LABEL_ZOOM_DELTA = 1.25"))
    }

    @Test
    fun settledIncompleteWideViewportsPlanLabelsOnceThenRefreshWhenComplete() {
        val source = renderer_source()
        val draw = source_section(source, "    fun draw(", "    fun content_revision()")
        val incompleteLabels = draw.indexOf("complete = false")
        val settledRender = draw.indexOf("if (ready && !interaction_active)")
        val directRender = draw.indexOf("val counts = draw_reference_content(")

        assertTrue("settled incomplete wide views must publish a partial label layout", incompleteLabels >= 0)
        assertTrue("partial labels must not trigger a partial border render", incompleteLabels < settledRender)
        assertTrue("partial labels must return before direct reference drawing", incompleteLabels < directRender)
        assertTrue(draw.contains("val plan_partial_point_labels"))
        assertTrue("the first loaded core tile must be allowed to publish labels", draw.contains("core_tiles_loaded > 0"))
        assertTrue("labels must not wait for half the viewport tiles", !draw.contains("core_tiles_loaded * 2 >= core_tiles_total"))

        val live = source_section(
            source,
            "private fun draw_live_labels",
            "private fun draw_retained_labels",
        )
        assertTrue("an empty early snapshot must not freeze labels until full readiness", live.contains("if (complete || count > 0)"))

        val retained = source_section(
            source,
            "private data class RetainedReferenceLabels",
            "private data class RetainedReferenceFade",
        )
        assertTrue(retained.contains("val complete: Boolean"))
        assertTrue(retained.contains("complete: Boolean"))
        assertTrue("a partial layout must be invalidated once every core tile is ready", retained.contains("!complete || this.complete"))
    }

    @Test
    fun wideViewportsDoNotRunTheExpensivePathLabelPlanner() {
        val source = renderer_source()
        val admission = source_section(
            source,
            "private fun label_record_visible_for_admission",
            "private fun label_record_intersects_viewport",
        )

        assertTrue(admission.contains("record.line_label && zoom < MIN_PATH_LABEL_ZOOM"))
        assertTrue(source.contains("const val MIN_PATH_LABEL_ZOOM = 10.0"))
    }

    private fun renderer_source(): String = File(
        "app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt",
    ).readText().replace("\r\n", "\n").replace('\r', '\n')

    private fun source_section(source: String, start: String, end: String): String {
        val start_index = source.indexOf(start)
        val end_index = source.indexOf(end, startIndex = start_index + start.length)
        assertTrue("missing section start: $start", start_index >= 0)
        assertTrue("missing section end: $end", end_index > start_index)
        return source.substring(start_index, end_index)
    }

}
