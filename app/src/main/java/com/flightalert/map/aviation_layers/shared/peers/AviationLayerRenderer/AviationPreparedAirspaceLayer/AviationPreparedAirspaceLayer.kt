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

internal data class AviationPreparedAirspaceLayer(
    val source: List<AviationAirspaceFeature>,
    val features: List<PreparedAirspaceFeature>
)