@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.ui


interface FlightMapChromeHost {
    // Keep chrome density and text work independent from FlightMapView.
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
}
