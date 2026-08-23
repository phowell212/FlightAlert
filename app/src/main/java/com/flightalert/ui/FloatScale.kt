@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui


fun interface FloatScale {
    // Scale primitive floats without boxing Function1.
    fun scale(value: Float): Float
}
