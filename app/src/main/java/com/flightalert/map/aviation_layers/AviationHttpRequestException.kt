@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.io.IOException
import java.net.URL


class AviationHttpRequestException(
    message: String,
    val final_url: URL,
    cause: IOException? = null
) : IOException(message, cause)