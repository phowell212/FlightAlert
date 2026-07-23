package com.flightalert.map

internal class ReferenceDictionaryTileMemoryCache<T : Any>(
    normalLimit: Int,
    lowZoomLimit: Int,
) {
    private val normalTiles = limited_map(normalLimit)
    private val lowZoomTiles = limited_map(lowZoomLimit)

    fun get(key: String, lowZoom: Boolean): T? {
        return tiles(lowZoom)[key]
    }

    fun contains(key: String, lowZoom: Boolean): Boolean {
        return tiles(lowZoom).containsKey(key)
    }

    fun put(key: String, lowZoom: Boolean, value: T) {
        tiles(lowZoom)[key] = value
    }

    fun clear() {
        normalTiles.clear()
        lowZoomTiles.clear()
    }

    private fun tiles(lowZoom: Boolean): MutableMap<String, T> {
        return if (lowZoom) lowZoomTiles else normalTiles
    }

    private fun limited_map(limit: Int): LinkedHashMap<String, T> {
        require(limit > 0) { "dictionary tile cache limit must be positive" }
        return object : LinkedHashMap<String, T>(limit, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, T>,
            ): Boolean = size > limit
        }
    }
}
