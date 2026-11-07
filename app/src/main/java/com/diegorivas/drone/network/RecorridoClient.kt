package com.diegorivas.drone.network

import com.diegorivas.drone.recording.TramoRecorder
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Envía los tramos (video + telemetría + meta) al RECORRIDO abierto en la PC.
 *
 *  REC  → reservar("grabando"): la PC dice "eres el tramo N del recorrido X"
 *  STOP → subir los 3 archivos de la carpeta del tramo → completar
 *
 * Usa su propio hilo para que una subida larga (decenas de MB) no frene la telemetría.
 */
class RecorridoClient(private val api: ApiClient) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "recorrido-upload").apply { isDaemon = true }
    }

    /** Ejecuta en el hilo de subidas (FIFO: una reserva siempre termina antes de su subida). */
    fun enqueue(block: () -> Unit) {
        executor.execute { try { block() } catch (_: Throwable) { } }
    }

    fun shutdown() = executor.shutdown()

    /** BLOQUEANTE. Pide a la PC el tramo que toca. Error con texto legible si no hay recorrido abierto. */
    fun reservar(estado: String): Result<TramoRecorder.Reserva> {
        val r = api.postJson("/api/recorridos/activo/reservar-tramo", JSONObject().put("estado", estado).toString())
        if (!r.ok) return Result.failure(IllegalStateException(detalle(r)))
        return try {
            val o = JSONObject(r.body)
            Result.success(TramoRecorder.Reserva(o.getString("recorrido_id"), o.optString("recorrido"), o.getInt("tramo")))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** BLOQUEANTE. Sube la carpeta del tramo. Devuelve null si todo fue bien o el error. */
    fun subir(dir: File, reserva: TramoRecorder.Reserva, progreso: (String) -> Unit): String? {
        val base = "/api/recorridos/" + enc(reserva.recorridoId) + "/tramos/" + reserva.tramo
        val archivos = listOf(TramoRecorder.VIDEO_FILE, TramoRecorder.TELEMETRY_FILE, TramoRecorder.META_FILE)
        for (nombre in archivos) {
            val f = File(dir, nombre)
            if (!f.exists()) {
                if (nombre == TramoRecorder.META_FILE) continue
                return "Falta $nombre en ${dir.name}"
            }
            progreso("Subiendo $nombre (${f.length() / 1024} KB)...")
            var r = api.uploadFile("$base/archivos/$nombre", f)
            if (!r.ok) {
                // Un reintento: el Wi-Fi del teléfono compartido a veces se corta un momento.
                Thread.sleep(2000)
                r = api.uploadFile("$base/archivos/$nombre", f)
            }
            if (!r.ok) return "No se pudo subir $nombre: ${detalle(r)}"
        }
        val c = api.postJson("$base/completar", "{}")
        return if (c.ok) null else "La PC no confirmó el tramo: ${detalle(c)}"
    }

    private fun detalle(r: ApiClient.Response): String {
        if (r.code == -1) return "PC sin conexión (${r.body})"
        return try {
            JSONObject(r.body).optString("detail").ifEmpty { "HTTP ${r.code}" }
        } catch (_: Exception) {
            "HTTP ${r.code}"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
