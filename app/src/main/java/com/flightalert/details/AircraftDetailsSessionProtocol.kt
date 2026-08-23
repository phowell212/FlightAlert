@file:Suppress(
    "ClassName",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details


enum class AircraftDetailsSection {
    MAIN,
    USAGE,
    ENVIRONMENTAL_IMPACT,
}


sealed interface AircraftDetailsOverlay {
    object NONE : AircraftDetailsOverlay
    object PHOTO_GALLERY : AircraftDetailsOverlay
    data class PHOTO_EVIDENCE(val return_to_gallery: Boolean) : AircraftDetailsOverlay
}


data class AircraftDetailsPage(
    val is_open: Boolean,
    val section: AircraftDetailsSection = AircraftDetailsSection.MAIN,
    val overlay: AircraftDetailsOverlay = AircraftDetailsOverlay.NONE,
) {
    companion object {
        val CLOSED = AircraftDetailsPage(is_open = false)
        val MAIN = AircraftDetailsPage(is_open = true)
    }
}


enum class AircraftDetailsEffect {
    RESET_SCROLL,
    RESET_SCROLL_OFFSET,
    REQUEST_REDRAW,
    REQUEST_ANIMATION_FRAME,
}
