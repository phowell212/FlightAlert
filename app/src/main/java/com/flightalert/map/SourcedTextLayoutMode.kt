@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map


enum class SourcedTextLayoutMode(val stableCode: Int) {
    SINGLE(1),
    PRIMARY_WITH_ENGLISH(2),
}