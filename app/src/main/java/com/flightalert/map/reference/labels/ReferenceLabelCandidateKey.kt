@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


internal data class ReferenceLabelCandidateKey(
    val candidate_id: ULong,
    val rendered_world_copy: Long,
)

