@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

import com.flightalert.map.policy_require
import java.util.Collections

enum class FilterKind(val stable_id: String) { LABEL("label"), OUTLINE("outline") }

enum class FilterId(val stable_id: String) {
    LABELS_REGIONS("labels.regions"), LABELS_PLACES("labels.places"),
    LABELS_ISLANDS("labels.islands"), LABELS_MAJOR_WATER("labels.major_water"),
    LABELS_RIVERS("labels.rivers"), LABELS_STREAMS("labels.streams"),
    LABELS_CANALS("labels.canals"), LABELS_PROTECTED_LANDS("labels.protected_lands"),
    OUTLINES_COASTLINES("outlines.coastlines"),
    OUTLINES_INTERNATIONAL("outlines.international"),
    OUTLINES_STATE_PROVINCE("outlines.state_province"),
    OUTLINES_COUNTY_LOCAL("outlines.county_local"),
    OUTLINES_PROTECTED_AREAS("outlines.protected_areas"),
    OUTLINES_WATER_BOUNDARIES("outlines.water_boundaries"),
    OUTLINES_OTHER("outlines.other");

    companion object {
        private val by_id = entries.associateBy { it.stable_id }
        fun from_stable_id(stable_id: String): FilterId? = by_id[stable_id]
    }
}

class FilterSpec(
    val filter_id: FilterId,
    val title: String,
    val kind: FilterKind,
    subtypes: List<SemanticSubtype>,
    val default_enabled: Boolean = true,
) {
    val subtypes: List<SemanticSubtype> = Collections.unmodifiableList(subtypes.toList())

    init {
        policy_require(title.isNotEmpty() && title.trim() == title) {
            "filter title must be nonempty and canonical"
        }
        policy_require(subtypes.isNotEmpty() && subtypes.toSet().size == subtypes.size) {
            "filter must own unique semantic subtypes"
        }
    }
}
