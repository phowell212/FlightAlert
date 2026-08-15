@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.net.URL


internal fun nat_identity(final_url: URL, observed_at: Long): AviationSourceIdentity =
    AviationSourceIdentity(
        provider = "FAA",
        service = "NMS North Atlantic Tracks",
        layer = "NAT",
        requested_envelopes = emptyList(),
        object_ids_captured_at_epoch_ms = null,
        response_observed_at_epoch_ms = observed_at,
        final_source_url = final_url,
        advertised_revision = null
    )
