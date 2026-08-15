@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import com.flightalert.map.SourcedMapTextPresentationPlan
import com.flightalert.map.reference.labels.ReferenceLabelCollisionShape
import com.flightalert.map.reference.labels.ReferenceLabelLayoutCandidate
import com.flightalert.map.reference.labels.ReferenceLabelOccurrenceId
import com.flightalert.map.reference.labels.ReferencePathLabelPoint


internal data class DictionaryLabelCandidate(
    val text: String,
    val source_kind: String,
    val presentation: SourcedMapTextPresentationPlan?,
    val style: DictionaryLabelStyle,
    override val occurrenceId: ReferenceLabelOccurrenceId,
    override val featureId: ULong,
    override val priority: Int,
    override val placementRank: Int,
    override val protectedArea: Boolean,
    override val waterLine: Boolean,
    override val anchor: ReferencePathLabelPoint,
    override val collisionShape: ReferenceLabelCollisionShape,
    val rotation: Float = 0f,
    val path_points: List<ReferencePathLabelPoint>? = null,
) : ReferenceLabelLayoutCandidate {
    val x: Float get() = anchor.x.toFloat()
    val y: Float get() = anchor.y.toFloat()
}