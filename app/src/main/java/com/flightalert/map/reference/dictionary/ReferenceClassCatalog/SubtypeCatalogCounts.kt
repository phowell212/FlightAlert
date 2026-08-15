@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

data class SubtypeCatalogCounts(
    val distinct_feature_count: ULong,
    val canonical_variant_count: ULong,
    val posting_count: ULong,
)