@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map


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