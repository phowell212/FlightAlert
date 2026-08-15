@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map

import java.lang.Character.UnicodeScript


internal data class SourcedTextScriptRun(
    val script: UnicodeScript?,
    val text: StringBuilder,
)