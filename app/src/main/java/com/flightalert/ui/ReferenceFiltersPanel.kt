package com.flightalert.ui

class ReferenceFiltersPanel(
    textMeasurer: ReferenceFiltersTextMeasurer,
) {
    private val layout = ReferenceFiltersPanelLayout(textMeasurer)

    fun plan(input: ReferenceFiltersPanelInput): ReferenceFiltersPanelPlan =
        layout.plan(input)

    fun keyIntent(
        plan: ReferenceFiltersPanelPlan,
        focusedActionId: String?,
        key: ReferencePanelKey,
    ): ReferencePanelIntent? {
        val actionIds = plan.controls.map(ReferenceControlPlan::actionId)
        return when (key) {
            ReferencePanelKey.BACK -> ReferencePanelIntent.Dismiss
            ReferencePanelKey.ACTIVATE -> plan.controls
                .firstOrNull { it.actionId == focusedActionId }
                ?.intent
            ReferencePanelKey.NEXT -> {
                val index = actionIds.indexOf(focusedActionId)
                ReferencePanelIntent.MoveFocus(actionIds[(index + 1).mod(actionIds.size)])
            }
            ReferencePanelKey.PREVIOUS -> {
                val index = actionIds.indexOf(focusedActionId).takeIf { it >= 0 } ?: 0
                ReferencePanelIntent.MoveFocus(actionIds[(index - 1).mod(actionIds.size)])
            }
            ReferencePanelKey.SCROLL_FORWARD -> ReferencePanelIntent.SetScrollOffset(
                (plan.appliedScrollOffsetDp + plan.contentViewport.height * 0.8f)
                    .coerceAtMost(plan.maxScrollOffsetDp)
            )
            ReferencePanelKey.SCROLL_BACKWARD -> ReferencePanelIntent.SetScrollOffset(
                (plan.appliedScrollOffsetDp - plan.contentViewport.height * 0.8f)
                    .coerceAtLeast(0f)
            )
        }
    }
}
