@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection


internal object DefaultAviationHttpConnectionFactory : AviationHttpConnectionFactory {
    override fun open(url: URL): HttpURLConnection {
        val connection = url.openConnection()
        if (connection !is HttpsURLConnection) {
            throw IOException("Aviation HTTPS URL did not open an HTTPS connection")
        }
        return connection
    }
}