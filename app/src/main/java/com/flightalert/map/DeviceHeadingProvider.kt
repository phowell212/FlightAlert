@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlin.math.abs
import kotlin.math.atan2

class DeviceHeadingProvider(
    context: Context,
    private val on_heading_changed: (Float?) -> Unit
) : SensorEventListener {
    private val sensor_manager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val main_handler = Handler(Looper.getMainLooper())
    private val rotation_matrix = FloatArray(9)
    private val gravity_values = FloatArray(3)
    private val magnetic_values = FloatArray(3)

    @Volatile
    private var active = false

    @Volatile
    private var generation = 0

    @Volatile
    private var location_snapshot: Location? = null

    @Volatile
    private var declination_degrees = 0f

    private var sensor_thread: HandlerThread? = null
    private var sensor_handler: Handler? = null
    private var has_gravity = false
    private var has_magnetic = false
    private var smoothed_heading_degrees: Float? = null
    private var published_heading_degrees: Float? = null
    private var heading_accuracy_reliable = true
    private var forward_axis = ForwardAxis.TOP_EDGE

    fun start() {
        if (active) return
        active = true
        val start_generation = ++generation
        publish_heading(null, start_generation)
        val thread = HandlerThread("FlightAlertHeading")
        sensor_thread = thread
        thread.start()
        val handler = Handler(thread.looper)
        sensor_handler = handler
        handler.post {
            if (!is_current(start_generation)) return@post
            reset_sensor_state()
            update_declination_from_location()
            if (!register_heading_sensors(handler)) {
                publish_heading(null, start_generation)
            }
        }
    }

    fun stop() {
        if (!active && sensor_thread == null) return
        active = false
        val stop_generation = ++generation
        val handler = sensor_handler
        val thread = sensor_thread
        sensor_handler = null
        sensor_thread = null
        sensor_manager.unregisterListener(this)
        handler?.post {
            if (generation == stop_generation && !active) reset_sensor_state()
            thread?.quitSafely()
        }
    }

    fun update_location(location: Location?) {
        location_snapshot = location?.let { Location(it) }
        sensor_handler?.post { update_declination_from_location() }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val current_generation = generation
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> handle_rotation_vector(event, current_generation)

            Sensor.TYPE_GRAVITY -> {
                copy_xyz(event.values, gravity_values)
                has_gravity = true
                handle_gravity_and_magnetic(current_generation)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                smooth_xyz(event.values, gravity_values)
                has_gravity = true
                handle_gravity_and_magnetic(current_generation)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                copy_xyz(event.values, magnetic_values)
                has_magnetic = true
                handle_gravity_and_magnetic(current_generation)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor?.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
            Sensor.TYPE_MAGNETIC_FIELD -> {
                heading_accuracy_reliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
            }
        }
    }

    private fun register_heading_sensors(handler: Handler): Boolean {
        sensor_manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            if (sensor_manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, handler)) {
                return true
            }
        }
        sensor_manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)?.let { sensor ->
            if (sensor_manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, handler)) {
                return true
            }
        }

        val gravity_sensor = sensor_manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensor_manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetic_sensor = sensor_manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (gravity_sensor == null || magnetic_sensor == null) return false

        val gravity_registered =
            sensor_manager.registerListener(this, gravity_sensor, SensorManager.SENSOR_DELAY_UI, handler)
        val magnetic_registered =
            sensor_manager.registerListener(this, magnetic_sensor, SensorManager.SENSOR_DELAY_UI, handler)
        if (!gravity_registered || !magnetic_registered) {
            sensor_manager.unregisterListener(this)
            return false
        }
        return true
    }

    private fun handle_rotation_vector(event: SensorEvent, current_generation: Int) {
        SensorManager.getRotationMatrixFromVector(rotation_matrix, event.values)
        handle_rotation_matrix(current_generation)
    }

    private fun handle_gravity_and_magnetic(current_generation: Int) {
        if (!has_gravity || !has_magnetic) return
        if (!SensorManager.getRotationMatrix(rotation_matrix, null, gravity_values, magnetic_values)) return
        handle_rotation_matrix(current_generation)
    }

    private fun handle_rotation_matrix(current_generation: Int) {
        if (!heading_accuracy_reliable) {
            publish_unavailable_heading(current_generation)
            return
        }
        val magnetic_heading = heading_from_rotation_matrix() ?: return
        publish_smoothed_heading(
            normalize_degrees(magnetic_heading + declination_degrees),
            current_generation
        )
    }

    private fun heading_from_rotation_matrix(): Float? {
        val top_east = rotation_matrix[1]
        val top_north = rotation_matrix[4]
        val top_horizontal = top_east * top_east + top_north * top_north

        val back_east = -rotation_matrix[2]
        val back_north = -rotation_matrix[5]
        val back_horizontal = back_east * back_east + back_north * back_north

        if (top_horizontal < MIN_FORWARD_HORIZONTAL_SQUARED &&
            back_horizontal < MIN_FORWARD_HORIZONTAL_SQUARED
        ) {
            return null
        }
        update_forward_axis(top_horizontal, back_horizontal)
        val (east, north, horizontal) = if (forward_axis == ForwardAxis.BACK_FACE) {
            Triple(back_east, back_north, back_horizontal)
        } else {
            Triple(top_east, top_north, top_horizontal)
        }
        if (horizontal < MIN_FORWARD_HORIZONTAL_SQUARED) return null
        return normalize_degrees(Math.toDegrees(atan2(east, north).toDouble()).toFloat())
    }

    private fun update_forward_axis(top_horizontal: Float, back_horizontal: Float) {
        forward_axis = when (forward_axis) {
            ForwardAxis.TOP_EDGE ->
                if (back_horizontal > top_horizontal + POSTURE_SWITCH_MARGIN_SQUARED) {
                    ForwardAxis.BACK_FACE
                } else {
                    ForwardAxis.TOP_EDGE
                }

            ForwardAxis.BACK_FACE ->
                if (top_horizontal > back_horizontal + POSTURE_SWITCH_MARGIN_SQUARED) {
                    ForwardAxis.TOP_EDGE
                } else {
                    ForwardAxis.BACK_FACE
                }
        }
    }

    private fun publish_smoothed_heading(true_heading_degrees: Float, current_generation: Int) {
        val previous_smoothed = smoothed_heading_degrees
        val next_heading = if (previous_smoothed == null) {
            true_heading_degrees
        } else {
            normalize_degrees(
                previous_smoothed +
                        shortest_heading_delta(previous_smoothed, true_heading_degrees) * HEADING_SMOOTHING
            )
        }
        smoothed_heading_degrees = next_heading

        val previous_published = published_heading_degrees
        if (previous_published == null ||
            abs(shortest_heading_delta(previous_published, next_heading)) >= MIN_PUBLISH_DELTA_DEGREES
        ) {
            published_heading_degrees = next_heading
            publish_heading(next_heading, current_generation)
        }
    }

    private fun publish_heading(heading_degrees: Float?, current_generation: Int) {
        main_handler.post {
            if (is_current(current_generation)) on_heading_changed(heading_degrees)
        }
    }

    private fun publish_unavailable_heading(current_generation: Int) {
        if (published_heading_degrees == null && smoothed_heading_degrees == null) return
        published_heading_degrees = null
        smoothed_heading_degrees = null
        publish_heading(null, current_generation)
    }

    private fun update_declination_from_location() {
        val location = location_snapshot
        if (location == null) {
            declination_degrees = 0f
            return
        }
        val altitude_meters = if (location.hasAltitude()) location.altitude.toFloat() else 0f
        val timestamp_ms = if (location.time > 0L) location.time else System.currentTimeMillis()
        declination_degrees = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            altitude_meters,
            timestamp_ms
        ).declination
    }

    private fun reset_sensor_state() {
        has_gravity = false
        has_magnetic = false
        heading_accuracy_reliable = true
        forward_axis = ForwardAxis.TOP_EDGE
        smoothed_heading_degrees = null
        published_heading_degrees = null
    }

    private fun is_current(current_generation: Int): Boolean =
        active && generation == current_generation

    private fun copy_xyz(source: FloatArray, target: FloatArray) {
        if (source.size < 3) return
        target[0] = source[0]
        target[1] = source[1]
        target[2] = source[2]
    }

    private fun smooth_xyz(source: FloatArray, target: FloatArray) {
        if (source.size < 3) return
        if (!has_gravity) {
            copy_xyz(source, target)
            return
        }
        target[0] = target[0] * ACCELEROMETER_KEEP + source[0] * ACCELEROMETER_NEW
        target[1] = target[1] * ACCELEROMETER_KEEP + source[1] * ACCELEROMETER_NEW
        target[2] = target[2] * ACCELEROMETER_KEEP + source[2] * ACCELEROMETER_NEW
    }

    private companion object {
        const val ACCELEROMETER_KEEP = 0.82f
        const val ACCELEROMETER_NEW = 1f - ACCELEROMETER_KEEP
        const val HEADING_SMOOTHING = 0.34f
        const val MIN_PUBLISH_DELTA_DEGREES = 0.75f
        const val MIN_FORWARD_HORIZONTAL_SQUARED = 0.05f
        const val POSTURE_SWITCH_MARGIN_SQUARED = 0.16f

        fun normalize_degrees(value: Float): Float {
            var normalized = value % 360f
            if (normalized < 0f) normalized += 360f
            return normalized
        }

        fun shortest_heading_delta(from_degrees: Float, to_degrees: Float): Float {
            var delta = (to_degrees - from_degrees + 540f) % 360f - 180f
            if (delta < -180f) delta += 360f
            return delta
        }
    }

    private enum class ForwardAxis {
        TOP_EDGE,
        BACK_FACE
    }
}
