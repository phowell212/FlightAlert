package com.flightalert.data.web

import com.flightalert.data.AircraftMetadataSeed
import com.flightalert.data.AircraftTelemetry
import com.flightalert.data.FeedAircraft
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedSource
import com.flightalert.data.airplaneslive.AirplanesLiveHttp
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ln

internal data class GlobeBinCraftSnapshot(
    val aircraft: List<FeedAircraft>,
    val epoch_sec: Double,
    val partial_coverage: Boolean
)

internal class GlobeBinCraftClient(private val user_agent: String) {
    fun fetch(bounds: FeedBounds): GlobeBinCraftSnapshot? {
        var connection: HttpURLConnection? = null
        return try {
            val normalized = bounds.normalized()
            val url = URL(
                String.format(
                    Locale.US,
                    "%s/re-api/?binCraft&zstd&box=%.6f,%.6f,%.6f,%.6f",
                    AirplanesLiveHttp.GLOBE_BASE_URL,
                    normalized.min_lat,
                    normalized.max_lat,
                    normalized.min_lon,
                    normalized.max_lon
                )
            )
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 9000
                requestMethod = "GET"
                AirplanesLiveHttp.apply_browser_headers(
                    connection = this,
                    app_user_agent = user_agent,
                    referer = "${AirplanesLiveHttp.GLOBE_BASE_URL}/?nowebgl",
                    accept = "*/*"
                )
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            val compressed = connection.inputStream.use { it.readBytes() }
            val decoded = ZstdInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            parse(decoded)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(bytes: ByteArray): GlobeBinCraftSnapshot? {
        if (bytes.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val now_sec = u32(buffer, 0) / 1000.0 + u32(buffer, 4) * 4294967.296
        val stride = int(buffer, 8)
        if (stride < MIN_STRIDE_BYTES || stride > MAX_STRIDE_BYTES || bytes.size < stride) return null
        val global_with_position = int(buffer, 12)
        val messages = u32(buffer, 28)
        val bin_craft_version = int(buffer, 40)
        val message_rate = u32(buffer, 44) / 10.0
        val flags = int(buffer, 48)
        val use_message_rate = flags and 1 != 0
        val parsed = ArrayList<FeedAircraft>(global_with_position.coerceIn(512, 32768))
        var offset = stride
        while (offset + stride <= bytes.size) {
            parse_aircraft(buffer, offset, stride, bin_craft_version, use_message_rate, now_sec)?.let(parsed::add)
            offset += stride
        }
        return GlobeBinCraftSnapshot(
            aircraft = parsed,
            epoch_sec = now_sec,
            partial_coverage = parsed.isEmpty() && (global_with_position > 0 || messages > 0 || message_rate > 0.0)
        )
    }

    private fun parse_aircraft(
        buffer: ByteBuffer,
        offset: Int,
        stride: Int,
        version: Int,
        use_message_rate: Boolean,
        now_sec: Double
    ): FeedAircraft? {
        val raw_hex = int(buffer, offset)
        val non_icao = raw_hex and (1 shl 24) != 0
        val hex = (raw_hex and ((1 shl 24) - 1)).toString(16).padStart(6, '0').let {
            if (non_icao) "~$it" else it
        }
        val seen = if (version >= 20240218) int(buffer, offset + 4) / 10.0 else u16(buffer, offset + 6) / 10.0
        val seen_pos = if (version >= 20240218 && stride >= 112) {
            int(buffer, offset + 27 * 4) / 10.0
        } else {
            u16(buffer, offset + 4) / 10.0
        }
        val lon_raw = int(buffer, offset + 8)
        val lat_raw = int(buffer, offset + 12)
        val validity_73 = u8(buffer, offset + 73)
        if (validity_73 and 64 == 0 || lon_raw == Int.MAX_VALUE || lat_raw == Int.MAX_VALUE) return null
        val lon = lon_raw / 1_000_000.0
        val lat = lat_raw / 1_000_000.0
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        val airground = u8(buffer, offset + 68) and 15
        val on_ground = airground == 1
        val valid_74 = u8(buffer, offset + 74)
        val valid_75 = u8(buffer, offset + 75)
        val valid_76 = u8(buffer, offset + 76)
        val valid_77 = u8(buffer, offset + 77)
        val category = u8(buffer, offset + 64).takeIf { it != 0 }?.toString(16)?.uppercase(Locale.US)
        val type_code = ascii(buffer, offset + 88, 4)
        val registration = ascii(buffer, offset + 92, 12)
        val flight = ascii(buffer, offset + 78, 8)
        val callsign = flight ?: registration ?: hex.trimStart('~')
        val altitude_feet = when {
            on_ground -> 0.0
            validity_73 and 16 != 0 -> s16(buffer, offset + 20) * 25.0
            validity_73 and 32 != 0 -> s16(buffer, offset + 22) * 25.0
            else -> null
        }
        val speed_kt = if (validity_73 and 128 != 0) s16(buffer, offset + 34) / 10.0 else null
        val track = if (valid_74 and 8 != 0) s16(buffer, offset + 40) / 90.0 else null
        val vertical_rate_fpm = when {
            valid_75 and 1 != 0 -> s16(buffer, offset + 16) * 8.0
            valid_75 and 2 != 0 -> s16(buffer, offset + 18) * 8.0
            else -> null
        }
        val db_flags = u16(buffer, offset + 86).takeIf { it != 0 }
        val receiver_count = u8(buffer, offset + 104).takeIf { stride > 104 && it != 0 }
        val rssi = if (stride > 105) {
            if (version >= 20250403) {
                u8(buffer, offset + 105) * (50.0 / 255.0) - 50.0
            } else {
                val level = u8(buffer, offset + 105).let { it * it / 65025.0 + 1.125e-5 }
                10.0 * ln(level) / ln(10.0)
            }
        } else {
            null
        }
        val telemetry = AircraftTelemetry(
            source_type = source_type_label(u8(buffer, offset + 67) ushr 4),
            squawk = if (valid_76 and 4 != 0) squawk(buffer, offset + 32) else null,
            baro_altitude_m = if (validity_73 and 16 != 0 || on_ground) (if (on_ground) 0.0 else s16(buffer, offset + 20) * 25.0 / FEET_PER_METER) else null,
            geom_altitude_m = if (validity_73 and 32 != 0) s16(buffer, offset + 22) * 25.0 / FEET_PER_METER else null,
            ground_speed_ms = speed_kt?.let { it * KNOTS_TO_METERS_PER_SECOND },
            true_speed_ms = if (valid_74 and 2 != 0) u16(buffer, offset + 56) * KNOTS_TO_METERS_PER_SECOND else null,
            indicated_speed_ms = if (valid_74 and 1 != 0) u16(buffer, offset + 58) * KNOTS_TO_METERS_PER_SECOND else null,
            mach = if (valid_74 and 4 != 0) s16(buffer, offset + 36) / 1000.0 else null,
            baro_rate_ms = if (valid_75 and 1 != 0) s16(buffer, offset + 16) * 8.0 / FEET_PER_METER / 60.0 else null,
            geom_rate_ms = if (valid_75 and 2 != 0) s16(buffer, offset + 18) * 8.0 / FEET_PER_METER / 60.0 else null,
            selected_altitude_m = selected_altitude_m(buffer, offset, valid_76),
            selected_heading_deg = if (valid_77 and 2 != 0) s16(buffer, offset + 30) / 90.0 else null,
            wind_speed_ms = if (valid_77 and 16 != 0) s16(buffer, offset + 50) * KNOTS_TO_METERS_PER_SECOND else null,
            wind_direction_deg = if (valid_77 and 16 != 0) s16(buffer, offset + 48).toDouble() else null,
            tat_c = if (valid_77 and 32 != 0) s16(buffer, offset + 54).toDouble() else null,
            oat_c = if (valid_77 and 32 != 0) s16(buffer, offset + 52).toDouble() else null,
            qnh_hpa = if (valid_76 and 32 != 0) s16(buffer, offset + 28) / 10.0 else null,
            true_heading_deg = if (valid_74 and 128 != 0) s16(buffer, offset + 46) / 90.0 else null,
            magnetic_heading_deg = if (valid_74 and 64 != 0) s16(buffer, offset + 44) / 90.0 else null,
            track_rate_deg_per_sec = if (valid_74 and 16 != 0) s16(buffer, offset + 42) / 100.0 else null,
            roll_deg = if (valid_74 and 32 != 0) s16(buffer, offset + 38) / 100.0 else null,
            nav_modes = if (valid_77 and 4 != 0) nav_modes(u8(buffer, offset + 66)) else emptyList(),
            adsb_version = adsb_version_label(buffer, offset),
            nac_p = if (valid_75 and 32 != 0) u8(buffer, offset + 71) and 15 else null,
            nac_v = if (valid_75 and 64 != 0) (u8(buffer, offset + 71) and 240) ushr 4 else null,
            sil = if (valid_75 and 128 != 0) u8(buffer, offset + 72) and 3 else null,
            sil_type = if (valid_75 and 128 != 0) sil_type_label(u8(buffer, offset + 69) and 15) else null,
            nic_baro = if (valid_75 and 16 != 0) (u8(buffer, offset + 73) and 1).toString() else null,
            rc_m = if (u8(buffer, offset + 71) and 15 != 0) u16(buffer, offset + 60).toDouble() else null,
            rssi = rssi,
            message_rate = if (use_message_rate) u16(buffer, offset + 62) / 10.0 else null,
            receiver_count_label = receiver_count?.toString(),
            category_label = category
        ).takeIf { it.has_values }
        return FeedAircraft(
            icao24 = hex,
            callsign = callsign,
            registration = registration,
            type_code = type_code,
            metadata = AircraftMetadataSeed(
                source_name = FeedSource.AIRPLANES_LIVE_GLOBE.display_name,
                registration = registration,
                type_code = type_code
            ).takeIf { it.has_details },
            db_flags = db_flags,
            lat = lat,
            lon = lon,
            on_ground = on_ground,
            altitude_m = altitude_feet?.let { it / FEET_PER_METER },
            velocity_ms = speed_kt?.let { it * KNOTS_TO_METERS_PER_SECOND },
            track_deg = track,
            vertical_rate_ms = vertical_rate_fpm?.let { it / FEET_PER_METER / 60.0 },
            category = category_from_globe(category, type_code),
            position_time_sec = now_sec - seen_pos,
            last_contact_sec = now_sec - seen,
            distance_m = 0.0,
            telemetry = telemetry
        )
    }

    private fun selected_altitude_m(buffer: ByteBuffer, offset: Int, valid_76: Int): Double? {
        val mcp = if (valid_76 and 64 != 0) u16(buffer, offset + 24) * 4.0 else null
        val fms = if (valid_76 and 128 != 0) u16(buffer, offset + 26) * 4.0 else null
        return (mcp ?: fms)?.let { it / FEET_PER_METER }
    }

    private fun source_type_label(type: Int): String {
        return when (type) {
            0 -> "adsb_icao"
            1 -> "adsb_icao_nt"
            2 -> "adsr_icao"
            3 -> "tisb_icao"
            4 -> "adsc"
            5 -> "mlat"
            6 -> "other"
            7 -> "mode_s"
            8 -> "adsb_other"
            9 -> "adsr_other"
            10 -> "tisb_trackfile"
            11 -> "tisb_other"
            12 -> "mode_ac"
            else -> "unknown"
        }
    }

    private fun adsb_version_label(buffer: ByteBuffer, offset: Int): String? {
        val source_type = u8(buffer, offset + 67) ushr 4
        val version = when {
            source_type in setOf(0, 1, 8) -> (u8(buffer, offset + 69) and 240) ushr 4
            source_type in setOf(2, 9) -> u8(buffer, offset + 70) and 15
            source_type in setOf(3, 10, 11) -> (u8(buffer, offset + 70) and 240) ushr 4
            else -> return null
        }
        return "v$version"
    }

    private fun sil_type_label(value: Int): String {
        return when (value) {
            1 -> "per_sample"
            2 -> "per_hour"
            else -> value.toString()
        }
    }

    private fun nav_modes(bits: Int): List<String> {
        val result = ArrayList<String>(6)
        if (bits and 1 != 0) result += "autopilot"
        if (bits and 2 != 0) result += "vnav"
        if (bits and 4 != 0) result += "alt_hold"
        if (bits and 8 != 0) result += "approach"
        if (bits and 16 != 0) result += "lnav"
        if (bits and 32 != 0) result += "tcas"
        return result
    }

    private fun squawk(buffer: ByteBuffer, offset: Int): String {
        val raw = u16(buffer, offset).toString(16).padStart(4, '0')
        return if (raw[0] > '9') {
            parse_hex_digit(raw[0]).toString() + raw.substring(1)
        } else {
            raw
        }
    }

    private fun parse_hex_digit(value: Char): Int {
        return value.digitToIntOrNull(16) ?: 0
    }

    private fun ascii(buffer: ByteBuffer, offset: Int, max_length: Int): String? {
        val builder = StringBuilder(max_length)
        var index = 0
        while (index < max_length && offset + index < buffer.limit()) {
            val byte = u8(buffer, offset + index)
            if (byte == 0) break
            builder.append(byte.toChar())
            index++
        }
        return builder.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun category_from_globe(category: String?, type_code: String?): Int? {
        val normalized = category?.trim()?.uppercase(Locale.US).orEmpty()
        if (normalized.startsWith("A7") || normalized.startsWith("B7")) return 8
        if (normalized.startsWith("B1")) return 9
        if (normalized.startsWith("B6")) return 14
        if (normalized.startsWith("C") || normalized.startsWith("D")) return 15
        val type = type_code?.trim()?.uppercase(Locale.US).orEmpty()
        return when {
            type.startsWith("H") || type.startsWith("R") -> 8
            type.startsWith("GL") -> 9
            type.startsWith("UAV") || type.startsWith("DRON") -> 14
            else -> null
        }
    }

    private fun int(buffer: ByteBuffer, offset: Int): Int = buffer.getInt(offset)

    private fun u32(buffer: ByteBuffer, offset: Int): Long = int(buffer, offset).toLong() and 0xffffffffL

    private fun s16(buffer: ByteBuffer, offset: Int): Short = buffer.getShort(offset)

    private fun u16(buffer: ByteBuffer, offset: Int): Int = buffer.getShort(offset).toInt() and 0xffff

    private fun u8(buffer: ByteBuffer, offset: Int): Int = buffer.get(offset).toInt() and 0xff

    private companion object {
        const val HEADER_BYTES = 52
        const val MIN_STRIDE_BYTES = 108
        const val MAX_STRIDE_BYTES = 160
        const val FEET_PER_METER = 3.28084
        const val KNOTS_TO_METERS_PER_SECOND = 0.514444
    }
}
