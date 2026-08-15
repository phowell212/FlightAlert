@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
)

package com.flightalert.details

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.display_model_name
import com.flightalert.sources.AirplanesLiveHttp
import com.flightalert.sources.fetch_json_object
import com.flightalert.sources.https_url



data class PhotoEvidence(
    val source_name: String,
    val image_url: String,
    val page_url: String,
    val search_query: String,
    val quote: String,
    val matched_terms: List<String>
)