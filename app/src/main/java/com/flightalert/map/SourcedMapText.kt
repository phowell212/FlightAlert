package com.flightalert.map

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

enum class SourcedTextErrorCode {
    PRIMARY_WRONG_TYPE,
    PRIMARY_INVALID_UTF8,
    PRIMARY_BLANK,
    PRIMARY_TOO_LONG,
    PRIMARY_FIELD_ID_INVALID,
    ENGLISH_FIELD_ID_REQUIRED,
    ENGLISH_FIELD_ID_INVALID,
    ENGLISH_TOO_LONG,
}

class SourcedMapTextException(
    message: String,
    val code: SourcedTextErrorCode? = null,
) : IllegalArgumentException(message)

enum class SourcedTextLayoutMode(val stableCode: Int) {
    SINGLE(1),
    PRIMARY_WITH_ENGLISH(2),
}

enum class SourcedTextEnglishGapReason(val stableCode: Int) {
    NONE(0),
    PRIMARY_NOT_ELIGIBLE(1),
    ENGLISH_FIELD_IS_PRIMARY(2),
    MISSING(3),
    WRONG_TYPE(4),
    INVALID_UTF8(5),
    BLANK(6),
    IDENTICAL(7),
    HAS_UNKNOWN(8),
    HAS_STRONG_NON_LATIN(9),
    NO_STRONG_LATIN(10),
}

data class SourcedTextScriptSignals(
    val hasStrongLatin: Boolean,
    val hasStrongNonLatin: Boolean,
    val hasUnknown: Boolean,
) {
    internal val mask: Int
        get() = (if (hasStrongLatin) 1 else 0) or
            (if (hasStrongNonLatin) 2 else 0) or
            (if (hasUnknown) 4 else 0)

    companion object {
        internal fun fromMask(mask: Int): SourcedTextScriptSignals {
            if (mask !in 0..7) throw SourcedMapTextException("script signal mask is invalid")
            return SourcedTextScriptSignals(
                hasStrongLatin = mask and 1 != 0,
                hasStrongNonLatin = mask and 2 != 0,
                hasUnknown = mask and 4 != 0,
            )
        }
    }
}

class SourcedMapText internal constructor(
    val primaryText: String,
    val primarySourceFieldId: ULong,
    val englishText: String?,
    val englishSourceFieldId: ULong?,
    val layoutMode: SourcedTextLayoutMode,
    val englishGapReason: SourcedTextEnglishGapReason,
    val primaryScriptSignals: SourcedTextScriptSignals,
    val englishScriptSignals: SourcedTextScriptSignals?,
    canonicalBytes: ByteArray,
    canonicalSha256: ByteArray,
    fullSha256: ByteArray,
) {
    private val canonicalBytesValue = canonicalBytes.copyOf()
    private val canonicalSha256Value = canonicalSha256.copyOf()
    private val fullSha256Value = fullSha256.copyOf()

    val canonicalBytes: ByteArray get() = canonicalBytesValue.copyOf()
    val canonicalSha256: ByteArray get() = canonicalSha256Value.copyOf()
    val fullSha256: ByteArray get() = fullSha256Value.copyOf()
    val hotIdBytes: ByteArray get() = fullSha256Value.copyOfRange(0, 8)
    val hotId: ULong get() = first_u64_big_endian(fullSha256Value)
    val isAtomicBilingual: Boolean
        get() = layoutMode == SourcedTextLayoutMode.PRIMARY_WITH_ENGLISH && englishText != null
}

internal data class SourcedMapTextLinePlan(
    val text: String,
    val textSize: Float,
    val baselineOffset: Float,
    val forceItalic: Boolean,
)

internal data class SourcedMapTextPresentationPlan(
    val primary: SourcedMapTextLinePlan,
    val english: SourcedMapTextLinePlan?,
    val collisionHeightEm: Float,
    val collisionCenterOffset: Float,
)

/** One shared visual contract for sourced text wherever it appears on the map. */
internal object SourcedMapTextPresentation {
    const val forcedItalicSkewX = -0.25f

    fun plan(text: SourcedMapText, primaryTextSize: Float): SourcedMapTextPresentationPlan {
        require(primaryTextSize.isFinite() && primaryTextSize > 0f) {
            "sourced map text size must be positive and finite"
        }
        val english = text.englishText
        if (!text.isAtomicBilingual || english == null) {
            return SourcedMapTextPresentationPlan(
                primary = SourcedMapTextLinePlan(
                    text.primaryText,
                    primaryTextSize,
                    0f,
                    false,
                ),
                english = null,
                collisionHeightEm = SINGLE_COLLISION_HEIGHT_EM,
                collisionCenterOffset = 0f,
            )
        }
        return SourcedMapTextPresentationPlan(
            primary = SourcedMapTextLinePlan(
                text.primaryText,
                primaryTextSize,
                PRIMARY_BASELINE_OFFSET_EM * primaryTextSize,
                false,
            ),
            english = SourcedMapTextLinePlan(
                english,
                primaryTextSize * ENGLISH_TEXT_SCALE,
                ENGLISH_BASELINE_OFFSET_EM * primaryTextSize,
                true,
            ),
            collisionHeightEm = BILINGUAL_COLLISION_HEIGHT_EM,
            collisionCenterOffset = BILINGUAL_COLLISION_CENTER_OFFSET_EM * primaryTextSize,
        )
    }

    private const val ENGLISH_TEXT_SCALE = 0.76f
    private const val PRIMARY_BASELINE_OFFSET_EM = -0.38f
    private const val ENGLISH_BASELINE_OFFSET_EM = 0.56f
    private const val SINGLE_COLLISION_HEIGHT_EM = 1.45f
    private const val BILINGUAL_COLLISION_HEIGHT_EM = 2.2f
    private const val BILINGUAL_COLLISION_CENTER_OFFSET_EM =
        (PRIMARY_BASELINE_OFFSET_EM + ENGLISH_BASELINE_OFFSET_EM) / 2f
}

/**
 * Decodes the bake-owned canonical sourced-text record used by the binary
 * reference dictionary package. Data preparation performs the script/source decision
 * once, and the package hash chain plus this strict decoder binds its typed
 * result. Live-source text instead enters through [SourcedMapTextPolicy] once at
 * ingestion; neither path classifies during rendering.
 */
object SourcedMapTextBinaryCodec {
    const val unicodeScriptProfileSha256 =
        "4df49aafa0b507ca5517277c5f3db7faf855196a4b3a2124f4fae4e1f386fbeb"
    const val policySha256 =
        "ca7bd559b3d84758d17e11b5e619f04da7e5093d67d0f90506ab509e2ef6543a"
    const val bilingualPresentationToken =
        "flightalert.sourced-map-text.primary-with-english.v1"
    const val maxSourcedTextUtf8Bytes = 4_096

    private const val recordTag = 53
    private const val recordVersion = 1
    private const val maximumCanonicalRecordBytes = 8_300
    private val profileIdentity = decodeLowerHex(unicodeScriptProfileSha256)
    private val policyIdentity = decodeLowerHex(policySha256)
    private val identityDomain = "FAE8SOURCEDTEXT1\u0000".toByteArray(Charsets.US_ASCII)

    fun decode(canonicalBytes: ByteArray, expectedFullSha256: String): SourcedMapText {
        if (canonicalBytes.isEmpty() || canonicalBytes.size > maximumCanonicalRecordBytes) {
            throw SourcedMapTextException("sourced-text record byte length is outside its bound")
        }
        return decode(
            canonicalBytes = canonicalBytes,
            expectedDigest = decodeLowerHex(expectedFullSha256),
            identityDigest = MessageDigest.getInstance("SHA-256"),
        )
    }

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
        identityDigest.reset()
        identityDigest.update(canonicalBytes)
        val canonicalDigest = identityDigest.digest()
        return SourcedMapText(
            primaryText,
            primaryFieldId,
            englishText,
            englishFieldId,
            layout,
            gap,
            primarySignals,
            englishSignals,
            canonicalBytes,
            canonicalDigest,
            actualDigest,
        )
    }

    internal fun encode(decision: SourcedTextDecision): SourcedMapText {
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
        val writer = PolicyByteWriter()
            .u8(recordTag)
            .u8(recordVersion)
            .raw(profileIdentity)
            .raw(policyIdentity)
            .u8(layout.stableCode)
            .u8(gap.stableCode)
            .u8(primarySignals.mask)
            .u64(primaryFieldId)
        appendCanonicalText(writer, primaryText, "primary")
        writer.boolean(englishFieldId != null)
        englishFieldId?.let(writer::u64)
        writer.boolean(englishText != null)
        if (englishText != null) {
            writer.u8(englishSignals!!.mask)
            appendCanonicalText(writer, englishText, "English")
        }
        val canonical = writer.finish()
        val fullDigest = sha256_bytes(identityDomain + canonical)
        val canonicalDigest = sha256_bytes(canonical)
        return SourcedMapText(
            primaryText,
            primaryFieldId,
            englishText,
            englishFieldId,
            layout,
            gap,
            primarySignals,
            englishSignals,
            canonical,
            canonicalDigest,
            fullDigest,
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

    private fun appendCanonicalText(writer: PolicyByteWriter, value: String, label: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > maxSourcedTextUtf8Bytes) {
            throw SourcedMapTextException("$label sourced text exceeds its UTF-8 bound")
        }
        writer.u32(bytes.size.toUInt()).raw(bytes)
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
