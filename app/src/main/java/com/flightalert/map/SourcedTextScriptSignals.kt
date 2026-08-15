@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map


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