@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "ConvertTryFinallyToUseCall",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class ReferenceDictionaryParsedManifest(
    val info: ReferenceDictionaryPackageInfo,
    val zoom_ranges: List<ReferenceDictionaryZoomRange>,
    val renderer_semantic_stream_sha256: String?,
    val class_catalog_manifest: ReferenceDictionaryV3ClassCatalogManifest?,
    val presentation_policy_sha256: String,
)