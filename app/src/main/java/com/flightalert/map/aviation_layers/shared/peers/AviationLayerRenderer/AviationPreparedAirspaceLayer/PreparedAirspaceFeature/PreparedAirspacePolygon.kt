@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "UseWithIndex",
)

package com.flightalert.map.aviation_layers

import android.graphics.Path


internal data class PreparedAirspacePolygon(
    val point_count: Int,
    val path: Path,
    val min_x: Float,
    val max_x: Float,
    val min_y: Float,
    val max_y: Float
)