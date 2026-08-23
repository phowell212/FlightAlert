@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

import com.flightalert.map.Viewport
import com.flightalert.map.reference.dictionary.RetainedReferenceOptions
import com.flightalert.map.reference.labels.ReferenceScreenRect


internal enum class RetainedFrameRole { FALLBACK, TARGET }

internal data class RetainedLabelTileKey(
    val z: Int,
    val x: Int,
    val y: Int,
    val drawX: Int,
    val coreVisible: Boolean,
    val identity: Int,
)

internal data class RetainedStaticLabelPlanKey(
    val role: RetainedFrameRole,
    val viewport: Viewport,
    val frameViewport: Viewport,
    val tileZoom: Int,
    val tiles: List<RetainedLabelTileKey>,
    val options: RetainedReferenceOptions,
    val packageGeneration: Long,
)

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
