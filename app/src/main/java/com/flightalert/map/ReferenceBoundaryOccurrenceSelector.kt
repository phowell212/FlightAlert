package com.flightalert.map

internal data class ReferenceBoundaryOccurrenceKey(
    val dedupeId: ULong,
    val renderedWorldCopy: Long,
)

/** Admits one complete canonical outline path per displayed wrapped-world copy. */
internal object ReferenceBoundaryOccurrenceSelector {
    fun admit(
        seen: MutableSet<ReferenceBoundaryOccurrenceKey>,
        dedupeId: ULong?,
        renderedWorldCopy: Long,
    ): Boolean = dedupeId == null || seen.add(
        ReferenceBoundaryOccurrenceKey(dedupeId, renderedWorldCopy),
    )

    fun renderedWorldCopy(
        zoom: Int,
        requestedTileX: Int,
        drawTileX: Int,
        postingWorldWrap: Int,
    ): Long {
        require(zoom in 0..29) { "reference tile zoom is outside [0,29]" }
        val worldTiles = 1L shl zoom
        val drawWrap = Math.floorDiv(
            drawTileX.toLong() - requestedTileX.toLong(),
            worldTiles,
        )
        return drawWrap - postingWorldWrap.toLong()
    }
}
