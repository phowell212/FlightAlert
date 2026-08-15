@file:Suppress(
    "ConstPropertyName",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

enum class ProminenceEvidenceKind(val stable_id: Int) {
    PROVIDER_RANK(1), CAPITAL_LEVEL(2), POPULATION(3), COMPLETE_AREA_M2(4),
    COMPLETE_RELATION_LENGTH_M(5), TYPED_SUBTYPE_DEFAULT(6)
}