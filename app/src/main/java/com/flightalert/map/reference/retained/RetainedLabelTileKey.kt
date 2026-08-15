@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.retained


internal data class RetainedLabelTileKey(
    val z: Int,
    val x: Int,
    val y: Int,
    val drawX: Int,
    val coreVisible: Boolean,
    val identity: Int,
)