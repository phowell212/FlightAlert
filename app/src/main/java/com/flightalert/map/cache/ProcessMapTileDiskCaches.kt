@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.cache

import java.io.File
import java.nio.file.Path as NioPath


internal object ProcessMapTileDiskCaches {
    // Keep one disk-cache budget for each canonical root.
    private val registry = MapTileDiskCacheRegistry()

    fun cache(
        cache_root: File,
        max_bytes: Long,
        managed_root_names: Set<String>,
    ): MapTileDiskCache = registry.cache(cache_root, max_bytes, managed_root_names)

    private class MapTileDiskCacheRegistry {
        private val registry_lock = Any()
        private val caches = HashMap<NioPath, RegisteredCache>()

        fun cache(
            cache_root: File,
            max_bytes: Long,
            managed_root_names: Set<String>,
        ): MapTileDiskCache {
            val canonical_root = cache_root.canonicalFile.toPath().normalize()
            synchronized(registry_lock) {
                val existing = caches[canonical_root]
                if (existing != null) {
                    require(existing.max_bytes == max_bytes) {
                        "one map tile cache root cannot have multiple byte budgets"
                    }
                    return existing.cache
                }
                val cache = MapTileDiskCache(
                    cache_root = cache_root,
                    max_bytes = max_bytes,
                    managed_root_names = managed_root_names,
                )
                caches[canonical_root] = RegisteredCache(max_bytes, cache)
                return cache
            }
        }

        private data class RegisteredCache(
            val max_bytes: Long,
            val cache: MapTileDiskCache,
        )
    }
}
