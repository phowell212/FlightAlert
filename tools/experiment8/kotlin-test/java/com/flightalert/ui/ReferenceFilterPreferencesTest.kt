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
    fun masterGatesPreserveStoredSubtypeChoices() {
        val initial = FilterState.defaults().with_filter(FilterId.LABELS_STREAMS, false)
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
    fun resetUsesAllPolicyDefaultsRatherThanOnlyVisibleControls() {
        val changed = FilterState.defaults()
            .with_labels_master(false)
            .with_outlines_master(false)
            .with_filter(FilterId.LABELS_STREAMS, false)
            .with_filter(FilterId.OUTLINES_OTHER, false)

        assertEquals(FilterState.defaults(), ReferenceFilterPreferences.reset(changed))
        assertTrue(FilterId.entries.all { FilterState.defaults().stored_enabled(it) })
    }
}
