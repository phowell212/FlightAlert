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


internal data class AviationAirspacePreparation(
    val restricted: AviationPreparedAirspaceLayer? = null,
    val atc: AviationPreparedAirspaceLayer? = null
)