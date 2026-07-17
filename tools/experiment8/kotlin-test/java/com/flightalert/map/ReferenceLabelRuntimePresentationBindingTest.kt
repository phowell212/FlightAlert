package com.flightalert.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceLabelRuntimePresentationBindingTest {
    @Test
    fun binaryLabelsCarrySemanticInputsIntoThePhonePresentationPolicy() {
        val source = renderer_source()
        val binary_mapping = source_section(
            source,
            "private fun parse_binary_tile_payload",
            "private fun binary_geometry",
        )

        assertTrue(binary_mapping.contains("ReferenceLabelRuntimePresentationPolicy.presentationSubtype("))
        assertTrue(binary_mapping.contains("presentation_subtype = presentation_subtype"))
        assertTrue(binary_mapping.contains("source_feature_id = model.featureId"))
        assertTrue(binary_mapping.contains("source_kind = binary_source_kind(model.subtype)"))
        assertTrue(binary_mapping.contains("prominence_tier = model.prominenceTier"))
        assertTrue(
            binary_mapping.contains(
                "complete_geometry_measure_bucket = model.completeGeometryMeasureBucket",
            ),
        )
        assertTrue(binary_mapping.contains("ReferenceLabelRuntimePresentationPolicy.typography("))
        assertTrue(binary_mapping.contains("model.completeGeometryMeasureBucket"))
        assertTrue(binary_mapping.contains("runtime_typography = runtime_typography"))
        assertTrue(source.contains("ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli("))
        assertTrue(source.contains("ReferencePresentationPolicy.centizoom(zoom)"))
    }

    @Test
    fun resolvedPhoneTypographyDrivesRealPaintAndHaloInputs() {
        val source = renderer_source()
        val label_style = source_section(
            source,
            "private fun label_style_for",
            "private fun label_style_template_for",
        )

        assertTrue(label_style.contains("runtime_typography?.textSizeMilliSp"))
        assertTrue(label_style.contains("runtime_visibility_alpha_milli"))
        assertTrue(label_style.contains("runtime_typography?.haloWidthMilliEm"))
        assertTrue(label_style.contains("runtime_typography?.fontWeight"))
        assertTrue(label_style.contains("runtime_typography?.italic"))
        assertTrue(label_style.contains("runtime_typography?.letterSpacingMilliEm"))
        assertTrue(label_style.contains("alpha = scaled_alpha(record.alpha)"))
        assertTrue(label_style.contains("halo_alpha = scaled_alpha(record.halo_alpha)"))
        assertTrue(label_style.contains("runtime_typography == null"))
        assertTrue(label_style.contains("record.halo_width_dp"))
        assertTrue(label_style.contains("else {\n                0f"))
        assertTrue(source.contains("Typeface.create(Typeface.DEFAULT, font_weight, italic)"))
        assertTrue(source.contains("text_size * style.halo_width_milli_em / 1_000f"))
        assertFalse(source.contains("text_size * 0.22f"))
        val line_candidates = source_section(
            source,
            "private fun line_label_candidates",
            "private fun point_label_candidate",
        )
        assertTrue(
            line_candidates.contains(
                "label_halo_width_px(fitted_style, fitted_style.text_size)",
            ),
        )
        val point_candidates = source_section(
            source,
            "private fun point_label_candidate",
            "private fun accept_label_candidates",
        )
        assertTrue(point_candidates.contains("label_halo_width_px(style, style.text_size)"))
    }

    @Test
    fun sharedEnglishLineStaysSmallerItalicAndNormalWeight() {
        val source = renderer_source()
        val measurement_paths = source_section(
            source,
            "private fun line_label_candidates",
            "private fun accept_label_candidates",
        )
        val drawing_paths = source_section(
            source,
            "private fun draw_label_candidate",
            "private fun draw_text_on_path",
        )

        val sourced = SourcedMapTextPolicy.create(
            primary = "東京",
            primarySourceFieldId = 1uL,
            declaredEnglish = "Tokyo",
            englishSourceFieldId = 2uL,
        )
        val presentation = SourcedMapTextPresentation.plan(sourced, 20f)

        assertTrue(source.contains("const val ENGLISH_FONT_WEIGHT = 400"))
        assertTrue(source.contains("const val ENGLISH_ITALIC = true"))
        assertTrue(source.contains("english.textSize"))
        assertTrue(measurement_paths.contains("ENGLISH_FONT_WEIGHT"))
        assertTrue(measurement_paths.contains("ENGLISH_ITALIC"))
        assertTrue(drawing_paths.contains("ENGLISH_FONT_WEIGHT"))
        assertTrue(drawing_paths.contains("ENGLISH_ITALIC"))
        assertEquals(15.2f, presentation.english!!.textSize, 0.001f)
        assertTrue(presentation.english.forceItalic)
        assertFalse(source.contains("italic_typeface_style"))
    }

    @Test
    fun waterFeatureRepeatsAreSeparatedForThePhoneViewport() {
        val source = renderer_source()

        assertTrue(source.contains("WATER_LINE_LABEL_REPEAT_DISTANCE_PX = 520f"))
        assertTrue(source.contains("waterRepeatDistancePx = WATER_LINE_LABEL_REPEAT_DISTANCE_PX"))
        assertTrue(source.contains("featureId = record.source_feature_id ?: candidate_id"))
        assertTrue(source.contains("singleWaterLabelPerFeature ="))
        assertTrue(source.contains("viewport.zoom <= WATER_SINGLE_LABEL_MAX_ZOOM"))
    }

    @Test
    fun repeatedBinaryTileMembershipsAreDeduplicatedOnlyAfterPathPlanningSucceeds() {
        val source = renderer_source()
        val drawLabels = source_section(
            source,
            "private fun draw_labels",
            "private fun line_label_candidates",
        )

        assertTrue(source.contains("planned_label_candidate_ids"))
        assertTrue(drawLabels.contains("planned_label_candidate_ids.clear()"))
        assertTrue(drawLabels.contains("record.candidate_id in planned_label_candidate_ids"))
        assertTrue(drawLabels.contains("val planned_candidates = line_label_candidates("))
        assertTrue(drawLabels.contains("if (planned_candidates.isNotEmpty())"))
        assertTrue(drawLabels.contains("record.candidate_id?.let(planned_label_candidate_ids::add)"))
        assertTrue(
            drawLabels.indexOf("val planned_candidates = line_label_candidates(") <
                drawLabels.indexOf("record.candidate_id?.let(planned_label_candidate_ids::add)"),
        )
    }

    @Test
    fun waterPathsRetrySmallerReadableTextButNeverFloatAwayFromTheirSource() {
        val source = renderer_source()
        val line_candidates = source_section(
            source,
            "private fun line_label_candidates",
            "private fun point_label_candidate",
        )

        assertTrue(line_candidates.contains("ReferenceLineLabelFitPolicy.attemptCount(is_water)"))
        assertTrue(line_candidates.contains("ReferenceLineLabelFitPolicy.textSizePx("))
        assertTrue(line_candidates.contains("ReferenceLineLabelFitPolicy.acceptAttempt("))
        assertTrue(line_candidates.contains("tangent_fallback_request"))
        assertTrue(line_candidates.contains("allowTangentFallback = !is_water"))
        assertTrue(line_candidates.contains("allowCurvedPlacement = false"))
        assertTrue(line_candidates.contains("MIN_WATER_LINE_TEXT_SIZE_SP * label_text_scale"))
        assertTrue(line_candidates.contains("maximumTangentOffsetPx = if (is_water)"))
        assertTrue(line_candidates.contains("maximumTangentSourceDistancePx = if (is_water)"))
        assertTrue(line_candidates.contains("maximumCurvedSourceDistancePx = if (is_water)"))
        assertTrue(
            line_candidates.contains(
                "collision_radius * WATER_CURVED_SOURCE_DISTANCE_FRACTION",
            ),
        )
        assertTrue(source.contains("WATER_CURVED_SOURCE_DISTANCE_FRACTION = 1f"))
        assertTrue(line_candidates.contains("collision_radius.toDouble()"))
        assertTrue(line_candidates.contains("viewport_diagonal"))
    }

    private fun renderer_source(): String = File(
        "app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt",
    ).readText()

    private fun source_section(source: String, start: String, end: String): String {
        val start_index = source.indexOf(start)
        val end_index = source.indexOf(end, startIndex = start_index + start.length)
        assertTrue("missing section start: $start", start_index >= 0)
        assertTrue("missing section end: $end", end_index > start_index)
        return source.substring(start_index, end_index)
    }
}
