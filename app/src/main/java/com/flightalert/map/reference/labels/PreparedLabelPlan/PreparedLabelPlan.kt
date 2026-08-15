@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


internal data class PreparedLabelPlan(
    val records: List<PreparedLabelRecord>,
    val excludedRect: ReferenceScreenRect?,
)