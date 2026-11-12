package com.diegorivas.drone.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sesión de telemetría. El id lo genera el teléfono, así la app funciona aunque el
 * servidor de la PC esté caído: el servidor crea el vuelo al recibir el primer dato.
 *
 *  - "vuelo_AAAAMMDD_HHMMSS": desde que el dron despega hasta que aterriza.
 *  - "tierra_AAAAMMDD_HHMMSS": el dron conectado pero en el suelo (antes/entre vuelos).
 */
data class FlightSession(
    val id: String,
    val startedAtMs: Long,
    val droneModel: String? = null
) {
    companion object {
        fun newSession(droneModel: String? = null, prefix: String = "vuelo"): FlightSession {
            val now = System.currentTimeMillis()
            val id = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
            return FlightSession(id, now, droneModel)
        }
    }
}
