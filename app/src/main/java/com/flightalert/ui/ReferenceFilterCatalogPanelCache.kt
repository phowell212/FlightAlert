@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import com.flightalert.map.reference.dictionary.ReferenceClassCatalog


internal class ReferenceFilterCatalogPanelCache {
    private var catalog: ReferenceClassCatalog? = null

    fun update(
        next: ReferenceClassCatalog?,
        on_identity_changed: () -> Unit,
    ): ReferenceClassCatalog? {
        if (next !== catalog) {
            catalog = next
            on_identity_changed()
        }
        return catalog
    }
}