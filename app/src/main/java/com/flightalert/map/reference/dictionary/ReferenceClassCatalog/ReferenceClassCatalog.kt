@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import com.flightalert.map.policy_require
import com.flightalert.map.reference.CatalogControlStatus
import com.flightalert.map.reference.ReferencePresentationPolicy
import com.flightalert.map.reference.SemanticSubtype
import com.flightalert.map.require_sha256
import com.flightalert.map.sha256_hex
import java.util.Collections


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