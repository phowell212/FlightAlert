@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

import com.flightalert.map.Viewport
import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate
import com.flightalert.map.reference.dictionary.ReferenceDictionaryLodPolicy
import com.flightalert.map.reference.dictionary.ReferenceTileRepository
import com.flightalert.map.reference.dictionary.RetainedReferenceOptions
import com.flightalert.map.reference.labels.ReferenceScreenRect
import kotlin.math.abs
import kotlin.math.ceil


internal class ReferenceRetainedRenderer(
    private val tile_repository: ReferenceTileRepository,
) {
    val destination = ReferenceRetainedDestination()
    private val retained_destination = destination
    private val fallback_destination = ReferenceRetainedDestination()
    private val candidate_destination = ReferenceRetainedDestination()
    private var retained_frame: RetainedReferenceFrame? = null
    private var retained_fallback_frame: RetainedReferenceFrame? = null
    private var retained_label_interaction_active = false
    private val retained_frame_history =
        ReferenceRetainedSceneHistory<RetainedReferenceFrame>(RETAINED_FRAME_HISTORY_LIMIT)

    fun interaction_started(interactionActive: Boolean): Boolean =
        interactionActive && !retained_label_interaction_active

    fun update_interaction_active(interactionActive: Boolean) {
        retained_label_interaction_active = interactionActive
    }

    fun target_frame(): RetainedReferenceFrame? = retained_frame

    fun reset() {
        retained_label_interaction_active = false
        retained_frame = null
        retained_fallback_frame = null
        retained_frame_history.clear()
    }

    fun retained_label_plan_is_available(
        key: RetainedLabelPlanKey,
        partial: RetainedReferenceFrame?,
        displayed: RetainedReferenceFrame?,
    ): Boolean {
        if (retained_frame?.label_plan_key == key ||
            retained_fallback_frame?.label_plan_key == key ||
            partial?.label_plan_key == key ||
            displayed?.label_plan_key == key
        ) return true
        return retained_frame_history.any { frame -> frame.label_plan_key == key }
    }

    fun finish_completed_retained_label_frame(
        prepared: PreparedRetainedLabelFrame,
        viewport: Viewport,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        options: RetainedReferenceOptions,
        label_avoid_rects: List<ReferenceScreenRect>,
        interaction_active: Boolean,
    ) {
        val frame = prepared.frame ?: return
        val key = prepared.request.token.key
        val current_generation = tile_repository.package_generation()
        val environment_matches =
            frame.options == options &&
                frame.package_generation == current_generation &&
                key.labelAvoidRects == label_avoid_rects &&
                frame.is_drawable()
        val visible_at_current_viewport = if (environment_matches) {
            retained_frame_destination(frame, viewport, retained_destination)
            frame.has_visible_content(viewport, retained_destination)
        } else {
            false
        }
        when (key.role) {
            RetainedFrameRole.TARGET -> when (
                reference_retained_completed_target_disposition(
                    environmentMatches = environment_matches,
                    matchesCurrentTarget = visible_at_current_viewport &&
                        frame.matches_exact(
                        viewport = viewport,
                        next_tile_zoom = target_tile_zoom,
                        next = options,
                        current_package_generation = current_generation,
                    ),
                    interactionActive = interaction_active,
                )
            ) {
                ReferenceRetainedCompletedTargetDisposition.PUBLISH ->
                    publish_retained_target_frame(
                        frame = frame,
                        viewport = viewport,
                        target_tile_zoom = target_tile_zoom,
                        fallback_tile_zoom = fallback_tile_zoom,
                        options = options,
                        current_generation = current_generation,
                    )
                ReferenceRetainedCompletedTargetDisposition.REMEMBER ->
                    retained_frame_history.remember(frame)
                ReferenceRetainedCompletedTargetDisposition.DISCARD -> Unit
            }
            RetainedFrameRole.FALLBACK -> when {
                !environment_matches -> Unit
                !interaction_active &&
                    visible_at_current_viewport &&
                    fallback_tile_zoom != null &&
                    frame.matches_exact(
                    viewport = viewport,
                    next_tile_zoom = fallback_tile_zoom,
                    next = options,
                    current_package_generation = current_generation,
                ) -> replace_retained_fallback_frame(frame)
                else -> retained_frame_history.remember(frame)
            }
        }
    }

    fun create_retained_frame(
        viewport: Viewport,
        frame_viewport: Viewport,
        tile_zoom: Int,
        options: RetainedReferenceOptions,
        package_generation_snapshot: Long,
        labels: List<DictionaryLabelCandidate>,
        label_plan_key: RetainedLabelPlanKey? = null,
    ): RetainedReferenceFrame {
        val width = ceil(viewport.width).toInt().coerceAtLeast(1)
        val height = ceil(viewport.height).toInt().coerceAtLeast(1)
        val frame_width = ceil(frame_viewport.width).toInt().coerceAtLeast(1)
        val frame_height = ceil(frame_viewport.height).toInt().coerceAtLeast(1)
        val label_coverage = ReferenceRetainedLabelCoverage(
            width = frame_width.toDouble(),
            height = frame_height.toDouble(),
        )
        for (label in labels) {
            label_coverage.add(label.collisionShape.bounds)
        }
        return RetainedReferenceFrame(
            zoom = viewport.zoom,
            center_x = viewport.center_x,
            center_y = viewport.center_y,
            tile_zoom = tile_zoom,
            width = frame_width,
            height = frame_height,
            visible_width = width,
            visible_height = height,
            options = options,
            package_generation = package_generation_snapshot,
            label_plan_key = label_plan_key,
            labels = labels,
            label_coverage = label_coverage,
        )
    }

    fun retained_frame_for_viewport(
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        displayed_frame: RetainedReferenceFrame?,
        destination: ReferenceRetainedDestination,
    ): RetainedReferenceFrame? {
        val primary = retained_frame
        val established = displayed_frame ?: primary
        var closer_future: RetainedReferenceFrame? = null
        var closest_zoom_distance = Double.POSITIVE_INFINITY
        if (established != null && primary != null && primary !== established &&
            primary.zoom > established.zoom &&
            reference_retained_zoom_ahead_can_handoff(
                establishedZoom = established.zoom,
                viewportZoom = viewport.zoom,
                minimumLead = ZOOM_AHEAD_HANDOFF_LEAD,
            ) &&
            retained_frame_is_emergency_eligible(
                frame = primary,
                viewport = viewport,
                options = options,
                target_tile_zoom = target_tile_zoom,
                fallback_tile_zoom = fallback_tile_zoom,
                destination = candidate_destination,
            )
        ) {
            closer_future = primary
            closest_zoom_distance = abs(primary.zoom - viewport.zoom)
        }
        if (established != null) {
            for (previous in retained_frame_history) {
                val zoom_distance = abs(previous.zoom - viewport.zoom)
                if (previous === established ||
                    previous.zoom <= established.zoom ||
                    zoom_distance >= closest_zoom_distance ||
                    !reference_retained_zoom_ahead_can_handoff(
                        establishedZoom = established.zoom,
                        viewportZoom = viewport.zoom,
                        minimumLead = ZOOM_AHEAD_HANDOFF_LEAD,
                    ) ||
                    !retained_frame_is_emergency_eligible(
                        frame = previous,
                        viewport = viewport,
                        options = options,
                        target_tile_zoom = target_tile_zoom,
                        fallback_tile_zoom = fallback_tile_zoom,
                        destination = candidate_destination,
                    )
                ) {
                    continue
                }
                closer_future = previous
                closest_zoom_distance = zoom_distance
            }
        }
        if (closer_future != null) {
            if (closer_future !== primary) promote_retained_frame(closer_future)
            retained_frame_destination(closer_future, viewport, destination)
            return closer_future
        }

        if (primary != null && retained_frame_is_eligible(
                primary,
                viewport,
                options,
                target_tile_zoom,
                fallback_tile_zoom,
                destination,
            )
        ) {
            return primary
        }
        var covering_target_history: RetainedReferenceFrame? = null
        for (previous in retained_frame_history) {
            if (previous !== primary &&
                previous.matches_environment(
                    viewport = viewport,
                    next = options,
                    current_package_generation = tile_repository.package_generation(),
                ) &&
                previous.is_drawable() &&
                retained_target_frame_can_settle(
                    frame = previous,
                    viewport = viewport,
                    target_tile_zoom = target_tile_zoom,
                    fallback_tile_zoom = fallback_tile_zoom,
                    destination = destination,
                )
            ) {
                covering_target_history = previous
                break
            }
        }
        if (covering_target_history != null) {
            promote_retained_frame(covering_target_history)
            return covering_target_history
        }
        val fallback = retained_fallback_frame
        if (fallback != null && fallback !== primary && retained_frame_is_eligible(
                fallback,
                viewport,
                options,
                target_tile_zoom,
                fallback_tile_zoom,
                destination,
            )
        ) {
            return fallback
        }
        var historical: RetainedReferenceFrame? = null
        for (previous in retained_frame_history) {
            if (previous !== primary && previous !== fallback && retained_frame_is_eligible(
                    previous,
                    viewport,
                    options,
                    target_tile_zoom,
                    fallback_tile_zoom,
                    destination,
                )
            ) {
                historical = previous
                break
            }
        }
        if (historical != null) {
            promote_retained_frame(historical)
            return historical
        }

        if (displayed_frame != null && retained_frame_is_emergency_eligible(
                frame = displayed_frame,
                viewport = viewport,
                options = options,
                target_tile_zoom = target_tile_zoom,
                fallback_tile_zoom = fallback_tile_zoom,
                destination = destination,
            )
        ) {
            return displayed_frame
        }
        if (primary != null && primary !== displayed_frame &&
            retained_frame_is_emergency_eligible(
                frame = primary,
                viewport = viewport,
                options = options,
                target_tile_zoom = target_tile_zoom,
                fallback_tile_zoom = fallback_tile_zoom,
                destination = destination,
            )
        ) {
            return primary
        }
        if (fallback != null && fallback !== displayed_frame && fallback !== primary &&
            retained_frame_is_emergency_eligible(
                frame = fallback,
                viewport = viewport,
                options = options,
                target_tile_zoom = target_tile_zoom,
                fallback_tile_zoom = fallback_tile_zoom,
                destination = destination,
            )
        ) {
            return fallback
        }
        for (previous in retained_frame_history) {
            if (previous !== displayed_frame && previous !== primary && previous !== fallback &&
                retained_frame_is_emergency_eligible(
                    frame = previous,
                    viewport = viewport,
                    options = options,
                    target_tile_zoom = target_tile_zoom,
                    fallback_tile_zoom = fallback_tile_zoom,
                    destination = destination,
                )
            ) {
                return previous
            }
        }
        return null
    }

    fun retained_history_has_exact_target(
        viewport: Viewport,
        tile_zoom: Int,
        options: RetainedReferenceOptions,
    ): Boolean {
        val current_generation = tile_repository.package_generation()
        for (frame in retained_frame_history) {
            if (frame.matches_exact(
                    viewport = viewport,
                    next_tile_zoom = tile_zoom,
                    next = options,
                    current_package_generation = current_generation,
                )
            ) return true
        }
        return false
    }

    private fun retained_frame_is_eligible(
        frame: RetainedReferenceFrame,
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        destination: ReferenceRetainedDestination,
    ): Boolean {
        if (!frame.matches_options(options) ||
            frame.package_generation != tile_repository.package_generation() ||
            !ReferenceDictionaryLodPolicy.can_reuse_scene(
                frame.tile_zoom,
                target_tile_zoom,
                fallback_tile_zoom,
            ) ||
            !frame.is_drawable()
        ) return false
        retained_frame_destination(frame, viewport, destination)
        return reference_retained_covers_viewport(destination, viewport) &&
            frame.has_visible_content(viewport, destination)
    }

    private fun retained_frame_is_emergency_eligible(
        frame: RetainedReferenceFrame,
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        destination: ReferenceRetainedDestination,
    ): Boolean {
        if (!frame.matches_options(options) ||
            frame.package_generation != tile_repository.package_generation() ||
            !ReferenceDictionaryLodPolicy.can_reuse_scene_during_interaction(
                frame.tile_zoom,
                target_tile_zoom,
                fallback_tile_zoom,
            ) ||
            !frame.is_drawable()
        ) return false
        retained_frame_destination(frame, viewport, destination)
        return reference_retained_covers_viewport(destination, viewport) &&
            frame.has_visible_content(viewport, destination)
    }

    fun retained_fallback_needs_build(
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
    ): Boolean {
        val fallback = retained_fallback_frame
        return fallback_tile_zoom != null && (
            fallback == null ||
                fallback.tile_zoom != fallback_tile_zoom ||
                !retained_frame_is_eligible(
                    frame = fallback,
                    viewport = viewport,
                    options = options,
                    target_tile_zoom = target_tile_zoom,
                    fallback_tile_zoom = fallback_tile_zoom,
                    destination = fallback_destination,
                )
            )
    }

    fun retained_target_frame_can_settle(
        frame: RetainedReferenceFrame,
        viewport: Viewport,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        destination: ReferenceRetainedDestination,
    ): Boolean {
        retained_frame_destination(frame, viewport, destination)
        return frame.has_visible_content(viewport, destination) &&
            reference_retained_scene_can_settle(
            destination = destination,
            viewport = viewport,
            frameZoom = frame.zoom,
            frameTileZoom = frame.tile_zoom,
            targetTileZoom = target_tile_zoom,
            fallbackTileZoom = fallback_tile_zoom,
        )
    }

    fun retained_frame_destination(
        frame: RetainedReferenceFrame,
        viewport: Viewport,
        destination: ReferenceRetainedDestination,
    ) {
        reference_retained_destination(
            frame_zoom = frame.zoom,
            frame_center_x = frame.center_x,
            frame_center_y = frame.center_y,
            frame_width = frame.width,
            frame_height = frame.height,
            viewport = viewport,
            destination = destination,
        )
    }

    private fun replace_retained_frame(next: RetainedReferenceFrame?) {
        val previous = retained_frame
        retained_frame = next
        if (previous != null && previous !== next) {
            retained_frame_history.remember(previous)
        }
    }

    private fun publish_retained_target_frame(
        frame: RetainedReferenceFrame,
        viewport: Viewport,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        options: RetainedReferenceOptions,
        current_generation: Long,
    ) {
        val active = retained_frame
        if (active == null) {
            replace_retained_frame(frame)
            return
        }
        if (active.matches_exact(
                viewport = viewport,
                next_tile_zoom = target_tile_zoom,
                next = options,
                current_package_generation = current_generation,
            )
        ) {
            if (active.label_plan_key != frame.label_plan_key) replace_retained_frame(frame)
            return
        }
        if (active.matches_environment(viewport, options, current_generation) &&
            retained_target_frame_can_settle(
                frame = active,
                viewport = viewport,
                target_tile_zoom = target_tile_zoom,
                fallback_tile_zoom = fallback_tile_zoom,
                destination = retained_destination,
            )
        ) {
            retained_frame_history.remember(frame)
            return
        }
        replace_retained_frame(frame)
    }

    private fun promote_retained_frame(next: RetainedReferenceFrame) {
        if (retained_frame === next) return
        retained_frame_history.remove(next)
        replace_retained_frame(next)
    }

    private fun replace_retained_fallback_frame(next: RetainedReferenceFrame?) {
        if (retained_fallback_frame === next) return
        retained_fallback_frame = next
    }

    private companion object {
        const val ZOOM_AHEAD_HANDOFF_LEAD = 0.25
    }
}