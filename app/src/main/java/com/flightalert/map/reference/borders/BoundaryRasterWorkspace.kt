@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.borders

import android.graphics.Paint
import android.graphics.Path
import com.flightalert.map.reference.dictionary.DictionaryLineRecord


internal class BoundaryRasterWorkspace {
    val occurrence_ids = ReferenceBoundaryOccurrenceSet()
    val batches = ArrayList<BoundaryPathBatch>(8)
    val clipped_segment = FloatArray(4)
    val paint = Paint()
    private val batch_pool = ArrayList<BoundaryPathBatch>(8)

    fun reset() {
        occurrence_ids.clear()
        batches.clear()
    }

    fun batch_for(record: DictionaryLineRecord, source_order: Int): BoundaryPathBatch {
        for (batch in batches) {
            if (batch.draw_order == record.draw_order &&
                batch.priority == record.priority &&
                batch.style == record.style &&
                batch.visibility_rule == record.visibility_rule
            ) {
                if (record.source_feature_id < batch.first_source_feature_id) {
                    batch.first_source_feature_id = record.source_feature_id
                    batch.first_source_order = source_order
                }
                return batch
            }
        }

        val batch_index = batches.size
        val batch = if (batch_index < batch_pool.size) {
            batch_pool[batch_index]
        } else {
            BoundaryPathBatch(Path(), record.style, record.visibility_rule).also {
                batch_pool += it
            }
        }
        batch.path.rewind()
        batch.style = record.style
        batch.visibility_rule = record.visibility_rule
        batch.draw_order = record.draw_order
        batch.priority = record.priority
        batch.first_source_feature_id = record.source_feature_id
        batch.first_source_order = source_order
        batch.boundaries = 0
        batches += batch
        return batch
    }
}
