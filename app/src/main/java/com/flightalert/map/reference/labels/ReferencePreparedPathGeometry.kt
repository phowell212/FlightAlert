@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


internal data class ReferencePreparedPathGeometry(
    val viewport: ReferenceScreenRect,
    val parts: List<ReferencePreparedPathPart>,
)