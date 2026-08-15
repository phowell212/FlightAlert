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