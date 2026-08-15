@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "ConvertTryFinallyToUseCall",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import java.io.File


internal data class ReferenceDictionaryPackageInfo(
    val package_id: String,
    val schema_version: Int,
    val tile_count: Long,
    val zooms: Set<Int>,
    val complete_declared_scope: Boolean,
    val complete_whole_earth_dictionary: Boolean,
    val runtime_schema: ReferenceDictionaryRuntimeSchema,
    val package_dir: File
)