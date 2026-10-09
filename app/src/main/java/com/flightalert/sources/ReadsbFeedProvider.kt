package com.flightalert.sources

import android.os.SystemClock

/** The public map inventory and REST API use the same readsb aircraft format. */
internal enum class ReadsbFeedProvider(
    val globe_base_url: String,
    val api_base_url: String,
    val globe_source: FeedSource,
    val api_source: FeedSource,
) {
    ADSB_LOL("https://adsb.lol", "https://api.adsb.lol/v2",
        FeedSource.ADSB_LOL_GLOBE, FeedSource.ADSB_LOL),
    AIRPLANES_LIVE(AirplanesLiveHttp.GLOBE_BASE_URL, AirplanesLiveHttp.API_BASE_URL,
        FeedSource.AIRPLANES_LIVE_GLOBE, FeedSource.AIRPLANES_LIVE);

    private val api_lock = Any()
    private var next_api_request_ms = 0L
    private var api_retry_after_ms = 0L
    private var api_failure: FeedResult? = null

    fun await_api_slot(): FeedResult? {
        while (true) {
            val wait_ms = synchronized(api_lock) {
                val now = SystemClock.elapsedRealtime()
                if (now < api_retry_after_ms) return api_failure
                val remaining = next_api_request_ms - now
                if (remaining <= 0L) {
                    next_api_request_ms = now + 1100L
                    return null
                }
                remaining
            }
            // A response can install a cooldown while another caller waits for its slot.
            Thread.sleep(wait_ms)
        }
    }

    fun back_off_api(failure: FeedResult, delay_ms: Long) = synchronized(api_lock) {
        val retry_after_ms = SystemClock.elapsedRealtime() + delay_ms
        if (retry_after_ms >= api_retry_after_ms) {
            api_failure = failure
            api_retry_after_ms = retry_after_ms
        }
    }
}

internal fun aircraft_feed_http_failure(
    source: FeedSource,
    code: Int,
    cf_mitigated: String?,
): FeedResult = FeedResult(
    status = if (code == 429) FeedStatus.RATE_LIMITED else FeedStatus.UNAVAILABLE,
    source = source,
    http_code = code,
    failure_reason = when {
        cf_mitigated.equals("challenge", ignoreCase = true) -> "Cloudflare challenge"
        code == 401 || code == 403 -> "Access denied"
        code == 429 -> "Rate limited"
        else -> "HTTP $code"
    },
)

internal fun aircraft_feed_retry_delay_ms(failure: FeedResult, retry_after: String?): Long {
    val minimum = when {
        failure.http_code == 401 || failure.http_code == 403 ||
            failure.failure_reason == "Cloudflare challenge" -> 60_000L
        failure.status == FeedStatus.RATE_LIMITED -> 15_000L
        else -> 2000L
    }
    val requested = retry_after?.toLongOrNull()?.coerceIn(0L, 86400L)?.times(1000L) ?: 0L
    return maxOf(minimum, requested)
}
