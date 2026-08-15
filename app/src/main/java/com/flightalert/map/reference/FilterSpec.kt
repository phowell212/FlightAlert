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