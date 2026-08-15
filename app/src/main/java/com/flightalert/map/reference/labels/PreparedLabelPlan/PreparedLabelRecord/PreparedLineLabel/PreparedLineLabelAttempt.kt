@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import com.flightalert.map.SourcedMapTextPresentationPlan
import com.flightalert.map.reference.dictionary.DictionaryLabelStyle


internal data class PreparedLineLabelAttempt(
    val style: DictionaryLabelStyle,
    val presentation: SourcedMapTextPresentationPlan?,
    val collisionRadius: Float,
    val request: ReferencePathLabelRequest,
)