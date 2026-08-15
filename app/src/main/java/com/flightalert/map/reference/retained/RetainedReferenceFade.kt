@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate


internal data class RetainedReferenceFade(
    val previous: RetainedReferenceFrame,
    val current: RetainedReferenceFrame,
    val labels: ReferenceRetainedLabelTransition<DictionaryLabelCandidate>,
    val start_ms: Long
)

