@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.sources

import org.json.JSONArray
import org.json.JSONObject


internal fun JSONArray.json_number_or_null(index: Int): Double? {
    if (index >= length() || isNull(index)) return null
    return optDouble(index)
}

internal fun JSONObject.json_number_or_null(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

internal fun JSONArray.json_int_or_null(index: Int): Int? {
    if (index >= length() || isNull(index)) return null
    return optInt(index)
}
