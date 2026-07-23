package com.flightalert.map

import kotlin.math.floor

internal object ReferenceDictionaryLodPolicy {
    const val LOW_ZOOM_OUTLINE_PROFILE = 400

    fun select(viewportZoom: Double, availableZooms: Set<Int>): Int? {
        if (availableZooms.isEmpty()) return null
        if (viewportZoom < LOW_ZOOM_SOURCE_LOD) {
            return LOW_ZOOM_SOURCE_LOD.takeIf { it in availableZooms }
        }
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

    fun select_fallback(
        viewportZoom: Double,
        targetZoom: Int,
        availableZooms: Set<Int>,
    ): Int? {
        if (viewportZoom < LOW_ZOOM_SOURCE_LOD && targetZoom == LOW_ZOOM_SOURCE_LOD) {
            return null
        }
        var fallback: Int? = null
        for (zoom in availableZooms) {
            if (zoom >= targetZoom || targetZoom - zoom > MAX_FALLBACK_DELTA) continue
            if (fallback == null || zoom < fallback) fallback = zoom
        }
        return fallback
    }

    fun can_reuse_scene(sceneZoom: Int, targetZoom: Int, fallbackZoom: Int?): Boolean {
        return sceneZoom in targetZoom..targetZoom + 1 ||
            (fallbackZoom != null && sceneZoom in fallbackZoom until targetZoom)
    }

    fun outline_decode_profile(viewport_zoom: Double, source_zoom: Int): Int? {
        return outline_decode_profile(
            ReferencePresentationPolicy.centizoom(viewport_zoom),
            source_zoom,
        )
    }

    fun outline_decode_profile(current_centizoom: Int, source_zoom: Int): Int? {
        require(current_centizoom >= 0) { "current centizoom must be nonnegative" }
        require(source_zoom in 0..29) { "source zoom must be inside [0, 29]" }
        val effective_centizoom = maxOf(current_centizoom, source_zoom * 100)
        return when {
            effective_centizoom <= 450 -> LOW_ZOOM_OUTLINE_PROFILE
            effective_centizoom <= 650 -> 500
            effective_centizoom <= 700 -> 700
            effective_centizoom <= 750 -> 750
            effective_centizoom <= 800 -> 800
            else -> null
        }
    }

    private const val PHONE_OVERZOOM_START = 8.0
    private const val PHONE_OVERZOOM = 1.5
    private const val MINIMUM_PHONE_OVERZOOM_LOD = 7
    private const val LOW_ZOOM_SOURCE_LOD = 4
    private const val MAX_FALLBACK_DELTA = 2
}
