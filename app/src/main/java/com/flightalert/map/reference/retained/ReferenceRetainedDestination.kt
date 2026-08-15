@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

internal class ReferenceRetainedDestination {
    var left = 0.0
        private set
    var top = 0.0
        private set
    var right = 0.0
        private set
    var bottom = 0.0
        private set

    fun set(left: Double, top: Double, right: Double, bottom: Double) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}