package com.diegorivas.drone.data.model

/**
 * Un punto de una ruta GPS.
 *
 * altitudeMeters es RELATIVA al punto de despegue (igual que la altitud que
 * reporta el FlightController del Mavic Air y que usa WaypointMission).
 */
data class RoutePoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Float,
    val speedMetersPerSecond: Float = 1.0f
)

/** Ruta completa: lista ordenada de puntos. */
data class FlightRoute(
    val id: String,
    val name: String,
    val points: List<RoutePoint>
)
