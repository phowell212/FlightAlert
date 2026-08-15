package com.flightalert.map.reference.labels

import kotlin.math.max
import kotlin.math.min


internal fun referencePathCollisionShape(
    points: List<ReferencePathLabelPoint>,
    radiusPx: Double,
): ReferenceLabelCollisionShape.Path {
    var minX = points[0].x
    var minY = points[0].y
    var maxX = minX
    var maxY = minY
    var index = 1
    while (index < points.size) {
        val point = points[index]
        minX = min(minX, point.x)
        minY = min(minY, point.y)
        maxX = max(maxX, point.x)
        maxY = max(maxY, point.y)
        index++
    }
    return ReferenceLabelCollisionShape.Path(
        points = points,
        radiusPx = radiusPx,
        bounds = ReferenceScreenRect(
            left = minX - radiusPx,
            top = minY - radiusPx,
            right = maxX + radiusPx,
            bottom = maxY + radiusPx,
        ),
    )
}

internal fun translateReferencePathCollisionShape(
    shape: ReferenceLabelCollisionShape.Path,
    dx: Double,
    dy: Double,
): ReferenceLabelCollisionShape.Path {
    val translated = ArrayList<ReferencePathLabelPoint>(shape.points.size)
    for (point in shape.points) {
        translated += ReferencePathLabelPoint(point.x + dx, point.y + dy)
    }
    return referencePathCollisionShape(translated, shape.radiusPx)
}