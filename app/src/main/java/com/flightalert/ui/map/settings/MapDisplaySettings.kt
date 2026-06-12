package com.flightalert.ui.map.settings

enum class TileSource(
    val baseCacheKey: String,
    val displayName: String,
    private val baseAttribution: String,
    val maxNativeZoom: Int
) {
    // Providers stay explicit so the map background remains auditable and never becomes fake imagery.
    STREET("osm", "Street map", "OpenStreetMap / CARTO tiles", 19),
    SATELLITE("esri_world_imagery", "Satellite", "Esri World Imagery", 19);

    fun cacheKey(labelsEnabled: Boolean): String {
        return when (this) {
            STREET -> if (labelsEnabled) baseCacheKey else "carto_voyager_nolabels"
            SATELLITE -> baseCacheKey
        }
    }

    fun attributionText(labelsEnabled: Boolean): String {
        return when (this) {
            STREET -> if (labelsEnabled) baseAttribution else "CARTO no-label tiles, OpenStreetMap data"
            SATELLITE -> baseAttribution
        }
    }

    fun tileUrl(z: Int, x: Int, y: Int, labelsEnabled: Boolean): String {
        return when (this) {
            STREET -> if (labelsEnabled) {
                "https://tile.openstreetmap.org/$z/$x/$y.png"
            } else {
                "https://basemaps.cartocdn.com/rastertiles/voyager_nolabels/$z/$x/$y.png"
            }
            SATELLITE -> "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
        }
    }
}

enum class UnitSystem(
    val distanceLabel: String,
    val altitudeLabel: String,
    val speedLabel: String
) {
    IMPERIAL("mi", "ft", "mph"),
    METRIC("km", "m", "km/h");

    fun distanceMetersToDisplay(meters: Double): Double {
        return if (this == IMPERIAL) meters / 1609.344 else meters / 1000.0
    }

    fun altitudeMetersToDisplay(meters: Double): Double {
        return if (this == IMPERIAL) meters * 3.28084 else meters
    }

    fun speedMetersPerSecondToDisplay(ms: Double): Double {
        return if (this == IMPERIAL) ms * 2.236936 else ms * 3.6
    }
}
