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
            val boundaries = (
                fastSpans.map { value -> floor(value * q8Scale + 0.5).toLong() } +
                    listOf(lowerQ8, upperQ8)
                ).distinct().sorted()
            boundaries.zipWithNext().forEach { (lower, upper) ->
                if (upper - lower > 1L) queue += CurvedHalfSpanInterval(lower, upper)
            }
            return queue
        }
    }

    fun plan(request: ReferencePathLabelRequest): List<ReferencePathLabelPlacement> {
        validate(request)
        val viewport = request.viewport
        val parts = request.parts.mapIndexedNotNull(::partGeometry)
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
        request.parts.flatten().forEach { point ->
            require(point.x.isFinite() && point.y.isFinite()) { "path-label coordinates must be finite" }
        }
    }

    private data class SourceSegment(
        val sourceIndex: Int,
        val start: ReferencePathLabelPoint,
        val end: ReferencePathLabelPoint,
        val sourceStartDistance: Double,
        val length: Double,
    ) {
        val sourceEndDistance: Double get() = sourceStartDistance + length
        val dx: Double get() = end.x - start.x
        val dy: Double get() = end.y - start.y
    }

    private class SupportIntervalWorkspace(initialIntervalCapacity: Int) {
        private var values = DoubleArray(max(1, initialIntervalCapacity) * 2)
        val bandScratch = DoubleArray(4)
        var count: Int = 0
            private set

        fun clear() {
            count = 0
        }

        fun add(start: Double, end: Double) {
            val required = (count + 1) * 2
            if (required > values.size) {
                values = values.copyOf(max(required, values.size * 2))
            }
            values[count * 2] = start
            values[count * 2 + 1] = end
            count += 1
        }

        fun start(index: Int): Double = values[index * 2]
        fun end(index: Int): Double = values[index * 2 + 1]

        fun sort() {
            if (count > 1) quickSort(0, count - 1)
        }

        private fun quickSort(left: Int, right: Int) {
            var lower = left
            var upper = right
            val pivotIndex = (left + right) ushr 1
            val pivotStart = start(pivotIndex)
            val pivotEnd = end(pivotIndex)
            while (lower <= upper) {
                while (compareToPivot(lower, pivotStart, pivotEnd) < 0) lower += 1
                while (compareToPivot(upper, pivotStart, pivotEnd) > 0) upper -= 1
                if (lower <= upper) {
                    swap(lower, upper)
                    lower += 1
                    upper -= 1
                }
            }
            if (left < upper) quickSort(left, upper)
            if (lower < right) quickSort(lower, right)
        }

        private fun compareToPivot(index: Int, pivotStart: Double, pivotEnd: Double): Int {
            val currentStart = start(index)
            if (currentStart < pivotStart) return -1
            if (currentStart > pivotStart) return 1
            val currentEnd = end(index)
            return when {
                currentEnd > pivotEnd -> -1
                currentEnd < pivotEnd -> 1
                else -> 0
            }
        }

        private fun swap(first: Int, second: Int) {
            if (first == second) return
            val firstOffset = first * 2
            val secondOffset = second * 2
            val start = values[firstOffset]
            val end = values[firstOffset + 1]
            values[firstOffset] = values[secondOffset]
            values[firstOffset + 1] = values[secondOffset + 1]
            values[secondOffset] = start
            values[secondOffset + 1] = end
        }
    }

    private data class PartGeometry(
        val partIndex: Int,
        val segments: List<SourceSegment>,
        val totalLength: Double,
    )

    private data class VisibleSpan(
        val part: PartGeometry,
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

    private fun partGeometry(index: Int, sourcePoints: List<ReferencePathLabelPoint>): PartGeometry? {
        if (sourcePoints.size < 2) return null
        val segments = ArrayList<SourceSegment>(sourcePoints.size - 1)
        var cumulative = 0.0
        sourcePoints.zipWithNext().forEachIndexed { sourceIndex, (start, end) ->
            val length = hypot(end.x - start.x, end.y - start.y)
            require(length.isFinite()) { "path-label segment length must be finite" }
            if (length > epsilon) {
                segments += SourceSegment(sourceIndex, start, end, cumulative, length)
                cumulative += length
                require(cumulative.isFinite()) { "path-label part length must be finite" }
            }
        }
        return if (segments.isEmpty()) null else PartGeometry(index, segments, cumulative)
    }

    private fun visibleSpans(part: PartGeometry, viewport: ReferenceScreenRect): List<VisibleSpan> {
        val result = mutableListOf<VisibleSpan>()
        var currentStart: Double? = null
        var currentEnd = 0.0
        part.segments.forEach { segment ->
            val clipped = clipToRect(segment.start, segment.end, viewport)
            if (clipped == null || clipped.endFraction - clipped.startFraction <= epsilon) {
                currentStart?.let { result += VisibleSpan(part, it, currentEnd) }
                currentStart = null
                return@forEach
            }
            val start = segment.sourceStartDistance + clipped.startFraction * segment.length
            val end = segment.sourceStartDistance + clipped.endFraction * segment.length
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
    ): ClipInterval? {
        val dx = end.x - start.x
        val dy = end.y - start.y
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
            clip(-dx, start.x - rect.left) &&
            clip(dx, rect.right - start.x) &&
            clip(-dy, start.y - rect.top) &&
            clip(dy, rect.bottom - start.y)
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
        if (polylineLength(simplifiedPath) + epsilon < request.shapedAdvancePx) {
            return null
        }
        val presentationPath = centeredSubpath(
            simplifiedPath,
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
            if (
                presentationSourceDistance > epsilon &&
                !pathHasSourceSupport(
                    presentationPath = offsetPath,
                    sourcePath = sourcePath,
                    maximumDistancePx = presentationSourceDistance,
                    workspace = supportWorkspace,
                )
            ) {
                continue
            }
            val clearance = minimumClearance(
                offsetPath,
                request.viewport,
                request.staticAvoidRects,
                request.edgeClearancePx,
            ) ?: continue
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
        val sourcePath = extractPath(
            span.part,
            span.sourceStartDistance,
            span.sourceEndDistance,
        )
        if (sourcePath.size < 2) return emptyList()
        val supportWorkspace = SupportIntervalWorkspace(64)
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
                val path = listOf(
                    ReferencePathLabelPoint(anchor.x - dx + offsetX, anchor.y - dy + offsetY),
                    ReferencePathLabelPoint(anchor.x + dx + offsetX, anchor.y + dy + offsetY),
                )
                if (
                    !segmentHasSourceSupport(
                        queryStart = path.first(),
                        queryEnd = path.last(),
                        sourcePath = sourcePath,
                        maximumDistancePx = request.maximumTangentSourceDistancePx,
                        workspace = supportWorkspace,
                    )
                ) {
                    continue
                }
                val clearance = minimumClearance(
                    path,
                    request.viewport,
                    request.staticAvoidRects,
                    request.edgeClearancePx,
                ) ?: continue
                placements += placement(
                    request = request,
                    mode = ReferencePathLabelPlacementMode.TANGENT_WIDE,
                    path = path,
                    anchor = anchor,
                    sourcePosition = sourcePosition,
                    tangentDegrees = Math.toDegrees(tangent),
                    bendCenti = localBendCenti(span.part, centerDistance),
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
        sourcePath: List<ReferencePathLabelPoint>,
        maximumDistancePx: Double,
        workspace: SupportIntervalWorkspace,
    ): Boolean {
        for (index in 0 until presentationPath.lastIndex) {
            if (
                !segmentHasSourceSupport(
                    queryStart = presentationPath[index],
                    queryEnd = presentationPath[index + 1],
                    sourcePath = sourcePath,
                    maximumDistancePx = maximumDistancePx,
                    workspace = workspace,
                )
            ) return false
        }
        return true
    }

    private fun segmentHasSourceSupport(
        queryStart: ReferencePathLabelPoint,
        queryEnd: ReferencePathLabelPoint,
        sourcePath: List<ReferencePathLabelPoint>,
        maximumDistancePx: Double,
        workspace: SupportIntervalWorkspace,
    ): Boolean {
        workspace.clear()
        for (index in 0 until sourcePath.lastIndex) {
            addCapsuleSupportIntervals(
                queryStart = queryStart,
                queryEnd = queryEnd,
                sourceStart = sourcePath[index],
                sourceEnd = sourcePath[index + 1],
                radius = maximumDistancePx,
                workspace = workspace,
            )
        }
        if (workspace.count == 0) return false
        workspace.sort()
        var coveredEnd = 0.0
        for (index in 0 until workspace.count) {
            if (workspace.start(index) > coveredEnd + epsilon) return false
            coveredEnd = max(coveredEnd, workspace.end(index))
            if (coveredEnd >= 1.0 - epsilon) return true
        }
        return false
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
        targetLength: Double,
    ): List<ReferencePathLabelPoint>? {
        val geometry = partGeometry(0, path) ?: return null
        if (geometry.totalLength + epsilon < targetLength) return null
        val startDistance = (geometry.totalLength - targetLength) / 2.0
        return extractPath(
            geometry,
            startDistance,
            startDistance + targetLength,
        )
    }

    private fun addCapsuleSupportIntervals(
        queryStart: ReferencePathLabelPoint,
        queryEnd: ReferencePathLabelPoint,
        sourceStart: ReferencePathLabelPoint,
        sourceEnd: ReferencePathLabelPoint,
        radius: Double,
        workspace: SupportIntervalWorkspace,
    ) {
        addCircleSupportInterval(queryStart, queryEnd, sourceStart, radius, workspace)
        addCircleSupportInterval(queryStart, queryEnd, sourceEnd, radius, workspace)

        val sourceDx = sourceEnd.x - sourceStart.x
        val sourceDy = sourceEnd.y - sourceStart.y
        val sourceLengthSquared = sourceDx * sourceDx + sourceDy * sourceDy
        if (sourceLengthSquared <= epsilon) return
        val queryDx = queryEnd.x - queryStart.x
        val queryDy = queryEnd.y - queryStart.y
        val relativeX = queryStart.x - sourceStart.x
        val relativeY = queryStart.y - sourceStart.y
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
        queryStart: ReferencePathLabelPoint,
        queryEnd: ReferencePathLabelPoint,
        center: ReferencePathLabelPoint,
        radius: Double,
        workspace: SupportIntervalWorkspace,
    ) {
        val dx = queryEnd.x - queryStart.x
        val dy = queryEnd.y - queryStart.y
        val relativeX = queryStart.x - center.x
        val relativeY = queryStart.y - center.y
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

    private fun projectedSourceDistance(segment: SourceSegment, point: ReferencePathLabelPoint): Double {
        val denominator = segment.length * segment.length
        val fraction = (
            ((point.x - segment.start.x) * segment.dx + (point.y - segment.start.y) * segment.dy) /
                denominator
            ).coerceIn(0.0, 1.0)
        return segment.sourceStartDistance + fraction * segment.length
    }

    private fun extractPath(
        part: PartGeometry,
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

    private fun pointAt(part: PartGeometry, sourceDistance: Double): ReferencePathLabelPoint {
        val segment = segmentAt(part, sourceDistance)
        val fraction = ((sourceDistance - segment.sourceStartDistance) / segment.length).coerceIn(0.0, 1.0)
        return ReferencePathLabelPoint(
            segment.start.x + segment.dx * fraction,
            segment.start.y + segment.dy * fraction,
        )
    }

    private fun sourcePositionAt(part: PartGeometry, sourceDistance: Double): ReferencePathSourcePosition {
        val segment = segmentAt(part, sourceDistance)
        val fraction = ((sourceDistance - segment.sourceStartDistance) / segment.length).coerceIn(0.0, 1.0)
        return ReferencePathSourcePosition(part.partIndex, segment.sourceIndex, fraction)
    }

    private fun segmentAt(part: PartGeometry, sourceDistance: Double): SourceSegment {
        return part.segments[segmentIndexAt(part, sourceDistance)]
    }

    private fun segmentIndexAt(part: PartGeometry, sourceDistance: Double): Int {
        val canonical = sourceDistance.coerceIn(0.0, part.totalLength)
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

    private fun tangentAt(part: PartGeometry, sourceDistance: Double): Double {
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

    private fun localBendCenti(part: PartGeometry, sourceDistance: Double): Int {
        val segment = segmentAt(part, sourceDistance)
        if (abs(sourceDistance - segment.sourceEndDistance) > epsilon) return 0
        val next = part.segments.firstOrNull { it.sourceIndex > segment.sourceIndex } ?: return 0
        return bendCentiDegrees(listOf(segment.start, segment.end, next.end))
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
        path.forEach { point ->
            val edge = minOf(
                point.x - viewport.left,
                viewport.right - point.x,
                point.y - viewport.top,
                viewport.bottom - point.y,
            ) - radius
            if (edge < -epsilon) return null
            minimum = min(minimum, max(0.0, edge))
        }
        avoidRects.forEach { rect ->
            val distance = path.zipWithNext().minOfOrNull { (start, end) ->
                segmentRectDistance(start, end, rect)
            } ?: return@forEach
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
    ): Double {
        if (clipToRect(start, end, rect) != null) return 0.0
        val corners = listOf(
            ReferencePathLabelPoint(rect.left, rect.top),
            ReferencePathLabelPoint(rect.right, rect.top),
            ReferencePathLabelPoint(rect.right, rect.bottom),
            ReferencePathLabelPoint(rect.left, rect.bottom),
        )
        return minOf(
            pointRectDistance(start, rect),
            pointRectDistance(end, rect),
            corners.minOf { pointSegmentDistance(it, start, end) },
        )
    }

    private fun pointRectDistance(point: ReferencePathLabelPoint, rect: ReferenceScreenRect): Double {
        val dx = max(max(rect.left - point.x, 0.0), point.x - rect.right)
        val dy = max(max(rect.top - point.y, 0.0), point.y - rect.bottom)
        return hypot(dx, dy)
    }

    private fun pointSegmentDistance(
        point: ReferencePathLabelPoint,
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
    ): Double {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val denominator = dx * dx + dy * dy
        if (denominator <= epsilon) return hypot(point.x - start.x, point.y - start.y)
        val fraction = (((point.x - start.x) * dx + (point.y - start.y) * dy) / denominator)
            .coerceIn(0.0, 1.0)
        return hypot(point.x - (start.x + dx * fraction), point.y - (start.y + dy * fraction))
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
