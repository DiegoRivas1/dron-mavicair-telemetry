package com.diegorivas.drone.dji

/**
 * Guarda los últimos SPS y PPS (NAL 7 y 8) que pasan por el video del dron.
 *
 * El Mavic Air casi nunca repite SPS/PPS ni manda fotogramas clave (usa "intra
 * refresh" para tener poca latencia). Si una grabación empieza en mitad del flujo,
 * el archivo .h264 no tiene SPS/PPS y NADIE puede decodificarlo: ffmpeg dice
 * "non-existing PPS 0 referenced" y el MP4 no se genera.
 *
 * Solución: recordar los SPS/PPS desde que se conecta el dron y escribirlos al
 * principio de cada grabación (ver VideoManager).
 *
 * Thread-safe: se llama desde el hilo del SDK.
 */
class H264ParamCache {

    companion object {
        /** SPS/PPS van al principio del bloque (tras el AUD); no hace falta mirar más. */
        private const val SCAN_BYTES = 1024
        private const val MAX_PARAM_BYTES = 512
    }

    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null

    @Volatile var seenCount = 0
        private set

    val hasParams: Boolean get() = sps != null && pps != null

    /** SPS + PPS con start codes de 4 bytes, listos para escribir; null si aún no llegaron. */
    fun header(): ByteArray? {
        val s = sps ?: return null
        val p = pps ?: return null
        return START + s + START + p
    }

    /** Busca SPS/PPS en un bloque del VideoDataListener. Rápido: sólo mira el inicio. */
    fun scan(buf: ByteArray, size: Int) {
        val limit = minOf(size, SCAN_BYTES)
        var i = 0
        while (i + 3 < limit) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                val start = i + 3
                val type = buf[start].toInt() and 0x1f
                if (type == 7 || type == 8) {
                    val end = nextStartCode(buf, start + 1, size)
                    val len = end - start
                    if (len in 2..MAX_PARAM_BYTES) {
                        val nal = buf.copyOfRange(start, end)
                        if (type == 7) sps = nal else pps = nal
                        seenCount++
                    }
                }
                i = start
            } else {
                i++
            }
        }
    }

    /** Posición del siguiente start code (00 00 01 o 00 00 00 01) o size. */
    private fun nextStartCode(buf: ByteArray, from: Int, size: Int): Int {
        var j = from
        while (j + 2 < size) {
            if (buf[j].toInt() == 0 && buf[j + 1].toInt() == 0 && buf[j + 2].toInt() == 1) {
                // Si es 00 00 00 01, el primer 00 pertenece al start code.
                return if (j > from && buf[j - 1].toInt() == 0) j - 1 else j
            }
            j++
        }
        return size
    }

    private val START = byteArrayOf(0, 0, 0, 1)
}
