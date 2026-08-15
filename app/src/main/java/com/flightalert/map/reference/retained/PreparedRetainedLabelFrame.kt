@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.retained


internal data class PreparedRetainedLabelFrame(
    val request: RetainedLabelPlanRequest,
    val frame: RetainedReferenceFrame?,
)