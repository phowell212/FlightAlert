package com.flightalert.map

import java.util.Collections
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

internal data class ReferencePathLabelPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "path-label coordinates must be finite" }
    }
}

internal class ReferenceSupportIntervalUnion(
    private val mergeEpsilon: Double,
    initialIntervalCapacity: Int = 16,
) {
    private var values = DoubleArray(max(1, initialIntervalCapacity) * 2)
    private var count = 0
    private var batchEvaluation = false

    internal val usesBatchEvaluation: Boolean get() = batchEvaluation

    fun clear() {
        count = 0
        batchEvaluation = false
    }

    fun add(start: Double, end: Double) {
        if (batchEvaluation) {
            append(start, end)
            return
        }
        var mergedStart = start
        var mergedEnd = end
        var insertionIndex = 0
        while (
            insertionIndex < count &&
            intervalEnd(insertionIndex) < mergedStart - mergeEpsilon
        ) {
            insertionIndex += 1
        }
        var afterMergedIndex = insertionIndex
        while (
            afterMergedIndex < count &&
            intervalStart(afterMergedIndex) <= mergedEnd + mergeEpsilon
        ) {
            mergedStart = min(mergedStart, intervalStart(afterMergedIndex))
            mergedEnd = max(mergedEnd, intervalEnd(afterMergedIndex))
            afterMergedIndex += 1
        }

        val removedCount = afterMergedIndex - insertionIndex
        val newCount = count - removedCount + 1
        if (newCount > MaximumOnlineIntervals) {
            append(start, end)
            batchEvaluation = true
            return
        }
        ensureCapacity(newCount)
        if (afterMergedIndex < count) {
            values.copyInto(
                destination = values,
                destinationOffset = (insertionIndex + 1) * 2,
                startIndex = afterMergedIndex * 2,
                endIndex = count * 2,
            )
        }
        values[insertionIndex * 2] = mergedStart
        values[insertionIndex * 2 + 1] = mergedEnd
        count = newCount
    }

    fun coversWholeOnline(): Boolean =
        !batchEvaluation && count > 0 &&
            intervalStart(0) <= mergeEpsilon &&
            intervalEnd(0) >= 1.0 - mergeEpsilon

    fun coversWhole(): Boolean {
        if (!batchEvaluation) return coversWholeOnline()
        sortIntervals(0, count - 1)
        var coveredEnd = 0.0
        for (index in 0 until count) {
            val start = intervalStart(index)
            if (start > coveredEnd + mergeEpsilon) return false
            coveredEnd = max(coveredEnd, intervalEnd(index))
            if (coveredEnd >= 1.0 - mergeEpsilon) return true
        }
        return false
    }

    fun coversWholeAfterAll(): Boolean = coversWhole()

    private fun append(start: Double, end: Double) {
        ensureCapacity(count + 1)
        values[count * 2] = start
        values[count * 2 + 1] = end
        count += 1
    }

    private fun sortIntervals(left: Int, right: Int) {
        if (left >= right) return
        var lower = left
        var upper = right
        val pivotIndex = left + (right - left) / 2
        val pivotStart = intervalStart(pivotIndex)
        val pivotEnd = intervalEnd(pivotIndex)
        while (lower <= upper) {
            while (compareToPivot(lower, pivotStart, pivotEnd) < 0) lower += 1
            while (compareToPivot(upper, pivotStart, pivotEnd) > 0) upper -= 1
            if (lower <= upper) {
                swap(lower, upper)
                lower += 1
                upper -= 1
            }
        }
        if (left < upper) sortIntervals(left, upper)
        if (lower < right) sortIntervals(lower, right)
    }

    private fun compareToPivot(index: Int, pivotStart: Double, pivotEnd: Double): Int {
        val start = intervalStart(index)
        if (start < pivotStart) return -1
        if (start > pivotStart) return 1
        val end = intervalEnd(index)
        return when {
            end > pivotEnd -> -1
            end < pivotEnd -> 1
            else -> 0
        }
    }

    private fun swap(first: Int, second: Int) {
        if (first == second) return
        val firstOffset = first * 2
        val secondOffset = second * 2
        val firstStart = values[firstOffset]
        val firstEnd = values[firstOffset + 1]
        values[firstOffset] = values[secondOffset]
        values[firstOffset + 1] = values[secondOffset + 1]
        values[secondOffset] = firstStart
        values[secondOffset + 1] = firstEnd
    }

    private fun ensureCapacity(requiredCount: Int) {
        val requiredValues = requiredCount * 2
        if (requiredValues > values.size) {
            values = values.copyOf(max(requiredValues, values.size * 2))
        }
    }

    private fun intervalStart(index: Int): Double = values[index * 2]

    private fun intervalEnd(index: Int): Double = values[index * 2 + 1]

    private companion object {
        const val MaximumOnlineIntervals = 128
    }
}

internal object ReferenceTangentViewportSupport {
    private const val Epsilon = 1e-9

    fun isGuaranteed(request: ReferencePathLabelRequest): Boolean {
        val baselineInset = request.edgeClearancePx - Epsilon
        val maximumDeltaX = max(
            0.0,
            request.viewport.right - request.viewport.left - baselineInset,
        )
        val maximumDeltaY = max(
            0.0,
            request.viewport.bottom - request.viewport.top - baselineInset,
        )
        return request.maximumTangentSourceDistancePx >= hypot(maximumDeltaX, maximumDeltaY)
    }
}

internal object ReferenceLocalPathBend {
    private const val Epsilon = 1e-9
    private const val AngleRoundingEpsilon = 1e-7

    fun centiDegrees(part: ReferencePreparedPathPart, sourceDistance: Double): Int {
        val ordinal = segmentOrdinalAt(part, sourceDistance)
        val segment = part.segments[ordinal]
        if (abs(sourceDistance - segment.sourceEndDistance) > Epsilon) return 0
        val next = part.segments.getOrNull(ordinal + 1) ?: return 0
        if (abs(next.sourceStartDistance - segment.sourceEndDistance) > Epsilon) return 0
        val firstAngle = atan2(segment.dy, segment.dx)
        var nextAngle = atan2(next.dy, next.dx)
        while (nextAngle - firstAngle > Math.PI) nextAngle -= Math.PI * 2.0
        while (nextAngle - firstAngle < -Math.PI) nextAngle += Math.PI * 2.0
        return ceil(abs(Math.toDegrees(nextAngle - firstAngle)) * 100.0 - AngleRoundingEpsilon)
            .toInt()
            .coerceAtLeast(0)
    }

    private fun segmentOrdinalAt(part: ReferencePreparedPathPart, sourceDistance: Double): Int {
        val canonical = sourceDistance.coerceIn(0.0, part.fullLength)
        var lower = 0
        var upper = part.segments.lastIndex
        while (lower < upper) {
            val middle = lower + (upper - lower) / 2
            if (canonical <= part.segments[middle].sourceEndDistance + Epsilon) {
                upper = middle
            } else {
                lower = middle + 1
            }
        }
        return lower
    }
}

internal data class ReferencePathSourcePosition(
    val partIndex: Int,
    val segmentIndex: Int,
    val segmentFraction: Double,
) {
    init {
        require(partIndex >= 0 && segmentIndex >= 0) { "source indices must be nonnegative" }
        require(segmentFraction.isFinite() && segmentFraction in 0.0..1.0) {
            "source segment fraction must be inside [0, 1]"
        }
    }
}

internal enum class ReferencePathLabelPlacementMode {
    CURVED,
    TANGENT_WIDE,
}

internal data class ReferencePathLabelRequest(
    val parts: List<List<ReferencePathLabelPoint>>,
    val viewport: ReferenceScreenRect,
    val shapedAdvancePx: Double,
    val endClearancePx: Double,
    val edgeClearancePx: Double,
    val maxBendDegrees: Double,
    val candidateId: ULong,
    val repeatSpacingPx: Int,
    val prominenceTier: ProminenceTier,
    val staticAvoidRects: List<ReferenceScreenRect> = emptyList(),
    val maximumTangentOffsetPx: Double = 0.0,
    val maximumTangentSourceDistancePx: Double = 0.0,
    val maximumCurvedSourceDistancePx: Double = 0.0,
    val allowCurvedPlacement: Boolean = true,
    val allowTangentFallback: Boolean = true,
)

internal data class ReferencePathLabelPlacement(
    val mode: ReferencePathLabelPlacementMode,
    val candidateId: ULong,
    val presentationPath: List<ReferencePathLabelPoint>,
    val anchor: ReferencePathLabelPoint,
    val sourcePosition: ReferencePathSourcePosition,
    val tangentDegrees: Double,
    val bendCentiDegrees: Int,
    val minimumClearanceQ8Px: Long,
    val centerDistanceQ8Px: Long,
    val repeatOrdinal: Long,
    val normalOffsetPx: Double,
) {
    val textScaleX: Double get() = 1.0
}

internal object ReferencePathLabelPlanner {
    private const val epsilon = 1e-9
    private const val angleRoundingEpsilon = 1e-7
    private const val q8Scale = 256.0
    private const val maximumCurvedCenterCandidates = 64
    private const val maximumMajorCurvedSpanEvaluations = 64
    private const val maximumCurvedPlacementsPerVisibleSpan = 16
    private const val maximumTangentEvaluationsPerVisibleSpan = 32
    private const val maximumMajorFailoverEvaluationsPerVisibleSpan = 64
    private const val maximumMajorSourceDiversityStrata = 24
    private const val screenScaleWiggleSmoothingPx = 8.0

    private data class CurvedHalfSpanInterval(
        val lowerQ8: Long,
        val upperQ8: Long,
    )

    private class CurvedHalfSpanCursor(
        private val fastSpans: List<Double>,
        private val halfAdvance: Double,
        private val boundedMaximum: Double,
        private val exhaustiveFailover: Boolean,
    ) {
        private var fastIndex = 0
        private var emittedCount = 0
        private var failoverQueue: PriorityQueue<CurvedHalfSpanInterval>? = null

        fun nextFast(): Double? {
            if (fastIndex >= fastSpans.size) return null
            emittedCount += 1
            return fastSpans[fastIndex++]
        }

        fun nextFailover(): Double? {
            if (!exhaustiveFailover || emittedCount >= maximumMajorCurvedSpanEvaluations) {
                return null
            }
            val queue = failoverQueue ?: createFailoverQueue().also { failoverQueue = it }
            while (queue.isNotEmpty()) {
                val interval = queue.remove()
                val midpointQ8 = interval.lowerQ8 +
                    (interval.upperQ8 - interval.lowerQ8) / 2L
                if (midpointQ8 <= interval.lowerQ8 || midpointQ8 >= interval.upperQ8) continue
                if (midpointQ8 - interval.lowerQ8 > 1L) {
                    queue += CurvedHalfSpanInterval(interval.lowerQ8, midpointQ8)
                }
                if (interval.upperQ8 - midpointQ8 > 1L) {
                    queue += CurvedHalfSpanInterval(midpointQ8, interval.upperQ8)
                }
                emittedCount += 1
                return midpointQ8.toDouble() / q8Scale
            }
            return null
        }

        private fun createFailoverQueue(): PriorityQueue<CurvedHalfSpanInterval> {
            val queue = PriorityQueue<CurvedHalfSpanInterval>(
                compareByDescending<CurvedHalfSpanInterval> {
                    it.upperQ8 - it.lowerQ8 - 1L
                }.thenBy { it.lowerQ8 },
            )
            val lowerQ8 = ceil(halfAdvance * q8Scale - epsilon).toLong()
            val upperQ8 = floor(boundedMaximum * q8Scale + epsilon).toLong()
            val boundaries = LongArray(fastSpans.size + 2)
            for (index in fastSpans.indices) {
                boundaries[index] = floor(fastSpans[index] * q8Scale + 0.5).toLong()
            }
            boundaries[fastSpans.size] = lowerQ8
            boundaries[fastSpans.size + 1] = upperQ8
            java.util.Arrays.sort(boundaries)
            var previous = boundaries[0]
            for (index in 1 until boundaries.size) {
                val current = boundaries[index]
                if (current == previous) continue
                if (current - previous > 1L) {
                    queue += CurvedHalfSpanInterval(previous, current)
                }
                previous = current
            }
            return queue
        }
    }

    fun plan(request: ReferencePathLabelRequest): List<ReferencePathLabelPlacement> {
        validate(request)
        val prepared = ReferenceVisiblePathProjector.prepareScreenParts(
            parts = request.parts,
            viewport = request.viewport,
        )
        return planPreparedValidated(request, prepared)
    }

    fun planPrepared(
        request: ReferencePathLabelRequest,
        prepared: ReferencePreparedPathGeometry,
    ): List<ReferencePathLabelPlacement> {
        validate(request)
        require(request.parts.isEmpty()) { "prepared path request must not retain raw parts" }
        require(request.viewport == prepared.viewport) {
            "prepared path viewport must match the label request"
        }
        return planPreparedValidated(request, prepared)
    }

    private fun planPreparedValidated(
        request: ReferencePathLabelRequest,
        prepared: ReferencePreparedPathGeometry,
    ): List<ReferencePathLabelPlacement> {
        val viewport = request.viewport
        val parts = prepared.parts
        if (parts.isEmpty()) return emptyList()

        val curved = if (request.allowCurvedPlacement) {
            parts.flatMap { part ->
                visibleSpans(part, viewport).flatMap { span -> curvedPlacements(request, span) }
            }
        } else {
            emptyList()
        }
        if (curved.isNotEmpty()) return rank(curved)
        if (!request.allowTangentFallback) return emptyList()
        if (request.prominenceTier !in setOf(ProminenceTier.GLOBAL_MAJOR, ProminenceTier.REGIONAL_MAJOR)) {
            return emptyList()
        }

        return rank(
            parts.flatMap { part ->
                visibleSpans(part, viewport).flatMap { span -> tangentPlacements(request, span) }
            },
        )
    }

    private fun validate(request: ReferencePathLabelRequest) {
        require(request.allowCurvedPlacement || request.allowTangentFallback) {
            "at least one path-label placement mode must be enabled"
        }
        require(request.shapedAdvancePx.isFinite() && request.shapedAdvancePx > 0.0) {
            "shaped advance must be finite and positive"
        }
        require(request.endClearancePx.isFinite() && request.endClearancePx >= 0.0) {
            "end clearance must be finite and nonnegative"
        }
        require(request.edgeClearancePx.isFinite() && request.edgeClearancePx >= 0.0) {
            "edge clearance must be finite and nonnegative"
        }
        require(request.maxBendDegrees.isFinite() && request.maxBendDegrees in 0.0..180.0) {
            "maximum bend must be finite and inside [0, 180]"
        }
        require(request.repeatSpacingPx > 0) { "repeat spacing must be positive" }
        require(
            request.maximumTangentOffsetPx.isFinite() && request.maximumTangentOffsetPx >= 0.0,
        ) { "maximum tangent offset must be finite and nonnegative" }
        require(
            request.maximumTangentSourceDistancePx.isFinite() &&
                request.maximumTangentSourceDistancePx >= 0.0,
        ) { "maximum tangent source distance must be finite and nonnegative" }
        require(
            request.maximumCurvedSourceDistancePx.isFinite() &&
                request.maximumCurvedSourceDistancePx >= 0.0,
        ) { "maximum curved source distance must be finite and nonnegative" }
    }

    private class SupportIntervalWorkspace(initialIntervalCapacity: Int) {
        private val intervals = ReferenceSupportIntervalUnion(
            mergeEpsilon = epsilon,
            initialIntervalCapacity = initialIntervalCapacity,
        )
        val bandScratch = DoubleArray(4)
        val segmentQueryScratch = ReferenceScreenSegmentAabbQueryScratch()

        fun clear() {
            intervals.clear()
        }

        fun add(start: Double, end: Double) {
            intervals.add(start, end)
        }

        fun coversWholeOnline(): Boolean = intervals.coversWholeOnline()

        fun coversWholeAfterAll(): Boolean = intervals.coversWholeAfterAll()
    }

    private data class SourceOrdinalWindow(
        val startInclusive: Int,
        val endExclusive: Int,
    )

    private data class VisibleSpan(
        val part: ReferencePreparedPathPart,
        val sourceStartDistance: Double,
        val sourceEndDistance: Double,
    )

    private data class CenterCandidate(
        val sourceDistance: Double,
        val sourceDistanceQ8: Long,
        val viewportDistanceQ8: Long,
    )

    private data class CurvedCenterSearch(
        val centerDistance: Double,
        val anchor: ReferencePathLabelPoint,
        val sourcePosition: ReferencePathSourcePosition,
        val halfSpans: CurvedHalfSpanCursor,
        var accepted: Boolean = false,
    )

    private data class ClipInterval(val startFraction: Double, val endFraction: Double)

    private fun visibleSpans(
        part: ReferencePreparedPathPart,
        viewport: ReferenceScreenRect,
    ): List<VisibleSpan> {
        val result = mutableListOf<VisibleSpan>()
        var currentStart: Double? = null
        var currentEnd = 0.0
        part.segments.forEach { segment ->
            val start = segment.sourceStartDistance +
                segment.visibleStartFraction * segment.length
            val end = segment.sourceStartDistance +
                segment.visibleEndFraction * segment.length
            if (currentStart == null) {
                currentStart = start
                currentEnd = end
            } else if (start <= currentEnd + epsilon) {
                currentEnd = max(currentEnd, end)
            } else {
                result += VisibleSpan(part, currentStart!!, currentEnd)
                currentStart = start
                currentEnd = end
            }
        }
        currentStart?.let { result += VisibleSpan(part, it, currentEnd) }
        return result
    }

    private fun clipToRect(
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
        rect: ReferenceScreenRect,
    ): ClipInterval? = clipToRect(start.x, start.y, end.x, end.y, rect)

    private fun clipToRect(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        rect: ReferenceScreenRect,
    ): ClipInterval? {
        val dx = endX - startX
        val dy = endY - startY
        var lower = 0.0
        var upper = 1.0

        fun clip(p: Double, q: Double): Boolean {
            if (abs(p) <= epsilon) return q >= -epsilon
            val ratio = q / p
            if (p < 0.0) {
                if (ratio > upper + epsilon) return false
                lower = max(lower, ratio)
            } else {
                if (ratio < lower - epsilon) return false
                upper = min(upper, ratio)
            }
            return lower <= upper + epsilon
        }

        return if (
            clip(-dx, startX - rect.left) &&
            clip(dx, rect.right - startX) &&
            clip(-dy, startY - rect.top) &&
            clip(dy, rect.bottom - startY)
        ) {
            ClipInterval(lower.coerceIn(0.0, 1.0), upper.coerceIn(0.0, 1.0))
        } else {
            null
        }
    }

    private fun curvedPlacements(
        request: ReferencePathLabelRequest,
        span: VisibleSpan,
    ): List<ReferencePathLabelPlacement> {
        val halfAdvance = request.shapedAdvancePx / 2.0
        val minimumCenter = span.sourceStartDistance + request.endClearancePx + halfAdvance
        val maximumCenter = span.sourceEndDistance - request.endClearancePx - halfAdvance
        if (minimumCenter > maximumCenter + epsilon) return emptyList()

        val centers = candidateDistances(request, span, minimumCenter, maximumCenter)
        val maximumBendCenti = floor(request.maxBendDegrees * 100.0 + angleRoundingEpsilon).toInt()
        val supportWorkspace = SupportIntervalWorkspace(min(span.part.segments.size * 3, 64))
        val exhaustiveFailover = request.prominenceTier in setOf(
            ProminenceTier.GLOBAL_MAJOR,
            ProminenceTier.REGIONAL_MAJOR,
        )
        val searches = centers.mapNotNull { centerDistance ->
            val availableHalfSpan = min(
                centerDistance - span.sourceStartDistance - request.endClearancePx,
                span.sourceEndDistance - request.endClearancePx - centerDistance,
            )
            val halfSpans = curvedHalfSpanCursor(
                halfAdvance = halfAdvance,
                availableHalfSpan = availableHalfSpan,
                shapedAdvancePx = request.shapedAdvancePx,
                maximumSourceDistancePx = request.maximumCurvedSourceDistancePx,
                exhaustiveFailover = exhaustiveFailover,
            ) ?: return@mapNotNull null
            CurvedCenterSearch(
                centerDistance = centerDistance,
                anchor = pointAt(span.part, centerDistance),
                sourcePosition = sourcePositionAt(span.part, centerDistance),
                halfSpans = halfSpans,
            )
        }
        val placements = ArrayList<ReferencePathLabelPlacement>(
            min(searches.size, maximumCurvedPlacementsPerVisibleSpan),
        )
        for (search in searches) {
            while (true) {
                val sourceHalfSpan = search.halfSpans.nextFast() ?: break
                val placement = curvedPlacementAtCenter(
                    request = request,
                    span = span,
                    search = search,
                    sourceHalfSpan = sourceHalfSpan,
                    maximumBendCenti = maximumBendCenti,
                    supportWorkspace = supportWorkspace,
                ) ?: continue
                placements += placement
                search.accepted = true
                if (placements.size >= maximumCurvedPlacementsPerVisibleSpan) {
                    return placements
                }
                break
            }
        }
        if (!exhaustiveFailover) return placements

        var remainingFailover = maximumMajorFailoverEvaluationsPerVisibleSpan
        while (
            remainingFailover > 0 &&
            placements.size < maximumCurvedPlacementsPerVisibleSpan
        ) {
            var advanced = false
            for (search in searches) {
                if (search.accepted) continue
                val sourceHalfSpan = search.halfSpans.nextFailover() ?: continue
                advanced = true
                remainingFailover -= 1
                val placement = curvedPlacementAtCenter(
                    request = request,
                    span = span,
                    search = search,
                    sourceHalfSpan = sourceHalfSpan,
                    maximumBendCenti = maximumBendCenti,
                    supportWorkspace = supportWorkspace,
                )
                if (placement != null) {
                    placements += placement
                    search.accepted = true
                    if (placements.size >= maximumCurvedPlacementsPerVisibleSpan) break
                }
                if (remainingFailover == 0) break
            }
            if (!advanced) break
        }
        return placements
    }

    private fun curvedPlacementAtCenter(
        request: ReferencePathLabelRequest,
        span: VisibleSpan,
        search: CurvedCenterSearch,
        sourceHalfSpan: Double,
        maximumBendCenti: Int,
        supportWorkspace: SupportIntervalWorkspace,
    ): ReferencePathLabelPlacement? {
        val sourceStart = search.centerDistance - sourceHalfSpan
        val sourceEnd = search.centerDistance + sourceHalfSpan
        val sourcePath = extractPath(span.part, sourceStart, sourceEnd)
        if (sourcePath.size < 2) return null
        val presentationSourceDistance = request.maximumCurvedSourceDistancePx
        val smoothingDistance = min(
            presentationSourceDistance,
            max(screenScaleWiggleSmoothingPx, presentationSourceDistance / 2.0),
        )
        val simplifiedPath = simplifyPresentationPath(
            sourcePath,
            smoothingDistance,
        )
        val simplifiedPathLength = polylineLength(simplifiedPath)
        if (simplifiedPathLength + epsilon < request.shapedAdvancePx) {
            return null
        }
        val presentationPath = centeredSubpath(
            simplifiedPath,
            simplifiedPathLength,
            request.shapedAdvancePx,
        ) ?: return null
        val bendCenti = bendCentiDegrees(presentationPath)
        if (bendCenti > maximumBendCenti) return null
        val uprightPresentationPath = keepUpright(presentationPath)
        val pathDx = uprightPresentationPath.last().x - uprightPresentationPath.first().x
        val pathDy = uprightPresentationPath.last().y - uprightPresentationPath.first().y
        val pathLength = hypot(pathDx, pathDy)
        if (pathLength <= epsilon) return null
        val normalX = -pathDy / pathLength
        val normalY = pathDx / pathLength
        for (normalOffset in curvedNormalOffsets(presentationSourceDistance)) {
            val offsetX = normalX * normalOffset
            val offsetY = normalY * normalOffset
            val offsetPath = if (abs(normalOffset) <= epsilon) {
                uprightPresentationPath
            } else {
                uprightPresentationPath.map { point ->
                    ReferencePathLabelPoint(point.x + offsetX, point.y + offsetY)
                }
            }
            val clearance = minimumClearance(
                offsetPath,
                request.viewport,
                request.staticAvoidRects,
                request.edgeClearancePx,
            ) ?: continue
            if (
                presentationSourceDistance > epsilon &&
                !pathHasSourceSupport(
                    presentationPath = offsetPath,
                    part = span.part,
                    sourceStartDistance = sourceStart,
                    sourceEndDistance = sourceEnd,
                    maximumDistancePx = presentationSourceDistance,
                    workspace = supportWorkspace,
                )
            ) {
                continue
            }
            return placement(
                request = request,
                mode = ReferencePathLabelPlacementMode.CURVED,
                path = offsetPath,
                anchor = ReferencePathLabelPoint(
                    search.anchor.x + offsetX,
                    search.anchor.y + offsetY,
                ),
                sourcePosition = search.sourcePosition,
                tangentDegrees = uprightDegrees(tangentAt(span.part, search.centerDistance)),
                bendCenti = bendCenti,
                clearance = clearance,
                centerDistance = search.centerDistance,
                normalOffset = normalOffset,
            )
        }
        return null
    }

    private fun curvedNormalOffsets(maximum: Double): DoubleArray {
        if (maximum <= epsilon) return doubleArrayOf(0.0)
        val half = maximum / 2.0
        return doubleArrayOf(0.0, half, -half, maximum, -maximum)
    }

    private fun curvedHalfSpanCursor(
        halfAdvance: Double,
        availableHalfSpan: Double,
        shapedAdvancePx: Double,
        maximumSourceDistancePx: Double,
        exhaustiveFailover: Boolean,
    ): CurvedHalfSpanCursor? {
        if (availableHalfSpan + epsilon < halfAdvance) return null
        if (maximumSourceDistancePx <= epsilon) {
            return CurvedHalfSpanCursor(
                fastSpans = listOf(halfAdvance),
                halfAdvance = halfAdvance,
                boundedMaximum = halfAdvance,
                exhaustiveFailover = false,
            )
        }
        val maximumOverscan = max(
            maximumSourceDistancePx * 4.0,
            shapedAdvancePx / 4.0,
        )
        // The fit check above deliberately accepts sub-pixel arithmetic error. Normalize
        // that epsilon-close case before passing the bounds to coerceIn.
        val boundedMaximum = max(
            halfAdvance,
            min(availableHalfSpan, halfAdvance + maximumOverscan),
        )
        val spans = linkedMapOf<Long, Double>()
        fun add(value: Double) {
            if (value > availableHalfSpan + epsilon) return
            val canonical = value.coerceIn(halfAdvance, boundedMaximum)
            spans.putIfAbsent(toQ8(canonical), canonical)
        }
        add(halfAdvance)
        add(halfAdvance + maximumSourceDistancePx)
        add(
            halfAdvance + max(
                maximumSourceDistancePx * 2.0,
                shapedAdvancePx / 8.0,
            ),
        )
        add(boundedMaximum)
        return CurvedHalfSpanCursor(
            fastSpans = spans.values.toList(),
            halfAdvance = halfAdvance,
            boundedMaximum = boundedMaximum,
            exhaustiveFailover = exhaustiveFailover,
        )
    }

    private fun tangentPlacements(
        request: ReferencePathLabelRequest,
        span: VisibleSpan,
    ): List<ReferencePathLabelPlacement> {
        val supportWorkspace = SupportIntervalWorkspace(64)
        val sourceSupportGuaranteedByViewport =
            ReferenceTangentViewportSupport.isGuaranteed(request)
        val sourceOrdinalWindow = if (sourceSupportGuaranteedByViewport) {
            null
        } else {
            sourceOrdinalWindow(
                span.part,
                span.sourceStartDistance,
                span.sourceEndDistance,
            ) ?: return emptyList()
        }
        val centers = candidateDistances(
            request,
            span,
            span.sourceStartDistance,
            span.sourceEndDistance,
        )
        val offsets = tangentOffsets(request)
        val placements = ArrayList<ReferencePathLabelPlacement>(
            maximumCurvedPlacementsPerVisibleSpan,
        )
        var evaluations = 0
        centerLoop@ for (centerDistance in centers) {
            val anchor = pointAt(span.part, centerDistance)
            val sourcePosition = sourcePositionAt(span.part, centerDistance)
            val sourceTangent = tangentAt(span.part, centerDistance)
            val tangent = uprightRadians(sourceTangent)
            val dx = cos(tangent) * request.shapedAdvancePx / 2.0
            val dy = kotlin.math.sin(tangent) * request.shapedAdvancePx / 2.0
            val normalX = -kotlin.math.sin(tangent)
            val normalY = cos(tangent)
            for (normalOffset in offsets) {
                if (evaluations >= maximumTangentEvaluationsPerVisibleSpan) break@centerLoop
                evaluations += 1
                val offsetX = normalX * normalOffset
                val offsetY = normalY * normalOffset
                val startX = anchor.x - dx + offsetX
                val startY = anchor.y - dy + offsetY
                val endX = anchor.x + dx + offsetX
                val endY = anchor.y + dy + offsetY
                val clearance = minimumClearanceForSegment(
                    startX,
                    startY,
                    endX,
                    endY,
                    request.viewport,
                    request.staticAvoidRects,
                    request.edgeClearancePx,
                ) ?: continue
                if (
                    !sourceSupportGuaranteedByViewport &&
                    !segmentHasSourceSupport(
                        queryStartX = startX,
                        queryStartY = startY,
                        queryEndX = endX,
                        queryEndY = endY,
                        part = span.part,
                        sourceStartDistance = span.sourceStartDistance,
                        sourceEndDistance = span.sourceEndDistance,
                        sourceOrdinalStartInclusive = sourceOrdinalWindow!!.startInclusive,
                        sourceOrdinalEndExclusive = sourceOrdinalWindow.endExclusive,
                        maximumDistancePx = request.maximumTangentSourceDistancePx,
                        workspace = supportWorkspace,
                    )
                ) {
                    continue
                }
                val path = listOf(
                    ReferencePathLabelPoint(startX, startY),
                    ReferencePathLabelPoint(endX, endY),
                )
                placements += placement(
                    request = request,
                    mode = ReferencePathLabelPlacementMode.TANGENT_WIDE,
                    path = path,
                    anchor = anchor,
                    sourcePosition = sourcePosition,
                    tangentDegrees = Math.toDegrees(tangent),
                    bendCenti = ReferenceLocalPathBend.centiDegrees(span.part, centerDistance),
                    clearance = clearance,
                    centerDistance = centerDistance,
                    normalOffset = normalOffset,
                )
                if (placements.size >= maximumCurvedPlacementsPerVisibleSpan) break@centerLoop
            }
        }
        return placements
    }

    private fun tangentOffsets(request: ReferencePathLabelRequest): List<Double> {
        val maximum = request.maximumTangentOffsetPx
        if (maximum <= epsilon) return listOf(0.0)
        val step = max(request.edgeClearancePx * 2.0, maximum / 4.0)
            .coerceAtMost(maximum)
        val offsets = linkedMapOf<Long, Double>()
        fun add(value: Double) {
            val canonical = value.coerceIn(-maximum, maximum)
            offsets.putIfAbsent(toSignedQ8(canonical), canonical)
        }
        add(0.0)
        var magnitude = step
        while (magnitude < maximum - epsilon) {
            add(magnitude)
            add(-magnitude)
            magnitude += step
        }
        add(maximum)
        add(-maximum)
        return offsets.values.toList()
    }

    private fun pathHasSourceSupport(
        presentationPath: List<ReferencePathLabelPoint>,
        part: ReferencePreparedPathPart,
        sourceStartDistance: Double,
        sourceEndDistance: Double,
        maximumDistancePx: Double,
        workspace: SupportIntervalWorkspace,
    ): Boolean {
        val sourceOrdinalWindow = sourceOrdinalWindow(
            part,
            sourceStartDistance,
            sourceEndDistance,
        ) ?: return false
        for (index in 0 until presentationPath.lastIndex) {
            if (
                !segmentHasSourceSupport(
                    queryStartX = presentationPath[index].x,
                    queryStartY = presentationPath[index].y,
                    queryEndX = presentationPath[index + 1].x,
                    queryEndY = presentationPath[index + 1].y,
                    part = part,
                    sourceStartDistance = sourceStartDistance,
                    sourceEndDistance = sourceEndDistance,
                    sourceOrdinalStartInclusive = sourceOrdinalWindow.startInclusive,
                    sourceOrdinalEndExclusive = sourceOrdinalWindow.endExclusive,
                    maximumDistancePx = maximumDistancePx,
                    workspace = workspace,
                )
            ) return false
        }
        return true
    }

    private fun segmentHasSourceSupport(
        queryStartX: Double,
        queryStartY: Double,
        queryEndX: Double,
        queryEndY: Double,
        part: ReferencePreparedPathPart,
        sourceStartDistance: Double,
        sourceEndDistance: Double,
        sourceOrdinalStartInclusive: Int,
        sourceOrdinalEndExclusive: Int,
        maximumDistancePx: Double,
        workspace: SupportIntervalWorkspace,
    ): Boolean {
        workspace.clear()
        part.supportIndex.queryInto(
            queryStartX = queryStartX,
            queryStartY = queryStartY,
            queryEndX = queryEndX,
            queryEndY = queryEndY,
            radiusPx = maximumDistancePx,
            sourceOrdinalStartInclusive = sourceOrdinalStartInclusive,
            sourceOrdinalEndExclusive = sourceOrdinalEndExclusive,
            scratch = workspace.segmentQueryScratch,
        )
        for (matchIndex in 0 until workspace.segmentQueryScratch.size) {
            val segment = part.supportIndex.matchAt(workspace.segmentQueryScratch, matchIndex)
            val clippedStartDistance = max(sourceStartDistance, segment.sourceStartDistance)
            val clippedEndDistance = min(sourceEndDistance, segment.sourceEndDistance)
            if (clippedStartDistance > clippedEndDistance + epsilon) continue
            val startFraction = (
                (clippedStartDistance - segment.sourceStartDistance) / segment.length
                ).coerceIn(0.0, 1.0)
            val endFraction = (
                (clippedEndDistance - segment.sourceStartDistance) / segment.length
                ).coerceIn(0.0, 1.0)
            addCapsuleSupportIntervals(
                queryStartX = queryStartX,
                queryStartY = queryStartY,
                queryEndX = queryEndX,
                queryEndY = queryEndY,
                sourceStartX = segment.start.x + segment.dx * startFraction,
                sourceStartY = segment.start.y + segment.dy * startFraction,
                sourceEndX = segment.start.x + segment.dx * endFraction,
                sourceEndY = segment.start.y + segment.dy * endFraction,
                radius = maximumDistancePx,
                workspace = workspace,
            )
            if (workspace.coversWholeOnline()) return true
        }
        return workspace.coversWholeAfterAll()
    }

    private fun sourceOrdinalWindow(
        part: ReferencePreparedPathPart,
        sourceStartDistance: Double,
        sourceEndDistance: Double,
    ): SourceOrdinalWindow? {
        val startInclusive = ReferenceSourceSegmentOrdinalWindow.startInclusive(
            part.segments,
            sourceStartDistance,
            epsilon,
        )
        val endExclusive = ReferenceSourceSegmentOrdinalWindow.endExclusive(
            part.segments,
            sourceEndDistance,
            epsilon,
        )
        return if (startInclusive < endExclusive) {
            SourceOrdinalWindow(startInclusive, endExclusive)
        } else {
            null
        }
    }

    private fun simplifyPresentationPath(
        sourcePath: List<ReferencePathLabelPoint>,
        maximumDistancePx: Double,
    ): List<ReferencePathLabelPoint> {
        if (sourcePath.size <= 2 || maximumDistancePx <= epsilon) return sourcePath
        val keep = BooleanArray(sourcePath.size)
        keep[0] = true
        keep[sourcePath.lastIndex] = true
        val stack = IntArray(sourcePath.size * 2)
        var stackSize = 0
        stack[stackSize++] = 0
        stack[stackSize++] = sourcePath.lastIndex
        while (stackSize > 0) {
            val endIndex = stack[--stackSize]
            val startIndex = stack[--stackSize]
            var farthestIndex = -1
            var farthestDistance = -1.0
            for (index in startIndex + 1 until endIndex) {
                val distance = pointSegmentDistance(
                    sourcePath[index],
                    sourcePath[startIndex],
                    sourcePath[endIndex],
                )
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthestIndex = index
                }
            }
            if (farthestIndex >= 0 && farthestDistance > maximumDistancePx + epsilon) {
                keep[farthestIndex] = true
                stack[stackSize++] = startIndex
                stack[stackSize++] = farthestIndex
                stack[stackSize++] = farthestIndex
                stack[stackSize++] = endIndex
            }
        }
        val simplified = ArrayList<ReferencePathLabelPoint>(sourcePath.size)
        sourcePath.forEachIndexed { index, point ->
            if (keep[index]) simplified += point
        }
        return simplified
    }

    private fun polylineLength(path: List<ReferencePathLabelPoint>): Double {
        var length = 0.0
        for (index in 0 until path.lastIndex) {
            length += hypot(
                path[index + 1].x - path[index].x,
                path[index + 1].y - path[index].y,
            )
        }
        return length
    }

    private fun centeredSubpath(
        path: List<ReferencePathLabelPoint>,
        fullLength: Double,
        targetLength: Double,
    ): List<ReferencePathLabelPoint>? {
        if (path.size < 2 || fullLength + epsilon < targetLength) return null
        val startDistance = (fullLength - targetLength) / 2.0
        val endDistance = startDistance + targetLength
        val result = ArrayList<ReferencePathLabelPoint>(path.size)
        var cumulative = 0.0
        var started = false
        for (index in 0 until path.lastIndex) {
            val start = path[index]
            val end = path[index + 1]
            val length = hypot(end.x - start.x, end.y - start.y)
            if (length <= epsilon) continue
            val segmentEndDistance = cumulative + length
            if (!started && startDistance <= segmentEndDistance + epsilon) {
                appendDistinct(
                    result,
                    interpolatePoint(start, end, (startDistance - cumulative) / length),
                )
                started = true
            }
            if (
                started &&
                segmentEndDistance > startDistance + epsilon &&
                segmentEndDistance < endDistance - epsilon
            ) {
                appendDistinct(result, end)
            }
            if (started && endDistance <= segmentEndDistance + epsilon) {
                appendDistinct(
                    result,
                    interpolatePoint(start, end, (endDistance - cumulative) / length),
                )
                return result
            }
            cumulative = segmentEndDistance
        }
        return null
    }

    private fun interpolatePoint(
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
        fraction: Double,
    ): ReferencePathLabelPoint {
        val canonical = fraction.coerceIn(0.0, 1.0)
        return ReferencePathLabelPoint(
            start.x + (end.x - start.x) * canonical,
            start.y + (end.y - start.y) * canonical,
        )
    }

    private fun addCapsuleSupportIntervals(
        queryStartX: Double,
        queryStartY: Double,
        queryEndX: Double,
        queryEndY: Double,
        sourceStartX: Double,
        sourceStartY: Double,
        sourceEndX: Double,
        sourceEndY: Double,
        radius: Double,
        workspace: SupportIntervalWorkspace,
    ) {
        addCircleSupportInterval(
            queryStartX, queryStartY, queryEndX, queryEndY,
            sourceStartX, sourceStartY, radius, workspace,
        )
        addCircleSupportInterval(
            queryStartX, queryStartY, queryEndX, queryEndY,
            sourceEndX, sourceEndY, radius, workspace,
        )

        val sourceDx = sourceEndX - sourceStartX
        val sourceDy = sourceEndY - sourceStartY
        val sourceLengthSquared = sourceDx * sourceDx + sourceDy * sourceDy
        if (sourceLengthSquared <= epsilon) return
        val queryDx = queryEndX - queryStartX
        val queryDy = queryEndY - queryStartY
        val relativeX = queryStartX - sourceStartX
        val relativeY = queryStartY - sourceStartY
        if (!linearBandInterval(
            offset = relativeX * sourceDx + relativeY * sourceDy,
            slope = queryDx * sourceDx + queryDy * sourceDy,
            lower = 0.0,
            upper = sourceLengthSquared,
            destination = workspace.bandScratch,
            destinationOffset = 0,
        )) return
        val maximumCross = radius * sqrt(sourceLengthSquared)
        if (!linearBandInterval(
            offset = sourceDx * relativeY - sourceDy * relativeX,
            slope = sourceDx * queryDy - sourceDy * queryDx,
            lower = -maximumCross,
            upper = maximumCross,
            destination = workspace.bandScratch,
            destinationOffset = 2,
        )) return
        addClippedSupportInterval(
            max(workspace.bandScratch[0], workspace.bandScratch[2]),
            min(workspace.bandScratch[1], workspace.bandScratch[3]),
            workspace,
        )
    }

    private fun addCircleSupportInterval(
        queryStartX: Double,
        queryStartY: Double,
        queryEndX: Double,
        queryEndY: Double,
        centerX: Double,
        centerY: Double,
        radius: Double,
        workspace: SupportIntervalWorkspace,
    ) {
        val dx = queryEndX - queryStartX
        val dy = queryEndY - queryStartY
        val relativeX = queryStartX - centerX
        val relativeY = queryStartY - centerY
        val a = dx * dx + dy * dy
        val b = 2.0 * (relativeX * dx + relativeY * dy)
        val c = relativeX * relativeX + relativeY * relativeY - radius * radius
        val discriminant = b * b - 4.0 * a * c
        val tolerance = epsilon * max(1.0, abs(b * b) + abs(4.0 * a * c))
        if (discriminant < -tolerance) return
        val root = sqrt(max(0.0, discriminant))
        addClippedSupportInterval(
            (-b - root) / (2.0 * a),
            (-b + root) / (2.0 * a),
            workspace,
        )
    }

    private fun linearBandInterval(
        offset: Double,
        slope: Double,
        lower: Double,
        upper: Double,
        destination: DoubleArray,
        destinationOffset: Int,
    ): Boolean {
        if (abs(slope) <= epsilon) {
            if (offset < lower - epsilon || offset > upper + epsilon) return false
            destination[destinationOffset] = 0.0
            destination[destinationOffset + 1] = 1.0
            return true
        }
        val first = (lower - offset) / slope
        val second = (upper - offset) / slope
        val start = max(0.0, min(first, second))
        val end = min(1.0, max(first, second))
        if (start > end + epsilon) return false
        destination[destinationOffset] = start
        destination[destinationOffset + 1] = end
        return true
    }

    private fun addClippedSupportInterval(
        start: Double,
        end: Double,
        workspace: SupportIntervalWorkspace,
    ) {
        val clippedStart = max(0.0, start)
        val clippedEnd = min(1.0, end)
        if (clippedStart <= clippedEnd + epsilon) {
            workspace.add(clippedStart, clippedEnd)
        }
    }

    private fun candidateDistances(
        request: ReferencePathLabelRequest,
        span: VisibleSpan,
        minimumDistance: Double,
        maximumDistance: Double,
    ): List<Double> {
        if (minimumDistance > maximumDistance + epsilon) return emptyList()
        val canonicalMinimumDistance = min(minimumDistance, maximumDistance)
        val canonicalMaximumDistance = max(minimumDistance, maximumDistance)
        val bestFirst = compareBy<CenterCandidate> { it.viewportDistanceQ8 }
            .thenBy { it.sourceDistanceQ8 }
        val selected = PriorityQueue<CenterCandidate>(
            maximumCurvedCenterCandidates,
            bestFirst.reversed(),
        )
        val reserved = ArrayList<CenterCandidate>(
            maximumMajorSourceDiversityStrata + 8,
        )
        val selectedQ8 = HashSet<Long>(maximumCurvedCenterCandidates * 2)
        val viewportCenterX = (request.viewport.left + request.viewport.right) / 2.0
        val viewportCenterY = (request.viewport.top + request.viewport.bottom) / 2.0
        fun add(
            distance: Double,
            knownAnchor: ReferencePathLabelPoint? = null,
            reserve: Boolean = false,
        ) {
            if (
                distance < canonicalMinimumDistance - epsilon ||
                distance > canonicalMaximumDistance + epsilon
            ) return
            val canonical = distance.coerceIn(canonicalMinimumDistance, canonicalMaximumDistance)
            val sourceDistanceQ8 = toQ8(canonical)
            if (sourceDistanceQ8 in selectedQ8) return
            val anchor = knownAnchor ?: pointAt(span.part, canonical)
            if (request.staticAvoidRects.any { rect ->
                    pointRectDistance(anchor, rect) <= request.edgeClearancePx + epsilon
                }
            ) {
                return
            }
            val viewportDistanceQ8 = toQ8(
                hypot(anchor.x - viewportCenterX, anchor.y - viewportCenterY),
            )
            val candidate = CenterCandidate(canonical, sourceDistanceQ8, viewportDistanceQ8)
            if (reserve) {
                reserved += candidate
                selectedQ8 += sourceDistanceQ8
                return
            }
            val nearestCapacity = maximumCurvedCenterCandidates - reserved.size
            if (nearestCapacity <= 0) return
            val worst = selected.peek()
            if (
                worst != null &&
                selected.size >= nearestCapacity &&
                (
                    viewportDistanceQ8 > worst.viewportDistanceQ8 ||
                    (
                        viewportDistanceQ8 == worst.viewportDistanceQ8 &&
                        sourceDistanceQ8 >= worst.sourceDistanceQ8
                    )
                )
            ) {
                return
            }
            if (selected.size >= nearestCapacity) {
                val removed = selected.remove()
                selectedQ8.remove(removed.sourceDistanceQ8)
            }
            selected += candidate
            selectedQ8 += sourceDistanceQ8
        }

        val isMajorFeature = request.prominenceTier in setOf(
            ProminenceTier.GLOBAL_MAJOR,
            ProminenceTier.REGIONAL_MAJOR,
        )
        if (isMajorFeature) {
            val sourceSpan = canonicalMaximumDistance - canonicalMinimumDistance
            val sourceTolerance = request.maximumCurvedSourceDistancePx
            if (sourceTolerance > epsilon) {
                doubleArrayOf(
                    sourceTolerance / 4.0,
                    sourceTolerance / 2.0,
                    sourceTolerance,
                    sourceTolerance * 1.25,
                ).forEach { inset ->
                    add(canonicalMinimumDistance + inset, reserve = true)
                    add(canonicalMaximumDistance - inset, reserve = true)
                }
            }
            if (sourceSpan > epsilon) {
                repeat(maximumMajorSourceDiversityStrata) { index ->
                    val fraction = (index + 0.5) / maximumMajorSourceDiversityStrata.toDouble()
                    add(
                        canonicalMinimumDistance + sourceSpan * fraction,
                        reserve = true,
                    )
                }
            }
        }

        add((canonicalMinimumDistance + canonicalMaximumDistance) / 2.0)
        add(canonicalMinimumDistance)
        add(canonicalMaximumDistance)
        val center = ReferencePathLabelPoint(viewportCenterX, viewportCenterY)
        span.part.segments.forEach { segment ->
            val overlapStart = max(canonicalMinimumDistance, segment.sourceStartDistance)
            val overlapEnd = min(canonicalMaximumDistance, segment.sourceEndDistance)
            if (overlapStart <= overlapEnd + epsilon) {
                val canonicalOverlapStart = min(overlapStart, overlapEnd)
                val canonicalOverlapEnd = max(overlapStart, overlapEnd)
                val projection = projectedSourceDistance(segment, center)
                    .coerceIn(canonicalOverlapStart, canonicalOverlapEnd)
                val fraction = ((projection - segment.sourceStartDistance) / segment.length)
                    .coerceIn(0.0, 1.0)
                add(
                    projection,
                    ReferencePathLabelPoint(
                        segment.start.x + segment.dx * fraction,
                        segment.start.y + segment.dy * fraction,
                    ),
                )
            }
        }

        val spacing = request.repeatSpacingPx.toDouble()
        val phase = (request.candidateId % request.repeatSpacingPx.toULong()).toDouble()
        var ordinal = ceil((minimumDistance - phase) / spacing - epsilon).toLong()
        var distance = phase + ordinal.toDouble() * spacing
        while (distance <= maximumDistance + epsilon) {
            add(distance)
            if (ordinal == Long.MAX_VALUE) break
            ordinal += 1L
            distance = phase + ordinal.toDouble() * spacing
            if (!distance.isFinite()) break
        }
        return (reserved + selected).sortedWith(bestFirst).map { it.sourceDistance }
    }

    private fun projectedSourceDistance(
        segment: ReferencePreparedPathSegment,
        point: ReferencePathLabelPoint,
    ): Double {
        val denominator = segment.length * segment.length
        val fraction = (
            ((point.x - segment.start.x) * segment.dx + (point.y - segment.start.y) * segment.dy) /
                denominator
            ).coerceIn(0.0, 1.0)
        return segment.sourceStartDistance + fraction * segment.length
    }

    private fun extractPath(
        part: ReferencePreparedPathPart,
        sourceStartDistance: Double,
        sourceEndDistance: Double,
    ): List<ReferencePathLabelPoint> {
        val startSegmentIndex = segmentIndexAt(part, sourceStartDistance)
        val endSegmentIndex = segmentIndexAt(part, sourceEndDistance)
        val result = ArrayList<ReferencePathLabelPoint>(endSegmentIndex - startSegmentIndex + 2)
        result += pointAt(part, sourceStartDistance)
        for (index in startSegmentIndex..endSegmentIndex) {
            val segment = part.segments[index]
            if (
                segment.sourceEndDistance > sourceStartDistance + epsilon &&
                segment.sourceEndDistance < sourceEndDistance - epsilon
            ) {
                appendDistinct(result, segment.end)
            }
        }
        appendDistinct(result, pointAt(part, sourceEndDistance))
        return result
    }

    private fun appendDistinct(
        points: MutableList<ReferencePathLabelPoint>,
        point: ReferencePathLabelPoint,
    ) {
        val previous = points.lastOrNull()
        if (previous == null || hypot(point.x - previous.x, point.y - previous.y) > epsilon) {
            points += point
        }
    }

    private fun pointAt(
        part: ReferencePreparedPathPart,
        sourceDistance: Double,
    ): ReferencePathLabelPoint {
        val segment = segmentAt(part, sourceDistance)
        val fraction = ((sourceDistance - segment.sourceStartDistance) / segment.length).coerceIn(0.0, 1.0)
        return ReferencePathLabelPoint(
            segment.start.x + segment.dx * fraction,
            segment.start.y + segment.dy * fraction,
        )
    }

    private fun sourcePositionAt(
        part: ReferencePreparedPathPart,
        sourceDistance: Double,
    ): ReferencePathSourcePosition {
        val segment = segmentAt(part, sourceDistance)
        val fraction = ((sourceDistance - segment.sourceStartDistance) / segment.length).coerceIn(0.0, 1.0)
        return ReferencePathSourcePosition(part.partIndex, segment.sourceIndex, fraction)
    }

    private fun segmentAt(
        part: ReferencePreparedPathPart,
        sourceDistance: Double,
    ): ReferencePreparedPathSegment {
        return part.segments[segmentIndexAt(part, sourceDistance)]
    }

    private fun segmentIndexAt(part: ReferencePreparedPathPart, sourceDistance: Double): Int {
        val canonical = sourceDistance.coerceIn(0.0, part.fullLength)
        var lower = 0
        var upper = part.segments.lastIndex
        while (lower < upper) {
            val middle = lower + (upper - lower) / 2
            if (canonical <= part.segments[middle].sourceEndDistance + epsilon) {
                upper = middle
            } else {
                lower = middle + 1
            }
        }
        return lower
    }

    private fun tangentAt(part: ReferencePreparedPathPart, sourceDistance: Double): Double {
        val segment = segmentAt(part, sourceDistance)
        return atan2(segment.dy, segment.dx)
    }

    private fun bendCentiDegrees(path: List<ReferencePathLabelPoint>): Int {
        val angles = path.zipWithNext().mapNotNull { (start, end) ->
            val dx = end.x - start.x
            val dy = end.y - start.y
            if (hypot(dx, dy) <= epsilon) null else atan2(dy, dx)
        }
        if (angles.size <= 1) return 0
        val unwrapped = ArrayList<Double>(angles.size)
        unwrapped += angles.first()
        angles.drop(1).forEach { raw ->
            var angle = raw
            val prior = unwrapped.last()
            while (angle - prior > Math.PI) angle -= Math.PI * 2.0
            while (angle - prior < -Math.PI) angle += Math.PI * 2.0
            unwrapped += angle
        }
        val degrees = Math.toDegrees(unwrapped.maxOrNull()!! - unwrapped.minOrNull()!!)
        return ceil(degrees * 100.0 - angleRoundingEpsilon).toInt().coerceAtLeast(0)
    }

    private fun keepUpright(path: List<ReferencePathLabelPoint>): List<ReferencePathLabelPoint> {
        val centerIndex = (path.size - 1) / 2
        val tangent = atan2(
            path[centerIndex + 1].y - path[centerIndex].y,
            path[centerIndex + 1].x - path[centerIndex].x,
        )
        return if (cos(tangent) < 0.0) path.asReversed() else path
    }

    private fun uprightRadians(radians: Double): Double = if (cos(radians) < 0.0) {
        normalizeRadians(radians + Math.PI)
    } else {
        normalizeRadians(radians)
    }

    private fun uprightDegrees(radians: Double): Double = Math.toDegrees(uprightRadians(radians))

    private fun normalizeRadians(radians: Double): Double {
        var result = radians
        while (result <= -Math.PI) result += 2.0 * Math.PI
        while (result > Math.PI) result -= 2.0 * Math.PI
        return result
    }

    private fun minimumClearance(
        path: List<ReferencePathLabelPoint>,
        viewport: ReferenceScreenRect,
        avoidRects: List<ReferenceScreenRect>,
        radius: Double,
    ): Double? {
        var minimum = Double.POSITIVE_INFINITY
        for (point in path) {
            val edge = minOf(
                point.x - viewport.left,
                viewport.right - point.x,
                point.y - viewport.top,
                viewport.bottom - point.y,
            ) - radius
            if (edge < -epsilon) return null
            minimum = min(minimum, max(0.0, edge))
        }
        for (rect in avoidRects) {
            var distance = Double.POSITIVE_INFINITY
            for (segmentIndex in 0 until path.lastIndex) {
                distance = min(
                    distance,
                    segmentRectDistance(path[segmentIndex], path[segmentIndex + 1], rect),
                )
            }
            if (!distance.isFinite()) continue
            val clearance = distance - radius
            if (clearance <= epsilon) return null
            minimum = min(minimum, clearance)
        }
        return if (minimum.isFinite()) minimum else null
    }

    private fun minimumClearanceForSegment(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        viewport: ReferenceScreenRect,
        avoidRects: List<ReferenceScreenRect>,
        radius: Double,
    ): Double? {
        var minimum = Double.POSITIVE_INFINITY
        val startEdge = minOf(
            startX - viewport.left,
            viewport.right - startX,
            startY - viewport.top,
            viewport.bottom - startY,
        ) - radius
        if (startEdge < -epsilon) return null
        minimum = min(minimum, max(0.0, startEdge))
        val endEdge = minOf(
            endX - viewport.left,
            viewport.right - endX,
            endY - viewport.top,
            viewport.bottom - endY,
        ) - radius
        if (endEdge < -epsilon) return null
        minimum = min(minimum, max(0.0, endEdge))
        for (rect in avoidRects) {
            val distance = segmentRectDistance(startX, startY, endX, endY, rect)
            val clearance = distance - radius
            if (clearance <= epsilon) return null
            minimum = min(minimum, clearance)
        }
        return if (minimum.isFinite()) minimum else null
    }

    private fun segmentRectDistance(
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
        rect: ReferenceScreenRect,
    ): Double = segmentRectDistance(start.x, start.y, end.x, end.y, rect)

    private fun segmentRectDistance(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        rect: ReferenceScreenRect,
    ): Double {
        if (clipToRect(startX, startY, endX, endY, rect) != null) return 0.0
        return minOf(
            pointRectDistance(startX, startY, rect),
            pointRectDistance(endX, endY, rect),
            pointSegmentDistance(rect.left, rect.top, startX, startY, endX, endY),
            pointSegmentDistance(rect.right, rect.top, startX, startY, endX, endY),
            pointSegmentDistance(rect.right, rect.bottom, startX, startY, endX, endY),
            pointSegmentDistance(rect.left, rect.bottom, startX, startY, endX, endY),
        )
    }

    private fun pointRectDistance(
        point: ReferencePathLabelPoint,
        rect: ReferenceScreenRect,
    ): Double = pointRectDistance(point.x, point.y, rect)

    private fun pointRectDistance(
        pointX: Double,
        pointY: Double,
        rect: ReferenceScreenRect,
    ): Double {
        val dx = max(max(rect.left - pointX, 0.0), pointX - rect.right)
        val dy = max(max(rect.top - pointY, 0.0), pointY - rect.bottom)
        return hypot(dx, dy)
    }

    private fun pointSegmentDistance(
        point: ReferencePathLabelPoint,
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
    ): Double = pointSegmentDistance(point.x, point.y, start, end)

    private fun pointSegmentDistance(
        pointX: Double,
        pointY: Double,
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
    ): Double = pointSegmentDistance(pointX, pointY, start.x, start.y, end.x, end.y)

    private fun pointSegmentDistance(
        pointX: Double,
        pointY: Double,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
    ): Double {
        val dx = endX - startX
        val dy = endY - startY
        val denominator = dx * dx + dy * dy
        if (denominator <= epsilon) return hypot(pointX - startX, pointY - startY)
        val fraction = (((pointX - startX) * dx + (pointY - startY) * dy) / denominator)
            .coerceIn(0.0, 1.0)
        return hypot(pointX - (startX + dx * fraction), pointY - (startY + dy * fraction))
    }

    private fun placement(
        request: ReferencePathLabelRequest,
        mode: ReferencePathLabelPlacementMode,
        path: List<ReferencePathLabelPoint>,
        anchor: ReferencePathLabelPoint,
        sourcePosition: ReferencePathSourcePosition,
        tangentDegrees: Double,
        bendCenti: Int,
        clearance: Double,
        centerDistance: Double,
        normalOffset: Double = 0.0,
    ): ReferencePathLabelPlacement {
        val viewportCenterX = (request.viewport.left + request.viewport.right) / 2.0
        val viewportCenterY = (request.viewport.top + request.viewport.bottom) / 2.0
        val phase = (request.candidateId % request.repeatSpacingPx.toULong()).toDouble()
        val repeatOrdinal = ((centerDistance - phase) / request.repeatSpacingPx.toDouble()).roundToLong()
        return ReferencePathLabelPlacement(
            mode = mode,
            candidateId = request.candidateId,
            presentationPath = Collections.unmodifiableList(path.toList()),
            anchor = anchor,
            sourcePosition = sourcePosition,
            tangentDegrees = tangentDegrees,
            bendCentiDegrees = bendCenti,
            minimumClearanceQ8Px = toQ8(clearance),
            centerDistanceQ8Px = toQ8(hypot(anchor.x - viewportCenterX, anchor.y - viewportCenterY)),
            repeatOrdinal = repeatOrdinal,
            normalOffsetPx = normalOffset,
        )
    }

    private fun rank(placements: List<ReferencePathLabelPlacement>): List<ReferencePathLabelPlacement> =
        placements.sortedWith(
            compareBy<ReferencePathLabelPlacement> { abs(it.normalOffsetPx) }
                .thenByDescending { it.minimumClearanceQ8Px }
                .thenBy { it.bendCentiDegrees }
                .thenBy { it.centerDistanceQ8Px }
                .thenBy { it.sourcePosition.partIndex }
                .thenBy { it.sourcePosition.segmentIndex }
                .thenBy { it.sourcePosition.segmentFraction }
                .thenBy { it.repeatOrdinal }
                .thenBy { it.candidateId },
        )

    private fun toQ8(value: Double): Long {
        require(value.isFinite() && value >= 0.0) { "rank measurement must be finite and nonnegative" }
        require(value <= Long.MAX_VALUE.toDouble() / q8Scale) { "rank measurement exceeds signed Q8" }
        return floor(value * q8Scale + 0.5).toLong()
    }

    private fun toSignedQ8(value: Double): Long {
        require(value.isFinite()) { "rank measurement must be finite" }
        require(abs(value) <= Long.MAX_VALUE.toDouble() / q8Scale) {
            "rank measurement exceeds signed Q8"
        }
        return if (value >= 0.0) {
            floor(value * q8Scale + 0.5).toLong()
        } else {
            -floor(-value * q8Scale + 0.5).toLong()
        }
    }
}
