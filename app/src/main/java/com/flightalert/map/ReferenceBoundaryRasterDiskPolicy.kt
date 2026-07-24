package com.flightalert.map

import java.math.BigInteger
import java.security.MessageDigest

internal fun reference_boundary_raster_disk_cacheable(
    band: ReferenceBoundaryRasterBand,
): Boolean {
    return band.sourceZoom <= MAX_DISK_CACHED_REFERENCE_SOURCE
}

internal fun reference_boundary_raster_disk_namespace(
    band: ReferenceBoundaryRasterBand,
    packageId: String,
    schemaVersion: Int,
    rendererSemanticStreamSha256: String,
    densityRawBits: Int,
): String {
    val identity = listOf(
        REFERENCE_BOUNDARY_RASTER_CACHE_VERSION,
        packageId,
        schemaVersion.toString(),
        rendererSemanticStreamSha256,
        ReferencePresentationPolicy.canonical_policy_sha256,
        densityRawBits.toString(),
        band.sourceZoom.toString(),
        band.rasterZoom.toString(),
        band.corePixels.toString(),
        band.presentationCentizoom.toString(),
        band.visibilityCentizoom.toString(),
        java.lang.Long.toUnsignedString(band.settingsKey),
    ).joinToString("|")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
    return BigInteger(1, digest).toString(10)
}

internal const val REFERENCE_BOUNDARY_RASTER_CACHE_ROOT =
    "reference_boundary_raster_tiles"
private const val REFERENCE_BOUNDARY_RASTER_CACHE_VERSION =
    "reference-boundary-raster-cache-v2"
private const val MAX_DISK_CACHED_REFERENCE_SOURCE = 6
