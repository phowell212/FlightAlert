package com.flightalert.map

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceBoundaryOccurrenceSelectorTest {
    @Test
    fun oneCanonicalOutlineOccurrenceIsAdmittedAcrossRepeatedTilePostings() {
        val seen = HashSet<ReferenceBoundaryOccurrenceKey>()

        assertTrue(ReferenceBoundaryOccurrenceSelector.admit(seen, 17uL, 0L))
        assertFalse(ReferenceBoundaryOccurrenceSelector.admit(seen, 17uL, 0L))
        assertTrue(ReferenceBoundaryOccurrenceSelector.admit(seen, 18uL, 0L))
        assertTrue(
            "the same canonical path remains visible in a second displayed world copy",
            ReferenceBoundaryOccurrenceSelector.admit(seen, 17uL, 1L),
        )
    }

    @Test
    fun legacyOutlinesWithoutCanonicalDedupeIdentityRemainIndependent() {
        val seen = HashSet<ReferenceBoundaryOccurrenceKey>()

        assertTrue(ReferenceBoundaryOccurrenceSelector.admit(seen, null, 0L))
        assertTrue(ReferenceBoundaryOccurrenceSelector.admit(seen, null, 0L))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun requestedTileWrapAndDrawWrapResolveToTheEffectiveDisplayedWorldCopy() {
        assertTrue(
            ReferenceBoundaryOccurrenceSelector.renderedWorldCopy(
                zoom = 4,
                requestedTileX = 15,
                drawTileX = -1,
                postingWorldWrap = -1,
            ) == 0L,
        )
        assertTrue(
            ReferenceBoundaryOccurrenceSelector.renderedWorldCopy(
                zoom = 4,
                requestedTileX = 0,
                drawTileX = 0,
                postingWorldWrap = 0,
            ) == 0L,
        )
        assertTrue(
            ReferenceBoundaryOccurrenceSelector.renderedWorldCopy(
                zoom = 4,
                requestedTileX = 0,
                drawTileX = 16,
                postingWorldWrap = 0,
            ) == 1L,
        )
    }

    @Test
    fun rendererWiresBinaryDedupeBeforeCollectingOutlineDrawReferences() {
        val source = File(
            "app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt",
        ).readText().replace("\r\n", "\n").replace('\r', '\n')
        val draw = source.substring(
            source.indexOf("private fun draw_reference_content"),
            source.indexOf("private fun draw_boundary_records"),
        )
        val clear = draw.indexOf("boundary_occurrence_ids.clear()")
        val tileLoop = draw.indexOf("for (tile in tiles)")
        val admit = draw.indexOf("ReferenceBoundaryOccurrenceSelector.admit(")
        val collect = draw.indexOf("boundary_record_refs += DictionaryLineRecordRef(")

        assertTrue("outline occurrence state must reset per frame", clear >= 0)
        assertTrue("reset must precede tile traversal", tileLoop > clear)
        assertTrue("dedupe must run inside tile traversal", admit > tileLoop)
        assertTrue("dedupe must precede draw-reference collection", collect > admit)
        assertTrue("binary outlines must preserve the canonical dedupe ID", source.contains(
            "dedupe_id = model.dedupeId",
        ))
        assertTrue("binary outlines must preserve their posting wrap", source.contains(
            "posting_world_wrap = record.postingWorldWrap",
        ))
    }
}
