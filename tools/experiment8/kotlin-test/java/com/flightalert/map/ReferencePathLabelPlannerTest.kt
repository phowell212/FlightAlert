package com.flightalert.map

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePathLabelPlannerTest {
    private val viewport = ReferenceScreenRect(0.0, 0.0, 300.0, 300.0)

    private fun point(x: Double, y: Double) = ReferencePathLabelPoint(x, y)

    private fun request(
        parts: List<List<ReferencePathLabelPoint>>,
        tier: ProminenceTier = ProminenceTier.LOCAL,
        shapedAdvancePx: Double = 100.0,
        endClearancePx: Double = 10.0,
        edgeClearancePx: Double = 5.0,
        maxBendDegrees: Double = 30.0,
        candidateId: ULong = 37uL,
        repeatSpacingPx: Int = 100,
        staticAvoidRects: List<ReferenceScreenRect> = emptyList(),
        maximumTangentOffsetPx: Double = 0.0,
        maximumTangentSourceDistancePx: Double = 0.0,
        maximumCurvedSourceDistancePx: Double = 0.0,
        viewport: ReferenceScreenRect = this.viewport,
    ) = ReferencePathLabelRequest(
        parts = parts,
        viewport = viewport,
        shapedAdvancePx = shapedAdvancePx,
        endClearancePx = endClearancePx,
        edgeClearancePx = edgeClearancePx,
        maxBendDegrees = maxBendDegrees,
        candidateId = candidateId,
        repeatSpacingPx = repeatSpacingPx,
        prominenceTier = tier,
        staticAvoidRects = staticAvoidRects,
        maximumTangentOffsetPx = maximumTangentOffsetPx,
        maximumTangentSourceDistancePx = maximumTangentSourceDistancePx,
        maximumCurvedSourceDistancePx = maximumCurvedSourceDistancePx,
    )

    @Test
    fun curvedPlacementUsesOneCompleteVisibleSourceSpanAndWholeUncondensedAdvance() {
        val placements = ReferencePathLabelPlanner.plan(
            request(parts = listOf(listOf(point(20.0, 150.0), point(280.0, 150.0)))),
        )

        val best = placements.first()
        assertEquals(ReferencePathLabelPlacementMode.CURVED, best.mode)
        assertEquals(37uL, best.candidateId)
        assertEquals(1.0, best.textScaleX, 0.0)
        assertEquals(0, best.bendCentiDegrees)
        assertEquals(0, best.sourcePosition.partIndex)
        assertEquals(0, best.sourcePosition.segmentIndex)
        assertEquals(0.5, best.sourcePosition.segmentFraction, 1e-9)
        assertEquals(100.0, polylineLength(best.presentationPath), 1e-8)
        assertEquals(point(150.0, 150.0), best.anchor)
    }

    @Test
    fun exactRequiredSpanFitsButOneContinuousPixelLessDoesNot() {
        val exact = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(90.0, 150.0), point(210.0, 150.0))),
                shapedAdvancePx = 100.0,
                endClearancePx = 10.0,
            ),
        )
        val short = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(90.0, 150.0), point(209.999, 150.0))),
                shapedAdvancePx = 100.0,
                endClearancePx = 10.0,
            ),
        )

        assertEquals(ReferencePathLabelPlacementMode.CURVED, exact.first().mode)
        assertTrue(short.isEmpty())
    }

    @Test
    fun epsilonCloseCandidateCenterRangeDoesNotCrash() {
        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(
                        point(0.0, 150.0),
                        point(220.99999999999997, 150.0),
                    ),
                ),
                shapedAdvancePx = 201.0,
                endClearancePx = 10.0,
            ),
        )

        assertEquals(ReferencePathLabelPlacementMode.CURVED, placements.first().mode)
        assertEquals(201.0, polylineLength(placements.first().presentationPath), 1e-8)
    }

    @Test
    fun epsilonCloseClippedSourceSpanKeepsTheWholeUncondensedLabel() {
        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(0.0, 150.0), point(500.0, 150.0))),
                viewport = ReferenceScreenRect(100.2, 0.0, 401.2, 300.0),
                shapedAdvancePx = 201.0,
                endClearancePx = 0.0,
                edgeClearancePx = 0.0,
                maximumCurvedSourceDistancePx = 8.0,
            ),
        )

        assertTrue(placements.isNotEmpty())
        assertEquals(ReferencePathLabelPlacementMode.CURVED, placements.first().mode)
        assertEquals(201.0, polylineLength(placements.first().presentationPath), 1e-8)
    }

    @Test
    fun disconnectedPartsNeverCombineIntoAnArtificialCurvedRun() {
        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(point(40.0, 150.0), point(100.0, 150.0)),
                    listOf(point(110.0, 150.0), point(170.0, 150.0)),
                ),
                shapedAdvancePx = 100.0,
                endClearancePx = 10.0,
            ),
        )

        assertTrue(placements.isEmpty())
    }

    @Test
    fun bendIsRoundedUpToCentiDegreesBeforeEligibility() {
        fun bentPart(turnDegrees: Double): List<ReferencePathLabelPoint> {
            val radians = Math.toRadians(turnDegrees)
            return listOf(
                point(50.0, 150.0),
                point(150.0, 150.0),
                point(150.0 + 100.0 * kotlin.math.cos(radians), 150.0 + 100.0 * kotlin.math.sin(radians)),
            )
        }

        val exact = ReferencePathLabelPlanner.plan(
            request(parts = listOf(bentPart(30.0)), shapedAdvancePx = 120.0),
        )
        val over = ReferencePathLabelPlanner.plan(
            request(parts = listOf(bentPart(30.001)), shapedAdvancePx = 120.0),
        )

        assertTrue(exact.any { it.mode == ReferencePathLabelPlacementMode.CURVED && it.bendCentiDegrees == 3_000 })
        assertTrue(over.isEmpty())
    }

    @Test
    fun curvedPlacementSmoothsOnlyScreenScaleWigglesAndKeepsTheWholeRun() {
        val overviewPath = List(27) { index ->
            val x = 20.0 + index * 10.0
            point(
                x,
                145.0 + 0.001 * (x - 150.0) * (x - 150.0) +
                    4.0 * kotlin.math.sin(index * Math.PI / 2.0),
            )
        }
        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(overviewPath),
                shapedAdvancePx = 220.0,
                endClearancePx = 0.0,
                edgeClearancePx = 8.0,
                maxBendDegrees = 30.0,
                maximumCurvedSourceDistancePx = 8.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 300.0, 300.0),
            ).copy(allowTangentFallback = false),
        )

        val best = placements.first()
        assertEquals(ReferencePathLabelPlacementMode.CURVED, best.mode)
        assertTrue(best.presentationPath.size >= 3)
        assertTrue(best.bendCentiDegrees in 1..3_000)
        assertEquals(220.0, polylineLength(best.presentationPath), 1e-7)
        assertTrue(
            best.presentationPath.all { presentationPoint ->
                overviewPath.zipWithNext().minOf { (start, end) ->
                    pointSegmentDistance(presentationPoint, start, end)
                } <= 8.0 + 1e-8
            },
        )
    }

    @Test
    fun curvedFitUsesBoundedExtraSourceSpanWhenSmoothingShortensThePath() {
        val river = List(81) { index ->
            val x = index * 5.0
            point(
                x,
                100.0 + 0.0005 * (x - 200.0) * (x - 200.0) +
                    2.0 * kotlin.math.sin(index * Math.PI / 2.0),
            )
        }

        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(river),
                shapedAdvancePx = 210.0,
                endClearancePx = 0.0,
                edgeClearancePx = 8.0,
                maxBendDegrees = 30.0,
                maximumCurvedSourceDistancePx = 8.0,
                staticAvoidRects = listOf(
                    ReferenceScreenRect(0.0, 0.0, 55.0, 300.0),
                    ReferenceScreenRect(345.0, 0.0, 400.0, 300.0),
                ),
                viewport = ReferenceScreenRect(0.0, 0.0, 400.0, 300.0),
            ).copy(allowTangentFallback = false),
        )

        val best = placements.first()
        assertEquals(210.0, polylineLength(best.presentationPath), 1e-7)
        assertTrue(best.bendCentiDegrees <= 3_000)
    }

    @Test
    fun exactKentViewportKeepsChesterRiverClearOfTheOwnshipPanel() {
        val chesterRiverArc = listOf(
            point(709.323992369025, 715.555215182347),
            point(707.727372471747, 716.495689916362),
            point(705.536957523747, 715.451733581981),
            point(705.309755019445, 711.965023886044),
            point(703.155248513197, 710.268839672953),
            point(699.754066196759, 710.926943478494),
            point(698.632090611617, 709.894412557388),
            point(695.820459620975, 709.723684238796),
            point(691.851597484181, 708.066999311641),
            point(690.538327836507, 706.032949305533),
            point(685.846726698983, 707.941319765447),
            point(682.066873542036, 706.430880128427),
            point(673.628063285558, 714.788406730349),
            point(674.365165662989, 719.019074051697),
            point(673.392699771758, 720.222659731784),
            point(670.333626973282, 720.235064466207),
            point(668.102406977810, 718.098185740473),
            point(662.415162682097, 720.814169699858),
            point(660.179372521293, 716.011578833228),
            point(656.984500524847, 715.268600528947),
            point(655.553385900087, 716.866199747355),
            point(655.088534799356, 722.478689196121),
            point(653.723361131305, 723.929390243654),
            point(638.341816878649, 725.343856409047),
            point(632.699294914945, 727.596621469588),
            point(626.464936542797, 725.668664586883),
            point(619.309363419037, 731.039588154628),
            point(611.397427935443, 733.691589799582),
            point(605.283852503805, 728.567128718523),
            point(598.765491001276, 731.129359259053),
            point(593.775523356669, 729.615981658608),
            point(589.684898958235, 725.540047077263),
            point(586.942799768467, 724.266603155913),
            point(578.191912508965, 726.064636767499),
            point(573.007059957660, 724.401423032754),
            point(572.047978122001, 725.674866954085),
            point(572.138075666796, 730.575063494267),
            point(570.579649293953, 732.403129620773),
            point(558.247384628387, 733.781687344395),
            point(551.249808648425, 731.279195393353),
            point(546.529480757463, 737.018343709443),
            point(541.030266120487, 746.398608021085),
            point(535.156297927556, 747.162804950046),
            point(526.149807850703, 753.785953814461),
            point(514.129946628549, 762.173512931321),
            point(509.442915656338, 765.533563759915),
            point(505.666979783965, 769.865427598981),
            point(500.329353134935, 781.573538258500),
            point(498.054716568953, 784.473961032436),
            point(497.050912401266, 788.175468498234),
            point(500.032618829759, 795.817437787794),
            point(504.348160649829, 797.945176182901),
            point(511.331046812719, 798.559536977562),
            point(517.339834882472, 801.376717454665),
            point(523.723050067773, 809.558292691922),
            point(522.239704982266, 816.810818608457),
            point(515.616556117852, 822.849312752759),
            point(503.823897399981, 830.116854926772),
            point(493.454518737742, 833.218691414998),
            point(486.816353615868, 837.339348328370),
            point(485.707435645908, 839.032594578053),
            point(486.217009078685, 841.864791312616),
            point(493.739174748883, 849.956269005077),
            point(492.870190458019, 854.496728246783),
            point(490.247894887632, 856.954171425410),
            point(482.051303392897, 859.336533316719),
            point(475.173204591910, 863.502239002488),
            point(454.445546240355, 886.344578133966),
            point(449.699755999788, 889.620407344580),
            point(445.459295467053, 889.515293542307),
            point(426.548604269099, 894.759884683116),
            point(422.368208766222, 896.857917003655),
            point(400.550565986741, 918.510380950988),
            point(383.797972139201, 942.065992312180),
            point(378.553380998410, 953.214584160840),
            point(377.040003397965, 958.968422294019),
            point(377.040003397965, 963.703766442441),
            point(392.683679275544, 997.298725241944),
            point(393.447876204506, 1006.409349799923),
            point(390.840596891579, 1011.833809589953),
            point(384.367610601836, 1017.587974163521),
            point(372.769510350293, 1022.892303893799),
            point(364.153442967613, 1023.446599658585),
            point(355.507343069977, 1019.805483663052),
            point(324.309435978854, 997.658462540403),
            point(315.079007801513, 993.118003298697),
            point(309.115268504157, 984.951444318900),
            point(308.485891452036, 981.130459674128),
            point(310.328973836020, 977.354523801755),
            point(313.637447084607, 954.090750140036),
            point(316.516977674234, 946.152372985689),
            point(317.528942851418, 938.602786323589),
            point(311.458131109512, 923.893056372416),
            point(307.488942532329, 922.025164519585),
            point(302.274383906476, 923.425920189018),
            point(293.012617452678, 931.987145587914),
            point(262.658885183537, 942.805379772276),
        )
        val ownshipPanel = ReferenceScreenRect(339.125, 740.5, 564.875, 992.625)
        fun chesterRequest(parts: List<List<ReferencePathLabelPoint>>) =
            request(
                parts = parts,
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 139.25,
                endClearancePx = 10.828,
                edgeClearancePx = 18.3,
                maxBendDegrees = 30.0,
                candidateId = 16736209353228503617u,
                repeatSpacingPx = 1_000,
                maximumCurvedSourceDistancePx = 18.3,
                staticAvoidRects = listOf(
                    ReferenceScreenRect(10.5, 10.5, 893.5, 225.75),
                    ReferenceScreenRect(10.5, 1_591.5, 893.5, 2_095.5),
                    ReferenceScreenRect(10.5, 1_444.5, 273.0, 1_602.0),
                    ReferenceScreenRect(257.0, 1_444.5, 519.5, 1_602.0),
                    ownshipPanel,
                ),
                viewport = ReferenceScreenRect(0.0, 0.0, 904.0, 2_105.0),
            ).copy(allowTangentFallback = false)

        val placements = ReferencePathLabelPlanner.plan(
            chesterRequest(listOf(chesterRiverArc)),
        )

        val best = placements.first()
        assertEquals(ReferencePathLabelPlacementMode.CURVED, best.mode)
        assertEquals(139.25, polylineLength(best.presentationPath), 1e-7)
        assertTrue("bend=${best.bendCentiDegrees}", best.bendCentiDegrees <= 3_000)
        assertTrue(
            best.presentationPath.all { presentationPoint ->
                chesterRiverArc.zipWithNext().minOf { (start, end) ->
                    pointSegmentDistance(presentationPoint, start, end)
                } <= 18.3 + 1e-8
            },
        )
        assertTrue(
            best.presentationPath.none { point ->
                point.x in ownshipPanel.left..ownshipPanel.right &&
                point.y in ownshipPanel.top..ownshipPanel.bottom
            },
        )

        val followedOwnshipViewportArc = chesterRiverArc.map { source ->
            point(source.x + 0.0034, source.y + 217.86759186185373)
        }
        val followedPlacements = ReferencePathLabelPlanner.plan(
            chesterRequest(listOf(followedOwnshipViewportArc)),
        )
        assertTrue(
            "Chester River must remain placeable when the live map follows the actual Kent ownship",
            followedPlacements.isNotEmpty(),
        )

        val androidFloatArc = chesterRiverArc.map { source ->
            point(source.x.toFloat().toDouble(), source.y.toFloat().toDouble())
        }
        val androidPaintPlacements = ReferencePathLabelPlanner.plan(
            chesterRequest(listOf(androidFloatArc)).copy(
                shapedAdvancePx = 132.0,
                endClearancePx = 10.828125,
                edgeClearancePx = 18.293125,
                maximumCurvedSourceDistancePx = 18.293125,
            ),
        )
        assertTrue(
            "Chester River must remain placeable with exact phone Paint metrics and Float geometry",
            androidPaintPlacements.isNotEmpty(),
        )

        val phoneCollisionRadius = 21.65625 * 1.45 / 2.0 + 21.65625 * 0.16 + 4.0
        val phoneSizedPlacements = ReferencePathLabelPlanner.plan(
            chesterRequest(listOf(androidFloatArc)).copy(
                shapedAdvancePx = 132.0,
                endClearancePx = 10.828125,
                edgeClearancePx = phoneCollisionRadius,
                maximumCurvedSourceDistancePx = phoneCollisionRadius,
            ),
        )
        assertTrue(
            "Chester River must remain placeable with the native phone glyph collision box",
            phoneSizedPlacements.isNotEmpty(),
        )
        val phoneSizedPlacement = phoneSizedPlacements.first()
        assertEquals(ReferencePathLabelPlacementMode.CURVED, phoneSizedPlacement.mode)
        assertEquals(132.0, polylineLength(phoneSizedPlacement.presentationPath), 1e-7)
        assertTrue(phoneSizedPlacement.bendCentiDegrees <= 3_000)
        assertTrue(abs(phoneSizedPlacement.normalOffsetPx) <= phoneCollisionRadius)
        assertTrue(phoneSizedPlacement.minimumClearanceQ8Px > 0L)
        assertTrue(
            phoneSizedPlacement.presentationPath.all { presentationPoint ->
                androidFloatArc.zipWithNext().minOf { (start, end) ->
                    pointSegmentDistance(presentationPoint, start, end)
                } <= phoneCollisionRadius + 1e-8
            },
        )
    }

    @Test
    fun theSameRiverKeepsMoreShapeWhenItsBendsAreVisibleAtACloserScale() {
        val overviewPath = List(27) { index ->
            val x = 20.0 + index * 10.0
            point(
                x,
                145.0 + 0.001 * (x - 150.0) * (x - 150.0) +
                    4.0 * kotlin.math.sin(index * Math.PI / 2.0),
            )
        }
        val closePath = overviewPath.map { source -> point(source.x * 2.0, source.y * 2.0) }

        val overview = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(overviewPath),
                shapedAdvancePx = 220.0,
                endClearancePx = 0.0,
                edgeClearancePx = 8.0,
                maxBendDegrees = 30.0,
                maximumCurvedSourceDistancePx = 8.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 300.0, 300.0),
            ).copy(allowTangentFallback = false),
        )
        val close = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(closePath),
                shapedAdvancePx = 440.0,
                endClearancePx = 0.0,
                edgeClearancePx = 8.0,
                maxBendDegrees = 30.0,
                maximumCurvedSourceDistancePx = 8.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 600.0, 600.0),
            ).copy(allowTangentFallback = false),
        )

        assertTrue(overview.isNotEmpty())
        assertTrue(close.isEmpty())
    }

    @Test
    fun curvedSmoothingCannotCutAcrossADeepSourceTurn() {
        val hairpin = listOf(
            point(40.0, 80.0),
            point(220.0, 80.0),
            point(220.0, 220.0),
            point(40.0, 220.0),
        )

        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(hairpin),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 360.0,
                endClearancePx = 0.0,
                edgeClearancePx = 8.0,
                maxBendDegrees = 30.0,
                maximumCurvedSourceDistancePx = 8.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 300.0, 300.0),
            ).copy(allowTangentFallback = false),
        )

        assertTrue(placements.isEmpty())
    }

    @Test
    fun curvedPlanningBoundsExpensiveCenterCandidatesOnLongSourceGeometry() {
        val longRiver = List(1_001) { index ->
            point(index.toDouble(), 150.0)
        }

        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(longRiver),
                shapedAdvancePx = 140.0,
                endClearancePx = 0.0,
                edgeClearancePx = 8.0,
                maximumCurvedSourceDistancePx = 8.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 1_000.0, 300.0),
            ).copy(allowTangentFallback = false),
        )

        assertTrue(placements.isNotEmpty())
        assertTrue("long paths must not expand every source segment into a placement", placements.size <= 64)
        assertEquals(140.0, polylineLength(placements.first().presentationPath), 1e-7)
    }

    @Test
    fun tangentFallbackBoundsCentersAndOffsetsOnLongSourceGeometry() {
        val longRiver = List(1_001) { index ->
            point(index.toDouble(), 150.0)
        }

        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(longRiver),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 140.0,
                endClearancePx = 0.0,
                edgeClearancePx = 8.0,
                maximumTangentOffsetPx = 32.0,
                maximumTangentSourceDistancePx = 0.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 1_000.0, 300.0),
            ).copy(allowCurvedPlacement = false),
        )

        assertTrue(placements.isNotEmpty())
        assertTrue("tangent fallback must not expand every source segment", placements.size <= 16)
    }

    @Test
    fun majorFeatureDoesNotInventAFullLabelBeyondItsShortSourcePath() {
        val shortPart = listOf(point(130.0, 150.0), point(170.0, 150.0))

        val regional = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(shortPart),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 120.0,
            ),
        )
        val global = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(shortPart),
                tier = ProminenceTier.GLOBAL_MAJOR,
                shapedAdvancePx = 120.0,
            ),
        )
        val local = ReferencePathLabelPlanner.plan(
            request(parts = listOf(shortPart), tier = ProminenceTier.LOCAL, shapedAdvancePx = 120.0),
        )
        val fine = ReferencePathLabelPlanner.plan(
            request(parts = listOf(shortPart), tier = ProminenceTier.FINE, shapedAdvancePx = 120.0),
        )

        assertTrue(regional.isEmpty())
        assertTrue(global.isEmpty())
        assertTrue(local.isEmpty())
        assertTrue(fine.isEmpty())
    }

    @Test
    fun tangentFallbackNeedsContinuousSourceSupportForItsWholeBaseline() {
        val supported = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(90.0, 150.0), point(210.0, 150.0))),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 120.0,
                endClearancePx = 10.0,
                maximumTangentSourceDistancePx = 0.0,
            ),
        )
        val unsupportedSCurve = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(
                        point(90.0, 110.0),
                        point(130.0, 110.0),
                        point(130.0, 150.0),
                        point(170.0, 150.0),
                        point(170.0, 190.0),
                        point(210.0, 190.0),
                    ),
                ),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 120.0,
                maxBendDegrees = 20.0,
                maximumTangentSourceDistancePx = 5.0,
            ),
        )

        assertEquals(ReferencePathLabelPlacementMode.TANGENT_WIDE, supported.first().mode)
        assertTrue(unsupportedSCurve.isEmpty())
    }

    @Test
    fun tangentFallbackRejectsUnsupportedGapsBetweenQuarterSamples() {
        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(
                        point(0.0, 0.0),
                        point(12.5, 20.0),
                        point(25.0, 0.0),
                        point(37.5, 20.0),
                        point(45.0, 0.0),
                        point(55.0, 0.0),
                        point(62.5, 20.0),
                        point(75.0, 0.0),
                        point(87.5, 20.0),
                        point(100.0, 0.0),
                    ),
                ),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 100.0,
                endClearancePx = 0.0,
                edgeClearancePx = 0.0,
                maxBendDegrees = 5.0,
                maximumTangentSourceDistancePx = 5.0,
                viewport = ReferenceScreenRect(-20.0, -20.0, 120.0, 60.0),
            ),
        )

        assertTrue(placements.isEmpty())
    }

    @Test
    fun tangentFallbackReprojectsItsVisibleSourceAnchorInsteadOfKeepingAStaticSpot() {
        val before = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(100.0, 150.0), point(200.0, 150.0))),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 100.0,
            ),
        ).first()
        val after = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(130.0, 150.0), point(230.0, 150.0))),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 100.0,
            ),
        ).first()

        assertEquals(150.0, before.anchor.x, 1e-9)
        assertEquals(180.0, after.anchor.x, 1e-9)
        assertEquals(30.0, after.anchor.x - before.anchor.x, 1e-9)
        assertEquals(0.5, before.sourcePosition.segmentFraction, 1e-9)
        assertEquals(0.5, after.sourcePosition.segmentFraction, 1e-9)
    }

    @Test
    fun wholeFallbackMustClearViewportEdgesAndStaticAvoidRectangles() {
        val nearEdge = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(15.0, 100.0), point(35.0, 100.0))),
                tier = ProminenceTier.GLOBAL_MAJOR,
                shapedAdvancePx = 100.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 200.0, 200.0),
            ),
        )
        val obstructed = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(90.0, 100.0), point(110.0, 100.0))),
                tier = ProminenceTier.GLOBAL_MAJOR,
                shapedAdvancePx = 100.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 200.0, 200.0),
                staticAvoidRects = listOf(ReferenceScreenRect(40.0, 80.0, 160.0, 120.0)),
            ),
        )

        assertTrue(nearEdge.isEmpty())
        assertTrue(obstructed.isEmpty())
    }

    @Test
    fun majorFallbackDoesNotFloatOffTheRiverWhenOwnshipCoversTheOnlySourceSupport() {
        val obstacle = ReferenceScreenRect(40.0, 80.0, 160.0, 120.0)

        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(80.0, 100.0), point(120.0, 100.0))),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 100.0,
                edgeClearancePx = 5.0,
                staticAvoidRects = listOf(obstacle),
                maximumTangentOffsetPx = 40.0,
                maximumTangentSourceDistancePx = 40.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 200.0, 200.0),
            ),
        )

        assertTrue(placements.isEmpty())
    }

    @Test
    fun rankingIsClearanceThenBendThenCenterThenCanonicalSourcePosition() {
        val clearanceRanked = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(point(20.0, 40.0), point(280.0, 40.0)),
                    listOf(point(20.0, 150.0), point(280.0, 150.0)),
                ),
            ),
        )
        assertEquals(1, clearanceRanked.first().sourcePosition.partIndex)

        val straightBeforeBent = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(point(100.0, 150.0), point(300.0, 150.0)),
                    listOf(point(100.0, 150.0), point(200.0, 145.0), point(300.0, 150.0)),
                ),
                shapedAdvancePx = 120.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 1_000.0, 1_000.0),
            ),
        )
        assertEquals(0, straightBeforeBent.first().sourcePosition.partIndex)
        assertEquals(0, straightBeforeBent.first().bendCentiDegrees)

        val centerRanked = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(point(250.0, 500.0), point(450.0, 500.0)),
                    listOf(point(450.0, 500.0), point(650.0, 500.0)),
                ),
                shapedAdvancePx = 80.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 1_000.0, 1_000.0),
            ),
        )
        assertEquals(1, centerRanked.first().sourcePosition.partIndex)

        val canonicalRanked = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(point(100.0, 150.0), point(200.0, 150.0)),
                    listOf(point(100.0, 150.0), point(200.0, 150.0)),
                ),
                shapedAdvancePx = 60.0,
            ),
        )
        assertEquals(0, canonicalRanked.first().sourcePosition.partIndex)
    }

    @Test
    fun repeatLatticeIsCandidateIdentityPhasedAndAvoidOrderCannotChangeResults() {
        val avoidA = ReferenceScreenRect(570.0, 0.0, 630.0, 90.0)
        val avoidB = ReferenceScreenRect(570.0, 110.0, 630.0, 200.0)
        val base = request(
            parts = listOf(listOf(point(0.0, 100.0), point(1_200.0, 100.0))),
            shapedAdvancePx = 80.0,
            candidateId = 50uL,
            repeatSpacingPx = 200,
            viewport = ReferenceScreenRect(0.0, 0.0, 1_200.0, 200.0),
            staticAvoidRects = listOf(avoidA, avoidB),
        )
        val forward = ReferencePathLabelPlanner.plan(base)
        val reversed = ReferencePathLabelPlanner.plan(base.copy(staticAvoidRects = listOf(avoidB, avoidA)))

        assertTrue(forward.any { abs(it.anchor.x - 250.0) < 1e-9 && it.repeatOrdinal == 1L })
        assertEquals(forward, reversed)
    }

    @Test
    fun invalidGeometryAndMeasurementsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePathLabelPlanner.plan(
                request(parts = listOf(listOf(point(Double.NaN, 0.0), point(1.0, 1.0)))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePathLabelPlanner.plan(
                request(
                    parts = listOf(listOf(point(0.0, 0.0), point(1.0, 1.0))),
                    shapedAdvancePx = 0.0,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePathLabelPlanner.plan(
                request(
                    parts = listOf(listOf(point(0.0, 0.0), point(1.0, 1.0))),
                    maxBendDegrees = 181.0,
                ),
            )
        }
    }

    private fun polylineLength(points: List<ReferencePathLabelPoint>): Double = points
        .zipWithNext()
        .sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }

    private fun pointSegmentDistance(
        point: ReferencePathLabelPoint,
        start: ReferencePathLabelPoint,
        end: ReferencePathLabelPoint,
    ): Double {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val denominator = dx * dx + dy * dy
        if (denominator == 0.0) return hypot(point.x - start.x, point.y - start.y)
        val fraction = (((point.x - start.x) * dx + (point.y - start.y) * dy) / denominator)
            .coerceIn(0.0, 1.0)
        return hypot(
            point.x - (start.x + dx * fraction),
            point.y - (start.y + dy * fraction),
        )
    }
}
