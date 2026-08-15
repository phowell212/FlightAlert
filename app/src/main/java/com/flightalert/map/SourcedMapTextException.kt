@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map


class SourcedMapTextException(
    message: String,
    val code: SourcedTextErrorCode? = null,
) : IllegalArgumentException(message)