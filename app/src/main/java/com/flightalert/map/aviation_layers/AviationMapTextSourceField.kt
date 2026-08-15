@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


/** Provider fields justify aviation labels; shared map text handles script, translation, shaping, and font policy. */
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