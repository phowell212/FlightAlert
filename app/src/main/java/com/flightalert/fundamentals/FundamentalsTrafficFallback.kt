package com.flightalert.fundamentals

import android.os.SystemClock
import com.flightalert.sources.FeedBounds
import com.flightalert.sources.FeedResult
import com.flightalert.sources.FeedSource
import com.flightalert.sources.FeedStatus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Scope identity excludes ownship: location drift must not discard an otherwise current response. */
internal data class FundamentalsTrafficQuery(val centerLat: Double, val centerLon: Double, val radiusNm: Int) {
    companion object {
        fun fromBounds(bounds: FeedBounds, centerLat: Double, centerLon: Double): FundamentalsTrafficQuery? {
            if (!bounds.min_lat.isFinite() || !bounds.max_lat.isFinite() ||
                !bounds.min_lon.isFinite() || !bounds.max_lon.isFinite() ||
                bounds.min_lat !in -90.0..90.0 || bounds.max_lat !in bounds.min_lat..90.0 ||
                bounds.min_lon !in -180.0..180.0 || bounds.max_lon !in -180.0..180.0 ||
                !centerLat.isFinite() || centerLat !in -90.0..90.0 ||
                !centerLon.isFinite() || centerLon !in -180.0..180.0) return null
            val span = if (bounds.max_lon >= bounds.min_lon) bounds.max_lon - bounds.min_lon
                else 360.0 + bounds.max_lon - bounds.min_lon
            val center = Math.toRadians(centerLat)
            val oppositeOffset = ((centerLon + 180.0 - bounds.min_lon) % 360.0 + 360.0) % 360.0
            val edge = if (span >= 360.0 || oppositeOffset <= span) Math.PI else maxOf(
                acos(cos(Math.toRadians(bounds.min_lon - centerLon)).coerceIn(-1.0, 1.0)),
                acos(cos(Math.toRadians(bounds.max_lon - centerLon)).coerceIn(-1.0, 1.0)))
            // At wide longitude spans the farthest latitude can lie between the corners.
            val farthestLatitude = Math.toDegrees(atan2(-sin(center), -cos(center) * cos(edge)))
            val latitudes = listOf(bounds.min_lat, bounds.max_lat, farthestLatitude)
                .filter { it in bounds.min_lat..bounds.max_lat }
            val angle = latitudes.maxOf { latitude ->
                val point = Math.toRadians(latitude)
                acos((sin(center) * sin(point) + cos(center) * cos(point) * cos(edge)).coerceIn(-1.0, 1.0))
            }
            val radius = ceil(angle * 6_371_000.0 / 1852.0).toInt().coerceIn(1, 250)
            return FundamentalsTrafficQuery(centerLat, centerLon, radius)
        }
    }
}

/** One independent HTTP worker, with no queued replacements or changes to globe preparation. */
internal class FundamentalsTrafficFallback(
    private val fetch: (FundamentalsTrafficQuery, Double, Double) -> FeedResult,
    private val post: (() -> Unit) -> Unit,
    private val onChanged: () -> Unit,
    private val elapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val epochSec: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
) {
    internal data class Snapshot(val query: FundamentalsTrafficQuery, val result: FeedResult,
                                 val receivedElapsedMs: Long)

    private var active = false
    private var closed = false
    private var token = 0L
    private var desired: FundamentalsTrafficQuery? = null
    private var lastViewportQuery: FundamentalsTrafficQuery? = null
    private var recovering = false
    private var ownLat = Double.NaN
    private var ownLon = Double.NaN
    private var outageSinceMs: Long? = null
    private var lastStartMs: Long? = null
    private var inFlight = false
    private var cached: Snapshot? = null

    @Synchronized fun start() {
        if (closed || active) return
        active = true
        outageSinceMs = elapsedMs()
    }

    @Synchronized fun stop() {
        active = false
        invalidate()
        outageSinceMs = null
    }

    @Synchronized fun close() {
        stop()
        closed = true
        worker.shutdownNow()
    }

    @Synchronized fun setQuery(query: FundamentalsTrafficQuery?, ownLat: Double, ownLon: Double,
                               allowNearbyScope: Boolean = false): FundamentalsTrafficQuery? {
        if (lastViewportQuery != query) {
            val previous = desired
            val retainScope = allowNearbyScope && previous != null && query != null &&
                previous.radiusNm == query.radiusNm && nearby(previous, query)
            if (!retainScope && desired != query) {
                desired = query
                // Supersede pending HTTP, retaining already accepted real data at its original age/scope.
                if (recovering) token++
            }
            lastViewportQuery = query
        }
        this.ownLat = ownLat
        this.ownLon = ownLon
        return desired
    }

    @Synchronized fun worldAvailable() {
        if (recovering) invalidate()
        recovering = false
        outageSinceMs = null
    }

    @Synchronized fun isCurrent(snapshot: Snapshot): Boolean =
        active && !closed && snapshot === cached && fresh(snapshot)

    @Synchronized fun isUpdatingArea(snapshot: Snapshot): Boolean = snapshot.query != desired

    @Synchronized fun latestOrRequest(query: FundamentalsTrafficQuery?, confirmedFailure: Boolean): Snapshot? {
        if (!active || closed) return null
        if (query == null || query != desired) return cached?.takeIf { isCurrent(it) }
        recovering = true
        val now = elapsedMs()
        val outage = outageSinceMs ?: now.also { outageSinceMs = it }
        if ((confirmedFailure || now - outage >= 2000L) && !inFlight &&
            (lastStartMs == null || now - lastStartMs!! >= 5000L)) {
            val requestToken = token
            inFlight = true
            worker.execute { request(query, requestToken) }
        }
        return cached?.takeIf { isCurrent(it) }
    }

    private fun request(query: FundamentalsTrafficQuery, requestToken: Long) {
        val own = synchronized(this) {
            if (!active || closed || token != requestToken || desired != query) {
                inFlight = false
                return
            }
            lastStartMs = elapsedMs()
            ownLat to ownLon
        }
        val result = try { fetch(query, own.first, own.second) }
            catch (_: Exception) { FeedResult(FeedStatus.UNAVAILABLE, FeedSource.ADSB_LOL) }
        val received = elapsedMs()
        val publish = synchronized(this) {
            inFlight = false
            if (!active || closed || token != requestToken || desired != query) return@synchronized false
            if (result.status == FeedStatus.OK) {
                val snapshot = Snapshot(query, result.copy(source = FeedSource.ADSB_LOL,
                    partial_coverage = true, query_count = 1, content_revision = null,
                    globe_aircraft = null, globe_motion_candidates = null, globe_default_summary = null),
                    received)
                if (fresh(snapshot)) cached = snapshot
            }
            true
        }
        if (publish) post {
            synchronized(this) {
                if (active && !closed && token == requestToken && desired == query) onChanged()
            }
        }
    }

    private fun fresh(snapshot: Snapshot): Boolean {
        val payloadTime = snapshot.result.epoch_sec ?: return false
        return payloadTime.isFinite() && payloadTime > 0.0 &&
            elapsedMs() - snapshot.receivedElapsedMs in 0L..9000L &&
            epochSec() - payloadTime in 0.0..9.0
    }

    private fun invalidate() {
        token++
        cached = null
    }

    private fun nearby(first: FundamentalsTrafficQuery, second: FundamentalsTrafficQuery): Boolean {
        val a = Math.toRadians(first.centerLat)
        val b = Math.toRadians(second.centerLat)
        val longitude = Math.toRadians(first.centerLon - second.centerLon)
        val meters = acos((sin(a) * sin(b) + cos(a) * cos(b) * cos(longitude)).coerceIn(-1.0, 1.0)) * 6_371_000.0
        // Follow-location jitter retains the original, explicitly partial query; gestures never use this allowance.
        return meters <= minOf(100.0, first.radiusNm * 1852.0 * 0.05)
    }
}
