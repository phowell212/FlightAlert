package com.flightalert.ui.map.photo

import android.graphics.Bitmap

sealed class AircraftPhotoResult {
    data class Found(
        val bitmap: Bitmap,
        val note: String,
        val evidence: PhotoEvidence? = null,
        val quality: PhotoQuality
    ) : AircraftPhotoResult()

    data class Unavailable(val reason: String) : AircraftPhotoResult()
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

data class SearchImageCandidate(
    val image_url: String,
    val page_url: String,
    val source_name: String,
    val title: String = "",
    val verification_text: String? = null
)

data class VerificationQuote(
    val text: String,
    val matched_terms: List<String>
)
