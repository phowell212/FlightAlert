@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate
import com.flightalert.map.reference.dictionary.DictionaryTileDrawRef
import com.flightalert.map.reference.dictionary.ReferenceFilterMask
import com.flightalert.map.reference.labels.ReferenceScreenRect


internal data class RetainedLabelPlanRequest(
    val token: ReferenceRetainedBitmapRequestToken<RetainedLabelPlanKey>,
    val staticKey: RetainedStaticLabelPlanKey,
    val tiles: List<DictionaryTileDrawRef>,
    val preferredLabels: List<DictionaryLabelCandidate>,
    val labelAvoidRects: List<ReferenceScreenRect>,
    val labelTextScale: Float,
    val placeLabelsEnabled: Boolean,
    val waterLabelsEnabled: Boolean,
    val regionLabelsEnabled: Boolean,
    val publicLandsEnabled: Boolean,
    val filterMask: ReferenceFilterMask,
)