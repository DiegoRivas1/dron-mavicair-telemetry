package com.diegorivas.drone

import android.content.Context
import android.os.Environment
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoRecorder(private val context: Context) {

    private var out: BufferedOutputStream? = null
    var currentFile: File? = null
        private set
    val isRecording: Boolean get() = out != null

    @Synchronized
    fun start(flightName: String? = null): File? {
        if (out != null) return currentFile
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "vuelos")
            .apply { mkdirs() }
        val name = flightName
            ?: ("vuelo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        val file = File(dir, "$name.h264")
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