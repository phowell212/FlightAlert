package com.flightalert.map.reference.labels

internal data class ReferenceLabelOccurrenceId(
    val candidateId: ULong,
    val repeatOrdinal: Long,
    val renderedWorldCopy: Long,
)