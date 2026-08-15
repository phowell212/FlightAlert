@file:Suppress(
    "ArrayInDataClass",
    "ConvertTwoComparisonsToRangeCheck",
)

package com.flightalert.map.reference.dictionary

import com.flightalert.map.SourcedMapText
import com.flightalert.map.reference.SemanticSubtype


internal data class ReferenceDictionaryBinaryRecord(
    val featureId: ULong,
    val canonicalVariantId: ULong,
    val dedupeId: ULong,
    val layerGroup: ReferenceDictionaryBinaryLayerGroup,
    val featureKind: ReferenceDictionaryBinaryFeatureKind,
    val subtype: SemanticSubtype,
    val geometry: ReferenceDictionaryBinaryGeometry,
    val sourcedText: SourcedMapText?,
    val placement: ReferenceDictionaryBinaryPlacement,
    val minimumZoomCenti: Int,
    val fullAlphaZoomCenti: Int,
    val fadeOutZoomCenti: Int,
    val maximumZoomCenti: Int,
    val drawOrder: Int,
    val priority: Int,
    val postingWorldWrap: Int = 0,
)