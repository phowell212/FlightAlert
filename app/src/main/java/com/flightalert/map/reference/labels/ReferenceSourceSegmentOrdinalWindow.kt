package com.flightalert.map.reference.labels


    internal fun startInclusive(
        segments: List<ReferencePreparedPathSegment>,
        sourceStartDistance: Double,
        epsilon: Double,
    ): Int {
        require(sourceStartDistance.isFinite()) { "source start distance must be finite" }
        require(epsilon.isFinite() && epsilon >= 0.0) {
            "source distance epsilon must be finite and nonnegative"
        }
        var lower = 0
        var upper = segments.size
        while (lower < upper) {
            val middle = (lower + upper) ushr 1
            if (segments[middle].sourceEndDistance + epsilon >= sourceStartDistance) {
                upper = middle
            } else {
                lower = middle + 1
            }
        }
        return lower
    }

    internal fun endExclusive(
        segments: List<ReferencePreparedPathSegment>,
        sourceEndDistance: Double,
        epsilon: Double,
    ): Int {
        require(sourceEndDistance.isFinite()) { "source end distance must be finite" }
        require(epsilon.isFinite() && epsilon >= 0.0) {
            "source distance epsilon must be finite and nonnegative"
        }
        var lower = 0
        var upper = segments.size
        val inclusiveUpperDistance = sourceEndDistance + epsilon
        while (lower < upper) {
            val middle = (lower + upper) ushr 1
            if (segments[middle].sourceStartDistance > inclusiveUpperDistance) {
                upper = middle
            } else {
                lower = middle + 1
            }
        }
        return lower
    }
