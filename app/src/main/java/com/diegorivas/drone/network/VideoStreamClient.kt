package com.diegorivas.drone.network

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Envía a la PC, por bloques, el H.264 que llega del Mavic Air mientras se graba.
 *
 *   VideoFeeder → VideoManager → VideoRecorder (archivo local, respaldo)
 *                              → VideoStreamClient (bloques HTTP → FastAPI)
 *
 * - Cada CHUNK_INTERVAL_MS se envía lo acumulado como un bloque numerado (seq).
 * - Si un bloque falla se reintenta en orden; si la cola supera MAX_PENDING_BYTES
 *   se descartan bloques y se marca la grabación como incompleta.
 * - Al detener: si hubo pérdidas, se sube el archivo local COMPLETO para que la
 *   PC tenga una copia íntegra.
 *
 * El servidor también reenvía los bloques al panel web para verlos en vivo.
 */
class VideoStreamClient(
    private val api: ApiClient,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val CHUNK_INTERVAL_MS = 500L
        private const val MAX_PENDING_BYTES = 16 * 1024 * 1024
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "video-stream").apply { isDaemon = true }
    }

    private val lock = Any()
    private var buffer = ByteArrayOutputStream(512 * 1024)
    private val pending = ArrayDeque<Pair<Int, ByteArray>>()
    private var pendingBytes = 0

    @Volatile private var active = false
    private var recordingId: String? = null
    private var flightId: String? = null
    private var nextSeq = 0
    private var lostData = false
    private var sentChunks = 0
    private var task: ScheduledFuture<*>? = null

    val isActive: Boolean get() = active

    fun start(recordingId: String, flightId: String) {
        synchronized(lock) {
            if (active) return
            buffer.reset()
            nextSeq = 0
            active = true
        }
        // El estado de la cola sólo se toca desde el hilo de envío (después de
        // que termine cualquier stop() anterior, porque el executor es FIFO).
        executor.execute {
            this.recordingId = recordingId
            this.flightId = flightId
            pending.clear()
            pendingBytes = 0
            sentChunks = 0
            lostData = false
        }
        task = executor.scheduleWithFixedDelay({ sendCycle() }, CHUNK_INTERVAL_MS, CHUNK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        log("Video → PC: iniciado ($recordingId)")
    }

    /** Llamado desde el listener de VideoFeeder (hilo del SDK). Debe ser rápido. */
    fun write(data: ByteArray, size: Int) {
        if (!active) return
        synchronized(lock) {
            if (active) buffer.write(data, 0, size)
        }
    }

    /**
     * Detiene el envío. localFile es el .h264 grabado en el teléfono: si se perdieron
     * bloques, se sube completo para reemplazar la copia incompleta de la PC.
     */
    fun stop(localFile: File?) {
        val tail: ByteArray
        val tailSeq: Int
        synchronized(lock) {
            if (!active) return
            active = false
            tail = buffer.toByteArray()
            buffer.reset()
            tailSeq = nextSeq++
        }
        task?.cancel(false)
        executor.execute {
            try {
                if (tail.isNotEmpty()) {
                    pending.addLast(tailSeq to tail)
                    pendingBytes += tail.size
                }
                // Último intento de vaciar la cola.
                drainPending(maxAttempts = 3)
                val id = recordingId ?: return@execute
                val incomplete = lostData || pending.isNotEmpty()
                val q = query()
                api.postJson(
                    "/api/video/$id/complete$q",
                    """{"chunks":$sentChunks,"incomplete":$incomplete}"""
                )
                if (incomplete && localFile != null && localFile.exists()) {
                    log("Video → PC: hubo pérdidas, subiendo archivo completo (${localFile.length() / 1024} KB)...")
                    val r = api.uploadFile("/api/video/$id/upload$q", localFile)
                    log(if (r.ok) "Video → PC: archivo completo subido" else "Video → PC: subida fallida (${r.code}); el archivo queda en el teléfono")
                } else {
                    log("Video → PC: finalizado, $sentChunks bloques")
                }
                pending.clear()
                pendingBytes = 0
            } catch (e: Exception) {
                log("Video → PC: error al finalizar: ${e.message}")
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }

    // ------------------------------------------------------------------

    private fun sendCycle() {
        try {
            cutChunk()
            drainPending(maxAttempts = 1)
        } catch (_: Throwable) { }
    }

    /** Mueve lo acumulado en buffer a la cola como un bloque numerado. */
    private fun cutChunk() {
        val bytes: ByteArray
        val seq: Int
        synchronized(lock) {
            if (buffer.size() == 0) return
            bytes = buffer.toByteArray()
            buffer.reset()
            seq = nextSeq++
        }
        pending.addLast(seq to bytes)
        pendingBytes += bytes.size
        while (pendingBytes > MAX_PENDING_BYTES && pending.isNotEmpty()) {
            val dropped = pending.removeFirst()
            pendingBytes -= dropped.second.size
            if (!lostData) log("Video → PC: sin conexión, descartando bloques (el archivo local está completo)")
            lostData = true
        }
    }

    private fun drainPending(maxAttempts: Int) {
        val id = recordingId ?: return
        var failures = 0
        while (pending.isNotEmpty()) {
            val (seq, bytes) = pending.peekFirst() ?: return
            val r = api.postBytes("/api/video/$id/chunk${query()}&seq=$seq", bytes)
            if (r.ok) {
                pending.removeFirst()
                pendingBytes -= bytes.size
                sentChunks++
            } else {
                failures++
                if (failures >= maxAttempts) return
            }
        }
    }

    private fun query(): String =
        "?flight_id=" + URLEncoder.encode(flightId ?: "", "UTF-8")
}
