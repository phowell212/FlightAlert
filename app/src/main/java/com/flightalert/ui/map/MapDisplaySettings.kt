package com.flightalert.ui.map

enum class ReferenceTileOverlay(
    val cache_key: String,
    val attribution: String,
    private val service_path: String
) {
    WORLD_TRANSPORTATION(
        cache_key = "esri_world_transportation",
        attribution = "Esri World Transportation",
        service_path = "Reference/World_Transportation"
    ),
    WORLD_BOUNDARIES_AND_PLACES(
        cache_key = "esri_world_boundaries_places",
        attribution = "Esri World Boundaries and Places, HERE, Garmin, OpenStreetMap contributors, GIS user community",
        service_path = "Reference/World_Boundaries_and_Places"
    );

    fun tile_url(z: Int, x: Int, y: Int): String {
        return "https://services.arcgisonline.com/ArcGIS/rest/services/$service_path/MapServer/tile/$z/$y/$x"
    }
}

enum class TileSource(
    val base_cache_key: String,
    val display_name: String,
    private val base_attribution: String,
    val max_native_zoom: Int
) {
    // Providers stay explicit so the map background remains auditable and never becomes fake imagery.
    STREET("carto_voyager", "Street map", "CARTO Voyager tiles, OpenStreetMap data", 19),
    SATELLITE("esri_world_imagery", "Satellite", "Esri World Imagery", 19);

    fun cache_key(labels_enabled: Boolean): String {
        return when (this) {
            STREET -> if (labels_enabled) base_cache_key else "carto_voyager_nolabels"
            SATELLITE -> base_cache_key
        }
    }

    fun reference_overlay_layers(street_labels_enabled: Boolean, borders_enabled: Boolean): List<ReferenceTileOverlay> {
        return when (this) {
            STREET -> emptyList()
            SATELLITE -> buildList {
                if (street_labels_enabled) add(ReferenceTileOverlay.WORLD_TRANSPORTATION)
            }
        }
    }

    fun attribution_text(labels_enabled: Boolean, borders_enabled: Boolean = labels_enabled): String {
        return when (this) {
            STREET -> if (labels_enabled) base_attribution else "CARTO no-label tiles, OpenStreetMap data"
            SATELLITE -> {
                val overlays = reference_overlay_layers(labels_enabled, borders_enabled)
                buildList {
                    add(base_attribution)
                    overlays.forEach { add(it.attribution) }
                    if (borders_enabled) add("Natural Earth public domain reference data")
                }.joinToString("; ")
            }
        }
    }

    fun tile_url(z: Int, x: Int, y: Int, labels_enabled: Boolean): String {
        return when (this) {
            STREET -> if (labels_enabled) {
                "https://basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y.png"
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
