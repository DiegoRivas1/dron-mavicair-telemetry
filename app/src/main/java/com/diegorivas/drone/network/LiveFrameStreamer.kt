package com.diegorivas.drone.network

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Video en vivo hacia la PC como fotogramas JPEG.
 *
 * Copia lo que se ve en la pantalla del teléfono (el TextureView donde DJICodecManager
 * ya decodifica el video del dron), lo comprime a JPEG y lo envía a POST /api/live/frame.
 * El panel web muestra cada imagen al instante.
 *
 * Por qué así y no reenviando el H.264: el H.264 crudo del Mavic Air necesita un
 * decodificador en el navegador que espere a un keyframe con SPS/PPS; si no llega
 * uno válido, la pantalla queda en NEGRO. Con JPEG cada mensaje es una imagen
 * completa: siempre se ve algo. El H.264 se sigue grabando aparte (MP4 en la PC).
 *
 * - Funciona siempre que el dron esté conectado, sin pulsar REC.
 * - Nunca hay más de un envío en curso: si la red va lenta se saltan fotogramas
 *   (no se acumula retraso).
 * - Si la PC no responde, reintenta cada BACKOFF_MS.
 */
class LiveFrameStreamer(
    private val api: ApiClient,
    private val view: TextureView,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val INTERVAL_MS = 125L      // ~8 fps
        private const val WIDTH = 640
        private const val HEIGHT = 360
        private const val JPEG_QUALITY = 60
        private const val BACKOFF_MS = 3000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-frames").apply { isDaemon = true }
    }
    private val sending = AtomicBoolean(false)

    @Volatile private var running = false
    @Volatile private var pausedUntilMs = 0L
    @Volatile private var failures = 0
    @Volatile private var streaming = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            capture()
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    /** Llamar desde el hilo principal. */
    fun start() {
        if (running) return
        running = true
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    fun shutdown() {
        stop()
        executor.shutdown()
    }

    /** Hilo principal: TextureView.getBitmap sólo puede llamarse aquí. */
    private fun capture() {
        if (System.currentTimeMillis() < pausedUntilMs) return
        if (!view.isAvailable) return
        if (!sending.compareAndSet(false, true)) return
        val bitmap: Bitmap? = try { view.getBitmap(WIDTH, HEIGHT) } catch (_: Throwable) { null }
        if (bitmap == null) {
            sending.set(false)
            return
        }
        executor.execute {
            try {
                val out = ByteArrayOutputStream(64 * 1024)
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                bitmap.recycle()
                val bytes = out.toByteArray()
                val r = api.postBytes("/api/live/frame", bytes, contentType = "image/jpeg")
                if (r.ok) {
                    failures = 0
                    if (!streaming) {
                        streaming = true
                        main.post { log("Video en vivo → PC: enviando") }
                    }
                } else {
                    failures++
                    if (failures >= 3) {
                        pausedUntilMs = System.currentTimeMillis() + BACKOFF_MS
                        if (streaming) {
                            streaming = false
                            main.post { log("Video en vivo → PC: sin respuesta (${r.code}), reintentando...") }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                sending.set(false)
            }
        }
    }
}
