@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.net.URL


class AviationSourceIdentity(
    val provider: String,
    val service: String,
    val layer: String,
    requested_envelopes: List<AviationGeoBounds>,
    val object_ids_captured_at_epoch_ms: Long?,
    val response_observed_at_epoch_ms: Long,
    final_source_url: URL,
    val advertised_revision: Long?
) {
    val requested_envelopes: List<AviationGeoBounds> = immutable_list(requested_envelopes)
    val final_source_url_is_valid: Boolean =
        final_source_url.protocol.equals("https", ignoreCase = true) && final_source_url.host.isNotBlank()
    val final_source_url: String = final_source_url.toString()
}