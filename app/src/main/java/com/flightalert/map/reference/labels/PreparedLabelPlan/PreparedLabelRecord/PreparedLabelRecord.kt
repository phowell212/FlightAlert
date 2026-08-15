@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate
import com.flightalert.map.reference.dictionary.DictionaryLabelRecordRef


internal data class PreparedLabelRecord(
    val recordRef: DictionaryLabelRecordRef,
    val renderedWorldCopy: Long,
    val candidateKey: ReferenceLabelCandidateKey?,
    val line: PreparedLineLabel?,
    val point: DictionaryLabelCandidate?,
)