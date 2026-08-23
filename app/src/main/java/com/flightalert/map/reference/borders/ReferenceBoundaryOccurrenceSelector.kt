package com.flightalert.map.reference.borders



    // Admit one complete outline path per displayed wrapped-world copy.
    internal fun admit(
        seen: MutableSet<ReferenceBoundaryOccurrenceKey>,
        dedupeId: ULong?,
        renderedWorldCopy: Long,
    ): Boolean = dedupeId == null || seen.add(
        ReferenceBoundaryOccurrenceKey(dedupeId, renderedWorldCopy),
    )

    internal fun renderedWorldCopy(
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
