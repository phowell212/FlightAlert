@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map


internal const val forcedItalicSkewX = -0.25f

// Apply one visual contract to sourced text across the map.
internal fun plan(text: SourcedMapText, primaryTextSize: Float): SourcedMapTextPresentationPlan {
    require(primaryTextSize.isFinite() && primaryTextSize > 0f) {
        "sourced map text size must be positive and finite"
    }
    val english = text.englishText
    if (!text.isAtomicBilingual || english == null) {
        return SourcedMapTextPresentationPlan(
            primaryLines = listOf(
                SourcedMapTextLinePlan(
                    text.primaryText,
                    primaryTextSize,
                    0f,
                    false,
                ),
            ),
            english = null,
            collisionHeightEm = SINGLE_COLLISION_HEIGHT_EM,
            collisionCenterOffset = 0f,
        )
    }
    val extra_primary_lines = text.primaryPresentationLines.size - 1
    val first_baseline_offset = PRIMARY_BASELINE_OFFSET_EM -
        extra_primary_lines * PRIMARY_LINE_STEP_EM
    return SourcedMapTextPresentationPlan(
        primaryLines = text.primaryPresentationLines.mapIndexed { index, line ->
            SourcedMapTextLinePlan(
                line,
                primaryTextSize,
                (first_baseline_offset + index * PRIMARY_LINE_STEP_EM) * primaryTextSize,
                false,
            )
        },
        english = SourcedMapTextLinePlan(
            english,
            primaryTextSize * ENGLISH_TEXT_SCALE,
            ENGLISH_BASELINE_OFFSET_EM * primaryTextSize,
            true,
        ),
        collisionHeightEm = BILINGUAL_COLLISION_HEIGHT_EM +
            extra_primary_lines * PRIMARY_LINE_STEP_EM,
        collisionCenterOffset = (
            BILINGUAL_COLLISION_CENTER_OFFSET_EM -
                extra_primary_lines * PRIMARY_LINE_STEP_EM / 2f
            ) * primaryTextSize,
    )
}

private const val ENGLISH_TEXT_SCALE = 0.76f
private const val PRIMARY_BASELINE_OFFSET_EM = -0.38f
private const val ENGLISH_BASELINE_OFFSET_EM = 0.56f
private const val PRIMARY_LINE_STEP_EM =
    ENGLISH_BASELINE_OFFSET_EM - PRIMARY_BASELINE_OFFSET_EM
private const val SINGLE_COLLISION_HEIGHT_EM = 1.45f
private const val BILINGUAL_COLLISION_HEIGHT_EM = 2.2f
private const val BILINGUAL_COLLISION_CENTER_OFFSET_EM =
    (PRIMARY_BASELINE_OFFSET_EM + ENGLISH_BASELINE_OFFSET_EM) / 2f
