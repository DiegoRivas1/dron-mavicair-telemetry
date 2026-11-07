package com.diegorivas.drone.dji

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import com.diegorivas.drone.network.VideoStreamClient
import com.diegorivas.drone.recording.VideoRecorder
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import java.io.File

/**
 * Video en vivo del Mavic Air:
 *
 *   VideoFeeder.primaryVideoFeed ──► DJICodecManager (pantalla del teléfono)
 *                                ├─► VideoRecorder   (archivo .h264 local, respaldo)
 *                                └─► VideoStreamClient (bloques → PC)
 *
 * Misma lógica que el MainActivity anterior; ahora también reenvía a la PC.
 */
class VideoManager(
    private val context: Context,
    private val streamClient: VideoStreamClient
) : TextureView.SurfaceTextureListener {

    private var codecManager: DJICodecManager? = null
    val recorder = VideoRecorder(context)

    val isRecording: Boolean get() = recorder.isRecording
    val currentFile: File? get() = recorder.currentFile

    private val videoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
        codecManager?.sendDataToDecoder(videoBuffer, size)
        recorder.write(videoBuffer, size)
        streamClient.write(videoBuffer, size)
    }

    fun startVideoFeed() {
        VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(videoDataListener)
        VideoFeeder.getInstance()?.primaryVideoFeed?.addVideoDataListener(videoDataListener)
    }

    fun stopVideoFeed() {
        VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(videoDataListener)
    }

    /** Graba localmente y envía a la PC a la vez. Devuelve el archivo local. */
    fun startRecording(recordingId: String, flightId: String): File? {
        val file = recorder.start(recordingId)
        streamClient.start(recordingId, flightId)
        return file
    }

    /** Como startRecording, pero el .h264 se guarda en la carpeta del tramo. */
    fun startRecordingTo(file: File, recordingId: String, flightId: String): File? {
        val f = recorder.startAt(file)
        streamClient.start(recordingId, flightId)
        return f
    }

    /** Hora (epoch ms) del primer dato de video de la grabación actual. */
    val firstVideoDataMs: Long? get() = recorder.firstWriteMs

    fun stopRecording(): File? {
        recorder.stop()
        val file = recorder.currentFile
        streamClient.stop(file)
        return file
    }

    fun release() {
        stopVideoFeed()
        codecManager?.destroyCodec()
        codecManager = null
        if (recorder.isRecording) stopRecording()
    }

    // ---------- TextureView ----------

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (codecManager == null) {
            codecManager = DJICodecManager(context, surface, width, height)
        }
        startVideoFeed()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopVideoFeed()
        codecManager?.cleanSurface()
        codecManager?.destroyCodec()
        codecManager = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
}
