package com.flightalert.map

internal data class ReferenceRetainedBitmapRequestToken<Key>(
    val id: Long,
    val key: Key,
)

internal enum class ReferenceRetainedBitmapCompletion {
    PUBLISH,
    DISCARD,
    INCOMPLETE,
}

internal class ReferenceRetainedBitmapCoordinator<Key, Value>(
    private val discardValue: (Value) -> Unit,
) {
    private var nextId = 0L
    private var current: ReferenceRetainedBitmapRequestToken<Key>? = null
    private var completed: Completed<Key, Value>? = null

    @Synchronized
    fun start(key: Key): ReferenceRetainedBitmapRequestToken<Key>? {
        if (current?.key == key) return null
        completed?.let { discardValue(it.value) }
        completed = null
        return ReferenceRetainedBitmapRequestToken(++nextId, key).also {
            current = it
        }
    }

    @Synchronized
    fun shouldStart(request: ReferenceRetainedBitmapRequestToken<Key>): Boolean {
        return current?.id == request.id
    }

    @Synchronized
    fun complete(
        request: ReferenceRetainedBitmapRequestToken<Key>,
        value: Value,
        complete: Boolean,
    ): ReferenceRetainedBitmapCompletion {
        if (current?.id != request.id) {
            discardValue(value)
            return ReferenceRetainedBitmapCompletion.DISCARD
        }
        if (!complete) {
            current = null
            discardValue(value)
            return ReferenceRetainedBitmapCompletion.INCOMPLETE
        }
        completed?.let { discardValue(it.value) }
        completed = Completed(request, value)
        return ReferenceRetainedBitmapCompletion.PUBLISH
    }

    @Synchronized
    fun take(): Value? {
        val ready = completed ?: return null
        completed = null
        current = null
        return ready.value
    }

    @Synchronized
    fun hasCurrentRequest(): Boolean = current != null

    @Synchronized
    fun cancel() {
        current = null
        completed?.let { discardValue(it.value) }
        completed = null
    }

    private data class Completed<Key, Value>(
        val request: ReferenceRetainedBitmapRequestToken<Key>,
        val value: Value,
    )
}
