@file:Suppress(
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map



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
        // Reject decisions that do not carry this exact seal.
        if (seal !== SourcedTextDecisionSeal) {
            throw SourcedMapTextException("sourced-text decision proof is invalid")
        }
    }
}
