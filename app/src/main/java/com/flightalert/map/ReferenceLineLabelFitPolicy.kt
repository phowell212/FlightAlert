package com.flightalert.map

import kotlin.math.max
import kotlin.math.min

internal object ReferenceLineLabelFitPolicy {
    private val waterScaleMilli = intArrayOf(1_000, 900, 800)

    fun attemptCount(isWater: Boolean): Int = if (isWater) waterScaleMilli.size else 1

    fun acceptAttempt(isWater: Boolean, hasCurvedPlacement: Boolean): Boolean =
        !isWater || hasCurvedPlacement

    fun textSizePx(
        baseTextSizePx: Float,
        minimumTextSizePx: Float,
        isWater: Boolean,
        attemptIndex: Int,
    ): Float {
        require(baseTextSizePx.isFinite() && baseTextSizePx > 0f)
        require(minimumTextSizePx.isFinite() && minimumTextSizePx > 0f)
        require(attemptIndex in 0 until attemptCount(isWater))
        if (!isWater) return baseTextSizePx
        val readableFloor = min(baseTextSizePx, minimumTextSizePx)
        return max(
            readableFloor,
            baseTextSizePx * waterScaleMilli[attemptIndex] / 1_000f,
        )
    }
}
