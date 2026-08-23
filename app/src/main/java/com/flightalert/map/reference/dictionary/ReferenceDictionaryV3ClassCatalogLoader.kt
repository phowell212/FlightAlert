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

import java.io.File


internal object ReferenceDictionaryV3ClassCatalogLoader {
    // Validate the catalog name, size, and install state while opening it.
    fun load(
        package_dir: File,
        runtime_schema: ReferenceDictionaryRuntimeSchema,
        renderer_semantic_stream_sha256: String?,
        class_catalog_manifest: ReferenceDictionaryV3ClassCatalogManifest?,
        presentation_policy_sha256: String,
        read_catalog_bytes: (File) -> ByteArray = { file -> file.readBytes() },
    ): ReferenceClassCatalog {
        if (
            runtime_schema != ReferenceDictionaryRuntimeSchema.BINARY_V3 &&
            runtime_schema != ReferenceDictionaryRuntimeSchema.RENDER_TILE_V1
        ) {
            return unavailable("class catalog is not defined for this reference package schema")
        }
        if (renderer_semantic_stream_sha256 == null || !renderer_semantic_stream_sha256.is_sha256()) {
            return unavailable(
                "V3 manifest rendererSemanticStreamSha256 is missing or malformed",
            )
        }
        val catalog_manifest = class_catalog_manifest
            ?: return unavailable("V3 manifest classCatalog object is missing or malformed")
        val catalog_sha256 = catalog_manifest.catalog_sha256
        if (catalog_sha256 == null || !catalog_sha256.is_sha256()) {
            return unavailable("V3 manifest classCatalog.catalogSha256 is missing or malformed")
        }
        val renderer_contract_sha256 = catalog_manifest.renderer_contract_sha256
        if (renderer_contract_sha256 == null || !renderer_contract_sha256.is_sha256()) {
            return unavailable(
                "V3 manifest classCatalog.rendererContractSha256 is missing or malformed",
            )
        }
        if (renderer_contract_sha256 != renderer_semantic_stream_sha256) {
            return unavailable(
                "V3 manifest classCatalog.rendererContractSha256 must exactly equal " +
                    "rendererSemanticStreamSha256",
            )
        }
        val catalog_file = File(package_dir, CLASS_CATALOG_FILE_NAME)
        return try {
            if (!catalog_file.isFile) {
                return unavailable("class-catalog.bin is missing")
            }
            if (catalog_file.length() != CLASS_CATALOG_BYTE_COUNT.toLong()) {
                return unavailable("class-catalog.bin length is not the canonical 754 bytes")
            }
            val catalog_bytes = read_catalog_bytes(catalog_file)
            if (catalog_bytes.size != CLASS_CATALOG_BYTE_COUNT) {
                return unavailable("class-catalog.bin length is not the canonical 754 bytes")
            }
            ReferenceClassCatalog.from_installed_bytes(
                catalog_bytes = catalog_bytes,
                expected_catalog_sha256 = catalog_sha256,
                expected_renderer_semantic_stream_sha256 = renderer_semantic_stream_sha256,
                expected_renderer_contract_sha256 = renderer_contract_sha256,
                expected_presentation_policy_sha256 = presentation_policy_sha256,
            )
        } catch (exception: Exception) {
            unavailable(
                "class-catalog.bin validation failed: " +
                    (exception.message ?: exception::class.java.simpleName),
            )
        }
    }

    private fun String.is_sha256(): Boolean =
        length == 64 && all { character ->
            character in '0'..'9' || character in 'a'..'f'
        }

    private fun unavailable(reason: String): ReferenceClassCatalog =
        ReferenceClassCatalog.unavailable(reason)

    internal const val CLASS_CATALOG_FILE_NAME = "class-catalog.bin"
    internal const val CLASS_CATALOG_BYTE_COUNT = 754
}
