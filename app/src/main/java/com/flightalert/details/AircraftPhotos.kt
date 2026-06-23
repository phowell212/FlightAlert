@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.details
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.withRotation
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import com.flightalert.FlightAlertAppSettings.AircraftFeedMode
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import com.flightalert.*
import com.flightalert.aircraft.*
import com.flightalert.traffic.*
import com.flightalert.map.*
import com.flightalert.flight.*
import com.flightalert.details.*
import com.flightalert.alerts.*
import com.flightalert.ui.*

object AircraftPhotoCatalog {
    fun representative_model_names(details: AircraftDetails): List<String> {
        val base = representative_model_name(details)
        val type = details.type?.trim()
        val manufacturer = details.manufacturer?.trim()
        val aliases = mutableListOf<String>()
        if (base != null) aliases += base
        if (manufacturer != null && type != null) {
            aliases += "$manufacturer $type"
            manufacturer_aliases(manufacturer).forEach { alias -> aliases += "$alias $type" }
            type_model_aliases(type).forEach { alias_type ->
                aliases += "$manufacturer $alias_type"
                manufacturer_aliases(manufacturer).forEach { alias -> aliases += "$alias $alias_type" }
            }
        }
        type?.let { aliases += it }
        return aliases
            .map { normalize_aircraft_search_name(it) }
            .filter { it.length >= 3 }
            .distinct()
    }

    fun representative_photo_queries(details: AircraftDetails, model: String): List<String> {
        return listOfNotNull(
            "\"$model\" aircraft exterior",
            "$model aircraft exterior",
            "\"$model\" exterior photo",
            "\"$model\" aircraft",
            "$model aircraft",
            "\"$model\" aircraft photo",
            "$model aircraft photo",
            "\"$model\" airplane",
            "$model airplane",
            "\"$model\" airliner",
            "\"$model\" in flight",
            details.type?.let { "\"$it\" aircraft" },
            details.type_code?.let { "${details.manufacturer.orEmpty()} $it aircraft".trim() },
            details.type_code?.let { "$it aircraft" }
        ).filter { it.isNotBlank() }.distinct()
    }

    fun exact_photo_queries(registration: String?, icao24: String): List<String> {
        return listOfNotNull(
            registration?.let { "\"$it\" aircraft exterior" },
            registration?.let { "\"$it\" exterior photo" },
            registration?.let { "\"$it\" aircraft photo" },
            registration?.let { "$it aircraft" },
            icao24.takeIf { it.isNotBlank() }?.let { "\"${it.uppercase(Locale.US)}\" aircraft" }
        ).distinct()
    }

    fun photo_verification_terms(details: AircraftDetails, model: String): List<String> {
        val family_terms = listOfNotNull(
            Regex("""Airbus A\d{3}""").find(model)?.value,
            Regex("""Boeing \d{3}""").find(model)?.value,
            Regex("""Cessna \d{3}""").find(model)?.value,
            Regex("""Piper PA-\d{2}""").find(model)?.value,
            Regex("""Embraer \d{3}""").find(model)?.value
        )
        return listOfNotNull(
            model,
            model.substringAfterLast(" ").takeIf { it.length >= 3 && it.any(Char::isDigit) },
            details.type,
            details.type_code
        ).plus(family_terms)
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
    }

    private fun representative_model_name(details: AircraftDetails): String? {
        val code = details.type_code?.uppercase(Locale.US)?.trim()
        val known_model = when (code) {
            "A19N" -> "Airbus A319neo"
            "A20N" -> "Airbus A320neo"
            "A21N" -> "Airbus A321neo"
            "A319" -> "Airbus A319"
            "A320" -> "Airbus A320"
            "A321" -> "Airbus A321"
            "B37M" -> "Boeing 737 MAX 7"
            "B38M" -> "Boeing 737 MAX 8"
            "B39M" -> "Boeing 737 MAX 9"
            "B3XM" -> "Boeing 737 MAX 10"
            "B737" -> "Boeing 737"
            "B738" -> "Boeing 737-800"
            "B739" -> "Boeing 737-900"
            "B752" -> "Boeing 757-200"
            "B763" -> "Boeing 767-300"
            "B772" -> "Boeing 777-200"
            "B77W" -> "Boeing 777-300ER"
            "B788" -> "Boeing 787-8"
            "B789" -> "Boeing 787-9"
            "B78X" -> "Boeing 787-10"
            "B744" -> "Boeing 747-400"
            "B748" -> "Boeing 747-8"
            "BCS1" -> "Airbus A220-100"
            "BCS3" -> "Airbus A220-300"
            "AT72" -> "ATR 72"
            "AT76" -> "ATR 72-600"
            "AA1" -> "Grumman American AA-1 Yankee"
            "AA5" -> "Grumman American AA-5 Traveler"
            "AG5B" -> "American General AG-5B Tiger"
            "BE20" -> "Beechcraft King Air 200"
            "BE30" -> "Beechcraft King Air 300"
            "BE33" -> "Beechcraft Bonanza"
            "BE35" -> "Beechcraft Bonanza"
            "BE36" -> "Beechcraft Bonanza"
            "BE40" -> "Beechjet 400"
            "BE55" -> "Beechcraft Baron"
            "BE58" -> "Beechcraft Baron"
            "BE76" -> "Beechcraft Duchess"
            "B350" -> "Beechcraft King Air 350"
            "C120" -> "Cessna 120"
            "C140" -> "Cessna 140"
            "C150" -> "Cessna 150"
            "C152" -> "Cessna 152"
            "C172" -> "Cessna 172"
            "C175" -> "Cessna 175 Skylark"
            "C177" -> "Cessna 177 Cardinal"
            "C180" -> "Cessna 180"
            "C182" -> "Cessna 182"
            "C195" -> "Cessna 195"
            "C185" -> "Cessna 185"
            "C206" -> "Cessna 206"
            "C208" -> "Cessna 208 Caravan"
            "C210" -> "Cessna 210"
            "C337" -> "Cessna 337 Skymaster"
            "C25A" -> "Cessna Citation CJ2"
            "C25B" -> "Cessna Citation CJ3"
            "C25C" -> "Cessna Citation CJ4"
            "C310" -> "Cessna 310"
            "C414" -> "Cessna 414"
            "C421" -> "Cessna 421"
            "C525" -> "Cessna CitationJet"
            "C56X" -> "Cessna Citation Excel"
            "C680" -> "Cessna Citation Sovereign"
            "C700" -> "Cessna Citation Longitude"
            "CL30" -> "Bombardier Challenger 300"
            "CL35" -> "Bombardier Challenger 350"
            "CL60" -> "Bombardier Challenger 600"
            "CRJ7" -> "Bombardier CRJ700"
            "CRJ9" -> "Bombardier CRJ900"
            "DA40" -> "Diamond DA40"
            "DA42" -> "Diamond DA42"
            "GA7" -> "Grumman American GA-7 Cougar"
            "DH8D" -> "De Havilland Canada Dash 8 Q400"
            "E170" -> "Embraer 170"
            "E75L", "E75S" -> "Embraer 175"
            "E190" -> "Embraer 190"
            "E195" -> "Embraer 195"
            "E50P" -> "Embraer Phenom 100"
            "E55P" -> "Embraer Phenom 300"
            "F2TH" -> "Dassault Falcon 2000"
            "F900" -> "Dassault Falcon 900"
            "FA50" -> "Dassault Falcon 50"
            "GL5T" -> "Bombardier Global 5000"
            "GL7T" -> "Bombardier Global 7500"
            "GLEX" -> "Bombardier Global Express"
            "GLF4" -> "Gulfstream IV"
            "GLF5" -> "Gulfstream V"
            "GLF6" -> "Gulfstream G650"
            "H25B" -> "Hawker 800"
            "LJ35" -> "Learjet 35"
            "LJ45" -> "Learjet 45"
            "P28A" -> "Piper PA-28 Cherokee"
            "PA28" -> "Piper PA-28 Cherokee"
            "PA30" -> "Piper PA-30 Twin Comanche"
            "PA31" -> "Piper PA-31 Navajo"
            "PA32" -> "Piper PA-32 Cherokee Six"
            "PA34" -> "Piper PA-34 Seneca"
            "PA44" -> "Piper PA-44 Seminole"
            "P46T" -> "Piper PA-46 Malibu"
            "PC12" -> "Pilatus PC-12"
            "R44" -> "Robinson R44"
            "R66" -> "Robinson R66"
            "S22T" -> "Cirrus SR22T"
            "SF34" -> "Saab 340"
            "SR20" -> "Cirrus SR20"
            "SR22" -> "Cirrus SR22"
            "M20P" -> "Mooney M20"
            "M20T" -> "Mooney M20"
            "RV6" -> "Van's RV-6"
            "RV7" -> "Van's RV-7"
            "RV8" -> "Van's RV-8"
            "RV9" -> "Van's RV-9"
            "TBM7" -> "Socata TBM 700"
            "TBM8" -> "Socata TBM 850"
            "TBM9" -> "Daher TBM 900"
            else -> null
        }
        if (known_model != null) return known_model
        return listOfNotNull(details.manufacturer, details.type ?: details.type_code)
            .joinToString(" ")
            .trim()
            .ifEmpty { null }
    }

    private fun manufacturer_aliases(manufacturer: String): List<String> {
        val normalized = manufacturer.uppercase(Locale.US)
        return when {
            "GRUMMAN" in normalized -> listOf("Grumman American", "Grumman")
            "AMERICAN" in normalized && "GENERAL" in normalized -> listOf("American General", "Grumman American")
            "CESSNA" in normalized -> listOf("Cessna")
            "PIPER" in normalized -> listOf("Piper")
            "BEECH" in normalized -> listOf("Beechcraft", "Beech")
            "CIRRUS" in normalized -> listOf("Cirrus")
            "DIAMOND" in normalized -> listOf("Diamond")
            "MOONEY" in normalized -> listOf("Mooney")
            "ROBINSON" in normalized -> listOf("Robinson")
            "VANS" in normalized || "VAN'S" in normalized -> listOf("Van's", "Vans")
            "PILATUS" in normalized -> listOf("Pilatus")
            else -> emptyList()
        }
    }

    private fun type_model_aliases(type: String): List<String> {
        val normalized = type.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), " ").trim()
        val compact = normalized.replace(" ", "")
        return when {
            compact == "AA1" || compact.startsWith("AA1") -> listOf("AA-1", "AA1", "American Yankee")
            compact.startsWith("AA5") -> listOf("AA-5", "AA5", "Cheetah", "Tiger")
            compact.startsWith("GA7") -> listOf("GA-7", "GA7", "Cougar")
            compact.startsWith("C120") -> listOf("120")
            compact.startsWith("C140") -> listOf("140")
            compact.startsWith("C150") -> listOf("150")
            compact.startsWith("C152") -> listOf("152")
            compact.startsWith("C172") -> listOf("172 Skyhawk", "172")
            compact.startsWith("C177") -> listOf("177 Cardinal", "177")
            compact.startsWith("C182") -> listOf("182 Skylane", "182")
            compact.startsWith("C185") -> listOf("185 Skywagon", "185")
            compact.startsWith("C206") -> listOf("206 Stationair", "206")
            compact.startsWith("C210") -> listOf("210 Centurion", "210")
            compact.startsWith("C337") -> listOf("337 Skymaster", "337")
            compact.startsWith("BE33") -> listOf("Bonanza")
            compact.startsWith("BE35") -> listOf("Bonanza")
            compact.startsWith("BE36") -> listOf("Bonanza")
            compact.startsWith("BE55") -> listOf("Baron")
            compact.startsWith("BE58") -> listOf("Baron")
            compact.startsWith("BE20") -> listOf("King Air 200", "King Air")
            compact.startsWith("B350") -> listOf("King Air 350", "King Air")
            compact.startsWith("PA28") -> listOf("PA-28 Cherokee", "PA-28")
            compact.startsWith("PA32") -> listOf("PA-32 Cherokee Six", "PA-32")
            compact.startsWith("PA34") -> listOf("PA-34 Seneca", "PA-34")
            compact.startsWith("PA44") -> listOf("PA-44 Seminole", "PA-44")
            compact.startsWith("PA46") || compact.startsWith("P46T") -> listOf("PA-46 Malibu", "PA-46 Mirage", "PA-46 Meridian", "PA-46")
            compact.startsWith("SR20") -> listOf("SR20")
            compact.startsWith("SR22") || compact.startsWith("S22T") -> listOf("SR22", "SR22T")
            compact.startsWith("DA40") -> listOf("DA40")
            compact.startsWith("DA42") -> listOf("DA42")
            compact.startsWith("M20") -> listOf("M20")
            compact.startsWith("RV6") -> listOf("RV-6", "RV6")
            compact.startsWith("RV7") -> listOf("RV-7", "RV7")
            compact.startsWith("RV8") -> listOf("RV-8", "RV8")
            compact.startsWith("RV9") -> listOf("RV-9", "RV9")
            compact.startsWith("R44") -> listOf("R44 Raven", "R44")
            compact.startsWith("R66") -> listOf("R66")
            compact.startsWith("PC12") -> listOf("PC-12", "PC12")
            else -> emptyList()
        }
    }

    private fun normalize_aircraft_search_name(value: String): String {
        return value
            .replace(Regex("\\bINC\\.?\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bCORP\\.?\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bAVN\\.?\\b", RegexOption.IGNORE_CASE), " Aviation ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}


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
                view_type = AircraftPhotoViewType.EXTERIOR
            )
            items += fetch_gallery_item_for_queries(
                queries = listOf("\"$model\" cockpit", "$model cockpit"),
                title = "Representative cockpit",
                caption = "Representative cockpit view for $model; not this exact aircraft",
                view_type = AircraftPhotoViewType.COCKPIT
            )
            items += fetch_gallery_item_for_queries(
                queries = listOf("\"$model\" cabin interior", "$model cabin interior", "$model interior"),
                title = "Representative interior",
                caption = "Representative interior/cabin view for $model; not this exact aircraft",
                view_type = AircraftPhotoViewType.INTERIOR
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
        view_type: AircraftPhotoViewType
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
                            quality = PhotoQuality.REPRESENTATIVE,
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
        EXTERIOR_PREFERRED_TEXT.findAll(text).forEach { _ -> score += 4 }
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
        val api_url = "https://commons.wikimedia.org/w/api.php" +
            "?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10" +
            "&gsrsearch=$encoded&prop=imageinfo&iiprop=url|mime|extmetadata"
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
                verification_text = plain_text_from_html(metadata_text)
            )
        }
        return candidates.distinctBy { it.image_url }.take(MAX_SEARCH_PHOTO_CANDIDATES_PER_QUERY)
    }

    // Pull a small proof quote from the source page when it mentions the aircraft or model terms.
    private fun fetch_verification_quote(page_url: String, terms: List<String>): VerificationQuote? {
        val html = fetch_text(page_url) ?: return null
        return quote_from_text(plain_text_from_html(html), terms)
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

    // JSON helpers reject non-HTTPS URLs before any request leaves the app.
    private fun fetch_json_object(url: String): JSONObject? =
        fetch_json_object(url, user_agent)

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


    private fun normalized_registration(value: String?): String? =
        normalized_photo_registration(value)

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

internal data class ExactPhotoCandidate(
    val image_url: String,
    val source_name: String,
    val page_url: String,
    val note: String
)

internal data class CachedPhotoResult(
    val photo: AircraftPhotoResult.Found,
    val stored_at_ms: Long
)



sealed class AircraftPhotoResult {
    data class Found(
        val bitmap: Bitmap,
        val note: String,
        val evidence: PhotoEvidence? = null,
        val quality: PhotoQuality
    ) : AircraftPhotoResult()

    data class Unavailable(val reason: String) : AircraftPhotoResult()
}


enum class PhotoQuality(val rank: Int) {
    INVESTIGABLE(1),
    REPRESENTATIVE(2),
    EXACT(3)
}


data class PhotoEvidence(
    val source_name: String,
    val image_url: String,
    val page_url: String,
    val search_query: String,
    val quote: String,
    val matched_terms: List<String>
)


data class SearchImageCandidate(
    val image_url: String,
    val page_url: String,
    val source_name: String,
    val title: String = "",
    val verification_text: String? = null
)


data class AircraftPhotoGalleryItem(
    val bitmap: Bitmap,
    val title: String,
    val caption: String,
    val evidence: PhotoEvidence?,
    val quality: PhotoQuality,
    val view_type: AircraftPhotoViewType
)


enum class AircraftPhotoViewType {
    EXTERIOR,
    INTERIOR,
    COCKPIT,
    CABIN
}


data class VerificationQuote(
    val text: String,
    val matched_terms: List<String>
)
