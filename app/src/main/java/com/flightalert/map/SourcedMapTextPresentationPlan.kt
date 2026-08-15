@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map


internal data class SourcedMapTextPresentationPlan(
    val primaryLines: List<SourcedMapTextLinePlan>,
    val english: SourcedMapTextLinePlan?,
    val collisionHeightEm: Float,
    val collisionCenterOffset: Float,
)