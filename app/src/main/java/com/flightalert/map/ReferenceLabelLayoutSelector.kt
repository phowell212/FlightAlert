package com.flightalert.map

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class ReferenceLabelOccurrenceId(
    val candidateId: ULong,
    val repeatOrdinal: Long,
)

internal sealed interface ReferenceLabelCollisionShape {
    val bounds: ReferenceScreenRect

    data class Box(val rect: ReferenceScreenRect) : ReferenceLabelCollisionShape {
        override val bounds: ReferenceScreenRect get() = rect
    }

    data class Path(
        val points: List<ReferencePathLabelPoint>,
        val radiusPx: Double,
        override val bounds: ReferenceScreenRect,
    ) : ReferenceLabelCollisionShape
}

internal interface ReferenceLabelLayoutCandidate {
    val occurrenceId: ReferenceLabelOccurrenceId
    val featureId: ULong
    val priority: Int
    val placementRank: Int
    val protectedArea: Boolean
    val waterLine: Boolean
    val anchor: ReferencePathLabelPoint
    val collisionShape: ReferenceLabelCollisionShape
}

internal object ReferenceLabelLayoutSelector {
    fun <T : ReferenceLabelLayoutCandidate> select(
        candidates: List<T>,
        viewport: ReferenceScreenRect,
        staticAvoidRects: List<ReferenceScreenRect>,
        labelBudget: Int,
        protectedAreaBudget: Int,
        waterRepeatDistancePx: Double,
    ): List<T> {
        require(labelBudget > 0) { "label budget must be positive" }
        require(protectedAreaBudget > 0) { "protected-area budget must be positive" }
        require(waterRepeatDistancePx.isFinite() && waterRepeatDistancePx >= 0.0) {
            "water repeat distance must be finite and nonnegative"
        }
        candidates.forEach(::validateCandidate)

        val groups = candidates
            .groupBy { it.occurrenceId }
            .values
            .map { group ->
                CandidateGroup(
                    first = group.first(),
                    placements = group.distinctBy { it.collisionShape }
                        .sortedBy { it.placementRank },
                )
            }
            .sortedWith(
                compareBy<CandidateGroup<T>> { it.first.priority }
                    .thenBy { it.first.featureId }
                    .thenBy { it.first.occurrenceId.repeatOrdinal },
            )

        val accepted = ArrayList<T>(min(labelBudget, groups.size))
        var protectedAreaCount = 0
        for (group in groups) {
            if (accepted.size >= labelBudget) break
            for (candidate in group.placements) {
                if (!strictlyOverlaps(candidate.collisionShape.bounds, viewport)) continue
                if (candidate.protectedArea && protectedAreaCount >= protectedAreaBudget) continue
                if (isNearbyWaterRepeat(candidate, accepted, waterRepeatDistancePx)) continue
                if (staticAvoidRects.any { collides(candidate.collisionShape, box = it) }) continue
                if (accepted.any { collides(candidate.collisionShape, it.collisionShape) }) continue

                accepted += candidate
                if (candidate.protectedArea) protectedAreaCount++
                break
            }
        }
        return accepted
    }

    private data class CandidateGroup<T : ReferenceLabelLayoutCandidate>(
        val first: T,
        val placements: List<T>,
    )

    private fun validateCandidate(candidate: ReferenceLabelLayoutCandidate) {
        require(candidate.anchor.x.isFinite() && candidate.anchor.y.isFinite()) {
            "label anchors must be finite"
        }
        when (val shape = candidate.collisionShape) {
            is ReferenceLabelCollisionShape.Box -> validateRect(shape.rect)
            is ReferenceLabelCollisionShape.Path -> {
                validateRect(shape.bounds)
                require(shape.points.size >= 2) { "collision paths need at least two points" }
                require(shape.radiusPx.isFinite() && shape.radiusPx >= 0.0) {
                    "collision path radii must be finite and nonnegative"
                }
                shape.points.forEach { point ->
                    require(point.x.isFinite() && point.y.isFinite()) {
                        "collision path coordinates must be finite"
                    }
                }
            }
        }
    }

    private fun validateRect(rect: ReferenceScreenRect) {
        require(
            rect.left.isFinite() && rect.top.isFinite() &&
                rect.right.isFinite() && rect.bottom.isFinite(),
        ) { "collision rectangles must be finite" }
    }

    private fun <T : ReferenceLabelLayoutCandidate> isNearbyWaterRepeat(
        candidate: T,
        accepted: List<T>,
        repeatDistancePx: Double,
    ): Boolean {
        if (!candidate.waterLine) return false
        return accepted.any { other ->
            other.waterLine &&
                other.featureId == candidate.featureId &&
                hypot(
                    other.anchor.x - candidate.anchor.x,
                    other.anchor.y - candidate.anchor.y,
                ) < repeatDistancePx
        }
    }

    private fun collides(
        shape: ReferenceLabelCollisionShape,
        box: ReferenceScreenRect,
    ): Boolean = when (shape) {
        is ReferenceLabelCollisionShape.Box -> strictlyOverlaps(shape.rect, box)
        is ReferenceLabelCollisionShape.Path -> pathCollidesWithBox(shape, box)
    }

    private fun collides(
        first: ReferenceLabelCollisionShape,
        second: ReferenceLabelCollisionShape,
    ): Boolean = when {
        first is ReferenceLabelCollisionShape.Box &&
            second is ReferenceLabelCollisionShape.Box ->
            strictlyOverlaps(first.rect, second.rect)

        first is ReferenceLabelCollisionShape.Path &&
            second is ReferenceLabelCollisionShape.Box ->
            pathCollidesWithBox(first, second.rect)

        first is ReferenceLabelCollisionShape.Box &&
            second is ReferenceLabelCollisionShape.Path ->
            pathCollidesWithBox(second, first.rect)

        first is ReferenceLabelCollisionShape.Path &&
            second is ReferenceLabelCollisionShape.Path ->
            pathsCollide(first, second)

        else -> error("unknown reference-label collision shapes")
    }

    private fun strictlyOverlaps(first: ReferenceScreenRect, second: ReferenceScreenRect): Boolean =
        first.left < second.right && second.left < first.right &&
            first.top < second.bottom && second.top < first.bottom

    private fun pathCollidesWithBox(
        path: ReferenceLabelCollisionShape.Path,
        box: ReferenceScreenRect,
    ): Boolean = path.points.zipWithNext().any { (start, end) ->
        segmentRectDistance(start, end, box) <= path.radiusPx
    }

    private fun pathsCollide(
        first: ReferenceLabelCollisionShape.Path,
        second: ReferenceLabelCollisionShape.Path,
    ): Boolean {
        val combinedRadius = first.radiusPx + second.radiusPx
        if (!combinedRadius.isFinite()) return true
        return first.points.zipWithNext().any { (firstStart, firstEnd) ->
            second.points.zipWithNext().any { (secondStart, secondEnd) ->
                segmentDistance(firstStart, firstEnd, secondStart, secondEnd) <= combinedRadius
            }
        }
    }

    private fun segmentRectDistance(
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
        rect: ReferenceScreenRect,
    ): Double {
        if (pointInsideRect(start, rect) || pointInsideRect(end, rect)) return 0.0
        val topLeft = ReferencePathLabelPoint(rect.left, rect.top)
        val topRight = ReferencePathLabelPoint(rect.right, rect.top)
        val bottomRight = ReferencePathLabelPoint(rect.right, rect.bottom)
        val bottomLeft = ReferencePathLabelPoint(rect.left, rect.bottom)
        if (
            segmentsIntersect(start, end, topLeft, topRight) ||
            segmentsIntersect(start, end, topRight, bottomRight) ||
            segmentsIntersect(start, end, bottomRight, bottomLeft) ||
            segmentsIntersect(start, end, bottomLeft, topLeft)
        ) {
            return 0.0
        }
        return minOf(
            pointRectDistance(start, rect),
            pointRectDistance(end, rect),
            pointSegmentDistance(topLeft, start, end),
            pointSegmentDistance(topRight, start, end),
            pointSegmentDistance(bottomRight, start, end),
            pointSegmentDistance(bottomLeft, start, end),
        )
    }

    private fun pointInsideRect(point: ReferencePathLabelPoint, rect: ReferenceScreenRect): Boolean =
        point.x in rect.left..rect.right && point.y in rect.top..rect.bottom

    private fun pointRectDistance(
        point: ReferencePathLabelPoint,
        rect: ReferenceScreenRect,
    ): Double {
        val dx = max(max(rect.left - point.x, 0.0), point.x - rect.right)
        val dy = max(max(rect.top - point.y, 0.0), point.y - rect.bottom)
        return hypot(dx, dy)
    }

    private fun segmentDistance(
        firstStart: ReferencePathLabelPoint,
        firstEnd: ReferencePathLabelPoint,
        secondStart: ReferencePathLabelPoint,
        secondEnd: ReferencePathLabelPoint,
    ): Double {
        if (segmentsIntersect(firstStart, firstEnd, secondStart, secondEnd)) return 0.0
        return minOf(
            pointSegmentDistance(firstStart, secondStart, secondEnd),
            pointSegmentDistance(firstEnd, secondStart, secondEnd),
            pointSegmentDistance(secondStart, firstStart, firstEnd),
            pointSegmentDistance(secondEnd, firstStart, firstEnd),
        )
    }

    private fun segmentsIntersect(
        firstStart: ReferencePathLabelPoint,
        firstEnd: ReferencePathLabelPoint,
        secondStart: ReferencePathLabelPoint,
        secondEnd: ReferencePathLabelPoint,
    ): Boolean {
        val firstSideStart = cross(firstStart, firstEnd, secondStart)
        val firstSideEnd = cross(firstStart, firstEnd, secondEnd)
        val secondSideStart = cross(secondStart, secondEnd, firstStart)
        val secondSideEnd = cross(secondStart, secondEnd, firstEnd)
        if (
            !firstSideStart.isFinite() || !firstSideEnd.isFinite() ||
            !secondSideStart.isFinite() || !secondSideEnd.isFinite()
        ) {
            return true
        }
        if (
            oppositeSigns(firstSideStart, firstSideEnd) &&
            oppositeSigns(secondSideStart, secondSideEnd)
        ) {
            return true
        }
        return (firstSideStart == 0.0 && pointOnSegment(secondStart, firstStart, firstEnd)) ||
            (firstSideEnd == 0.0 && pointOnSegment(secondEnd, firstStart, firstEnd)) ||
            (secondSideStart == 0.0 && pointOnSegment(firstStart, secondStart, secondEnd)) ||
            (secondSideEnd == 0.0 && pointOnSegment(firstEnd, secondStart, secondEnd))
    }

    private fun cross(
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
        point: ReferencePathLabelPoint,
    ): Double = (end.x - start.x) * (point.y - start.y) -
        (end.y - start.y) * (point.x - start.x)

    private fun oppositeSigns(first: Double, second: Double): Boolean =
        (first > 0.0 && second < 0.0) || (first < 0.0 && second > 0.0)

    private fun pointOnSegment(
        point: ReferencePathLabelPoint,
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
    ): Boolean = point.x in min(start.x, end.x)..max(start.x, end.x) &&
        point.y in min(start.y, end.y)..max(start.y, end.y)

    private fun pointSegmentDistance(
        point: ReferencePathLabelPoint,
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
    ): Double {
        val dx = end.x - start.x
        val dy = end.y - start.y
        if (!dx.isFinite() || !dy.isFinite()) return 0.0
        val length = hypot(dx, dy)
        if (length == 0.0) return hypot(point.x - start.x, point.y - start.y)
        if (!length.isFinite()) return 0.0
        val pointDx = point.x - start.x
        val pointDy = point.y - start.y
        if (!pointDx.isFinite() || !pointDy.isFinite()) return 0.0
        val unitX = dx / length
        val unitY = dy / length
        val projection = (pointDx * unitX + pointDy * unitY).coerceIn(0.0, length)
        val closestX = start.x + unitX * projection
        val closestY = start.y + unitY * projection
        if (!closestX.isFinite() || !closestY.isFinite()) return 0.0
        return hypot(point.x - closestX, point.y - closestY)
    }
}
