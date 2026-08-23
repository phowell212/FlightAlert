@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.net.URL


fun interface AviationHttpTransport {
    // Replace network I/O here when provider tests need deterministic responses.
    fun get(url: URL): AviationHttpResponse
}
