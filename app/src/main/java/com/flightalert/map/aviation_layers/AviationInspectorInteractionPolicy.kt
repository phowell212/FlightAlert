@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


internal object AviationInspectorInteractionPolicy {
    fun action_for(
        inside_panel: Boolean,
        inside_close_button: Boolean
    ): AviationInspectorTapAction = if (inside_close_button || !inside_panel) {
        AviationInspectorTapAction.CLOSE
    } else {
        AviationInspectorTapAction.CONSUME
    }
}