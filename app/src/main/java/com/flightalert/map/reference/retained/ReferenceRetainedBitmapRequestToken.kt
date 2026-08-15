package com.flightalert.map.reference.retained

internal data class ReferenceRetainedBitmapRequestToken<Key>(
    val id: Long,
    val key: Key,
)