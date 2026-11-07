package com.diegorivas.drone.recording

import android.content.Context
import android.os.Environment
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Graba el H.264 crudo que llega del dron en el teléfono.
 * Es el RESPALDO local: si el Wi-Fi a la PC se corta, el archivo completo queda aquí
 * y VideoStreamClient lo vuelve a subir al terminar.
 *
 * Reproducir/convertir en la PC:  ffmpeg -f h264 -i vuelo.h264 -c copy vuelo.mp4
 */
class VideoRecorder(private val context: Context) {

    private var out: BufferedOutputStream? = null
    var currentFile: File? = null
        private set
    val isRecording: Boolean @Synchronized get() = out != null

    @Synchronized
    fun start(name: String? = null): File? {
        if (out != null) return currentFile
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "vuelos")
            .apply { mkdirs() }
        val fileName = name
            ?: ("vuelo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        val file = File(dir, "$fileName.h264")
        out = BufferedOutputStream(FileOutputStream(file), 256 * 1024)
        currentFile = file
        return file
    }

    @Synchronized
    fun write(buffer: ByteArray, size: Int) {
        try {
            out?.write(buffer, 0, size)
        } catch (e: Exception) {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        try { out?.flush(); out?.close() } catch (_: Exception) {}
        out = null
    }
}
