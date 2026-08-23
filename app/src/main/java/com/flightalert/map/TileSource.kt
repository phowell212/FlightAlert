@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map

private const val LOCAL_REFERENCE_DICTIONARY_ATTRIBUTION =
    "Local whole-world OpenStreetMap-derived reference data, © OpenStreetMap contributors"


enum class TileSource(
    val base_cache_key: String,
    val display_name: String,
    private val base_attribution: String,
    val max_native_zoom: Int
) {
    // Explicit providers prevent synthetic backgrounds and keep attribution auditable.
    STREET("carto_voyager", "Street map", "CARTO Voyager tiles, OpenStreetMap data", 19),
    SATELLITE("esri_world_imagery", "Satellite", "Esri World Imagery", 19);

    fun cache_key(labels_enabled: Boolean): String {
        return when (this) {
            STREET -> if (labels_enabled) base_cache_key else "carto_voyager_nolabels"
            SATELLITE -> base_cache_key
        }
    }

    fun attribution_text(
        labels_enabled: Boolean,
        borders_enabled: Boolean = labels_enabled
    ): String {
        return when (this) {
            STREET -> if (labels_enabled) base_attribution else "CARTO no-label tiles, OpenStreetMap data"
            SATELLITE -> {
                buildList {
                    add(base_attribution)
                    if (labels_enabled || borders_enabled) {
                        add(LOCAL_REFERENCE_DICTIONARY_ATTRIBUTION)
                    }
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

    fun street_label_tile_url(z: Int, x: Int, y: Int): String {
        return "https://basemaps.cartocdn.com/rastertiles/voyager_only_labels/$z/$x/$y.png"
    }

}
