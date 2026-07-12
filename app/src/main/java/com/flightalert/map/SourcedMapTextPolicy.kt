package com.flightalert.map

enum class SourcedTextScalarClass {
    NEUTRAL,
    STRONG_LATIN,
    STRONG_NON_LATIN,
    UNKNOWN,
}

private object SourcedTextDecisionSeal

internal class SourcedTextDecision internal constructor(
    val primaryText: String,
    val primarySourceFieldId: ULong,
    val englishText: String?,
    val englishSourceFieldId: ULong?,
    val layoutMode: SourcedTextLayoutMode,
    val englishGapReason: SourcedTextEnglishGapReason,
    val primaryScriptSignals: SourcedTextScriptSignals,
    val englishScriptSignals: SourcedTextScriptSignals?,
    seal: Any,
) {
    init {
        if (seal !== SourcedTextDecisionSeal) {
            throw SourcedMapTextException("sourced-text decision proof is invalid")
        }
    }
}

/** The one source-exact policy and factory for live sourced map text. */
object SourcedMapTextPolicy {
    const val unicodeScriptProfileSha256 = SourcedTextUnicode17Profile.profileSha256
    const val policySha256 =
        "ca7bd559b3d84758d17e11b5e619f04da7e5093d67d0f90506ab509e2ef6543a"
    const val bilingualPresentationToken =
        "flightalert.sourced-map-text.primary-with-english.v1"
    const val decisionPathId = "flightalert.sourced-text.source-exact-v2"
    const val maxSourcedTextUtf8Bytes = 4_096

    /**
     * Runs once at live-source ingestion. Renderers retain the returned immutable record and
     * must not classify its text again.
     */
    fun create(
        primary: Any?,
        primarySourceFieldId: Any?,
        declaredEnglish: Any? = null,
        englishSourceFieldId: Any? = null,
    ): SourcedMapText = SourcedMapTextBinaryCodec.encode(
        decide(primary, primarySourceFieldId, declaredEnglish, englishSourceFieldId),
    )

    private fun decide(
        primary: Any?,
        primarySourceFieldId: Any?,
        declaredEnglish: Any?,
        englishSourceFieldId: Any?,
    ): SourcedTextDecision {
        val englishIsStrictUtf8 = if (declaredEnglish is String) {
            requireBoundedText(declaredEnglish, SourcedTextErrorCode.ENGLISH_TOO_LONG)
        } else {
            null
        }
        val primaryAnalysis = analyzePrimary(primary)
        val primaryFieldId = requireFieldId(
            primarySourceFieldId,
            SourcedTextErrorCode.PRIMARY_FIELD_ID_INVALID,
            "primary source-field ID",
        )
        val englishFieldId = if (englishSourceFieldId == null) {
            null
        } else {
            requireFieldId(
                englishSourceFieldId,
                SourcedTextErrorCode.ENGLISH_FIELD_ID_INVALID,
                "English source-field ID",
            )
        }
        if (declaredEnglish != null && englishFieldId == null) {
            fail(
                SourcedTextErrorCode.ENGLISH_FIELD_ID_REQUIRED,
                "declared English requires a nonzero source-field ID",
            )
        }
        if (englishFieldId == primaryFieldId) {
            return single(
                primaryAnalysis,
                primaryFieldId,
                englishFieldId,
                SourcedTextEnglishGapReason.ENGLISH_FIELD_IS_PRIMARY,
            )
        }
        if (!primaryAnalysis.signals.hasStrongNonLatin) {
            return single(
                primaryAnalysis,
                primaryFieldId,
                englishFieldId,
                SourcedTextEnglishGapReason.PRIMARY_NOT_ELIGIBLE,
            )
        }
        if (declaredEnglish == null) {
            return single(
                primaryAnalysis,
                primaryFieldId,
                englishFieldId,
                SourcedTextEnglishGapReason.MISSING,
            )
        }
        if (declaredEnglish !is String) {
            return single(
                primaryAnalysis,
                primaryFieldId,
                englishFieldId,
                SourcedTextEnglishGapReason.WRONG_TYPE,
            )
        }
        if (englishIsStrictUtf8 != true) {
            return single(
                primaryAnalysis,
                primaryFieldId,
                englishFieldId,
                SourcedTextEnglishGapReason.INVALID_UTF8,
            )
        }
        val canonicalEnglish = endTrim(declaredEnglish)
        if (canonicalEnglish.isEmpty()) {
            return single(
                primaryAnalysis,
                primaryFieldId,
                englishFieldId,
                SourcedTextEnglishGapReason.BLANK,
            )
        }
        if (canonicalEnglish == primaryAnalysis.canonicalText) {
            return single(
                primaryAnalysis,
                primaryFieldId,
                englishFieldId,
                SourcedTextEnglishGapReason.IDENTICAL,
            )
        }
        val englishAnalysis = analyzeCanonical(canonicalEnglish)
        val englishGap = when {
            englishAnalysis.signals.hasUnknown -> SourcedTextEnglishGapReason.HAS_UNKNOWN
            englishAnalysis.signals.hasStrongNonLatin -> SourcedTextEnglishGapReason.HAS_STRONG_NON_LATIN
            !englishAnalysis.signals.hasStrongLatin -> SourcedTextEnglishGapReason.NO_STRONG_LATIN
            else -> null
        }
        if (englishGap != null) {
            return single(primaryAnalysis, primaryFieldId, englishFieldId, englishGap)
        }
        return SourcedTextDecision(
            primaryText = primaryAnalysis.canonicalText,
            primarySourceFieldId = primaryFieldId,
            englishText = englishAnalysis.canonicalText,
            englishSourceFieldId = englishFieldId,
            layoutMode = SourcedTextLayoutMode.PRIMARY_WITH_ENGLISH,
            englishGapReason = SourcedTextEnglishGapReason.NONE,
            primaryScriptSignals = primaryAnalysis.signals,
            englishScriptSignals = englishAnalysis.signals,
            seal = SourcedTextDecisionSeal,
        )
    }

    internal fun classifyScalar(scalar: Int): SourcedTextScalarClass =
        SourcedTextUnicode17Profile.classify(scalar)

    private fun single(
        primary: TextAnalysis,
        primaryFieldId: ULong,
        englishFieldId: ULong?,
        gap: SourcedTextEnglishGapReason,
    ): SourcedTextDecision = SourcedTextDecision(
        primaryText = primary.canonicalText,
        primarySourceFieldId = primaryFieldId,
        englishText = null,
        englishSourceFieldId = englishFieldId,
        layoutMode = SourcedTextLayoutMode.SINGLE,
        englishGapReason = gap,
        primaryScriptSignals = primary.signals,
        englishScriptSignals = null,
        seal = SourcedTextDecisionSeal,
    )

    private fun analyzePrimary(value: Any?): TextAnalysis {
        if (value !is String) {
            fail(SourcedTextErrorCode.PRIMARY_WRONG_TYPE, "primary sourced text must be text")
        }
        if (!requireBoundedText(value, SourcedTextErrorCode.PRIMARY_TOO_LONG)) {
            fail(
                SourcedTextErrorCode.PRIMARY_INVALID_UTF8,
                "primary sourced text is not strict UTF-8",
            )
        }
        val canonical = endTrim(value)
        if (canonical.isEmpty()) {
            fail(
                SourcedTextErrorCode.PRIMARY_BLANK,
                "primary sourced text is blank after end trimming",
            )
        }
        return analyzeCanonical(canonical)
    }

    private fun analyzeCanonical(value: String): TextAnalysis {
        var hasLatin = false
        var hasNonLatin = false
        var hasUnknown = false
        var offset = 0
        while (offset < value.length) {
            val first = value[offset]
            val scalar = if (first.isHighSurrogate()) {
                Character.toCodePoint(first, value[offset + 1])
            } else {
                first.code
            }
            when (classifyScalar(scalar)) {
                SourcedTextScalarClass.NEUTRAL -> Unit
                SourcedTextScalarClass.STRONG_LATIN -> hasLatin = true
                SourcedTextScalarClass.STRONG_NON_LATIN -> hasNonLatin = true
                SourcedTextScalarClass.UNKNOWN -> hasUnknown = true
            }
            offset += if (first.isHighSurrogate()) 2 else 1
        }
        return TextAnalysis(
            value,
            SourcedTextScriptSignals(hasLatin, hasNonLatin, hasUnknown),
        )
    }

    private fun requireBoundedText(value: String, tooLongCode: SourcedTextErrorCode): Boolean {
        if (value.codePointCount(0, value.length) > maxSourcedTextUtf8Bytes) {
            fail(tooLongCode, "sourced text exceeds $maxSourcedTextUtf8Bytes Unicode scalars")
        }
        val utf8Length = strictUtf8Length(value) ?: return false
        if (utf8Length > maxSourcedTextUtf8Bytes) {
            fail(tooLongCode, "sourced text exceeds $maxSourcedTextUtf8Bytes UTF-8 bytes")
        }
        return true
    }

    private fun strictUtf8Length(value: String): Int? {
        var length = 0
        var offset = 0
        while (offset < value.length) {
            val character = value[offset]
            when {
                character.isHighSurrogate() -> {
                    if (offset + 1 >= value.length || !value[offset + 1].isLowSurrogate()) return null
                    length += 4
                    offset += 2
                }
                character.isLowSurrogate() -> return null
                character.code <= 0x7f -> {
                    length += 1
                    offset += 1
                }
                character.code <= 0x7ff -> {
                    length += 2
                    offset += 1
                }
                else -> {
                    length += 3
                    offset += 1
                }
            }
        }
        return length
    }

    private fun endTrim(value: String): String {
        var end = value.length
        while (end > 0) {
            val scalar = value.codePointBefore(end)
            if (!isUnicode17WhiteSpace(scalar)) break
            end -= Character.charCount(scalar)
        }
        return if (end == value.length) value else value.substring(0, end)
    }

    private fun isUnicode17WhiteSpace(scalar: Int): Boolean = when (scalar) {
        in 0x0009..0x000d,
        0x0020,
        0x0085,
        0x00a0,
        0x1680,
        in 0x2000..0x200a,
        0x2028,
        0x2029,
        0x202f,
        0x205f,
        0x3000,
        -> true
        else -> false
    }

    private fun requireFieldId(value: Any?, code: SourcedTextErrorCode, label: String): ULong {
        if (value !is ULong || value == 0uL) fail(code, "$label must be a nonzero u64")
        return value
    }

    private fun fail(code: SourcedTextErrorCode, message: String): Nothing =
        throw SourcedMapTextException(message, code)

    private data class TextAnalysis(
        val canonicalText: String,
        val signals: SourcedTextScriptSignals,
    )
}
