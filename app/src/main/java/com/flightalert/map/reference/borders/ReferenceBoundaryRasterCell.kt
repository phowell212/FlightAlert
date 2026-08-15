@file:Suppress(
    "FunctionName",
)

package com.flightalert.map.reference.borders


internal data class ReferenceBoundaryRasterCell(
    val zoom: Int,
    val x: Int,
    val drawX: Int,
    val y: Int,
    val coreVisible: Boolean,
    val requestPriority: Int,
)