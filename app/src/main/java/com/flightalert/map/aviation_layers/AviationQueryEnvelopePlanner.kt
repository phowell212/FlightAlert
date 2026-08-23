@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


    fun for_viewport(
        min_lat: Double,
        west_lon: Double,
        max_lat: Double,
        east_lon: Double
    ): List<AviationLayerBounds> {
        require(listOf(min_lat, west_lon, max_lat, east_lon).all(Double::isFinite)) {
            "Aviation query bounds must be finite"
        }
        require(min_lat in -90.0..90.0 && max_lat in -90.0..90.0 && min_lat <= max_lat) {
            "Aviation query latitude bounds are invalid"
        }
        require(west_lon in -180.0..180.0 && east_lon in -180.0..180.0) {
            "Aviation query longitude bounds are invalid"
        }
        return if (west_lon <= east_lon) {
            listOf(AviationLayerBounds(min_lat, west_lon, max_lat, east_lon))
        } else {
            listOf(
                AviationLayerBounds(min_lat, west_lon, max_lat, 180.0),
                AviationLayerBounds(min_lat, -180.0, max_lat, east_lon)
            )
        }
    }
