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

import com.flightalert.map.SourcedMapText


internal data class PreparedAirspaceFeature(
    val source: AviationAirspaceFeature,
    val map_text: SourcedMapText,
    val center: AviationLayerPoint,
    val point_count: Int,
    val min_unwrapped_lon: Double,
    val max_unwrapped_lon: Double,
    val polygons: List<PreparedAirspacePolygon>
)