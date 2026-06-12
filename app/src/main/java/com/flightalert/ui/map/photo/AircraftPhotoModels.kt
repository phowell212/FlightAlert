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
    val sourceName: String,
    val imageUrl: String,
    val pageUrl: String,
    val searchQuery: String,
    val quote: String,
    val matchedTerms: List<String>
)

data class SearchImageCandidate(
    val imageUrl: String,
    val pageUrl: String,
    val sourceName: String,
    val title: String = "",
    val verificationText: String? = null
)

data class VerificationQuote(
    val text: String,
    val matchedTerms: List<String>
)
