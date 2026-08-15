@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map


internal data class SourcedMapTextLinePlan(
    val text: String,
    val textSize: Float,
    val baselineOffset: Float,
    val forceItalic: Boolean,
)