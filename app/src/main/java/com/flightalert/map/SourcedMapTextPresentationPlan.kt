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

internal data class SourcedMapTextPresentationPlan(
    val primaryLines: List<SourcedMapTextLinePlan>,
    val english: SourcedMapTextLinePlan?,
    val collisionHeightEm: Float,
    val collisionCenterOffset: Float,
)
