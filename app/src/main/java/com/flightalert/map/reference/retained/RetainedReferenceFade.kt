@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate


internal data class ReferenceRetainedFadeCarry<T>(
    val established: T,
    val elapsedMs: Long,
)

internal data class ReferenceRetainedLabelTransition<T>(
    val leaving: List<T>,
    val continuing_current: BooleanArray,
)

internal data class RetainedReferenceFade(
    val previous: RetainedReferenceFrame,
    val current: RetainedReferenceFrame,
    val labels: ReferenceRetainedLabelTransition<DictionaryLabelCandidate>,
    val start_ms: Long
)
