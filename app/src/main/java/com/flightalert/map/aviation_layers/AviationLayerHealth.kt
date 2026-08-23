@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

enum class AviationLayerAvailability { USABLE, EMPTY, UNAVAILABLE }

enum class AviationLayerCompleteness { COMPLETE, PARTIAL, UNKNOWN }

enum class AviationLayerFreshness { CURRENT, STALE }

data class AviationLayerHealth(
    val availability: AviationLayerAvailability,
    val completeness: AviationLayerCompleteness,
    val freshness: AviationLayerFreshness
) {
    init {
        require(
            availability != AviationLayerAvailability.EMPTY ||
                completeness == AviationLayerCompleteness.COMPLETE
        ) { "An empty aviation layer must be complete" }
    }

    val is_usable: Boolean
        get() = availability == AviationLayerAvailability.USABLE
    val can_claim_complete: Boolean
        get() = completeness == AviationLayerCompleteness.COMPLETE &&
            (availability == AviationLayerAvailability.USABLE ||
                availability == AviationLayerAvailability.EMPTY)
}
