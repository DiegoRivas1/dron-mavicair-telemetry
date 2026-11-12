package com.diegorivas.drone.network

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente HTTP mínimo hacia el servidor FastAPI de la PC (misma red Wi-Fi).
 * Sin dependencias extra: HttpURLConnection + org.json (incluido en Android).
 *
 * TODOS los métodos son BLOQUEANTES: llámalos siempre desde un hilo de fondo
 * (TelemetryApi y VideoStreamClient ya tienen sus propios hilos).
 * Nada de esto participa en el control de vuelo: si la PC no responde, el dron
 * sigue funcionando igual.
 */
class ApiClient(context: Context) {

    companion object {
        private const val PREFS = "ground_station"
        private const val KEY_URL = "server_url"
        const val DEFAULT_URL = "http://192.168.1.100:8000"
        private const val CONNECT_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 5000
    }

    data class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    var baseUrl: String = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        private set

    /** Último resultado conocido de comunicación con la PC. */
    @Volatile
    var serverReachable: Boolean = false
        private set

    fun setBaseUrl(url: String) {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        baseUrl = u
        prefs.edit().putString(KEY_URL, u).apply()
    }

    fun getJson(path: String): Response = request("GET", path, null, null)

    fun postJson(path: String, json: String): Response =
        request("POST", path, json.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    fun postBytes(
        path: String,
        bytes: ByteArray,
        length: Int = bytes.size,
        contentType: String = "application/octet-stream"
    ): Response = request("POST", path, bytes, contentType, length)

    /** Sube un archivo completo por streaming (no lo carga en memoria). */
    fun uploadFile(path: String, file: File): Response {
        var conn: HttpURLConnection? = null
        return try {
            conn = open("POST", path).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setFixedLengthStreamingMode(file.length())
                readTimeout = 60_000
            }
            FileInputStream(file).use { input ->
                conn.outputStream.use { out -> input.copyTo(out, 64 * 1024) }
            }
            readResponse(conn)
        } catch (e: Exception) {
            serverReachable = false
            Response(-1, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    fun healthCheck(): Boolean = getJson("/api/health").ok

    private fun open(method: String, path: String): HttpURLConnection {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.useCaches = false
        return conn
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
        length: Int = body?.size ?: 0
    ): Response {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(method, path)
            if (body != null) {
                conn.doOutput = true
                contentType?.let { conn.setRequestProperty("Content-Type", it) }
                conn.setFixedLengthStreamingMode(length)
                conn.outputStream.use { it.write(body, 0, length) }
            }
            readResponse(conn)
        } catch (e: Exception) {
            serverReachable = false
            Response(-1, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): Response {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        serverReachable = code in 200..499
        return Response(code, text)
    }
}
