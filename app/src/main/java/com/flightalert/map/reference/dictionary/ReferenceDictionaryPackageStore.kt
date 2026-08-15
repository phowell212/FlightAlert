@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "ConvertTryFinallyToUseCall",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.io.File


internal class ReferenceDictionaryPackageStore(
    private val candidate_provider: () -> List<File>,
    private val package_opener: (File) -> ReferenceDictionaryPackage?,
    private val elapsed_realtime_ms: () -> Long,
) : Closeable {
    constructor(
        context: Context,
        package_id: String? = null,
    ) : this(
        candidate_provider = {
            ReferenceDictionaryPackage.package_candidates(context, package_id)
        },
        package_opener = { candidate ->
            ReferenceDictionaryPackage.open_if_available(candidate)
        },
        elapsed_realtime_ms = { SystemClock.elapsedRealtime() },
    )

    private var package_checked = false
    private var last_package_check_ms = 0L
    private val package_lock = Any()
    @Volatile
    private var package_ref: ReferenceDictionaryPackage? = null

    fun package_info_if_available(): ReferenceDictionaryPackageInfo? {
        return open_package_if_available()?.info
    }

    fun reference_class_catalog_if_available(): ReferenceClassCatalog? {
        return open_package_if_available()?.reference_class_catalog
    }

    fun read_tile_payload(
        z: Int,
        x: Int,
        y: Int,
        verify_hash: Boolean = false,
        request_is_relevant: () -> Boolean,
    ): ReferenceDictionaryTilePayload? {
        return open_package_if_available()?.read_tile_payload(
            z = z,
            x = x,
            y = y,
            verify_hash = verify_hash,
            request_is_relevant = request_is_relevant,
        )
    }

    override fun close() {
        synchronized(package_lock) {
            package_ref?.close()
            package_ref = null
            package_checked = false
        }
    }

    private fun open_package_if_available(): ReferenceDictionaryPackage? {
        package_ref?.let { return it }
        return synchronized(package_lock) {
            package_ref?.let { return@synchronized it }
            val now_ms = elapsed_realtime_ms()
            if (package_checked && now_ms - last_package_check_ms < PACKAGE_MISSING_RETRY_MS) {
                return@synchronized null
            }
            package_checked = true
            last_package_check_ms = now_ms
            val opened = first_available_reference_package(
                candidate_provider(),
                package_opener,
            )
            package_ref = opened
            opened
        }
    }

    companion object {
        internal const val PACKAGE_MISSING_RETRY_MS = 1_000L
    }
}