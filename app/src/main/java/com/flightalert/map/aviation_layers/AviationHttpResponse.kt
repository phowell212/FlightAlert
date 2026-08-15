@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.net.URL

data class AviationHttpResponse(
    val status_code: Int,
    val body: String,
    val final_url: URL
)