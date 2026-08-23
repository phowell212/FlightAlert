@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest


object SourcedMapTextBinaryCodec {
    // Keep package text bound to the script and source fixed by the hash chain.
    // Route live text through the policy and never reclassify either path while drawing.
    const val unicodeScriptProfileSha256 =
        "4df49aafa0b507ca5517277c5f3db7faf855196a4b3a2124f4fae4e1f386fbeb"
    const val policySha256 =
        "ca7bd559b3d84758d17e11b5e619f04da7e5093d67d0f90506ab509e2ef6543a"
    const val maxSourcedTextUtf8Bytes = 4_096

    private const val recordTag = 53
    private const val recordVersion = 1
    private const val maximumCanonicalRecordBytes = 8_300
    private val profileIdentity = decodeLowerHex(unicodeScriptProfileSha256)
    private val policyIdentity = decodeLowerHex(policySha256)
    private val identityDomain = "FAE8SOURCEDTEXT1\u0000".toByteArray(Charsets.US_ASCII)

    internal fun decode(
        canonicalBytes: ByteArray,
        expectedDigest: ByteArray,
        identityDigest: MessageDigest,
    ): SourcedMapText {
        if (canonicalBytes.isEmpty() || canonicalBytes.size > maximumCanonicalRecordBytes) {
            throw SourcedMapTextException("sourced-text record byte length is outside its bound")
        }
        val actualDigest = sha256_bytes(identityDigest, identityDomain, canonicalBytes)
        if (!actualDigest.contentEquals(expectedDigest)) {
            throw SourcedMapTextException("sourced-text record full SHA-256 does not match")
        }

        return decodePrevalidated(canonicalBytes)
    }

    internal fun decodePrevalidated(canonicalBytes: ByteArray): SourcedMapText {
        if (canonicalBytes.isEmpty() || canonicalBytes.size > maximumCanonicalRecordBytes) {
            throw SourcedMapTextException("sourced-text record byte length is outside its bound")
        }

        val reader = PolicyByteReader(canonicalBytes)
        if (reader.u8() != recordTag || reader.u8() != recordVersion) {
            throw SourcedMapTextException("sourced-text record tag or version is unsupported")
        }
        if (!reader.take(32).contentEquals(profileIdentity)) {
            throw SourcedMapTextException("sourced-text Unicode profile identity is unavailable")
        }
        if (!reader.take(32).contentEquals(policyIdentity)) {
            throw SourcedMapTextException("sourced-text policy identity is unavailable")
        }
        val layoutCode = reader.u8()
        val layout = SourcedTextLayoutMode.entries.firstOrNull { it.stableCode == layoutCode }
            ?: throw SourcedMapTextException("sourced-text layout mode is unknown")
        val gapCode = reader.u8()
        val gap = SourcedTextEnglishGapReason.entries.firstOrNull { it.stableCode == gapCode }
            ?: throw SourcedMapTextException("sourced-text English gap reason is unknown")
        val primarySignals = SourcedTextScriptSignals.fromMask(reader.u8())
        val primaryFieldId = reader.u64().also(::requireNonzeroFieldId)
        val primaryText = readCanonicalText(reader, "primary")
        val englishFieldId = when (reader.u8()) {
            0 -> null
            1 -> reader.u64().also(::requireNonzeroFieldId)
            else -> throw SourcedMapTextException("English source-field presence flag is invalid")
        }
        val englishPresent = when (reader.u8()) {
            0 -> false
            1 -> true
            else -> throw SourcedMapTextException("English text presence flag is invalid")
        }
        val englishSignals: SourcedTextScriptSignals?
        val englishText: String?
        if (englishPresent) {
            englishSignals = SourcedTextScriptSignals.fromMask(reader.u8())
            englishText = readCanonicalText(reader, "English")
        } else {
            englishSignals = null
            englishText = null
        }
        try {
            reader.finish()
        } catch (_: ReferencePolicyException) {
            throw SourcedMapTextException("sourced-text record has trailing bytes")
        }
        validateStructure(
            primaryText,
            primaryFieldId,
            englishText,
            englishFieldId,
            layout,
            gap,
            primarySignals,
            englishSignals,
        )
        return SourcedMapText(
            primaryText,
            primaryFieldId,
            englishText,
            layout,
            primarySignals,
        )
    }

    internal fun fromDecision(decision: SourcedTextDecision): SourcedMapText {
        val primaryText = decision.primaryText
        val primaryFieldId = decision.primarySourceFieldId
        val englishText = decision.englishText
        val englishFieldId = decision.englishSourceFieldId
        val layout = decision.layoutMode
        val gap = decision.englishGapReason
        val primarySignals = decision.primaryScriptSignals
        val englishSignals = decision.englishScriptSignals
        validateStructure(
            primaryText,
            primaryFieldId,
            englishText,
            englishFieldId,
            layout,
            gap,
            primarySignals,
            englishSignals,
        )
        return SourcedMapText(
            primaryText,
            primaryFieldId,
            englishText,
            layout,
            primarySignals,
        )
    }

    private fun validateStructure(
        primaryText: String,
        primaryFieldId: ULong,
        englishText: String?,
        englishFieldId: ULong?,
        layout: SourcedTextLayoutMode,
        gap: SourcedTextEnglishGapReason,
        primarySignals: SourcedTextScriptSignals,
        englishSignals: SourcedTextScriptSignals?,
    ) {
        requireNonzeroFieldId(primaryFieldId)
        if (englishText != null && englishFieldId == null) {
            throw SourcedMapTextException("English text lacks exact source-field evidence")
        }
        if (englishFieldId == primaryFieldId && gap != SourcedTextEnglishGapReason.ENGLISH_FIELD_IS_PRIMARY) {
            throw SourcedMapTextException("English and primary source-field identity conflict")
        }
        when (layout) {
            SourcedTextLayoutMode.SINGLE -> {
                if (gap == SourcedTextEnglishGapReason.NONE || englishText != null || englishSignals != null) {
                    throw SourcedMapTextException("single-line sourced text retains bilingual state")
                }
                if (
                    gap == SourcedTextEnglishGapReason.PRIMARY_NOT_ELIGIBLE &&
                    primarySignals.hasStrongNonLatin
                ) {
                    throw SourcedMapTextException("eligible primary is mislabeled not eligible")
                }
                if (
                    gap == SourcedTextEnglishGapReason.MISSING &&
                    !primarySignals.hasStrongNonLatin
                ) {
                    throw SourcedMapTextException("ineligible primary is mislabeled missing English")
                }
            }

            SourcedTextLayoutMode.PRIMARY_WITH_ENGLISH -> {
                if (
                    gap != SourcedTextEnglishGapReason.NONE || englishText == null ||
                    englishFieldId == null || englishSignals == null ||
                    englishFieldId == primaryFieldId || englishText == primaryText ||
                    !primarySignals.hasStrongNonLatin || !englishSignals.hasStrongLatin ||
                    englishSignals.hasStrongNonLatin || englishSignals.hasUnknown
                ) {
                    throw SourcedMapTextException("bilingual sourced-text structure is inconsistent")
                }
            }
        }
    }

    private fun readCanonicalText(reader: PolicyByteReader, label: String): String {
        val length = reader.u32().toLong()
        if (length > maxSourcedTextUtf8Bytes.toLong() || length > reader.remaining.toLong()) {
            throw SourcedMapTextException("$label sourced text exceeds its UTF-8 bound")
        }
        val bytes = reader.take(length.toInt())
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw SourcedMapTextException("$label sourced text is not strict UTF-8")
        }
        if (text.isEmpty() || endsWithUnicode17WhiteSpace(text)) {
            throw SourcedMapTextException("$label sourced text is not canonically end-trimmed")
        }
        return text
    }

    private fun endsWithUnicode17WhiteSpace(value: String): Boolean {
        val scalar = value.codePointBefore(value.length)
        return when (scalar) {
            0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x0020, 0x0085, 0x00a0,
            0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
            0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f,
            0x3000,
            -> true
            else -> false
        }
    }

    private fun requireNonzeroFieldId(value: ULong) {
        if (value == 0uL) throw SourcedMapTextException("source-field ID must be nonzero")
    }

    private fun decodeLowerHex(value: String): ByteArray {
        if (value.length != 64 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw SourcedMapTextException("SHA-256 identity is not lowercase hexadecimal")
        }
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
