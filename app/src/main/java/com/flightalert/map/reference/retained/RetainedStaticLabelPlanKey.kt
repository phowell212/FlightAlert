@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

import com.flightalert.map.Viewport
import com.flightalert.map.reference.dictionary.RetainedReferenceOptions


internal data class RetainedStaticLabelPlanKey(
    val role: RetainedFrameRole,
    val viewport: Viewport,
    val frameViewport: Viewport,
    val tileZoom: Int,
    val tiles: List<RetainedLabelTileKey>,
    val options: RetainedReferenceOptions,
    val packageGeneration: Long,
)