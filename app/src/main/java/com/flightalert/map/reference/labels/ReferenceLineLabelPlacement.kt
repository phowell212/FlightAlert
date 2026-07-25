package com.flightalert.map.reference.labels

import com.flightalert.map.*
import com.flightalert.map.reference.*
import com.flightalert.map.reference.borders.*
import com.flightalert.map.reference.dictionary.*
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

internal data class ReferenceLineLabelPlacementOption(
    val placement: ReferencePathLabelPlacement,
    val occurrenceId: ReferenceLabelOccurrenceId,
    val placementRank: Int,
)

internal object ReferenceLineLabelPlacementAdapter {
    fun fromPlanner(
        placements: List<ReferencePathLabelPlacement>,
        renderedWorldCopy: Long,
    ): List<ReferenceLineLabelPlacementOption> = placements.mapIndexed { index, placement ->
        ReferenceLineLabelPlacementOption(
            placement = placement,
            occurrenceId = ReferenceLabelOccurrenceId(
                placement.candidateId,
                placement.repeatOrdinal,
                renderedWorldCopy,
            ),
            placementRank = index,
        )
    }
}
