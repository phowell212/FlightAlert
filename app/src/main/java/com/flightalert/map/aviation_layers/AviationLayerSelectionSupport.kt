@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


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

