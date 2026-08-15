@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftRouteSource
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.aircraft.AirportDetails
import com.flightalert.aircraft.TrackPoint
import com.flightalert.flight.CurrentRouteValidator
import com.flightalert.flight.OriginAerodrome
import com.flightalert.flight.TraceSegment
import com.flightalert.map.MapProjection
import com.flightalert.sources.AirplanesLiveHttp
import com.flightalert.sources.fetch_json_object
import com.flightalert.sources.https_url



data class FaaRegistryRecord(
    val registration: String,
    val manufacturer: String?,
    val model: String?,
    val manufactured_year: String?,
    val registered_owner: String?,
    val source_name: String
)