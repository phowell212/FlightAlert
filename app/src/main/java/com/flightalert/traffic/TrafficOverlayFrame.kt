@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import android.graphics.RectF
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftAppearance
import com.flightalert.aircraft.AircraftMarkerMorph
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.ensure_point_capacity
import com.flightalert.map.GeoPoint
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport
import com.flightalert.map.WorldPoint



internal data class TrafficOverlayFrame(
    val viewport: Viewport,
    val cache: CachedTraffic,
    val now_epoch_sec: Double,
    val selection: TrafficOverlaySelection,
    val filters_restrict_aircraft: Boolean,
    val map_source: TileSource,
    val visual_theme_key: Int,
    val content_width: Float,
    val content_height: Float,
    val label_avoid_rects: List<RectF>,
    val interaction: TrafficOverlayInteraction
)