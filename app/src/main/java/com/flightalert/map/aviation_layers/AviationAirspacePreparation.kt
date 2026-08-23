@file:Suppress("PropertyName")

package com.flightalert.map.aviation_layers

import android.graphics.Path
import com.flightalert.map.SourcedMapText

internal data class PreparedAirspacePolygon(
    val point_count: Int,
    val path: Path,
    val min_x: Float,
    val max_x: Float,
    val min_y: Float,
    val max_y: Float
)

internal data class PreparedAirspaceFeature(
    val source: AviationAirspaceFeature,
    val map_text: SourcedMapText,
    val center: AviationLayerPoint,
    val point_count: Int,
    val min_unwrapped_lon: Double,
    val max_unwrapped_lon: Double,
    val polygons: List<PreparedAirspacePolygon>
)

internal data class AviationPreparedAirspaceLayer(
    val source: List<AviationAirspaceFeature>,
    val features: List<PreparedAirspaceFeature>
)

internal data class AviationAirspacePreparation(
    val restricted: AviationPreparedAirspaceLayer? = null,
    val atc: AviationPreparedAirspaceLayer? = null
)
