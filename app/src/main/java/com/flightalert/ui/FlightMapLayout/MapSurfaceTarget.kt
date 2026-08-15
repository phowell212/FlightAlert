@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import com.flightalert.map.ScreenPoint



data class MapSurfaceTarget(
    val bounds: RectF,
    val action: MapSurfaceAction
)