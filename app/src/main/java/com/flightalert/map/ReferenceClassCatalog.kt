package com.flightalert.map

import java.util.Collections

data class SubtypeCatalogCounts(
    val distinct_feature_count: ULong,
    val canonical_variant_count: ULong,
    val posting_count: ULong,
)

class ReferenceClassCatalog private constructor(
    val status: CatalogControlStatus,
    val reason: String,
    val renderer_semantic_stream_sha256: String?,
    val renderer_contract_sha256: String?,
    val presentation_policy_sha256: String?,
    val catalog_sha256: String?,
    subtype_counts: List<Pair<SemanticSubtype, SubtypeCatalogCounts>>,
) {
    val subtype_counts: List<Pair<SemanticSubtype, SubtypeCatalogCounts>> =
        Collections.unmodifiableList(subtype_counts.toList())

    init {
        policy_require(reason.isNotEmpty()) { "catalog status reason is required" }
        if (status == CatalogControlStatus.AVAILABLE) {
            policy_require(reason == "verified") { "available catalog must be verifier-produced" }
            require_sha256(renderer_semantic_stream_sha256, "renderer semantic stream SHA-256")
            require_sha256(renderer_contract_sha256, "renderer contract SHA-256")
            require_sha256(presentation_policy_sha256, "presentation policy SHA-256")
            require_sha256(catalog_sha256, "catalog SHA-256")
            policy_require(presentation_policy_sha256 == ReferencePresentationPolicy.canonical_policy_sha256) {
                "catalog presentation policy is unsupported"
            }
            policy_require(this.subtype_counts.map { it.first } == SemanticSubtype.entries) {
                "catalog must contain the exact subtype set"
            }
        } else {
            policy_require(
                renderer_semantic_stream_sha256 == null && renderer_contract_sha256 == null &&
                    presentation_policy_sha256 == null && catalog_sha256 == null &&
                    this.subtype_counts.isEmpty(),
            ) { "unavailable catalog cannot claim verified data" }
        }
    }

    companion object {
        fun from_installed_bytes(
            catalog_bytes: ByteArray,
            expected_catalog_sha256: String,
            expected_renderer_semantic_stream_sha256: String,
            expected_renderer_contract_sha256: String,
            expected_presentation_policy_sha256: String,
        ): ReferenceClassCatalog {
            val installed_bytes = catalog_bytes.copyOf()
            require_sha256(expected_catalog_sha256, "catalog SHA-256")
            require_sha256(expected_renderer_semantic_stream_sha256, "expected renderer semantic stream SHA-256")
            require_sha256(expected_renderer_contract_sha256, "expected renderer contract SHA-256")
            require_sha256(expected_presentation_policy_sha256, "expected presentation policy SHA-256")
            val actual = sha256_hex(installed_bytes)
            policy_require(actual == expected_catalog_sha256) {
                "catalog SHA-256 mismatch: expected $expected_catalog_sha256, got $actual"
            }
            val decoded = ReferenceCatalogCodec.decode(installed_bytes)
            policy_require(decoded.renderer_semantic_stream_sha256 == expected_renderer_semantic_stream_sha256) {
                "catalog renderer semantic stream does not match the installed package"
            }
            policy_require(decoded.renderer_contract_sha256 == expected_renderer_contract_sha256) {
                "catalog renderer contract does not match the installed package"
            }
            policy_require(decoded.presentation_policy_sha256 == expected_presentation_policy_sha256) {
                "catalog presentation policy does not match the installed package"
            }
            policy_require(decoded.presentation_policy_sha256 == ReferencePresentationPolicy.canonical_policy_sha256) {
                "catalog presentation policy is not supported"
            }
            return ReferenceClassCatalog(
                CatalogControlStatus.AVAILABLE,
                "verified",
                decoded.renderer_semantic_stream_sha256,
                decoded.renderer_contract_sha256,
                decoded.presentation_policy_sha256,
                actual,
                decoded.subtype_counts,
            )
        }

        fun unavailable(reason: String): ReferenceClassCatalog {
            policy_require(reason.isNotEmpty() && reason.trim() == reason) {
                "unavailable catalog reason must be explicit"
            }
            return ReferenceClassCatalog(
                CatalogControlStatus.UNAVAILABLE, reason, null, null, null, null, emptyList(),
            )
        }

    }
}

@ConsistentCopyVisibility
data class FilterState private constructor(
    private val enabled_filters: Set<FilterId>,
    val labels_master_enabled: Boolean,
    val outlines_master_enabled: Boolean,
) {
    fun stored_enabled(filter_id: FilterId): Boolean = filter_id in enabled_filters

    fun effectively_enabled(filter_id: FilterId): Boolean {
        val master = if (ReferencePresentationPolicy.filter_spec(filter_id).kind == FilterKind.LABEL) {
            labels_master_enabled
        } else {
            outlines_master_enabled
        }
        return master && stored_enabled(filter_id)
    }

    fun with_filter(filter_id: FilterId, enabled: Boolean): FilterState {
        val next = enabled_filters.toMutableSet()
        if (enabled) next += filter_id else next -= filter_id
        return of(next, labels_master_enabled, outlines_master_enabled)
    }

    fun with_labels_master(enabled: Boolean): FilterState =
        of(enabled_filters, enabled, outlines_master_enabled)

    fun with_outlines_master(enabled: Boolean): FilterState =
        of(enabled_filters, labels_master_enabled, enabled)

    companion object {
        fun defaults(): FilterState = of(FilterId.entries.toSet(), true, true)

        fun of(
            enabled: Collection<FilterId>,
            labels_master_enabled: Boolean,
            outlines_master_enabled: Boolean,
        ): FilterState = FilterState(
            Collections.unmodifiableSet(enabled.toSet()),
            labels_master_enabled,
            outlines_master_enabled,
        )
    }
}

internal data class DecodedCatalog(
    val renderer_semantic_stream_sha256: String,
    val renderer_contract_sha256: String,
    val presentation_policy_sha256: String,
    val subtype_counts: List<Pair<SemanticSubtype, SubtypeCatalogCounts>>,
)

internal object ReferenceCatalogCodec {
    private const val version = 1
    private const val exactSize = 754

    fun encode(
        rendererSemanticStreamSha256: String,
        rendererContractSha256: String,
        presentationPolicySha256: String,
        subtypeCounts: Map<SemanticSubtype, SubtypeCatalogCounts>,
    ): ByteArray {
        require_sha256(rendererSemanticStreamSha256, "renderer semantic stream SHA-256")
        require_sha256(rendererContractSha256, "renderer contract SHA-256")
        require_sha256(presentationPolicySha256, "presentation policy SHA-256")
        policy_require(presentationPolicySha256 == ReferencePresentationPolicy.canonical_policy_sha256) {
            "catalog presentation policy is unsupported"
        }
        policy_require(subtypeCounts.keys == SemanticSubtype.entries.toSet()) {
            "catalog must contain the exact subtype set"
        }
        val writer = PolicyByteWriter()
            .ascii("FAE8CAT1\u0000")
            .u8(version)
            .raw(hex_bytes(rendererSemanticStreamSha256))
            .raw(hex_bytes(rendererContractSha256))
            .raw(hex_bytes(presentationPolicySha256))
            .u32(SemanticSubtype.entries.size.toUInt())
        SemanticSubtype.entries.forEach { subtype ->
            val counts = subtypeCounts.getValue(subtype)
            writer.u32(subtype.stable_id.toUInt())
                .u64(counts.distinct_feature_count)
                .u64(counts.canonical_variant_count)
                .u64(counts.posting_count)
        }
        return writer.finish().also {
            policy_require(it.size == exactSize) { "catalog byte length is noncanonical" }
        }
    }

    fun decode(bytes: ByteArray): DecodedCatalog {
        policy_require(bytes.size == exactSize) { "catalog byte length is noncanonical" }
        val reader = PolicyByteReader(bytes)
        policy_require(reader.take(9).contentEquals("FAE8CAT1\u0000".toByteArray(Charsets.US_ASCII))) {
            "catalog domain is unknown"
        }
        policy_require(reader.u8() == version) { "catalog version is unsupported" }
        val semantic = reader.take(32).toHex()
        val contract = reader.take(32).toHex()
        val policy = reader.take(32).toHex()
        policy_require(reader.u32().toInt() == SemanticSubtype.entries.size) {
            "catalog must contain the exact subtype set"
        }
        val counts = SemanticSubtype.entries.map { expected ->
            val actual = SemanticSubtype.from_stable_id(reader.u32().toInt())
            policy_require(actual == expected) {
                "catalog subtype entries must be exact and strictly ID-sorted"
            }
            expected to SubtypeCatalogCounts(reader.u64(), reader.u64(), reader.u64())
        }
        reader.finish()
        return DecodedCatalog(semantic, contract, policy, counts)
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }
}

internal object ReferenceCatalogPolicy {
    fun available_filter_catalog(catalog: ReferenceClassCatalog?): FilterPanelCatalog {
        if (catalog == null) {
            return FilterPanelCatalog(
                CatalogControlStatus.UNAVAILABLE,
                "reference_package_catalog_unavailable",
                emptyList(),
            )
        }
        if (catalog.status == CatalogControlStatus.UNAVAILABLE) {
            return FilterPanelCatalog(catalog.status, catalog.reason, emptyList())
        }
        val counts = catalog.subtype_counts.toMap()
        val available = ReferencePresentationTables.all_filter_specs()
            .filter { spec -> spec.subtypes.any { counts.getValue(it).distinct_feature_count > 0uL } }
            .map { it.filter_id }
        return FilterPanelCatalog(
            CatalogControlStatus.AVAILABLE,
            "verified",
            Collections.unmodifiableList(available),
        )
    }
}
