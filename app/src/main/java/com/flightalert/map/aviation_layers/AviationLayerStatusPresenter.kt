package com.flightalert.map.aviation_layers


internal fun text(state: AviationLayerLoadState): String = when (state) {
        AviationLayerLoadState.OFF -> "Layers off"
        AviationLayerLoadState.WAITING -> "Waiting for aviation layers"
        AviationLayerLoadState.LOADING -> "Loading aviation layers"
        is AviationLayerLoadState.SUMMARY -> {
            val base = when {
                state.waiting -> "Waiting for aviation layers"
                state.retained > 0 -> buildString {
                    append("Latest source incomplete; showing last aviation layers")
                    if (state.unavailable > 0) append("; ${state.unavailable} unavailable")
                }
                state.loaded > 0 || state.partial > 0 || state.unavailable > 0 -> buildList {
                    if (state.loaded > 0) {
                        add("${state.loaded} aviation layer${if (state.loaded == 1) "" else "s"} loaded")
                    }
                    if (state.partial > 0) add("${state.partial} partial")
                    if (state.unavailable > 0) add("${state.unavailable} unavailable")
                }.joinToString("; ")
                else -> "No aviation layer data in view"
            }
            if (!state.airport_labels_deferred) {
                base
            } else {
                val airport_note = "Airport labels appear at zoom 8.4"
                if (state.other_layers_enabled) "$base; $airport_note" else airport_note
            }
        }
}
