@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import com.flightalert.map.GeoPoint
import com.flightalert.map.ScreenPoint
import com.flightalert.map.Viewport
import com.flightalert.map.WorldPoint



internal class AviationLayerInteractionCoordinator(
    private val lat_lon_to_world: (Double, Double, Double) -> WorldPoint,
    private val world_to_lat_lon: (Double, Double, Double) -> GeoPoint
) {
    private var draw_identity: DrawIdentity? = null
    private val accepted_label_targets = ArrayList<AcceptedLabelTarget>()

    fun begin_draw(
        snapshot: AviationLayerSnapshot,
        viewport: Viewport,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility
    ) {
        draw_identity = DrawIdentity(snapshot, viewport, visible_bounds, visibility)
        accepted_label_targets.clear()
    }

    fun accept_airport_marker(
        airport: AviationAirportFeature,
        point: ScreenPoint,
        label_bounds: AviationScreenRect
    ) {
        accepted_label_targets += AcceptedLabelTarget(
            key = AviationSelectionKey.ArcGis(AviationLayerKind.AIRPORTS, airport.object_id),
            kind = AviationHitKind.AIRPORT,
            point = point,
            label_bounds = label_bounds
        )
    }

    fun accept_atc_label(
        feature: AviationAirspaceFeature,
        point: ScreenPoint,
        label_bounds: AviationScreenRect
    ) {
        accepted_label_targets += AcceptedLabelTarget(
            key = AviationSelectionKey.ArcGis(
                AviationLayerKind.ATC_BOUNDARIES,
                feature.object_id
            ),
            kind = AviationHitKind.ATC_BOUNDARY,
            point = point,
            label_bounds = label_bounds
        )
    }

    fun selection_key_at(
        x: Float,
        y: Float,
        density: Float,
        snapshot: AviationLayerSnapshot,
        viewport: Viewport,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        now_epoch_ms: Long
    ): AviationSelectionKey? {
        if (!x.isFinite() || !y.isFinite() || !density.isFinite() || density <= 0f) return null
        val density_squared = density.toDouble() * density.toDouble()
        val candidates = ArrayList<AviationHitCandidate>()

        if (draw_identity?.matches(snapshot, viewport, visible_bounds, visibility) == true) {
            accepted_label_targets.forEach { target ->
                if (!accepted_target_is_eligible(target, snapshot, viewport, visibility)) {
                    return@forEach
                }
                candidates += AviationHitCandidate(
                    key = target.key,
                    kind = target.kind,
                    distance_squared_dp = if (target.label_bounds.contains(x, y)) {
                        0.0
                    } else {
                        aviation_point_distance_squared_px(x, y, target.point) / density_squared
                    }
                )
            }
        }

        if (layer_is_eligible(
                AviationLayerKind.RESTRICTED_AIRSPACES,
                visibility.restricted_airspaces_enabled,
                snapshot
            )
        ) {
            val query_geo = aviation_screen_to_geo_point(x, y, viewport, world_to_lat_lon)
            forEachApplicable(
                features = snapshot.restricted_airspaces,
                visibleBounds = visible_bounds,
                excludedFeature = null
            ) { feature ->
                val key = AviationSelectionKey.ArcGis(
                    AviationLayerKind.RESTRICTED_AIRSPACES,
                    feature.object_id
                )
                aviation_distance_to_airspace_screen_squared_px(
                    x,
                    y,
                    feature,
                    viewport,
                    lat_lon_to_world
                )?.let { distance ->
                    candidates += AviationHitCandidate(
                        key = key,
                        kind = AviationHitKind.SPECIAL_USE_BOUNDARY,
                        distance_squared_dp = distance / density_squared
                    )
                }
                if (contains(feature, query_geo.lat, query_geo.lon)) {
                    candidates += AviationHitCandidate(
                        key = key,
                        kind = AviationHitKind.SPECIAL_USE_INTERIOR,
                        containment_area = boundsArea(feature)
                    )
                }
            }
        }

        if (layer_is_eligible(
                AviationLayerKind.ATC_BOUNDARIES,
                visibility.atc_boundaries_enabled,
                snapshot
            )
        ) {
            forEachApplicable(
                features = snapshot.atc_boundaries,
                visibleBounds = visible_bounds,
                excludedFeature = null
            ) { feature ->
                aviation_distance_to_airspace_screen_squared_px(
                    x,
                    y,
                    feature,
                    viewport,
                    lat_lon_to_world
                )?.let { distance ->
                    candidates += AviationHitCandidate(
                        key = AviationSelectionKey.ArcGis(
                            AviationLayerKind.ATC_BOUNDARIES,
                            feature.object_id
                        ),
                        kind = AviationHitKind.ATC_BOUNDARY,
                        distance_squared_dp = distance / density_squared
                    )
                }
            }
        }

        if (visibility.oceanic_tracks_enabled) {
            val status = snapshot.statuses[AviationLayerKind.OCEANIC_TRACKS]
            val publication = snapshot.publications[AviationLayerKind.OCEANIC_TRACKS]
            snapshot.oceanic_tracks.forEach { track ->
                if (!nat_is_eligible(
                        enabled = true,
                        track = track,
                        now_epoch_ms = now_epoch_ms,
                        status = status,
                        publication = publication
                    )
                ) return@forEach
                aviation_distance_to_source_paths_screen_squared_px(
                    x,
                    y,
                    track.drawable_segments,
                    viewport,
                    lat_lon_to_world
                )?.let { distance ->
                    candidates += AviationHitCandidate(
                        key = AviationSelection.OceanicTrack(track).key,
                        kind = AviationHitKind.NAT_SEGMENT,
                        distance_squared_dp = distance / density_squared
                    )
                }
            }
        }

        return choose_hit(candidates)
    }

    private fun accepted_target_is_eligible(
        target: AcceptedLabelTarget,
        snapshot: AviationLayerSnapshot,
        viewport: Viewport,
        visibility: AviationLayerVisibility
    ): Boolean = when (target.kind) {
        AviationHitKind.AIRPORT ->
            resolve(target.key, snapshot) is AviationSelection.Airport &&
                airport_is_eligible(
                    enabled = visibility.airport_labels_enabled,
                    zoom = viewport.zoom,
                    status = snapshot.statuses[AviationLayerKind.AIRPORTS],
                    publication = snapshot.publications[AviationLayerKind.AIRPORTS]
                )
        AviationHitKind.ATC_BOUNDARY ->
            resolve(target.key, snapshot) is AviationSelection.AtcBoundary &&
                layer_is_eligible(
                    AviationLayerKind.ATC_BOUNDARIES,
                    visibility.atc_boundaries_enabled,
                    snapshot
                )
        else -> false
    }

    private fun layer_is_eligible(
        kind: AviationLayerKind,
        enabled: Boolean,
        snapshot: AviationLayerSnapshot
    ): Boolean = com.flightalert.map.aviation_layers.layer_is_eligible(
        kind = kind,
        enabled = enabled,
        status = snapshot.statuses[kind],
        publication = snapshot.publications[kind]
    )

    private data class AcceptedLabelTarget(
        val key: AviationSelectionKey,
        val kind: AviationHitKind,
        val point: ScreenPoint,
        val label_bounds: AviationScreenRect
    )

    private class DrawIdentity(
        val snapshot: AviationLayerSnapshot,
        val viewport: Viewport,
        val visible_bounds: AviationGeoBounds,
        val visibility: AviationLayerVisibility
    ) {
        fun matches(
            snapshot: AviationLayerSnapshot,
            viewport: Viewport,
            visible_bounds: AviationGeoBounds,
            visibility: AviationLayerVisibility
        ): Boolean = this.snapshot === snapshot &&
            this.viewport == viewport &&
            this.visible_bounds == visible_bounds &&
            this.visibility == visibility
    }
}
