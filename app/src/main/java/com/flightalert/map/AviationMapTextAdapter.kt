package com.flightalert.map

/**
 * Exact provider fields used to construct an aviation map label.
 *
 * This is deliberately not a script, translation, shaping, or font policy.
 * The shared sourced-map-text runtime owns those decisions; aviation supplies
 * only source text and the fields that justify it.
 */
internal enum class AviationMapTextSourceField {
    AIRSPACE_NAME,
    AIRSPACE_TYPE,
    AIRPORT_IDENT,
    AIRPORT_NAME,
    NAT_DESIGNATOR
}

internal class AviationMapTextSource(
    primary_text: String,
    source_fields: List<AviationMapTextSourceField>
) {
    val primary_text: String = primary_text.also {
        require(it.isNotBlank()) { "aviation map text must not be blank" }
    }
    val source_fields: List<AviationMapTextSourceField> = source_fields.toList().also {
        require(it.isNotEmpty()) { "aviation map text requires source-field provenance" }
        require(it.distinct().size == it.size) {
            "aviation map text source-field provenance must not contain duplicates"
        }
    }
}

internal object AviationMapTextAdapter {
    fun airspace(feature: AviationAirspaceFeature): AviationMapTextSource {
        val name = feature.name
        val type = feature.type.trim()
        if (type.isBlank() || type.equals(name, ignoreCase = true)) {
            return AviationMapTextSource(
                name,
                listOf(AviationMapTextSourceField.AIRSPACE_NAME)
            )
        }
        val type_already_in_name = Regex(
            "\\b${Regex.escape(type)}\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(name)
        return if (type_already_in_name) {
            AviationMapTextSource(
                name,
                listOf(AviationMapTextSourceField.AIRSPACE_NAME)
            )
        } else {
            AviationMapTextSource(
                "$name $type",
                listOf(
                    AviationMapTextSourceField.AIRSPACE_NAME,
                    AviationMapTextSourceField.AIRSPACE_TYPE
                )
            )
        }
    }

    fun airport(feature: AviationAirportFeature): AviationMapTextSource {
        return if (feature.ident.isNotBlank()) {
            AviationMapTextSource(
                feature.ident,
                listOf(AviationMapTextSourceField.AIRPORT_IDENT)
            )
        } else {
            AviationMapTextSource(
                feature.name,
                listOf(AviationMapTextSourceField.AIRPORT_NAME)
            )
        }
    }

    fun oceanic_track(track: AviationOceanicTrack): AviationMapTextSource {
        return AviationMapTextSource(
            track.name,
            listOf(AviationMapTextSourceField.NAT_DESIGNATOR)
        )
    }
}
