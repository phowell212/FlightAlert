@file:Suppress(
    "CanBeVal",
    "ClassName",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
)

package com.flightalert.sources

import android.os.SystemClock
import com.flightalert.AircraftFeedMode
import com.flightalert.FlightAlertAppSettings
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException


class VisibleAircraftFeedController(
    private val aircraft_traffic_feed: AircraftTrafficFeed,
    private val globe_source: GlobeBinCraftAircraftSource?,
    private val executor: Executor,
    private val post_to_main: (() -> Unit) -> Unit,
    private val post_delayed: (() -> Unit, Long) -> Unit,
    private val environment_source: VisibleAircraftFeedEnvironmentSource,
    private val on_state_changed: (VisibleAircraftFeedState) -> Unit,
    private val request_redraw: () -> Unit,
    private val apply_result: (
        FeedResult,
        Long,
        String,
        FeedPublicationPhase,
        VisibleAircraftRequest
    ) -> Unit,
    private val refresh_ms: Long,
    private val force_refresh_ms: Long,
    private val in_flight_retry_ms: Long,
    private val map_interaction_refresh_delay_ms: Long,
    private val hybrid_supplement_retry_ms: Long,
    private val globe_snapshot_refresh_ms: Long,
    private val api_grace_ms: Long
) {
    private var fetch_in_flight = false
    private var refresh_scheduled = false
    private var scheduled_refresh_force = false
    private var last_fetch_elapsed_ms = 0L
    private var last_globe_snapshot_publish_elapsed_ms = 0L
    private var fetch_token = 0L
    private var fetch_signature: String? = null
    private var active_fetch_token: Long? = null
    private var waiting_for_viewport = false
    private var interaction_globe_snapshot_in_flight = false
    private var pending_interaction_feed_mode: AircraftFeedMode? = null
    private var interaction_globe_snapshot_token: Long? = null
    private var interaction_globe_snapshot_signature: String? = null
    private var state: VisibleAircraftFeedState = VisibleAircraftFeedState.IDLE

    private fun publish_state(next: VisibleAircraftFeedState) {
        if (state == next) return
        state = next
        on_state_changed(next)
    }

    // One request may publish intermediate and final real feed results, but only the newest token can affect the map.
    fun request_if_needed(force: Boolean = false) {
        request_if_needed(force, environment_source.snapshot())
    }

    private fun request_if_needed(
        force: Boolean,
        environment_snapshot: VisibleAircraftFeedEnvironment,
    ) {
        val environment = when (environment_snapshot) {
            VisibleAircraftFeedEnvironment.NO_LOCATION -> {
                publish_state(VisibleAircraftFeedState.WAITING_FOR_LOCATION)
                return
            }
            VisibleAircraftFeedEnvironment.NO_VIEWPORT -> {
                waiting_for_viewport = true
                publish_state(VisibleAircraftFeedState.WAITING_FOR_VIEWPORT)
                return
            }
            is VisibleAircraftFeedEnvironment.READY -> environment_snapshot
        }
        val now = environment.now_elapsed_ms
        if (environment.interaction_deferred) {
            publish_state(VisibleAircraftFeedState.DEFERRED_FOR_INTERACTION)
            if (force) schedule_refresh(environment.interaction_delay_ms, force = true)
            return
        }
        if (fetch_in_flight) {
            publish_state(VisibleAircraftFeedState.FETCHING)
            schedule_refresh(in_flight_retry_ms, force)
            return
        }
        val min_fetch_interval_ms = if (force) force_refresh_ms else refresh_ms
        if (now - last_fetch_elapsed_ms < min_fetch_interval_ms) {
            publish_state(VisibleAircraftFeedState.IDLE)
            if (force) schedule_refresh(min_fetch_interval_ms - (now - last_fetch_elapsed_ms), true)
            return
        }

        fetch_in_flight = true
        publish_state(VisibleAircraftFeedState.FETCHING)
        last_fetch_elapsed_ms = now
        val request = environment_source.build_request()
        pending_interaction_feed_mode = null
        interaction_globe_snapshot_token = null
        interaction_globe_snapshot_signature = null
        val token = ++fetch_token
        active_fetch_token = token
        val signature = request_signature(request)
        fetch_signature = signature
        aircraft_traffic_feed.update_viewport(request.feed_mode)
        val publish_intermediate_results = !environment.feed_ready
        execute_in_background(on_rejected = {
            if (active_fetch_token == token) {
                active_fetch_token = null
                fetch_in_flight = false
                if (is_current_token(token)) {
                    publish_state(VisibleAircraftFeedState.WAITING_FOR_CAPACITY)
                }
                schedule_refresh(in_flight_retry_ms, force = true)
                request_redraw()
            }
        }) {
            try {
                aircraft_traffic_feed.fetch_aircraft(
                    feed_bounds = request.feed_bounds,
                    globe_display_bounds = request.globe_display_bounds,
                    safety_api_bounds = request.safety_api_bounds,
                    own_lat = request.own_lat,
                    own_lon = request.own_lon,
                    search_query = request.search_query,
                    selected_aircraft_key = request.selected_aircraft_key,
                    feed_mode = request.feed_mode,
                    on_intermediate_result = { result ->
                        if (publish_intermediate_results) {
                            apply_result(
                                result,
                                token,
                                signature,
                                FeedPublicationPhase.FEED_INTERMEDIATE,
                                request
                            )
                        }
                    }
                )?.let { result ->
                    apply_result(
                        result,
                        token,
                        signature,
                        FeedPublicationPhase.FINAL,
                        request
                    )
                }
            } catch (_: Exception) {
                if (is_current_token(token)) {
                    post_to_main {
                        if (is_current_token(token)) {
                            publish_state(VisibleAircraftFeedState.UNAVAILABLE)
                        }
                    }
                }
            } finally {
                post_to_main {
                    if (active_fetch_token == token) {
                        active_fetch_token = null
                        fetch_in_flight = false
                        if (state == VisibleAircraftFeedState.FETCHING) {
                            publish_state(VisibleAircraftFeedState.IDLE)
                        }
                        request_redraw()
                    }
                }
            }
        }
    }

    fun request_after_map_interaction() {
        if (!environment_source.feed_ready()) {
            request_if_needed(force = true)
        } else {
            schedule_refresh(map_interaction_refresh_delay_ms, force = true)
        }
    }

    fun request_interaction_globe_snapshot(feed_mode: AircraftFeedMode) {
        if (!feed_mode.uses_globe || globe_source == null) return
        pending_interaction_feed_mode = feed_mode
        publish_pending_interaction_globe_snapshot()
    }

    fun publish_globe_snapshot_update_if_useful(feed_mode: AircraftFeedMode) {
        val source = globe_source ?: return
        if (!feed_mode.uses_globe) return
        val environment = environment_source.snapshot() as? VisibleAircraftFeedEnvironment.READY
            ?: return
        val now = environment.now_elapsed_ms
        val startup_publish = !environment.feed_ready
        if (!startup_publish && environment.interaction_deferred) {
            request_interaction_globe_snapshot(feed_mode)
            return
        }
        if (!startup_publish && fetch_in_flight && now - last_fetch_elapsed_ms < api_grace_ms) return
        if (!startup_publish &&
            now - last_globe_snapshot_publish_elapsed_ms < globe_publish_interval_floor_ms()
        ) {
            return
        }
        val request = environment_source.build_request()
        val signature = request_signature(request)
        val publish_while_fetching = fetch_in_flight
        val token = if (publish_while_fetching && fetch_token > 0L) fetch_token else ++fetch_token
        val result_signature =
            if (publish_while_fetching) fetch_signature ?: signature else signature
        if (!publish_while_fetching) {
            pending_interaction_feed_mode = null
            interaction_globe_snapshot_token = null
            interaction_globe_snapshot_signature = null
            fetch_signature = signature
        }
        last_globe_snapshot_publish_elapsed_ms = now
        execute_in_background {
            val result = source.latest_snapshot(
                request.globe_display_bounds,
                request.safety_api_bounds,
                request.own_lat,
                request.own_lon,
                request.search_query,
                request.selected_aircraft_key
            ) ?: return@execute_in_background
            if (result.status == FeedStatus.OK) {
                if (publish_while_fetching) {
                    post_to_main {
                        if (fetch_in_flight && is_current_token(token)) {
                            apply_result(
                                result,
                                token,
                                result_signature,
                                FeedPublicationPhase.GLOBE_INTERMEDIATE,
                                request
                            )
                        }
                    }
                } else {
                    apply_result(
                        result,
                        token,
                        result_signature,
                        FeedPublicationPhase.GLOBE_INTERMEDIATE,
                        request
                    )
                }
            }
        }
    }

    private fun publish_pending_interaction_globe_snapshot() {
        if (interaction_globe_snapshot_in_flight) return
        val feed_mode = pending_interaction_feed_mode ?: return
        pending_interaction_feed_mode = null
        val source = globe_source ?: return
        if (!feed_mode.uses_globe) return
        if (environment_source.snapshot() !is VisibleAircraftFeedEnvironment.READY) return

        val request = environment_source.build_request()
        val signature = request_signature(request)
        val token = interaction_globe_snapshot_token
            ?: ++fetch_token
        val result_signature = interaction_globe_snapshot_signature
            ?: signature
        interaction_globe_snapshot_token = token
        interaction_globe_snapshot_signature = result_signature
        fetch_signature = result_signature
        aircraft_traffic_feed.update_viewport(request.feed_mode)
        last_globe_snapshot_publish_elapsed_ms = SystemClock.elapsedRealtime()
        interaction_globe_snapshot_in_flight = true
        execute_in_background(on_rejected = {
            post_to_main {
                interaction_globe_snapshot_in_flight = false
                publish_pending_interaction_globe_snapshot()
            }
        }) {
            val result = try {
                source.latest_snapshot(
                    request.globe_display_bounds,
                    request.safety_api_bounds,
                    request.own_lat,
                    request.own_lon,
                    request.search_query,
                    request.selected_aircraft_key
                )
            } catch (_: Exception) {
                null
            }
            post_to_main {
                if (result?.status == FeedStatus.OK && is_current_token(token)) {
                    apply_result(
                        result,
                        token,
                        result_signature,
                        FeedPublicationPhase.FINAL,
                        request
                    )
                }
                interaction_globe_snapshot_in_flight = false
                publish_pending_interaction_globe_snapshot()
            }
        }
    }

    private fun execute_in_background(
        on_rejected: () -> Unit = {},
        task: () -> Unit
    ) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            on_rejected()
        }
    }

    fun request_deferred_refresh() {
        if (!waiting_for_viewport) return
        val environment = environment_source.snapshot()
        if (environment !is VisibleAircraftFeedEnvironment.READY) return
        waiting_for_viewport = false
        request_if_needed(force = true, environment_snapshot = environment)
    }

    fun schedule_hybrid_supplement_refresh() {
        schedule_refresh(hybrid_supplement_retry_ms, force = true)
    }

    fun schedule_refresh(delay_ms: Long, force: Boolean) {
        scheduled_refresh_force = scheduled_refresh_force || force
        if (refresh_scheduled) return
        refresh_scheduled = true
        post_delayed({
            val should_force = scheduled_refresh_force
            refresh_scheduled = false
            scheduled_refresh_force = false
            request_if_needed(force = should_force)
        }, delay_ms.coerceAtLeast(0L))
    }

    fun is_current_fetch(token: Long, signature: String): Boolean {
        return fetch_token == token && fetch_signature == signature
    }

    fun is_current_token(token: Long): Boolean {
        return fetch_token == token
    }

    private fun request_signature(request: VisibleAircraftRequest): String {
        return listOf(
            request.feed_mode.name,
            request.search_query.orEmpty(),
            request.selected_aircraft_key.orEmpty(),
            "%.4f".format(Locale.US, request.feed_bounds.min_lat),
            "%.4f".format(Locale.US, request.feed_bounds.min_lon),
            "%.4f".format(Locale.US, request.feed_bounds.max_lat),
            "%.4f".format(Locale.US, request.feed_bounds.max_lon)
        ).joinToString("|")
    }

    private fun globe_publish_interval_floor_ms(): Long {
        return (globe_snapshot_refresh_ms - GLOBE_SNAPSHOT_CADENCE_JITTER_MS).coerceAtLeast(0L)
    }

    private companion object {
        const val GLOBE_SNAPSHOT_CADENCE_JITTER_MS =
            FlightAlertAppSettings.AircraftFeed.GLOBE_SNAPSHOT_CADENCE_JITTER_MS
    }
}
