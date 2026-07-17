package com.flightalert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceLineLabelFitPolicyTest {
    @Test
    fun waterLabelsTryThreeBoundedPhoneSizesWithoutFallingBelowTheReadableFloor() {
        assertEquals(3, ReferenceLineLabelFitPolicy.attemptCount(isWater = true))
        assertEquals(
            10.75f,
            ReferenceLineLabelFitPolicy.textSizePx(
                baseTextSizePx = 10.75f,
                minimumTextSizePx = 8.25f,
                isWater = true,
                attemptIndex = 0,
            ),
            0.001f,
        )
        assertEquals(
            9.675f,
            ReferenceLineLabelFitPolicy.textSizePx(
                baseTextSizePx = 10.75f,
                minimumTextSizePx = 8.25f,
                isWater = true,
                attemptIndex = 1,
            ),
            0.001f,
        )
        assertEquals(
            8.6f,
            ReferenceLineLabelFitPolicy.textSizePx(
                baseTextSizePx = 10.75f,
                minimumTextSizePx = 8.25f,
                isWater = true,
                attemptIndex = 2,
            ),
            0.001f,
        )
        assertEquals(
            8.25f,
            ReferenceLineLabelFitPolicy.textSizePx(
                baseTextSizePx = 9.25f,
                minimumTextSizePx = 8.25f,
                isWater = true,
                attemptIndex = 2,
            ),
            0.001f,
        )
    }

    @Test
    fun nonWaterLineLabelsKeepTheirExistingSizeAndSingleAttempt() {
        assertEquals(1, ReferenceLineLabelFitPolicy.attemptCount(isWater = false))
        assertEquals(
            11.0f,
            ReferenceLineLabelFitPolicy.textSizePx(
                baseTextSizePx = 11.0f,
                minimumTextSizePx = 8.25f,
                isWater = false,
                attemptIndex = 0,
            ),
            0.0f,
        )
    }

    @Test
    fun waterFitKeepsSearchingWhenLargeTextOnlyHasATangentButSmallerTextCanCurve() {
        val parts = listOf(
            listOf(
                ReferencePathLabelPoint(20.0, 170.0),
                ReferencePathLabelPoint(80.0, 155.0),
                ReferencePathLabelPoint(140.0, 145.0),
                ReferencePathLabelPoint(200.0, 140.0),
                ReferencePathLabelPoint(260.0, 145.0),
                ReferencePathLabelPoint(320.0, 155.0),
                ReferencePathLabelPoint(380.0, 170.0),
            ),
        )
        fun placements(
            width: Double,
            allowTangentFallback: Boolean = true,
        ) = ReferencePathLabelPlanner.plan(
            ReferencePathLabelRequest(
                parts = parts,
                viewport = ReferenceScreenRect(0.0, 0.0, 400.0, 300.0),
                shapedAdvancePx = width,
                endClearancePx = 10.0,
                edgeClearancePx = 5.0,
                maxBendDegrees = 12.0,
                candidateId = 1uL,
                repeatSpacingPx = 100,
                prominenceTier = ProminenceTier.REGIONAL_MAJOR,
                maximumTangentSourceDistancePx = 20.0,
                allowTangentFallback = allowTangentFallback,
            ),
        )

        val large = placements(220.0)
        val largeCurvedOnly = placements(220.0, allowTangentFallback = false)
        val small = placements(80.0)

        assertEquals(ReferencePathLabelPlacementMode.TANGENT_WIDE, large.first().mode)
        assertTrue(largeCurvedOnly.isEmpty())
        assertEquals(ReferencePathLabelPlacementMode.CURVED, small.first().mode)
        assertFalse(
            ReferenceLineLabelFitPolicy.acceptAttempt(
                isWater = true,
                hasCurvedPlacement = false,
            ),
        )
        assertTrue(
            ReferenceLineLabelFitPolicy.acceptAttempt(
                isWater = true,
                hasCurvedPlacement = true,
            ),
        )
        assertTrue(
            ReferenceLineLabelFitPolicy.acceptAttempt(
                isWater = false,
                hasCurvedPlacement = false,
            ),
        )
    }
}
