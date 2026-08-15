package com.flightalert.map.reference.labels


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