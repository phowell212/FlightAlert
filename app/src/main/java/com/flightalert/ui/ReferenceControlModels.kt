@file:Suppress("PropertyName")

package com.flightalert.ui

enum class ReferenceControlKind {
    BUTTON,
    TAB,
    SWITCH,
}

data class ReferenceDpRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "rectangle coordinates must be finite"
        }
        require(right >= left && bottom >= top) { "rectangle must not be inverted" }
    }

    fun intersection(other: ReferenceDpRect): ReferenceDpRect? {
        val clippedLeft = maxOf(left, other.left)
        val clippedTop = maxOf(top, other.top)
        val clippedRight = minOf(right, other.right)
        val clippedBottom = minOf(bottom, other.bottom)
        return if (clippedRight > clippedLeft && clippedBottom > clippedTop) {
            ReferenceDpRect(clippedLeft, clippedTop, clippedRight, clippedBottom)
        } else {
            null
        }
    }
}

data class ReferenceTextPlan(
    val text: String,
    val lines: List<String>,
    val textSizeSp: Float,
    val bounds: ReferenceDpRect,
    val fontScale: Float = 1f,
    val fontWeight: Int = 500,
    val italic: Boolean = false,
)

data class ReferenceControlPlan(
    val actionId: String,
    val kind: ReferenceControlKind,
    val label: String,
    val bounds: ReferenceDpRect,
    val category: ReferenceAccessibilityCategory,
    val selected: Boolean? = null,
    val onOff: Boolean? = null,
    val stateLabel: String? = null,
    val swatch: ReferenceStyleSwatch? = null,
    val sectionMasterEnabled: Boolean? = null,
    val labelText: ReferenceTextPlan? = null,
    val stateText: ReferenceTextPlan? = null,
    val swatchBounds: ReferenceDpRect? = null,
    val intent: ReferencePanelIntent,
)
