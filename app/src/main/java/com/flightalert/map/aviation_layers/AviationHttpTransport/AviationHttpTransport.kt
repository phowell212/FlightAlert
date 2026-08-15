@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.net.URL


fun interface AviationHttpTransport {
    fun get(url: URL): AviationHttpResponse
}