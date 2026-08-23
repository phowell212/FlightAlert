@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import java.util.Locale


internal fun String.clean_seed_value(): String? {
    val cleaned = trim().trim('-').trim()
    return cleaned.takeIf {
        it.isNotBlank() &&
                !it.equals("null", ignoreCase = true) &&
                !it.equals("n/a", ignoreCase = true) &&
                !it.equals("unavailable", ignoreCase = true)
    }
}

internal fun normalized_photo_registration(value: String?): String? {
    return value
        ?.uppercase(Locale.US)
        ?.replace("PHOTOS", "")
        ?.replace(Regex("[^A-Z0-9-]"), "")
        ?.trim('-')
        ?.takeIf { it.isNotBlank() && it != "NA" }
}

internal fun plain_text_from_html(html: String): String {
    return html
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()
}
