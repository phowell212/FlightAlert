@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

internal sealed interface AviationSelectionKey {
    val kind: AviationLayerKind

    data class ArcGis(
        override val kind: AviationLayerKind,
        val object_id: Long
    ) : AviationSelectionKey {
        init {
            require(kind != AviationLayerKind.OCEANIC_TRACKS) {
                "Oceanic tracks require a source-bound NAT key"
            }
        }
    }

    data class Nat(
        val designator: String,
        val source_notam: String?,
        val icao_id: String?,
        val part_number: Int?,
        val start_datetime: String?,
        val end_datetime: String?,
        val entry_datetime: String?,
        val raw_track_line: String
    ) : AviationSelectionKey {
        override val kind: AviationLayerKind = AviationLayerKind.OCEANIC_TRACKS
    }
}

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

internal fun AviationOceanicTrack.selection_key(): AviationSelectionKey.Nat =
    AviationSelectionKey.Nat(
        designator = designator,
        source_notam = source_notam,
        icao_id = icao_id,
        part_number = part_number,
        start_datetime = start_datetime,
        end_datetime = end_datetime,
        entry_datetime = entry_datetime,
        raw_track_line = raw_track_line
    )
