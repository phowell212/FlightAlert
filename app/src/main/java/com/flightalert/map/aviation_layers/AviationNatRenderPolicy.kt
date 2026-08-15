@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.util.Collections


object AviationNatRenderPolicy {
    fun select_active_tracks(
        tracks: List<AviationOceanicTrack>,
        visible_bounds: AviationGeoBounds,
        limit: Int,
        now_epoch_ms: Long
    ): List<AviationOceanicTrack> {
        require(limit >= 0) { "NAT render limit cannot be negative" }
        val selected = tracks.asSequence()
            .filter { track ->
                val start_epoch_ms = track.start_epoch_ms
                val end_epoch_ms = track.end_epoch_ms
                start_epoch_ms != null &&
                    end_epoch_ms != null &&
                    start_epoch_ms < end_epoch_ms &&
                    now_epoch_ms >= start_epoch_ms &&
                    now_epoch_ms < end_epoch_ms
            }
            .filter { it.drawable_segments.isNotEmpty() }
            .filter { it.bounds?.intersects(visible_bounds) == true }
            .take(limit)
            .toList()
        return Collections.unmodifiableList(selected)
    }

}