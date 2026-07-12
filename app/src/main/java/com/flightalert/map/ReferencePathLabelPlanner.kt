package com.flightalert.map

import java.util.Collections
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

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

    fun plan(request: ReferencePathLabelRequest): List<ReferencePathLabelPlacement> {
        validate(request)
        val viewport = request.viewport
        val parts = request.parts.mapIndexedNotNull(::partGeometry)
        if (parts.isEmpty()) return emptyList()

        val curved = parts.flatMap { part ->
            visibleSpans(part, viewport).flatMap { span -> curvedPlacements(request, span) }
        }
        if (curved.isNotEmpty()) return rank(curved)
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
        return centers.mapNotNull { centerDistance ->
            val sourceStart = centerDistance - halfAdvance
            val sourceEnd = centerDistance + halfAdvance
            val sourcePath = extractPath(span.part, sourceStart, sourceEnd)
            if (sourcePath.size < 2) return@mapNotNull null
            val bendCenti = bendCentiDegrees(sourcePath)
            if (bendCenti > maximumBendCenti) return@mapNotNull null
            val anchor = pointAt(span.part, centerDistance)
            val sourcePosition = sourcePositionAt(span.part, centerDistance)
            val presentationPath = keepUpright(sourcePath)
            val clearance = minimumClearance(
                presentationPath,
                request.viewport,
                request.staticAvoidRects,
                request.edgeClearancePx,
            ) ?: return@mapNotNull null
            placement(
                request = request,
                mode = ReferencePathLabelPlacementMode.CURVED,
                path = presentationPath,
                anchor = anchor,
                sourcePosition = sourcePosition,
                tangentDegrees = uprightDegrees(tangentAt(span.part, centerDistance)),
                bendCenti = bendCenti,
                clearance = clearance,
                centerDistance = centerDistance,
            )
        }
    }

    private fun tangentPlacements(
        request: ReferencePathLabelRequest,
        span: VisibleSpan,
    ): List<ReferencePathLabelPlacement> {
        val centers = candidateDistances(
            request,
            span,
            span.sourceStartDistance,
            span.sourceEndDistance,
        )
        return centers.flatMap { centerDistance ->
            val anchor = pointAt(span.part, centerDistance)
            val sourcePosition = sourcePositionAt(span.part, centerDistance)
            val sourceTangent = tangentAt(span.part, centerDistance)
            val tangent = uprightRadians(sourceTangent)
            val dx = cos(tangent) * request.shapedAdvancePx / 2.0
            val dy = kotlin.math.sin(tangent) * request.shapedAdvancePx / 2.0
            val normalX = -kotlin.math.sin(tangent)
            val normalY = cos(tangent)
            tangentOffsets(request).mapNotNull { normalOffset ->
                val offsetX = normalX * normalOffset
                val offsetY = normalY * normalOffset
                val path = listOf(
                    ReferencePathLabelPoint(anchor.x - dx + offsetX, anchor.y - dy + offsetY),
                    ReferencePathLabelPoint(anchor.x + dx + offsetX, anchor.y + dy + offsetY),
                )
                val clearance = minimumClearance(
                    path,
                    request.viewport,
                    request.staticAvoidRects,
                    request.edgeClearancePx,
                ) ?: return@mapNotNull null
                placement(
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
            }
        }
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

    private fun candidateDistances(
        request: ReferencePathLabelRequest,
        span: VisibleSpan,
        minimumDistance: Double,
        maximumDistance: Double,
    ): List<Double> {
        if (minimumDistance > maximumDistance + epsilon) return emptyList()
        val candidates = linkedMapOf<Long, Double>()
        fun add(distance: Double) {
            if (distance < minimumDistance - epsilon || distance > maximumDistance + epsilon) return
            val canonical = distance.coerceIn(minimumDistance, maximumDistance)
            candidates.putIfAbsent(toQ8(canonical), canonical)
        }

        add((minimumDistance + maximumDistance) / 2.0)
        add(minimumDistance)
        add(maximumDistance)

        val center = ReferencePathLabelPoint(
            (request.viewport.left + request.viewport.right) / 2.0,
            (request.viewport.top + request.viewport.bottom) / 2.0,
        )
        span.part.segments.forEach { segment ->
            val overlapStart = max(minimumDistance, segment.sourceStartDistance)
            val overlapEnd = min(maximumDistance, segment.sourceEndDistance)
            if (overlapStart <= overlapEnd + epsilon) {
                val projection = projectedSourceDistance(segment, center).coerceIn(overlapStart, overlapEnd)
                add(projection)
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
        return candidates.values.toList()
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
        val result = mutableListOf(pointAt(part, sourceStartDistance))
        part.segments.forEach { segment ->
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
        val canonical = sourceDistance.coerceIn(0.0, part.totalLength)
        return part.segments.firstOrNull { canonical <= it.sourceEndDistance + epsilon }
            ?: part.segments.last()
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
