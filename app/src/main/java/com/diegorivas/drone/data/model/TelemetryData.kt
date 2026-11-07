package com.diegorivas.drone.data.model

import kotlin.math.sqrt

/**
 * Foto instantánea de la telemetría del DRON (FlightController del Mavic Air),
 * no del GPS del teléfono.
 *
 * velocityX = Norte (m/s), velocityY = Este (m/s), velocityZ = Abajo (m/s)  [NED]
 */
data class TelemetryData(
    val timestampMs: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Float? = null,
    val velocityX: Float? = null,
    val velocityY: Float? = null,
    val velocityZ: Float? = null,
    val heading: Float? = null,
    val batteryPercent: Int? = null,
    val isFlying: Boolean = false,
    val areMotorsOn: Boolean = false,
    val satelliteCount: Int? = null,
    val gpsSignalLevel: Int? = null,
    val flightMode: String? = null
) {
    val horizontalSpeed: Double?
        get() {
            val vx = velocityX ?: return null
            val vy = velocityY ?: return null
            return sqrt((vx * vx + vy * vy).toDouble())
        }

    /** El SDK devuelve NaN/0.0 cuando no hay fix GPS (por ejemplo, dentro de un edificio). */
    val hasValidPosition: Boolean
        get() {
            val lat = latitude ?: return false
            val lon = longitude ?: return false
            return lat.isFinite() && lon.isFinite() &&
                    !(lat == 0.0 && lon == 0.0) &&
                    lat in -90.0..90.0 && lon in -180.0..180.0
        }
}
