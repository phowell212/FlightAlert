package com.flightalert.map

internal object ReferencePolicyCanonicalJson {
    fun bytes(): ByteArray {
        val subtype_records = SemanticSubtype.entries.map { subtype ->
            val owner = ReferencePresentationTables.all_filter_specs().single {
                subtype in it.subtypes
            }
            val style = ReferencePresentationTables.style_spec(subtype)
            val resolved = ReferencePresentationTables.resolved_style(subtype)
            obj(
                "filterId" to owner.filter_id.stable_id,
                "id" to subtype.stable_id,
                "name" to subtype.name,
                "semanticPriorityByTier" to if (subtype.is_label) {
                    ProminenceTier.entries.associate { tier ->
                        tier.stable_id to ReferenceProminencePolicy.semantic_priority(subtype, tier)
                    }
                } else null,
                "semanticPriorityWithinTier" to ReferenceProminencePolicy.within_tier_priority(subtype),
                "style" to obj(
                    "colorToken" to style.color_token,
                    "family" to style.family.stable_id,
                    "fontSlant" to style.font_slant.stable_id,
                    "fontWeight" to style.font_weight,
                    "haloToken" to style.halo_token,
                    "letterSpacingMilliEm" to style.letter_spacing_milli_em,
                    "linePattern" to style.line_pattern.stable_id,
                    "lineWidthMilliDp" to style.line_width_milli_dp,
                    "resolved" to obj(
                        "alphaMilli" to resolved.alpha_milli,
                        "colorArgb" to resolved.color_argb,
                        "dashMilliDp" to resolved.dash_milli_dp,
                        "dashPhaseMilliDp" to resolved.dash_phase_milli_dp,
                        "haloAlphaMilli" to resolved.halo_alpha_milli,
                        "haloArgb" to resolved.halo_argb,
                        "haloWidthMilliEm" to resolved.halo_width_milli_em,
                        "lineCap" to resolved.line_cap,
                        "lineHaloWidthMilliDp" to resolved.line_halo_width_milli_dp,
                        "lineJoin" to resolved.line_join,
                    ),
                ),
            )
        }
        val filter_records = ReferencePresentationTables.all_filter_specs().map { spec ->
            obj(
                "defaultEnabled" to spec.default_enabled,
                "id" to spec.filter_id.stable_id,
                "kind" to spec.kind.stable_id,
                "subtypeIds" to spec.subtypes.map { it.stable_id },
                "title" to spec.title,
            )
        }
        val visibility_records = buildList {
            SemanticSubtype.entries.filter { it.is_label }.forEach { subtype ->
                ProminenceTier.entries.forEach { tier ->
                    val rule = ReferencePresentationTables.visibility_rule(subtype, tier)
                    add(
                        obj(
                            "fullAlphaZoomCenti" to rule.full_alpha_zoom_centi,
                            "letterSpacingMilliEm" to rule.letter_spacing_milli_em,
                            "maxBendCentiDegrees" to rule.max_bend_centi_degrees,
                            "maxBendDegreesStored" to rule.max_bend_centi_degrees / 100,
                            "minZoomCenti" to rule.min_zoom_centi,
                            "prominenceTier" to tier.stable_id,
                            "prominenceTierCode" to tier.stable_code,
                            "ruleId" to rule.rule_id,
                            "semanticSubtypeId" to subtype.stable_id,
                            "textSizeMilliSp" to rule.text_size_milli_sp,
                            "displayMaxZoomCenti" to ReferencePresentationPolicy.label_display_max_zoom_centi,
                            "fadeOutZoomCenti" to ReferencePresentationPolicy.label_fade_out_zoom_centi,
                        ),
                    )
                }
            }
        }
        val outline_records = SemanticSubtype.entries.filterNot { it.is_label }.map { subtype ->
            val rule = ReferencePresentationTables.outline_rule(subtype)
            obj(
                "drawOrder" to rule.draw_order,
                "fadeOutZoomCenti" to rule.fade_out_zoom_centi,
                "fullAlphaZoomCenti" to rule.full_alpha_zoom_centi,
                "maxZoomCenti" to rule.max_zoom_centi,
                "minZoomCenti" to rule.min_zoom_centi,
                "priority" to rule.priority,
                "ruleId" to rule.rule_id,
                "semanticSubtypeId" to subtype.stable_id,
            )
        }
        val document = obj(
            "catalog" to obj(
                "countDefinition" to obj(
                    "canonicalVariantCount" to "distinct_canonical_variant_ids",
                    "distinctFeatureCount" to "distinct_admitted_feature_ids",
                    "postingCount" to "canonical_tile_posting_records",
                ),
                "digestDomain" to "FAE8CAT1\\0",
                "exposeOnlyVerifiedNonzeroClasses" to true,
                "missingOrCorruptBehavior" to "unavailable_no_toggles",
                "packageBinding" to "manifest_binds_catalog_digest_and_renderer_semantic_stream_sha256",
                "subtypeCount" to SemanticSubtype.entries.size,
                "uiAvailabilityCount" to "distinctFeatureCount",
                "version" to 1,
            ),
            "filters" to filter_records,
            "masterGatesPreserveSubtypeChoices" to true,
            "outlineRules" to outline_records,
            "placement" to placement_document(),
            "prominenceDecision" to prominence_decision_document(),
            "schema" to "flight-alert-exp8-reference-presentation-policy-v4",
            "sourceClassifier" to obj(
                "nameOrSuffixClassificationForbidden" to true,
                "providerEvidenceRequiredFields" to listOf(
                    "source_generation_sha256",
                    "classifier_sha256",
                    "source_field_id",
                    "raw_provider_rank_i32",
                    "selected_tier",
                ),
                "typedClassifierManifestRequired" to true,
            ),
            "subtypes" to subtype_records,
            "visibility" to visibility_document(visibility_records),
        )
        return (encode(document) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun placement_document(): Map<String, Any?> = obj(
        "activeBandLimit" to ReferencePresentationPolicy.label_active_band_limit,
        "alpha" to obj(
            "fadeInterpolation" to "nearest_integer_ties_away_from_zero",
            "fullAlphaMilli" to ReferencePresentationPolicy.full_alpha_milli,
            "labelEndpoints" to "zero_at_or_below_min_full_at_or_above_full",
            "outlineEndpoints" to
                "maximum_zero_precedes_fade_out_full_when_equal_otherwise_zero_at_or_below_min_or_at_or_above_max_and_full_from_full_through_fade_out",
        ),
        "avoidEdges" to true,
        "candidateOrder" to listOf(
            "semantic_priority",
            "prominence_tier",
            "provider_rank_present_first",
            "provider_rank_i32_smaller_first",
            "u16_max_minus_complete_geometry_measure_bucket",
            "candidate_id",
        ),
        "collisionCapsule" to obj(
            "paddingMilliEm" to ReferencePresentationPolicy.label_collision_padding_milli_em,
            "usesShapedAscentDescentHalo" to true,
        ),
        "collisionGroup" to ReferencePresentationPolicy.reference_label_collision_group,
        "currentFractionalViewportRequired" to true,
        "centizoomQuantization" to obj(
            "acceptedZoomRange" to listOf(0, 100),
            "formulaForNonnegativeZoom" to "floor(zoom_times_100_plus_0.5)",
            "nonfiniteRejected" to true,
        ),
        "displayMaxZoomCenti" to ReferencePresentationPolicy.label_display_max_zoom_centi,
        "endClearanceConversion" to "ceil(text_em_milli_px*value/1000)",
        "endClearanceMilliEm" to ReferencePresentationPolicy.label_end_clearance_milli_em,
        "edgeClearanceMilliEm" to ReferencePresentationPolicy.label_edge_clearance_milli_em,
        "fadeOutZoomCenti" to ReferencePresentationPolicy.label_fade_out_zoom_centi,
        "handoff" to "two_complete_runs_complementary_alpha",
        "handoffMaxMs" to ReferencePresentationPolicy.label_handoff_max_ms,
        "keepUpright" to true,
        "maxPresentationsPerCandidateWrap" to
            ReferencePresentationPolicy.label_max_presentations_per_candidate_wrap,
        "maximumBendCentiDegrees" to ReferencePresentationPolicy.max_line_label_bend_centi_degrees,
        "maximumBendDegreesStored" to
            ReferencePresentationPolicy.max_line_label_bend_centi_degrees / 100,
        "bendMeasurement" to "ceil_shortest_angle_unwrapped_tangent_span_centi_degrees",
        "minimumSpanFormula" to "shaped_advance+2*end_clearance",
        "partialTextForbidden" to true,
        "placementSourceKindCodes" to PlacementSourceKind.entries.associate {
            it.name to it.stable_id
        },
        "pointLabel" to obj(
            "allowedPlacementSourceKinds" to listOf(
                PlacementSourceKind.DIRECT_SOURCE_POINT.stable_id,
                PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT.stable_id,
            ),
            "collision" to "whole_shaped_bbox_plus_halo_padding_and_static_chrome",
            "edgePolicy" to "whole_bbox_inside_edge_clearance_or_absent",
            "exactSourcePointRequired" to true,
            "inferredCentroidForbidden" to true,
            "verifiedSourceTextEvidenceRequired" to true,
        ),
        "prominenceRuleIdDomain" to "FAE8RULE1\\0",
        "prominenceTierCodes" to ProminenceTier.entries.associate {
            it.stable_id to it.stable_code
        },
        "providerRank" to obj(
            "missingSortsAfterPresent" to true,
            "smallerSignedI32IsStronger" to true,
        ),
        "retainedScaledLabelBitmapForbidden" to true,
        "repeatPhase" to "label_candidate_id_mod_repeat_spacing_px",
        "repeatSpacingPx" to ReferencePresentationPolicy.line_label_repeat_spacing_px,
        "sourceFraction" to "exact_nonnegative_rational",
        "staticChromeCollides" to true,
        "textScaleXMilli" to ReferencePresentationPolicy.uncondensed_text_scale_x_milli,
        "withinCandidateOrder" to listOf(
            "prior_still_valid_false_first",
            "negative_minimum_clearance_q8_px",
            "bend_centi_degrees",
            "center_distance_q8_px",
            "canonical_part_index",
            "canonical_segment_index",
            "exact_source_fraction",
            "repeat_ordinal",
            "candidate_id",
        ),
    )

    private fun prominence_decision_document(): Map<String, Any?> = obj(
        "canonicalDomain" to "FAE8PDEC1\\0",
        "candidateBinding" to "prominence_decision_sha256_equals_sha256_canonical_bytes",
        "canonicalFieldOrder" to listOf(
            "policy_sha256",
            "semantic_subtype_u32le",
            "semantic_priority_i32le",
            "prominence_tier_u8",
            "provider_rank_presence_bool",
            "optional_provider_rank_i32le",
            "complete_geometry_measure_bucket_u16le",
            "prominence_rule_id_u64le",
            "evidence_kind_u8",
            "evidence_value_i64le",
            "source_generation_sha256",
            "classifier_sha256",
            "source_field_id_u64le",
        ),
        "completeGeometryMeasureBucket" to
            "zero_if_unverified_or_zero_else_1_plus_floor_log2_times_1024_plus_floor_fractional_mantissa_times_1024_saturating_u16",
        "evidenceSemantics" to obj(
            "capitalLevel" to obj(
                "allowedSubtypeIds" to listOf(200, 210, 220),
                "bucket" to 0,
                "evidenceValueToTier" to obj(
                    "1" to ProminenceTier.REGIONAL_MAJOR.stable_id,
                    "2" to ProminenceTier.GLOBAL_MAJOR.stable_id,
                ),
            ),
            "completeAreaM2" to obj(
                "bucketFromEvidenceValue" to true,
                "nonnegativeI64" to true,
                "tierUsesSubtypeAreaThresholds" to true,
            ),
            "completeRelationLengthM" to obj(
                "bucketFromEvidenceValue" to true,
                "nonnegativeI64" to true,
                "tierUsesWatercourseSubtypeThresholds" to true,
            ),
            "population" to obj(
                "allowedSubtypeIds" to listOf(200, 210, 220),
                "bucket" to 0,
                "nonnegativeI64" to true,
                "tierUsesPopulationThresholds" to true,
            ),
            "providerRank" to obj(
                "bucket" to 0,
                "cannotMixVerifiedFallbackEvidence" to listOf(
                    "population",
                    "capital_level",
                    "complete_area_m2",
                    "complete_relation_length_m",
                ),
                "evidenceValueEqualsProviderRank" to true,
                "tierComesFromBoundClassifier" to true,
            ),
            "typedSubtypeDefault" to obj(
                "bucket" to 0,
                "evidenceValueEqualsSubtypeId" to true,
                "tierUsesDefaultProminenceTierBySubtype" to true,
            ),
        ),
        "evidenceKindCodes" to ProminenceEvidenceKind.entries.associate {
            it.name to it.stable_id
        },
        "semanticPriority" to obj(
            "formula" to "tier_code_times_stride_plus_within_tier_priority",
            "tierDominatesAllSubtypeClasses" to true,
            "tierStride" to ReferenceProminencePolicy.tierStride,
        ),
        "sourceContextRequiredForAuthoritativeDecision" to true,
    )

    private fun visibility_document(rules: List<Map<String, Any?>>): Map<String, Any?> = obj(
        "defaultProminenceTierBySubtype" to SemanticSubtype.entries
            .filter { it.is_label }
            .associate { it.stable_id.toString() to ReferenceProminencePolicy.default_tier(it).stable_id },
        "evidencePrecedence" to listOf(
            "verified_provider_rank_or_scale",
            "verified_explicit_capital_population_or_complete_geometry_measure",
            "conservative_typed_subtype_default",
        ),
        "globalCityPopulationMin" to ReferenceProminencePolicy.globalPopulation,
        "globalIslandAreaM2Min" to ReferenceProminencePolicy.globalIslandArea,
        "globalRiverCompleteRelationMinLengthM" to ReferenceProminencePolicy.globalRiverLength,
        "globalWaterAreaM2Min" to ReferenceProminencePolicy.globalWaterArea,
        "localCityPopulationMin" to ReferenceProminencePolicy.localPopulation,
        "localIslandAreaM2Min" to ReferenceProminencePolicy.localIslandArea,
        "localProtectedAreaM2Min" to ReferenceProminencePolicy.localProtectedArea,
        "localRiverCompleteRelationMinLengthM" to ReferenceProminencePolicy.localRiverLength,
        "localWaterAreaM2Min" to ReferenceProminencePolicy.localWaterArea,
        "majorRiverCompleteRelationMinLengthM" to ReferenceProminencePolicy.regionalRiverLength,
        "namesAndSuffixesCannotPromote" to true,
        "numericDomain" to "nonnegative_signed_i64",
        "regionalCityPopulationMin" to ReferenceProminencePolicy.regionalPopulation,
        "regionalIslandAreaM2Min" to ReferenceProminencePolicy.regionalIslandArea,
        "regionalProtectedAreaM2Min" to ReferenceProminencePolicy.regionalProtectedArea,
        "regionalWaterAreaM2Min" to ReferenceProminencePolicy.regionalWaterArea,
        "rules" to rules,
        "screenshotBoundaryCenti" to 628,
        "strongestSameLevelEvidenceWins" to true,
    )

    private fun obj(vararg entries: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*entries)

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is String -> encode_string(value)
        is Byte, is Short, is Int, is Long -> value.toString()
        is UByte, is UShort, is UInt, is ULong -> value.toString()
        is Map<*, *> -> value.entries
            .map { entry ->
                val key = entry.key as? String
                    ?: throw ReferencePolicyException("canonical JSON object key must be a string")
                key to entry.value
            }
            .sortedBy { it.first }
            .joinToString(prefix = "{", postfix = "}", separator = ",") {
                encode_string(it.first) + ":" + encode(it.second)
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
            encode(it)
        }
        else -> throw ReferencePolicyException(
            "canonical JSON value has unsupported type ${value::class.java.name}",
        )
    }

    private fun encode_string(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
