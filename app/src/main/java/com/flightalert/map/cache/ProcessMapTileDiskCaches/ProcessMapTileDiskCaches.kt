@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.cache

import java.io.File


internal object ProcessMapTileDiskCaches {
    private val registry = MapTileDiskCacheRegistry()

    fun cache(
        cache_root: File,
        max_bytes: Long,
        managed_root_names: Set<String>,
    ): MapTileDiskCache = registry.cache(cache_root, max_bytes, managed_root_names)
}