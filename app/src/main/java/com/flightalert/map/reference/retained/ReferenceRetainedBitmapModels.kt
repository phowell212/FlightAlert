package com.flightalert.map.reference.retained

internal enum class ReferenceRetainedBitmapCompletion {
    PUBLISH,
    DISCARD,
    INCOMPLETE,
}

internal data class ReferenceRetainedBitmapRequestToken<Key>(
    val id: Long,
    val key: Key,
)

internal enum class ReferenceRetainedCompletedTargetDisposition {
    PUBLISH,
    REMEMBER,
    DISCARD,
}
