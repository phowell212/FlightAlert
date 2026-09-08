package com.flightalert.map

/** Optional reference detail waits for a quiet map; camera input never waits for detail. */
internal class MapDetailDeferral {
    private var resume_at_ms = 0L

    fun resume(now_ms: Long) {
        resume_at_ms = now_ms + 1_000L
    }

    fun interact(now_ms: Long) {
        resume_at_ms = maxOf(resume_at_ms, now_ms + 500L)
    }

    fun deferred(now_ms: Long, touch_active: Boolean): Boolean =
        touch_active || now_ms < resume_at_ms

    fun delay_ms(now_ms: Long): Long = (resume_at_ms - now_ms).coerceAtLeast(0L)
}
