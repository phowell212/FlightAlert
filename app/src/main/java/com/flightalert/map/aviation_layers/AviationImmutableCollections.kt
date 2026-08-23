@file:Suppress(
    "FunctionName",
)

package com.flightalert.map.aviation_layers

import java.util.Collections


internal fun <T> immutable_list(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal fun <T> immutable_set(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

internal fun <T> immutable_nested_list(values: Collection<Collection<T>>): List<List<T>> =
    immutable_list(values.map(::immutable_list))
