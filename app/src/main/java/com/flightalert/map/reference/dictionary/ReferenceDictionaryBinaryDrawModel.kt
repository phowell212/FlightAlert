package com.flightalert.map.reference.dictionary

import com.flightalert.map.SourcedMapText
import com.flightalert.map.reference.FilterId
import com.flightalert.map.reference.LabelVisibilityRule
import com.flightalert.map.reference.OutlineVisibilityRule
import com.flightalert.map.reference.ProminenceTier
import com.flightalert.map.reference.ResolvedStyleDetails
import com.flightalert.map.reference.SemanticSubtype
import com.flightalert.map.reference.StyleSpec


internal sealed interface ReferenceDictionaryBinaryDrawModel {
    val filterId: FilterId
    val subtype: SemanticSubtype
    val geometry: ReferenceDictionaryBinaryGeometry
    val resolvedStyle: ResolvedStyleDetails

    data class Label(
        override val filterId: FilterId,
        override val subtype: SemanticSubtype,
        override val geometry: ReferenceDictionaryBinaryGeometry,
        override val resolvedStyle: ResolvedStyleDetails,
        val style: StyleSpec,
        val visibility: ReferenceDictionaryBinaryLabelVisibility,
        val text: SourcedMapText,
        val lineLabel: Boolean,
        val featureId: ULong,
        val dedupeId: ULong,
        val labelCandidateId: ULong,
        val priority: Int,
        val prominenceTier: ProminenceTier,
        val completeGeometryMeasureBucket: Int,
        val visibilityRule: LabelVisibilityRule,
        val repeatSpacingPx: Int,
    ) : ReferenceDictionaryBinaryDrawModel

    data class Outline(
        override val filterId: FilterId,
        override val subtype: SemanticSubtype,
        override val geometry: ReferenceDictionaryBinaryGeometry,
        override val resolvedStyle: ResolvedStyleDetails,
        val style: StyleSpec,
        val visibility: OutlineVisibilityRule,
        val featureId: ULong,
        val dedupeId: ULong,
        val drawOrder: Int,
        val priority: Int,
    ) : ReferenceDictionaryBinaryDrawModel
}