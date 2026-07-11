package com.flightalert.map

enum class ProminenceEvidenceKind(val stable_id: Int) {
    PROVIDER_RANK(1), CAPITAL_LEVEL(2), POPULATION(3), COMPLETE_AREA_M2(4),
    COMPLETE_RELATION_LENGTH_M(5), TYPED_SUBTYPE_DEFAULT(6)
}

enum class PlacementSourceKind(val stable_id: Int) {
    NONE(0), DIRECT_SOURCE_POINT(1), SOURCE_OWNED_AREA_LABEL_POINT(2),
    DIRECT_SOURCE_PATH(3), EXACT_PARENT_PATH(4)
}

data class SourceEvidenceContext(
    val source_generation_sha256: String,
    val classifier_sha256: String,
    val source_field_id: ULong,
) {
    init {
        require_sha256(source_generation_sha256, "source generation SHA-256")
        require_sha256(classifier_sha256, "source classifier SHA-256")
        policy_require(source_field_id != 0uL) { "source field ID must be a nonzero u64" }
    }
}

data class ProviderProminenceEvidence(
    val context: SourceEvidenceContext,
    val tier: ProminenceTier,
    val raw_provider_rank: Int,
)

data class WaterwayFacts(
    val subtype: SemanticSubtype,
    val complete_named_relation: Boolean = false,
    val complete_relation_length_m: Long? = null,
    val evidence_context: SourceEvidenceContext? = null,
    val provider_evidence: ProviderProminenceEvidence? = null,
) {
    init {
        policy_require(subtype in ReferenceProminencePolicy.watercourse_subtypes) {
            "waterway facts require a watercourse subtype"
        }
        policy_require(complete_relation_length_m == null || complete_relation_length_m >= 0) {
            "complete relation length must be nonnegative signed 64-bit metres"
        }
        policy_require(provider_evidence == null || evidence_context == null) {
            "waterway provider evidence already owns the source context"
        }
    }
}

data class LabelFacts(
    val subtype: SemanticSubtype,
    val evidence_context: SourceEvidenceContext? = null,
    val provider_evidence: ProviderProminenceEvidence? = null,
    val population: Long? = null,
    val population_verified: Boolean = false,
    val capital_level: CapitalLevel = CapitalLevel.NONE,
    val capital_level_verified: Boolean = false,
    val complete_area_m2: Long? = null,
    val complete_area_verified: Boolean = false,
    val complete_named_relation: Boolean = false,
    val complete_relation_length_m: Long? = null,
) {
    init {
        policy_require(subtype.is_label) { "label facts require a label semantic subtype" }
        policy_require(provider_evidence == null || evidence_context == null) {
            "label provider evidence already owns the source context"
        }
        listOf(population, complete_area_m2, complete_relation_length_m).forEach {
            policy_require(it == null || it >= 0) {
                "numeric evidence must be a nonnegative signed 64-bit integer"
            }
        }
        policy_require(!population_verified || population != null) {
            "verified population requires an exact value"
        }
        policy_require(!complete_area_verified || complete_area_m2 != null) {
            "verified complete area requires an exact value"
        }
        val places = setOf(
            SemanticSubtype.CAPITAL_MAJOR_CITY,
            SemanticSubtype.CITY_TOWN,
            SemanticSubtype.LOCAL_PLACE,
        )
        policy_require(
            subtype in places || (
                population == null && !population_verified && capital_level == CapitalLevel.NONE &&
                    !capital_level_verified
                ),
        ) { "population evidence and capital evidence apply only to place labels" }
        val areas = setOf(
            SemanticSubtype.ISLAND_ISLET,
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR,
            SemanticSubtype.PROTECTED_LAND,
        )
        policy_require(subtype in areas || (complete_area_m2 == null && !complete_area_verified)) {
            "complete area evidence does not apply to this label subtype"
        }
        policy_require(
            subtype in ReferenceProminencePolicy.watercourse_subtypes ||
                (!complete_named_relation && complete_relation_length_m == null),
        ) { "relation-length evidence applies only to watercourse labels" }
    }
}

data class ProminenceDecision(
    val subtype: SemanticSubtype,
    val semantic_priority: Int,
    val tier: ProminenceTier,
    val provider_rank: Int?,
    val complete_geometry_measure_bucket: Int,
    val prominence_rule_id: ULong,
    val evidence_kind: ProminenceEvidenceKind,
    val evidence_value: Long,
    val source_generation_sha256: String,
    val classifier_sha256: String,
    val source_field_id: ULong,
    val policy_sha256: String,
) {
    init {
        policy_require(subtype.is_label) { "prominence decision requires a label subtype" }
        policy_require(semantic_priority == ReferenceProminencePolicy.semantic_priority(subtype, tier)) {
            "prominence decision semantic priority drifted"
        }
        policy_require(complete_geometry_measure_bucket in 0..0xffff) {
            "complete geometry measure bucket must be u16"
        }
        require_sha256(source_generation_sha256, "source generation SHA-256")
        require_sha256(classifier_sha256, "source classifier SHA-256")
        policy_require(source_field_id != 0uL) { "prominence source field ID must be nonzero u64" }
        policy_require(policy_sha256 == ReferencePresentationPolicy.canonical_policy_sha256) {
            "prominence decision policy SHA-256 drifted"
        }
        policy_require(prominence_rule_id == ReferenceProminencePolicy.rule_id(subtype, tier, evidence_kind)) {
            "prominence decision rule ID drifted"
        }
        policy_require((evidence_kind == ProminenceEvidenceKind.PROVIDER_RANK) == (provider_rank != null)) {
            "provider-rank presence contradicts evidence kind"
        }
        ReferenceProminencePolicy.validate_decision(this)
    }
}

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

    val watercourse_subtypes = setOf(
        SemanticSubtype.RIVER,
        SemanticSubtype.STREAM_CREEK,
        SemanticSubtype.CANAL_CHANNEL,
        SemanticSubtype.UNSPECIFIED_WATERCOURSE,
    )

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

    fun for_label(facts: LabelFacts): ProminenceTier {
        facts.provider_evidence?.let { return it.tier }
        return when (facts.subtype) {
            SemanticSubtype.COUNTRY_TERRITORY -> ProminenceTier.GLOBAL_MAJOR
            SemanticSubtype.FIRST_ORDER_REGION -> ProminenceTier.REGIONAL_MAJOR
            SemanticSubtype.SECOND_LOCAL_REGION -> ProminenceTier.LOCAL
            SemanticSubtype.CAPITAL_MAJOR_CITY,
            SemanticSubtype.CITY_TOWN,
            SemanticSubtype.LOCAL_PLACE -> place_tier(facts)
            SemanticSubtype.ISLAND_ISLET -> if (facts.complete_area_verified) {
                area_tier(facts.subtype, facts.complete_area_m2!!)
            } else ProminenceTier.FINE
            SemanticSubtype.OCEAN_SEA -> ProminenceTier.GLOBAL_MAJOR
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR -> if (facts.complete_area_verified) {
                area_tier(facts.subtype, facts.complete_area_m2!!)
            } else ProminenceTier.LOCAL
            SemanticSubtype.RIVER,
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.CANAL_CHANNEL,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE -> for_waterway(
                WaterwayFacts(
                    facts.subtype,
                    facts.complete_named_relation,
                    facts.complete_relation_length_m,
                    facts.evidence_context,
                    facts.provider_evidence,
                ),
            )
            SemanticSubtype.PROTECTED_LAND -> if (facts.complete_area_verified) {
                area_tier(facts.subtype, facts.complete_area_m2!!)
            } else ProminenceTier.FINE
            else -> throw ReferencePolicyException("label subtype has no prominence policy")
        }
    }

    fun for_waterway(facts: WaterwayFacts): ProminenceTier {
        facts.provider_evidence?.let { return it.tier }
        if (facts.complete_named_relation && facts.complete_relation_length_m != null) {
            return relation_tier(facts.subtype, facts.complete_relation_length_m)
        }
        return default_tier(facts.subtype)
    }

    private fun place_tier(facts: LabelFacts): ProminenceTier {
        val candidates = mutableListOf<ProminenceTier>()
        if (facts.capital_level_verified) {
            if (facts.capital_level == CapitalLevel.NATIONAL) candidates += ProminenceTier.GLOBAL_MAJOR
            if (facts.capital_level == CapitalLevel.REGIONAL) candidates += ProminenceTier.REGIONAL_MAJOR
        }
        if (facts.population_verified) candidates += population_tier(facts.population!!)
        return candidates.minByOrNull { it.stable_code } ?: default_tier(facts.subtype)
    }

    private fun population_tier(value: Long) = when {
        value >= globalPopulation -> ProminenceTier.GLOBAL_MAJOR
        value >= regionalPopulation -> ProminenceTier.REGIONAL_MAJOR
        value >= localPopulation -> ProminenceTier.LOCAL
        else -> ProminenceTier.FINE
    }

    private fun area_tier(subtype: SemanticSubtype, value: Long): ProminenceTier = when (subtype) {
        SemanticSubtype.ISLAND_ISLET -> tier_from_area(value, globalIslandArea, regionalIslandArea, localIslandArea)
        SemanticSubtype.BAY_SOUND,
        SemanticSubtype.LAKE_RESERVOIR -> tier_from_area(value, globalWaterArea, regionalWaterArea, localWaterArea)
        SemanticSubtype.PROTECTED_LAND -> tier_from_area(value, null, regionalProtectedArea, localProtectedArea)
        else -> throw ReferencePolicyException("complete-area evidence is invalid for this subtype")
    }

    private fun tier_from_area(value: Long, global: Long?, regional: Long, local: Long) = when {
        global != null && value >= global -> ProminenceTier.GLOBAL_MAJOR
        value >= regional -> ProminenceTier.REGIONAL_MAJOR
        value >= local -> ProminenceTier.LOCAL
        else -> ProminenceTier.FINE
    }

    private fun relation_tier(subtype: SemanticSubtype, value: Long): ProminenceTier = when (subtype) {
        SemanticSubtype.RIVER -> when {
            value >= globalRiverLength -> ProminenceTier.GLOBAL_MAJOR
            value >= regionalRiverLength -> ProminenceTier.REGIONAL_MAJOR
            value >= localRiverLength -> ProminenceTier.LOCAL
            else -> ProminenceTier.FINE
        }
        SemanticSubtype.STREAM_CREEK,
        SemanticSubtype.UNSPECIFIED_WATERCOURSE -> ProminenceTier.FINE
        SemanticSubtype.CANAL_CHANNEL -> ProminenceTier.LOCAL
        else -> throw ReferencePolicyException("relation-length evidence is invalid for this subtype")
    }

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

    fun rule_id(
        subtype: SemanticSubtype,
        tier: ProminenceTier,
        evidence_kind: ProminenceEvidenceKind,
    ): ULong {
        policy_require(subtype.is_label) { "prominence rule requires a label subtype" }
        val preimage = PolicyByteWriter()
            .ascii("FAE8RULE1\u0000")
            .u32(subtype.stable_id.toUInt())
            .u8(tier.stable_code)
            .u8(evidence_kind.stable_id)
            .finish()
        return first_u64_big_endian(sha256_bytes(preimage))
    }

    fun decision_for_label(facts: LabelFacts): ProminenceDecision {
        val provider = facts.provider_evidence
        val context: SourceEvidenceContext
        val tier: ProminenceTier
        val providerRank: Int?
        val kind: ProminenceEvidenceKind
        val evidenceValue: Long
        if (provider != null) {
            policy_require(
                !facts.population_verified && !facts.capital_level_verified && !facts.complete_area_verified &&
                    !(facts.complete_named_relation && facts.complete_relation_length_m != null),
            ) { "provider prominence cannot mix unbound fallback evidence" }
            context = provider.context
            tier = provider.tier
            providerRank = provider.raw_provider_rank
            kind = ProminenceEvidenceKind.PROVIDER_RANK
            evidenceValue = provider.raw_provider_rank.toLong()
        } else {
            context = facts.evidence_context
                ?: throw ReferencePolicyException("authoritative prominence decision requires source evidence context")
            tier = for_label(facts)
            providerRank = null
            val selected = evidence_for(facts)
            kind = selected.first
            evidenceValue = selected.second
        }
        val verifiedMeasure = when {
            provider != null -> null
            facts.complete_area_verified -> facts.complete_area_m2
            facts.subtype in watercourse_subtypes && facts.complete_named_relation &&
                facts.complete_relation_length_m != null -> facts.complete_relation_length_m
            else -> null
        }
        val bucket = measure_bucket(verifiedMeasure, verifiedMeasure != null)
        return ProminenceDecision(
            facts.subtype,
            semantic_priority(facts.subtype, tier),
            tier,
            providerRank,
            bucket,
            rule_id(facts.subtype, tier, kind),
            kind,
            evidenceValue,
            context.source_generation_sha256,
            context.classifier_sha256,
            context.source_field_id,
            ReferencePresentationPolicy.canonical_policy_sha256,
        )
    }

    private fun evidence_for(facts: LabelFacts): Pair<ProminenceEvidenceKind, Long> {
        if (facts.subtype in setOf(
                SemanticSubtype.CAPITAL_MAJOR_CITY,
                SemanticSubtype.CITY_TOWN,
                SemanticSubtype.LOCAL_PLACE,
            )
        ) {
            val candidates = mutableListOf<Triple<ProminenceTier, ProminenceEvidenceKind, Long>>()
            if (facts.capital_level_verified && facts.capital_level == CapitalLevel.NATIONAL) {
                candidates += Triple(ProminenceTier.GLOBAL_MAJOR, ProminenceEvidenceKind.CAPITAL_LEVEL, 2L)
            }
            if (facts.capital_level_verified && facts.capital_level == CapitalLevel.REGIONAL) {
                candidates += Triple(ProminenceTier.REGIONAL_MAJOR, ProminenceEvidenceKind.CAPITAL_LEVEL, 1L)
            }
            if (facts.population_verified) {
                candidates += Triple(population_tier(facts.population!!), ProminenceEvidenceKind.POPULATION, facts.population)
            }
            val selected = candidates.minWithOrNull(compareBy({ it.first.stable_code }, { it.second.stable_id }))
            if (selected != null) return selected.second to selected.third
        }
        if (facts.complete_area_verified) {
            return ProminenceEvidenceKind.COMPLETE_AREA_M2 to facts.complete_area_m2!!
        }
        if (facts.subtype in watercourse_subtypes && facts.complete_named_relation && facts.complete_relation_length_m != null) {
            return ProminenceEvidenceKind.COMPLETE_RELATION_LENGTH_M to facts.complete_relation_length_m
        }
        return ProminenceEvidenceKind.TYPED_SUBTYPE_DEFAULT to facts.subtype.stable_id.toLong()
    }

    fun validate_decision(decision: ProminenceDecision) {
        var expectedBucket = 0
        val expectedTier = when (decision.evidence_kind) {
            ProminenceEvidenceKind.PROVIDER_RANK -> {
                policy_require(decision.provider_rank?.toLong() == decision.evidence_value) {
                    "provider prominence evidence value must equal provider rank"
                }
                decision.tier
            }
            ProminenceEvidenceKind.CAPITAL_LEVEL -> {
                policy_require(
                    decision.subtype in setOf(
                        SemanticSubtype.CAPITAL_MAJOR_CITY,
                        SemanticSubtype.CITY_TOWN,
                        SemanticSubtype.LOCAL_PLACE,
                    ) && decision.evidence_value in 1L..2L,
                ) { "capital prominence evidence is impossible" }
                if (decision.evidence_value == 2L) ProminenceTier.GLOBAL_MAJOR else ProminenceTier.REGIONAL_MAJOR
            }
            ProminenceEvidenceKind.POPULATION -> {
                policy_require(decision.evidence_value >= 0 && decision.subtype in setOf(
                    SemanticSubtype.CAPITAL_MAJOR_CITY,
                    SemanticSubtype.CITY_TOWN,
                    SemanticSubtype.LOCAL_PLACE,
                )) { "population prominence evidence is impossible" }
                population_tier(decision.evidence_value)
            }
            ProminenceEvidenceKind.COMPLETE_AREA_M2 -> {
                policy_require(decision.evidence_value >= 0) { "complete-area prominence evidence is impossible" }
                expectedBucket = measure_bucket(decision.evidence_value, true)
                area_tier(decision.subtype, decision.evidence_value)
            }
            ProminenceEvidenceKind.COMPLETE_RELATION_LENGTH_M -> {
                policy_require(decision.evidence_value >= 0) { "relation-length prominence evidence is impossible" }
                expectedBucket = measure_bucket(decision.evidence_value, true)
                relation_tier(decision.subtype, decision.evidence_value)
            }
            ProminenceEvidenceKind.TYPED_SUBTYPE_DEFAULT -> {
                policy_require(decision.evidence_value == decision.subtype.stable_id.toLong()) {
                    "typed-default evidence value must equal the semantic subtype"
                }
                default_tier(decision.subtype)
            }
        }
        policy_require(decision.tier == expectedTier) { "prominence decision tier contradicts its evidence" }
        policy_require(decision.complete_geometry_measure_bucket == expectedBucket) {
            "prominence decision measure bucket contradicts its evidence"
        }
    }

    fun canonical_bytes(decision: ProminenceDecision): ByteArray = PolicyByteWriter()
        .ascii("FAE8PDEC1\u0000")
        .raw(hex_bytes(decision.policy_sha256))
        .u32(decision.subtype.stable_id.toUInt())
        .i32(decision.semantic_priority)
        .u8(decision.tier.stable_code)
        .boolean(decision.provider_rank != null)
        .apply { decision.provider_rank?.let { i32(it) } }
        .u16(decision.complete_geometry_measure_bucket)
        .u64(decision.prominence_rule_id)
        .u8(decision.evidence_kind.stable_id)
        .i64(decision.evidence_value)
        .raw(hex_bytes(decision.source_generation_sha256))
        .raw(hex_bytes(decision.classifier_sha256))
        .u64(decision.source_field_id)
        .finish()
}
