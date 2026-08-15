@file:Suppress(
    "ConstPropertyName",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


enum class PlacementSourceKind(val stable_id: Int) {
    NONE(0), DIRECT_SOURCE_POINT(1), SOURCE_OWNED_AREA_LABEL_POINT(2),
    DIRECT_SOURCE_PATH(3), EXACT_PARENT_PATH(4)
}