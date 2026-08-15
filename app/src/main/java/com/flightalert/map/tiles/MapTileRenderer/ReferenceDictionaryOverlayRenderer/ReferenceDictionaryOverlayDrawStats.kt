@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

internal data class ReferenceDictionaryOverlayDrawStats(
    val available: Boolean,
    val ready: Boolean,
    val visible_tiles: Int,
    val loaded_tiles: Int,
    val requested_tiles: Int,
    val boundaries_drawn: Int,
    val labels_drawn: Int,
    val retained_frame_drawn: Boolean,
    val fading: Boolean
)