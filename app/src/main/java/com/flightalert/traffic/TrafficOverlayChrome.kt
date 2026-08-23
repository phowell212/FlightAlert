@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.traffic

import android.graphics.Bitmap
import com.flightalert.aircraft.Aircraft

interface TrafficOverlayChrome {
    // Keep drawing and bitmap retirement behind the host boundary.
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun aircraft_label_detail(aircraft: Aircraft): String
    fun request_animation_frame()
    fun retire_bitmap_after_frame_commit(bitmap: Bitmap)
}
