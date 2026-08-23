@file:Suppress("PropertyName")

package com.flightalert.ui

enum class ReferenceAccessibilityCategory {
    PANEL,
    TRAFFIC,
    MAP,
    LABELS,
    OUTLINES,
}

enum class ReferenceAccessibilityRole {
    BUTTON,
    TAB,
    SWITCH,
    STATUS,
    SCROLL_CONTAINER,
}

enum class ReferenceAccessibilityAction {
    CLICK,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
}

data class ReferenceAccessibilityNode(
    val virtualId: Int,
    val stableId: String,
    val bounds: ReferenceDpRect,
    val role: ReferenceAccessibilityRole,
    val category: ReferenceAccessibilityCategory,
    val label: String,
    val selected: Boolean?,
    val onOff: Boolean?,
    val stateDescription: String?,
    val actionId: String?,
    val actions: Set<ReferenceAccessibilityAction>,
)
