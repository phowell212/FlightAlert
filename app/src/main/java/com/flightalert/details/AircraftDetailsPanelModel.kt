@file:Suppress("PropertyName")

package com.flightalert.details

import android.graphics.Bitmap
import com.flightalert.VisualTheme


sealed interface AircraftDetailsPanelContent


data class AircraftDetailsRow(
    val label: String,
    val value: String,
    val section: Boolean = false
) {
    companion object {
        fun section(label: String, value: String = ""): AircraftDetailsRow =
            AircraftDetailsRow(label, value, section = true)
    }
}


data class AircraftDetailsMainState(
    val title: String,
    val rows: List<AircraftDetailsRow>,
    val has_aircraft: Boolean,
    val has_usage_trace: Boolean
) : AircraftDetailsPanelContent


data class AircraftImpactPanelState(
    val selected_aircraft_available: Boolean,
    val status: String,
    val show_trace_co2: Boolean,
    val co2_text: String,
    val score_label: String,
    val score_text: String,
    val score_color: Int,
    val rows: List<AircraftDetailsRow>
) : AircraftDetailsPanelContent


data class AircraftPhotoEvidencePanelState(
    val evidence: PhotoEvidence?
) : AircraftDetailsPanelContent


data class AircraftPhotoGalleryPanelState(
    val items: List<AircraftPhotoGalleryItem>,
    val status: String,
    val loading: Boolean
) : AircraftDetailsPanelContent


data class AircraftUsagePanelState(
    val selected_aircraft_available: Boolean,
    val status: String,
    val unavailable_message: String?,
    val stat_rows: List<AircraftDetailsRow>,
    val stats: UsageStats?
) : AircraftDetailsPanelContent


data class AircraftDetailsPhotoState(
    val bitmap: Bitmap?,
    val status: String,
    val previous_bitmap: Bitmap? = null,
    val transition_progress: Float = 1f
)


data class AircraftDetailsPanelState(
    val content: AircraftDetailsPanelContent,
    val photo: AircraftDetailsPhotoState,
    val scroll_y: Float,
    val wide_layout: Boolean
)


data class AircraftDetailsPanelStyle(val visual_theme: VisualTheme)
