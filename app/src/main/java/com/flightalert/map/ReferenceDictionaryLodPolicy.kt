package com.flightalert.map

import kotlin.math.floor

internal object ReferenceDictionaryLodPolicy {
    fun select(viewportZoom: Double, availableZooms: Set<Int>): Int? {
        if (availableZooms.isEmpty()) return null
        val targetZoom = if (viewportZoom < PHONE_OVERZOOM_START) {
            floor(viewportZoom).toInt()
        } else {
            maxOf(MINIMUM_PHONE_OVERZOOM_LOD, floor(viewportZoom - PHONE_OVERZOOM).toInt())
        }
        val minimumZoom = availableZooms.minOrNull() ?: return null
        val maximumZoom = availableZooms.maxOrNull() ?: return null
        return targetZoom.coerceIn(minimumZoom, maximumZoom)
            .takeIf { it in availableZooms }
            ?: availableZooms.filter { it <= targetZoom }.maxOrNull()
            ?: minimumZoom
    }

    private const val PHONE_OVERZOOM_START = 8.0
    private const val PHONE_OVERZOOM = 1.5
    private const val MINIMUM_PHONE_OVERZOOM_LOD = 7
}
