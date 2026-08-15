@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.cache

import android.content.Context
import android.os.Process
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


internal fun default_map_tile_disk_executor(): ExecutorService {
    val worker_id = AtomicInteger()
    return ThreadPoolExecutor(
        1,
        1,
        15_000L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    ) { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-map-tile-disk-${worker_id.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.apply {
        allowCoreThreadTimeOut(true)
    }
}

internal fun map_tile_cache_file(
    context: Context,
    cache_key: String,
    z: Int,
    x: Int,
    y: Int
): File {
    return File(context.cacheDir, "${cache_key}_tiles/$z/$x/$y.png")
}
