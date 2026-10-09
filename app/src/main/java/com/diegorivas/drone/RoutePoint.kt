
package com.diegorivas.drone

data class RoutePoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Float
)

object TestRoute {

    // Coordenadas de ejemplo: reemplazarlas por puntos
    // de una zona de pruebas autorizada y despejada.
    val points = listOf(
        RoutePoint("A", -16.4090, -71.5375, 5f),
        RoutePoint("P1", -16.4088, -71.5372, 5f),
        RoutePoint("B", -16.4085, -71.5369, 5f)
    )

    fun validate(): String? {
        if (points.size < 2) {
            return "La ruta necesita al menos dos puntos."
        }

        if (points.any {
                !it.latitude.isFinite() ||
                        !it.longitude.isFinite() ||
                        it.latitude !in -90.0..90.0 ||
                        it.longitude !in -180.0..180.0 ||
                        !it.altitudeMeters.isFinite() ||
                        it.altitudeMeters < 0f
            }) {
            return "Hay coordenadas o altitudes inválidas."
        }

        return null
    }
}