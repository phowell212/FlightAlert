@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

internal enum class ReferenceDictionaryLayerGroup {
    PLACES,
    WATER,
    REGIONS,
    PUBLIC_LANDS,
    TRANSPORTATION,
    OTHER
}

internal data class ReferenceDictionaryLayerGroups(
    val places: Boolean,
    val water: Boolean,
    val regions: Boolean,
    val public_lands: Boolean
) {
    fun enabled(group: ReferenceDictionaryLayerGroup): Boolean {
        return when (group) {
            ReferenceDictionaryLayerGroup.PLACES -> places
            ReferenceDictionaryLayerGroup.WATER -> water
            ReferenceDictionaryLayerGroup.REGIONS -> regions
            ReferenceDictionaryLayerGroup.PUBLIC_LANDS -> public_lands
            ReferenceDictionaryLayerGroup.TRANSPORTATION,
            ReferenceDictionaryLayerGroup.OTHER -> false
        }
    }
}
