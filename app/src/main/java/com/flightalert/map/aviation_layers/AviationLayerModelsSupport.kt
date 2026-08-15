@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.util.Collections
import kotlin.math.max
import kotlin.math.min


internal const val AVIATION_AIRPORT_LABEL_MIN_ZOOM = 8.4

fun List<AviationLayerPoint>.to_bounds(): AviationGeoBounds {
    if (isEmpty()) return AviationGeoBounds(0.0, 0.0, 0.0, 0.0)
    var min_lat = first().lat
    var max_lat = first().lat
    var min_lon = first().lon
    var max_lon = first().lon
    forEach { point ->
        min_lat = min(min_lat, point.lat)
        max_lat = max(max_lat, point.lat)
        min_lon = min(min_lon, point.lon)
        max_lon = max(max_lon, point.lon)
    }
    return AviationGeoBounds(min_lat, min_lon, max_lat, max_lon)
}

internal fun <T> immutable_list(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal fun <T> immutable_set(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

internal fun <T> immutable_nested_list(values: Collection<Collection<T>>): List<List<T>> =
    immutable_list(values.map(::immutable_list))

internal fun Collection<Collection<AviationLayerPoint>>.to_bounds_or_null(): AviationGeoBounds? {
    var first_point: AviationLayerPoint? = null
    var min_lat = 0.0
    var min_lon = 0.0
    var max_lat = 0.0
    var max_lon = 0.0
    for (segment in this) {
        for (point in segment) {
            if (first_point == null) {
                first_point = point
                min_lat = point.lat
                min_lon = point.lon
                max_lat = point.lat
                max_lon = point.lon
            } else {
                min_lat = minOf(min_lat, point.lat)
                min_lon = minOf(min_lon, point.lon)
                max_lat = maxOf(max_lat, point.lat)
                max_lon = maxOf(max_lon, point.lon)
            }
        }
    }
    if (first_point == null) return null
    return AviationGeoBounds(min_lat, min_lon, max_lat, max_lon)
}

