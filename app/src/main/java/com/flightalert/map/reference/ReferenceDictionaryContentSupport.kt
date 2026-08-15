@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

internal fun tile_request_priority(
    tx: Int,
    ty: Int,
    center_x: Int,
    center_y: Int,
): Int {
    val dx = tx - center_x
    val dy = ty - center_y
    return dx * dx + dy * dy
}

internal fun scaled_alpha_byte(
    base_alpha: Int,
    visibility_alpha_milli: Int,
    frame_alpha: Int = 255,
): Int {
    val visible_alpha = (
        base_alpha.coerceIn(0, 255) * visibility_alpha_milli.coerceIn(0, 1_000) + 500
        ) / 1_000
    return (visible_alpha * frame_alpha.coerceIn(0, 255) + 127) / 255
}

internal fun dictionary_tile_zoom(viewport_zoom: Double, zooms: Set<Int>): Int? =
    ReferenceDictionaryLodPolicy.select(viewport_zoom, zooms)