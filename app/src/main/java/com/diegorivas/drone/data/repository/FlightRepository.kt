package com.diegorivas.drone.data.repository

import android.content.Context
import com.diegorivas.drone.data.model.FlightEvent
import com.diegorivas.drone.data.model.FlightSession
import com.diegorivas.drone.data.model.TelemetryData
import com.diegorivas.drone.network.TelemetryApi
import com.diegorivas.drone.recording.TelemetryLogger

/**
 * Punto único para registrar la telemetría:
 *  - CSV local en el teléfono (TelemetryLogger)  → siempre
 *  - Servidor de la PC (TelemetryApi)            → cuando hay conexión
 *
 * UN VUELO = desde que el dron despega hasta que aterriza (lo decide MainActivity
 * con IS_FLYING). Cada vuelo tiene su id, su CSV en el teléfono y su carpeta en la PC.
 * Mientras el dron está en tierra se usa una sesión "tierra_..." aparte, para que los
 * datos del suelo no se mezclen con los del vuelo.
 */
class FlightRepository(
    private val context: Context,
    private val telemetryApi: TelemetryApi
) {
    companion object {
        private const val CSV_TELEMETRY_INTERVAL_MS = 200L // 5 Hz en el CSV
    }

    var session: FlightSession = FlightSession.newSession(prefix = "tierra")
        private set

    /** true entre startFlight() y endFlight(). */
    var inFlight = false
        private set

    private var droneModel: String? = null
    private var logger: TelemetryLogger = TelemetryLogger(context, session.id)
    private var lastCsvTelemetryMs = 0L

    init {
        telemetryApi.flightId = session.id
    }

    /** Asigna el modelo del dron conectado (se envía al servidor con la telemetría). */
    fun setDroneModel(model: String?) {
        droneModel = model
        session = session.copy(droneModel = model)
        telemetryApi.droneModel = model
    }

    /** El dron acaba de despegar: abre un vuelo nuevo. Devuelve su id. */
    fun startFlight(): String {
        switchTo(FlightSession.newSession(droneModel, prefix = "vuelo"))
        inFlight = true
        return session.id
    }

    /** El dron aterrizó: vuelve a una sesión de tierra. Devuelve el id del vuelo cerrado. */
    fun endFlight(): String {
        val closed = session.id
        inFlight = false
        switchTo(FlightSession.newSession(droneModel, prefix = "tierra"))
        return closed
    }

    private fun switchTo(newSession: FlightSession) {
        logger.close()
        session = newSession
        logger = TelemetryLogger(context, session.id)
        telemetryApi.flightId = session.id
        lastCsvTelemetryMs = 0L
    }

    fun onTelemetry(t: TelemetryData) {
        val now = System.currentTimeMillis()
        if (now - lastCsvTelemetryMs >= CSV_TELEMETRY_INTERVAL_MS) {
            lastCsvTelemetryMs = now
            write("TELEMETRY", "", t)
        }
        telemetryApi.onTelemetry(t)
    }

    fun event(type: String, details: String, t: TelemetryData) {
        write(type, details, t)
        telemetryApi.sendEvent(FlightEvent(session.id, type, details, t))
    }

    private fun write(type: String, details: String, t: TelemetryData) {
        logger.log(
            event = type,
            latitude = t.latitude,
            longitude = t.longitude,
            altitude = t.altitude?.toDouble(),
            speed = t.horizontalSpeed,
            heading = t.heading,
            battery = t.batteryPercent,
            details = details
        )
    }

    fun close() {
        logger.close()
    }
}
