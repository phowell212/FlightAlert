package com.flightalert.ui.map.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.flightalert.data.AircraftDetails
import com.flightalert.ui.map.model.Aircraft
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

class AircraftPhotoFetcher(private val userAgent: String) {
    fun fetch(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult {
        val registration = normalizedRegistration(details.registration ?: aircraft.registration)
        fetchAdsbDbPhotoUrls(aircraft.icao24, registration).forEach { imageUrl ->
            fetchBitmap(imageUrl)?.let {
                return AircraftPhotoResult.Found(it, "Exact aircraft photo from ADSBdb", quality = PhotoQuality.EXACT)
            }
        }
        if (registration != null) {
            fetchJetPhotosExactImageUrls(registration).forEach { imageUrl ->
                fetchBitmap(imageUrl)?.let {
                    return AircraftPhotoResult.Found(it, "Exact aircraft photo from JetPhotos; registration verified", quality = PhotoQuality.EXACT)
                }
            }
        }
        val exactSources = listOfNotNull(
            "https://api.planespotters.net/pub/photos/hex/${aircraft.icao24.trim()}",
            (details.registration ?: aircraft.registration)?.let { "https://api.planespotters.net/pub/photos/reg/${it.trim()}" }
        )
        exactSources.forEach { apiUrl ->
            fetchPlanespottersPhotoUrl(apiUrl)?.let { imageUrl ->
                fetchBitmap(imageUrl)?.let {
                    return AircraftPhotoResult.Found(it, "Exact aircraft photo from PlaneSpotters", quality = PhotoQuality.EXACT)
                }
            }
        }
        fetchBitmap("https://hexdb.io/hex-image-thumb?hex=${aircraft.icao24.trim()}")?.let {
            return AircraftPhotoResult.Found(it, "Exact aircraft photo from HexDB", quality = PhotoQuality.EXACT)
        }
        return fetchRepresentativePhoto(details)
            ?: fetchVerifiedGenericSearchPhoto(aircraft, details)
            ?: fetchInvestigableSearchPhoto(aircraft, details)
            ?: AircraftPhotoResult.Unavailable("Exact, representative, and search photos unavailable")
    }

    private fun fetchAdsbDbPhotoUrls(icao24: String, registration: String?): List<String> {
        val keys = listOfNotNull(icao24.trim().takeIf { it.isNotBlank() }, registration).distinct()
        return keys.flatMap { key ->
            val encoded = URLEncoder.encode(key, "UTF-8")
            val json = fetchJsonObject("https://api.adsbdb.com/v0/aircraft/$encoded") ?: return@flatMap emptyList()
            val aircraft = json.optJSONObject("response")?.optJSONObject("aircraft") ?: return@flatMap emptyList()
            listOfNotNull(
                aircraft.optString("url_photo").takeIf { it.startsWith("https://", ignoreCase = true) },
                aircraft.optString("url_photo_thumbnail").takeIf { it.startsWith("https://", ignoreCase = true) }
            )
        }.distinct()
    }

    private fun fetchJetPhotosExactImageUrls(registration: String): List<String> {
        val encoded = URLEncoder.encode(registration, "UTF-8")
        val apiUrl = "https://jp.rewis.workers.dev/?page=1&sort-order=1&keywords=$encoded&keywords-type=registration&keywords-contain=0"
        val photos = fetchJsonObject(apiUrl)?.optJSONArray("photos") ?: return emptyList()
        val urls = mutableListOf<String>()
        for (index in 0 until photos.length()) {
            val item = photos.optJSONObject(index) ?: continue
            val foundRegistration = normalizedRegistration(item.optString("registration"))
            if (foundRegistration != registration) continue
            listOf(item.optString("imageUrl"), item.optString("thumbnailUrl")).forEach { url ->
                if (url.startsWith("https://", ignoreCase = true) && IMAGE_URL_PATTERN.containsMatchIn(url)) urls += url
            }
        }
        return urls.distinct()
    }

    private fun fetchPlanespottersPhotoUrl(apiUrl: String): String? {
        val safeUrl = httpsUrl(apiUrl) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safeUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            findFirstImageUrl(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchRepresentativePhoto(details: AircraftDetails): AircraftPhotoResult.Found? {
        AircraftPhotoCatalog.representativeModelNames(details).forEach { model ->
            val queries = AircraftPhotoCatalog.representativePhotoQueries(details, model)
            queries.forEach { query ->
                val encoded = URLEncoder.encode(query, "UTF-8")
                val apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch=$encoded&prop=imageinfo&iiprop=url|mime"
                fetchWikimediaImageUrls(apiUrl).take(MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY).forEach { url ->
                    fetchBitmap(url)?.let { bitmap ->
                        return AircraftPhotoResult.Found(bitmap, "Representative $model photo from Wikimedia Commons; not this exact aircraft", quality = PhotoQuality.REPRESENTATIVE)
                    }
                }
            }

            queries.forEach { query ->
                fetchWikipediaPageImageUrls(query).take(MAX_REPRESENTATIVE_PHOTO_CANDIDATES_PER_QUERY).forEach { url ->
                    fetchBitmap(url)?.let { bitmap ->
                        return AircraftPhotoResult.Found(bitmap, "Representative $model photo from Wikipedia; not this exact aircraft", quality = PhotoQuality.REPRESENTATIVE)
                    }
                }
            }
        }
        return null
    }

    private fun fetchVerifiedGenericSearchPhoto(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult.Found? {
        val registration = normalizedRegistration(details.registration ?: aircraft.registration)
        val exactTerms = listOfNotNull(registration, aircraft.icao24.takeIf { it.isNotBlank() })
        AircraftPhotoCatalog.exactPhotoQueries(registration, aircraft.icao24).forEach { query ->
            val candidates = fetchWikimediaSearchImageCandidates(query) +
                fetchOpenverseImageCandidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
            candidates.distinctBy { it.imageUrl }.forEach { candidate ->
                verifiedSearchPhoto(
                    candidate = candidate,
                    query = query,
                    note = "Verified exact-aircraft search result for ${registration ?: aircraft.icao24.uppercase(Locale.US)}",
                    verificationTerms = exactTerms,
                    quality = PhotoQuality.EXACT
                )?.let { return it }
            }
        }

        AircraftPhotoCatalog.representativeModelNames(details).forEach { model ->
            val verificationTerms = AircraftPhotoCatalog.photoVerificationTerms(details, model)
            AircraftPhotoCatalog.representativePhotoQueries(details, model).forEach { query ->
                val candidates = fetchWikimediaSearchImageCandidates(query) +
                    fetchOpenverseImageCandidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
                candidates.distinctBy { it.imageUrl }.forEach { candidate ->
                    verifiedSearchPhoto(
                        candidate = candidate,
                        query = query,
                        note = "Verified search result for $model; not this exact aircraft",
                        verificationTerms = verificationTerms,
                        quality = PhotoQuality.REPRESENTATIVE
                    )?.let { return it }
                }
            }
        }
        return null
    }

    private fun fetchInvestigableSearchPhoto(aircraft: Aircraft, details: AircraftDetails): AircraftPhotoResult.Found? {
        val registration = normalizedRegistration(details.registration ?: aircraft.registration)
        val exactQueries = AircraftPhotoCatalog.exactPhotoQueries(registration, aircraft.icao24)
        val representativeQueries = AircraftPhotoCatalog.representativeModelNames(details)
            .flatMap { AircraftPhotoCatalog.representativePhotoQueries(details, it) }
        (exactQueries + representativeQueries).distinct().forEach { query ->
            val candidates = fetchWikimediaSearchImageCandidates(query) +
                fetchOpenverseImageCandidates(query).take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
            candidates.distinctBy { it.imageUrl }.forEach { candidate ->
                val bitmap = fetchBitmap(candidate.imageUrl) ?: return@forEach
                val evidence = PhotoEvidence(
                    sourceName = candidate.sourceName,
                    imageUrl = candidate.imageUrl,
                    pageUrl = candidate.pageUrl,
                    searchQuery = query,
                    quote = candidate.investigationText(),
                    matchedTerms = emptyList()
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

    private fun verifiedSearchPhoto(
        candidate: SearchImageCandidate,
        query: String,
        note: String,
        verificationTerms: List<String>,
        quality: PhotoQuality
    ): AircraftPhotoResult.Found? {
        val quote = fetchVerificationQuote(candidate.pageUrl, verificationTerms)
            ?: candidate.verificationText?.let { quoteFromText(it, verificationTerms) }
            ?: return null
        val bitmap = fetchBitmap(candidate.imageUrl) ?: return null
        val evidence = PhotoEvidence(
            sourceName = candidate.sourceName,
            imageUrl = candidate.imageUrl,
            pageUrl = candidate.pageUrl,
            searchQuery = query,
            quote = quote.text,
            matchedTerms = quote.matchedTerms
        )
        return AircraftPhotoResult.Found(
            bitmap,
            note,
            evidence,
            quality
        )
    }

    private fun SearchImageCandidate.investigationText(): String {
        val titleText = title.takeIf { it.isNotBlank() }?.let { "Title: $it" }
        val metadataText = verificationText?.takeIf { it.isNotBlank() }?.let { "Metadata: $it" }
        return listOfNotNull(titleText, metadataText, "Source page: $pageUrl")
            .joinToString("  ")
    }

    private fun fetchWikimediaImageUrls(apiUrl: String): List<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
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
                if (mime.startsWith("image/") && isAllowedHttpsImageUrl(url)) urls += url
            }
            urls
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchOpenverseImageCandidates(query: String): List<SearchImageCandidate> {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val apiUrl = "https://api.openverse.org/v1/images/?format=json&page_size=12&mature=false&q=$encoded"
            connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 9000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
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
                val imageUrl = item.optString("url").trim()
                val pageUrl = item.optString("foreign_landing_url").trim()
                val title = item.optString("title").trim()
                val lowerTitle = title.lowercase(Locale.US)
                if (
                    isAllowedHttpsImageUrl(imageUrl) &&
                    pageUrl.startsWith("https://", ignoreCase = true) &&
                    !lowerTitle.contains("logo") &&
                    !lowerTitle.contains("diagram")
                ) {
                    val tags = item.optJSONArray("tags")?.let { tagArray ->
                        (0 until tagArray.length()).mapNotNull { tagIndex ->
                            tagArray.optJSONObject(tagIndex)?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
                        }
                    }.orEmpty()
                    candidates += SearchImageCandidate(
                        imageUrl = imageUrl,
                        pageUrl = pageUrl,
                        sourceName = item.optString("provider").trim().ifEmpty { "Openverse source" },
                        title = title,
                        verificationText = listOf(
                            title,
                            item.optString("creator").trim(),
                            tags.take(8).joinToString(" ")
                        ).filter { it.isNotBlank() }.joinToString(" ")
                    )
                }
            }
            candidates.distinctBy { it.imageUrl }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchWikimediaSearchImageCandidates(query: String): List<SearchImageCandidate> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch=$encoded&prop=imageinfo&iiprop=url|mime|extmetadata"
        val pages = fetchJsonObject(apiUrl)
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
            val imageUrl = info.optString("url").trim()
            val pageUrl = info.optString("descriptionurl").trim()
            if (!mime.startsWith("image/") || !isAllowedHttpsImageUrl(imageUrl)) continue
            if (!pageUrl.startsWith("https://", ignoreCase = true)) continue
            val metadata = info.optJSONObject("extmetadata")
            val metadataText = listOfNotNull(
                title,
                metadata?.optJSONObject("ObjectName")?.optString("value"),
                metadata?.optJSONObject("ImageDescription")?.optString("value"),
                metadata?.optJSONObject("Categories")?.optString("value")
            ).joinToString(" ")
            candidates += SearchImageCandidate(
                imageUrl = imageUrl,
                pageUrl = pageUrl,
                sourceName = "Wikimedia Commons",
                title = title,
                verificationText = normalizeHtmlText(metadataText)
            )
        }
        return candidates.distinctBy { it.imageUrl }.take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
    }

    private fun fetchVerificationQuote(pageUrl: String, terms: List<String>): VerificationQuote? {
        val html = fetchText(pageUrl) ?: return null
        return quoteFromText(normalizeHtmlText(html), terms)
    }

    private fun quoteFromText(text: String, terms: List<String>): VerificationQuote? {
        val upper = text.uppercase(Locale.US)
        val matched = terms.filter { term -> upper.contains(term.uppercase(Locale.US)) }.distinct()
        if (matched.isEmpty()) return null
        val firstIndex = matched.mapNotNull { term ->
            val index = upper.indexOf(term.uppercase(Locale.US))
            if (index >= 0) index else null
        }.minOrNull() ?: return null
        val start = max(0, firstIndex - 120)
        val end = min(text.length, firstIndex + 180)
        return VerificationQuote(
            text = text.substring(start, end).trim(),
            matchedTerms = matched.take(4)
        )
    }

    private fun normalizeHtmlText(html: String): String {
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

    private fun fetchWikipediaPageImageUrls(query: String): List<String> {
        val encoded = URLEncoder.encode("$query aircraft", "UTF-8")
        val apiUrl = "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=$encoded&gsrlimit=6&prop=pageimages&piprop=thumbnail|original&pithumbsize=1100"
        val pages = fetchJsonObject(apiUrl)
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
                if (isAllowedHttpsImageUrl(url)) urls += url
            }
        }
        return urls.distinct()
    }

    private fun fetchJsonObject(url: String): JSONObject? {
        val safeUrl = httpsUrl(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safeUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 9000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
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

    private fun fetchText(url: String): String? {
        val safeUrl = httpsUrl(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safeUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = PHOTO_TEXT_READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
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

    private fun fetchBitmap(url: String): Bitmap? {
        if (!isAllowedHttpsImageUrl(url)) return null
        val safeUrl = httpsUrl(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safeUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
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

    private fun findFirstImageUrl(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    findFirstImageUrl(value.opt(keys.next()))?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findFirstImageUrl(value.opt(index))?.let { return it }
                }
                null
            }
            is String -> value.takeIf { isAllowedHttpsImageUrl(it) }
            else -> null
        }
    }

    private fun isAllowedHttpsImageUrl(value: String?): Boolean {
        val url = value?.trim() ?: return false
        return url.startsWith("https://", ignoreCase = true) && IMAGE_URL_PATTERN.containsMatchIn(url)
    }

    private fun httpsUrl(value: String): URL? {
        return try {
            URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizedRegistration(value: String?): String? {
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
