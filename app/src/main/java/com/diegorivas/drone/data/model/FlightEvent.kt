package com.diegorivas.drone.data.model

/** Evento de vuelo (despegue, STOP, aterrizaje, misión...) con la telemetría del momento. */
data class FlightEvent(
    val flightId: String,
    val type: String,
    val details: String = "",
    val telemetry: TelemetryData,
    val timestampMs: Long = System.currentTimeMillis()
)
