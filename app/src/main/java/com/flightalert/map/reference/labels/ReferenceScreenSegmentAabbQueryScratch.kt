package com.flightalert.map.reference.labels

import java.util.Arrays
import kotlin.math.max


internal class ReferenceScreenSegmentAabbQueryScratch(initialCapacity: Int = 16) {
    private var values = IntArray(max(1, initialCapacity))
    var size: Int = 0
        private set

    internal fun clear() {
        size = 0
    }

    internal fun add(value: Int) {
        if (size == values.size) {
            values = values.copyOf(values.size * 2)
        }
        values[size++] = value
    }

    internal fun ordinalAt(index: Int): Int {
        require(index in 0 until size)
        return values[index]
    }

    internal fun sort() {
        Arrays.sort(values, 0, size)
    }
}

