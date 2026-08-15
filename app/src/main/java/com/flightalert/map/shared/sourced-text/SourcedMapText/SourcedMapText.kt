@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map


class SourcedMapText internal constructor(
    val primaryText: String,
    val primarySourceFieldId: ULong,
    val englishText: String?,
    private val layoutMode: SourcedTextLayoutMode,
    private val primaryScriptSignals: SourcedTextScriptSignals,
) {
    val isAtomicBilingual: Boolean
        get() = layoutMode == SourcedTextLayoutMode.PRIMARY_WITH_ENGLISH && englishText != null
    internal val primaryPresentationLines: List<String> =
        if (isAtomicBilingual && primaryScriptSignals.hasStrongLatin) {
            splitMixedScriptPrimary(primaryText)
        } else {
            listOf(primaryText)
        }
}