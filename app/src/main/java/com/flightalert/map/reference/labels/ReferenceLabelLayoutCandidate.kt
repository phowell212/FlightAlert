package com.flightalert.map.reference.labels


internal interface ReferenceLabelLayoutCandidate {
    // Expose only geometry and priority to label layout.
    val occurrenceId: ReferenceLabelOccurrenceId
    val featureId: ULong
    val priority: Int
    val placementRank: Int
    val protectedArea: Boolean
    val waterLine: Boolean
    val anchor: ReferencePathLabelPoint
    val collisionShape: ReferenceLabelCollisionShape
}
