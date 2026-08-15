@file:Suppress(
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import kotlin.math.hypot
import kotlin.math.max


internal object ReferenceTangentViewportSupport {
    private const val Epsilon = 1e-9

    fun isGuaranteed(request: ReferencePathLabelRequest): Boolean {
        val baselineInset = request.edgeClearancePx - Epsilon
        val maximumDeltaX = max(
            0.0,
            request.viewport.right - request.viewport.left - baselineInset,
        )
        val maximumDeltaY = max(
            0.0,
            request.viewport.bottom - request.viewport.top - baselineInset,
        )
        return request.maximumTangentSourceDistancePx >= hypot(maximumDeltaX, maximumDeltaY)
    }
}