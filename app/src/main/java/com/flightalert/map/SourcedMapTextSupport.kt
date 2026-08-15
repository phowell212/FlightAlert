@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map

import java.lang.Character.UnicodeScript


internal fun splitMixedScriptPrimary(text: String): List<String> {
    val runs = ArrayList<SourcedTextScriptRun>(3)
    var offset = 0
    while (offset < text.length) {
        while (offset < text.length) {
            val scalar = text.codePointAt(offset)
            if (!isScriptSeparator(scalar)) break
            offset += Character.charCount(scalar)
        }
        if (offset >= text.length) break
        val start = offset
        while (offset < text.length) {
            val scalar = text.codePointAt(offset)
            if (isScriptSeparator(scalar)) break
            offset += Character.charCount(scalar)
        }
        val word = text.substring(start, offset)
        val script = firstStrongScript(word)
        val previous = runs.lastOrNull()
        if (previous != null && (script == null || previous.script == script)) {
            previous.text.append(' ').append(word)
        } else {
            runs += SourcedTextScriptRun(script, StringBuilder(word))
        }
    }
    val native_lines = runs
        .filter { it.script != UnicodeScript.LATIN }
        .map { it.text.toString() }
    return native_lines.ifEmpty { listOf(text) }
}

private fun isScriptSeparator(scalar: Int): Boolean {
    return Character.isWhitespace(scalar) || Character.isSpaceChar(scalar) || scalar == ';'.code
}

private fun firstStrongScript(text: String): UnicodeScript? {
    var offset = 0
    while (offset < text.length) {
        val scalar = text.codePointAt(offset)
        val script = UnicodeScript.of(scalar)
        if (script != UnicodeScript.COMMON && script != UnicodeScript.INHERITED &&
            script != UnicodeScript.UNKNOWN
        ) {
            return script
        }
        offset += Character.charCount(scalar)
    }
    return null
}