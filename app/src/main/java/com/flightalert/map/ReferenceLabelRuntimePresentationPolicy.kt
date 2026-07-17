@file:Suppress("FunctionName")

package com.flightalert.map

internal data class ReferenceLabelRuntimePresentation(
    val textSizeMilliSp: Int,
    val alphaMilli: Int,
    val haloWidthMilliEm: Int,
    val fontWeight: Int,
    val italic: Boolean,
    val letterSpacingMilliEm: Int,
)

internal data class ReferenceLabelRuntimeTypography(
    val textSizeMilliSp: Int,
    val haloWidthMilliEm: Int,
    val fontWeight: Int,
    val italic: Boolean,
    val letterSpacingMilliEm: Int,
)

/** Phone-display presentation layered over the source-bound package policy. */
internal object ReferenceLabelRuntimePresentationPolicy {
    private const val FULL_ALPHA_MILLI = 1_000
    private const val HALO_WIDTH_MILLI_EM = 160
    private val stream_name_suffixes = arrayOf("Creek", "Brook", "Run", "Falls", "Branch")

    private val typography_by_subtype_tier =
        Array(SemanticSubtype.entries.size) { subtype_index ->
            Array<ReferenceLabelRuntimeTypography?>(ProminenceTier.entries.size) { tier_index ->
                val subtype = SemanticSubtype.entries[subtype_index]
                val tier = ProminenceTier.entries[tier_index]
                if (subtype.is_label) create_typography(subtype, tier) else null
            }
        }

    private val visibility_band_by_subtype_tier =
        Array(SemanticSubtype.entries.size) { subtype_index ->
            Array<ZoomFadeBand?>(ProminenceTier.entries.size) { tier_index ->
                val subtype = SemanticSubtype.entries[subtype_index]
                val tier = ProminenceTier.entries[tier_index]
                if (subtype.is_label) create_visibility_band(subtype, tier) else null
            }
        }

    fun typography(
        subtype: SemanticSubtype,
        tier: ProminenceTier,
    ): ReferenceLabelRuntimeTypography {
        require(subtype.is_label) { "runtime label presentation requires a label subtype" }
        return checkNotNull(typography_by_subtype_tier[subtype.ordinal][tier.ordinal])
    }

    fun typography(
        subtype: SemanticSubtype,
        tier: ProminenceTier,
        completeGeometryMeasureBucket: Int,
    ): ReferenceLabelRuntimeTypography {
        val tierTypography = typography(subtype, tier)
        if (subtype != SemanticSubtype.RIVER || completeGeometryMeasureBucket == 0) {
            return tierTypography
        }
        require(completeGeometryMeasureBucket in 1..0xffff) {
            "complete geometry measure bucket must be u16"
        }
        return ReferenceLabelRuntimeTypography(
            textSizeMilliSp = measured_river_text_size_milli_sp(completeGeometryMeasureBucket),
            haloWidthMilliEm = tierTypography.haloWidthMilliEm,
            fontWeight = tierTypography.fontWeight,
            italic = tierTypography.italic,
            letterSpacingMilliEm = tierTypography.letterSpacingMilliEm,
        )
    }

    fun presentationSubtype(
        sourceSubtype: SemanticSubtype,
        primaryText: String,
    ): SemanticSubtype {
        if (sourceSubtype != SemanticSubtype.RIVER) return sourceSubtype
        return if (stream_name_suffixes.any { suffix ->
                has_terminal_word(primaryText, suffix)
            }
        ) {
            SemanticSubtype.STREAM_CREEK
        } else {
            sourceSubtype
        }
    }

    fun visibilityAlphaMilli(
        subtype: SemanticSubtype,
        tier: ProminenceTier,
        currentCentizoom: Int,
    ): Int {
        require(subtype.is_label) { "runtime label presentation requires a label subtype" }
        require(currentCentizoom >= 0) { "current centizoom must be nonnegative" }
        val band = visibility_band_by_subtype_tier[subtype.ordinal][tier.ordinal]
            ?: return FULL_ALPHA_MILLI
        var alpha = FULL_ALPHA_MILLI
        if (band.fadeInStart != null && band.fadeInEnd != null) {
            alpha = when {
                currentCentizoom <= band.fadeInStart -> 0
                currentCentizoom >= band.fadeInEnd -> FULL_ALPHA_MILLI
                else -> interpolate_alpha(
                    currentCentizoom - band.fadeInStart,
                    band.fadeInEnd - band.fadeInStart,
                )
            }
        }
        if (band.fadeOutStart != null && band.fadeOutEnd != null) {
            val fade_out_alpha = when {
                currentCentizoom <= band.fadeOutStart -> FULL_ALPHA_MILLI
                currentCentizoom >= band.fadeOutEnd -> 0
                else -> FULL_ALPHA_MILLI - interpolate_alpha(
                    currentCentizoom - band.fadeOutStart,
                    band.fadeOutEnd - band.fadeOutStart,
                )
            }
            alpha = minOf(alpha, fade_out_alpha)
        }
        return alpha
    }

    fun resolve(
        subtype: SemanticSubtype,
        tier: ProminenceTier,
        currentCentizoom: Int,
    ): ReferenceLabelRuntimePresentation {
        val typography = typography(subtype, tier)
        return ReferenceLabelRuntimePresentation(
            textSizeMilliSp = typography.textSizeMilliSp,
            alphaMilli = visibilityAlphaMilli(subtype, tier, currentCentizoom),
            haloWidthMilliEm = typography.haloWidthMilliEm,
            fontWeight = typography.fontWeight,
            italic = typography.italic,
            letterSpacingMilliEm = typography.letterSpacingMilliEm,
        )
    }

    fun hasVisiblePaintAlpha(
        fillAlpha: Int,
        haloAlpha: Int,
        visibilityAlphaMilli: Int,
    ): Boolean {
        require(fillAlpha in 0..255 && haloAlpha in 0..255)
        require(visibilityAlphaMilli in 0..FULL_ALPHA_MILLI)
        fun scaled(alpha: Int): Int =
            (alpha * visibilityAlphaMilli + FULL_ALPHA_MILLI / 2) / FULL_ALPHA_MILLI
        return scaled(fillAlpha) > 0 || scaled(haloAlpha) > 0
    }

    private fun create_typography(
        subtype: SemanticSubtype,
        tier: ProminenceTier,
    ): ReferenceLabelRuntimeTypography = ReferenceLabelRuntimeTypography(
        textSizeMilliSp = text_size_milli_sp(subtype, tier),
        haloWidthMilliEm = HALO_WIDTH_MILLI_EM,
        fontWeight = font_weight(subtype),
        italic = italic(subtype),
        letterSpacingMilliEm = letter_spacing_milli_em(subtype),
    )

    private fun has_terminal_word(text: String, word: String): Boolean {
        val start = text.length - word.length
        if (start < 0 || !text.regionMatches(start, word, 0, word.length, ignoreCase = true)) {
            return false
        }
        return start == 0 || text[start - 1].isWhitespace() || text[start - 1] == '-'
    }

    private fun text_size_milli_sp(subtype: SemanticSubtype, tier: ProminenceTier): Int =
        when (subtype) {
            SemanticSubtype.COUNTRY_TERRITORY -> tier_value(tier, 10_500, 10_000, 9_500, 9_000)
            SemanticSubtype.FIRST_ORDER_REGION -> tier_value(tier, 10_250, 9_750, 9_000, 8_500)
            SemanticSubtype.SECOND_LOCAL_REGION -> tier_value(tier, 10_500, 9_750, 9_000, 8_500)
            SemanticSubtype.CAPITAL_MAJOR_CITY -> tier_value(tier, 12_000, 11_000, 10_000, 9_250)
            SemanticSubtype.CITY_TOWN -> tier_value(tier, 11_000, 10_250, 9_500, 8_750)
            SemanticSubtype.LOCAL_PLACE -> tier_value(tier, 10_000, 9_500, 9_000, 8_500)
            SemanticSubtype.ISLAND_ISLET -> tier_value(tier, 10_500, 9_750, 9_000, 8_500)
            SemanticSubtype.OCEAN_SEA -> tier_value(tier, 12_500, 11_000, 9_750, 9_000)
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR -> tier_value(tier, 11_000, 10_250, 9_500, 8_750)
            SemanticSubtype.RIVER -> tier_value(tier, 10_750, 10_000, 9_250, 8_750)
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE -> tier_value(tier, 9_500, 9_000, 8_500, 8_250)
            SemanticSubtype.CANAL_CHANNEL -> tier_value(tier, 9_750, 9_500, 9_000, 8_500)
            SemanticSubtype.PROTECTED_LAND -> tier_value(tier, 9_750, 9_250, 8_750, 8_500)
            else -> error("outline subtype cannot be presented as a label")
        }

    private fun measured_river_text_size_milli_sp(measureBucket: Int): Int = when {
        measureBucket <= LOCAL_RIVER_MEASURE_BUCKET -> {
            val bucketDistance = LOCAL_RIVER_MEASURE_BUCKET - measureBucket
            val reduction = interpolate_delta(
                bucketDistance,
                REGIONAL_RIVER_MEASURE_BUCKET - LOCAL_RIVER_MEASURE_BUCKET,
                REGIONAL_RIVER_TEXT_SIZE_MILLI_SP - LOCAL_RIVER_TEXT_SIZE_MILLI_SP,
            )
            (LOCAL_RIVER_TEXT_SIZE_MILLI_SP - reduction).coerceAtLeast(
                FINE_RIVER_TEXT_SIZE_MILLI_SP,
            )
        }
        measureBucket <= REGIONAL_RIVER_MEASURE_BUCKET -> interpolate_size(
            measureBucket,
            LOCAL_RIVER_MEASURE_BUCKET,
            REGIONAL_RIVER_MEASURE_BUCKET,
            LOCAL_RIVER_TEXT_SIZE_MILLI_SP,
            REGIONAL_RIVER_TEXT_SIZE_MILLI_SP,
        )
        measureBucket <= GLOBAL_RIVER_MEASURE_BUCKET -> interpolate_size(
            measureBucket,
            REGIONAL_RIVER_MEASURE_BUCKET,
            GLOBAL_RIVER_MEASURE_BUCKET,
            REGIONAL_RIVER_TEXT_SIZE_MILLI_SP,
            GLOBAL_RIVER_TEXT_SIZE_MILLI_SP,
        )
        else -> {
            val bucketDistance = measureBucket - GLOBAL_RIVER_MEASURE_BUCKET
            val increase = interpolate_delta(
                bucketDistance,
                GLOBAL_RIVER_MEASURE_BUCKET - REGIONAL_RIVER_MEASURE_BUCKET,
                GLOBAL_RIVER_TEXT_SIZE_MILLI_SP - REGIONAL_RIVER_TEXT_SIZE_MILLI_SP,
            )
            (GLOBAL_RIVER_TEXT_SIZE_MILLI_SP + increase).coerceAtMost(
                MAXIMUM_RIVER_TEXT_SIZE_MILLI_SP,
            )
        }
    }

    private fun interpolate_size(
        value: Int,
        lowerValue: Int,
        upperValue: Int,
        lowerSize: Int,
        upperSize: Int,
    ): Int = lowerSize + interpolate_delta(
        value - lowerValue,
        upperValue - lowerValue,
        upperSize - lowerSize,
    )

    private fun interpolate_delta(value: Int, span: Int, sizeSpan: Int): Int =
        ((value.toLong() * sizeSpan + span / 2L) / span).toInt()

    private fun interpolate_alpha(value: Int, span: Int): Int =
        ((value.toLong() * FULL_ALPHA_MILLI + span / 2L) / span).toInt()

    private fun create_visibility_band(
        subtype: SemanticSubtype,
        tier: ProminenceTier,
    ): ZoomFadeBand? = when (subtype) {
            SemanticSubtype.COUNTRY_TERRITORY -> ZoomFadeBand(
                fadeOutStart = 820,
                fadeOutEnd = 920,
            )
            SemanticSubtype.FIRST_ORDER_REGION -> ZoomFadeBand(
                fadeOutStart = 850,
                fadeOutEnd = 975,
            )
            SemanticSubtype.LOCAL_PLACE -> when (tier) {
                ProminenceTier.GLOBAL_MAJOR -> ZoomFadeBand(fadeInStart = 750, fadeInEnd = 800)
                ProminenceTier.REGIONAL_MAJOR -> ZoomFadeBand(fadeInStart = 850, fadeInEnd = 900)
                ProminenceTier.LOCAL -> ZoomFadeBand(fadeInStart = 975, fadeInEnd = 1_025)
                ProminenceTier.FINE -> ZoomFadeBand(fadeInStart = 1_050, fadeInEnd = 1_100)
            }
            SemanticSubtype.RIVER -> when (tier) {
                ProminenceTier.GLOBAL_MAJOR -> ZoomFadeBand(fadeInStart = 550, fadeInEnd = 585)
                ProminenceTier.REGIONAL_MAJOR -> ZoomFadeBand(fadeInStart = 593, fadeInEnd = 628)
                ProminenceTier.LOCAL -> ZoomFadeBand(fadeInStart = 950, fadeInEnd = 985)
                ProminenceTier.FINE -> ZoomFadeBand(fadeInStart = 1_075, fadeInEnd = 1_110)
            }
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE -> when (tier) {
                ProminenceTier.GLOBAL_MAJOR -> if (subtype == SemanticSubtype.STREAM_CREEK) {
                    ZoomFadeBand(fadeInStart = 1_035, fadeInEnd = 1_070)
                } else {
                    ZoomFadeBand(fadeInStart = 975, fadeInEnd = 1_010)
                }
                ProminenceTier.REGIONAL_MAJOR -> ZoomFadeBand(fadeInStart = 1_050, fadeInEnd = 1_085)
                ProminenceTier.LOCAL -> ZoomFadeBand(fadeInStart = 1_125, fadeInEnd = 1_160)
                ProminenceTier.FINE -> ZoomFadeBand(fadeInStart = 1_200, fadeInEnd = 1_235)
            }
            SemanticSubtype.CANAL_CHANNEL -> when (tier) {
                ProminenceTier.GLOBAL_MAJOR -> ZoomFadeBand(fadeInStart = 900, fadeInEnd = 935)
                ProminenceTier.REGIONAL_MAJOR -> ZoomFadeBand(fadeInStart = 1_000, fadeInEnd = 1_035)
                ProminenceTier.LOCAL -> ZoomFadeBand(fadeInStart = 1_100, fadeInEnd = 1_135)
                ProminenceTier.FINE -> ZoomFadeBand(fadeInStart = 1_200, fadeInEnd = 1_235)
            }
            else -> null
        }

    private fun font_weight(subtype: SemanticSubtype): Int = when (subtype) {
        SemanticSubtype.CAPITAL_MAJOR_CITY -> 700
        SemanticSubtype.COUNTRY_TERRITORY,
        SemanticSubtype.CITY_TOWN -> 600
        SemanticSubtype.FIRST_ORDER_REGION -> 500
        SemanticSubtype.SECOND_LOCAL_REGION,
        SemanticSubtype.LOCAL_PLACE,
        SemanticSubtype.ISLAND_ISLET,
        SemanticSubtype.OCEAN_SEA,
        SemanticSubtype.CANAL_CHANNEL,
        SemanticSubtype.PROTECTED_LAND -> 500
        SemanticSubtype.RIVER -> 450
        SemanticSubtype.BAY_SOUND,
        SemanticSubtype.LAKE_RESERVOIR,
        SemanticSubtype.STREAM_CREEK,
        SemanticSubtype.UNSPECIFIED_WATERCOURSE -> 400
        else -> error("outline subtype cannot be presented as a label")
    }

    private fun italic(subtype: SemanticSubtype): Boolean = when (subtype) {
        SemanticSubtype.OCEAN_SEA,
        SemanticSubtype.BAY_SOUND,
        SemanticSubtype.LAKE_RESERVOIR,
        SemanticSubtype.RIVER,
        SemanticSubtype.STREAM_CREEK,
        SemanticSubtype.UNSPECIFIED_WATERCOURSE -> true
        else -> false
    }

    private fun letter_spacing_milli_em(subtype: SemanticSubtype): Int = when (subtype) {
        SemanticSubtype.COUNTRY_TERRITORY -> 45
        SemanticSubtype.FIRST_ORDER_REGION -> 35
        SemanticSubtype.SECOND_LOCAL_REGION -> 25
        SemanticSubtype.CAPITAL_MAJOR_CITY -> 10
        SemanticSubtype.CITY_TOWN -> 5
        SemanticSubtype.LOCAL_PLACE -> 0
        SemanticSubtype.ISLAND_ISLET -> 40
        SemanticSubtype.OCEAN_SEA -> 50
        SemanticSubtype.BAY_SOUND -> 45
        SemanticSubtype.LAKE_RESERVOIR -> 35
        SemanticSubtype.RIVER -> 40
        SemanticSubtype.STREAM_CREEK,
        SemanticSubtype.UNSPECIFIED_WATERCOURSE -> 25
        SemanticSubtype.CANAL_CHANNEL -> 35
        SemanticSubtype.PROTECTED_LAND -> 20
        else -> error("outline subtype cannot be presented as a label")
    }

    private fun tier_value(
        tier: ProminenceTier,
        globalMajor: Int,
        regionalMajor: Int,
        local: Int,
        fine: Int,
    ): Int = when (tier) {
        ProminenceTier.GLOBAL_MAJOR -> globalMajor
        ProminenceTier.REGIONAL_MAJOR -> regionalMajor
        ProminenceTier.LOCAL -> local
        ProminenceTier.FINE -> fine
    }

    private data class ZoomFadeBand(
        val fadeInStart: Int? = null,
        val fadeInEnd: Int? = null,
        val fadeOutStart: Int? = null,
        val fadeOutEnd: Int? = null,
    ) {
        init {
            require((fadeInStart == null) == (fadeInEnd == null))
            require((fadeOutStart == null) == (fadeOutEnd == null))
            if (fadeInStart != null && fadeInEnd != null) require(fadeInStart < fadeInEnd)
            if (fadeOutStart != null && fadeOutEnd != null) require(fadeOutStart < fadeOutEnd)
        }
    }

    private val LOCAL_RIVER_MEASURE_BUCKET = ReferencePresentationPolicy
        .complete_geometry_measure_bucket(ReferenceProminencePolicy.localRiverLength, verified = true)
    private val REGIONAL_RIVER_MEASURE_BUCKET = ReferencePresentationPolicy
        .complete_geometry_measure_bucket(ReferenceProminencePolicy.regionalRiverLength, verified = true)
    private val GLOBAL_RIVER_MEASURE_BUCKET = ReferencePresentationPolicy
        .complete_geometry_measure_bucket(ReferenceProminencePolicy.globalRiverLength, verified = true)
    private const val FINE_RIVER_TEXT_SIZE_MILLI_SP = 8_750
    private const val LOCAL_RIVER_TEXT_SIZE_MILLI_SP = 9_250
    private const val REGIONAL_RIVER_TEXT_SIZE_MILLI_SP = 10_000
    private const val GLOBAL_RIVER_TEXT_SIZE_MILLI_SP = 10_750
    private const val MAXIMUM_RIVER_TEXT_SIZE_MILLI_SP = 11_250
}
