package com.flightalert.ui.map.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flightalert.data.AircraftDetails
import com.flightalert.data.airplaneslive.AirplanesLiveHttp
import com.flightalert.ui.map.Aircraft
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

// Finds aircraft photos in an honesty ladder: exact first, labeled representative next, investigable last.
class AircraftPhotoFetcher(private val user_agent: String) {
    private val photo_cache = object : LinkedHashMap<String, CachedPhotoResult>(
        PHOTO_CACHE_MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPhotoResult>): Boolean {
            return size > PHOTO_CACHE_MAX_ENTRIES
        }
    }

    // Public photo lookup tries exact aircraft sources before any labeled fallback.
    fun fetch(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult {
        fetch_exact_aircraft_photo(aircraft, details)?.let { return it }
        return fetch_fallback_photo(aircraft, details)
    }

    // Exact photos require an aircraft identifier or matching registration from documented photo sources.
    fun fetch_exact_aircraft_photo(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult.Found? {
        val registration = normalized_registration(details.registration ?: aircraft.registration)
        val cache_key = exact_photo_cache_key(aircraft, details, registration)
        cached_photo(cache_key)?.let { return it }

        fun found(photo: AircraftPhotoResult.Found): AircraftPhotoResult.Found {
            cache_photo(cache_key, photo)
            return photo
        }

        fetch_adsb_db_photo_candidates(aircraft.icao24, registration).forEach { candidate ->
            fetch_bitmap(candidate.image_url, candidate.page_url)?.let { bitmap ->
                return found(found_exact_photo(bitmap, candidate))
            }
        }
        fetch_planespotters_selected_candidates(aircraft.icao24, registration, details.type_code ?: aircraft.type_code).forEach { candidate ->
            fetch_bitmap(candidate.image_url, candidate.page_url)?.let { bitmap ->
                return found(found_exact_photo(bitmap, candidate))
            }
        }
        val exact_sources = listOfNotNull(
            "https://api.planespotters.net/pub/photos/hex/${aircraft.icao24.trim()}",
            (details.registration ?: aircraft.registration)?.let { "https://api.planespotters.net/pub/photos/reg/${it.trim()}" }
        )
        exact_sources.forEach { api_url ->
            fetch_planespotters_photo_candidate(api_url)?.let { candidate ->
                fetch_bitmap(candidate.image_url, candidate.page_url)?.let { bitmap ->
                    return found(found_exact_photo(bitmap, candidate))
                }
            }
        }
        if (registration != null) {
            fetch_jet_photos_exact_candidates(registration).forEach { candidate ->
                fetch_bitmap(candidate.image_url, candidate.page_url)?.let { bitmap ->
                    return found(found_exact_photo(bitmap, candidate))
                }
            }
        }
        val hex_image_url = "https://hexdb.io/hex-image-thumb?hex=${aircraft.icao24.trim()}"
        fetch_bitmap(hex_image_url)?.let { bitmap ->
            return found(
                found_exact_photo(
                    bitmap,
                    ExactPhotoCandidate(
                        image_url = hex_image_url,
                        source_name = "HexDB",
                        page_url = hex_image_url,
                        note = "Exact aircraft photo from HexDB"
                    )
                )
            )
        }
        return null
    }

    // Fallback photos must stay labeled as representative, verified search, or investigable search.
    fun fetch_fallback_photo(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult {
        val cache_key = fallback_photo_cache_key(aircraft, details)
        cached_photo(cache_key)?.let { return it }
        val photo = fetch_representative_photo(details)
            ?: fetch_verified_generic_search_photo(aircraft, details)
            ?: fetch_investigable_search_photo(aircraft, details)
            ?: AircraftPhotoResult.Unavailable("Exact, representative, and search photos unavailable")
        if (photo is AircraftPhotoResult.Found) cache_photo(cache_key, photo)
        return photo
    }

    fun fetch_gallery(
        aircraft: Aircraft,
        details: AircraftDetails
    ): List<AircraftPhotoGalleryItem> {
        val items = mutableListOf<AircraftPhotoGalleryItem>()
        fetch_exact_aircraft_photo(aircraft, details)?.let {
            items += it.to_gallery_item("Exact exterior", AircraftPhotoViewType.EXTERIOR)
        }
        items += fetch_representative_gallery_items(aircraft, details)
        return items.distinctBy { it.evidence?.image_url ?: "${it.title}:${it.caption}" }.take(MAX_GALLERY_ITEMS)
    }

    // ADSBdb can return direct exact-photo URLs keyed by ICAO or registration.
    private fun fetch_adsb_db_photo_candidates(icao24: String, registration: String?): List<ExactPhotoCandidate> {
        val keys = listOfNotNull(icao24.trim().takeIf { it.isNotBlank() }, registration).distinct()
        return keys.flatMap { key ->
            val encoded = URLEncoder.encode(key, "UTF-8")
            val api_url = "https://api.adsbdb.com/v0/aircraft/$encoded"
            val json = fetch_json_object(api_url) ?: return@flatMap emptyList()
            val aircraft = json.optJSONObject("response")?.optJSONObject("aircraft") ?: return@flatMap emptyList()
            listOfNotNull(
                aircraft.optString("url_photo").takeIf { it.startsWith("https://", ignoreCase = true) },
                aircraft.optString("url_photo_thumbnail").takeIf { it.startsWith("https://", ignoreCase = true) }
            ).filter { url -> is_exterior_candidate_text(url) }
                .map { url ->
                    ExactPhotoCandidate(
                        image_url = url,
                        source_name = "ADSBdb",
                        page_url = api_url,
                        note = "Exact aircraft photo from ADSBdb"
                    )
                }
        }.distinct()
    }

    // JetPhotos results are accepted only when the returned registration matches exactly.
    private fun fetch_jet_photos_exact_candidates(registration: String): List<ExactPhotoCandidate> {
        val encoded = URLEncoder.encode(registration, "UTF-8")
        val api_url = "https://jp.rewis.workers.dev/?page=1&sort-order=1&keywords=$encoded&keywords-type=registration&keywords-contain=0"
        val photos = fetch_json_object(api_url)?.optJSONArray("photos") ?: return emptyList()
        val candidates = mutableListOf<ExactPhotoCandidate>()
        for (index in 0 until photos.length()) {
            val item = photos.optJSONObject(index) ?: continue
            val found_registration = normalized_registration(item.optString("registration"))
            if (found_registration != registration) continue
            if (!is_exterior_candidate_text(item.toString())) continue
            listOf(item.optString("imageUrl"), item.optString("thumbnailUrl")).forEach { url ->
                if (url.startsWith("https://", ignoreCase = true) && IMAGE_URL_PATTERN.containsMatchIn(url)) {
                    candidates += ExactPhotoCandidate(
                        image_url = url,
                        source_name = "JetPhotos",
                        page_url = item.optString("link").takeIf { it.startsWith("https://", ignoreCase = true) } ?: api_url,
                        note = "Exact aircraft photo from JetPhotos; registration verified"
                    )
                }
            }
        }
        return candidates.distinctBy { it.image_url }
    }

    // PlaneSpotters APIs return nested JSON, so this walks the response for the first allowed image URL.
    private fun fetch_planespotters_photo_candidate(api_url: String): ExactPhotoCandidate? {
        val safe_url = https_url(api_url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safe_url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", user_agent)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            exterior_image_urls(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
                .firstOrNull()
                ?.let { image_url ->
                    ExactPhotoCandidate(
                        image_url = image_url,
                        source_name = "PlaneSpotters",
                        page_url = api_url,
                        note = "Exact aircraft photo from PlaneSpotters"
                    )
                }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // This mirrors tar1090's selected-aircraft photo query using direct HTTP.
    private fun fetch_planespotters_selected_candidates(
        icao24: String,
        registration: String?,
        type_code: String?
    ): List<ExactPhotoCandidate> {
        val hex = icao24.trim().trimStart('~').uppercase(Locale.US).takeIf { MODE_S_HEX.matches(it) } ?: return emptyList()
        val url = StringBuilder("https://api.planespotters.net/pub/photos/hex/$hex")
        val params = mutableListOf<String>()
        registration?.takeIf { it.isNotBlank() }?.let { params += "reg=${URLEncoder.encode(it, "UTF-8")}" }
        type_code?.trim()?.takeIf { it.isNotBlank() }?.let { params += "icaoType=${URLEncoder.encode(it, "UTF-8")}" }
        if (params.isNotEmpty()) url.append("?").append(params.joinToString("&"))
        val json = fetch_json_object(url.toString()) ?: return emptyList()
        val photos = json.optJSONArray("photos") ?: json.optJSONArray("images") ?: return emptyList()
        val candidates = mutableListOf<ExactPhotoCandidate>()
            for (index in 0 until photos.length()) {
                val photo = photos.optJSONObject(index) ?: continue
                if (!is_exterior_candidate_text(photo.toString())) continue
                val image_url = photo.optJSONObject("thumbnail")?.optString("src")?.takeIf { it.startsWith("https://", ignoreCase = true) }
                    ?: photo.optString("thumbnail").takeIf { it.startsWith("https://", ignoreCase = true) }
                    ?: photo.optString("image").takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: photo.optString("imageUrl").takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: continue
            if (!is_allowed_https_image_url(image_url)) continue
            val page_url = photo.optString("link").takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: photo.optString("url").takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: url.toString()
            val photographer = photo.optString("photographer").trim().ifEmpty { photo.optString("user").trim() }
            candidates += ExactPhotoCandidate(
                image_url = image_url,
                source_name = "PlaneSpotters",
                page_url = page_url,
                note = listOfNotNull(
                    "Exact aircraft photo from PlaneSpotters",
                    photographer.takeIf { it.isNotBlank() }?.let { "image credit $it" }
                ).joinToString("; ")
            )
        }
        return candidates.distinctBy { it.image_url }
    }

    // Representative photos are only same make/model examples and are labeled as not this exact aircraft.
    private fun fetch_representative_photo(details: AircraftDetails): AircraftPhotoResult.Found? {
        AircraftPhotoCatalog.representative_model_names(details).forEach { model ->
            val queries = AircraftPhotoCatalog.representative_photo_queries(details, model)
            queries.forEach { query ->
                val candidates = fetch_wikimedia_search_image_candidates(query) +
                    fetch_openverse_image_candidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
                candidates
                    .as_exterior_candidates()
                    .take(MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY)
                    .forEach { candidate ->
                        fetch_bitmap(candidate.image_url, candidate.page_url)?.let { bitmap ->
                            return AircraftPhotoResult.Found(
                                bitmap,
                                "Representative exterior $model photo from ${candidate.source_name}; not this exact aircraft",
                                candidate.gallery_evidence(query),
                                PhotoQuality.REPRESENTATIVE
                            )
                        }
                    }
            }
        }
        return null
    }

    // Generic search results must contain proof terms before they can be shown as verified.
    private fun fetch_verified_generic_search_photo(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult.Found? {
        val registration = normalized_registration(details.registration ?: aircraft.registration)
        val exact_terms = listOfNotNull(registration, aircraft.icao24.takeIf { it.isNotBlank() })
        AircraftPhotoCatalog.exact_photo_queries(registration, aircraft.icao24).forEach { query ->
            val candidates = fetch_wikimedia_search_image_candidates(query) +
                fetch_openverse_image_candidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
            candidates.as_exterior_candidates().forEach { candidate ->
                verified_search_photo(
                    candidate = candidate,
                    query = query,
                    note = "Verified exact-aircraft search result for ${registration ?: aircraft.icao24.uppercase(Locale.US)}",
                    verification_terms = exact_terms,
                    quality = PhotoQuality.EXACT
                )?.let { return it }
            }
        }

        AircraftPhotoCatalog.representative_model_names(details).forEach { model ->
            val verification_terms = AircraftPhotoCatalog.photo_verification_terms(details, model)
            AircraftPhotoCatalog.representative_photo_queries(details, model).forEach { query ->
                val candidates = fetch_wikimedia_search_image_candidates(query) +
                    fetch_openverse_image_candidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
                candidates.as_exterior_candidates().forEach { candidate ->
                    verified_search_photo(
                        candidate = candidate,
                        query = query,
                        note = "Verified search result for $model; not this exact aircraft",
                        verification_terms = verification_terms,
                        quality = PhotoQuality.REPRESENTATIVE
                    )?.let { return it }
                }
            }
        }
        return null
    }

    // Investigable photos are a last resort and always carry source evidence for the user to inspect.
    private fun fetch_investigable_search_photo(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult.Found? {
        val registration = normalized_registration(details.registration ?: aircraft.registration)
        val exact_queries = AircraftPhotoCatalog.exact_photo_queries(registration, aircraft.icao24)
        val representative_queries = AircraftPhotoCatalog.representative_model_names(details)
            .flatMap { AircraftPhotoCatalog.representative_photo_queries(details, it) }
        (exact_queries + representative_queries).distinct().forEach { query ->
            val candidates = fetch_wikimedia_search_image_candidates(query) +
                fetch_openverse_image_candidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
            candidates.as_exterior_candidates().forEach { candidate ->
                val bitmap = fetch_bitmap(candidate.image_url, candidate.page_url) ?: return@forEach
                val evidence = PhotoEvidence(
                    source_name = candidate.source_name,
                    image_url = candidate.image_url,
                    page_url = candidate.page_url,
                    search_query = query,
                    quote = candidate.investigation_text(),
                    matched_terms = emptyList()
                )
                return AircraftPhotoResult.Found(
                    bitmap,
                    "Unverified search result; tap photo to inspect source",
                    evidence,
                    PhotoQuality.INVESTIGABLE
                )
            }
        }
        return null
    }

    private fun fetch_representative_gallery_items(
        aircraft: Aircraft,
        details: AircraftDetails
    ): List<AircraftPhotoGalleryItem> {
        val items = mutableListOf<AircraftPhotoGalleryItem>()
        val models = AircraftPhotoCatalog.representative_model_names(details)
        models.take(3).forEach { model ->
            items += fetch_gallery_item_for_queries(
                queries = AircraftPhotoCatalog.representative_photo_queries(details, model),
                title = "Representative exterior",
                caption = "Same make/model family: $model; not this exact aircraft",
                view_type = AircraftPhotoViewType.EXTERIOR,
                quality = PhotoQuality.REPRESENTATIVE
            )
            items += fetch_gallery_item_for_queries(
                queries = listOf("\"$model\" cockpit", "$model cockpit"),
                title = "Representative cockpit",
                caption = "Representative cockpit view for $model; not this exact aircraft",
                view_type = AircraftPhotoViewType.COCKPIT,
                quality = PhotoQuality.REPRESENTATIVE
            )
            items += fetch_gallery_item_for_queries(
                queries = listOf("\"$model\" cabin interior", "$model cabin interior", "$model interior"),
                title = "Representative interior",
                caption = "Representative interior/cabin view for $model; not this exact aircraft",
                view_type = AircraftPhotoViewType.INTERIOR,
                quality = PhotoQuality.REPRESENTATIVE
            )
        }
        if (items.isEmpty() && aircraft.icao24.isNotBlank()) {
            fetch_investigable_search_photo(aircraft, details)?.let {
                items += it.to_gallery_item("Investigable photo", AircraftPhotoViewType.EXTERIOR)
            }
        }
        return items
    }

    private fun fetch_gallery_item_for_queries(
        queries: List<String>,
        title: String,
        caption: String,
        view_type: AircraftPhotoViewType,
        quality: PhotoQuality
    ): List<AircraftPhotoGalleryItem> {
        queries.distinct().forEach { query ->
            val candidates = fetch_wikimedia_search_image_candidates(query) +
                fetch_openverse_image_candidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
            candidates
                .filter { candidate -> candidate.matches_gallery_view_type(view_type) }
                .distinctBy { it.image_url }
                .take(MAX_GALLERY_CANDIDATES_PER_QUERY)
                .forEach { candidate ->
                    val bitmap = fetch_bitmap(candidate.image_url, candidate.page_url) ?: return@forEach
                    return listOf(
                        AircraftPhotoGalleryItem(
                            bitmap = bitmap,
                            title = title,
                            caption = caption,
                            evidence = candidate.gallery_evidence(query),
                            quality = quality,
                            view_type = view_type
                        )
                    )
                }
        }
        return emptyList()
    }

    // A search candidate becomes usable only after the source page or metadata actually mentions the verification terms.
    private fun verified_search_photo(
        candidate: SearchImageCandidate,
        query: String,
        note: String,
        verification_terms: List<String>,
        quality: PhotoQuality
    ): AircraftPhotoResult.Found? {
        val quote = fetch_verification_quote(candidate.page_url, verification_terms)
            ?: candidate.verification_text?.let { quote_from_text(it, verification_terms) }
            ?: return null
        val bitmap = fetch_bitmap(candidate.image_url, candidate.page_url) ?: return null
        val evidence = PhotoEvidence(
            source_name = candidate.source_name,
            image_url = candidate.image_url,
            page_url = candidate.page_url,
            search_query = query,
            quote = quote.text,
            matched_terms = quote.matched_terms
        )
        return AircraftPhotoResult.Found(
            bitmap,
            note,
            evidence,
            quality
        )
    }

    // Build the short proof text shown for user-investigable images.
    private fun SearchImageCandidate.investigation_text(): String {
        val title_text = title.takeIf { it.isNotBlank() }?.let { "Title: $it" }
        val metadata_text = verification_text?.takeIf { it.isNotBlank() }?.let { "Metadata: $it" }
        return listOfNotNull(title_text, metadata_text, "Source page: $page_url")
            .joinToString("  ")
    }

    private fun found_exact_photo(
        bitmap: Bitmap,
        candidate: ExactPhotoCandidate
    ): AircraftPhotoResult.Found {
        return AircraftPhotoResult.Found(
            bitmap = bitmap,
            note = candidate.note,
            evidence = PhotoEvidence(
                source_name = candidate.source_name,
                image_url = candidate.image_url,
                page_url = candidate.page_url,
                search_query = "Exact aircraft photo lookup",
                quote = candidate.note,
                matched_terms = emptyList()
            ),
            quality = PhotoQuality.EXACT
        )
    }

    private fun AircraftPhotoResult.Found.to_gallery_item(
        title: String,
        view_type: AircraftPhotoViewType
    ): AircraftPhotoGalleryItem {
        return AircraftPhotoGalleryItem(
            bitmap = bitmap,
            title = title,
            caption = note,
            evidence = evidence,
            quality = quality,
            view_type = view_type
        )
    }

    private fun SearchImageCandidate.gallery_evidence(query: String): PhotoEvidence {
        return PhotoEvidence(
            source_name = source_name,
            image_url = image_url,
            page_url = page_url,
            search_query = query,
            quote = investigation_text(),
            matched_terms = emptyList()
        )
    }

    private fun SearchImageCandidate.is_main_exterior_candidate(): Boolean {
        val text = "$title ${verification_text.orEmpty()}"
        return is_exterior_candidate_text(text)
    }

    private fun List<SearchImageCandidate>.as_exterior_candidates(): List<SearchImageCandidate> {
        return distinctBy { it.image_url }
            .filter { it.is_main_exterior_candidate() }
            .sortedByDescending { it.exterior_score() }
    }

    private fun SearchImageCandidate.exterior_score(): Int {
        val text = "$title ${verification_text.orEmpty()} $image_url".lowercase(Locale.US)
        var score = 0
        EXTERIOR_PREFERRED_TEXT.findAll(text).forEach { score += 4 }
        if (AIRCRAFT_TEXT.containsMatchIn(text)) score += 2
        if (DISALLOWED_MAIN_PHOTO_TEXT.containsMatchIn(text)) score -= 100
        return score
    }

    private fun SearchImageCandidate.matches_gallery_view_type(view_type: AircraftPhotoViewType): Boolean {
        val text = "$title ${verification_text.orEmpty()}"
        return when (view_type) {
            AircraftPhotoViewType.EXTERIOR -> !DISALLOWED_MAIN_PHOTO_TEXT.containsMatchIn(text)
            AircraftPhotoViewType.COCKPIT -> COCKPIT_TEXT.containsMatchIn(text)
            AircraftPhotoViewType.CABIN,
            AircraftPhotoViewType.INTERIOR -> INTERIOR_TEXT.containsMatchIn(text)
        }
    }

    // Openverse candidates stay candidates until proof terms are found elsewhere.
    private fun fetch_openverse_image_candidates(query: String): List<SearchImageCandidate> {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val api_url = "https://api.openverse.org/v1/images/?format=json&page_size=12&mature=false&q=$encoded"
            connection = (URL(api_url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 9000
                requestMethod = "GET"
                setRequestProperty("User-Agent", user_agent)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return emptyList()
            }
            val results = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONArray("results")
                ?: return emptyList()
            val candidates = mutableListOf<SearchImageCandidate>()
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val image_url = item.optString("url").trim()
                val page_url = item.optString("foreign_landing_url").trim()
                val title = item.optString("title").trim()
                val lower_title = title.lowercase(Locale.US)
                if (
                    is_allowed_https_image_url(image_url) &&
                    page_url.startsWith("https://", ignoreCase = true) &&
                    !lower_title.contains("logo") &&
                    !lower_title.contains("diagram")
                ) {
                    val tags = item.optJSONArray("tags")?.let { tag_array ->
                        (0 until tag_array.length()).mapNotNull { tag_index ->
                            tag_array.optJSONObject(tag_index)?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
                        }
                    }.orEmpty()
                    candidates += SearchImageCandidate(
                        image_url = image_url,
                        page_url = page_url,
                        source_name = item.optString("provider").trim().ifEmpty { "Openverse source" },
                        title = title,
                        verification_text = listOf(
                            title,
                            item.optString("creator").trim(),
                            tags.take(8).joinToString(" ")
                        ).filter { it.isNotBlank() }.joinToString(" ")
                    )
                }
            }
            candidates.distinctBy { it.image_url }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    // Wikimedia Commons candidates include structured metadata for exact or representative proof.
    private fun fetch_wikimedia_search_image_candidates(query: String): List<SearchImageCandidate> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val api_url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch=$encoded&prop=imageinfo&iiprop=url|mime|extmetadata"
        val pages = fetch_json_object(api_url)
            ?.optJSONObject("query")
            ?.optJSONObject("pages")
            ?: return emptyList()
        val candidates = mutableListOf<SearchImageCandidate>()
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            val title = page.optString("title").removePrefix("File:").trim()
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val mime = info.optString("mime")
            val image_url = info.optString("url").trim()
            val page_url = info.optString("descriptionurl").trim()
            if (!mime.startsWith("image/") || !is_allowed_https_image_url(image_url)) continue
            if (!page_url.startsWith("https://", ignoreCase = true)) continue
            val metadata = info.optJSONObject("extmetadata")
            val metadata_text = listOfNotNull(
                title,
                metadata?.optJSONObject("ObjectName")?.optString("value"),
                metadata?.optJSONObject("ImageDescription")?.optString("value"),
                metadata?.optJSONObject("Categories")?.optString("value")
            ).joinToString(" ")
            candidates += SearchImageCandidate(
                image_url = image_url,
                page_url = page_url,
                source_name = "Wikimedia Commons",
                title = title,
                verification_text = normalize_html_text(metadata_text)
            )
        }
        return candidates.distinctBy { it.image_url }.take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
    }

    // Pull a small proof quote from the source page when it mentions the aircraft or model terms.
    private fun fetch_verification_quote(page_url: String, terms: List<String>): VerificationQuote? {
        val html = fetch_text(page_url) ?: return null
        return quote_from_text(normalize_html_text(html), terms)
    }

    // Keep only enough quoted text to justify the match without treating the source page as app data.
    private fun quote_from_text(text: String, terms: List<String>): VerificationQuote? {
        val upper = text.uppercase(Locale.US)
        val matched = terms.filter { term -> upper.contains(term.uppercase(Locale.US)) }.distinct()
        if (matched.isEmpty()) return null
        val first_index = matched.mapNotNull { term ->
            val index = upper.indexOf(term.uppercase(Locale.US))
            if (index >= 0) index else null
        }.minOrNull() ?: return null
        val start = max(0, first_index - 120)
        val end = min(text.length, first_index + 180)
        return VerificationQuote(
            text = text.substring(start, end).trim(),
            matched_terms = matched.take(4)
        )
    }

    // Strip page markup before proof matching so verification looks at readable source text.
    private fun normalize_html_text(html: String): String {
        return html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // JSON helpers reject non-HTTPS URLs before any network request leaves the app.
    private fun fetch_json_object(url: String): JSONObject? {
        val safe_url = https_url(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safe_url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 9000
                requestMethod = "GET"
                setRequestProperty("User-Agent", user_agent)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // Text helpers are used only for proof snippets, not to manufacture aircraft facts.
    private fun fetch_text(url: String): String? {
        val safe_url = https_url(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safe_url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = PHOTO_TEXT_READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", user_agent)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // Image download accepts only allowed HTTPS image URLs and returns null on any uncertainty.
    private fun fetch_bitmap(url: String, referer: String? = null): Bitmap? {
        val safe_url = https_url(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safe_url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", AirplanesLiveHttp.browser_user_agent(user_agent))
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                referer?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let {
                    setRequestProperty("Referer", it)
                }
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            val content_type = connection.contentType.orEmpty()
            if (!content_type.startsWith("image/", ignoreCase = true) && !IMAGE_URL_PATTERN.containsMatchIn(url)) {
                return null
            }
            val bytes = read_image_bytes(connection.inputStream) ?: return null
            decode_display_photo(bytes)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun read_image_bytes(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_PHOTO_IMAGE_BYTES) return null
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun decode_display_photo(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = photo_sample_size(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun photo_sample_size(width: Int, height: Int): Int {
        var sample_size = 1
        var sampled_width = width
        var sampled_height = height
        while (max(sampled_width, sampled_height) > PHOTO_DECODE_MAX_EDGE_PX) {
            sample_size *= 2
            sampled_width /= 2
            sampled_height /= 2
        }
        return sample_size
    }

    // Walk nested photo API responses without assuming a specific field name for the first image.
    private fun exterior_image_urls(value: Any?): List<String> {
        val urls = mutableListOf<String>()
        collect_exterior_image_urls(value, context_text = "", output = urls)
        return urls.distinct()
    }

    private fun collect_exterior_image_urls(value: Any?, context_text: String, output: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                val next_context = listOf(
                    context_text,
                    value.optString("caption"),
                    value.optString("description"),
                    value.optString("title"),
                    value.optString("category"),
                    value.optString("type")
                ).filter { it.isNotBlank() }.joinToString(" ")
                val keys = value.keys()
                while (keys.hasNext()) {
                    collect_exterior_image_urls(value.opt(keys.next()), next_context, output)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collect_exterior_image_urls(value.opt(index), context_text, output)
                }
            }
            is String -> {
                if (is_allowed_https_image_url(value) && is_exterior_candidate_text("$context_text $value")) output += value
            }
        }
    }

    private fun is_exterior_candidate_text(text: String): Boolean {
        return !DISALLOWED_MAIN_PHOTO_TEXT.containsMatchIn(text)
    }

    private fun is_allowed_https_image_url(value: String?): Boolean {
        val url = value?.trim() ?: return false
        return url.startsWith("https://", ignoreCase = true) && IMAGE_URL_PATTERN.containsMatchIn(url)
    }

    private fun https_url(value: String): URL? {
        return try {
            URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalized_registration(value: String?): String? {
        return value
            ?.uppercase(Locale.US)
            ?.replace("PHOTOS", "")
            ?.replace(Regex("[^A-Z0-9-]"), "")
            ?.trim('-')
            ?.takeIf { it.isNotBlank() && it != "NA" }
    }

    private fun exact_photo_cache_key(
        aircraft: Aircraft,
        details: AircraftDetails,
        registration: String?
    ): String {
        return listOf(
            "exact",
            aircraft.icao24.trim().trimStart('~').uppercase(Locale.US),
            registration.orEmpty(),
            (details.type_code ?: aircraft.type_code).orEmpty().uppercase(Locale.US)
        ).joinToString("|")
    }

    private fun fallback_photo_cache_key(aircraft: Aircraft, details: AircraftDetails): String {
        return listOf(
            "fallback",
            aircraft.icao24.trim().trimStart('~').uppercase(Locale.US),
            normalized_registration(details.registration ?: aircraft.registration).orEmpty(),
            details.manufacturer.orEmpty().uppercase(Locale.US),
            details.type.orEmpty().uppercase(Locale.US),
            (details.type_code ?: aircraft.type_code).orEmpty().uppercase(Locale.US)
        ).joinToString("|")
    }

    private fun cached_photo(key: String): AircraftPhotoResult.Found? {
        val now = System.currentTimeMillis()
        return synchronized(photo_cache) {
            val cached = photo_cache[key] ?: return@synchronized null
            if (now - cached.stored_at_ms > PHOTO_CACHE_MAX_AGE_MS) {
                photo_cache.remove(key)
                null
            } else {
                cached.photo
            }
        }
    }

    private fun cache_photo(key: String, photo: AircraftPhotoResult.Found) {
        synchronized(photo_cache) {
            photo_cache[key] = CachedPhotoResult(photo, System.currentTimeMillis())
        }
    }

    private companion object {
        const val PHOTO_CACHE_MAX_ENTRIES = 32
        const val PHOTO_CACHE_MAX_AGE_MS = 15L * 60L * 1000L
        const val PHOTO_DECODE_MAX_EDGE_PX = 1200
        const val MAX_PHOTO_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY = 4
        const val MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY = 5
        const val MAX_GALLERY_CANDIDATES_PER_QUERY = 4
        const val MAX_GALLERY_ITEMS = 8
        const val PHOTO_TEXT_READ_TIMEOUT_MS = 9000
        val IMAGE_URL_PATTERN = Regex("\\.(jpg|jpeg|png|webp)(\\?|$)", RegexOption.IGNORE_CASE)
        val DISALLOWED_MAIN_PHOTO_TEXT = Regex(
            "\\b(interior|cockpit|cabin|seat|seats|seating|lavatory|blueprint|diagram|schematic|floor\\s*plan|seat\\s*map)\\b",
            RegexOption.IGNORE_CASE
        )
        val EXTERIOR_PREFERRED_TEXT = Regex(
            "\\b(exterior|side\\s*view|ramp|apron|taxi|taxiing|takeoff|landing|in\\s*flight|airborne|livery)\\b",
            RegexOption.IGNORE_CASE
        )
        val AIRCRAFT_TEXT = Regex("\\b(aircraft|airplane|aeroplane|airliner|jet|helicopter|rotorcraft)\\b", RegexOption.IGNORE_CASE)
        val COCKPIT_TEXT = Regex("\\b(cockpit|flight\\s*deck)\\b", RegexOption.IGNORE_CASE)
        val INTERIOR_TEXT = Regex("\\b(interior|cabin|seat|seats|seating|lavatory)\\b", RegexOption.IGNORE_CASE)
        val MODE_S_HEX = Regex("^[0-9A-F]{6}$")
    }
}

private data class ExactPhotoCandidate(
    val image_url: String,
    val source_name: String,
    val page_url: String,
    val note: String
)

private data class CachedPhotoResult(
    val photo: AircraftPhotoResult.Found,
    val stored_at_ms: Long
)
