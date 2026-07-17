package com.flightalert.ui

import com.flightalert.map.CatalogControlStatus
import com.flightalert.map.FilterId
import com.flightalert.map.FilterState
import com.flightalert.map.ReferenceClassCatalog
import com.flightalert.map.ReferencePresentationPolicy
import com.flightalert.map.ReferencePolicyException
import com.flightalert.map.SemanticSubtype
import com.flightalert.map.SubtypeCatalogCounts
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceFilterPreferencesTest {
    private fun expected_app_defaults(): FilterState =
        ReferenceFilterPreferences.fromLegacyGroups(
            placesEnabled = FlightAlertSettings.DEFAULT_LAYER_PLACE_LABELS_ENABLED,
            waterEnabled = FlightAlertSettings.DEFAULT_LAYER_WATER_LABELS_ENABLED,
            regionsEnabled = FlightAlertSettings.DEFAULT_LAYER_REGION_LABELS_ENABLED,
            publicLandsEnabled = FlightAlertSettings.DEFAULT_LAYER_PUBLIC_LANDS_ENABLED,
        )

    private fun installed_catalog(
        subtype_counts: Map<SemanticSubtype, ULong>,
    ): ReferenceClassCatalog {
        val complete = SemanticSubtype.entries.associateWith { subtype ->
            val count = subtype_counts[subtype] ?: 0uL
            SubtypeCatalogCounts(count, count, count)
        }
        val semantic = "a".repeat(64)
        val contract = "b".repeat(64)
        val policy = ReferencePresentationPolicy.canonical_policy_sha256
        val bytes = ReferencePresentationPolicy.canonical_class_catalog_bytes(
            semantic,
            contract,
            policy,
            complete,
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        return ReferenceClassCatalog.from_installed_bytes(
            bytes,
            digest,
            semantic,
            contract,
            policy,
        )
    }

    @Test
    fun mapLabelsDefaultToThePhoneSizedBaseline() {
        assertEquals(1f, FlightAlertSettings.DEFAULT_MAP_LABEL_TEXT_SCALE)
    }

    @Test
    fun appDefaultsKeepOnlyCoastlinesAndDefaultProtectedLandRowsOff() {
        val defaults = ReferenceFilterPreferences.app_defaults()

        assertEquals(expected_app_defaults(), defaults)
        assertFalse(defaults.stored_enabled(FilterId.OUTLINES_COASTLINES))
        assertFalse(defaults.stored_enabled(FilterId.LABELS_PROTECTED_LANDS))
        assertFalse(defaults.stored_enabled(FilterId.OUTLINES_PROTECTED_AREAS))
        assertTrue(defaults.stored_enabled(FilterId.OUTLINES_INTERNATIONAL))
        assertTrue(defaults.labels_master_enabled)
        assertTrue(defaults.outlines_master_enabled)
        assertTrue(
            FilterId.entries
                .filterNot {
                    it == FilterId.OUTLINES_COASTLINES ||
                        it == FilterId.LABELS_PROTECTED_LANDS ||
                        it == FilterId.OUTLINES_PROTECTED_AREAS
                }
                .all(defaults::stored_enabled),
        )
    }

    @Test
    fun masterGatesPreserveStoredSubtypeChoices() {
        val initial = FilterState.defaults()
            .with_filter(FilterId.LABELS_STREAMS, false)
            .with_filter(FilterId.OUTLINES_COASTLINES, true)
        val labels_off = initial.with_labels_master(false)
        val labels_back_on = labels_off.with_labels_master(true)

        assertFalse(labels_off.effectively_enabled(FilterId.LABELS_RIVERS))
        assertTrue(labels_off.stored_enabled(FilterId.LABELS_RIVERS))
        assertFalse(labels_back_on.effectively_enabled(FilterId.LABELS_STREAMS))
        assertTrue(labels_back_on.effectively_enabled(FilterId.LABELS_RIVERS))
        assertTrue(labels_off.effectively_enabled(FilterId.OUTLINES_COASTLINES))
    }

    @Test
    fun encodingIsVersionedCanonicalAndRoundTripsEveryKnownChoice() {
        val state = FilterState.defaults()
            .with_labels_master(false)
            .with_filter(FilterId.LABELS_STREAMS, false)
            .with_filter(FilterId.OUTLINES_COASTLINES, false)
        val encoded = ReferenceFilterPreferences.encode(state)

        assertEquals(
            """
            flight-alert-reference-filters-v1
            labels.master=0
            outlines.master=1
            labels.regions=1
            labels.places=1
            labels.islands=1
            labels.major_water=1
            labels.rivers=1
            labels.streams=0
            labels.canals=1
            labels.protected_lands=1
            outlines.coastlines=0
            outlines.international=1
            outlines.state_province=1
            outlines.county_local=1
            outlines.protected_areas=1
            outlines.water_boundaries=1
            outlines.other=1
            """.trimIndent() + "\n",
            encoded,
        )
        assertEquals(state, ReferenceFilterPreferences.decode(encoded))
    }

    @Test
    fun unknownIdsAreIgnoredWhileKnownTemporarilyHiddenChoicesRemainStored() {
        val stored = FilterState.defaults().with_filter(FilterId.LABELS_STREAMS, false)
        val encoded_with_future_id = ReferenceFilterPreferences.encode(stored).replace(
            "labels.streams=0\n",
            "labels.future_class=future-state\nlabels.streams=0\n",
        )
        val decoded = ReferenceFilterPreferences.decode(encoded_with_future_id)

        assertFalse(decoded.stored_enabled(FilterId.LABELS_STREAMS))
        val catalog_without_streams = installed_catalog(
            subtype_counts = mapOf(SemanticSubtype.RIVER to 1uL),
        )
        val panel = ReferencePresentationPolicy.available_filter_catalog(catalog_without_streams)
        assertEquals(CatalogControlStatus.AVAILABLE, panel.status)
        assertFalse(panel.filter_ids.contains(FilterId.LABELS_STREAMS))
        assertFalse(
            ReferenceFilterPreferences.decode(
                ReferenceFilterPreferences.encode(decoded),
            ).stored_enabled(FilterId.LABELS_STREAMS),
        )
        assertFalse(ReferenceFilterPreferences.encode(decoded).contains("future_class"))
    }

    @Test
    fun missingKnownRowsUsePolicyDefaultsButAmbiguousKnownRowsFailClosed() {
        val partial = """
            flight-alert-reference-filters-v1
            labels.master=1
            outlines.master=1
            labels.streams=0
            labels.future_class=1
        """.trimIndent() + "\n"
        val decoded = ReferenceFilterPreferences.decode(partial)
        assertFalse(decoded.stored_enabled(FilterId.LABELS_STREAMS))
        assertTrue(decoded.stored_enabled(FilterId.LABELS_RIVERS))
        assertEquals(FilterState.defaults(), ReferenceFilterPreferences.decode(null))

        assertThrows(ReferencePolicyException::class.java) {
            ReferenceFilterPreferences.decode(partial + "labels.streams=1\n")
        }
        assertThrows(ReferencePolicyException::class.java) {
            ReferenceFilterPreferences.decode(
                partial.replace("flight-alert-reference-filters-v1", "unknown-version"),
            )
        }
        assertThrows(ReferencePolicyException::class.java) {
            ReferenceFilterPreferences.decode(partial.replace("labels.streams=0", "labels.streams=yes"))
        }
    }

    @Test
    fun changingThenResettingReturnsTheSingleAppDefaultState() {
        val app_defaults = ReferenceFilterPreferences.app_defaults()
        val changed = app_defaults
            .with_labels_master(false)
            .with_outlines_master(false)
            .with_filter(FilterId.LABELS_STREAMS, false)
            .with_filter(FilterId.OUTLINES_OTHER, false)
            .with_filter(FilterId.OUTLINES_COASTLINES, true)
            .with_filter(FilterId.LABELS_PROTECTED_LANDS, true)
            .with_filter(FilterId.OUTLINES_PROTECTED_AREAS, true)

        assertEquals(app_defaults, ReferenceFilterPreferences.reset(changed))
    }

    @Test
    fun legacyGroupMigrationMapsOnlyTheClassesTheOldControlsOwned() {
        val migrated = ReferenceFilterPreferences.fromLegacyGroups(
            placesEnabled = false,
            waterEnabled = false,
            regionsEnabled = false,
            publicLandsEnabled = false,
        )

        assertFalse(migrated.stored_enabled(FilterId.LABELS_PLACES))
        assertFalse(migrated.stored_enabled(FilterId.LABELS_ISLANDS))
        assertFalse(migrated.stored_enabled(FilterId.LABELS_MAJOR_WATER))
        assertFalse(migrated.stored_enabled(FilterId.LABELS_RIVERS))
        assertFalse(migrated.stored_enabled(FilterId.LABELS_STREAMS))
        assertFalse(migrated.stored_enabled(FilterId.LABELS_CANALS))
        assertFalse(migrated.stored_enabled(FilterId.LABELS_REGIONS))
        assertFalse(migrated.stored_enabled(FilterId.LABELS_PROTECTED_LANDS))
        assertFalse(migrated.stored_enabled(FilterId.OUTLINES_PROTECTED_AREAS))
        assertFalse(migrated.stored_enabled(FilterId.OUTLINES_COASTLINES))
        assertTrue(migrated.stored_enabled(FilterId.OUTLINES_INTERNATIONAL))
    }
}
