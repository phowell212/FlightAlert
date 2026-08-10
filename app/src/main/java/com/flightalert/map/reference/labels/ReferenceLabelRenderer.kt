@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.withSave
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import com.flightalert.map.SourcedMapTextPresentation
import com.flightalert.map.SourcedMapTextPresentationPlan
import com.flightalert.map.Viewport
import com.flightalert.map.reference.ProminenceTier
import com.flightalert.map.reference.ReferencePresentationPolicy
import com.flightalert.map.reference.borders.ReferenceBoundaryOccurrenceSelector
import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate
import com.flightalert.map.reference.dictionary.DictionaryLabelRecord
import com.flightalert.map.reference.dictionary.DictionaryLabelRecordRef
import com.flightalert.map.reference.dictionary.DictionaryLabelStyle
import com.flightalert.map.reference.dictionary.DictionaryScreenPoint
import com.flightalert.map.reference.dictionary.DictionaryTileDrawRef
import com.flightalert.map.reference.dictionary.ReferenceDictionaryLayerGroups
import com.flightalert.map.reference.dictionary.ReferenceFilterMask
import com.flightalert.map.reference.dictionary.scaled_alpha_byte

internal val ALWAYS_KEEP_LABEL_PLAN: () -> Boolean = { true }

internal class ReferenceLabelRenderer(
    private val text_paint: Paint,
    sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val boundary_density: Float,
) {
    private val label_scaled_density = sp(1f)
    private val line_label_path = Path()
    private val path_measure = PathMeasure()
    val ui_workspace = LabelPlanningWorkspace(text_paint)
    val retained_workspace = LabelPlanningWorkspace(
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG),
    )

    fun draw_labels(
        canvas: Canvas,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        label_text_scale: Float,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
    ): Int {
        plan_labels(
            workspace = ui_workspace,
            viewport = viewport,
            tiles = tiles,
            label_text_scale = label_text_scale,
            groups = groups,
            filter_mask = filter_mask,
            label_avoid_rects = label_avoid_rects,
        )
        draw_planned_labels(canvas, ui_workspace)
        return ui_workspace.acceptedLabels.size
    }

    fun prepare_label_plan(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        label_text_scale: Float,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        include_buffer_tiles: Boolean = false,
        excluded_rect: ReferenceScreenRect? = null,
        keep_planning: () -> Boolean,
    ): PreparedLabelPlan? {
        if (!keep_planning()) return null
        workspace.recordRefs.clear()
        var encounter_order = 0
        for (tile in tiles) {
            if (!include_buffer_tiles && !tile.core_visible) continue
            for (record in tile.tile.labels) {
                if (encounter_order and 63 == 0 && !keep_planning()) return null
                if (!label_record_visible_for_admission(record, viewport.zoom, groups, filter_mask)) {
                    continue
                }
                if (!label_record_intersects_viewport(viewport, tile, record)) continue
                workspace.recordRefs += DictionaryLabelRecordRef(
                    tile = tile,
                    record = record,
                    layout_priority = reference_low_zoom_label_priority(
                        record.priority,
                        record.low_zoom_country_rank,
                        viewport.zoom,
                    ),
                    layout_feature_id = layout_feature_id(record),
                    encounter_order = encounter_order++,
                )
            }
        }
        workspace.recordRefs.sortWith(
            compareBy<DictionaryLabelRecordRef> { it.layout_priority }
                .thenBy { it.layout_feature_id }
                .thenBy { it.encounter_order },
        )
        val prepared_records = ArrayList<PreparedLabelRecord>(workspace.recordRefs.size)
        for (record_ref in workspace.recordRefs) {
            if (!keep_planning()) return null
            val record = record_ref.record
            val rendered_world_copy = rendered_label_world_copy(record_ref.tile, record)
            val candidate_key = record.candidate_id?.let { candidate_id ->
                ReferenceLabelCandidateKey(candidate_id, rendered_world_copy)
            }
            val style = label_style_for(record, label_text_scale, viewport.zoom)
            val has_line_geometry = record.line_label &&
                record.geometry?.rings?.any { ring -> ring.point_count >= 2 } == true
            val line = if (has_line_geometry) {
                prepare_line_label(
                    workspace = workspace,
                    viewport = viewport,
                    tile = record_ref.tile,
                    record = record,
                    style = style,
                    label_text_scale = label_text_scale,
                    rendered_world_copy = rendered_world_copy,
                    keep_planning = keep_planning,
                )
            } else {
                null
            }
            val point = when {
                !record.line_label -> point_label_candidate(
                    workspace,
                    viewport,
                    record_ref.tile,
                    record,
                    style,
                    rendered_world_copy,
                )
                line != null && record.candidate_id == null && !line.isWater ->
                    point_label_candidate(
                        workspace,
                        viewport,
                        record_ref.tile,
                        record,
                        style,
                        rendered_world_copy,
                    )
                else -> null
            }
            prepared_records += PreparedLabelRecord(
                recordRef = record_ref,
                renderedWorldCopy = rendered_world_copy,
                candidateKey = candidate_key,
                line = line,
                point = point,
            )
        }
        workspace.recordRefs.clear()
        if (!keep_planning()) return null
        return PreparedLabelPlan(
            records = prepared_records,
            excludedRect = excluded_rect,
        )
    }

    fun resolve_label_plan(
        workspace: LabelPlanningWorkspace,
        plan: PreparedLabelPlan,
        viewport: Viewport,
        label_avoid_rects: List<ReferenceScreenRect>,
        fixed_candidates: List<DictionaryLabelCandidate> = emptyList(),
        preferred_occurrences: Set<ReferenceLabelOccurrenceId> = emptySet(),
        preferred_candidate_keys: Set<ReferenceLabelCandidateKey> = emptySet(),
        label_budget_override: Int? = null,
        protected_area_budget_override: Int? = null,
        keep_planning: () -> Boolean,
    ): Boolean {
        if (!keep_planning()) return abandon_label_plan(workspace)
        workspace.candidates.clear()
        workspace.plannedCandidateIds.clear()
        workspace.acceptedLabels.clear()
        var preferred_seeds: List<DictionaryLabelCandidate> = emptyList()
        val selection_scratch: ArrayList<DictionaryLabelCandidate>?
        if (preferred_occurrences.isEmpty()) {
            selection_scratch = null
        } else {
            val seed_candidates = ArrayList<DictionaryLabelCandidate>()
            val seed_candidate_keys = HashSet<ReferenceLabelCandidateKey>()
            val prepass_complete = ReferenceLabelAdmissionPolicy.visitPreferredSeedRecords(
                records = plan.records,
                preferredLookupKeys = preferred_candidate_keys,
                lookupKey = { prepared ->
                    ReferenceLabelCandidateKey(
                        layout_candidate_id(prepared.recordRef.record),
                        prepared.renderedWorldCopy,
                    )
                },
                recordCandidateId = { prepared -> prepared.recordRef.record.candidate_id },
                dedupeKey = { candidate_id, lookup_key ->
                    ReferenceLabelCandidateKey(candidate_id, lookup_key.rendered_world_copy)
                },
                visit = { prepared, _, dedupe_key ->
                    keep_planning() && append_prepared_label_candidates(
                        prepared = prepared,
                        label_avoid_rects = label_avoid_rects,
                        excluded_rect = plan.excludedRect,
                        candidate_key = dedupe_key,
                        planned_candidate_ids = seed_candidate_keys,
                        output = seed_candidates,
                        keep_planning = keep_planning,
                    )
                },
            )
            if (!prepass_complete) return abandon_label_plan(workspace)
            val retained_seeds = ArrayList<DictionaryLabelCandidate>()
            ReferenceLabelAdmissionPolicy.retainPreferredSeeds(
                candidates = seed_candidates,
                preferredOccurrences = preferred_occurrences,
                occurrenceId = DictionaryLabelCandidate::occurrenceId,
                output = retained_seeds,
            )
            preferred_seeds = retained_seeds
            selection_scratch = ArrayList()
        }
        val budget = label_budget_override ?: label_budget(viewport)
        var selection_threshold = ReferenceLabelAdmissionPolicy.initial_threshold(budget)
        var last_selected_candidate_count = -1
        var record_index = 0
        while (record_index < plan.records.size) {
            if (!keep_planning()) return abandon_label_plan(workspace)
            val first_ref = plan.records[record_index].recordRef
            val block_priority = first_ref.layout_priority
            val block_feature_id = first_ref.layout_feature_id
            do {
                val prepared = plan.records[record_index]
                if (!append_prepared_label_candidates(
                        prepared = prepared,
                        label_avoid_rects = label_avoid_rects,
                        excluded_rect = plan.excludedRect,
                        candidate_key = prepared.candidateKey,
                        planned_candidate_ids = workspace.plannedCandidateIds,
                        output = workspace.candidates,
                        keep_planning = keep_planning,
                    )
                ) {
                    return abandon_label_plan(workspace)
                }
                record_index++
            } while (
                record_index < plan.records.size &&
                    plan.records[record_index].recordRef.layout_priority == block_priority &&
                    plan.records[record_index].recordRef.layout_feature_id == block_feature_id
            )
            if (workspace.candidates.size >= selection_threshold) {
                accept_label_candidates(
                    workspace = workspace,
                    viewport = viewport,
                    label_avoid_rects = label_avoid_rects,
                    fixed_candidates = fixed_candidates,
                    preferred_occurrences = preferred_occurrences,
                    preferred_seeds = preferred_seeds,
                    priority_frontier = block_priority,
                    selection_scratch = selection_scratch,
                    label_budget = budget,
                    protected_area_budget = protected_area_budget_override
                        ?: protected_area_label_budget(viewport),
                )
                last_selected_candidate_count = workspace.candidates.size
                if (workspace.acceptedLabels.size >= budget) break
                selection_threshold =
                    ReferenceLabelAdmissionPolicy.next_threshold(workspace.candidates.size)
            }
        }
        if (
            workspace.acceptedLabels.size < budget &&
            last_selected_candidate_count != workspace.candidates.size
        ) {
            accept_label_candidates(
                workspace = workspace,
                viewport = viewport,
                label_avoid_rects = label_avoid_rects,
                fixed_candidates = fixed_candidates,
                preferred_occurrences = preferred_occurrences,
                preferred_seeds = preferred_seeds,
                priority_frontier =
                    plan.records.getOrNull(record_index - 1)?.recordRef?.layout_priority,
                selection_scratch = selection_scratch,
                label_budget = budget,
                protected_area_budget = protected_area_budget_override
                    ?: protected_area_label_budget(viewport),
            )
        }
        if (!keep_planning()) return abandon_label_plan(workspace)
        return true
    }

    private fun append_prepared_label_candidates(
        prepared: PreparedLabelRecord,
        label_avoid_rects: List<ReferenceScreenRect>,
        excluded_rect: ReferenceScreenRect?,
        candidate_key: ReferenceLabelCandidateKey?,
        planned_candidate_ids: MutableSet<ReferenceLabelCandidateKey>,
        output: MutableList<DictionaryLabelCandidate>,
        keep_planning: () -> Boolean,
    ): Boolean {
        if (!keep_planning()) return false
        if (candidate_key != null && candidate_key in planned_candidate_ids) {
            return true
        }
        val line_candidates = prepared.line?.let { line ->
            resolve_line_label(line, label_avoid_rects)
        }.orEmpty()
        if (!keep_planning()) return false
        val candidates = line_candidates.ifEmpty { listOfNotNull(prepared.point) }
        var added = false
        for (candidate in candidates) {
            if (
                excluded_rect != null &&
                !label_is_outside(candidate.collisionShape.bounds, excluded_rect)
            ) {
                continue
            }
            output += candidate
            added = true
        }
        if (added) candidate_key?.let(planned_candidate_ids::add)
        return true
    }

    fun plan_labels(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        label_text_scale: Float,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        include_buffer_tiles: Boolean = false,
        fixed_candidates: List<DictionaryLabelCandidate> = emptyList(),
        preferred_occurrences: Set<ReferenceLabelOccurrenceId> = emptySet(),
        preferred_candidate_keys: Set<ReferenceLabelCandidateKey> = emptySet(),
        excluded_rect: ReferenceScreenRect? = null,
        label_budget_override: Int? = null,
        protected_area_budget_override: Int? = null,
        keep_planning: () -> Boolean = ALWAYS_KEEP_LABEL_PLAN,
    ): Boolean {
        if (!keep_planning()) return abandon_label_plan(workspace)
        workspace.recordRefs.clear()
        workspace.candidates.clear()
        workspace.plannedCandidateIds.clear()
        workspace.acceptedLabels.clear()
        var encounter_order = 0
        for (tile in tiles) {
            if (!include_buffer_tiles && !tile.core_visible) continue
            for (record in tile.tile.labels) {
                if (encounter_order and 63 == 0 && !keep_planning()) {
                    return abandon_label_plan(workspace)
                }
                if (!label_record_visible_for_admission(record, viewport.zoom, groups, filter_mask)) {
                    continue
                }
                if (!label_record_intersects_viewport(viewport, tile, record)) continue
                workspace.recordRefs += DictionaryLabelRecordRef(
                    tile = tile,
                    record = record,
                    layout_priority = reference_low_zoom_label_priority(
                        record.priority,
                        record.low_zoom_country_rank,
                        viewport.zoom,
                    ),
                    layout_feature_id = layout_feature_id(record),
                    encounter_order = encounter_order++,
                )
            }
        }
        workspace.recordRefs.sortWith(
            compareBy<DictionaryLabelRecordRef> { it.layout_priority }
                .thenBy { it.layout_feature_id }
                .thenBy { it.encounter_order },
        )
        var preferred_seeds: List<DictionaryLabelCandidate> = emptyList()
        val selection_scratch: ArrayList<DictionaryLabelCandidate>?
        if (preferred_occurrences.isEmpty()) {
            selection_scratch = null
        } else {
            val seed_candidates = ArrayList<DictionaryLabelCandidate>()
            val seed_candidate_keys = HashSet<ReferenceLabelCandidateKey>()
            val prepass_complete = ReferenceLabelAdmissionPolicy.visitPreferredSeedRecords(
                records = workspace.recordRefs,
                preferredLookupKeys = preferred_candidate_keys,
                lookupKey = { record_ref ->
                    ReferenceLabelCandidateKey(
                        layout_candidate_id(record_ref.record),
                        rendered_label_world_copy(record_ref.tile, record_ref.record),
                    )
                },
                recordCandidateId = { record_ref -> record_ref.record.candidate_id },
                dedupeKey = { candidate_id, lookup_key ->
                    ReferenceLabelCandidateKey(
                        candidate_id,
                        lookup_key.rendered_world_copy,
                    )
                },
                visit = { record_ref, lookup_key, dedupe_key ->
                    keep_planning() && generate_label_record_candidates(
                        workspace = workspace,
                        viewport = viewport,
                        record_ref = record_ref,
                        label_text_scale = label_text_scale,
                        label_avoid_rects = label_avoid_rects,
                        excluded_rect = excluded_rect,
                        rendered_world_copy = lookup_key.rendered_world_copy,
                        candidate_key = dedupe_key,
                        planned_candidate_ids = seed_candidate_keys,
                        output = seed_candidates,
                        keep_planning = keep_planning,
                    )
                },
            )
            if (!prepass_complete) return abandon_label_plan(workspace)
            val retained_seeds = ArrayList<DictionaryLabelCandidate>()
            ReferenceLabelAdmissionPolicy.retainPreferredSeeds(
                candidates = seed_candidates,
                preferredOccurrences = preferred_occurrences,
                occurrenceId = DictionaryLabelCandidate::occurrenceId,
                output = retained_seeds,
            )
            seed_candidate_keys.clear()
            seed_candidates.clear()
            preferred_seeds = retained_seeds
            selection_scratch = ArrayList()
        }
        val budget = label_budget_override ?: label_budget(viewport)
        var selection_threshold = ReferenceLabelAdmissionPolicy.initial_threshold(budget)
        var last_selected_candidate_count = -1
        var record_index = 0
        while (record_index < workspace.recordRefs.size) {
            if (!keep_planning()) return abandon_label_plan(workspace)
            val first_ref = workspace.recordRefs[record_index]
            val block_priority = first_ref.layout_priority
            val block_feature_id = first_ref.layout_feature_id
            do {
                val record_ref = workspace.recordRefs[record_index]
                val rendered_world_copy =
                    rendered_label_world_copy(record_ref.tile, record_ref.record)
                val candidate_key = record_ref.record.candidate_id?.let { candidate_id ->
                    ReferenceLabelCandidateKey(candidate_id, rendered_world_copy)
                }
                if (!generate_label_record_candidates(
                        workspace = workspace,
                        viewport = viewport,
                        record_ref = record_ref,
                        label_text_scale = label_text_scale,
                        label_avoid_rects = label_avoid_rects,
                        excluded_rect = excluded_rect,
                        rendered_world_copy = rendered_world_copy,
                        candidate_key = candidate_key,
                        planned_candidate_ids = workspace.plannedCandidateIds,
                        output = workspace.candidates,
                        keep_planning = keep_planning,
                    )
                ) {
                    return abandon_label_plan(workspace)
                }
                record_index++
            } while (
                record_index < workspace.recordRefs.size &&
                    workspace.recordRefs[record_index].layout_priority == block_priority &&
                    workspace.recordRefs[record_index].layout_feature_id == block_feature_id
            )
            if (workspace.candidates.size >= selection_threshold) {
                accept_label_candidates(
                    workspace = workspace,
                    viewport = viewport,
                    label_avoid_rects = label_avoid_rects,
                    fixed_candidates = fixed_candidates,
                    preferred_occurrences = preferred_occurrences,
                    preferred_seeds = preferred_seeds,
                    priority_frontier = block_priority,
                    selection_scratch = selection_scratch,
                    label_budget = budget,
                    protected_area_budget = protected_area_budget_override
                        ?: protected_area_label_budget(viewport),
                )
                last_selected_candidate_count = workspace.candidates.size
                if (workspace.acceptedLabels.size >= budget) break
                selection_threshold =
                    ReferenceLabelAdmissionPolicy.next_threshold(workspace.candidates.size)
            }
        }
        if (
            workspace.acceptedLabels.size < budget &&
            last_selected_candidate_count != workspace.candidates.size
        ) {
            accept_label_candidates(
                workspace = workspace,
                viewport = viewport,
                label_avoid_rects = label_avoid_rects,
                fixed_candidates = fixed_candidates,
                preferred_occurrences = preferred_occurrences,
                preferred_seeds = preferred_seeds,
                priority_frontier =
                    workspace.recordRefs.getOrNull(record_index - 1)?.layout_priority,
                selection_scratch = selection_scratch,
                label_budget = budget,
                protected_area_budget = protected_area_budget_override
                    ?: protected_area_label_budget(viewport),
            )
        }
        workspace.recordRefs.clear()
        if (!keep_planning()) return abandon_label_plan(workspace)
        return true
    }

    private fun generate_label_record_candidates(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        record_ref: DictionaryLabelRecordRef,
        label_text_scale: Float,
        label_avoid_rects: List<ReferenceScreenRect>,
        excluded_rect: ReferenceScreenRect?,
        rendered_world_copy: Long,
        candidate_key: ReferenceLabelCandidateKey?,
        planned_candidate_ids: MutableSet<ReferenceLabelCandidateKey>,
        output: MutableList<DictionaryLabelCandidate>,
        keep_planning: () -> Boolean,
    ): Boolean {
        val tile = record_ref.tile
        val record = record_ref.record
        val line_candidate_already_planned = record.line_label &&
            candidate_key != null &&
            candidate_key in planned_candidate_ids
        if (line_candidate_already_planned) return true
        val style = label_style_for(record, label_text_scale, viewport.zoom)
        if (
            record.line_label &&
            record.geometry?.rings?.any { ring -> ring.point_count >= 2 } == true
        ) {
            if (!keep_planning()) return false
            val planned_candidates = line_label_candidates(
                workspace,
                viewport,
                tile,
                record,
                style,
                label_text_scale,
                label_avoid_rects,
                rendered_world_copy,
            )
            if (!keep_planning()) return false
            val admitted_candidates = if (excluded_rect == null) {
                planned_candidates
            } else {
                planned_candidates.filter { candidate ->
                    label_is_outside(candidate.collisionShape.bounds, excluded_rect)
                }
            }
            if (admitted_candidates.isNotEmpty()) {
                candidate_key?.let(planned_candidate_ids::add)
                output += admitted_candidates
            }
        } else if (!record.line_label) {
            point_label_candidate(
                workspace,
                viewport,
                tile,
                record,
                style,
                rendered_world_copy,
            )?.let { candidate ->
                val outside_excluded_rect = excluded_rect == null ||
                    label_is_outside(candidate.collisionShape.bounds, excluded_rect)
                if (outside_excluded_rect && (
                    candidate_key == null ||
                        planned_candidate_ids.add(candidate_key)
                    )
                ) {
                    output += candidate
                }
            }
        }
        return true
    }

    private fun abandon_label_plan(workspace: LabelPlanningWorkspace): Boolean {
        workspace.recordRefs.clear()
        workspace.candidates.clear()
        workspace.plannedCandidateIds.clear()
        workspace.acceptedLabels.clear()
        return false
    }

    fun draw_planned_labels(canvas: Canvas, workspace: LabelPlanningWorkspace) {
        for (index in workspace.acceptedLabels.indices.reversed()) {
            draw_label_candidate(canvas, workspace.acceptedLabels[index])
        }
    }

    private fun label_record_visible_for_admission(
        record: DictionaryLabelRecord,
        zoom: Double,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
    ): Boolean {
        if (!record.drawable || !record.visible_at(zoom)) return false
        if (record.line_label && zoom < MIN_PATH_LABEL_ZOOM) return false
        if (!filter_mask.allows(record.filter_id, groups.enabled(record.layer_group))) return false
        val visibility_alpha_milli = label_visibility_alpha_milli(record, zoom)
        return ReferenceLabelRuntimePresentationPolicy.hasVisiblePaintAlpha(
            record.alpha,
            record.halo_alpha,
            visibility_alpha_milli,
        )
    }

    private fun label_record_intersects_viewport(
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
    ): Boolean {
        record.anchor?.let { anchor ->
            val point = project_point(viewport, tile, anchor.x, anchor.y)
            return point.x in 0f..viewport.width && point.y in 0f..viewport.height
        }
        val bounds = record.geometry?.bounds ?: return true
        val top_left = project_point(viewport, tile, bounds.min_x, bounds.min_y)
        val bottom_right = project_point(viewport, tile, bounds.max_x, bounds.max_y)
        return top_left.x <= viewport.width && bottom_right.x >= 0f &&
            top_left.y <= viewport.height && bottom_right.y >= 0f
    }

    private fun layout_candidate_id(record: DictionaryLabelRecord): ULong =
        record.candidate_id
            ?: (record.dedupe_key ?: record.text).hashCode().toUInt().toULong()

    private fun layout_feature_id(record: DictionaryLabelRecord): ULong =
        record.source_feature_id ?: layout_candidate_id(record)

    private fun rendered_label_world_copy(
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
    ): Long = ReferenceBoundaryOccurrenceSelector.renderedWorldCopy(
        zoom = tile.tile.coordinate.z,
        requestedTileX = tile.tile.coordinate.x,
        drawTileX = tile.draw_x,
        postingWorldWrap = record.posting_world_wrap,
    )

    private fun line_label_candidates(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
        style: DictionaryLabelStyle,
        label_text_scale: Float,
        label_avoid_rects: List<ReferenceScreenRect>,
        rendered_world_copy: Long,
    ): List<DictionaryLabelCandidate> {
        val prepared = prepare_line_label(
            workspace = workspace,
            viewport = viewport,
            tile = tile,
            record = record,
            style = style,
            label_text_scale = label_text_scale,
            rendered_world_copy = rendered_world_copy,
            keep_planning = ALWAYS_KEEP_LABEL_PLAN,
        ) ?: return emptyList()
        val candidates = resolve_line_label(prepared, label_avoid_rects)
        if (candidates.isNotEmpty()) return candidates
        return if (record.candidate_id == null && !prepared.isWater) {
            listOfNotNull(
                point_label_candidate(
                    workspace,
                    viewport,
                    tile,
                    record,
                    style,
                    rendered_world_copy,
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun prepare_line_label(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
        style: DictionaryLabelStyle,
        label_text_scale: Float,
        rendered_world_copy: Long,
        keep_planning: () -> Boolean,
    ): PreparedLineLabel? {
        val is_water = record.is_water ?: (record.source_kind == "water")
        val geometry = record.geometry ?: return null
        val scale = 2.0.pow(viewport.zoom - tile.tile.coordinate.z)
        val local_to_screen = (MAP_TILE_SIZE / DICTIONARY_EXTENT) * scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val prepared_path = ReferenceVisiblePathProjector.prepare(
            parts = geometry.rings.mapIndexed { part_index, ring ->
                ReferenceRawPathPart(part_index, ring.points, ring.point_count)
            },
            transform = ReferencePathProjectionTransform(
                scaleX = local_to_screen,
                scaleY = local_to_screen,
                translateX = tile.draw_x * MAP_TILE_SIZE * scale - left_world,
                translateY = tile.tile.coordinate.y * MAP_TILE_SIZE * scale - top_world,
            ),
            viewport = ReferenceScreenRect(
                left = 0.0,
                top = 0.0,
                right = viewport.width.toDouble(),
                bottom = viewport.height.toDouble(),
            ),
        )
        if (prepared_path.parts.isEmpty() || !keep_planning()) return null
        val candidate_id = layout_candidate_id(record)
        val minimum_water_text_size_px =
            label_scaled_density * MIN_WATER_LINE_TEXT_SIZE_SP * label_text_scale
        val viewport_diagonal = hypot(viewport.width.toDouble(), viewport.height.toDouble())
        val attempts = ArrayList<PreparedLineLabelAttempt>(
            ReferenceLineLabelFitPolicy.attemptCount(is_water),
        )
        var tangent_fallback: PreparedLineLabelAttempt? = null
        var previous_text_size = -1f
        for (attempt_index in 0 until ReferenceLineLabelFitPolicy.attemptCount(is_water)) {
            if (!keep_planning()) return null
            val fitted_text_size = ReferenceLineLabelFitPolicy.textSizePx(
                baseTextSizePx = style.text_size,
                minimumTextSizePx = minimum_water_text_size_px,
                isWater = is_water,
                attemptIndex = attempt_index,
            )
            if (fitted_text_size == previous_text_size) continue
            previous_text_size = fitted_text_size
            val fitted_style = if (fitted_text_size == style.text_size) {
                style
            } else {
                style.copy(text_size = fitted_text_size)
            }
            prepare_planning_text_paint(
                workspace,
                fitted_style,
                fitted_style.font_weight,
                fitted_style.italic,
                fitted_style.text_size,
            )
            val presentation = record.sourced_text?.let {
                SourcedMapTextPresentation.plan(it, fitted_style.text_size)
            }
            val primary_width = measure_primary_label_width(
                workspace,
                fitted_style,
                presentation,
                record.text,
            )
            val english_width = presentation?.english?.let { english ->
                prepare_planning_text_paint(
                    workspace,
                    fitted_style,
                    ENGLISH_FONT_WEIGHT,
                    ENGLISH_ITALIC,
                    english.textSize,
                )
                workspace.textPaint.measureText(english.text)
            } ?: 0f
            prepare_planning_text_paint(
                workspace,
                fitted_style,
                fitted_style.font_weight,
                fitted_style.italic,
                fitted_style.text_size,
            )
            val text_width = max(primary_width, english_width)
            val collision_height = presentation?.collisionHeightEm
                ?.times(fitted_style.text_size)
                ?: fitted_style.text_size
            val collision_radius = collision_height / 2f +
                label_halo_width_px(fitted_style, fitted_style.text_size) +
                LABEL_COLLISION_PADDING_PX
            val policy_edge_clearance = fitted_style.text_size *
                ReferencePresentationPolicy.label_edge_clearance_milli_em / 1_000f
            val attempt_request = ReferencePathLabelRequest(
                    parts = emptyList(),
                    viewport = ReferenceScreenRect(
                        left = 0.0,
                        top = 0.0,
                        right = viewport.width.toDouble(),
                        bottom = viewport.height.toDouble(),
                    ),
                    shapedAdvancePx = text_width.toDouble(),
                    endClearancePx = (
                        fitted_style.text_size *
                            ReferencePresentationPolicy.label_end_clearance_milli_em / 1_000f
                        ).toDouble(),
                    edgeClearancePx = max(collision_radius, policy_edge_clearance).toDouble(),
                    maxBendDegrees = (
                        record.visibility_rule?.max_bend_centi_degrees
                            ?: ReferencePresentationPolicy.max_line_label_bend_centi_degrees
                        ) / 100.0,
                    candidateId = candidate_id,
                    repeatSpacingPx = record.repeat_spacing_px
                        ?.takeIf { it > 0 }
                        ?: ReferencePresentationPolicy.line_label_repeat_spacing_px,
                    prominenceTier = record.prominence_tier ?: ProminenceTier.LOCAL,
                    maximumTangentOffsetPx = if (is_water) {
                        0.0
                    } else {
                        max(boundary_density * 48f, collision_radius * 4f).toDouble()
                    },
                    maximumTangentSourceDistancePx = if (is_water) {
                        collision_radius.toDouble()
                    } else {
                        viewport_diagonal
                    },
                    maximumCurvedSourceDistancePx = if (is_water) {
                        (
                            collision_radius * WATER_CURVED_SOURCE_DISTANCE_FRACTION
                            ).toDouble()
                    } else {
                        0.0
                    },
                    allowTangentFallback = !is_water,
                )
            val attempt = PreparedLineLabelAttempt(
                style = fitted_style,
                presentation = presentation,
                collisionRadius = collision_radius,
                request = attempt_request,
            )
            attempts += attempt
            if (is_water) {
                tangent_fallback = attempt.copy(
                    request = attempt_request.copy(
                        allowCurvedPlacement = false,
                        allowTangentFallback = true,
                    ),
                )
            }
        }
        if (!keep_planning()) return null
        return PreparedLineLabel(
            record = record,
            viewportZoom = viewport.zoom,
            renderedWorldCopy = rendered_world_copy,
            preparedPath = prepared_path,
            candidateId = candidate_id,
            isWater = is_water,
            attempts = attempts.toList(),
            tangentFallback = tangent_fallback,
        )
    }

    private fun resolve_line_label(
        prepared: PreparedLineLabel,
        label_avoid_rects: List<ReferenceScreenRect>,
    ): List<DictionaryLabelCandidate> {
        var selected_attempt: PreparedLineLabelAttempt? = null
        var placements: List<ReferencePathLabelPlacement> = emptyList()
        for (attempt in prepared.attempts) {
            val attempt_placements = ReferencePathLabelPlanner.planPrepared(
                attempt.request.copy(staticAvoidRects = label_avoid_rects),
                prepared.preparedPath,
            )
            val has_curved_placement = attempt_placements.any { placement ->
                placement.mode == ReferencePathLabelPlacementMode.CURVED
            }
            if (
                attempt_placements.isNotEmpty() &&
                ReferenceLineLabelFitPolicy.acceptAttempt(prepared.isWater, has_curved_placement)
            ) {
                selected_attempt = attempt
                placements = attempt_placements
                break
            }
        }
        if (placements.isEmpty()) {
            prepared.tangentFallback?.let { fallback ->
                val fallback_placements = ReferencePathLabelPlanner.planPrepared(
                    fallback.request.copy(staticAvoidRects = label_avoid_rects),
                    prepared.preparedPath,
                )
                if (fallback_placements.isNotEmpty()) {
                    selected_attempt = fallback
                    placements = fallback_placements
                }
            }
        }
        val selected = selected_attempt ?: return emptyList()
        val record = prepared.record
        return ReferenceLineLabelPlacementAdapter.fromPlanner(
            placements,
            prepared.renderedWorldCopy,
        ).map { option ->
            DictionaryLabelCandidate(
                text = record.text,
                source_kind = record.source_kind,
                presentation = selected.presentation,
                style = selected.style,
                occurrenceId = option.occurrenceId,
                featureId = record.source_feature_id ?: prepared.candidateId,
                priority = reference_low_zoom_label_priority(
                    record.priority,
                    record.low_zoom_country_rank,
                    prepared.viewportZoom,
                ),
                placementRank = option.placementRank,
                protectedArea = record.protected_area,
                waterLine = prepared.isWater,
                anchor = option.placement.anchor,
                collisionShape = referencePathCollisionShape(
                    option.placement.presentationPath,
                    selected.collisionRadius.toDouble(),
                ),
                path_points = option.placement.presentationPath,
            )
        }
    }

    private fun point_label_candidate(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
        style: DictionaryLabelStyle,
        rendered_world_copy: Long,
    ): DictionaryLabelCandidate? {
        val anchor = label_anchor(viewport, tile, record) ?: return null
        val presentation = record.sourced_text?.let {
            SourcedMapTextPresentation.plan(it, style.text_size)
        }
        prepare_planning_text_paint(
            workspace,
            style,
            style.font_weight,
            style.italic,
            style.text_size,
        )
        val primary_width = measure_primary_label_width(
            workspace,
            style,
            presentation,
            record.text,
        )
        val english_width = presentation?.english?.let { english ->
            prepare_planning_text_paint(
                workspace,
                style,
                ENGLISH_FONT_WEIGHT,
                ENGLISH_ITALIC,
                english.textSize,
            )
            workspace.textPaint.measureText(english.text)
        } ?: 0f
        val candidate_id = layout_candidate_id(record)
        val label_bounds = label_bbox(
            record.text,
            anchor.x,
            anchor.y,
            style.text_size,
            width_override = max(primary_width, english_width),
            height_override = presentation?.collisionHeightEm?.times(style.text_size),
        )
        val collision_padding =
            label_halo_width_px(style, style.text_size) + LABEL_COLLISION_PADDING_PX
        return DictionaryLabelCandidate(
            text = record.text,
            source_kind = record.source_kind,
            presentation = presentation,
            style = style,
            occurrenceId = ReferenceLabelOccurrenceId(
                candidate_id,
                0L,
                rendered_world_copy,
            ),
            featureId = record.source_feature_id ?: candidate_id,
            priority = reference_low_zoom_label_priority(
                record.priority,
                record.low_zoom_country_rank,
                viewport.zoom,
            ),
            placementRank = 0,
            protectedArea = record.protected_area,
            waterLine = false,
            anchor = ReferencePathLabelPoint(anchor.x.toDouble(), anchor.y.toDouble()),
            collisionShape = ReferenceLabelCollisionShape.Box(
                ReferenceScreenRect(
                    left = label_bounds.left.toDouble() - collision_padding,
                    top = label_bounds.top.toDouble() - collision_padding,
                    right = label_bounds.right.toDouble() + collision_padding,
                    bottom = label_bounds.bottom.toDouble() + collision_padding,
                ),
            ),
            rotation = record.rotation
        )
    }

    private fun accept_label_candidates(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        label_avoid_rects: List<ReferenceScreenRect>,
        fixed_candidates: List<DictionaryLabelCandidate> = emptyList(),
        preferred_occurrences: Set<ReferenceLabelOccurrenceId> = emptySet(),
        preferred_seeds: List<DictionaryLabelCandidate> = emptyList(),
        priority_frontier: Int? = null,
        selection_scratch: MutableList<DictionaryLabelCandidate>? = null,
        label_budget: Int = label_budget(viewport),
        protected_area_budget: Int = protected_area_label_budget(viewport),
    ) {
        val selection_candidates = if (selection_scratch == null || priority_frontier == null) {
            workspace.candidates
        } else {
            selection_scratch.clear()
            selection_scratch += workspace.candidates
            ReferenceLabelAdmissionPolicy.appendActivePreferredSeeds(
                seeds = preferred_seeds,
                priorityFrontier = priority_frontier,
                priority = DictionaryLabelCandidate::priority,
                output = selection_scratch,
            )
            selection_scratch
        }
        workspace.acceptedLabels.clear()
        workspace.acceptedLabels += ReferenceLabelLayoutSelector.select(
            candidates = selection_candidates,
            fixedCandidates = fixed_candidates,
            preferredOccurrences = preferred_occurrences,
            viewport = ReferenceScreenRect(
                0.0,
                0.0,
                viewport.width.toDouble(),
                viewport.height.toDouble(),
            ),
            staticAvoidRects = label_avoid_rects,
            labelBudget = label_budget,
            protectedAreaBudget = protected_area_budget,
            waterRepeatDistancePx = WATER_LINE_LABEL_REPEAT_DISTANCE_PX.toDouble(),
            singleWaterLabelPerFeature = viewport.zoom <= WATER_SINGLE_LABEL_MAX_ZOOM,
        )
    }

    private fun measure_primary_label_width(
        workspace: LabelPlanningWorkspace,
        style: DictionaryLabelStyle,
        presentation: SourcedMapTextPresentationPlan?,
        fallback_text: String,
    ): Float {
        if (presentation == null) return workspace.textPaint.measureText(fallback_text)
        var width = 0f
        for (line in presentation.primaryLines) {
            prepare_planning_text_paint(
                workspace,
                style,
                style.font_weight,
                style.italic,
                line.textSize,
            )
            width = max(width, workspace.textPaint.measureText(line.text))
        }
        return width
    }

    private fun label_is_outside(
        label: ReferenceScreenRect,
        core: ReferenceScreenRect,
    ): Boolean {
        return label.right <= core.left || label.left >= core.right ||
            label.bottom <= core.top || label.top >= core.bottom
    }

    fun label_budget(viewport: Viewport): Int {
        val area_budget = (viewport.width * viewport.height / LABEL_AREA_PER_ITEM_PX)
            .roundToInt()
            .coerceIn(MIN_LABELS_PER_VIEWPORT, MAX_LABELS_PER_VIEWPORT)
        return when {
            viewport.zoom < 8.5 -> (area_budget - 4).coerceAtLeast(MIN_LABELS_PER_VIEWPORT)
            viewport.zoom > 11.5 -> (area_budget + 3).coerceAtMost(MAX_LABELS_PER_VIEWPORT)
            else -> area_budget
        }
    }

    fun protected_area_label_budget(viewport: Viewport): Int {
        return when {
            viewport.zoom < 8.5 -> 1
            viewport.zoom < 10.5 -> 2
            else -> 3
        }
    }

    fun draw_label_candidate(
        canvas: Canvas,
        candidate: DictionaryLabelCandidate,
        zoom_scale: Double = 1.0,
        translate_x: Double = 0.0,
        translate_y: Double = 0.0,
        frame_alpha: Int = 255,
    ) {
        val style = candidate.style
        val presentation = candidate.presentation
        val path_points = candidate.path_points
        if (path_points != null) {
            line_label_path.reset()
            path_points.forEachIndexed { index, point ->
                val x = (translate_x + point.x * zoom_scale).toFloat()
                val y = (translate_y + point.y * zoom_scale).toFloat()
                if (index == 0) {
                    line_label_path.moveTo(x, y)
                } else {
                    line_label_path.lineTo(x, y)
                }
            }
            if (presentation == null) {
                draw_text_on_path(
                    canvas,
                    candidate.text,
                    line_label_path,
                    style,
                    style.text_size,
                    style.font_weight,
                    style.italic,
                    0f,
                    frame_alpha,
                )
            } else {
                for (line in presentation.primaryLines) {
                    draw_text_on_path(
                        canvas,
                        line.text,
                        line_label_path,
                        style,
                        line.textSize,
                        style.font_weight,
                        style.italic,
                        line.baselineOffset,
                        frame_alpha,
                    )
                }
                presentation.english?.let { english ->
                    draw_text_on_path(
                        canvas,
                        english.text,
                        line_label_path,
                        style,
                        english.textSize,
                        ENGLISH_FONT_WEIGHT,
                        ENGLISH_ITALIC,
                        english.baselineOffset,
                        frame_alpha,
                    )
                }
            }
            return
        }
        val x = (translate_x + candidate.anchor.x * zoom_scale).toFloat()
        val y = (translate_y + candidate.anchor.y * zoom_scale).toFloat()
        canvas.withSave {
            if (candidate.rotation != 0f) rotate(candidate.rotation, x, y)
            if (presentation == null) {
                draw_text_with_halo(
                    canvas,
                    candidate.text,
                    x,
                    y,
                    style,
                    style.text_size,
                    style.font_weight,
                    style.italic,
                    frame_alpha,
                )
            } else {
                for (line in presentation.primaryLines) {
                    draw_text_with_halo(
                        canvas,
                        line.text,
                        x,
                        y + line.baselineOffset,
                        style,
                        line.textSize,
                        style.font_weight,
                        style.italic,
                        frame_alpha,
                    )
                }
                presentation.english?.let { english ->
                    draw_text_with_halo(
                        canvas,
                        english.text,
                        x,
                        y + english.baselineOffset,
                        style,
                        english.textSize,
                        ENGLISH_FONT_WEIGHT,
                        ENGLISH_ITALIC,
                        frame_alpha,
                    )
                }
            }
        }
    }

    private fun draw_text_on_path(
        canvas: Canvas,
        text: String,
        label_path: Path,
        style: DictionaryLabelStyle,
        text_size: Float,
        font_weight: Int,
        italic: Boolean,
        baseline_offset: Float,
        frame_alpha: Int,
    ) {
        prepare_text_paint(style, font_weight, italic, text_size)
        path_measure.setPath(label_path, false)
        val path_length = path_measure.length
        text_paint.textAlign = Paint.Align.LEFT
        val h_offset = ((path_length - text_paint.measureText(text)) / 2f).coerceAtLeast(0f)
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = label_halo_width_px(style, text_size)
        text_paint.color = with_alpha(
            style.halo_color,
            scaled_alpha_byte(style.halo_alpha, 1_000, frame_alpha),
        )
        canvas.drawTextOnPath(text, label_path, h_offset, baseline_offset, text_paint)
        text_paint.style = Paint.Style.FILL
        text_paint.strokeWidth = 0f
        text_paint.color = with_alpha(
            style.color,
            scaled_alpha_byte(style.alpha, 1_000, frame_alpha),
        )
        canvas.drawTextOnPath(text, label_path, h_offset, baseline_offset, text_paint)
    }

    private fun draw_text_with_halo(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        style: DictionaryLabelStyle,
        text_size: Float,
        font_weight: Int,
        italic: Boolean,
        frame_alpha: Int,
    ) {
        prepare_text_paint(style, font_weight, italic, text_size)
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = label_halo_width_px(style, text_size)
        text_paint.color = with_alpha(
            style.halo_color,
            scaled_alpha_byte(style.halo_alpha, 1_000, frame_alpha),
        )
        canvas.drawText(text, x, y, text_paint)
        text_paint.style = Paint.Style.FILL
        text_paint.strokeWidth = 0f
        text_paint.color = with_alpha(
            style.color,
            scaled_alpha_byte(style.alpha, 1_000, frame_alpha),
        )
        canvas.drawText(text, x, y, text_paint)
    }

    private fun prepare_text_paint(
        style: DictionaryLabelStyle,
        font_weight: Int,
        italic: Boolean,
        text_size: Float,
    ) {
        configure_text_paint(
            target = text_paint,
            typeface_cache = ui_workspace.typefaceCache,
            style = style,
            font_weight = font_weight,
            italic = italic,
            text_size = text_size,
        )
    }

    private fun prepare_planning_text_paint(
        workspace: LabelPlanningWorkspace,
        style: DictionaryLabelStyle,
        font_weight: Int,
        italic: Boolean,
        text_size: Float,
    ) {
        configure_text_paint(
            target = workspace.textPaint,
            typeface_cache = workspace.typefaceCache,
            style = style,
            font_weight = font_weight,
            italic = italic,
            text_size = text_size,
        )
    }

    private fun configure_text_paint(
        target: Paint,
        typeface_cache: MutableMap<Int, Typeface>,
        style: DictionaryLabelStyle,
        font_weight: Int,
        italic: Boolean,
        text_size: Float,
    ) {
        target.isAntiAlias = true
        target.isSubpixelText = true
        val typeface_key = font_weight * 2 + if (italic) 1 else 0
        target.typeface = typeface_cache.getOrPut(typeface_key) {
            Typeface.create(Typeface.DEFAULT, font_weight, italic)
        }
        target.textAlign = Paint.Align.CENTER
        target.textSize = text_size
        target.textScaleX = 1f
        target.isFakeBoldText = false
        target.textSkewX = 0f
        target.letterSpacing = style.letter_spacing_em
    }

    private fun label_halo_width_px(
        style: DictionaryLabelStyle,
        text_size: Float,
    ): Float = max(
        boundary_density * style.halo_width_dp,
        text_size * style.halo_width_milli_em / 1_000f,
    )

    private fun label_anchor(
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord
    ): DictionaryScreenPoint? {
        record.anchor?.let { anchor ->
            return project_point(viewport, tile, anchor.x, anchor.y)
        }
        record.geometry?.bounds?.let { bounds ->
            return project_point(
                viewport = viewport,
                tile = tile,
                local_x = (bounds.min_x + bounds.max_x) / 2f,
                local_y = (bounds.min_y + bounds.max_y) / 2f
            )
        }
        return null
    }

    private fun project_point(
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        local_x: Float,
        local_y: Float
    ): DictionaryScreenPoint {
        val scale = 2.0.pow(viewport.zoom - tile.tile.coordinate.z)
        val local_to_screen = (MAP_TILE_SIZE / DICTIONARY_EXTENT) * scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val origin_x = tile.draw_x * MAP_TILE_SIZE * scale - left_world
        val origin_y = tile.tile.coordinate.y * MAP_TILE_SIZE * scale - top_world
        return DictionaryScreenPoint(
            x = (origin_x + local_x * local_to_screen).toFloat(),
            y = (origin_y + local_y * local_to_screen).toFloat()
        )
    }

    private fun label_style_for(
        record: DictionaryLabelRecord,
        label_text_scale: Float,
        zoom: Double,
    ): DictionaryLabelStyle {
        val scale = label_text_scale.coerceIn(MAP_LABEL_TEXT_SCALE_MIN, MAP_LABEL_TEXT_SCALE_MAX)
        val runtime_typography = record.runtime_typography
        val visibility_alpha_milli = label_visibility_alpha_milli(record, zoom)
        fun scaled_alpha(alpha: Int): Int {
            return ((alpha * visibility_alpha_milli) + 500) / 1_000
        }
        val text_size_sp = runtime_typography?.textSizeMilliSp?.div(1_000f)
            ?: record.base_text_size_sp
        val halo_width_milli_em = runtime_typography?.haloWidthMilliEm
            ?: LEGACY_LABEL_HALO_WIDTH_MILLI_EM
        return DictionaryLabelStyle(
            color = record.color,
            text_size = label_scaled_density * text_size_sp * scale,
            alpha = scaled_alpha(record.alpha),
            halo_color = record.halo_color,
            halo_width_dp = if (runtime_typography == null) {
                record.halo_width_dp
            } else {
                0f
            },
            halo_width_milli_em = halo_width_milli_em,
            halo_alpha = scaled_alpha(record.halo_alpha),
            font_weight = runtime_typography?.fontWeight
                ?: legacy_font_weight(record.typeface_style),
            italic = runtime_typography?.italic ?: legacy_italic(record.typeface_style),
            letter_spacing_em = (
                runtime_typography?.letterSpacingMilliEm?.div(1_000f)
                    ?: record.letter_spacing_em
            ),
        )
    }

    private fun label_visibility_alpha_milli(
        record: DictionaryLabelRecord,
        zoom: Double,
    ): Int {
        val current_centizoom = ReferencePresentationPolicy.centizoom(zoom)
        val package_visibility_alpha_milli = record.visibility_rule?.let { rule ->
            ReferencePresentationPolicy.label_alpha_milli(rule, current_centizoom)
        } ?: ReferencePresentationPolicy.full_alpha_milli
        val runtime_visibility_alpha_milli = record.presentation_subtype?.let { subtype ->
            ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli(
                subtype,
                record.prominence_tier
                    ?: ReferencePresentationPolicy.default_prominence_for_subtype(subtype),
                current_centizoom,
            )
        } ?: ReferencePresentationPolicy.full_alpha_milli
        return (
            package_visibility_alpha_milli * runtime_visibility_alpha_milli + 500
        ) / 1_000
    }

    private fun legacy_font_weight(typeface_style: Int): Int = when (typeface_style) {
        Typeface.BOLD,
        Typeface.BOLD_ITALIC -> 700
        else -> 400
    }

    private fun legacy_italic(typeface_style: Int): Boolean = when (typeface_style) {
        Typeface.ITALIC,
        Typeface.BOLD_ITALIC -> true
        else -> false
    }

    private fun label_bbox(
        text: String,
        x: Float,
        y: Float,
        font_size: Float,
        width_override: Float? = null,
        height_override: Float? = null,
    ): RectF {
        val width_estimate = max(24f, width_override ?: (text.length * font_size * 0.58f))
        val height_estimate = height_override ?: (font_size * 1.45f)
        return RectF(
            x - width_estimate / 2f,
            y - height_estimate / 2f,
            x + width_estimate / 2f,
            y + height_estimate / 2f
        )
    }


    private companion object {
        const val MAP_TILE_SIZE = 256.0
        const val DICTIONARY_EXTENT = 4096.0
        const val MAP_LABEL_TEXT_SCALE_MIN = 1f
        const val MAP_LABEL_TEXT_SCALE_MAX = 1.75f
        const val MIN_PATH_LABEL_ZOOM = 10.0
        const val LEGACY_LABEL_HALO_WIDTH_MILLI_EM = 220
        const val ENGLISH_FONT_WEIGHT = 400
        const val ENGLISH_ITALIC = true
        const val LABEL_COLLISION_PADDING_PX = 4f
        const val LABEL_AREA_PER_ITEM_PX = 118_000f
        const val MIN_LABELS_PER_VIEWPORT = 9
        const val MAX_LABELS_PER_VIEWPORT = 22
        const val WATER_LINE_LABEL_REPEAT_DISTANCE_PX = 520f
        const val WATER_SINGLE_LABEL_MAX_ZOOM = 10.5
        const val MIN_WATER_LINE_TEXT_SIZE_SP = 8.25f
        const val WATER_CURVED_SOURCE_DISTANCE_FRACTION = 1f
    }
}

internal data class PreparedLabelPlan(
    val records: List<PreparedLabelRecord>,
    val excludedRect: ReferenceScreenRect?,
)

internal data class PreparedLabelRecord(
    val recordRef: DictionaryLabelRecordRef,
    val renderedWorldCopy: Long,
    val candidateKey: ReferenceLabelCandidateKey?,
    val line: PreparedLineLabel?,
    val point: DictionaryLabelCandidate?,
)

internal data class PreparedLineLabel(
    val record: DictionaryLabelRecord,
    val viewportZoom: Double,
    val renderedWorldCopy: Long,
    val preparedPath: ReferencePreparedPathGeometry,
    val candidateId: ULong,
    val isWater: Boolean,
    val attempts: List<PreparedLineLabelAttempt>,
    val tangentFallback: PreparedLineLabelAttempt?,
)

internal data class PreparedLineLabelAttempt(
    val style: DictionaryLabelStyle,
    val presentation: SourcedMapTextPresentationPlan?,
    val collisionRadius: Float,
    val request: ReferencePathLabelRequest,
)

internal class LabelPlanningWorkspace(
    val textPaint: Paint,
) {
    val typefaceCache = HashMap<Int, Typeface>(8)
    val recordRefs = ArrayList<DictionaryLabelRecordRef>(512)
    val candidates = ArrayList<DictionaryLabelCandidate>(256)
    val plannedCandidateIds = HashSet<ReferenceLabelCandidateKey>(256)
    val acceptedLabels = ArrayList<DictionaryLabelCandidate>(128)
}

internal data class ReferenceLabelCandidateKey(
    val candidate_id: ULong,
    val rendered_world_copy: Long,
)

