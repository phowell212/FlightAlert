package com.flightalert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceLabelRuntimePresentationPolicyTest {
    @Test
    fun admissionUsesTheSameRoundedByteAlphaAsPhonePaint() {
        assertFalse(
            ReferenceLabelRuntimePresentationPolicy.hasVisiblePaintAlpha(
                fillAlpha = 235,
                haloAlpha = 235,
                visibilityAlphaMilli = 1,
            ),
        )
        assertFalse(
            ReferenceLabelRuntimePresentationPolicy.hasVisiblePaintAlpha(
                fillAlpha = 235,
                haloAlpha = 235,
                visibilityAlphaMilli = 2,
            ),
        )
        assertTrue(
            ReferenceLabelRuntimePresentationPolicy.hasVisiblePaintAlpha(
                fillAlpha = 235,
                haloAlpha = 235,
                visibilityAlphaMilli = 3,
            ),
        )
    }

    @Test
    fun globalCountryUsesRestrainedPhoneTypography() {
        val typography = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.COUNTRY_TERRITORY,
            ProminenceTier.GLOBAL_MAJOR,
        )

        assertEquals(
            10_500 to 600,
            typography.textSizeMilliSp to typography.fontWeight,
        )
    }

    @Test
    fun globalCapitalRemainsTheStrongestPhonePlaceLabel() {
        val typography = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.CAPITAL_MAJOR_CITY,
            ProminenceTier.GLOBAL_MAJOR,
        )

        assertEquals(
            12_000 to 700,
            typography.textSizeMilliSp to typography.fontWeight,
        )
    }

    @Test
    fun regionalFirstOrderRegionIsSmallerAndLighterThanAGlobalCapital() {
        val region = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.FIRST_ORDER_REGION,
            ProminenceTier.REGIONAL_MAJOR,
        )
        val capital = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.CAPITAL_MAJOR_CITY,
            ProminenceTier.GLOBAL_MAJOR,
        )

        assertEquals(9_750, region.textSizeMilliSp)
        assertTrue(region.fontWeight < capital.fontWeight)
    }

    @Test
    fun countryIsFullyVisibleAtRegionalScaleAndGoneByCityScale() {
        assertEquals(
            1_000,
            ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli(
                SemanticSubtype.COUNTRY_TERRITORY,
                ProminenceTier.GLOBAL_MAJOR,
                currentCentizoom = 680,
            ),
        )
        assertEquals(
            0,
            ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli(
                SemanticSubtype.COUNTRY_TERRITORY,
                ProminenceTier.GLOBAL_MAJOR,
                currentCentizoom = 920,
            ),
        )
    }

    @Test
    fun fineLocalPlaceWaitsUntilClosePhoneScale() {
        assertEquals(
            0,
            ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli(
                SemanticSubtype.LOCAL_PLACE,
                ProminenceTier.FINE,
                currentCentizoom = 920,
            ),
        )
        assertEquals(
            1_000,
            ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli(
                SemanticSubtype.LOCAL_PLACE,
                ProminenceTier.FINE,
                currentCentizoom = 1_100,
            ),
        )
    }

    @Test
    fun firstOrderRegionYieldsToCityLabelsAsThePhoneZoomsIn() {
        val regionalScaleAlpha =
            ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli(
                SemanticSubtype.FIRST_ORDER_REGION,
                ProminenceTier.REGIONAL_MAJOR,
                currentCentizoom = 680,
            )
        val cityScaleAlpha =
            ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli(
                SemanticSubtype.FIRST_ORDER_REGION,
                ProminenceTier.REGIONAL_MAJOR,
                currentCentizoom = 920,
            )

        assertTrue(regionalScaleAlpha > 0)
        assertTrue(
            "expected first-order region alpha below $regionalScaleAlpha at zoom 9.2, " +
                "but was $cityScaleAlpha",
            cityScaleAlpha < regionalScaleAlpha,
        )
    }

    @Test
    fun fineRiversWaitUntilACloserPhoneScaleWhileRegionalRiversRemainVisible() {
        val fineAtKent = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.RIVER,
            ProminenceTier.FINE,
            currentCentizoom = 1_032,
        )
        val fineAtFullAlpha = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.RIVER,
            ProminenceTier.FINE,
            currentCentizoom = 1_110,
        )
        val regionalAtKent = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.RIVER,
            ProminenceTier.REGIONAL_MAJOR,
            currentCentizoom = 1_032,
        )
        val fineMidFade = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.RIVER,
            ProminenceTier.FINE,
            currentCentizoom = 1_092,
        )

        assertEquals(0, fineAtKent.alphaMilli)
        assertEquals(1_000, fineAtFullAlpha.alphaMilli)
        assertEquals(1_000, regionalAtKent.alphaMilli)
        assertEquals(486, fineMidFade.alphaMilli)
        assertEquals(8_750, fineAtKent.textSizeMilliSp)
        assertEquals(10_000, regionalAtKent.textSizeMilliSp)
    }

    @Test
    fun streamsAndCreeksUseLaterProminenceBandsThanRivers() {
        val regionalAtKent = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.STREAM_CREEK,
            ProminenceTier.REGIONAL_MAJOR,
            currentCentizoom = 1_032,
        )
        val regionalAtFullAlpha = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.STREAM_CREEK,
            ProminenceTier.REGIONAL_MAJOR,
            currentCentizoom = 1_085,
        )
        val fineAtEleven = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.STREAM_CREEK,
            ProminenceTier.FINE,
            currentCentizoom = 1_100,
        )

        assertEquals(0, regionalAtKent.alphaMilli)
        assertEquals(1_000, regionalAtFullAlpha.alphaMilli)
        assertEquals(0, fineAtEleven.alphaMilli)
        assertEquals(9_000, regionalAtKent.textSizeMilliSp)
        assertEquals(8_250, fineAtEleven.textSizeMilliSp)
    }

    @Test
    fun phoneTypographyIsFeatureSpecificAndUsesTheSharedThinHalo() {
        val river = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.RIVER,
            ProminenceTier.REGIONAL_MAJOR,
            currentCentizoom = 1_100,
        )
        val creek = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.STREAM_CREEK,
            ProminenceTier.REGIONAL_MAJOR,
            currentCentizoom = 1_100,
        )
        val city = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.CITY_TOWN,
            ProminenceTier.LOCAL,
            currentCentizoom = 1_100,
        )
        val island = ReferenceLabelRuntimePresentationPolicy.resolve(
            SemanticSubtype.ISLAND_ISLET,
            ProminenceTier.LOCAL,
            currentCentizoom = 1_100,
        )

        assertEquals(160, river.haloWidthMilliEm)
        assertEquals(40, river.letterSpacingMilliEm)
        assertEquals(25, creek.letterSpacingMilliEm)
        assertEquals(450, river.fontWeight)
        assertEquals(400, creek.fontWeight)
        assertEquals(600, city.fontWeight)
        assertEquals(500, island.fontWeight)
        assertTrue(river.italic)
        assertTrue(creek.italic)
        assertFalse(city.italic)
        assertFalse(island.italic)
        assertEquals(9_500, city.textSizeMilliSp)
        assertEquals(9_000, island.textSizeMilliSp)
    }

    @Test
    fun everyLabelSubtypeHasACompleteRuntimePresentation() {
        SemanticSubtype.entries.filter { it.is_label }.forEach { subtype ->
            ProminenceTier.entries.forEach { tier ->
                val presentation = ReferenceLabelRuntimePresentationPolicy.resolve(
                    subtype,
                    tier,
                    currentCentizoom = 1_100,
                )
                assertTrue(presentation.textSizeMilliSp > 0)
                assertTrue(presentation.alphaMilli in 0..1_000)
                assertEquals(160, presentation.haloWidthMilliEm)
                assertTrue(presentation.fontWeight in 1..1_000)
                assertTrue(presentation.letterSpacingMilliEm >= 0)
            }
        }
    }

    @Test
    fun rendererTypographyIsCachedForAllocationFreeFrameReads() {
        val first = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.RIVER,
            ProminenceTier.REGIONAL_MAJOR,
        )
        val second = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.RIVER,
            ProminenceTier.REGIONAL_MAJOR,
        )

        assertTrue(first === second)
        assertEquals(10_000, first.textSizeMilliSp)
        assertEquals(
            0,
            ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli(
                SemanticSubtype.RIVER,
                ProminenceTier.FINE,
                currentCentizoom = 1_032,
            ),
        )
    }

    @Test
    fun verifiedRiverMeasureUsesContinuousSourceProminenceAtPhoneScale() {
        val fiveKilometres = measure_bucket(5_000)
        val twentyFiveKilometres = measure_bucket(25_000)
        val fiveHundredKilometres = measure_bucket(500_000)

        assertEquals(
            9_250,
            ReferenceLabelRuntimePresentationPolicy.typography(
                SemanticSubtype.RIVER,
                ProminenceTier.LOCAL,
                fiveKilometres,
            ).textSizeMilliSp,
        )
        assertEquals(
            10_000,
            ReferenceLabelRuntimePresentationPolicy.typography(
                SemanticSubtype.RIVER,
                ProminenceTier.REGIONAL_MAJOR,
                twentyFiveKilometres,
            ).textSizeMilliSp,
        )
        assertEquals(
            10_750,
            ReferenceLabelRuntimePresentationPolicy.typography(
                SemanticSubtype.RIVER,
                ProminenceTier.GLOBAL_MAJOR,
                fiveHundredKilometres,
            ).textSizeMilliSp,
        )

        val orderedRiverSizes = listOf(
            38_000L,  // Little Choptank
            70_000L,  // Chester
            115_000L, // Choptank
            484_000L, // Delaware
            715_000L, // Susquehanna
        ).map { lengthMetres ->
            ReferenceLabelRuntimePresentationPolicy.typography(
                SemanticSubtype.RIVER,
                ProminenceTier.REGIONAL_MAJOR,
                measure_bucket(lengthMetres),
            ).textSizeMilliSp
        }

        assertEquals(orderedRiverSizes.sorted(), orderedRiverSizes)
        orderedRiverSizes.zipWithNext().forEach { (smaller, larger) ->
            assertTrue("river size step must remain visible", larger - smaller >= 75)
            assertTrue("river size step must remain restrained", larger - smaller <= 400)
        }
    }

    @Test
    fun absentMeasureAndNonRiverLabelsKeepCachedTierTypographyExactly() {
        val cachedRiver = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.RIVER,
            ProminenceTier.REGIONAL_MAJOR,
        )
        val absentMeasureRiver = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.RIVER,
            ProminenceTier.REGIONAL_MAJOR,
            completeGeometryMeasureBucket = 0,
        )
        val cachedLake = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.LAKE_RESERVOIR,
            ProminenceTier.LOCAL,
        )
        val measuredLake = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.LAKE_RESERVOIR,
            ProminenceTier.LOCAL,
            completeGeometryMeasureBucket = measure_bucket(500_000),
        )

        assertTrue(cachedRiver === absentMeasureRiver)
        assertTrue(cachedLake === measuredLake)
    }

    @Test
    fun sourceNamedCreeksAndFallsUseCreekPresentationWithoutChangingTheirSourceText() {
        assertEquals(
            SemanticSubtype.STREAM_CREEK,
            ReferenceLabelRuntimePresentationPolicy.presentationSubtype(
                SemanticSubtype.RIVER,
                "Big Elk Creek",
            ),
        )
        assertEquals(
            SemanticSubtype.STREAM_CREEK,
            ReferenceLabelRuntimePresentationPolicy.presentationSubtype(
                SemanticSubtype.RIVER,
                "Little Gunpowder Falls",
            ),
        )
        assertEquals(
            SemanticSubtype.RIVER,
            ReferenceLabelRuntimePresentationPolicy.presentationSubtype(
                SemanticSubtype.RIVER,
                "Chester River",
            ),
        )
        assertEquals(
            SemanticSubtype.RIVER,
            ReferenceLabelRuntimePresentationPolicy.presentationSubtype(
                SemanticSubtype.RIVER,
                "東京川",
            ),
        )
    }

    private fun measure_bucket(lengthMetres: Long): Int =
        ReferencePresentationPolicy.complete_geometry_measure_bucket(
            lengthMetres,
            verified = true,
        )
}
