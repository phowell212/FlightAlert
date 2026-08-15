@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference


enum class ProminenceTier(val stable_id: String, val stable_code: Int) {
    GLOBAL_MAJOR("global_major", 0), REGIONAL_MAJOR("regional_major", 1),
    LOCAL("local", 2), FINE("fine", 3)
}