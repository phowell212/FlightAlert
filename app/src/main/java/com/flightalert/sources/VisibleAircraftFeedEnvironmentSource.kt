@file:Suppress(
    "FunctionName",
)

package com.flightalert.sources


interface VisibleAircraftFeedEnvironmentSource {
    // Capture location and viewport before recording poll time.
    fun snapshot(): VisibleAircraftFeedEnvironment
    fun feed_ready(): Boolean
    fun build_request(): VisibleAircraftRequest
}
