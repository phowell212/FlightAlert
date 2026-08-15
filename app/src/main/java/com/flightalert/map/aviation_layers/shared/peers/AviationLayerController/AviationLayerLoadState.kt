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
)

package com.flightalert.map.aviation_layers

internal sealed interface AviationLayerLoadState {
    object OFF : AviationLayerLoadState
    object WAITING : AviationLayerLoadState
    object LOADING : AviationLayerLoadState
    data class SUMMARY(
        val loaded: Int,
        val partial: Int,
        val unavailable: Int,
        val retained: Int,
        val waiting: Boolean,
        val airport_labels_deferred: Boolean,
        val other_layers_enabled: Boolean,
    ) : AviationLayerLoadState
}