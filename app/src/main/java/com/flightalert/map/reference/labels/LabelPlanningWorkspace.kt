@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import android.graphics.Paint
import android.graphics.Typeface
import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate
import com.flightalert.map.reference.dictionary.DictionaryLabelRecordRef


internal class LabelPlanningWorkspace(
    val textPaint: Paint,
) {
    val typefaceCache = HashMap<Int, Typeface>(8)
    val recordRefs = ArrayList<DictionaryLabelRecordRef>(512)
    val candidates = ArrayList<DictionaryLabelCandidate>(256)
    val plannedCandidateIds = HashSet<ReferenceLabelCandidateKey>(256)
    val acceptedLabels = ArrayList<DictionaryLabelCandidate>(128)
}