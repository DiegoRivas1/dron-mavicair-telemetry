package com.diegorivas.drone.data.repository

import com.diegorivas.drone.data.model.FlightRoute
import com.diegorivas.drone.data.model.RoutePoint
import com.diegorivas.drone.data.model.TelemetryData
import com.diegorivas.drone.network.TelemetryApi
import com.diegorivas.drone.utils.FlightValidator
import com.diegorivas.drone.utils.GeoUtils

/**
 * Rutas de vuelo: generadas a partir de la posición REAL del dron o descargadas
 * del panel web de la PC. Las coordenadas de ejemplo antiguas (TestRoute) ya no
 * se usan para volar.
 */
class RouteRepository(private val telemetryApi: TelemetryApi) {

    /** Ruta elegida actualmente (para Waypoint Mission). */
    var currentRoute: FlightRoute? = null
        private set

    fun select(route: FlightRoute) {
        currentRoute = route
    }

    /**
     * Ruta de prueba A → B → A desde la posición actual del dron:
     * B queda a distanceM metros en la dirección a la que apunta el dron (heading).
     */
    fun buildOutAndBack(
        t: TelemetryData,
        distanceM: Double,
        altitudeM: Float,
        speedMps: Float
    ): Result<FlightRoute> {
        FlightValidator.gpsReady(t)?.let { return Result.failure(IllegalStateException(it)) }
        val lat = t.latitude!!
        val lon = t.longitude!!
        val heading = (t.heading ?: 0f).toDouble()
        val (bLat, bLon) = GeoUtils.destination(lat, lon, heading, distanceM)

        val route = FlightRoute(
            id = "aba_${System.currentTimeMillis()}",
            name = "A→B→A ${"%.0f".format(distanceM)} m",
            points = listOf(
                RoutePoint("A", lat, lon, altitudeM, speedMps),
                RoutePoint("B", bLat, bLon, altitudeM, speedMps),
                // Un 3er punto idéntico a A no es válido para WaypointMission (< 0.5 m),
                // así que el regreso se marca con un punto muy cercano a A (0.7 m antes).
                GeoUtils.destination(lat, lon, heading, 0.7).let { (rLat, rLon) ->
                    RoutePoint("A'", rLat, rLon, altitudeM, speedMps)
                }
            )
        )
        return Result.success(route)
    }

    /**
     * Descarga la ruta activa del panel web. BLOQUEANTE (llamar en hilo de fondo).
     */
    fun fetchActiveRouteFromServer(): FlightRoute? = telemetryApi.fetchActiveRoute()

    fun validate(route: FlightRoute, t: TelemetryData?): String? =
        FlightValidator.validateRoute(route, t)
}
