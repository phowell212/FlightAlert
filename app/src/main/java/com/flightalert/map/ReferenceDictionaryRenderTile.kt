@file:Suppress("ArrayInDataClass")

package com.flightalert.map

import kotlin.math.max
import kotlin.math.min

/** Bounded decoder for the render-ready tile stored by reference package v5. */
internal object ReferenceDictionaryRenderTileCodec {
    private val magic = "FAE8RTILE1\u0000".toByteArray(Charsets.US_ASCII)

    fun decode(
        coordinate: ReferenceDictionaryTileCoordinate,
        bytes: ByteArray,
        outlineVisibilityCentizoom: Int? = null,
    ): ReferenceDictionaryBinaryTile {
        return decodeStrict(
            coordinate,
            bytes,
            outlineVisibilityCentizoom,
            DecodeControl(requestIsRelevant = null),
        )!!
    }

    fun decodeIfRelevant(
        coordinate: ReferenceDictionaryTileCoordinate,
        bytes: ByteArray,
        outlineVisibilityCentizoom: Int? = null,
        requestIsRelevant: () -> Boolean,
    ): ReferenceDictionaryBinaryTile? {
        return decodeStrict(
            coordinate,
            bytes,
            outlineVisibilityCentizoom,
            DecodeControl(requestIsRelevant),
        )
    }

    private fun decodeStrict(
        coordinate: ReferenceDictionaryTileCoordinate,
        bytes: ByteArray,
        outlineVisibilityCentizoom: Int?,
        control: DecodeControl,
    ): ReferenceDictionaryBinaryTile? {
        requireRender(bytes.isNotEmpty() && bytes.size <= MAX_TILE_BYTES) {
            "render-ready tile byte length is outside its bound"
        }
        validateCoordinate(coordinate)
        val reader = Reader(bytes)
        requireRender(reader.take(magic.size).contentEquals(magic)) {
            "render-ready tile magic is unsupported"
        }
        val encoded = ReferenceDictionaryTileCoordinate(
            z = reader.u8(),
            x = reader.u32Int("tile x"),
            y = reader.u32Int("tile y"),
        )
        validateCoordinate(encoded)
        requireRender(encoded == coordinate) {
            "render-ready tile coordinate does not match its index entry"
        }
        val count = reader.u32Int("record count")
        requireRender(count <= MAX_RECORDS_PER_TILE) {
            "render-ready tile record count exceeds its bound"
        }
        if (outlineVisibilityCentizoom != null) {
            requireRender(outlineVisibilityCentizoom >= 0) {
                "outline visibility centizoom must be nonnegative"
            }
        }
        if (!control.keepDecoding()) return null
        val records = ArrayList<ReferenceDictionaryBinaryRecord>(
            if (outlineVisibilityCentizoom == null) count else min(count, 1_024),
        )
        repeat(count) { index ->
            if (index % RECORD_CANCELLATION_INTERVAL == 0 && !control.keepDecoding()) {
                return null
            }
            decodeRecord(reader, outlineVisibilityCentizoom, control)?.let(records::add)
            if (control.cancelled) return null
        }
        if (!control.keepDecoding()) return null
        reader.finish()
        return ReferenceDictionaryBinaryTile(coordinate, records)
    }

    private fun decodeRecord(
        reader: Reader,
        outlineVisibilityCentizoom: Int?,
        control: DecodeControl,
    ): ReferenceDictionaryBinaryRecord? {
        val featureId = reader.u64()
        val canonicalVariantId = reader.u64()
        val dedupeId = reader.u64()
        val worldWrap = reader.i32()
        val geometryKindCode = reader.u8()
        val geometryKind = ReferenceDictionaryBinaryGeometryKind.entries
            .firstOrNull { it.stableCode == geometryKindCode }
            ?: fail("render-ready geometry kind is unknown")
        val subtype = SemanticSubtype.from_stable_id(reader.u16())
            ?: fail("render-ready semantic subtype is unknown")
        val layerGroupCode = reader.u8()
        val layerGroup = ReferenceDictionaryBinaryLayerGroup.entries
            .firstOrNull { it.stableCode == layerGroupCode }
            ?: fail("render-ready layer group is unknown")
        val skipOutline = !subtype.is_label && outlineVisibilityCentizoom != null &&
            ReferencePresentationPolicy.outline_alpha_milli(
                ReferencePresentationPolicy.outline_visibility_rule(subtype),
                outlineVisibilityCentizoom,
            ) <= 0
        if (skipOutline) {
            requireRender(geometryKind != ReferenceDictionaryBinaryGeometryKind.POINT) {
                "render-ready outline geometry must be a path or polygon"
            }
            if (!skipGeometry(reader, geometryKind, control)) return null
            requireRender(!reader.boolean("sourced-text presence")) {
                "render-ready outlines forbid text"
            }
            return null
        }
        val geometry = decodeGeometry(reader, geometryKind, control) ?: return null
        val hasText = reader.boolean("sourced-text presence")
        requireRender(hasText == subtype.is_label) {
            "render-ready labels require text and outlines forbid it"
        }
        return if (hasText) {
            decodeLabel(
                reader,
                featureId,
                canonicalVariantId,
                dedupeId,
                subtype,
                layerGroup,
                geometry,
                worldWrap,
            )
        } else {
            decodeOutline(
                featureId,
                canonicalVariantId,
                dedupeId,
                subtype,
                layerGroup,
                geometry,
                worldWrap,
            )
        }
    }

    private fun decodeLabel(
        reader: Reader,
        featureId: ULong,
        canonicalVariantId: ULong,
        dedupeId: ULong,
        subtype: SemanticSubtype,
        layerGroup: ReferenceDictionaryBinaryLayerGroup,
        geometry: ReferenceDictionaryBinaryGeometry,
        worldWrap: Int,
    ): ReferenceDictionaryBinaryRecord {
        requireRender(geometry.kind != ReferenceDictionaryBinaryGeometryKind.POLYGON) {
            "render-ready label geometry must be a point or path"
        }
        requireRender(layerGroup == labelLayerGroup(subtype)) {
            "render-ready label layer group differs from its subtype"
        }
        val candidateId = reader.u64()
        val priority = reader.i32()
        val prominenceCode = reader.u8()
        val prominence = ProminenceTier.entries.firstOrNull {
            it.stable_code == prominenceCode
        } ?: fail("render-ready label prominence is unknown")
        val measureBucket = reader.u16()
        val canonicalLength = reader.u32Int("sourced-text length")
        requireRender(canonicalLength in 1..MAX_SOURCED_TEXT_BYTES) {
            "render-ready sourced text exceeds its bound"
        }
        val canonical = reader.take(canonicalLength)
        val canonicalSha256 = reader.take(SHA256_BYTES)
        val fullSha256 = reader.take(SHA256_BYTES)
        val sourcedText = SourcedMapTextBinaryCodec.decodePrevalidated(
            canonical,
            canonicalSha256,
            fullSha256,
        )
        val visibility = ReferencePresentationTables.visibility_rule(subtype, prominence)
        val placement = ReferenceDictionaryBinaryPlacement(
            labelCandidateId = candidateId,
            textSourceFieldId = sourcedText.primarySourceFieldId,
            prominenceTier = prominence,
            completeGeometryMeasureBucket = measureBucket,
            spacingPx = ReferencePresentationPolicy.line_label_repeat_spacing_px,
            maximumAngleDegrees =
                ReferencePresentationPolicy.max_line_label_bend_centi_degrees / 100,
            collisionGroup = ReferencePresentationPolicy.reference_label_collision_group.toULong(),
            semanticPriority = priority,
            avoidEdges = true,
            keepUpright = true,
            activeBandLimit = ReferencePresentationPolicy.label_active_band_limit,
        )
        return ReferenceDictionaryBinaryRecord(
            featureId = featureId,
            canonicalVariantId = canonicalVariantId,
            dedupeId = dedupeId,
            layerGroup = layerGroup,
            featureKind = ReferenceDictionaryBinaryFeatureKind.LABEL,
            subtype = subtype,
            geometry = geometry,
            sourcedText = sourcedText,
            placement = placement,
            minimumZoomCenti = visibility.min_zoom_centi,
            fullAlphaZoomCenti = visibility.full_alpha_zoom_centi,
            fadeOutZoomCenti = ReferencePresentationPolicy.label_fade_out_zoom_centi,
            maximumZoomCenti = ReferencePresentationPolicy.label_display_max_zoom_centi,
            drawOrder = 0,
            priority = priority,
            postingWorldWrap = worldWrap,
        )
    }

    private fun decodeOutline(
        featureId: ULong,
        canonicalVariantId: ULong,
        dedupeId: ULong,
        subtype: SemanticSubtype,
        layerGroup: ReferenceDictionaryBinaryLayerGroup,
        geometry: ReferenceDictionaryBinaryGeometry,
        worldWrap: Int,
    ): ReferenceDictionaryBinaryRecord {
        requireRender(geometry.kind != ReferenceDictionaryBinaryGeometryKind.POINT) {
            "render-ready outline geometry must be a path or polygon"
        }
        val featureKind = if (geometry.kind == ReferenceDictionaryBinaryGeometryKind.PATH) {
            ReferenceDictionaryBinaryFeatureKind.LINE
        } else {
            ReferenceDictionaryBinaryFeatureKind.POLYGON_OUTLINE
        }
        val visibility = ReferencePresentationPolicy.outline_visibility_rule(subtype)
        return ReferenceDictionaryBinaryRecord(
            featureId = featureId,
            canonicalVariantId = canonicalVariantId,
            dedupeId = dedupeId,
            layerGroup = layerGroup,
            featureKind = featureKind,
            subtype = subtype,
            geometry = geometry,
            sourcedText = null,
            placement = EMPTY_PLACEMENT,
            minimumZoomCenti = visibility.min_zoom_centi,
            fullAlphaZoomCenti = visibility.full_alpha_zoom_centi,
            fadeOutZoomCenti = visibility.fade_out_zoom_centi,
            maximumZoomCenti = visibility.max_zoom_centi,
            drawOrder = visibility.draw_order,
            priority = visibility.priority,
            postingWorldWrap = worldWrap,
        )
    }

    private fun decodeGeometry(
        reader: Reader,
        kind: ReferenceDictionaryBinaryGeometryKind,
        control: DecodeControl,
    ): ReferenceDictionaryBinaryGeometry? {
        val partCount = reader.u32Int("geometry part count")
        val pointCount = reader.u32Int("geometry point count")
        requireRender(partCount in 1..MAX_GEOMETRY_POINTS) {
            "render-ready geometry part count exceeds its bound"
        }
        requireRender(pointCount in 1..MAX_GEOMETRY_POINTS) {
            "render-ready geometry point count exceeds its bound"
        }
        requireRender(partCount <= pointCount && pointCount <= reader.remaining / 8) {
            "render-ready geometry counts exceed the remaining tile"
        }
        if (!control.keepDecoding()) return null
        val parts = IntArray(partCount)
        for (index in parts.indices) {
            if (index % PART_CANCELLATION_INTERVAL == 0 && !control.keepDecoding()) return null
            parts[index] = reader.u32Int("geometry part offset")
        }
        if (!control.keepDecoding()) return null
        val coordinates = FloatArray(pointCount * 2)
        for (index in coordinates.indices) {
            if (index % FLOAT_CANCELLATION_INTERVAL == 0 && !control.keepDecoding()) return null
            coordinates[index] = reader.f32().also { coordinate ->
                requireRender(coordinate.isFinite()) {
                    "render-ready geometry coordinate is non-finite"
                }
            }
        }
        if (!control.keepDecoding()) return null
        validatePartOffsets(kind, parts, pointCount)
        validatePolygonClosure(kind, parts, coordinates)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (index in coordinates.indices step 2) {
            if (index % FLOAT_CANCELLATION_INTERVAL == 0 && !control.keepDecoding()) return null
            minX = min(minX, coordinates[index])
            minY = min(minY, coordinates[index + 1])
            maxX = max(maxX, coordinates[index])
            maxY = max(maxY, coordinates[index + 1])
        }
        return ReferenceDictionaryBinaryGeometry(
            kind,
            parts,
            coordinates,
            floatArrayOf(minX, minY, maxX, maxY),
        )
    }

    private fun skipGeometry(
        reader: Reader,
        kind: ReferenceDictionaryBinaryGeometryKind,
        control: DecodeControl,
    ): Boolean {
        val partCount = reader.u32Int("geometry part count")
        val pointCount = reader.u32Int("geometry point count")
        requireRender(partCount in 1..MAX_GEOMETRY_POINTS) {
            "render-ready geometry part count exceeds its bound"
        }
        requireRender(pointCount in 1..MAX_GEOMETRY_POINTS) {
            "render-ready geometry point count exceeds its bound"
        }
        requireRender(partCount <= pointCount && pointCount <= reader.remaining / 8) {
            "render-ready geometry counts exceed the remaining tile"
        }
        if (!control.keepDecoding()) return false
        val parts = IntArray(partCount)
        for (index in parts.indices) {
            if (index % PART_CANCELLATION_INTERVAL == 0 && !control.keepDecoding()) return false
            parts[index] = reader.u32Int("geometry part offset")
        }
        validatePartOffsets(kind, parts, pointCount)
        val coordinateOffset = reader.position
        if (kind == ReferenceDictionaryBinaryGeometryKind.POLYGON) {
            parts.forEachIndexed { index, start ->
                if (index % PART_CANCELLATION_INTERVAL == 0 && !control.keepDecoding()) {
                    return false
                }
                val end = if (index + 1 < parts.size) parts[index + 1] else pointCount
                requireRender(reader.pointsMatch(coordinateOffset, start, end - 1)) {
                    "render-ready polygon ring is not exactly closed"
                }
            }
        }
        return reader.skipFiniteFloat32(pointCount * 2, control)
    }

    private fun validatePartOffsets(
        kind: ReferenceDictionaryBinaryGeometryKind,
        parts: IntArray,
        pointCount: Int,
    ) {
        requireRender(parts.first() == 0) { "render-ready geometry parts must start at zero" }
        parts.forEachIndexed { index, start ->
            requireRender(start in 0 until pointCount && (index == 0 || start > parts[index - 1])) {
                "render-ready geometry part offsets are not strictly increasing"
            }
            val end = if (index + 1 < parts.size) parts[index + 1] else pointCount
            val minimum = when (kind) {
                ReferenceDictionaryBinaryGeometryKind.POINT -> 1
                ReferenceDictionaryBinaryGeometryKind.PATH -> 2
                ReferenceDictionaryBinaryGeometryKind.POLYGON -> 4
            }
            requireRender(end - start >= minimum) {
                "render-ready geometry part has too few points"
            }
        }
    }

    private fun validatePolygonClosure(
        kind: ReferenceDictionaryBinaryGeometryKind,
        parts: IntArray,
        coordinates: FloatArray,
    ) {
        if (kind != ReferenceDictionaryBinaryGeometryKind.POLYGON) return
        val pointCount = coordinates.size / 2
        parts.forEachIndexed { index, start ->
            val end = if (index + 1 < parts.size) parts[index + 1] else pointCount
            requireRender(
                coordinates[start * 2].toRawBits() == coordinates[(end - 1) * 2].toRawBits() &&
                    coordinates[start * 2 + 1].toRawBits() ==
                    coordinates[(end - 1) * 2 + 1].toRawBits(),
            ) { "render-ready polygon ring is not exactly closed" }
        }
    }

    private fun labelLayerGroup(subtype: SemanticSubtype): ReferenceDictionaryBinaryLayerGroup =
        when (subtype) {
            SemanticSubtype.COUNTRY_TERRITORY,
            SemanticSubtype.FIRST_ORDER_REGION,
            SemanticSubtype.SECOND_LOCAL_REGION -> ReferenceDictionaryBinaryLayerGroup.REGIONS

            SemanticSubtype.CAPITAL_MAJOR_CITY,
            SemanticSubtype.CITY_TOWN,
            SemanticSubtype.LOCAL_PLACE,
            SemanticSubtype.ISLAND_ISLET -> ReferenceDictionaryBinaryLayerGroup.PLACES

            SemanticSubtype.OCEAN_SEA,
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR,
            SemanticSubtype.RIVER,
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.CANAL_CHANNEL,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE -> ReferenceDictionaryBinaryLayerGroup.WATER

            SemanticSubtype.PROTECTED_LAND -> ReferenceDictionaryBinaryLayerGroup.PUBLIC_LANDS
            else -> fail("render-ready label subtype is unsupported")
        }

    private fun validateCoordinate(coordinate: ReferenceDictionaryTileCoordinate) {
        requireRender(coordinate.z in 0..29) { "render-ready tile zoom is invalid" }
        val limit = 1 shl coordinate.z
        requireRender(coordinate.x in 0 until limit && coordinate.y in 0 until limit) {
            "render-ready tile coordinate is invalid"
        }
    }

    private fun requireRender(condition: Boolean, message: () -> String) {
        if (!condition) throw ReferenceDictionaryBinaryTileException(message())
    }

    private fun fail(message: String): Nothing =
        throw ReferenceDictionaryBinaryTileException(message)

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset
        val position: Int get() = offset

        fun take(length: Int): ByteArray {
            requireRender(length >= 0 && length <= remaining) {
                "render-ready reference bytes are truncated"
            }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun u8(): Int {
            requireRender(remaining > 0) { "render-ready reference bytes are truncated" }
            return bytes[offset++].toUByte().toInt()
        }

        fun boolean(label: String): Boolean = when (val value = u8()) {
            0 -> false
            1 -> true
            else -> fail("$label is not canonical: $value")
        }

        fun u16(): Int = u8() or (u8() shl 8)

        fun u32(): UInt {
            var value = 0u
            repeat(4) { index -> value = value or (u8().toUInt() shl (index * 8)) }
            return value
        }

        fun u32Int(label: String): Int {
            val value = u32()
            requireRender(value <= Int.MAX_VALUE.toUInt()) { "$label exceeds the signed runtime bound" }
            return value.toInt()
        }

        fun i32(): Int = u32().toInt()

        fun u64(): ULong {
            var value = 0uL
            repeat(8) { index -> value = value or (u8().toULong() shl (index * 8)) }
            return value
        }

        fun f32(): Float = Float.fromBits(i32())

        fun pointsMatch(coordinateOffset: Int, firstPoint: Int, secondPoint: Int): Boolean {
            val firstOffset = coordinateOffset + firstPoint * 8
            val secondOffset = coordinateOffset + secondPoint * 8
            requireRender(
                firstOffset >= offset && secondOffset >= offset &&
                    firstOffset + 8 <= bytes.size && secondOffset + 8 <= bytes.size,
            ) { "render-ready reference bytes are truncated" }
            for (index in 0 until 8) {
                if (bytes[firstOffset + index] != bytes[secondOffset + index]) return false
            }
            return true
        }

        fun skipFiniteFloat32(count: Int, control: DecodeControl): Boolean {
            requireRender(count >= 0 && count <= remaining / 4) {
                "render-ready reference bytes are truncated"
            }
            val end = offset + count * 4
            var skipped = 0
            while (offset < end) {
                if (skipped % FLOAT_CANCELLATION_INTERVAL == 0 && !control.keepDecoding()) {
                    return false
                }
                val bits = bytes[offset].toInt() and 0xff or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xff) shl 24)
                requireRender(bits and 0x7f80_0000 != 0x7f80_0000) {
                    "render-ready geometry coordinate is non-finite"
                }
                offset += 4
                skipped += 1
            }
            return true
        }

        fun finish() {
            requireRender(remaining == 0) { "render-ready tile has trailing bytes" }
        }
    }

    private class DecodeControl(
        private val requestIsRelevant: (() -> Boolean)?,
    ) {
        var cancelled = false
            private set

        fun keepDecoding(): Boolean {
            if (cancelled) return false
            if (requestIsRelevant?.invoke() != false) return true
            cancelled = true
            return false
        }
    }

    private val EMPTY_PLACEMENT = ReferenceDictionaryBinaryPlacement(
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
    )

    private const val SHA256_BYTES = 32
    private const val RECORD_CANCELLATION_INTERVAL = 64
    private const val PART_CANCELLATION_INTERVAL = 4_096
    private const val FLOAT_CANCELLATION_INTERVAL = 16_384
    private const val MAX_TILE_BYTES = 32 * 1024 * 1024
    private const val MAX_RECORDS_PER_TILE = 65_536
    private const val MAX_GEOMETRY_POINTS = 1_048_576
    private const val MAX_SOURCED_TEXT_BYTES = 8_300
}
