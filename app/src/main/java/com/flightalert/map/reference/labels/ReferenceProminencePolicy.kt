@file:Suppress(
    "ConstPropertyName",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import com.flightalert.map.ReferencePolicyException
import com.flightalert.map.policy_require
import com.flightalert.map.reference.ProminenceTier
import com.flightalert.map.reference.SemanticSubtype



internal object ReferenceProminencePolicy {
    internal const val globalRiverLength = 500_000L
    internal const val regionalRiverLength = 25_000L
    internal const val localRiverLength = 5_000L
    internal const val globalPopulation = 1_000_000L
    internal const val regionalPopulation = 100_000L
    internal const val localPopulation = 10_000L
    internal const val globalIslandArea = 10_000_000_000L
    internal const val regionalIslandArea = 500_000_000L
    internal const val localIslandArea = 25_000_000L
    internal const val globalWaterArea = 100_000_000_000L
    internal const val regionalWaterArea = 5_000_000_000L
    internal const val localWaterArea = 100_000_000L
    internal const val regionalProtectedArea = 5_000_000_000L
    internal const val localProtectedArea = 100_000_000L
    internal const val tierStride = 1_000

    private val within_tier_priority_by_subtype = mapOf(
        SemanticSubtype.COUNTRY_TERRITORY to 0,
        SemanticSubtype.OCEAN_SEA to 10,
        SemanticSubtype.CAPITAL_MAJOR_CITY to 20,
        SemanticSubtype.FIRST_ORDER_REGION to 30,
        SemanticSubtype.RIVER to 40,
        SemanticSubtype.BAY_SOUND to 50,
        SemanticSubtype.CITY_TOWN to 60,
        SemanticSubtype.ISLAND_ISLET to 70,
        SemanticSubtype.LAKE_RESERVOIR to 80,
        SemanticSubtype.SECOND_LOCAL_REGION to 90,
        SemanticSubtype.LOCAL_PLACE to 100,
        SemanticSubtype.PROTECTED_LAND to 110,
        SemanticSubtype.STREAM_CREEK to 120,
        SemanticSubtype.CANAL_CHANNEL to 130,
        SemanticSubtype.UNSPECIFIED_WATERCOURSE to 140,
    )

    private val defaults = mapOf(
        SemanticSubtype.COUNTRY_TERRITORY to ProminenceTier.GLOBAL_MAJOR,
        SemanticSubtype.FIRST_ORDER_REGION to ProminenceTier.REGIONAL_MAJOR,
        SemanticSubtype.SECOND_LOCAL_REGION to ProminenceTier.LOCAL,
        SemanticSubtype.CAPITAL_MAJOR_CITY to ProminenceTier.REGIONAL_MAJOR,
        SemanticSubtype.CITY_TOWN to ProminenceTier.LOCAL,
        SemanticSubtype.LOCAL_PLACE to ProminenceTier.FINE,
        SemanticSubtype.ISLAND_ISLET to ProminenceTier.FINE,
        SemanticSubtype.OCEAN_SEA to ProminenceTier.GLOBAL_MAJOR,
        SemanticSubtype.BAY_SOUND to ProminenceTier.LOCAL,
        SemanticSubtype.LAKE_RESERVOIR to ProminenceTier.LOCAL,
        SemanticSubtype.RIVER to ProminenceTier.FINE,
        SemanticSubtype.STREAM_CREEK to ProminenceTier.FINE,
        SemanticSubtype.CANAL_CHANNEL to ProminenceTier.LOCAL,
        SemanticSubtype.UNSPECIFIED_WATERCOURSE to ProminenceTier.FINE,
        SemanticSubtype.PROTECTED_LAND to ProminenceTier.FINE,
    )

    fun semantic_priority(subtype: SemanticSubtype, tier: ProminenceTier): Int {
        val within = within_tier_priority_by_subtype[subtype]
            ?: throw ReferencePolicyException("semantic priority requires a label subtype")
        return tier.stable_code * tierStride + within
    }

    fun within_tier_priority(subtype: SemanticSubtype): Int? =
        within_tier_priority_by_subtype[subtype]

    fun default_tier(subtype: SemanticSubtype): ProminenceTier = defaults[subtype]
        ?: throw ReferencePolicyException("default prominence requires a label subtype")

    fun measure_bucket(measure: Long?, verified: Boolean): Int {
        if (!verified) return 0
        val exact = measure ?: throw ReferencePolicyException(
            "verified complete geometry measure must be nonnegative signed 64-bit",
        )
        policy_require(exact >= 0) {
            "verified complete geometry measure must be nonnegative signed 64-bit"
        }
        if (exact == 0L) return 0
        val exponent = 63 - java.lang.Long.numberOfLeadingZeros(exact)
        val base = 1L shl exponent
        val delta = exact - base
        val fractional = if (exponent >= 10) {
            delta ushr (exponent - 10)
        } else {
            delta shl (10 - exponent)
        }
        return minOf(0xffff, 1 + exponent * 1_024 + fractional.toInt())
    }

}