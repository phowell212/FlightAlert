package com.flightalert.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class ReferenceDictionaryBinaryTileCodecTest {
    @Test
    fun pythonGoldenTileDecodesTypedRendererRecordAndSourcedText() {
        val coordinate = ReferenceDictionaryTileCoordinate(z = 1, x = 0, y = 0)

        val tile = ReferenceDictionaryBinaryTileCodec.decode(coordinate, cairoTileBytes)

        assertEquals(coordinate, tile.coordinate)
        assertEquals(1, tile.records.size)
        val record = tile.records.single()
        assertEquals(0x8877665544332211uL, record.featureId)
        assertEquals(0x461ecb3a537217dauL, record.canonicalVariantId)
        assertEquals(ReferenceDictionaryBinaryFeatureKind.LABEL, record.featureKind)
        assertEquals(SemanticSubtype.CITY_TOWN, record.subtype)
        assertEquals(ProminenceTier.LOCAL, record.placement.prominenceTier)
        assertEquals(201uL, record.placement.textSourceFieldId)
        assertEquals(650, record.minimumZoomCenti)
        assertEquals(690, record.fullAlphaZoomCenti)
        assertEquals(1, record.geometry.partOffsets.size)
        assertEquals(ReferenceDictionaryBinaryGeometryKind.POINT, record.geometry.kind)
        assertArrayEquals(floatArrayOf(2048f, 2048f), record.geometry.localCoordinates, 0.001f)
        val sourcedText = record.sourcedText!!
        assertEquals("\u0627\u0644\u0642\u0627\u0647\u0631\u0629", sourcedText.primaryText)
        assertEquals("Cairo", sourcedText.englishText)
        assertEquals(SourcedTextLayoutMode.PRIMARY_WITH_ENGLISH, sourcedText.layoutMode)
    }

    @Test
    fun coordinateAndCanonicalIdentityDriftFailClosed() {
        assertThrows(ReferenceDictionaryBinaryTileException::class.java) {
            ReferenceDictionaryBinaryTileCodec.decode(
                ReferenceDictionaryTileCoordinate(z = 1, x = 1, y = 0),
                cairoTileBytes,
            )
        }

        val changedVariant = cairoTileBytes.copyOf().apply {
            this[47] = (this[47].toInt() xor 1).toByte()
        }
        assertThrows(ReferenceDictionaryBinaryTileException::class.java) {
            ReferenceDictionaryBinaryTileCodec.decode(
                ReferenceDictionaryTileCoordinate(z = 1, x = 0, y = 0),
                changedVariant,
            )
        }
    }

    @Test
    fun decodedLabelUsesTheSharedTypedFilterStyleAndProminencePolicy() {
        val record = ReferenceDictionaryBinaryTileCodec.decode(
            ReferenceDictionaryTileCoordinate(z = 1, x = 0, y = 0),
            cairoTileBytes,
        ).records.single()

        val label = ReferenceDictionaryBinaryPresenter.present(record)
            as ReferenceDictionaryBinaryDrawModel.Label

        assertEquals(FilterId.LABELS_PLACES, label.filterId)
        assertEquals(StyleFamily.PLACE, label.style.family)
        assertEquals(650, label.visibility.minimumZoomCenti)
        assertEquals(690, label.visibility.fullAlphaZoomCenti)
        assertEquals(9_750, label.visibility.textSizeMilliSp)
        assertEquals("\u0627\u0644\u0642\u0627\u0647\u0631\u0629", label.text.primaryText)
        assertEquals("Cairo", label.text.englishText)
        assertEquals(false, label.lineLabel)
    }

    @Test
    fun canonicalNonLabelPlacementIsExactAndLabelsRemainStrict() {
        val coordinate = ReferenceDictionaryTileCoordinate(z = 1, x = 0, y = 0)

        val record = ReferenceDictionaryBinaryTileCodec.decode(
            coordinate,
            lineTileBytes,
        ).records.single()

        assertEquals(ReferenceDictionaryBinaryFeatureKind.LINE, record.featureKind)
        assertEquals(ReferenceDictionaryBinaryGeometryKind.PATH, record.geometry.kind)
        assertEquals(null, record.sourcedText)

        val forgedNonLabel = mutatePlacementAndRebindVariant(lineTileBytes) { bytes, start ->
            bytes[start + PLACEMENT_TEXT_SOURCE_FIELD_OFFSET] = 1
        }
        val nonLabelError = assertThrows(ReferenceDictionaryBinaryTileException::class.java) {
            ReferenceDictionaryBinaryTileCodec.decode(coordinate, forgedNonLabel)
        }
        assertEquals(
            "non-label records require the canonical non-applicable placement",
            nonLabelError.message,
        )

        val zeroishLabel = mutatePlacementAndRebindVariant(cairoTileBytes) { bytes, start ->
            repeat(Int.SIZE_BYTES) { offset ->
                bytes[start + PLACEMENT_SPACING_OFFSET + offset] = 0
            }
        }
        val labelError = assertThrows(ReferenceDictionaryBinaryTileException::class.java) {
            ReferenceDictionaryBinaryTileCodec.decode(coordinate, zeroishLabel)
        }
        assertEquals("label placement limits are invalid", labelError.message)
    }

    private val cairoTileBytes = hex(
        "4641453854494c453100010000000000000000010000000b0200004e385431081122334455667788" +
            "da1772533acb1e46000000000000000400000000e60100004e385431040807060504030201d6b39d" +
            "92164e54e7110000000000000003000000000000000101d20000000000000000000000010e000000" +
            "d8a7d984d982d8a7d987d8b1d8a94a0000004e385431020104000000000000000100000000000000" +
            "02000000010000000000000001000000000000000100000000000000010000000000000001000000" +
            "0000000001000000000000008a02000010270000b20200001027000014000000a208000030010000" +
            "4e38543120464b52b337f3ff8b8bfff337b3524b46eef2b65592474582a91f2f7886ba8fde5ea193" +
            "7c191278c21122334455667788999999999999999999999999999999999999999999999999e7544e" +
            "16929db3d6b4bcfb355501e7a227852c9c99c6795532b3a06cac68706601c9000000000000008877" +
            "665544332211d6b39d92164e54e70000000000000004010010000000000000000000000000000000" +
            "0000000000000000100000000000000010000000000000018a02000010270000e80300001e000000" +
            "0100000000000000a208000002010100000001000807060504030201555555555555555555555555" +
            "5555555555555555555555555555555555555555010104dce9bd4b789c0528318dbb184e17efe7465" +
            "f444550ba0180efd82e1cb7219154017b00000000000000000000000000730000003b2758eb2cc1" +
            "c17b02dff76a8a494155d057020e48bbc0f67ed119ea108f1cd735014df49aafa0b507ca5517277c" +
            "5f3db7faf855196a4b3a2124f4fae4e1f386fbebca7bd559b3d84758d17e11b5e619f04da7e5093d" +
            "67d0f90506ab509e2ef6543a020002c9000000000000000e000000d8a7d984d982d8a7d987d8b1d8" +
            "a901ca00000000000000010105000000436169726f",
    )

    private val lineTileBytes = hex(
        "4641453854494c45310001000000000000000001000000fd0100004e38543108efcdab8967452301cbb7993b526fbbd5" +
            "000000000000000400000000d80100004e385431048070605040302010816efaec32785bf81200000000000000040000" +
            "00000000000202300200000000000000000000005a0000004e3854310202040000000000000001000000000000000400" +
            "000000000000000000000000000000000000010000000000000001000000000000000000000000000000000000000000" +
            "0000010000000000000001000000000000006400000058020000c8000000f40100000a00000000000000240100004e38" +
            "543120000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000",
    )

    private fun mutatePlacementAndRebindVariant(
        tile: ByteArray,
        mutation: (ByteArray, Int) -> Unit,
    ): ByteArray {
        val changed = tile.copyOf()
        val rendererStart = TILE_HEADER_BYTES + Int.SIZE_BYTES
        val variantIdOffset = rendererStart + N8_HEADER_BYTES + ULong.SIZE_BYTES
        val variantLengthOffset =
            rendererStart + N8_HEADER_BYTES + ULong.SIZE_BYTES * 3 + Int.SIZE_BYTES
        val variantStart = variantLengthOffset + Int.SIZE_BYTES
        val variantLength = littleEndianInt(changed, variantLengthOffset)
        var cursor = variantStart + N8_HEADER_BYTES + ULong.SIZE_BYTES * 4 + 2 + Int.SIZE_BYTES
        repeat(2) {
            val count = littleEndianInt(changed, cursor)
            cursor += Int.SIZE_BYTES + count * ULong.SIZE_BYTES
        }
        val hasVisibleText = changed[cursor++].toInt()
        if (hasVisibleText == 1) {
            val textLength = littleEndianInt(changed, cursor)
            cursor += Int.SIZE_BYTES + textLength
        }
        val geometryLength = littleEndianInt(changed, cursor)
        cursor += Int.SIZE_BYTES + geometryLength + Int.SIZE_BYTES * 6
        val placementLength = littleEndianInt(changed, cursor)
        val placementStart = cursor + Int.SIZE_BYTES
        require(placementStart + placementLength <= variantStart + variantLength)
        mutation(changed, placementStart)

        val domain = "FAE8VAR1\u0000".toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(
            domain + changed.copyOfRange(variantStart, variantStart + variantLength),
        )
        repeat(ULong.SIZE_BYTES) { offset ->
            changed[variantIdOffset + offset] = digest[ULong.SIZE_BYTES - 1 - offset]
        }
        return changed
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        const val TILE_HEADER_BYTES = 10 + 1 + Int.SIZE_BYTES * 3
        const val N8_HEADER_BYTES = 5
        const val PLACEMENT_TEXT_SOURCE_FIELD_OFFSET = 110
        const val PLACEMENT_SPACING_OFFSET = 192
    }
}
