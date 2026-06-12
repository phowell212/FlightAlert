package com.flightalert.ui.map

enum class TileSource(
    val base_cache_key: String,
    val display_name: String,
    private val base_attribution: String,
    val max_native_zoom: Int
) {
    // Providers stay explicit so the map background remains auditable and never becomes fake imagery.
    STREET("osm", "Street map", "OpenStreetMap / CARTO tiles", 19),
    SATELLITE("esri_world_imagery", "Satellite", "Esri World Imagery", 19);

    fun cache_key(labels_enabled: Boolean): String {
        return when (this) {
            STREET -> if (labels_enabled) base_cache_key else "carto_voyager_nolabels"
            SATELLITE -> base_cache_key
        }
    }

    fun attribution_text(labels_enabled: Boolean): String {
        return when (this) {
            STREET -> if (labels_enabled) base_attribution else "CARTO no-label tiles, OpenStreetMap data"
            SATELLITE -> base_attribution
        }
    }

    fun tile_url(z: Int, x: Int, y: Int, labels_enabled: Boolean): String {
        return when (this) {
            STREET -> if (labels_enabled) {
                "https://tile.openstreetmap.org/$z/$x/$y.png"
            } else {
                "https://basemaps.cartocdn.com/rastertiles/voyager_nolabels/$z/$x/$y.png"
            }
            SATELLITE -> "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
        }
    }
}

enum class UnitSystem(
    val distance_label: String,
    val altitude_label: String,
    val speed_label: String
) {
    IMPERIAL("mi", "ft", "mph"),
    METRIC("km", "m", "km/h");

    fun distance_meters_to_display(meters: Double): Double {
        return if (this == IMPERIAL) meters / 1609.344 else meters / 1000.0
    }

    fun altitude_meters_to_display(meters: Double): Double {
        return if (this == IMPERIAL) meters * 3.28084 else meters
    }

    fun speed_meters_per_second_to_display(ms: Double): Double {
        return if (this == IMPERIAL) ms * 2.236936 else ms * 3.6
    }
}
