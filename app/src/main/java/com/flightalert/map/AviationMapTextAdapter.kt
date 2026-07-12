package com.flightalert.map

/**
 * Exact provider fields used to construct an aviation map label.
 *
 * This is deliberately not a script, translation, shaping, or font policy.
 * The shared sourced-map-text runtime owns those decisions; aviation supplies
 * only source text and the fields that justify it.
 */
enum class AviationMapTextSourceField(
    val stable_source_field_id: ULong,
) {
    AIRSPACE_NAME(0x4641_4141_0001_0001uL),
    AIRSPACE_IDENT(0x4641_4141_0001_0002uL),
    AIRPORT_ICAO_ID(0x4641_4141_0002_0001uL),
    AIRPORT_IDENT(0x4641_4141_0002_0002uL),
    AIRPORT_NAME(0x4641_4141_0002_0003uL),
    NAT_DESIGNATOR(0x4641_414e_0001_0001uL),
}

internal class AviationMapTextSource(
    primary_text: String,
    source_fields: List<AviationMapTextSourceField>
) {
    val primary_text: String = primary_text
    val source_fields: List<AviationMapTextSourceField> = source_fields.toList().also {
        require(it.size == 1) { "aviation map text requires one exact source field" }
    }
    val sourced_text: SourcedMapText = SourcedMapTextPolicy.create(
        primary = this.primary_text,
        primarySourceFieldId = this.source_fields.single().stable_source_field_id,
    )
}

internal object AviationMapTextAdapter {
    fun airspace(feature: AviationAirspaceFeature): AviationMapTextSource {
        return AviationMapTextSource(
            feature.name,
            listOf(feature.map_text_source_field),
        )
    }

    fun airport(feature: AviationAirportFeature): AviationMapTextSource {
        val ident_source_field = feature.map_text_ident_source_field
        return if (ident_source_field != null) {
            AviationMapTextSource(
                feature.ident,
                listOf(ident_source_field),
            )
        } else {
            AviationMapTextSource(
                feature.name,
                listOf(AviationMapTextSourceField.AIRPORT_NAME),
            )
        }
    }

    fun oceanic_track(track: AviationOceanicTrack): AviationMapTextSource {
        return AviationMapTextSource(
            track.designator,
            listOf(AviationMapTextSourceField.NAT_DESIGNATOR),
        )
    }
}
