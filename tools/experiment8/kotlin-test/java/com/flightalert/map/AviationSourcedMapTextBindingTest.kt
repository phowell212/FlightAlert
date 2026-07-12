package com.flightalert.map

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AviationSourcedMapTextBindingTest {
    @Test
    fun airspaceCachesExactNameWithoutFabricatingTypeIntoPrimaryText() {
        val feature = airspace(name = "São Paulo", type = "FIR")

        val sourced = feature.map_text

        assertSame(sourced, feature.map_text)
        assertEquals("São Paulo", sourced.primaryText)
        assertEquals("FIR", feature.type)
        assertEquals(
            AviationMapTextSourceField.AIRSPACE_NAME.stable_source_field_id,
            sourced.primarySourceFieldId,
        )
        assertTrue(sourced.primarySourceFieldId != 0uL)
        assertNull(sourced.englishText)
        assertNull(sourced.englishSourceFieldId)
        assertEquals(SourcedTextLayoutMode.SINGLE, sourced.layoutMode)
        assertEquals(
            SourcedTextEnglishGapReason.PRIMARY_NOT_ELIGIBLE,
            sourced.englishGapReason,
        )
    }

    @Test
    fun providerRoleIdsArePinnedNonzeroConstants() {
        val expected = mapOf(
            AviationMapTextSourceField.AIRSPACE_NAME to 0x4641_4141_0001_0001uL,
            AviationMapTextSourceField.AIRSPACE_IDENT to 0x4641_4141_0001_0002uL,
            AviationMapTextSourceField.AIRPORT_ICAO_ID to 0x4641_4141_0002_0001uL,
            AviationMapTextSourceField.AIRPORT_IDENT to 0x4641_4141_0002_0002uL,
            AviationMapTextSourceField.AIRPORT_NAME to 0x4641_4141_0002_0003uL,
            AviationMapTextSourceField.NAT_DESIGNATOR to 0x4641_414e_0001_0001uL,
        )

        assertEquals(expected.keys, AviationMapTextSourceField.entries.toSet())
        assertEquals(expected.values.toSet().size, expected.size)
        expected.forEach { (role, id) ->
            assertEquals(id, role.stable_source_field_id)
            assertTrue(id != 0uL)
        }
    }

    @Test
    fun adapterLeavesCanonicalBlankAndScalarDecisionsToTheSharedPolicy() {
        val runtime_only_whitespace = airspace(name = "\u001c", type = "FIR")

        assertEquals("\u001c", runtime_only_whitespace.map_text.primaryText)
        assertEquals(
            AviationMapTextSourceField.AIRSPACE_NAME.stable_source_field_id,
            runtime_only_whitespace.map_text.primarySourceFieldId,
        )
    }

    @Test
    fun airportCachesExactIdentAndUsesExactNameOnlyWhenIdentIsAbsent() {
        val identified = AviationAirportFeature(
            1L,
            "KJFK",
            "John F Kennedy Intl",
            "AD",
            "CIVIL",
            1.0,
            2.0,
        )
        val named = AviationAirportFeature(
            2L,
            "",
            "L'Île Airport",
            "AD",
            "CIVIL",
            1.0,
            2.0,
        )

        assertSame(identified.map_text, identified.map_text)
        assertEquals("KJFK", identified.map_text.primaryText)
        assertEquals(
            AviationMapTextSourceField.AIRPORT_IDENT.stable_source_field_id,
            identified.map_text.primarySourceFieldId,
        )
        assertEquals("L'Île Airport", named.map_text.primaryText)
        assertEquals(
            AviationMapTextSourceField.AIRPORT_NAME.stable_source_field_id,
            named.map_text.primarySourceFieldId,
        )
    }

    @Test
    fun ingestionRetainsTheExactArcGisRoleUsedForEachPrimary() {
        val airspaces = AviationGeoJsonParser.parse_airspaces(
            JSONObject(
                """{"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"OBJECTID":1,"IDENT":"ZNY","TYPE_CODE":"ARTCC"},
                   "geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}},
                  {"type":"Feature","properties":{"OBJECTID":4,"NAME":" Leading","TYPE_CODE":"FIR"},
                   "geometry":{"type":"Polygon","coordinates":[[[2,2],[3,2],[3,3],[2,2]]]}}
                ]}""",
            ),
        )
        val airports = AviationGeoJsonParser.parse_airports(
            JSONObject(
                """{"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"OBJECTID":2,"ICAO_ID":"KJFK","IDENT":"JFK","NAME":"John F Kennedy Intl","TYPE_CODE":"AD","MIL_CODE":"CIVIL"},
                   "geometry":{"type":"Point","coordinates":[-73.7,40.6]}},
                  {"type":"Feature","properties":{"OBJECTID":3,"NAME":"Exact Name Field","TYPE_CODE":"AD","MIL_CODE":"CIVIL"},
                   "geometry":{"type":"Point","coordinates":[-74.0,40.7]}}
                ]}""",
            ),
        )

        assertEquals(listOf("ZNY", " Leading"), airspaces.records.map { it.map_text.primaryText })
        assertEquals(
            listOf(
                AviationMapTextSourceField.AIRSPACE_IDENT.stable_source_field_id,
                AviationMapTextSourceField.AIRSPACE_NAME.stable_source_field_id,
            ),
            airspaces.records.map { it.map_text.primarySourceFieldId },
        )
        assertEquals(listOf("KJFK", "Exact Name Field"), airports.records.map { it.map_text.primaryText })
        assertEquals(
            listOf(
                AviationMapTextSourceField.AIRPORT_ICAO_ID.stable_source_field_id,
                AviationMapTextSourceField.AIRPORT_NAME.stable_source_field_id,
            ),
            airports.records.map { it.map_text.primarySourceFieldId },
        )
    }

    @Test
    fun natCachesOnlyTheExactDesignatorAsItsMapPrimary() {
        val track = AviationOceanicTrack(
            designator = "A",
            source_notam = null,
            icao_id = null,
            part_number = null,
            declared_part_number = null,
            declared_part_count = null,
            start_datetime = null,
            end_datetime = null,
            entry_datetime = null,
            last_updated = null,
            raw_condition_message = "",
            raw_track_line = "A 50/20 51/30",
            start_epoch_ms = null,
            end_epoch_ms = null,
            temporal_state = AviationNatTemporalState.UNKNOWN,
            waypoints = emptyList(),
            drawable_segments = emptyList(),
        )

        assertSame(track.map_text, track.map_text)
        assertEquals("A", track.map_text.primaryText)
        assertEquals(
            AviationMapTextSourceField.NAT_DESIGNATOR.stable_source_field_id,
            track.map_text.primarySourceFieldId,
        )
    }

    @Test
    fun sharedPresentationOwnsTheFutureBilingualAtomicBlock() {
        val sourced = SourcedMapTextPolicy.create(
            primary = "東京",
            primarySourceFieldId = 91uL,
            declaredEnglish = "Tokyo",
            englishSourceFieldId = 92uL,
        )

        val plan = SourcedMapTextPresentation.plan(sourced, 20f)

        assertEquals(20f, plan.primary.textSize)
        assertEquals("東京", plan.primary.text)
        assertEquals("Tokyo", plan.english?.text)
        assertTrue(plan.english!!.textSize < plan.primary.textSize)
        assertTrue(plan.english.forceItalic)
        assertTrue(plan.collisionHeightEm > 1.45f)
        assertTrue(plan.collisionCenterOffset > plan.primary.baselineOffset)
        assertTrue(plan.collisionCenterOffset < plan.english.baselineOffset)
    }

    @Test
    fun aviationDrawPathsUseCachedTextAndTheSharedAtomicPresentationOnly() {
        val source = File(
            "app/src/main/java/com/flightalert/map/AviationLayers.kt",
        ).readText()
        val renderer = source.substringAfter("internal class AviationLayerRenderer")

        assertFalse(renderer.contains("AviationMapTextAdapter."))
        assertFalse(renderer.contains("SourcedMapTextPolicy.create"))
        assertFalse(renderer.contains("SOURCED_ENGLISH_TEXT_SKEW"))
        assertTrue(renderer.contains("SourcedMapTextPresentation.plan"))
        assertTrue(renderer.contains("SourcedMapTextPresentation.forcedItalicSkewX"))
        assertTrue(renderer.contains("collisionHeightEm"))
    }

    @Test
    fun airportCollisionRectUsesTheSharedAtomicCenterOffset() {
        val source = File(
            "app/src/main/java/com/flightalert/map/AviationLayers.kt",
        ).readText()
        val airport_draw = source
            .substringAfter("private fun draw_airport_labels")
            .substringBefore("private fun prepare_sourced_map_label")

        assertTrue(airport_draw.contains("label.collision_center_offset"))
    }

    private fun airspace(name: String, type: String): AviationAirspaceFeature {
        val shell = listOf(
            AviationLayerPoint(0.0, 0.0),
            AviationLayerPoint(0.0, 1.0),
            AviationLayerPoint(1.0, 1.0),
            AviationLayerPoint(0.0, 0.0),
        )
        return AviationAirspaceFeature(
            object_id = 1L,
            name = name,
            type = type,
            lower_limit = null,
            upper_limit = null,
            schedule = null,
            city = null,
            state = null,
            geometry = AviationMultiPolygon(listOf(AviationPolygon(shell, emptyList()))),
            bounds = AviationGeoBounds(0.0, 0.0, 1.0, 1.0),
        )
    }
}
