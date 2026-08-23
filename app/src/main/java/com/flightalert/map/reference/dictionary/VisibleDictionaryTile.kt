@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class VisibleDictionaryTile(
    val z: Int,
    val x: Int,
    val draw_x: Int,
    val y: Int,
    val core_visible: Boolean,
    val request_priority: Int,
    val outline_visibility_centizoom: Int? = null,
) {
    fun cache_key(): String {
        val visibility = outline_visibility_centizoom ?: return "$z/$x/$y"
        return "$z/$x/$y@outline-$visibility"
    }

    fun low_zoom_profile(): Boolean =
        outline_visibility_centizoom == LOW_ZOOM_OUTLINE_PROFILE
}
