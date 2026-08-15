@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.borders

import com.flightalert.map.reference.dictionary.ReferenceDictionaryLayerGroups
import com.flightalert.map.reference.dictionary.ReferenceFilterMask


internal data class BoundaryRasterRequest(
    val key: ReferenceBoundaryRasterKey,
    val source: ReferenceBoundaryRasterSource,
    val tiles: List<BoundaryRasterSourceTile>,
    val groups: ReferenceDictionaryLayerGroups,
    val filter_mask: ReferenceFilterMask,
)