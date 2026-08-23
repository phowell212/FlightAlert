@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import com.flightalert.map.reference.SemanticSubtype

data class SubtypeCatalogCounts(
    val distinct_feature_count: ULong,
    val canonical_variant_count: ULong,
    val posting_count: ULong,
)

internal data class DecodedCatalog(
    val renderer_semantic_stream_sha256: String,
    val renderer_contract_sha256: String,
    val presentation_policy_sha256: String,
    val subtype_counts: List<Pair<SemanticSubtype, SubtypeCatalogCounts>>,
)
