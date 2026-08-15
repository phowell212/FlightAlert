@file:Suppress(
    "FunctionName",
)

package com.flightalert.map.reference.retained


internal fun reference_retained_completed_target_disposition(
    environmentMatches: Boolean,
    matchesCurrentTarget: Boolean,
    interactionActive: Boolean,
): ReferenceRetainedCompletedTargetDisposition {
    if (!environmentMatches) return ReferenceRetainedCompletedTargetDisposition.DISCARD
    if (matchesCurrentTarget && !interactionActive) {
        return ReferenceRetainedCompletedTargetDisposition.PUBLISH
    }
    return ReferenceRetainedCompletedTargetDisposition.REMEMBER
}