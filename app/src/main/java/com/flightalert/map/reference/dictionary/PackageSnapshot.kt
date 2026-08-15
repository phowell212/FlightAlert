@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class PackageSnapshot(
    val info: ReferenceDictionaryPackageInfo,
    val catalog: ReferenceClassCatalog,
)