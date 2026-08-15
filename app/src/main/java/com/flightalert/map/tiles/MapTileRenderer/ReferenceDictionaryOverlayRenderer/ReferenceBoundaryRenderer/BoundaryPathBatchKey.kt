@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.borders

import com.flightalert.map.reference.OutlineVisibilityRule
import com.flightalert.map.reference.dictionary.DictionaryLineStyle


internal data class BoundaryPathBatchKey(
    val draw_order: Int,
    val priority: Int,
    val style: DictionaryLineStyle,
    val visibility_rule: OutlineVisibilityRule?,
)