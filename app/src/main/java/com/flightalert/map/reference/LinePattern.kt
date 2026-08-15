@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference


enum class LinePattern(val stable_id: String) {
    NONE("none"), SOLID("solid"), LONG_DASH("long_dash"), SHORT_DASH("short_dash"),
    DASH_DOT("dash_dot"), DOT("dot")
}