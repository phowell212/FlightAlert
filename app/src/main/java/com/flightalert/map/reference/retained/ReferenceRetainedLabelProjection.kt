package com.flightalert.map.reference.retained

import com.flightalert.map.Viewport
import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate
import com.flightalert.map.reference.labels.ReferenceLabelCollisionShape
import com.flightalert.map.reference.labels.ReferenceLabelLayoutCandidate
import com.flightalert.map.reference.labels.ReferencePathLabelPoint
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.map.reference.labels.select
import kotlin.math.max
import kotlin.math.min

private class RetainedLabelWithOrder(
    val label: DictionaryLabelCandidate,
    override val featureId: ULong,
) : ReferenceLabelLayoutCandidate by label

internal fun reference_retained_project_labels(
    labels: List<DictionaryLabelCandidate>,
    zoomScale: Double,
    translateX: Double,
    translateY: Double,
    viewport: Viewport,
    avoidRects: List<ReferenceScreenRect>,
): List<DictionaryLabelCandidate> {
    require(zoomScale.isFinite() && zoomScale > 0.0)
    require(translateX.isFinite() && translateY.isFinite())
    require(viewport.width.isFinite() && viewport.width > 0f &&
        viewport.height.isFinite() && viewport.height > 0f)
    if (labels.isEmpty()) return emptyList()
    val projected = ArrayList<RetainedLabelWithOrder>(labels.size)
    var left = 0.0
    var top = 0.0
    var right = viewport.width.toDouble()
    var bottom = viewport.height.toDouble()
    for (candidate in labels) {
        val anchorX = translateX + candidate.anchor.x * zoomScale
        val anchorY = translateY + candidate.anchor.y * zoomScale
        require(anchorX.isFinite() && anchorY.isFinite()) { "path-label coordinates must be finite" }
        val original = candidate.collisionShape
        val labelLeft: Double
        val labelTop: Double
        val labelRight: Double
        val labelBottom: Double
        when (original) {
            is ReferenceLabelCollisionShape.Box -> {
                labelLeft = anchorX + original.rect.left - candidate.anchor.x
                labelTop = anchorY + original.rect.top - candidate.anchor.y
                labelRight = anchorX + original.rect.right - candidate.anchor.x
                labelBottom = anchorY + original.rect.bottom - candidate.anchor.y
            }
            is ReferenceLabelCollisionShape.Path -> {
                var minX = anchorX + original.points[0].x - candidate.anchor.x
                var minY = anchorY + original.points[0].y - candidate.anchor.y
                require(minX.isFinite() && minY.isFinite()) { "path-label coordinates must be finite" }
                var maxX = minX
                var maxY = minY
                for (index in 1 until original.points.size) {
                    val point = original.points[index]
                    val x = anchorX + point.x - candidate.anchor.x
                    val y = anchorY + point.y - candidate.anchor.y
                    require(x.isFinite() && y.isFinite()) { "path-label coordinates must be finite" }
                    minX = min(minX, x); minY = min(minY, y)
                    maxX = max(maxX, x); maxY = max(maxY, y)
                }
                labelLeft = minX - original.radiusPx
                labelTop = minY - original.radiusPx
                labelRight = maxX + original.radiusPx
                labelBottom = maxY + original.radiusPx
            }
        }
        require(labelLeft.isFinite() && labelTop.isFinite() && labelRight.isFinite() && labelBottom.isFinite())
        require(labelLeft < labelRight && labelTop < labelBottom)
        if (labelRight < 0.0 || labelBottom < 0.0 ||
            labelLeft > viewport.width || labelTop > viewport.height) continue
        // Offscreen padding must not allocate a copied glyph path on every gesture frame.
        val anchor = ReferencePathLabelPoint(anchorX, anchorY)
        val bounds = ReferenceScreenRect(labelLeft, labelTop, labelRight, labelBottom)
        val shape = when (original) {
            is ReferenceLabelCollisionShape.Box -> ReferenceLabelCollisionShape.Box(bounds)
            is ReferenceLabelCollisionShape.Path -> ReferenceLabelCollisionShape.Path(
                // Fixed-size text needs the complete prepared glyph path during zoom.
                original.points.map { point -> ReferencePathLabelPoint(
                    anchorX + point.x - candidate.anchor.x,
                    anchorY + point.y - candidate.anchor.y,
                ) },
                original.radiusPx,
                bounds,
            )
        }
        projected += RetainedLabelWithOrder(
            label = candidate.copy(
                anchor = anchor,
                collisionShape = shape,
                path_points = (shape as? ReferenceLabelCollisionShape.Path)?.points,
            ),
            // Keep established ordering within each priority without changing source identity.
            featureId = projected.size.toULong(),
        )
        left = minOf(left, bounds.left)
        top = minOf(top, bounds.top)
        right = maxOf(right, bounds.right)
        bottom = maxOf(bottom, bounds.bottom)
    }
    if (projected.isEmpty()) return emptyList()
    return select(
        candidates = projected,
        // Include clipped glyph footprints: their visible parts still reserve collision space.
        viewport = ReferenceScreenRect(left, top, right, bottom),
        staticAvoidRects = avoidRects,
        labelBudget = labels.size,
        protectedAreaBudget = labels.size,
        waterRepeatDistancePx = 0.0,
        singleWaterLabelPerFeature = false,
    ).sortedBy { it.featureId }.map { it.label }
}
