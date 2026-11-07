package com.diegorivas.drone.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sesión de vuelo. El id lo genera el teléfono, así la app funciona aunque el
 * servidor de la PC esté caído: el servidor crea el vuelo al recibir el primer dato.
 */
data class FlightSession(
    val id: String,
    val startedAtMs: Long,
    val droneModel: String? = null
) {
    companion object {
        fun newSession(droneModel: String? = null): FlightSession {
            val now = System.currentTimeMillis()
            val id = "vuelo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
            return FlightSession(id, now, droneModel)
        }
    }
}
