@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


    internal fun sections(kind: AviationLayerKind): List<AviationInspectorContentSection> =
        if (kind == AviationLayerKind.RESTRICTED_AIRSPACES) {
            listOf(
                AviationInspectorContentSection.SELECTION_ROWS,
                AviationInspectorContentSection.PROVENANCE
            )
        } else {
            listOf(
                AviationInspectorContentSection.PROVENANCE,
                AviationInspectorContentSection.SELECTION_ROWS
            )
        }
