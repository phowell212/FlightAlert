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

import com.flightalert.map.SourcedMapTextBinaryCodec
import com.flightalert.map.reference.ReferencePresentationPolicy
import java.io.IOException


internal object ReferenceDictionaryRuntimeSchemaResolver {
    private const val binaryV3PayloadSchema = "flightalert.reference.renderer-tile.v1"
    const val renderTileV1PayloadSchema = "flightalert.reference.render-tile.v1"

    // Accept installed data only when every schema identity matches.
    fun resolve(
        schemaVersion: Int,
        hasPayloadSchema: Boolean,
        payloadSchema: String,
        emptyPresentTilesSharePayload: Boolean,
        presentationPolicySha256: String,
        sourcedTextPolicySha256: String,
        unicodeScriptProfileSha256: String,
    ): ReferenceDictionaryRuntimeSchema {
        if (schemaVersion == 3 || schemaVersion == 4) {
            val expectedPayloadSchema = if (schemaVersion == 3) {
                binaryV3PayloadSchema
            } else {
                renderTileV1PayloadSchema
            }
            if (!hasPayloadSchema || payloadSchema != expectedPayloadSchema ||
                presentationPolicySha256 != ReferencePresentationPolicy.canonical_policy_sha256 ||
                sourcedTextPolicySha256 != SourcedMapTextBinaryCodec.policySha256 ||
                unicodeScriptProfileSha256 != SourcedMapTextBinaryCodec.unicodeScriptProfileSha256
            ) {
                throw IOException("Unsupported reference dictionary policy identity")
            }
            return if (schemaVersion == 3) {
                ReferenceDictionaryRuntimeSchema.BINARY_V3
            } else {
                ReferenceDictionaryRuntimeSchema.RENDER_TILE_V1
            }
        }
        return if (hasPayloadSchema || emptyPresentTilesSharePayload || schemaVersion >= 2) {
            ReferenceDictionaryRuntimeSchema.HOT_JSON_V2
        } else {
            ReferenceDictionaryRuntimeSchema.LEGACY_JSON
        }
    }
}
