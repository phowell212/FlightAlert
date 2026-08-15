@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.map.reference.labels

internal data class ReferenceLabelRuntimeTypography(
    val textSizeMilliSp: Int,
    val haloWidthMilliEm: Int,
    val fontWeight: Int,
    val italic: Boolean,
    val letterSpacingMilliEm: Int,
)