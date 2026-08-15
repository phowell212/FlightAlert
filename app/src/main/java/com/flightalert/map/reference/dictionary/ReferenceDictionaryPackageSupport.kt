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


internal fun <T> first_available_reference_package(
    candidates: List<File>,
    opener: (File) -> T?,
): T? {
    candidates.forEach { candidate -> opener(candidate)?.let { return it } }
    return null
}