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
import com.flightalert.map.reference.labels.PreparedLabelPlan
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

internal data class PreparedRetainedStaticLabelPlan(
    val key: RetainedStaticLabelPlanKey,
    val core: PreparedLabelPlan,
    val padding: PreparedLabelPlan,
)

internal data class PreparedRetainedLabelFrame(
    val request: RetainedLabelPlanRequest,
    val frame: RetainedReferenceFrame?,
)
