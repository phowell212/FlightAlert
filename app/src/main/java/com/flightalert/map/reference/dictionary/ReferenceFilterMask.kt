@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import com.flightalert.map.reference.FilterId


@JvmInline
internal value class ReferenceFilterMask private constructor(
    private val enabledBits: Int,
) {
    val cacheKey: Int get() = enabledBits

    fun allows(filterId: FilterId): Boolean =
        enabledBits and (1 shl filterId.ordinal) != 0

    fun allows(filterId: FilterId?, legacyAllowed: Boolean): Boolean =
        filterId?.let(::allows) ?: legacyAllowed

    companion object {
        fun from(state: FilterState): ReferenceFilterMask {
            require(FilterId.entries.size < Int.SIZE_BITS) {
                "reference filters no longer fit the hot-path mask"
            }
            var bits = 0
            FilterId.entries.forEach { filterId ->
                if (state.effectively_enabled(filterId)) {
                    bits = bits or (1 shl filterId.ordinal)
                }
            }
            return ReferenceFilterMask(bits)
        }
    }
}