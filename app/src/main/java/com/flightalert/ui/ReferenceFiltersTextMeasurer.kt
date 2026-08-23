package com.flightalert.ui


fun interface ReferenceFiltersTextMeasurer {
    // Measure Android text here without boxing a multi-argument function.
    fun measure(
        text: String,
        textSizeSp: Float,
        fontScale: Float,
        fontWeight: Int,
        italic: Boolean,
    ): Float
}
