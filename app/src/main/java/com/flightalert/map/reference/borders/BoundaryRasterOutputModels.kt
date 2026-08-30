@file:Suppress("PropertyName")

package com.flightalert.map.reference.borders

import android.graphics.Bitmap
import android.graphics.Path
import com.flightalert.map.reference.OutlineVisibilityRule
import com.flightalert.map.reference.dictionary.DictionaryLineStyle

internal class BoundaryPathBatch(
    val path: Path,
    var style: DictionaryLineStyle,
    var visibility_rule: OutlineVisibilityRule?,
    var draw_order: Int = 0,
    var priority: Int = 0,
    var first_source_feature_id: ULong = 0uL,
    var first_source_order: Int = 0,
)

internal data class BoundaryRasterFade(
    val previous: BoundaryRasterScene,
    val currentBand: ReferenceBoundaryRasterBand,
    val startMs: Long,
)

internal data class BoundaryRasterTile(
    val key: ReferenceBoundaryRasterKey,
    val bitmap: Bitmap?,
    val gutterPixels: Int,
    val byteCount: Long,
)
