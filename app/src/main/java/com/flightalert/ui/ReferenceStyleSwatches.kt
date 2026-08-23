package com.flightalert.ui

enum class ReferenceOutlinePattern {
    SOLID,
    LONG_DASH,
    SHORT_DASH,
    DASH_DOT,
    DOT,
}

data class ReferenceLabelStyleSwatch(
    val colorArgb: Int,
    val haloArgb: Int,
    val fontWeight: Int,
    val italic: Boolean,
    val letterSpacingEm: Float,
) : ReferenceStyleSwatch {
    init {
        require(fontWeight in 1..900) { "label swatch font weight is invalid" }
        require(letterSpacingEm.isFinite() && letterSpacingEm in 0f..1f) {
            "label swatch letter spacing is invalid"
        }
    }
}

data class ReferenceOutlineStyleSwatch(
    val colorArgb: Int,
    val haloArgb: Int,
    val lineWidthDp: Float,
    val pattern: ReferenceOutlinePattern,
) : ReferenceStyleSwatch {
    init {
        require(lineWidthDp.isFinite() && lineWidthDp > 0f) {
            "outline swatch line width must be positive"
        }
    }
}

sealed interface ReferenceStyleSwatch
