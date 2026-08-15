@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert

import android.content.Context
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.Manifest
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem



enum class AircraftFeedMode(
    val uses_globe: Boolean
) {
    HYBRID(true),
    BINCRAFT(true),
    API(false)
}