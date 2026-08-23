@file:Suppress("PropertyName")

package com.flightalert.details

import android.graphics.Bitmap


enum class AircraftPhotoViewType {
    EXTERIOR,
    INTERIOR,
    COCKPIT,
    CABIN
}


enum class PhotoQuality(val rank: Int) {
    INVESTIGABLE(1),
    REPRESENTATIVE(2),
    EXACT(3)
}


data class PhotoEvidence(
    val source_name: String,
    val image_url: String,
    val page_url: String,
    val search_query: String,
    val quote: String,
    val matched_terms: List<String>
)


data class AircraftPhotoGalleryItem(
    val bitmap: Bitmap,
    val title: String,
    val caption: String,
    val evidence: PhotoEvidence?,
    val quality: PhotoQuality,
    val view_type: AircraftPhotoViewType
)


sealed class AircraftPhotoResult {
    data class Found(
        val bitmap: Bitmap,
        val note: String,
        val evidence: PhotoEvidence? = null,
        val quality: PhotoQuality
    ) : AircraftPhotoResult()

    data class Unavailable(val reason: String) : AircraftPhotoResult()
}
