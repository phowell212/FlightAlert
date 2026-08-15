@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


internal sealed interface AviationSelection {
    val key: AviationSelectionKey
    val kind: AviationLayerKind

    data class SpecialUse(val feature: AviationAirspaceFeature) : AviationSelection {
        override val kind: AviationLayerKind = AviationLayerKind.RESTRICTED_AIRSPACES
        override val key: AviationSelectionKey =
            AviationSelectionKey.ArcGis(kind, feature.object_id)
    }

    data class AtcBoundary(val feature: AviationAirspaceFeature) : AviationSelection {
        override val kind: AviationLayerKind = AviationLayerKind.ATC_BOUNDARIES
        override val key: AviationSelectionKey =
            AviationSelectionKey.ArcGis(kind, feature.object_id)
    }

    data class OceanicTrack(val track: AviationOceanicTrack) : AviationSelection {
        override val kind: AviationLayerKind = AviationLayerKind.OCEANIC_TRACKS
        override val key: AviationSelectionKey = track.selection_key()
    }

    data class Airport(val airport: AviationAirportFeature) : AviationSelection {
        override val kind: AviationLayerKind = AviationLayerKind.AIRPORTS
        override val key: AviationSelectionKey =
            AviationSelectionKey.ArcGis(kind, airport.object_id)
    }
}