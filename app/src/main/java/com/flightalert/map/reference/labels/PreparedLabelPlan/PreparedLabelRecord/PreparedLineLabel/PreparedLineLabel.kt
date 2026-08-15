@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import com.flightalert.map.reference.dictionary.DictionaryLabelRecord


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