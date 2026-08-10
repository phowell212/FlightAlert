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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.TreeSet
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

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
            final_url = after_response.final_url,
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
        identity = AviationSourceIdentity(
            provider = layer.source_provider,
            service = layer.source_service,
            layer = layer.source_layer,
            requested_envelopes = envelopes.map {
                AviationGeoBounds(it.min_lat, it.min_lon, it.max_lat, it.max_lon)
            },
            object_ids_captured_at_epoch_ms = null,
            response_observed_at_epoch_ms = now_ms(),
            final_source_url = failure.final_url ?: URL(layer.metadata_url),
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
                identity = AviationSourceIdentity(
                    provider = layer.source_provider,
                    service = layer.source_service,
                    layer = layer.source_layer,
                    requested_envelopes = envelopes.map {
                        AviationGeoBounds(it.min_lat, it.min_lon, it.max_lat, it.max_lon)
                    },
                    object_ids_captured_at_epoch_ms = captured_at_epoch_ms,
                    response_observed_at_epoch_ms = observed_at_epoch_ms,
                    final_source_url = final_url,
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
