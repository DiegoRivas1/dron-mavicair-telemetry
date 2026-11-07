package com.diegorivas.drone.utils

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cálculos geográficos sobre coordenadas del DRON (WGS84).
 * Para distancias de pruebas (metros a cientos de metros) la fórmula de
 * Haversine es más que suficiente.
 */
object GeoUtils {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Distancia en metros entre dos coordenadas. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Rumbo inicial (0..360°, 0 = Norte, 90 = Este) desde el punto 1 hacia el punto 2. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Punto destino a partir de un origen, un rumbo (°) y una distancia (m). */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2)
        )
        val lon2 = (Math.toDegrees(lambda2) + 540.0) % 360.0 - 180.0
        return Math.toDegrees(phi2) to lon2
    }

    /**
     * Error de posición expresado en metros Norte/Este (aproximación local plana).
     * Positivo = el objetivo está al Norte / al Este de la posición actual.
     */
    fun offsetNorthEastMeters(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): Pair<Double, Double> {
        val north = Math.toRadians(toLat - fromLat) * EARTH_RADIUS_M
        val east = Math.toRadians(toLon - fromLon) * EARTH_RADIUS_M * cos(Math.toRadians(fromLat))
        return north to east
    }

    /** ¿Llegó al punto según la tolerancia en metros? */
    fun hasArrived(
        currentLat: Double, currentLon: Double,
        targetLat: Double, targetLon: Double,
        toleranceMeters: Double
    ): Boolean = distanceMeters(currentLat, currentLon, targetLat, targetLon) <= toleranceMeters

    /** Longitud total de una ruta (lista de pares lat/lon). */
    fun pathLengthMeters(points: List<Pair<Double, Double>>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceMeters(a.first, a.second, b.first, b.second) }
}

/**
 * Acumula la distancia recorrida a partir de la telemetría GPS del dron.
 * Ignora saltos por debajo de minStepMeters (ruido del GPS en hover).
 */
class DistanceTracker(private val minStepMeters: Double = 0.3) {
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    var totalMeters: Double = 0.0
        private set

    fun reset() {
        lastLat = null
        lastLon = null
        totalMeters = 0.0
    }

    fun update(lat: Double, lon: Double) {
        val pLat = lastLat
        val pLon = lastLon
        if (pLat == null || pLon == null) {
            lastLat = lat; lastLon = lon
            return
        }
        val step = GeoUtils.distanceMeters(pLat, pLon, lat, lon)
        if (step >= minStepMeters) {
            totalMeters += step
            lastLat = lat; lastLon = lon
        }
    }
}
