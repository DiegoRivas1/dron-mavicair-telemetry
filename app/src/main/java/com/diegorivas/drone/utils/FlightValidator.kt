package com.diegorivas.drone.utils

import com.diegorivas.drone.data.model.FlightRoute
import com.diegorivas.drone.data.model.TelemetryData

/**
 * Comprobaciones de seguridad ANTES de mover el dron de forma automática.
 * Devuelven null si todo está bien, o un texto con el motivo del bloqueo.
 *
 * Los límites son deliberadamente conservadores para pruebas.
 */
object FlightValidator {

    const val MIN_BATTERY_FOR_TAKEOFF = 20
    const val MIN_BATTERY_FOR_MISSION = 30
    const val MIN_SATELLITES = 8
    const val MIN_GPS_LEVEL = 3          // GPSSignalLevel 0..5
    const val MAX_ROUTE_RADIUS_M = 100.0 // distancia máx. de cualquier punto al dron
    const val MAX_SEGMENT_M = 100.0
    const val MIN_SEGMENT_M = 0.6        // WaypointMission exige > 0.5 m entre puntos
    const val MIN_ALTITUDE_M = 1.5f
    const val MAX_ALTITUDE_M = 30f
    const val MAX_SPEED_MPS = 5f

    fun canTakeoff(t: TelemetryData): String? {
        val bat = t.batteryPercent
        if (bat != null && bat < MIN_BATTERY_FOR_TAKEOFF) return "Batería baja ($bat%)."
        if (t.isFlying) return "El dron ya está volando."
        if (t.areMotorsOn) return "Los motores ya están encendidos."
        val mode = t.flightMode ?: ""
        if (mode.contains("sport", ignoreCase = true)) {
            return "El mando está en S (Sport). Pon el interruptor en P."
        }
        if (mode.contains("tripod", ignoreCase = true)) {
            return "El dron está en modo Trípode. Pon el interruptor en P."
        }
        return null
    }

    /** Requisitos de GPS para navegar por coordenadas (Virtual Stick GPS o Waypoint). */
    fun gpsReady(t: TelemetryData): String? {
        if (!t.hasValidPosition) return "Sin posición GPS válida del dron."
        val sats = t.satelliteCount
        if (sats != null && sats < MIN_SATELLITES) return "Pocos satélites ($sats < $MIN_SATELLITES)."
        val lvl = t.gpsSignalLevel
        if (lvl != null && lvl < MIN_GPS_LEVEL) return "Señal GPS débil (nivel $lvl)."
        return null
    }

    fun canStartMission(t: TelemetryData): String? {
        gpsReady(t)?.let { return it }
        val bat = t.batteryPercent
        if (bat != null && bat < MIN_BATTERY_FOR_MISSION) return "Batería insuficiente para misión ($bat%)."
        return null
    }

    fun validateRoute(route: FlightRoute, current: TelemetryData? = null): String? {
        val pts = route.points
        if (pts.size < 2) return "La ruta necesita al menos dos puntos."
        if (pts.size > 99) return "Máximo 99 puntos por misión."

        pts.forEachIndexed { i, p ->
            if (!p.latitude.isFinite() || !p.longitude.isFinite() ||
                p.latitude !in -90.0..90.0 || p.longitude !in -180.0..180.0
            ) return "Punto ${i + 1} (${p.id}): coordenadas inválidas."
            if (!p.altitudeMeters.isFinite() || p.altitudeMeters < MIN_ALTITUDE_M || p.altitudeMeters > MAX_ALTITUDE_M)
                return "Punto ${i + 1} (${p.id}): altitud fuera de rango ($MIN_ALTITUDE_M..$MAX_ALTITUDE_M m)."
            if (p.speedMetersPerSecond <= 0f || p.speedMetersPerSecond > MAX_SPEED_MPS)
                return "Punto ${i + 1} (${p.id}): velocidad fuera de rango (0..$MAX_SPEED_MPS m/s)."
        }

        pts.zipWithNext().forEachIndexed { i, (a, b) ->
            val d = GeoUtils.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            if (d < MIN_SEGMENT_M) return "Tramo ${i + 1}: puntos demasiado cercanos (${"%.2f".format(d)} m)."
            if (d > MAX_SEGMENT_M) return "Tramo ${i + 1}: demasiado largo (${"%.0f".format(d)} m)."
        }

        if (current != null && current.hasValidPosition) {
            pts.forEachIndexed { i, p ->
                val d = GeoUtils.distanceMeters(current.latitude!!, current.longitude!!, p.latitude, p.longitude)
                if (d > MAX_ROUTE_RADIUS_M)
                    return "Punto ${i + 1} (${p.id}) está a ${"%.0f".format(d)} m del dron (máx $MAX_ROUTE_RADIUS_M m)."
            }
        }
        return null
    }
}
