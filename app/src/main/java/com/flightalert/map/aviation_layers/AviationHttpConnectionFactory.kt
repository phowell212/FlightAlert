@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.net.HttpURLConnection
import java.net.URL


fun interface AviationHttpConnectionFactory {
    // Open platform connections while the transport keeps HTTPS enforcement.
    fun open(url: URL): HttpURLConnection
}
