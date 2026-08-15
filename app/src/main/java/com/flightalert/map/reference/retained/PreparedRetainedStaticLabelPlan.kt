@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

import com.flightalert.map.reference.labels.PreparedLabelPlan


internal data class PreparedRetainedStaticLabelPlan(
    val key: RetainedStaticLabelPlanKey,
    val core: PreparedLabelPlan,
    val padding: PreparedLabelPlan,
)