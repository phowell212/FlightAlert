@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

import com.flightalert.map.Viewport
import com.flightalert.map.reference.dictionary.RetainedReferenceOptions
import com.flightalert.map.reference.labels.ReferenceScreenRect


internal data class RetainedLabelPlanKey(
    val role: RetainedFrameRole,
    val viewport: Viewport,
    val frameViewport: Viewport,
    val tileZoom: Int,
    val tiles: List<RetainedLabelTileKey>,
    val options: RetainedReferenceOptions,
    val packageGeneration: Long,
    val labelAvoidRects: List<ReferenceScreenRect>,
)