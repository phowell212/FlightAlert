package com.flightalert.ui

import com.flightalert.map.FilterId
import com.flightalert.map.FilterState
import com.flightalert.map.ReferenceClassCatalog
import com.flightalert.map.ReferencePresentationPolicy
import com.flightalert.map.SemanticSubtype
import com.flightalert.map.SubtypeCatalogCounts
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceFilterCatalogUiMapperTest {
    @Test
    fun missingAndUnverifiedCatalogsAreExplicitlyUnavailable() {
        val missing = ReferenceFilterCatalogUiMapper.map(
            catalog = null,
            filterState = FilterState.defaults(),
        )
        val unverified = ReferenceFilterCatalogUiMapper.map(
            catalog = ReferenceClassCatalog.unavailable("catalog_digest_mismatch"),
            filterState = FilterState.defaults(),
        )

        assertEquals(
            ReferenceMapFilterCatalogUi.Unavailable(
                "Map reference filters are unavailable because the installed reference catalog is missing.",
            ),
            missing,
        )
        assertEquals(
            ReferenceMapFilterCatalogUi.Unavailable(
                "Map reference filters are unavailable because the installed reference catalog is not verified: " +
                    "catalog_digest_mismatch",
            ),
            unverified,
        )
    }

    @Test
    fun verifiedRowsUseStablePolicyOrderTitlesAndStoredChoices() {
        val catalog = installedCatalog(
            mapOf(
                SemanticSubtype.SECOND_LOCAL_REGION to count(2uL),
                SemanticSubtype.LOCAL_PLACE to count(3uL),
                SemanticSubtype.UNSPECIFIED_WATERCOURSE to count(4uL),
                SemanticSubtype.INTERNATIONAL_BOUNDARY to count(5uL),
                SemanticSubtype.OTHER_SOURCED_OUTLINE to count(6uL),
            ),
        )
        val state = FilterState.defaults()
            .with_labels_master(false)
            .with_filter(FilterId.LABELS_PLACES, false)
            .with_filter(FilterId.OUTLINES_OTHER, false)

        val mapped = ReferenceFilterCatalogUiMapper.map(catalog, state)
        assertTrue(mapped is ReferenceMapFilterCatalogUi.Verified)
        val rows = (mapped as ReferenceMapFilterCatalogUi.Verified).rows

        assertEquals(
            listOf(
                "labels.regions" to "Regions",
                "labels.places" to "Places",
                "labels.streams" to "Streams & creeks",
                "outlines.international" to "International borders",
                "outlines.other" to "Other sourced outlines",
            ),
            rows.map { it.stableId to it.title },
        )
        assertEquals(listOf(0, 1, 5, 9, 14), rows.map(ReferenceFilterRowUi::sortOrder))
        assertEquals(listOf(true, false, true, true, false), rows.map(ReferenceFilterRowUi::storedEnabled))
        assertEquals(
            listOf(
                ReferenceFilterSection.LABELS,
                ReferenceFilterSection.LABELS,
                ReferenceFilterSection.LABELS,
                ReferenceFilterSection.OUTLINES,
                ReferenceFilterSection.OUTLINES,
            ),
            rows.map(ReferenceFilterRowUi::section),
        )
    }

    @Test
    fun swatchesUseTheFirstActuallyPresentOwnedSubtypeAndExactPolicyStyle() {
        val catalog = installedCatalog(
            mapOf(
                SemanticSubtype.SECOND_LOCAL_REGION to count(1uL),
                SemanticSubtype.UNSPECIFIED_WATERCOURSE to count(1uL),
                SemanticSubtype.INTERNATIONAL_BOUNDARY to count(1uL),
            ),
        )
        val rows = (ReferenceFilterCatalogUiMapper.map(
            catalog,
            FilterState.defaults(),
        ) as ReferenceMapFilterCatalogUi.Verified).rows.associateBy(ReferenceFilterRowUi::stableId)

        assertEquals(
            ReferenceLabelStyleSwatch(
                colorArgb = 0xFFD2DEE8u.toInt(),
                haloArgb = 0xFF071419u.toInt(),
                fontWeight = 500,
                italic = false,
                letterSpacingEm = 0.025f,
            ),
            rows.getValue("labels.regions").swatch,
        )
        assertEquals(
            ReferenceLabelStyleSwatch(
                colorArgb = 0xFF78AFC7u.toInt(),
                haloArgb = 0xFF071419u.toInt(),
                fontWeight = 400,
                italic = true,
                letterSpacingEm = 0.04f,
            ),
            rows.getValue("labels.streams").swatch,
        )
        assertEquals(
            ReferenceOutlineStyleSwatch(
                colorArgb = 0xFFDDE7EDu.toInt(),
                haloArgb = 0xFF061013u.toInt(),
                lineWidthDp = 1.1f,
                pattern = ReferenceOutlinePattern.LONG_DASH,
            ),
            rows.getValue("outlines.international").swatch,
        )
    }

    @Test
    fun canonicalVariantsAndPostingsCannotMakeAZeroDistinctFeatureFilterAvailable() {
        val catalog = installedCatalog(
            mapOf(
                SemanticSubtype.RIVER to count(1uL),
                SemanticSubtype.CANAL_CHANNEL to SubtypeCatalogCounts(
                    distinct_feature_count = 0uL,
                    canonical_variant_count = 7uL,
                    posting_count = 19uL,
                ),
            ),
        )

        val rows = (ReferenceFilterCatalogUiMapper.map(
            catalog,
            FilterState.defaults(),
        ) as ReferenceMapFilterCatalogUi.Verified).rows

        assertEquals(listOf("labels.rivers"), rows.map(ReferenceFilterRowUi::stableId))
        assertFalse(rows.any { it.stableId == "labels.canals" })
    }

    @Test
    fun verifiedCatalogWithNoDistinctFeaturesRemainsVerifiedAndEmpty() {
        val mapped = ReferenceFilterCatalogUiMapper.map(
            installedCatalog(emptyMap()),
            FilterState.defaults(),
        )

        assertEquals(ReferenceMapFilterCatalogUi.Verified(emptyList()), mapped)
    }

    private fun installedCatalog(
        suppliedCounts: Map<SemanticSubtype, SubtypeCatalogCounts>,
    ): ReferenceClassCatalog {
        val completeCounts = SemanticSubtype.entries.associateWith { subtype ->
            suppliedCounts[subtype] ?: count(0uL)
        }
        val semanticSha = "a".repeat(64)
        val contractSha = "b".repeat(64)
        val policySha = ReferencePresentationPolicy.canonical_policy_sha256
        val bytes = ReferencePresentationPolicy.canonical_class_catalog_bytes(
            semanticSha,
            contractSha,
            policySha,
            completeCounts,
        )
        val catalogSha = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return ReferenceClassCatalog.from_installed_bytes(
            catalog_bytes = bytes,
            expected_catalog_sha256 = catalogSha,
            expected_renderer_semantic_stream_sha256 = semanticSha,
            expected_renderer_contract_sha256 = contractSha,
            expected_presentation_policy_sha256 = policySha,
        )
    }

    private fun count(distinctFeatures: ULong): SubtypeCatalogCounts = SubtypeCatalogCounts(
        distinct_feature_count = distinctFeatures,
        canonical_variant_count = distinctFeatures,
        posting_count = distinctFeatures,
    )
}
