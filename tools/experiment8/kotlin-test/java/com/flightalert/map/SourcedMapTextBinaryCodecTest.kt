package com.flightalert.map

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcedMapTextBinaryCodecTest {
    private val cairoCanonical = hex(
        "35014df49aafa0b507ca5517277c5f3db7faf855196a4b3a2124f4fae4e1f386fbeb" +
            "ca7bd559b3d84758d17e11b5e619f04da7e5093d67d0f90506ab509e2ef6543a" +
            "020002c9000000000000000e000000d8a7d984d982d8a7d987d8b1d8a901ca" +
            "00000000000000010105000000436169726f",
    )
    private val cairoFullSha =
        "3b2758eb2cc1c17b02dff76a8a494155d057020e48bbc0f67ed119ea108f1cd7"

    @Test
    fun goldenBakedRecordDecodesWithoutRuntimeScriptClassification() {
        val record = SourcedMapTextBinaryCodec.decode(cairoCanonical, cairoFullSha)
        assertEquals("القاهرة", record.primaryText)
        assertEquals(201uL, record.primarySourceFieldId)
        assertEquals("Cairo", record.englishText)
        assertEquals(202uL, record.englishSourceFieldId)
        assertEquals(SourcedTextLayoutMode.PRIMARY_WITH_ENGLISH, record.layoutMode)
        assertEquals(SourcedTextEnglishGapReason.NONE, record.englishGapReason)
        assertEquals(
            SourcedTextScriptSignals(false, true, false),
            record.primaryScriptSignals,
        )
        assertEquals(
            SourcedTextScriptSignals(true, false, false),
            record.englishScriptSignals,
        )
        assertTrue(record.isAtomicBilingual)
        assertEquals(cairoFullSha, record.fullSha256.hex())
        assertEquals("3b2758eb2cc1c17b", record.hotIdBytes.hex())
        assertArrayEquals(cairoCanonical, record.canonicalBytes)
    }

    @Test
    fun returnedIdentityBytesCannotMutateTheDecodedRecord() {
        val record = SourcedMapTextBinaryCodec.decode(cairoCanonical, cairoFullSha)
        val canonical = record.canonicalBytes
        val digest = record.fullSha256
        canonical.fill(0)
        digest.fill(0)
        assertArrayEquals(cairoCanonical, record.canonicalBytes)
        assertEquals(cairoFullSha, record.fullSha256.hex())
    }

    @Test
    fun wrongHashProfilePolicyOrVersionFailsClosed() {
        assertThrows(SourcedMapTextException::class.java) {
            SourcedMapTextBinaryCodec.decode(cairoCanonical, "0".repeat(64))
        }
        listOf(0, 2, 34).forEach { offset ->
            val changed = cairoCanonical.copyOf().apply { this[offset] = (this[offset] + 1).toByte() }
            assertThrows(SourcedMapTextException::class.java) {
                SourcedMapTextBinaryCodec.decode(changed, fullHash(changed))
            }
        }
    }

    @Test
    fun malformedUtf8OversizedBlobAndTrailingBytesFailClosed() {
        val malformed = cairoCanonical.copyOf().apply { this[81] = 0x80.toByte() }
        assertThrows(SourcedMapTextException::class.java) {
            SourcedMapTextBinaryCodec.decode(malformed, fullHash(malformed))
        }

        val oversized = cairoCanonical.copyOf().apply {
            this[77] = 0x01
            this[78] = 0x10
            this[79] = 0x00
            this[80] = 0x00
        }
        assertThrows(SourcedMapTextException::class.java) {
            SourcedMapTextBinaryCodec.decode(oversized, fullHash(oversized))
        }

        val trailing = cairoCanonical + byteArrayOf(0)
        assertThrows(SourcedMapTextException::class.java) {
            SourcedMapTextBinaryCodec.decode(trailing, fullHash(trailing))
        }
    }

    @Test
    fun structurallyInconsistentBilingualStateFailsEvenWithMatchingHash() {
        val singleWithEnglish = cairoCanonical.copyOf().apply { this[66] = 1 }
        assertThrows(SourcedMapTextException::class.java) {
            SourcedMapTextBinaryCodec.decode(singleWithEnglish, fullHash(singleWithEnglish))
        }

        val noNonLatinPrimary = cairoCanonical.copyOf().apply { this[68] = 1 }
        assertThrows(SourcedMapTextException::class.java) {
            SourcedMapTextBinaryCodec.decode(noNonLatinPrimary, fullHash(noNonLatinPrimary))
        }

        val unknownEnglish = cairoCanonical.copyOf().apply { this[105] = 5 }
        assertThrows(SourcedMapTextException::class.java) {
            SourcedMapTextBinaryCodec.decode(unknownEnglish, fullHash(unknownEnglish))
        }
    }

    @Test
    fun appRuntimeContainsOnlyDecoderIdentityNotASecondPolicyEngine() {
        assertEquals(
            "4df49aafa0b507ca5517277c5f3db7faf855196a4b3a2124f4fae4e1f386fbeb",
            SourcedMapTextBinaryCodec.unicodeScriptProfileSha256,
        )
        assertEquals(
            "ca7bd559b3d84758d17e11b5e619f04da7e5093d67d0f90506ab509e2ef6543a",
            SourcedMapTextBinaryCodec.policySha256,
        )
        assertFalse(
            SourcedMapTextBinaryCodec::class.java.declaredMethods.any {
                it.name.contains("classify", ignoreCase = true) ||
                    it.name.contains("transliter", ignoreCase = true) ||
                    it.name.contains("translate", ignoreCase = true)
            },
        )
    }

    @Test
    fun bilingualPresentationIsOneAtomicPrimaryPlusSmallerItalicEnglishPlan() {
        val record = SourcedMapTextBinaryCodec.decode(cairoCanonical, cairoFullSha)

        val plan = SourcedMapTextPresentation.plan(record, primaryTextSize = 20f)

        assertEquals("\u0627\u0644\u0642\u0627\u0647\u0631\u0629", plan.primary.text)
        assertEquals(20f, plan.primary.textSize, 0.001f)
        assertFalse(plan.primary.forceItalic)
        assertEquals("Cairo", plan.english!!.text)
        assertEquals(15.2f, plan.english!!.textSize, 0.001f)
        assertTrue(plan.english!!.forceItalic)
        assertTrue(plan.primary.baselineOffset < 0f)
        assertTrue(plan.english!!.baselineOffset > 0f)
        assertEquals(2.2f, plan.collisionHeightEm, 0.001f)
    }

    private fun fullHash(canonical: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest("FAE8SOURCEDTEXT1\u0000".toByteArray(Charsets.US_ASCII) + canonical)
        .hex()

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.hex(): String = joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }
}
