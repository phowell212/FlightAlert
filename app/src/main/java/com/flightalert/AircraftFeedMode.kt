@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert


enum class AircraftFeedMode(
    val uses_globe: Boolean
) {
    HYBRID(true),
    BINCRAFT(true),
    API(false)
}