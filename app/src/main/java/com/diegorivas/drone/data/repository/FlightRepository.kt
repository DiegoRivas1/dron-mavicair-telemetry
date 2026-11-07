package com.diegorivas.drone.data.repository

import android.content.Context
import com.diegorivas.drone.data.model.FlightEvent
import com.diegorivas.drone.data.model.FlightSession
import com.diegorivas.drone.data.model.TelemetryData
import com.diegorivas.drone.network.TelemetryApi
import com.diegorivas.drone.recording.TelemetryLogger

/**
 * Punto único para registrar un vuelo:
 *  - CSV local en el teléfono (TelemetryLogger)  → siempre
 *  - Servidor de la PC (TelemetryApi)            → cuando hay conexión
 *
 * Sustituye las llamadas repetidas a telemetryLogger?.log(...) del MainActivity anterior.
 */
class FlightRepository(
    private val context: Context,
    private val telemetryApi: TelemetryApi
) {
    companion object {
        private const val CSV_TELEMETRY_INTERVAL_MS = 200L // 5 Hz en el CSV
    }

    var session: FlightSession = FlightSession.newSession()
        private set

    private var logger: TelemetryLogger = TelemetryLogger(context, session.id)
    private var lastCsvTelemetryMs = 0L

    init {
        telemetryApi.flightId = session.id
    }

    /** Asigna el modelo del dron conectado (se envía al servidor con la telemetría). */
    fun setDroneModel(model: String?) {
        session = session.copy(droneModel = model)
        telemetryApi.droneModel = model
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
