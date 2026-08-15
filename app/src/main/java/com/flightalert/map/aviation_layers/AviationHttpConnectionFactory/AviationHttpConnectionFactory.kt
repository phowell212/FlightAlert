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
    fun open(url: URL): HttpURLConnection
}