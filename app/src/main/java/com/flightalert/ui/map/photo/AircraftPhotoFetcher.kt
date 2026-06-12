package com.flightalert.ui.map.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flightalert.data.AircraftDetails
import com.flightalert.ui.map.Aircraft
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

class AircraftPhotoFetcher(private val user_agent: String) {
    fun fetch(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult {
        val registration = normalized_registration(details.registration ?: aircraft.registration)
        fetch_adsb_db_photo_urls(aircraft.icao24, registration).forEach { image_url ->
            fetch_bitmap(image_url)?.let {
                return AircraftPhotoResult.Found(it, "Exact aircraft photo from ADSBdb", quality = PhotoQuality.EXACT)
            }
        }
        if (registration != null) {
            fetch_jet_photos_exact_image_urls(registration).forEach { image_url ->
                fetch_bitmap(image_url)?.let {
                    return AircraftPhotoResult.Found(it, "Exact aircraft photo from JetPhotos; registration verified", quality = PhotoQuality.EXACT)
                }
            }
        }
        val exact_sources = listOfNotNull(
            "https://api.planespotters.net/pub/photos/hex/${aircraft.icao24.trim()}",
            (details.registration ?: aircraft.registration)?.let { "https://api.planespotters.net/pub/photos/reg/${it.trim()}" }
        )
        exact_sources.forEach { api_url ->
            fetch_planespotters_photo_url(api_url)?.let { image_url ->
                fetch_bitmap(image_url)?.let {
                    return AircraftPhotoResult.Found(it, "Exact aircraft photo from PlaneSpotters", quality = PhotoQuality.EXACT)
                }
            }
        }
        fetch_bitmap("https://hexdb.io/hex-image-thumb?hex=${aircraft.icao24.trim()}")?.let {
            return AircraftPhotoResult.Found(it, "Exact aircraft photo from HexDB", quality = PhotoQuality.EXACT)
        }
        return fetch_representative_photo(details)
            ?: fetch_verified_generic_search_photo(aircraft, details)
            ?: fetch_investigable_search_photo(aircraft, details)
            ?: AircraftPhotoResult.Unavailable("Exact, representative, and search photos unavailable")
    }

    private fun fetch_adsb_db_photo_urls(icao24: String, registration: String?): List<String> {
        val keys = listOfNotNull(icao24.trim().takeIf { it.isNotBlank() }, registration).distinct()
        return keys.flatMap { key ->
            val encoded = URLEncoder.encode(key, "UTF-8")
            val json = fetch_json_object("https://api.adsbdb.com/v0/aircraft/$encoded") ?: return@flatMap emptyList()
            val aircraft = json.optJSONObject("response")?.optJSONObject("aircraft") ?: return@flatMap emptyList()
            listOfNotNull(
                aircraft.optString("url_photo").takeIf { it.startsWith("https://", ignoreCase = true) },
                aircraft.optString("url_photo_thumbnail").takeIf { it.startsWith("https://", ignoreCase = true) }
            )
        }.distinct()
    }

    private fun fetch_jet_photos_exact_image_urls(registration: String): List<String> {
        val encoded = URLEncoder.encode(registration, "UTF-8")
        val api_url = "https://jp.rewis.workers.dev/?page=1&sort-order=1&keywords=$encoded&keywords-type=registration&keywords-contain=0"
        val photos = fetch_json_object(api_url)?.optJSONArray("photos") ?: return emptyList()
        val urls = mutableListOf<String>()
        for (index in 0 until photos.length()) {
            val item = photos.optJSONObject(index) ?: continue
            val found_registration = normalized_registration(item.optString("registration"))
            if (found_registration != registration) continue
            listOf(item.optString("imageUrl"), item.optString("thumbnailUrl")).forEach { url ->
                if (url.startsWith("https://", ignoreCase = true) && IMAGE_URL_PATTERN.containsMatchIn(url)) urls += url
            }
        }
        return urls.distinct()
    }

    private fun fetch_planespotters_photo_url(api_url: String): String? {
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
            find_first_image_url(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetch_representative_photo(details: AircraftDetails): AircraftPhotoResult.Found? {
        AircraftPhotoCatalog.representative_model_names(details).forEach { model ->
            val queries = AircraftPhotoCatalog.representative_photo_queries(details, model)
            queries.forEach { query ->
                val encoded = URLEncoder.encode(query, "UTF-8")
                val api_url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch=$encoded&prop=imageinfo&iiprop=url|mime"
                fetch_wikimedia_image_urls(api_url).take(MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY).forEach { url ->
                    fetch_bitmap(url)?.let { bitmap ->
                        return AircraftPhotoResult.Found(bitmap, "Representative $model photo from Wikimedia Commons; not this exact aircraft", quality = PhotoQuality.REPRESENTATIVE)
                    }
                }
            }

            queries.forEach { query ->
                fetch_wikipedia_page_image_urls(query).take(MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY).forEach { url ->
                    fetch_bitmap(url)?.let { bitmap ->
                        return AircraftPhotoResult.Found(bitmap, "Representative $model photo from Wikipedia; not this exact aircraft", quality = PhotoQuality.REPRESENTATIVE)
                    }
                }
            }
        }
        return null
    }

    private fun fetch_verified_generic_search_photo(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult.Found? {
        val registration = normalized_registration(details.registration ?: aircraft.registration)
        val exact_terms = listOfNotNull(registration, aircraft.icao24.takeIf { it.isNotBlank() })
        AircraftPhotoCatalog.exact_photo_queries(registration, aircraft.icao24).forEach { query ->
            val candidates = fetch_wikimedia_search_image_candidates(query) +
                fetch_openverse_image_candidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
            candidates.distinctBy { it.image_url }.forEach { candidate ->
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
                candidates.distinctBy { it.image_url }.forEach { candidate ->
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

    private fun fetch_investigable_search_photo(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult.Found? {
        val registration = normalized_registration(details.registration ?: aircraft.registration)
        val exact_queries = AircraftPhotoCatalog.exact_photo_queries(registration, aircraft.icao24)
        val representative_queries = AircraftPhotoCatalog.representative_model_names(details)
            .flatMap { AircraftPhotoCatalog.representative_photo_queries(details, it) }
        (exact_queries + representative_queries).distinct().forEach { query ->
            val candidates = fetch_wikimedia_search_image_candidates(query) +
                fetch_openverse_image_candidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
            candidates.distinctBy { it.image_url }.forEach { candidate ->
                val bitmap = fetch_bitmap(candidate.image_url) ?: return@forEach
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
        val bitmap = fetch_bitmap(candidate.image_url) ?: return null
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

    private fun SearchImageCandidate.investigation_text(): String {
        val title_text = title.takeIf { it.isNotBlank() }?.let { "Title: $it" }
        val metadata_text = verification_text?.takeIf { it.isNotBlank() }?.let { "Metadata: $it" }
        return listOfNotNull(title_text, metadata_text, "Source page: $page_url")
            .joinToString("  ")
    }

    private fun fetch_wikimedia_image_urls(api_url: String): List<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(api_url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", user_agent)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return emptyList()
            }
            val pages = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONObject("query")
                ?.optJSONObject("pages")
                ?: return emptyList()
            val keys = pages.keys()
            val urls = mutableListOf<String>()
            while (keys.hasNext()) {
                val info = pages.optJSONObject(keys.next())?.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val mime = info.optString("mime")
                val url = info.optString("url")
                if (mime.startsWith("image/") && is_allowed_https_image_url(url)) urls += url
            }
            urls
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

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

    private fun fetch_verification_quote(page_url: String, terms: List<String>): VerificationQuote? {
        val html = fetch_text(page_url) ?: return null
        return quote_from_text(normalize_html_text(html), terms)
    }

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

    private fun fetch_wikipedia_page_image_urls(query: String): List<String> {
        val encoded = URLEncoder.encode("$query aircraft", "UTF-8")
        val api_url = "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=$encoded&gsrlimit=6&prop=pageimages&piprop=thumbnail|original&pithumbsize=1100"
        val pages = fetch_json_object(api_url)
            ?.optJSONObject("query")
            ?.optJSONObject("pages")
            ?: return emptyList()
        val urls = mutableListOf<String>()
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            listOfNotNull(
                page.optJSONObject("original")?.optString("source"),
                page.optJSONObject("thumbnail")?.optString("source")
            ).forEach { url ->
                if (is_allowed_https_image_url(url)) urls += url
            }
        }
        return urls.distinct()
    }

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

    private fun fetch_bitmap(url: String): Bitmap? {
        if (!is_allowed_https_image_url(url)) return null
        val safe_url = https_url(url) ?: return null
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
            BitmapFactory.decodeStream(connection.inputStream)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun find_first_image_url(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    find_first_image_url(value.opt(keys.next()))?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    find_first_image_url(value.opt(index))?.let { return it }
                }
                null
            }
            is String -> value.takeIf { is_allowed_https_image_url(it) }
            else -> null
        }
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

    private companion object {
        const val MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY = 4
        const val MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY = 5
        const val PHOTO_TEXT_READ_TIMEOUT_MS = 9000
        val IMAGE_URL_PATTERN = Regex("\\.(jpg|jpeg|png|webp)(\\?|$)", RegexOption.IGNORE_CASE)
    }
}
