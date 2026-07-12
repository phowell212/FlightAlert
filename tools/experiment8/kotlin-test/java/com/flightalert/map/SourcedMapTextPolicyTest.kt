package com.flightalert.map

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcedMapTextPolicyTest {
    private val repositoryRoot: File by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
            it.parentFile
        }
            .first { File(it, CONFORMANCE_PATH).isFile }
    }
    private val conformance: JSONObject by lazy {
        verifiedDocument(CONFORMANCE_PATH, CONFORMANCE_BYTES, CONFORMANCE_SHA256)
    }

    @Test
    fun frozenIdentitiesAndProductDependenciesAreExact() {
        val identities = conformance.getJSONObject("identities")
        assertEquals(1, identities.getInt("sharedPolicyCount"))
        assertEquals(1, identities.getInt("decisionPathCount"))
        assertEquals(1, identities.getInt("presentationTokenCount"))
        assertEquals(4_096, SourcedMapTextPolicy.maxSourcedTextUtf8Bytes)
        assertEquals(identities.getString("profileSha256"), SourcedMapTextPolicy.unicodeScriptProfileSha256)
        assertEquals(identities.getString("policySha256"), SourcedMapTextPolicy.policySha256)
        assertEquals(identities.getString("decisionPathId"), SourcedMapTextPolicy.decisionPathId)
        assertEquals(
            identities.getString("bilingualPresentationToken"),
            SourcedMapTextPolicy.bilingualPresentationToken,
        )

        val productFiles = listOf(
            "app/src/main/java/com/flightalert/map/SourcedMapText.kt",
            "app/src/main/java/com/flightalert/map/SourcedMapTextPolicy.kt",
            "app/src/main/java/com/flightalert/map/SourcedTextUnicode17Profile.kt",
        ).map { File(repositoryRoot, it) }
        assertTrue(productFiles.all(File::isFile))
        val productSource = productFiles.joinToString("\n") { it.readText(Charsets.UTF_8) }
        listOf(
            "org.json",
            "JSONObject",
            "JSONArray",
            "JSONTokener",
            "Character.UnicodeScript",
            "java.lang.Character.UnicodeScript",
        ).forEach { forbidden ->
            assertFalse("product sourced-text code contains $forbidden", productSource.contains(forbidden))
        }
    }

    @Test
    fun canonicalEncoderAcceptsOnlyASealedPolicyDecision() {
        val encoders = SourcedMapTextBinaryCodec::class.java.declaredMethods.filter {
            it.name.startsWith("encode")
        }
        assertEquals(1, encoders.size)
        assertEquals(1, encoders.single().parameterCount)
        assertEquals("SourcedTextDecision", encoders.single().parameterTypes.single().simpleName)
    }

    @Test
    fun decisionProofAndStrictSurrogatesCannotForgeCanonicalState() {
        val constructor = SourcedTextDecision::class.java.declaredConstructors.single {
            it.parameterCount == 9
        }
        constructor.isAccessible = true
        val forged = assertThrows(InvocationTargetException::class.java) {
            constructor.newInstance(
                "Latin",
                1L,
                null,
                null,
                SourcedTextLayoutMode.SINGLE,
                SourcedTextEnglishGapReason.MISSING,
                SourcedTextScriptSignals(false, true, false),
                null,
                Any(),
            )
        }
        assertTrue(forged.cause is SourcedMapTextException)

        val invalidPrimary = assertThrows(SourcedMapTextException::class.java) {
            SourcedMapTextPolicy.create("\ud800", 1uL)
        }
        assertEquals(SourcedTextErrorCode.PRIMARY_INVALID_UTF8, invalidPrimary.code)

        val rejectedEnglish = SourcedMapTextPolicy.create(
            primary = "\u0627",
            primarySourceFieldId = 2uL,
            declaredEnglish = "\ud800",
            englishSourceFieldId = 3uL,
        )
        assertEquals(SourcedTextEnglishGapReason.INVALID_UTF8, rejectedEnglish.englishGapReason)
        assertNull(rejectedEnglish.englishText)
        assertFalse(rejectedEnglish.canonicalBytes.containsSubsequence(byteArrayOf(0xef.toByte(), 0xbf.toByte(), 0xbd.toByte())))
        val decoded = SourcedMapTextBinaryCodec.decode(
            rejectedEnglish.canonicalBytes,
            rejectedEnglish.fullSha256.hex(),
        )
        assertEquals(rejectedEnglish.primaryText, decoded.primaryText)
        assertEquals(rejectedEnglish.englishGapReason, decoded.englishGapReason)
    }

    @Test
    fun everyFrozenScalarVectorUsesTheCompiledUnicode17Profile() {
        conformance.getJSONArray("scalarCases").objects().forEach { case ->
            val expected = case.getString("expected")
            if (expected == "ERROR") {
                assertThrows(SourcedMapTextException::class.java) {
                    SourcedMapTextPolicy.classifyScalar(case.getInt("scalar"))
                }
            } else {
                assertEquals(
                    case.getString("id"),
                    SourcedTextScalarClass.valueOf(expected),
                    SourcedMapTextPolicy.classifyScalar(case.getInt("scalar")),
                )
            }
        }
    }

    @Test
    fun everyFrozenUnicode17IntervalBoundaryHasItsExactPolicyClass() {
        val profile = verifiedDocument(PROFILE_PATH, PROFILE_BYTES, PROFILE_SHA256)
        val intervals = profile.getJSONArray("intervals")
        assertEquals(1_718, intervals.length())
        repeat(intervals.length()) { index ->
            val interval = intervals.getJSONArray(index)
            val expected = when (interval.getString(2)) {
                "Common", "Inherited" -> SourcedTextScalarClass.NEUTRAL
                "Latin" -> SourcedTextScalarClass.STRONG_LATIN
                "Unknown" -> SourcedTextScalarClass.UNKNOWN
                else -> SourcedTextScalarClass.STRONG_NON_LATIN
            }
            listOf(interval.getInt(0), interval.getInt(1)).distinct().forEach { scalar ->
                assertEquals(
                    "profile interval $index scalar $scalar",
                    expected,
                    SourcedMapTextPolicy.classifyScalar(scalar),
                )
            }
        }
    }

    @Test
    fun everyTextAndSourceExactVectorPreservesExactScalarsAndSignals() {
        conformance.getJSONArray("textCases").objects().forEachIndexed { index, case ->
            val expected = case.getJSONObject("expected")
            val record = SourcedMapTextPolicy.create(
                primary = case.getString("text"),
                primarySourceFieldId = (10_000 + index).toULong(),
            )
            assertEquals(case.getString("id"), expected.getString("canonicalText"), record.primaryText)
            assertSignals(case.getString("id"), expected.getJSONObject("signals"), record.primaryScriptSignals)
            assertEquals(expected.getBoolean("bilingualEligible"), record.primaryScriptSignals.hasStrongNonLatin)
        }
        conformance.getJSONArray("sourceExactCases").objects().forEachIndexed { index, case ->
            val expected = case.getJSONObject("expected")
            val record = SourcedMapTextPolicy.create(
                primary = case.getString("text"),
                primarySourceFieldId = (20_000 + index).toULong(),
            )
            assertEquals(case.getString("id"), expected.getString("canonicalText"), record.primaryText)
            assertSignals(case.getString("id"), expected.getJSONObject("signals"), record.primaryScriptSignals)
        }
    }

    @Test
    fun everyRecordVectorHasExactLayoutGapSignalsCanonicalIdentityAndHotId() {
        conformance.getJSONArray("recordCases").objects().forEach { case ->
            val expected = case.getJSONObject("expected")
            val record = create(case.getJSONObject("input"))
            val label = case.getString("id")
            assertEquals(label, expected.getString("primaryText"), record.primaryText)
            assertEquals(label, expected.get("primarySourceFieldId").toString().toULong(), record.primarySourceFieldId)
            assertNullableString(label, expected, "englishText", record.englishText)
            assertNullableULong(label, expected, "englishSourceFieldId", record.englishSourceFieldId)
            assertEquals(label, SourcedTextLayoutMode.valueOf(expected.getString("layout")), record.layoutMode)
            assertEquals(
                label,
                SourcedTextEnglishGapReason.valueOf(expected.getString("englishGap")),
                record.englishGapReason,
            )
            assertSignals(label, expected.getJSONObject("primarySignals"), record.primaryScriptSignals)
            if (expected.isNull("englishSignals")) {
                assertNull(label, record.englishScriptSignals)
            } else {
                assertSignals(label, expected.getJSONObject("englishSignals"), record.englishScriptSignals!!)
            }
            if (expected.has("canonicalBytesHex")) {
                assertArrayEquals(label, expected.getString("canonicalBytesHex").hexBytes(), record.canonicalBytes)
            }
            assertEquals(label, expected.getString("canonicalSha256"), record.canonicalSha256.hex())
            assertEquals(label, expected.getString("fullSha256"), record.fullSha256.hex())
            assertEquals(label, expected.getString("hotIdHex"), record.hotIdBytes.hex())
            assertEquals(label, expected.getString("hotIdHex").toULong(16), record.hotId)

            val decoded = SourcedMapTextBinaryCodec.decode(record.canonicalBytes, record.fullSha256.hex())
            assertEquals(label, record.primaryText, decoded.primaryText)
            assertEquals(label, record.englishText, decoded.englishText)
            assertEquals(label, record.layoutMode, decoded.layoutMode)
            assertEquals(label, record.englishGapReason, decoded.englishGapReason)
        }
    }

    @Test
    fun everyInvalidVectorFailsWithItsExactErrorCode() {
        conformance.getJSONArray("invalidCases").objects().forEach { case ->
            val error = assertThrows(case.getString("id"), SourcedMapTextException::class.java) {
                create(case.getJSONObject("input"))
            }
            assertEquals(
                case.getString("id"),
                SourcedTextErrorCode.valueOf(case.getString("errorCode")),
                error.code,
            )
        }
    }

    @Test
    fun vectorIdentityGroupsProveExactCanonicalEquivalenceAndMutation() {
        val records = conformance.getJSONArray("recordCases").objects().associate { case ->
            case.getString("id") to create(case.getJSONObject("input"))
        }
        conformance.getJSONArray("canonicalEquivalenceGroups").objects().forEach { group ->
            val recordsInGroup = group.getJSONArray("caseIds").strings().map(records::getValue)
            recordsInGroup.drop(1).forEach { record ->
                assertArrayEquals(group.getString("id"), recordsInGroup.first().canonicalBytes, record.canonicalBytes)
            }
        }
        conformance.getJSONArray("canonicalNonEquivalenceGroups").objects().forEach { group ->
            val recordsInGroup = group.getJSONArray("caseIds").strings().map(records::getValue)
            assertEquals(
                group.getString("id"),
                recordsInGroup.size,
                recordsInGroup.map { it.canonicalSha256.hex() }.toSet().size,
            )
        }
        conformance.getJSONArray("identityMutationGroups").objects().forEach { group ->
            val recordsInGroup = group.getJSONArray("caseIds").strings().map(records::getValue)
            assertEquals(
                group.getString("id"),
                recordsInGroup.size,
                recordsInGroup.map { it.fullSha256.hex() }.toSet().size,
            )
            assertNotEquals(0uL, recordsInGroup.first().hotId)
        }
    }

    private fun create(input: JSONObject): SourcedMapText = SourcedMapTextPolicy.create(
        primary = sourceValue(input, "primary"),
        primarySourceFieldId = fieldId(input, "primarySourceFieldId"),
        declaredEnglish = sourceValue(input, "english"),
        englishSourceFieldId = fieldId(input, "englishSourceFieldId"),
    )

    private fun sourceValue(input: JSONObject, name: String): Any? {
        val scalarKey = "${name}Scalars"
        if (input.has(scalarKey)) return scalarString(input.getJSONArray(scalarKey))
        val repeatKey = "${name}Repeat"
        if (input.has(repeatKey)) {
            val repeat = input.getJSONObject(repeatKey)
            return repeat.getString("text").repeat(repeat.getInt("count"))
        }
        if (!input.has(name) || input.isNull(name)) return null
        return input.get(name)
    }

    private fun fieldId(input: JSONObject, name: String): Any? {
        if (!input.has(name) || input.isNull(name)) return null
        val raw = input.get(name)
        if (raw !is Number) return raw
        val integer = runCatching { BigInteger(raw.toString()) }.getOrNull() ?: return raw
        return if (integer >= BigInteger.ZERO && integer <= U64_MAX) integer.toString().toULong() else raw
    }

    private fun scalarString(scalars: JSONArray): String = buildString {
        scalars.ints().forEach { scalar ->
            if (scalar in 0xD800..0xDFFF) {
                append(scalar.toChar())
            } else {
                appendCodePoint(scalar)
            }
        }
    }

    private fun verifiedDocument(path: String, expectedBytes: Int, expectedSha256: String): JSONObject {
        val raw = File(repositoryRoot, path).readBytes()
        assertEquals("$path byte length", expectedBytes, raw.size)
        assertEquals("$path SHA-256", expectedSha256, sha256(raw))
        return JSONObject(raw.toString(Charsets.UTF_8))
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .hex()

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
    }

    private fun assertSignals(label: String, expected: JSONObject, actual: SourcedTextScriptSignals) {
        assertEquals(label, expected.getBoolean("hasStrongLatin"), actual.hasStrongLatin)
        assertEquals(label, expected.getBoolean("hasStrongNonLatin"), actual.hasStrongNonLatin)
        assertEquals(label, expected.getBoolean("hasUnknown"), actual.hasUnknown)
    }

    private fun assertNullableString(label: String, expected: JSONObject, key: String, actual: String?) {
        if (expected.isNull(key)) assertNull(label, actual) else assertEquals(label, expected.getString(key), actual)
    }

    private fun assertNullableULong(label: String, expected: JSONObject, key: String, actual: ULong?) {
        if (expected.isNull(key)) {
            assertNull(label, actual)
        } else {
            assertEquals(label, expected.get(key).toString().toULong(), actual)
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length(), ::getJSONObject)
    private fun JSONArray.strings(): List<String> = List(length(), ::getString)
    private fun JSONArray.ints(): List<Int> = List(length(), ::getInt)
    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val CONFORMANCE_PATH =
            "tools/experiment8/data/sourced-text-conformance-v1.json"
        private const val PROFILE_PATH =
            "tools/experiment8/data/unicode-script-profile-17.0.0.json"
        private const val CONFORMANCE_BYTES = 38_390
        private const val CONFORMANCE_SHA256 =
            "c5f5e9f7ab8d2f9fde7317e217e36331814c72fe280fcc023109e3ba4225c18d"
        private const val PROFILE_BYTES = 41_325
        private const val PROFILE_SHA256 =
            "4df49aafa0b507ca5517277c5f3db7faf855196a4b3a2124f4fae4e1f386fbeb"
        private val U64_MAX = BigInteger("18446744073709551615")
    }
}
