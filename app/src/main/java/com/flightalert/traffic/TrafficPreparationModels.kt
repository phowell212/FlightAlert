@file:Suppress("PropertyName")

package com.flightalert.traffic

import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftAppearance
import com.flightalert.alerts.PrioritySnapshotPayload
import com.flightalert.flight.TraceSegment
import com.flightalert.sources.FeedBounds
import com.flightalert.sources.FeedPublicationPhase
import com.flightalert.sources.FeedResult
import com.flightalert.sources.VisibleAircraftRequest

internal sealed interface AircraftPrepareContent {
    data class Feed(
        val result: FeedResult,
        val fetch_token: Long,
        val fetch_signature: String,
        val publication_phase: FeedPublicationPhase,
        val feed_request: VisibleAircraftRequest,
        val retain_previous_aircraft: Boolean,
        val retained_feed_coverages: List<FeedBounds>,
        val previous_aircraft: List<Aircraft>,
        val previous_metadata_by_key: Map<String, Aircraft>,
        val previous_appearances: Map<String, AircraftAppearance>,
        val previous_appearance_animation_until_ms: Long,
        val reuses_previous_aircraft_generation: Boolean
    ) : AircraftPrepareContent

    data class Committed(
        val source_result: FeedResult?,
        val feed_request: VisibleAircraftRequest?,
        val aircraft: List<Aircraft>,
        val aircraft_by_icao: Map<String, Aircraft>,
        val metadata_by_key: Map<String, Aircraft>,
        val appearances: Map<String, AircraftAppearance>,
        val appearance_animation_until_ms: Long
    ) : AircraftPrepareContent
}

internal data class AircraftPrepareRequest(
    val generation: Long,
    val content: AircraftPrepareContent,
    val publish_result: FeedResult?,
    val fetch_token: Long?,
    val fetch_signature: String?,
    val publication_phase: FeedPublicationPhase?,
    val now_epoch_sec: Double,
    val filters: AircraftFilterState,
    val own_lat: Double?,
    val own_lon: Double?,
    val own_altitude_feet: Double?,
    val required_safety_bounds: FeedBounds?,
    val alerts_enabled: Boolean,
    val alert_distance_feet: Float,
    val alert_altitude_feet: Float,
    val priority_tracking_enabled: Boolean,
    val priority_range_feet: Float,
    val theme: VisualTheme,
    val theme_key: Int,
    val selected_path: SelectedPathPrepareState?,
    val reusable_visual_cache: CachedTraffic?
)

internal data class PreparedAircraftFrame(
    val request: AircraftPrepareRequest,
    val aircraft: List<Aircraft>,
    val aircraft_by_icao: Map<String, Aircraft>,
    val metadata_by_key: Map<String, Aircraft>,
    val appearances: Map<String, AircraftAppearance>,
    val appearance_animation_until_ms: Long,
    val cached_traffic: CachedTraffic,
    val priority_payload: PrioritySnapshotPayload,
    val coverage: String
)

internal data class SelectedPathPrepareState(
    val selected_key: String,
    val segments: List<TraceSegment>
)
