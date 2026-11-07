package com.diegorivas.drone.recording

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro CSV local de telemetría y eventos (mismo formato que la versión anterior).
 * Es el respaldo en el teléfono: funciona aunque no haya conexión con la PC.
 *
 * Cambio respecto a la versión anterior: mantiene el archivo abierto con buffer
 * (antes abría/cerraba el archivo en cada muestra) y tiene close().
 */
class TelemetryLogger(context: Context, sessionId: String) {

    private val file: File
    private var writer: BufferedWriter?
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private var linesSinceFlush = 0

    init {
        val directory = File(context.getExternalFilesDir(null), "telemetry")
        if (!directory.exists()) directory.mkdirs()

        file = File(directory, "$sessionId.csv")
        writer = BufferedWriter(FileWriter(file, false)).also {
            it.append(
                "timestamp,event,latitude,longitude," +
                        "altitude_m,speed_mps,heading_deg,battery_pct,details\n"
            )
            it.flush()
        }
    }

    @Synchronized
    fun log(
        event: String,
        latitude: Double?,
        longitude: Double?,
        altitude: Double?,
        speed: Double?,
        heading: Float?,
        battery: Int?,
        details: String = ""
    ) {
        val w = writer ?: return
        try {
            val values = listOf(
                tsFormat.format(Date()),
                event,
                latitude?.toString() ?: "",
                longitude?.toString() ?: "",
                altitude?.toString() ?: "",
                speed?.toString() ?: "",
                heading?.toString() ?: "",
                battery?.toString() ?: "",
                details
            )
            w.append(values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
            w.append("\n")

            // Los eventos se escriben de inmediato; la telemetría cada 20 líneas.
            linesSinceFlush++
            if (event != "TELEMETRY" || linesSinceFlush >= 20) {
                w.flush()
                linesSinceFlush = 0
            }
        } catch (_: Exception) {
            // Nunca dejar que un fallo de escritura afecte al vuelo.
        }
    }

    @Synchronized
    fun close() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
    }

    fun getFile(): File = file
}
