@file:Suppress("ArrayInDataClass")

package com.flightalert.map

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

internal class ReferenceDictionaryBinaryTileException(message: String) :
    IllegalArgumentException(message)

internal enum class ReferenceDictionaryBinaryFeatureKind(val stableCode: Int) {
    LABEL(1),
    LINE(2),
    POLYGON_OUTLINE(3),
}

internal enum class ReferenceDictionaryBinaryGeometryKind(val stableCode: Int) {
    POINT(1),
    PATH(2),
    POLYGON(3),
}

internal enum class ReferenceDictionaryBinaryLayerGroup(val stableCode: Int) {
    PLACES(1),
    WATER(2),
    REGIONS(3),
    PUBLIC_LANDS(4),
    TRANSPORTATION(5),
    CONTEXT(6),
}

internal data class ReferenceDictionaryBinaryGeometry(
    val kind: ReferenceDictionaryBinaryGeometryKind,
    val partOffsets: IntArray,
    val localCoordinates: FloatArray,
    val localBounds: FloatArray,
)

internal data class ReferenceDictionaryBinaryPlacement(
    val labelCandidateId: ULong,
    val textSourceFieldId: ULong,
    val prominenceTier: ProminenceTier,
    val completeGeometryMeasureBucket: Int,
    val spacingPx: Int,
    val maximumAngleDegrees: Int,
    val collisionGroup: ULong,
    val semanticPriority: Int,
    val avoidEdges: Boolean,
    val keepUpright: Boolean,
    val activeBandLimit: Int,
)

internal data class ReferenceDictionaryBinaryRecord(
    val featureId: ULong,
    val canonicalVariantId: ULong,
    val dedupeId: ULong,
    val layerGroup: ReferenceDictionaryBinaryLayerGroup,
    val featureKind: ReferenceDictionaryBinaryFeatureKind,
    val subtype: SemanticSubtype,
    val geometry: ReferenceDictionaryBinaryGeometry,
    val sourcedText: SourcedMapText?,
    val placement: ReferenceDictionaryBinaryPlacement,
    val minimumZoomCenti: Int,
    val fullAlphaZoomCenti: Int,
    val fadeOutZoomCenti: Int,
    val maximumZoomCenti: Int,
    val drawOrder: Int,
    val priority: Int,
    val postingWorldWrap: Int = 0,
)

internal data class ReferenceDictionaryBinaryTile(
    val coordinate: ReferenceDictionaryTileCoordinate,
    val records: List<ReferenceDictionaryBinaryRecord>,
)

/** Strict decoder for a background-loaded reference dictionary renderer tile. */
internal object ReferenceDictionaryBinaryTileCodec {
    private val tileMagic = "FAE8TILE1\u0000".toByteArray(Charsets.US_ASCII)
    private val n8Magic = "N8T1".toByteArray(Charsets.US_ASCII)
    private val variantDomain = "FAE8VAR1\u0000".toByteArray(Charsets.US_ASCII)
    private val geometryDomain =
        "exp8-renderer-geometry-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val expectedStylePolicy by lazy {
        decodeLowerHex(ReferencePresentationPolicy.canonical_policy_sha256)
    }
    private val canonicalNonApplicablePlacement by lazy {
        ByteArray(NON_APPLICABLE_PLACEMENT_BYTES).apply {
            n8Magic.copyInto(this)
            this[n8Magic.size] = 0x20
            this[NON_APPLICABLE_DECLARED_EXTENT_OFFSET] = 1
        }
    }

    fun decode(
        coordinate: ReferenceDictionaryTileCoordinate,
        bytes: ByteArray,
    ): ReferenceDictionaryBinaryTile {
        return try {
            decodeStrict(coordinate, bytes)
        } catch (error: ReferenceDictionaryBinaryTileException) {
            throw error
        } catch (error: Exception) {
            throw ReferenceDictionaryBinaryTileException(
                error.message ?: "binary reference tile is malformed",
            )
        }
    }

    private fun decodeStrict(
        coordinate: ReferenceDictionaryTileCoordinate,
        bytes: ByteArray,
    ): ReferenceDictionaryBinaryTile {
        requireBinary(bytes.isNotEmpty() && bytes.size <= MAX_TILE_BYTES) {
            "binary reference tile byte length is outside its bound"
        }
        validateCoordinate(coordinate)
        val reader = BinaryReader(bytes)
        reader.expect(tileMagic, "binary reference tile magic is unsupported")
        val encoded = ReferenceDictionaryTileCoordinate(
            z = reader.u8(),
            x = reader.u32Int("tile x"),
            y = reader.u32Int("tile y"),
        )
        validateCoordinate(encoded)
        requireBinary(encoded == coordinate) {
            "binary reference tile coordinate does not match its index entry"
        }
        val count = reader.u32Int("renderer record count")
        requireBinary(count <= MAX_RECORDS_PER_TILE) {
            "binary reference tile record count exceeds its bound"
        }
        val identityDigest = MessageDigest.getInstance("SHA-256")
        val records = ArrayList<ReferenceDictionaryBinaryRecord>(count)
        repeat(count) {
            val rendererBytes = reader.blob(MAX_RENDERER_RECORD_BYTES, "renderer record")
            val sourcedLength = reader.u32Int("sourced-text record length")
            val sourced = if (sourcedLength == 0) {
                null
            } else {
                requireBinary(sourcedLength <= MAX_SOURCED_TEXT_RECORD_BYTES) {
                    "sourced-text record exceeds its tile bound"
                }
                val expectedDigest = reader.take(32)
                val canonical = reader.take(sourcedLength)
                try {
                    SourcedMapTextBinaryCodec.decode(canonical, expectedDigest, identityDigest)
                } catch (error: SourcedMapTextException) {
                    throw ReferenceDictionaryBinaryTileException(error.message ?: "sourced text is invalid")
                }
            }
            records += decodeRendererRecord(coordinate, rendererBytes, sourced, identityDigest)
        }
        reader.finish("binary reference tile has trailing bytes")
        return ReferenceDictionaryBinaryTile(coordinate, records)
    }

    private fun decodeRendererRecord(
        coordinate: ReferenceDictionaryTileCoordinate,
        bytes: ByteArray,
        sourcedText: SourcedMapText?,
        identityDigest: MessageDigest,
    ): ReferenceDictionaryBinaryRecord {
        val reader = BinaryReader(bytes)
        reader.n8Header(8, n8Magic, "renderer record")
        val featureId = reader.u64()
        val canonicalVariantId = reader.u64()
        decodePackedTile(reader.u64())
        val worldWrap = reader.i32()
        val variantBytes = reader.blob(MAX_VARIANT_BYTES, "canonical variant")
        reader.finish("renderer record has trailing bytes")
        requireBinary(hotId(identityDigest, variantDomain, variantBytes) == canonicalVariantId) {
            "canonical variant ID does not match FAE8VAR1 bytes"
        }
        val variant = decodeVariant(coordinate, worldWrap, variantBytes, identityDigest)
        val isLabel = variant.featureKind == ReferenceDictionaryBinaryFeatureKind.LABEL
        requireBinary(isLabel == (sourcedText != null)) {
            "labels require sourced text and non-label records forbid it"
        }
        if (sourcedText != null) {
            requireBinary(variant.visibleText == sourcedText.primaryText) {
                "canonical variant text differs from sourced primary text"
            }
            requireBinary(variant.placement.textSourceFieldId == sourcedText.primarySourceFieldId) {
                "placement source field differs from sourced primary field"
            }
        }
        return ReferenceDictionaryBinaryRecord(
            featureId = featureId,
            canonicalVariantId = canonicalVariantId,
            dedupeId = variant.dedupeId,
            layerGroup = variant.layerGroup,
            featureKind = variant.featureKind,
            subtype = variant.subtype,
            geometry = variant.geometry,
            sourcedText = sourcedText,
            placement = variant.placement,
            minimumZoomCenti = variant.minimumZoomCenti,
            fullAlphaZoomCenti = variant.fullAlphaZoomCenti,
            fadeOutZoomCenti = variant.fadeOutZoomCenti,
            maximumZoomCenti = variant.maximumZoomCenti,
            drawOrder = variant.drawOrder,
            priority = variant.priority,
            postingWorldWrap = worldWrap,
        )
    }

    private fun decodeVariant(
        coordinate: ReferenceDictionaryTileCoordinate,
        worldWrap: Int,
        bytes: ByteArray,
        identityDigest: MessageDigest,
    ): DecodedVariant {
        val reader = BinaryReader(bytes)
        reader.n8Header(4, n8Magic, "canonical variant")
        val dedupeId = reader.u64()
        val geometryId = reader.u64()
        reader.u64() // source layer identity remains audit-bound, not string-decoded on phone.
        reader.u64() // source scale-band identity.
        val layerGroupCode = reader.u8()
        val layerGroup = ReferenceDictionaryBinaryLayerGroup.entries
            .firstOrNull { it.stableCode == layerGroupCode }
            ?: fail("canonical variant layer group is unknown")
        val featureKindCode = reader.u8()
        val featureKind = ReferenceDictionaryBinaryFeatureKind.entries
            .firstOrNull { it.stableCode == featureKindCode }
            ?: fail("canonical variant feature kind is unknown")
        val subtype = SemanticSubtype.from_stable_id(reader.u32Int("semantic subtype"))
            ?: fail("canonical variant semantic subtype is unknown")
        skipU64Table(reader, "source style")
        skipU64Table(reader, "render style")
        val visibleText = if (reader.boolean("visible-text presence")) {
            reader.text(MAX_TEXT_BYTES, "visible text")
        } else {
            null
        }
        val geometryBytes = reader.blob(MAX_GEOMETRY_BYTES, "renderer geometry")
        requireBinary(hotId(identityDigest, geometryDomain, geometryBytes) == geometryId) {
            "renderer geometry ID does not match its canonical bytes"
        }
        val geometry = decodeGeometry(coordinate, worldWrap, geometryBytes)
        val minimumZoom = reader.i32()
        val maximumZoom = reader.i32()
        val fullAlphaZoom = reader.i32()
        val fadeOutZoom = reader.i32()
        requireBinary(
            minimumZoom in 0..MAX_CENTIZOOM &&
                minimumZoom <= fullAlphaZoom &&
                fullAlphaZoom <= fadeOutZoom &&
                fadeOutZoom <= maximumZoom &&
                maximumZoom <= MAX_CENTIZOOM,
        ) { "canonical variant zoom interval is invalid" }
        val drawOrder = reader.i32()
        val priority = reader.i32()
        val isLabel = featureKind == ReferenceDictionaryBinaryFeatureKind.LABEL
        val placement = decodePlacement(
            reader.blob(MAX_PLACEMENT_BYTES, "label placement"),
            isLabel,
        )
        requireBinary(reader.u8() in 0..3 && reader.u8() in 0..3) {
            "canonical variant evidence state is unknown"
        }
        reader.u32() // flags are baked and retained in the FAE8VAR1 identity.
        reader.finish("canonical variant has trailing bytes")
        requireBinary(featureKind == ReferenceDictionaryBinaryFeatureKind.LABEL == subtype.is_label) {
            "feature kind and semantic subtype disagree"
        }
        requireBinary((visibleText != null) == (featureKind == ReferenceDictionaryBinaryFeatureKind.LABEL)) {
            "canonical variant visible-text state is inconsistent"
        }
        if (isLabel) {
            requireBinary(placement.semanticPriority == priority) {
                "placement and renderer priority disagree"
            }
            requireBinary(
                placement.displayMinimumZoomCenti == minimumZoom &&
                    placement.displayMaximumZoomCenti == maximumZoom,
            ) { "placement and renderer zoom intervals disagree" }
        }
        requireGeometryKind(featureKind, geometry.kind)
        return DecodedVariant(
            dedupeId,
            layerGroup,
            featureKind,
            subtype,
            geometry,
            visibleText,
            placement.publicValue,
            minimumZoom,
            fullAlphaZoom,
            fadeOutZoom,
            maximumZoom,
            drawOrder,
            priority,
        )
    }

    private fun decodeGeometry(
        coordinate: ReferenceDictionaryTileCoordinate,
        worldWrap: Int,
        bytes: ByteArray,
    ): ReferenceDictionaryBinaryGeometry {
        val reader = BinaryReader(bytes)
        reader.n8Header(2, n8Magic, "renderer geometry")
        val kindCode = reader.u8()
        val kind = ReferenceDictionaryBinaryGeometryKind.entries
            .firstOrNull { it.stableCode == kindCode }
            ?: fail("renderer geometry kind is unknown")
        val denominator = reader.u64()
        requireBinary(denominator in 1uL..Long.MAX_VALUE.toULong()) {
            "renderer geometry denominator is invalid"
        }
        val partCount = reader.u32Int("geometry part count")
        requireBinary(partCount in 1..MAX_GEOMETRY_POINTS) {
            "geometry part count exceeds its bound"
        }
        val parts = IntArray(partCount) { reader.u32Int("geometry part offset") }
        val coordinateCount = reader.u32Int("geometry coordinate count")
        requireBinary(
            coordinateCount in 2..MAX_GEOMETRY_POINTS * 2 && coordinateCount % 2 == 0,
        ) { "geometry coordinate count is invalid" }
        val numerators = LongArray(coordinateCount) { reader.i64() }
        val encodedBounds = LongArray(4) { reader.i64() }
        reader.finish("renderer geometry has trailing bytes")
        validateGeometry(kind, parts, numerators, encodedBounds)
        val scale = (1L shl coordinate.z).toDouble()
        val denominatorDouble = denominator.toLong().toDouble()
        val wrappedTileX = coordinate.x.toDouble() + worldWrap.toDouble() * scale
        val local = FloatArray(coordinateCount)
        for (index in numerators.indices step 2) {
            local[index] = (
                (numerators[index].toDouble() / denominatorDouble * scale - wrappedTileX) *
                    REFERENCE_EXTENT
                ).toFloat()
            local[index + 1] = (
                (numerators[index + 1].toDouble() / denominatorDouble * scale - coordinate.y) *
                    REFERENCE_EXTENT
                ).toFloat()
            requireBinary(local[index].isFinite() && local[index + 1].isFinite()) {
                "renderer geometry projection is non-finite"
            }
        }
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (index in local.indices step 2) {
            minX = min(minX, local[index])
            minY = min(minY, local[index + 1])
            maxX = max(maxX, local[index])
            maxY = max(maxY, local[index + 1])
        }
        return ReferenceDictionaryBinaryGeometry(
            kind,
            parts,
            local,
            floatArrayOf(minX, minY, maxX, maxY),
        )
    }

    private fun decodePlacement(bytes: ByteArray, isLabel: Boolean): DecodedPlacement {
        if (!isLabel) {
            requireBinary(bytes.contentEquals(canonicalNonApplicablePlacement)) {
                "non-label records require the canonical non-applicable placement"
            }
            return DecodedPlacement(
                publicValue = ReferenceDictionaryBinaryPlacement(
                    labelCandidateId = 0uL,
                    textSourceFieldId = 0uL,
                    prominenceTier = ProminenceTier.GLOBAL_MAJOR,
                    completeGeometryMeasureBucket = 0,
                    spacingPx = 0,
                    maximumAngleDegrees = 0,
                    collisionGroup = 0uL,
                    semanticPriority = 0,
                    avoidEdges = false,
                    keepUpright = false,
                    activeBandLimit = 0,
                ),
                displayMinimumZoomCenti = 0,
                displayMaximumZoomCenti = 0,
            )
        }
        val reader = BinaryReader(bytes)
        reader.n8Header(0x20, n8Magic, "label placement")
        val labelCandidateId = reader.u64()
        val labelCandidateSha = reader.take(32)
        val sourceFeatureSha = reader.take(32)
        val placementGeometrySha = reader.take(32)
        requireBinary(firstU64BigEndian(labelCandidateSha) == labelCandidateId) {
            "label candidate hot ID differs from its full identity"
        }
        val evidenceKind = reader.u8()
        requireBinary(evidenceKind in 0..4) { "text evidence kind is unknown" }
        val textSourceFieldId = reader.u64()
        val sourceFeatureId = reader.u64()
        val placementGeometryId = reader.u64()
        requireBinary(firstU64BigEndian(sourceFeatureSha) == sourceFeatureId) {
            "placement source feature identity is inconsistent"
        }
        requireBinary(firstU64BigEndian(placementGeometrySha) == placementGeometryId) {
            "placement geometry identity is inconsistent"
        }
        decodePackedTile(reader.u64())
        val sourceZoom = reader.u8()
        requireBinary(sourceZoom in 0..29) { "placement source zoom is invalid" }
        requireBinary(reader.u64() in 1uL..Long.MAX_VALUE.toULong()) {
            "placement source extent is invalid"
        }
        repeat(4) { reader.i64() }
        requireBinary(reader.u8() in 0..4) { "placement source kind is unknown" }
        val displayMinimumZoom = reader.i32()
        val displayMaximumZoom = reader.i32()
        val spacingPx = reader.u32Int("label spacing")
        val maximumAngle = reader.u32Int("maximum label angle")
        val collisionGroup = reader.u64()
        val semanticPriority = reader.i32()
        val prominenceCode = reader.u8()
        val prominence = ProminenceTier.entries.firstOrNull { it.stable_code == prominenceCode }
            ?: fail("label prominence tier is unknown")
        if (reader.boolean("provider-rank presence")) reader.i32()
        val completeGeometryMeasureBucket = reader.u16()
        val prominenceRuleId = reader.u64()
        val prominenceDecision = reader.take(32)
        val avoidEdges = reader.boolean("avoid-edges flag")
        val keepUpright = reader.boolean("keep-upright flag")
        val activeBandLimit = reader.u8()
        val stylePolicySha = reader.take(32)
        if (reader.boolean("provider-feature presence")) reader.u64()
        reader.finish("label placement has trailing bytes")
        requireBinary(stylePolicySha.contentEquals(expectedStylePolicy)) {
            "label placement uses an unsupported presentation policy"
        }
        requireBinary(displayMinimumZoom in 0 until displayMaximumZoom && displayMaximumZoom <= MAX_CENTIZOOM) {
            "label placement zoom interval is invalid"
        }
        requireBinary(spacingPx > 0 && maximumAngle in 0..180 && activeBandLimit in 1..29) {
            "label placement limits are invalid"
        }
        requireBinary(prominenceRuleId != 0uL && prominenceDecision.any { it.toInt() != 0 }) {
            "label prominence evidence is unavailable"
        }
        return DecodedPlacement(
            publicValue = ReferenceDictionaryBinaryPlacement(
                labelCandidateId = labelCandidateId,
                textSourceFieldId = textSourceFieldId,
                prominenceTier = prominence,
                completeGeometryMeasureBucket = completeGeometryMeasureBucket,
                spacingPx = spacingPx,
                maximumAngleDegrees = maximumAngle,
                collisionGroup = collisionGroup,
                semanticPriority = semanticPriority,
                avoidEdges = avoidEdges,
                keepUpright = keepUpright,
                activeBandLimit = activeBandLimit,
            ),
            displayMinimumZoomCenti = displayMinimumZoom,
            displayMaximumZoomCenti = displayMaximumZoom,
        )
    }

    private fun validateGeometry(
        kind: ReferenceDictionaryBinaryGeometryKind,
        parts: IntArray,
        coordinates: LongArray,
        bounds: LongArray,
    ) {
        val points = coordinates.size / 2
        requireBinary(parts.first() == 0) { "geometry parts must start at zero" }
        parts.forEachIndexed { index, part ->
            requireBinary(part in 0 until points && (index == 0 || part > parts[index - 1])) {
                "geometry part offsets are not strictly increasing"
            }
            val end = if (index + 1 < parts.size) parts[index + 1] else points
            val count = end - part
            requireBinary(
                when (kind) {
                    ReferenceDictionaryBinaryGeometryKind.POINT -> count >= 1
                    ReferenceDictionaryBinaryGeometryKind.PATH -> count >= 2
                    ReferenceDictionaryBinaryGeometryKind.POLYGON -> count >= 4
                },
            ) { "geometry part has too few points" }
            if (kind == ReferenceDictionaryBinaryGeometryKind.POLYGON) {
                requireBinary(
                    coordinates[part * 2] == coordinates[(end - 1) * 2] &&
                        coordinates[part * 2 + 1] == coordinates[(end - 1) * 2 + 1],
                ) { "polygon ring is not exactly closed" }
            }
        }
        val actual = longArrayOf(
            coordinates.filterIndexed { index, _ -> index % 2 == 0 }.min(),
            coordinates.filterIndexed { index, _ -> index % 2 == 1 }.min(),
            coordinates.filterIndexed { index, _ -> index % 2 == 0 }.max(),
            coordinates.filterIndexed { index, _ -> index % 2 == 1 }.max(),
        )
        requireBinary(actual.contentEquals(bounds)) { "renderer geometry bounds are inexact" }
    }

    private fun requireGeometryKind(
        feature: ReferenceDictionaryBinaryFeatureKind,
        geometry: ReferenceDictionaryBinaryGeometryKind,
    ) {
        requireBinary(
            when (feature) {
                ReferenceDictionaryBinaryFeatureKind.LABEL -> geometry in setOf(
                    ReferenceDictionaryBinaryGeometryKind.POINT,
                    ReferenceDictionaryBinaryGeometryKind.PATH,
                )
                ReferenceDictionaryBinaryFeatureKind.LINE ->
                    geometry == ReferenceDictionaryBinaryGeometryKind.PATH
                ReferenceDictionaryBinaryFeatureKind.POLYGON_OUTLINE ->
                    geometry == ReferenceDictionaryBinaryGeometryKind.POLYGON
            },
        ) { "feature kind and renderer geometry disagree" }
    }

    private fun skipU64Table(reader: BinaryReader, label: String) {
        val count = reader.u32Int("$label reference count")
        requireBinary(count <= MAX_TABLE_REFERENCES && count <= reader.remaining / 8) {
            "$label reference count exceeds its bound"
        }
        repeat(count) { reader.u64() }
    }

    private fun validateCoordinate(coordinate: ReferenceDictionaryTileCoordinate) {
        requireBinary(coordinate.z in 0..29) { "reference tile zoom is invalid" }
        val limit = 1 shl coordinate.z
        requireBinary(coordinate.x in 0 until limit && coordinate.y in 0 until limit) {
            "reference tile coordinate is invalid"
        }
    }

    private fun decodePackedTile(packed: ULong): ReferenceDictionaryTileCoordinate {
        requireBinary(packed < (1uL shl 63)) { "packed tile is outside its domain" }
        val mask = (1uL shl 29) - 1uL
        return ReferenceDictionaryTileCoordinate(
            z = ((packed shr 58) and 0x1fuL).toInt(),
            x = ((packed shr 29) and mask).toInt(),
            y = (packed and mask).toInt(),
        ).also(::validateCoordinate)
    }

    private fun hotId(
        digest: MessageDigest,
        domain: ByteArray,
        canonical: ByteArray,
    ): ULong {
        return firstU64BigEndian(sha256_bytes(digest, domain, canonical))
    }

    private fun firstU64BigEndian(bytes: ByteArray): ULong {
        requireBinary(bytes.size >= 8) { "identity digest is truncated" }
        var value = 0uL
        repeat(8) { index -> value = (value shl 8) or bytes[index].toUByte().toULong() }
        return value
    }

    private fun decodeLowerHex(value: String): ByteArray {
        requireBinary(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "policy identity is malformed"
        }
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun requireBinary(condition: Boolean, message: () -> String) {
        if (!condition) throw ReferenceDictionaryBinaryTileException(message())
    }

    private fun fail(message: String): Nothing =
        throw ReferenceDictionaryBinaryTileException(message)

    private data class DecodedVariant(
        val dedupeId: ULong,
        val layerGroup: ReferenceDictionaryBinaryLayerGroup,
        val featureKind: ReferenceDictionaryBinaryFeatureKind,
        val subtype: SemanticSubtype,
        val geometry: ReferenceDictionaryBinaryGeometry,
        val visibleText: String?,
        val placement: ReferenceDictionaryBinaryPlacement,
        val minimumZoomCenti: Int,
        val fullAlphaZoomCenti: Int,
        val fadeOutZoomCenti: Int,
        val maximumZoomCenti: Int,
        val drawOrder: Int,
        val priority: Int,
    )

    private data class DecodedPlacement(
        val publicValue: ReferenceDictionaryBinaryPlacement,
        val displayMinimumZoomCenti: Int,
        val displayMaximumZoomCenti: Int,
    ) {
        val textSourceFieldId: ULong get() = publicValue.textSourceFieldId
        val semanticPriority: Int get() = publicValue.semanticPriority
    }

    private class BinaryReader(private val bytes: ByteArray) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset

        fun take(length: Int): ByteArray {
            requireBinary(length >= 0 && length <= remaining) { "binary reference bytes are truncated" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun expect(value: ByteArray, message: String) {
            requireBinary(take(value.size).contentEquals(value)) { message }
        }

        fun n8Header(tag: Int, magic: ByteArray, label: String) {
            expect(magic, "$label canonical magic is unsupported")
            requireBinary(u8() == tag) { "$label canonical tag is unsupported" }
        }

        fun u8(): Int {
            requireBinary(remaining > 0) { "binary reference bytes are truncated" }
            return bytes[offset++].toUByte().toInt()
        }

        fun boolean(label: String): Boolean = when (val value = u8()) {
            0 -> false
            1 -> true
            else -> fail("$label is not canonical: $value")
        }

        fun u16(): Int {
            val b0 = u8()
            val b1 = u8()
            return b0 or (b1 shl 8)
        }

        fun u32(): UInt {
            var value = 0u
            repeat(4) { index -> value = value or (u8().toUInt() shl (index * 8)) }
            return value
        }

        fun u32Int(label: String): Int {
            val value = u32()
            requireBinary(value <= Int.MAX_VALUE.toUInt()) { "$label exceeds the phone integer domain" }
            return value.toInt()
        }

        fun i32(): Int = u32().toInt()

        fun u64(): ULong {
            var value = 0uL
            repeat(8) { index -> value = value or (u8().toULong() shl (index * 8)) }
            return value
        }

        fun i64(): Long = u64().toLong()

        fun blob(maximum: Int, label: String): ByteArray {
            val length = u32Int("$label length")
            requireBinary(length <= maximum) { "$label exceeds its byte bound" }
            return take(length)
        }

        fun text(maximum: Int, label: String): String {
            val data = blob(maximum, label)
            val value = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString()
            } catch (_: Exception) {
                fail("$label is not strict UTF-8")
            }
            requireBinary(value.isNotEmpty() && Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
                "$label is empty or not NFC"
            }
            return value
        }

        fun finish(message: String) {
            requireBinary(remaining == 0) { message }
        }
    }

    private fun ByteArray.toLowerHex(): String = joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }

    private const val REFERENCE_EXTENT = 4_096.0
    private const val MAX_CENTIZOOM = 10_000
    private const val MAX_TEXT_BYTES = 4_096
    private const val MAX_TILE_BYTES = 32 * 1024 * 1024
    private const val MAX_RECORDS_PER_TILE = 65_536
    private const val MAX_RENDERER_RECORD_BYTES = 8 * 1024 * 1024
    private const val MAX_VARIANT_BYTES = 8 * 1024 * 1024
    private const val MAX_GEOMETRY_BYTES = 8 * 1024 * 1024
    private const val MAX_PLACEMENT_BYTES = 4 * 1024
    private const val MAX_SOURCED_TEXT_RECORD_BYTES = 8_300
    private const val MAX_GEOMETRY_POINTS = 1_048_576
    private const val MAX_TABLE_REFERENCES = 262_144
    private const val NON_APPLICABLE_PLACEMENT_BYTES = 292
    private const val NON_APPLICABLE_DECLARED_EXTENT_OFFSET = 143
}
