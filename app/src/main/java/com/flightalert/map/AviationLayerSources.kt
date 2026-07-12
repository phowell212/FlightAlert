package com.flightalert.map

import java.math.BigDecimal
import java.math.BigInteger
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Collections
import java.util.TreeSet
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener
import org.json.JSONObject

data class AviationHttpResponse(
    val status_code: Int,
    val body: String,
    val headers: Map<String, String>,
    val final_url: URL
)

fun interface AviationHttpTransport {
    fun get(url: URL): AviationHttpResponse
}

fun interface AviationHttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

class AviationHttpRequestException(
    message: String,
    val final_url: URL,
    cause: IOException? = null
) : IOException(message, cause)

class HttpsAviationHttpTransport(
    private val user_agent: String,
    private val connect_timeout_ms: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val read_timeout_ms: Int = DEFAULT_READ_TIMEOUT_MS,
    private val max_redirects: Int = DEFAULT_MAX_REDIRECTS,
    private val connection_factory: AviationHttpConnectionFactory = DefaultAviationHttpConnectionFactory
) : AviationHttpTransport {
    init {
        require(user_agent.isNotBlank()) { "Aviation HTTP User-Agent is required" }
        require(connect_timeout_ms > 0) { "Aviation HTTP connect timeout must be positive" }
        require(read_timeout_ms > 0) { "Aviation HTTP read timeout must be positive" }
        require(max_redirects >= 0) { "Aviation HTTP redirect limit cannot be negative" }
    }

    override fun get(url: URL): AviationHttpResponse {
        require_https(url, initial = true)
        var current_url = url
        var redirect_count = 0
        while (true) {
            val connection = try {
                connection_factory.open(current_url)
            } catch (failure: AviationHttpRequestException) {
                throw failure
            } catch (failure: IOException) {
                throw AviationHttpRequestException(
                    failure.message ?: "Aviation HTTPS connection failed",
                    current_url,
                    failure
                )
            }
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = connect_timeout_ms
                connection.readTimeout = read_timeout_ms
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", user_agent)
                connection.setRequestProperty("Accept", ACCEPT_HEADER)
                connection.setRequestProperty("Cache-Control", "no-cache")

                val status_code = connection.responseCode
                if (status_code in REDIRECT_STATUS_CODES) {
                    if (redirect_count >= max_redirects) {
                        throw IOException("Aviation HTTPS redirect limit exceeded")
                    }
                    val location = connection.getHeaderField("Location")
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: throw IOException("Aviation HTTPS redirect has no Location header")
                    val redirect_url = URL(current_url, location)
                    require_https(redirect_url, initial = false)
                    current_url = redirect_url
                    redirect_count += 1
                    continue
                }

                val stream = if (status_code >= HTTP_ERROR_STATUS) {
                    connection.errorStream
                } else {
                    connection.inputStream
                }
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    .orEmpty()
                val headers = SELECTED_RESPONSE_HEADERS.mapNotNull { header ->
                    connection.getHeaderField(header)?.let { header to it }
                }.toMap(linkedMapOf())
                return AviationHttpResponse(status_code, body, headers, current_url)
            } catch (failure: AviationHttpRequestException) {
                throw failure
            } catch (failure: IOException) {
                throw AviationHttpRequestException(
                    failure.message ?: "Aviation HTTPS request failed",
                    current_url,
                    failure
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun require_https(url: URL, initial: Boolean) {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            if (initial) {
                throw IllegalArgumentException("Aviation HTTP requests require HTTPS")
            }
            throw IOException("Aviation HTTP redirect rejected a non-HTTPS URL")
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 9_000
        const val DEFAULT_MAX_REDIRECTS = 4
        const val HTTP_ERROR_STATUS = 400
        const val ACCEPT_HEADER = "application/json, application/geo+json"
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        val SELECTED_RESPONSE_HEADERS = listOf("Date", "ETag", "Last-Modified", "Cache-Control")
    }
}

private object DefaultAviationHttpConnectionFactory : AviationHttpConnectionFactory {
    override fun open(url: URL): HttpURLConnection {
        val connection = url.openConnection()
        if (connection !is HttpsURLConnection) {
            throw IOException("Aviation HTTPS URL did not open an HTTPS connection")
        }
        return connection
    }
}

data class AviationArcGisLayer<T>(
    val kind: AviationLayerKind,
    val source_provider: String,
    val source_service: String,
    val source_layer: String,
    val metadata_url: String,
    val query_url: String,
    val where_clause: String,
    val out_fields: String,
    val record_id: (T) -> Long,
    val parser: (JSONObject) -> AviationParsedBatch<T>
) {
    init {
        require(source_provider.isNotBlank()) { "ArcGIS source provider is required" }
        require(source_service.isNotBlank()) { "ArcGIS source service is required" }
        require(source_layer.isNotBlank()) { "ArcGIS source layer is required" }
    }
}

internal class AviationFetchCancelledException : Exception()

object AviationQueryEnvelopePlanner {
    fun for_viewport(
        min_lat: Double,
        west_lon: Double,
        max_lat: Double,
        east_lon: Double
    ): List<AviationLayerBounds> {
        require(listOf(min_lat, west_lon, max_lat, east_lon).all(Double::isFinite)) {
            "Aviation query bounds must be finite"
        }
        require(min_lat in -90.0..90.0 && max_lat in -90.0..90.0 && min_lat <= max_lat) {
            "Aviation query latitude bounds are invalid"
        }
        require(west_lon in -180.0..180.0 && east_lon in -180.0..180.0) {
            "Aviation query longitude bounds are invalid"
        }
        return if (west_lon <= east_lon) {
            listOf(AviationLayerBounds(min_lat, west_lon, max_lat, east_lon))
        } else {
            listOf(
                AviationLayerBounds(min_lat, west_lon, max_lat, 180.0),
                AviationLayerBounds(min_lat, -180.0, max_lat, east_lon)
            )
        }
    }
}

class AviationArcGisSource(
    private val transport: AviationHttpTransport,
    private val batch_size: Int,
    private val now_ms: () -> Long
) {
    init {
        require(batch_size > 0) { "ArcGIS batch size must be positive" }
    }

    fun <T> fetch(
        envelopes: List<AviationLayerBounds>,
        layer: AviationArcGisLayer<T>
    ): AviationSourceResult<T> = fetch(envelopes, layer, should_continue = { true })

    fun <T> fetch(
        envelopes: List<AviationLayerBounds>,
        layer: AviationArcGisLayer<T>,
        should_continue: () -> Boolean
    ): AviationSourceResult<T> {
        val requested_envelopes = envelopes.toList()
        validate_envelopes(requested_envelopes)
        val first = try {
            fetch_attempt(requested_envelopes, layer, should_continue)
        } catch (failure: ArcGisSourceFailure) {
            return unavailable_result(requested_envelopes, layer, failure)
        }
        if (!first.revision_changed) return first.to_result(layer, revision_changed_twice = false)

        val retry = try {
            fetch_attempt(requested_envelopes, layer, should_continue)
        } catch (failure: ArcGisSourceFailure) {
            return unavailable_result(requested_envelopes, layer, failure)
        }
        return retry.to_result(layer, revision_changed_twice = retry.revision_changed)
    }

    private fun <T> fetch_attempt(
        envelopes: List<AviationLayerBounds>,
        layer: AviationArcGisLayer<T>,
        should_continue: () -> Boolean
    ): ArcGisAttempt<T> {
        val metadata_url = query_url(layer.metadata_url, listOf("f" to "json"))
        val before_response = get(metadata_url, should_continue).require_success()
        val before = parse_metadata(before_response)
        val expected_ids = TreeSet<Long>()
        var last_response = before_response
        for (envelope in envelopes) {
            val id_url = query_url(
                layer.query_url,
                listOf(
                    "f" to "json",
                    "where" to layer.where_clause,
                    "geometry" to envelope.arc_gis_envelope(),
                    "geometryType" to "esriGeometryEnvelope",
                    "spatialRel" to "esriSpatialRelIntersects",
                    "inSR" to "4326",
                    "returnIdsOnly" to "true"
                )
            )
            val response = try {
                get(id_url, should_continue).require_success()
            } catch (failure: ArcGisSourceFailure) {
                throw failure.with_revision(before.revision)
            }
            val ids = try {
                parse_object_ids(response, before.object_id_field)
            } catch (failure: ArcGisSourceFailure) {
                throw failure.with_revision(before.revision)
            }
            expected_ids += ids
            last_response = response
        }
        val captured_at = now_ms()
        val records_by_id = linkedMapOf<Long, T>()
        val invalid_ids = linkedSetOf<Long>()
        val unexpected_ids = linkedSetOf<Long>()
        val duplicate_ids = linkedSetOf<Long>()
        val integrity_reasons = linkedSetOf<String>()
        var exceeded = false
        val effective_batch_size = minOf(batch_size, before.max_record_count)
        for (ids in expected_ids.toList().chunked(effective_batch_size)) {
            val feature_url = query_url(
                layer.query_url,
                listOf(
                    "f" to "geojson",
                    "objectIds" to ids.joinToString(","),
                    "outFields" to layer.out_fields,
                    "outSR" to "4326",
                    "returnGeometry" to "true"
                )
            )
            val response: AviationHttpResponse
            val feature_collection: ArcGisFeatureCollection
            try {
                response = get(feature_url, should_continue).require_success()
                feature_collection = response.require_feature_collection(before.object_id_field)
            } catch (failure: ArcGisSourceFailure) {
                return ArcGisAttempt(
                    envelopes = envelopes,
                    records = records_by_id.values.toList(),
                    expected_ids = expected_ids,
                    returned_ids = records_by_id.keys,
                    invalid_ids = invalid_ids,
                    unexpected_ids = unexpected_ids,
                    duplicate_ids = duplicate_ids,
                    exceeded_transfer_limit = exceeded,
                    captured_at_epoch_ms = captured_at,
                    observed_at_epoch_ms = now_ms(),
                    final_url = failure.final_url ?: feature_url,
                    advertised_revision = before.revision,
                    revision_changed = false,
                    failure_reason = (integrity_reasons +
                        "feature batch failed: ${failure.message}").joinToString("; ")
                )
            }
            val json = feature_collection.json
            if (feature_collection.has_schema_error) {
                integrity_reasons += "feature schema error"
            }
            exceeded = exceeded || feature_collection.exceeded_transfer_limit == true
            val parsed = try {
                layer.parser(json)
            } catch (_: JSONException) {
                return ArcGisAttempt.failed_batch(
                    envelopes,
                    records_by_id,
                    expected_ids,
                    invalid_ids,
                    unexpected_ids,
                    duplicate_ids,
                    exceeded,
                    captured_at,
                    now_ms(),
                    response.final_url,
                    before.revision,
                    (integrity_reasons + "feature batch parser failed").joinToString("; ")
                )
            } catch (_: IllegalArgumentException) {
                return ArcGisAttempt.failed_batch(
                    envelopes,
                    records_by_id,
                    expected_ids,
                    invalid_ids,
                    unexpected_ids,
                    duplicate_ids,
                    exceeded,
                    captured_at,
                    now_ms(),
                    response.final_url,
                    before.revision,
                    (integrity_reasons + "feature batch parser failed").joinToString("; ")
                )
            }
            invalid_ids += parsed.invalid_object_ids
            val requested_ids = ids.toSet()
            val raw_id_counts = feature_collection.object_ids.groupingBy { it }.eachCount()
            duplicate_ids += raw_id_counts.filterValues { it > 1 }.keys
            unexpected_ids += raw_id_counts.keys - requested_ids
            val parsed_record_ids = parsed.records.map(layer.record_id)
            val raw_id_set = raw_id_counts.keys
            val parsed_accounted_ids = parsed_record_ids.toSet() + parsed.invalid_object_ids
            if (
                parsed.returned_object_ids != parsed_record_ids.toSet() ||
                parsed_accounted_ids != raw_id_set
            ) {
                integrity_reasons += "parser ID accounting mismatch"
            }
            for (record in parsed.records) {
                val record_id = layer.record_id(record)
                if (record_id !in requested_ids) {
                    unexpected_ids += record_id
                } else if (records_by_id.putIfAbsent(record_id, record) != null) {
                    duplicate_ids += record_id
                }
            }
            last_response = response
        }

        val after_response: AviationHttpResponse
        val after: ArcGisMetadata
        try {
            after_response = get(metadata_url, should_continue).require_success()
            after = parse_metadata(after_response)
        } catch (failure: ArcGisSourceFailure) {
            return ArcGisAttempt(
                envelopes = envelopes,
                records = records_by_id.values.toList(),
                expected_ids = expected_ids,
                returned_ids = records_by_id.keys,
                invalid_ids = invalid_ids,
                unexpected_ids = unexpected_ids,
                duplicate_ids = duplicate_ids,
                exceeded_transfer_limit = exceeded,
                captured_at_epoch_ms = captured_at,
                observed_at_epoch_ms = now_ms(),
                final_url = failure.final_url ?: metadata_url,
                advertised_revision = before.revision,
                revision_changed = false,
                failure_reason = (integrity_reasons +
                    "final revision check failed: ${failure.message}").joinToString("; ")
            )
        }
        last_response = after_response
        return ArcGisAttempt(
            envelopes = envelopes,
            records = records_by_id.values.toList(),
            expected_ids = expected_ids,
            returned_ids = records_by_id.keys,
            invalid_ids = invalid_ids,
            unexpected_ids = unexpected_ids,
            duplicate_ids = duplicate_ids,
            exceeded_transfer_limit = exceeded,
            captured_at_epoch_ms = captured_at,
            observed_at_epoch_ms = now_ms(),
            final_url = last_response.final_url,
            advertised_revision = after.revision,
            revision_changed = before != after,
            failure_reason = integrity_reasons.takeIf(Set<String>::isNotEmpty)
                ?.joinToString("; ")
        )
    }

    private fun validate_envelopes(envelopes: List<AviationLayerBounds>) {
        require(envelopes.isNotEmpty()) { "At least one ArcGIS query envelope is required" }
        for (envelope in envelopes) {
            require(
                listOf(
                    envelope.min_lat,
                    envelope.min_lon,
                    envelope.max_lat,
                    envelope.max_lon
                ).all(Double::isFinite)
            ) { "ArcGIS query envelopes must be finite" }
            require(
                envelope.min_lat in -90.0..90.0 &&
                    envelope.max_lat in -90.0..90.0 &&
                    envelope.min_lat <= envelope.max_lat &&
                    envelope.min_lon in -180.0..180.0 &&
                    envelope.max_lon in -180.0..180.0 &&
                    envelope.min_lon <= envelope.max_lon
            ) { "ArcGIS query envelope is invalid" }
        }
    }

    private fun parse_metadata(response: AviationHttpResponse): ArcGisMetadata {
        val json = response.arcgis_json()
        val object_id_field = (json.opt("objectIdField") as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw response.failure("ArcGIS metadata has no object ID field")
        val revision = json.optJSONObject("editingInfo")
            ?.opt("dataLastEditDate")
            .exact_aviation_object_id_or_null()
            ?: throw response.failure("ArcGIS metadata has no exact edit revision")
        val max_record_count = json.opt("maxRecordCount")
            .exact_aviation_object_id_or_null()
            ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: throw response.failure("ArcGIS metadata has no valid maxRecordCount")
        return ArcGisMetadata(object_id_field, revision, max_record_count)
    }

    private fun parse_object_ids(
        response: AviationHttpResponse,
        expected_field: String
    ): List<Long> {
        val json = response.arcgis_json()
        if (json.has("exceededTransferLimit") && !json.isNull("exceededTransferLimit")) {
            val exceeded = json.opt("exceededTransferLimit") as? Boolean
                ?: throw response.failure("ArcGIS ID capture has an invalid transfer-limit flag")
            if (exceeded) {
                throw response.failure("ArcGIS ID capture exceeded transfer limit")
            }
        }
        val field = (json.opt("objectIdFieldName") as? String)?.trim()
        if (field != expected_field) throw response.failure("ArcGIS object ID field changed")
        val ids = json.optJSONArray("objectIds")
            ?: throw response.failure("ArcGIS ID response has no objectIds array")
        return buildList {
            for (index in 0 until ids.length()) {
                add(
                    ids.opt(index).exact_aviation_object_id_or_null()
                        ?: throw response.failure("ArcGIS ID response contains an invalid ID")
                )
            }
        }
    }

    private fun AviationHttpResponse.require_success(): AviationHttpResponse {
        if (status_code != HttpURLConnection.HTTP_OK) {
            throw failure("ArcGIS HTTP status $status_code")
        }
        return this
    }

    private fun AviationHttpResponse.arcgis_json(): JSONObject {
        val json = strict_json_object(body)
            ?: throw failure("ArcGIS returned malformed JSON")
        if (json.has("error") && !json.isNull("error")) {
            throw failure("ArcGIS returned an error object")
        }
        return json
    }

    private fun strict_json_object(body: String): JSONObject? {
        val has_illegal_control = body.any { character ->
            character < ' ' &&
                character != '\t' &&
                character != '\n' &&
                character != '\r'
        }
        if (has_illegal_control) return null
        return try {
            val tokener = JSONTokener(body)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != '\u0000') null else value
        } catch (_: JSONException) {
            null
        }
    }

    private fun AviationHttpResponse.require_feature_collection(
        object_id_field: String
    ): ArcGisFeatureCollection {
        val json = arcgis_json()
        val features = json.optJSONArray("features")
            ?: throw failure("ArcGIS feature batch schema has no features array")
        val properties_value = json.opt("properties")
        val exceeded_transfer_limit = when {
            !json.has("properties") -> null
            properties_value !is JSONObject -> {
                throw failure("ArcGIS feature batch schema has invalid properties")
            }
            !properties_value.has("exceededTransferLimit") -> null
            else -> properties_value.opt("exceededTransferLimit") as? Boolean
                ?: throw failure("ArcGIS feature batch schema has an invalid transfer-limit flag")
        }
        var has_schema_error = json.opt("type") != "FeatureCollection"
        val object_ids = buildList {
            for (index in 0 until features.length()) {
                val feature = features.optJSONObject(index)
                val feature_properties = feature?.optJSONObject("properties")
                val object_id = feature_properties
                    ?.opt(object_id_field)
                    .exact_aviation_object_id_or_null()
                if (object_id == null) {
                    has_schema_error = true
                } else {
                    add(object_id)
                }
            }
        }
        return ArcGisFeatureCollection(
            json,
            object_ids,
            has_schema_error,
            exceeded_transfer_limit
        )
    }

    private fun get(url: URL): AviationHttpResponse = try {
        transport.get(url)
    } catch (failure: AviationHttpRequestException) {
        throw ArcGisSourceFailure(failure.message ?: "ArcGIS transport failed", failure.final_url)
    } catch (_: IOException) {
        throw ArcGisSourceFailure("ArcGIS transport failed", url)
    }

    private fun get(
        url: URL,
        should_continue: () -> Boolean
    ): AviationHttpResponse {
        if (!should_continue()) throw AviationFetchCancelledException()
        return try {
            val response = get(url)
            if (!should_continue()) throw AviationFetchCancelledException()
            response
        } catch (failure: ArcGisSourceFailure) {
            if (!should_continue()) throw AviationFetchCancelledException()
            throw failure
        }
    }

    private fun AviationHttpResponse.failure(message: String): ArcGisSourceFailure =
        ArcGisSourceFailure(message, final_url)

    private fun <T> unavailable_result(
        envelopes: List<AviationLayerBounds>,
        layer: AviationArcGisLayer<T>,
        failure: ArcGisSourceFailure
    ): AviationSourceResult<T> = AviationSourceResult(
        records = emptyList(),
        expected_object_ids = null,
        returned_object_ids = emptySet(),
        invalid_object_ids = emptySet(),
        identity = AviationSourceIdentity(
            provider = layer.source_provider,
            service = layer.source_service,
            layer = layer.source_layer,
            requested_envelopes = envelopes.map {
                AviationGeoBounds(it.min_lat, it.min_lon, it.max_lat, it.max_lon)
            },
            object_ids_captured_at_epoch_ms = null,
            response_observed_at_epoch_ms = now_ms(),
            final_source_url = (failure.final_url ?: URL(layer.metadata_url)).toString(),
            advertised_revision = failure.advertised_revision
        ),
        health = AviationLayerHealth(
            AviationLayerAvailability.UNAVAILABLE,
            AviationLayerCompleteness.UNKNOWN,
            AviationLayerFreshness.STALE
        ),
        message = failure.message
    )

    private fun query_url(base: String, parameters: List<Pair<String, String>>): URL {
        val encoded = parameters.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val separator = if ('?' in base) '&' else '?'
        return URL("$base$separator$encoded")
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private data class ArcGisMetadata(
        val object_id_field: String,
        val revision: Long,
        val max_record_count: Int
    )

    private data class ArcGisFeatureCollection(
        val json: JSONObject,
        val object_ids: List<Long>,
        val has_schema_error: Boolean,
        val exceeded_transfer_limit: Boolean?
    )

    private class ArcGisSourceFailure(
        override val message: String,
        val final_url: URL? = null,
        val advertised_revision: Long? = null
    ) : Exception(message) {
        fun with_revision(revision: Long): ArcGisSourceFailure =
            ArcGisSourceFailure(message, final_url, revision)
    }

    private data class ArcGisAttempt<T>(
        val envelopes: List<AviationLayerBounds>,
        val records: List<T>,
        val expected_ids: Set<Long>,
        val returned_ids: Set<Long>,
        val invalid_ids: Set<Long>,
        val unexpected_ids: Set<Long>,
        val duplicate_ids: Set<Long>,
        val exceeded_transfer_limit: Boolean,
        val captured_at_epoch_ms: Long,
        val observed_at_epoch_ms: Long,
        val final_url: URL,
        val advertised_revision: Long,
        val revision_changed: Boolean,
        val failure_reason: String?
    ) {
        fun to_result(
            layer: AviationArcGisLayer<T>,
            revision_changed_twice: Boolean
        ): AviationSourceResult<T> {
            val missing = expected_ids - returned_ids
            val unexpected = unexpected_ids + (returned_ids - expected_ids)
            val complete = !revision_changed &&
                !revision_changed_twice &&
                failure_reason == null &&
                missing.isEmpty() &&
                unexpected.isEmpty() &&
                duplicate_ids.isEmpty() &&
                invalid_ids.isEmpty() &&
                !exceeded_transfer_limit
            val stable_empty = complete && expected_ids.isEmpty()
            val availability = when {
                stable_empty -> AviationLayerAvailability.EMPTY
                records.isNotEmpty() -> AviationLayerAvailability.USABLE
                else -> AviationLayerAvailability.UNAVAILABLE
            }
            val completeness = when {
                stable_empty || complete -> AviationLayerCompleteness.COMPLETE
                records.isNotEmpty() -> AviationLayerCompleteness.PARTIAL
                else -> AviationLayerCompleteness.UNKNOWN
            }
            val freshness = if (availability == AviationLayerAvailability.UNAVAILABLE) {
                AviationLayerFreshness.STALE
            } else {
                AviationLayerFreshness.CURRENT
            }
            val reasons = buildList {
                failure_reason?.let(::add)
                if (revision_changed || revision_changed_twice) {
                    add("source revision changed or source proof metadata changed")
                }
                if (missing.isNotEmpty()) add("missing object IDs")
                if (unexpected.isNotEmpty()) add("unexpected object IDs")
                if (duplicate_ids.isNotEmpty()) add("duplicate object IDs")
                if (invalid_ids.isNotEmpty()) add("invalid records")
                if (exceeded_transfer_limit) add("transfer limit exceeded")
            }
            return AviationSourceResult(
                records = records,
                expected_object_ids = expected_ids,
                returned_object_ids = returned_ids,
                invalid_object_ids = invalid_ids,
                identity = AviationSourceIdentity(
                    provider = layer.source_provider,
                    service = layer.source_service,
                    layer = layer.source_layer,
                    requested_envelopes = envelopes.map {
                        AviationGeoBounds(it.min_lat, it.min_lon, it.max_lat, it.max_lon)
                    },
                    object_ids_captured_at_epoch_ms = captured_at_epoch_ms,
                    response_observed_at_epoch_ms = observed_at_epoch_ms,
                    final_source_url = final_url.toString(),
                    advertised_revision = advertised_revision
                ),
                health = AviationLayerHealth(availability, completeness, freshness),
                message = if (reasons.isEmpty()) "Complete FAA source response" else reasons.joinToString("; ")
            )
        }


        companion object {
            fun <T> failed_batch(
                envelopes: List<AviationLayerBounds>,
                records_by_id: Map<Long, T>,
                expected_ids: Set<Long>,
                invalid_ids: Set<Long>,
                unexpected_ids: Set<Long>,
                duplicate_ids: Set<Long>,
                exceeded_transfer_limit: Boolean,
                captured_at_epoch_ms: Long,
                observed_at_epoch_ms: Long,
                final_url: URL,
                advertised_revision: Long,
                reason: String
            ): ArcGisAttempt<T> = ArcGisAttempt(
                envelopes = envelopes,
                records = records_by_id.values.toList(),
                expected_ids = expected_ids,
                returned_ids = records_by_id.keys,
                invalid_ids = invalid_ids,
                unexpected_ids = unexpected_ids,
                duplicate_ids = duplicate_ids,
                exceeded_transfer_limit = exceeded_transfer_limit,
                captured_at_epoch_ms = captured_at_epoch_ms,
                observed_at_epoch_ms = observed_at_epoch_ms,
                final_url = final_url,
                advertised_revision = advertised_revision,
                revision_changed = false,
                failure_reason = reason
            )
        }
    }
}

class AviationNatSource(
    private val transport: AviationHttpTransport,
    private val source_url: URL = DEFAULT_NAT_SOURCE_URL,
    private val now_ms: () -> Long
) {
    fun fetch(): AviationSourceResult<AviationOceanicTrack> = fetch(should_continue = { true })

    fun fetch(should_continue: () -> Boolean): AviationSourceResult<AviationOceanicTrack> {
        if (!should_continue()) throw AviationFetchCancelledException()
        val response = try {
            transport.get(source_url)
        } catch (failure: AviationHttpRequestException) {
            if (!should_continue()) throw AviationFetchCancelledException()
            val observed_at = now_ms()
            return unavailable(
                observed_at,
                failure.final_url,
                failure.message ?: "NAT transport failed"
            )
        } catch (failure: IOException) {
            if (!should_continue()) throw AviationFetchCancelledException()
            val observed_at = now_ms()
            return unavailable(observed_at, source_url, failure.message ?: "NAT transport failed")
        }
        if (!should_continue()) throw AviationFetchCancelledException()
        val observed_at = now_ms()
        val identity = nat_identity(response.final_url, observed_at)
        if (response.status_code != HttpURLConnection.HTTP_OK) {
            return unavailable(observed_at, response.final_url, "NAT HTTP status ${response.status_code}")
        }
        val array = strict_json_array(response.body)
            ?: return unavailable(observed_at, response.final_url, "Malformed NAT JSON response")
        return AviationNatParser.parse(array, observed_at, identity)
    }

    private fun unavailable(
        observed_at: Long,
        final_url: URL,
        message: String
    ): AviationSourceResult<AviationOceanicTrack> = AviationSourceResult(
        records = emptyList(),
        expected_object_ids = null,
        returned_object_ids = emptySet(),
        invalid_object_ids = emptySet(),
        identity = nat_identity(final_url, observed_at),
        health = AviationLayerHealth(
            AviationLayerAvailability.UNAVAILABLE,
            AviationLayerCompleteness.UNKNOWN,
            AviationLayerFreshness.STALE
        ),
        message = message
    )

    private fun strict_json_array(body: String): JSONArray? {
        if ('\u0000' in body) return null
        return try {
            val tokener = JSONTokener(body)
            val value = tokener.nextValue()
            if (value !is JSONArray || tokener.nextClean() != '\u0000') null else value
        } catch (_: JSONException) {
            null
        }
    }

    companion object {
        val DEFAULT_NAT_SOURCE_URL = URL("https://nms.aim.faa.gov/datanat/nat.json")
    }
}

object AviationNatParser {
    internal fun parse(
        json: JSONArray,
        now_epoch_ms: Long,
        identity: AviationSourceIdentity
    ): AviationSourceResult<AviationOceanicTrack> {
        if (json.length() == 0) {
            return result(
                records = emptyList(),
                identity = identity,
                availability = AviationLayerAvailability.EMPTY,
                completeness = AviationLayerCompleteness.COMPLETE,
                freshness = AviationLayerFreshness.CURRENT,
                message = "No FAA NMS NAT records"
            )
        }

        val reasons = linkedSetOf<String>()
        val parts = mutableListOf<NatPartEvidence>()
        val records = mutableListOf<AviationOceanicTrack>()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index)
            if (item == null) {
                reasons += "malformed response member"
                continue
            }
            val part = parse_part(item, index, now_epoch_ms, reasons)
            parts += part
            records += part.tracks
        }

        validate_multipart(parts, reasons)
        records.groupBy { it.source_notam to it.designator }
            .values
            .filter { it.size > 1 && it.first().source_notam != null }
            .takeIf { it.isNotEmpty() }
            ?.let { reasons += "duplicate track designator" }

        if (records.isEmpty()) {
            return result(
                records = emptyList(),
                identity = identity,
                availability = AviationLayerAvailability.UNAVAILABLE,
                completeness = AviationLayerCompleteness.UNKNOWN,
                freshness = AviationLayerFreshness.STALE,
                message = (reasons + "no honest NAT track record").joinToString("; ")
            )
        }
        for (track in records) {
            if (
                track.temporal_state == AviationNatTemporalState.UNKNOWN ||
                track.waypoints.isEmpty() ||
                track.waypoints.any { it !is AviationNatWaypoint.Coordinate } ||
                track.drawable_segments.isEmpty()
            ) {
                reasons += "unresolved or incomplete track"
            }
        }
        return result(
            records = records,
            identity = identity,
            availability = AviationLayerAvailability.USABLE,
            completeness = if (reasons.isEmpty()) {
                AviationLayerCompleteness.COMPLETE
            } else {
                AviationLayerCompleteness.PARTIAL
            },
            freshness = AviationLayerFreshness.CURRENT,
            message = if (reasons.isEmpty()) {
                "Complete FAA NMS NAT response"
            } else {
                reasons.joinToString("; ")
            }
        )
    }

    private fun parse_part(
        item: JSONObject,
        source_index: Int,
        now_epoch_ms: Long,
        reasons: MutableSet<String>
    ): NatPartEvidence {
        val source_notam = item.strict_string("notam_number_formatted")
        val icao_id = item.strict_string("icao_id")
        val part_number = item.opt("part_no")
            .exact_aviation_object_id_or_null()
            ?.takeIf { it in 1..MAX_NAT_PARTS.toLong() }
            ?.toInt()
        val start_datetime = item.strict_string("start_datetime")
        val end_datetime = item.strict_string("end_datetime")
        val entry_datetime = item.strict_string("entry_datetime")
        val last_updated = item.strict_string("last_updated")
        val condition = item.strict_string("condition_message")
        val fields_valid = source_notam != null &&
            icao_id != null &&
            part_number != null &&
            start_datetime != null &&
            end_datetime != null &&
            entry_datetime != null &&
            last_updated != null &&
            condition != null
        if (!fields_valid) reasons += "malformed source field"

        val lines = condition?.split(Regex("\\r?\\n")) ?: emptyList()
        val first_nonblank = lines.firstOrNull { it.isNotBlank() }
        val header = first_nonblank?.trim()?.let(::parse_header)
        if (header == null) reasons += "multipart evidence incomplete"
        val time = parse_window(start_datetime, end_datetime, now_epoch_ms)
        if (time.temporal_state == AviationNatTemporalState.UNKNOWN) {
            reasons += "invalid NAT time window"
        }
        val tracks = mutableListOf<AviationOceanicTrack>()
        if (condition != null) {
            for (raw_line in lines) {
                val trimmed = raw_line.trim()
                if (trimmed.isEmpty()) continue
                val tokens = trimmed.split(WHITESPACE)
                val designator = tokens.firstOrNull()?.takeIf(TRACK_DESIGNATOR::matches) ?: continue
                val waypoints = tokens.drop(1).map(::parse_waypoint)
                val segments = drawable_segments(waypoints)
                tracks += AviationOceanicTrack(
                    designator = designator,
                    source_notam = source_notam,
                    icao_id = icao_id,
                    part_number = part_number,
                    declared_part_number = header?.part_number,
                    declared_part_count = header?.part_count,
                    start_datetime = start_datetime,
                    end_datetime = end_datetime,
                    entry_datetime = entry_datetime,
                    last_updated = last_updated,
                    raw_condition_message = condition,
                    raw_track_line = raw_line,
                    start_epoch_ms = time.start_epoch_ms,
                    end_epoch_ms = time.end_epoch_ms,
                    temporal_state = time.temporal_state,
                    waypoints = waypoints,
                    drawable_segments = segments
                )
            }
        }
        if (tracks.isEmpty()) reasons += "source part has no track record"
        return NatPartEvidence(
            source_index = source_index,
            source_notam = source_notam,
            icao_id = icao_id,
            part_number = part_number,
            declared_part_number = header?.part_number,
            declared_part_count = header?.part_count,
            start_datetime = start_datetime,
            end_datetime = end_datetime,
            fields_valid = fields_valid,
            tracks = tracks
        )
    }

    private fun validate_multipart(
        parts: List<NatPartEvidence>,
        reasons: MutableSet<String>
    ) {
        val groups = parts.groupBy { it.source_notam ?: "__missing_notam_${it.source_index}" }
        for (group in groups.values) {
            val declared_counts = group.mapNotNull(NatPartEvidence::declared_part_count).toSet()
            val declared_count = declared_counts.singleOrNull()
            val part_numbers = group.mapNotNull(NatPartEvidence::part_number)
            val header_numbers = group.mapNotNull(NatPartEvidence::declared_part_number)
            val complete_parts = declared_count != null &&
                group.all(NatPartEvidence::fields_valid) &&
                group.all { it.declared_part_count == declared_count } &&
                group.all { it.part_number == it.declared_part_number } &&
                part_numbers.size == group.size &&
                header_numbers.size == group.size &&
                part_numbers.size == part_numbers.toSet().size &&
                part_numbers.size == declared_count &&
                part_numbers.toSet() == (1..declared_count).toSet()
            val consistent_identity = group.map(NatPartEvidence::icao_id).toSet().size == 1 &&
                group.map(NatPartEvidence::start_datetime).toSet().size == 1 &&
                group.map(NatPartEvidence::end_datetime).toSet().size == 1
            if (!complete_parts || !consistent_identity) {
                reasons += "multipart evidence incomplete or conflicting"
            }
        }
    }

    private fun parse_header(line: String): NatHeader? {
        val match = NAT_HEADER.find(line) ?: return null
        val part = match.groupValues[1].toLongOrNull()
        val count = match.groupValues[2].toLongOrNull()
        if (part == null || count == null || part !in 1..MAX_NAT_PARTS || count !in 1..MAX_NAT_PARTS) {
            return null
        }
        if (part > count) return null
        return NatHeader(part.toInt(), count.toInt())
    }

    private fun parse_window(
        start_raw: String?,
        end_raw: String?,
        now_epoch_ms: Long
    ): NatWindow {
        val start = parse_utc(start_raw)
        val end = parse_utc(end_raw)
        if (start == null || end == null || !start.isBefore(end)) {
            return NatWindow(null, null, AviationNatTemporalState.UNKNOWN)
        }
        val start_ms = try {
            start.toEpochMilli()
        } catch (_: ArithmeticException) {
            return NatWindow(null, null, AviationNatTemporalState.UNKNOWN)
        }
        val end_ms = try {
            end.toEpochMilli()
        } catch (_: ArithmeticException) {
            return NatWindow(null, null, AviationNatTemporalState.UNKNOWN)
        }
        val state = when {
            now_epoch_ms < start_ms -> AviationNatTemporalState.UPCOMING
            now_epoch_ms >= end_ms -> AviationNatTemporalState.EXPIRED
            else -> AviationNatTemporalState.ACTIVE
        }
        return NatWindow(start_ms, end_ms, state)
    }

    private fun parse_utc(raw: String?): Instant? {
        if (raw == null || !raw.endsWith('Z')) return null
        return try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parse_waypoint(raw: String): AviationNatWaypoint {
        val coordinate = parse_coordinate(raw)
        if (coordinate != null) return AviationNatWaypoint.Coordinate(raw, coordinate)
        val normalized = raw.uppercase().trimEnd(',', '.', ';')
        return if (NAMED_FIX.matches(normalized)) {
            AviationNatWaypoint.NamedFix(raw)
        } else {
            AviationNatWaypoint.Unrecognized(raw)
        }
    }

    private fun parse_coordinate(raw: String): AviationLayerPoint? {
        if (raw.count { it == '/' } != 1) return null
        val latitude_token = raw.substringBefore('/')
        val longitude_token = raw.substringAfter('/')
        if (!latitude_token.all { it in '0'..'9' } || !longitude_token.all { it in '0'..'9' }) return null
        val latitude = parse_coordinate_side(latitude_token, degree_width = 2, max_degrees = 90)
            ?: return null
        val longitude = parse_coordinate_side(longitude_token, degree_width = 2, max_degrees = 180)
            ?: return null
        return AviationLayerPoint(latitude, -longitude)
    }

    private fun parse_coordinate_side(
        raw: String,
        degree_width: Int,
        max_degrees: Int
    ): Double? {
        if (raw.length != degree_width && raw.length != degree_width + 2) return null
        val degrees = raw.take(degree_width).toIntOrNull() ?: return null
        val minutes = raw.drop(degree_width).takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0
        if (degrees !in 0..max_degrees || minutes !in 0..59) return null
        if (degrees == max_degrees && minutes != 0) return null
        return degrees + minutes / 60.0
    }

    private fun drawable_segments(
        waypoints: List<AviationNatWaypoint>
    ): List<List<AviationLayerPoint>> {
        val segments = mutableListOf<List<AviationLayerPoint>>()
        val run = mutableListOf<AviationLayerPoint>()
        fun flush() {
            if (run.size >= 2) segments += run.toList()
            run.clear()
        }
        for (waypoint in waypoints) {
            if (waypoint is AviationNatWaypoint.Coordinate) {
                run += waypoint.point
            } else {
                flush()
            }
        }
        flush()
        return segments
    }

    private fun result(
        records: List<AviationOceanicTrack>,
        identity: AviationSourceIdentity,
        availability: AviationLayerAvailability,
        completeness: AviationLayerCompleteness,
        freshness: AviationLayerFreshness,
        message: String
    ): AviationSourceResult<AviationOceanicTrack> = AviationSourceResult(
        records = records,
        expected_object_ids = null,
        returned_object_ids = emptySet(),
        invalid_object_ids = emptySet(),
        identity = identity,
        health = AviationLayerHealth(availability, completeness, freshness),
        message = message
    )

    private fun JSONObject.strict_string(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return (opt(key) as? String)?.takeIf { it.isNotBlank() }
    }

    private data class NatPartEvidence(
        val source_index: Int,
        val source_notam: String?,
        val icao_id: String?,
        val part_number: Int?,
        val declared_part_number: Int?,
        val declared_part_count: Int?,
        val start_datetime: String?,
        val end_datetime: String?,
        val fields_valid: Boolean,
        val tracks: List<AviationOceanicTrack>
    )

    private data class NatHeader(val part_number: Int, val part_count: Int)
    private data class NatWindow(
        val start_epoch_ms: Long?,
        val end_epoch_ms: Long?,
        val temporal_state: AviationNatTemporalState
    )

    private const val MAX_NAT_PARTS = 1_000
    private val NAT_HEADER = Regex("^NAT-(\\d+)/(\\d+)(?:\\s|$)")
    private val TRACK_DESIGNATOR = Regex("^[A-Z]$")
    private val NAMED_FIX = Regex("^[A-Z][A-Z0-9]{1,7}$")
    private val WHITESPACE = Regex("\\s+")
}

object AviationNatRenderPolicy {
    fun select_active_tracks(
        tracks: List<AviationOceanicTrack>,
        visible_bounds: AviationGeoBounds,
        limit: Int,
        now_epoch_ms: Long
    ): List<AviationOceanicTrack> {
        require(limit >= 0) { "NAT render limit cannot be negative" }
        val selected = tracks.asSequence()
            .filter { track ->
                val start_epoch_ms = track.start_epoch_ms
                val end_epoch_ms = track.end_epoch_ms
                start_epoch_ms != null &&
                    end_epoch_ms != null &&
                    start_epoch_ms < end_epoch_ms &&
                    now_epoch_ms >= start_epoch_ms &&
                    now_epoch_ms < end_epoch_ms
            }
            .filter { it.drawable_segments.isNotEmpty() }
            .filter { it.bounds?.intersects(visible_bounds) == true }
            .take(limit)
            .toList()
        return Collections.unmodifiableList(selected)
    }

    internal fun temporal_state_token(
        tracks: List<AviationOceanicTrack>,
        now_epoch_ms: Long
    ): Long {
        var passed_boundary_count = 0L
        for (track in tracks) {
            val start_epoch_ms = track.start_epoch_ms ?: continue
            val end_epoch_ms = track.end_epoch_ms ?: continue
            if (start_epoch_ms >= end_epoch_ms) continue
            if (now_epoch_ms >= start_epoch_ms) passed_boundary_count += 1L
            if (now_epoch_ms >= end_epoch_ms) passed_boundary_count += 1L
        }
        return passed_boundary_count
    }
}

private fun nat_identity(final_url: URL, observed_at: Long): AviationSourceIdentity =
    AviationSourceIdentity(
        provider = "FAA",
        service = "NMS North Atlantic Tracks",
        layer = "NAT",
        requested_envelopes = emptyList(),
        object_ids_captured_at_epoch_ms = null,
        response_observed_at_epoch_ms = observed_at,
        final_source_url = final_url.toString(),
        advertised_revision = null
    )

object AviationGeoJsonParser {
    fun parse_airspaces(json: JSONObject): AviationParsedBatch<AviationAirspaceFeature> {
        val records = mutableListOf<AviationAirspaceFeature>()
        val returned_object_ids = linkedSetOf<Long>()
        val invalid_object_ids = linkedSetOf<Long>()
        val features = json.optJSONArray("features") ?: JSONArray()

        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val object_id = properties.object_id_or_null() ?: continue
            val record = parse_airspace_feature(feature, properties, object_id)
            if (record == null) {
                invalid_object_ids += object_id
            } else {
                records += record
                returned_object_ids += object_id
            }
        }

        return AviationParsedBatch(records, returned_object_ids, invalid_object_ids)
    }

    fun parse_airports(json: JSONObject): AviationParsedBatch<AviationAirportFeature> {
        val records = mutableListOf<AviationAirportFeature>()
        val returned_object_ids = linkedSetOf<Long>()
        val invalid_object_ids = linkedSetOf<Long>()
        val features = json.optJSONArray("features") ?: JSONArray()

        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val object_id = properties.object_id_or_null() ?: continue
            val record = parse_airport_feature(feature, properties, object_id)
            if (record == null) {
                invalid_object_ids += object_id
            } else {
                records += record
                returned_object_ids += object_id
            }
        }

        return AviationParsedBatch(records, returned_object_ids, invalid_object_ids)
    }

    private fun parse_airspace_feature(
        feature: JSONObject,
        properties: JSONObject,
        object_id: Long
    ): AviationAirspaceFeature? {
        val geometry = parse_airspace_geometry(feature.optJSONObject("geometry")) ?: return null
        val type = properties.clean_string("TYPE_CODE")
            ?: properties.clean_string("LOCAL_TYPE")
            ?: return null
        val name = properties.clean_string("NAME")
            ?: properties.clean_string("IDENT")
            ?: return null
        return AviationAirspaceFeature(
            object_id = object_id,
            name = name,
            type = type,
            lower_limit = altitude_label(properties, "LOWER"),
            upper_limit = altitude_label(properties, "UPPER"),
            schedule = properties.clean_string("TIMESOFUSE"),
            city = properties.clean_string("CITY"),
            state = properties.clean_string("STATE"),
            geometry = geometry,
            bounds = geometry.all_rings.flatten().to_bounds()
        )
    }

    private fun parse_airport_feature(
        feature: JSONObject,
        properties: JSONObject,
        object_id: Long
    ): AviationAirportFeature? {
        val geometry = feature.optJSONObject("geometry") ?: return null
        if (geometry.optString("type") != "Point") return null
        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        val lon = coordinates.coordinate_number_or_null(0)?.takeIf { it in -180.0..180.0 }
            ?: return null
        val lat = coordinates.coordinate_number_or_null(1)?.takeIf { it in -90.0..90.0 }
            ?: return null
        val ident = properties.clean_string("ICAO_ID")
            ?: properties.clean_string("IDENT")
            ?: return null
        val type = properties.clean_string("TYPE_CODE") ?: return null
        val military_code = properties.clean_string("MIL_CODE") ?: return null
        return AviationAirportFeature(
            object_id = object_id,
            ident = ident,
            name = properties.clean_string("NAME") ?: ident,
            type = type,
            military_code = military_code,
            lat = lat,
            lon = lon
        )
    }

    private fun parse_airspace_geometry(geometry: JSONObject?): AviationMultiPolygon? {
        geometry ?: return null
        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        val polygons = when (geometry.optString("type")) {
            "Polygon" -> listOf(parse_polygon(coordinates) ?: return null)
            "MultiPolygon" -> {
                if (coordinates.length() == 0) return null
                buildList {
                    for (index in 0 until coordinates.length()) {
                        val polygon_coordinates = coordinates.optJSONArray(index) ?: return null
                        add(parse_polygon(polygon_coordinates) ?: return null)
                    }
                }
            }

            else -> return null
        }
        return AviationMultiPolygon(polygons)
    }

    private fun parse_polygon(coordinates: JSONArray): AviationPolygon? {
        if (coordinates.length() == 0) return null
        val shell = parse_ring(coordinates.optJSONArray(0)) ?: return null
        val holes = buildList {
            for (index in 1 until coordinates.length()) {
                add(parse_ring(coordinates.optJSONArray(index)) ?: return null)
            }
        }
        return AviationPolygon(shell, holes)
    }

    private fun parse_ring(coordinates: JSONArray?): List<AviationLayerPoint>? {
        coordinates ?: return null
        if (coordinates.length() < MIN_RING_POINTS) return null
        val points = buildList {
            for (index in 0 until coordinates.length()) {
                val coordinate = coordinates.optJSONArray(index) ?: return null
                val lon = coordinate.coordinate_number_or_null(0)
                    ?.takeIf { it in -180.0..180.0 }
                    ?: return null
                val lat = coordinate.coordinate_number_or_null(1)
                    ?.takeIf { it in -90.0..90.0 }
                    ?: return null
                add(AviationLayerPoint(lat, lon))
            }
        }
        return points.takeIf { it.first() == it.last() }
    }

    private fun JSONObject.object_id_or_null(): Long? {
        if (!has("OBJECTID") || isNull("OBJECTID")) return null
        return opt("OBJECTID").exact_aviation_object_id_or_null()
    }

    private fun JSONObject.clean_string(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return (opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.number_or_null(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }?.takeIf(Double::isFinite)
    }

    private fun JSONArray.coordinate_number_or_null(index: Int): Double? {
        if (index >= length() || isNull(index)) return null
        return (opt(index) as? Number)?.toDouble()?.takeIf(Double::isFinite)
    }

    private fun altitude_label(properties: JSONObject, prefix: String): String? {
        val code = properties.clean_string("${prefix}_CODE")
        val value = properties.number_or_null("${prefix}_VAL")
            ?.takeIf { it > MISSING_ALTITUDE_SENTINEL }
        val unit = properties.clean_string("${prefix}_UOM")

        if (code.equals(UNLIMITED_ALTITUDE_CODE, ignoreCase = true)) return code
        if (code.equals(SURFACE_ALTITUDE_CODE, ignoreCase = true) && value == 0.0) return code
        if (value == null) return code?.let { "$it (value unavailable)" }

        val value_label = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        return when {
            unit == null && code == null -> "$value_label (unit/reference unavailable)"
            unit == null -> "$value_label $code (unit unavailable)"
            code == null -> "$value_label $unit (reference unavailable)"
            else -> "$value_label $unit $code"
        }
    }

    private const val MIN_RING_POINTS = 4
    private const val MISSING_ALTITUDE_SENTINEL = -9000.0
    private const val SURFACE_ALTITUDE_CODE = "SFC"
    private const val UNLIMITED_ALTITUDE_CODE = "UNLTD"
}

internal fun Any?.exact_aviation_object_id_or_null(): Long? = when (this) {
    is Byte -> toLong()
    is Short -> toLong()
    is Int -> toLong()
    is Long -> this
    is BigInteger -> takeIf { it >= LONG_MIN_BIG_INTEGER && it <= LONG_MAX_BIG_INTEGER }?.toLong()
    is BigDecimal -> exact_long_or_null()
    is Float -> toDouble().safe_floating_long_or_null()
    is Double -> safe_floating_long_or_null()
    else -> null
}

private fun BigDecimal.exact_long_or_null(): Long? = try {
    longValueExact()
} catch (_: ArithmeticException) {
    null
}

private fun Double.safe_floating_long_or_null(): Long? {
    if (!isFinite() || this % 1.0 != 0.0 || this !in -MAX_SAFE_FLOATING_ID..MAX_SAFE_FLOATING_ID) {
        return null
    }
    return toLong()
}

private const val MAX_SAFE_FLOATING_ID = 9_007_199_254_740_991.0
private val LONG_MIN_BIG_INTEGER: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX_BIG_INTEGER: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
