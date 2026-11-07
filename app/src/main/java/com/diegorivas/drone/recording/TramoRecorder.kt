package com.diegorivas.drone.recording

import android.content.Context
import com.diegorivas.drone.data.model.TelemetryData
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Un TRAMO = una grabación REC → STOP. Cada tramo es una carpeta en el teléfono:
 *
 *   Android/data/com.diegorivas.drone/files/tramos/tramo_YYYYMMDD_HHMMSS/
 *       video.h264      video del dron (VideoRecorder)
 *       telemetria.csv  formato del análisis: timestamp_ms,lat,lon,alt_m,tramo_id,...
 *       meta.json       {"video_start_epoch_ms": ...} para sincronizar video y telemetría
 *       estado.json     pendiente / subido + recorrido y tramo asignados por la PC
 *
 * Los archivos SIEMPRE quedan en el teléfono; la subida a la PC es una copia.
 */
class TramoRecorder(context: Context) {

    companion object {
        private const val SAMPLE_INTERVAL_MS = 200L   // 5 Hz
        const val VIDEO_FILE = "video.h264"
        const val TELEMETRY_FILE = "telemetria.csv"
        const val META_FILE = "meta.json"
        private const val STATE_FILE = "estado.json"
    }

    data class Reserva(val recorridoId: String, val recorrido: String, val tramo: Int)

    val baseDir = File(context.getExternalFilesDir(null), "tramos").apply { mkdirs() }

    var currentDir: File? = null
        private set
    private var writer: BufferedWriter? = null
    private var lastSampleMs = 0L
    private var startedMs = 0L
    private var samples = 0

    val isRecording: Boolean get() = writer != null

    @Synchronized
    fun start(): File {
        val name = "tramo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var dir = File(baseDir, name)
        var i = 2
        while (dir.exists()) dir = File(baseDir, "${name}_${i++}")
        dir.mkdirs()
        currentDir = dir
        startedMs = System.currentTimeMillis()
        samples = 0
        lastSampleMs = 0L
        writer = BufferedWriter(FileWriter(File(dir, TELEMETRY_FILE))).also {
            it.append("timestamp_ms,lat,lon,alt_m,tramo_id,heading_deg,speed_mps,battery_pct,satellites\n")
            it.flush()
        }
        writeState(dir, JSONObject().put("estado", "grabando"))
        return dir
    }

    /** Llamar en cada actualización de telemetría. Sólo guarda filas con posición GPS válida. */
    @Synchronized
    fun onTelemetry(t: TelemetryData) {
        val w = writer ?: return
        if (!t.hasValidPosition) return
        val now = System.currentTimeMillis()
        if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
        lastSampleMs = now
        fun n(v: Number?): String = v?.toDouble()?.takeIf { it.isFinite() }?.toString() ?: ""
        try {
            // tramo_id = 0: la PC lo reemplaza por el número real del tramo al recibirlo.
            w.append(
                "${t.timestampMs},${t.latitude},${t.longitude},${n(t.altitude).ifEmpty { "0" }},0," +
                        "${n(t.heading)},${n(t.horizontalSpeed)},${t.batteryPercent ?: ""},${t.satelliteCount ?: ""}\n"
            )
            samples++
            if (samples % 25 == 0) w.flush()
        } catch (_: Exception) { }
    }

    /** Cierra el tramo: escribe meta.json y lo marca como pendiente de subir. */
    @Synchronized
    fun stop(videoStartMs: Long?, droneModel: String?): File? {
        val dir = currentDir ?: return null
        try { writer?.flush(); writer?.close() } catch (_: Exception) { }
        writer = null
        currentDir = null

        val meta = JSONObject()
            .put("video_start_epoch_ms", videoStartMs ?: startedMs)
            .put("origen_inicio", if (videoStartMs != null) "primer_dato_video" else "pulsacion_rec")
            .put("rec_pulsado_epoch_ms", startedMs)
            .put("fps", 30)
            .put("muestras_gps", samples)
            .put("dron", droneModel ?: "")
        File(dir, META_FILE).writeText(meta.toString(2))

        val st = readState(dir).put("estado", "pendiente")
        writeState(dir, st)
        return dir
    }

    // ---------------- estado / pendientes ----------------

    fun samplesInCurrent(): Int = samples

    fun reserva(dir: File): Reserva? {
        val s = readState(dir)
        if (!s.has("recorrido_id")) return null
        return Reserva(s.getString("recorrido_id"), s.optString("recorrido"), s.getInt("tramo"))
    }

    @Synchronized
    fun guardarReserva(dir: File, r: Reserva) {
        writeState(dir, readState(dir)
            .put("recorrido_id", r.recorridoId).put("recorrido", r.recorrido).put("tramo", r.tramo))
    }

    @Synchronized
    fun marcarSubido(dir: File) {
        writeState(dir, readState(dir).put("estado", "subido").put("subido_ms", System.currentTimeMillis()))
    }

    /** Tramos terminados que aún no llegaron a la PC (más antiguos primero). */
    fun pendientes(): List<File> =
        (baseDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it != currentDir && readState(it).optString("estado") == "pendiente" }
            .sortedBy { it.name }

    private fun readState(dir: File): JSONObject = try {
        JSONObject(File(dir, STATE_FILE).readText())
    } catch (_: Exception) {
        JSONObject()
    }

    private fun writeState(dir: File, json: JSONObject) {
        try { File(dir, STATE_FILE).writeText(json.toString(2)) } catch (_: Exception) { }
    }
}
