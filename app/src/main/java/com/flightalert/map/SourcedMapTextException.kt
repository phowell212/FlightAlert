@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map

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
