@file:Suppress(
    "PropertyName",
)

package com.flightalert.map.reference.labels

import com.flightalert.map.SourcedMapTextPresentationPlan
import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate
import com.flightalert.map.reference.dictionary.DictionaryLabelRecord
import com.flightalert.map.reference.dictionary.DictionaryLabelRecordRef
import com.flightalert.map.reference.dictionary.DictionaryLabelStyle

enum class PlacementSourceKind(val stable_id: Int) {
    NONE(0), DIRECT_SOURCE_POINT(1), SOURCE_OWNED_AREA_LABEL_POINT(2),
    DIRECT_SOURCE_PATH(3), EXACT_PARENT_PATH(4)
}

enum class ProminenceEvidenceKind(val stable_id: Int) {
    PROVIDER_RANK(1), CAPITAL_LEVEL(2), POPULATION(3), COMPLETE_AREA_M2(4),
    COMPLETE_RELATION_LENGTH_M(5), TYPED_SUBTYPE_DEFAULT(6)
}

internal data class PreparedLineLabelAttempt(
    val style: DictionaryLabelStyle,
    val presentation: SourcedMapTextPresentationPlan?,
    val collisionRadius: Float,
    val request: ReferencePathLabelRequest,
)

internal data class PreparedLineLabel(
    val record: DictionaryLabelRecord,
    val viewportZoom: Double,
    val renderedWorldCopy: Long,
    val preparedPath: ReferencePreparedPathGeometry,
    val candidateId: ULong,
    val isWater: Boolean,
    val attempts: List<PreparedLineLabelAttempt>,
    val tangentFallback: PreparedLineLabelAttempt?,
)

internal data class PreparedLabelRecord(
    val recordRef: DictionaryLabelRecordRef,
    val renderedWorldCopy: Long,
    val candidateKey: ReferenceLabelCandidateKey?,
    val line: PreparedLineLabel?,
    val point: DictionaryLabelCandidate?,
)

internal data class PreparedLabelPlan(
    val records: List<PreparedLabelRecord>,
    val excludedRect: ReferenceScreenRect?,
)
