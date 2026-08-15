@file:Suppress(
    "FunctionName",
)

package com.flightalert.map.tiles

internal enum class SatelliteBaseRequestKind {
    VISIBLE_PARENT,
    VISIBLE_EXACT,
    UPPER_PREFETCH,
    BUFFER_EXACT,
    BUFFER_PARENT
}