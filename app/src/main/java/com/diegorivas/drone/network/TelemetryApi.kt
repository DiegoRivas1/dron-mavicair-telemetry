package com.diegorivas.drone.network

import com.diegorivas.drone.data.model.FlightEvent
import com.diegorivas.drone.data.model.FlightRoute
import com.diegorivas.drone.data.model.RoutePoint
import com.diegorivas.drone.data.model.TelemetryData
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Envía telemetría y eventos a la PC en lotes, desde un hilo propio.
 *
 * - Telemetría: se muestrea a SAMPLE_INTERVAL_MS y se envía cada SEND_INTERVAL_MS.
 * - Si la PC no responde, se guarda en cola (con límite) y se reintenta.
 * - Nunca bloquea el hilo principal ni el control de vuelo.
 */
class TelemetryApi(
    private val api: ApiClient,
    private val onServerStatus: (Boolean) -> Unit = {}
) {
    companion object {
        private const val SAMPLE_INTERVAL_MS = 500L   // 2 Hz
        private const val SEND_INTERVAL_MS = 1000L
        private const val HEALTH_INTERVAL_MS = 5000L
        private const val MAX_QUEUED_SAMPLES = 1200    // ~10 min a 2 Hz
        private const val MAX_QUEUED_EVENTS = 500
        private const val MAX_BATCH = 100
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "telemetry-api").apply { isDaemon = true }
    }

    /** Cada muestra lleva el id del vuelo en el que se tomó (el id cambia al despegar/aterrizar). */
    private val samples = ArrayDeque<Pair<String, JSONObject>>()
    private val events = ArrayDeque<JSONObject>()
    private var lastSampleMs = 0L

    @Volatile
    var flightId: String? = null

    @Volatile
    var droneModel: String? = null

    private var lastReportedStatus: Boolean? = null

    fun start() {
        executor.scheduleWithFixedDelay({ safe { flush() } }, SEND_INTERVAL_MS, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS)
        executor.scheduleWithFixedDelay({ safe { health() } }, 0, HEALTH_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun shutdown() {
        executor.submit { safe { flush() } }
        executor.shutdown()
    }

    /** Llamar en cada actualización de telemetría; se submuestrea internamente. */
    fun onTelemetry(t: TelemetryData) {
        val now = System.currentTimeMillis()
        if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
        lastSampleMs = now
        val id = flightId ?: return
        val json = try { telemetryToJson(t) } catch (_: Exception) { return }
        executor.execute {
            samples.addLast(id to json)
            while (samples.size > MAX_QUEUED_SAMPLES) samples.removeFirst()
        }
    }

    fun sendEvent(e: FlightEvent) {
        val json = try {
            JSONObject()
                .put("flight_id", e.flightId)
                .put("type", e.type)
                .put("details", e.details)
                .put("timestamp_ms", e.timestampMs)
                .put("telemetry", telemetryToJson(e.telemetry))
        } catch (_: Exception) { return }
        executor.execute {
            events.addLast(json)
            while (events.size > MAX_QUEUED_EVENTS) events.removeFirst()
            safe { flushEvents() }
        }
    }

    /**
     * Descarga la ruta activa definida en el panel web.
     * BLOQUEANTE: llamar desde un hilo de fondo. Devuelve null si no hay ruta o falla.
     */
    fun fetchActiveRoute(): FlightRoute? {
        val r = api.getJson("/api/routes/active")
        if (!r.ok || r.body.isBlank() || r.body == "null") return null
        return try {
            val o = JSONObject(r.body)
            val pts = o.getJSONArray("points")
            FlightRoute(
                id = o.get("id").toString(),
                name = o.optString("name", "Ruta"),
                points = (0 until pts.length()).map { i ->
                    val p = pts.getJSONObject(i)
                    RoutePoint(
                        id = p.optString("id", "P${i + 1}"),
                        latitude = p.getDouble("latitude"),
                        longitude = p.getDouble("longitude"),
                        altitudeMeters = p.getDouble("altitude_m").toFloat(),
                        speedMetersPerSecond = p.optDouble("speed_mps", 1.0).toFloat()
                    )
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Ejecuta un bloque en el hilo de red (para llamadas bloqueantes puntuales). */
    fun runInBackground(block: () -> Unit) {
        executor.execute { safe(block) }
    }

    // ------------------------------------------------------------------

    private fun flush() {
        flushEvents()
        while (samples.isNotEmpty()) {
            // Un lote = muestras consecutivas del mismo vuelo.
            val id = samples.peekFirst()?.first ?: return
            val batch = JSONArray()
            val taken = mutableListOf<Pair<String, JSONObject>>()
            while (samples.isNotEmpty() && taken.size < MAX_BATCH && samples.peekFirst()?.first == id) {
                taken.add(samples.removeFirst())
            }
            taken.forEach { batch.put(it.second) }
            val body = JSONObject()
                .put("flight_id", id)
                .put("drone_model", droneModel ?: JSONObject.NULL)
                .put("samples", batch)
            val r = api.postJson("/api/telemetry", body.toString())
            if (!r.ok) {
                // Devolver a la cola, en orden, y reintentar en el próximo ciclo.
                taken.asReversed().forEach { samples.addFirst(it) }
                while (samples.size > MAX_QUEUED_SAMPLES) samples.removeFirst()
                reportStatus(false)
                return
            }
            reportStatus(true)
        }
    }

    private fun flushEvents() {
        while (events.isNotEmpty()) {
            val e = events.peekFirst() ?: return
            val r = api.postJson("/api/events", e.toString())
            if (!r.ok) {
                reportStatus(false)
                return
            }
            events.removeFirst()
            reportStatus(true)
        }
    }

    private fun health() {
        reportStatus(api.healthCheck())
    }

    private fun reportStatus(ok: Boolean) {
        if (lastReportedStatus != ok) {
            lastReportedStatus = ok
            onServerStatus(ok)
        }
    }

    /** JSONObject lanza excepción con NaN/Infinity: se envían como null. */
    private fun num(v: Number?): Any {
        val d = v?.toDouble() ?: return JSONObject.NULL
        return if (d.isFinite()) d else JSONObject.NULL
    }

    private fun telemetryToJson(t: TelemetryData): JSONObject = JSONObject()
        .put("timestamp_ms", t.timestampMs)
        .put("latitude", if (t.hasValidPosition) num(t.latitude) else JSONObject.NULL)
        .put("longitude", if (t.hasValidPosition) num(t.longitude) else JSONObject.NULL)
        .put("altitude_m", num(t.altitude))
        .put("velocity_x", num(t.velocityX))
        .put("velocity_y", num(t.velocityY))
        .put("velocity_z", num(t.velocityZ))
        .put("speed_mps", num(t.horizontalSpeed))
        .put("heading_deg", num(t.heading))
        .put("battery_pct", t.batteryPercent ?: JSONObject.NULL)
        .put("is_flying", t.isFlying)
        .put("motors_on", t.areMotorsOn)
        .put("satellites", t.satelliteCount ?: JSONObject.NULL)
        .put("gps_level", t.gpsSignalLevel ?: JSONObject.NULL)
        .put("flight_mode", t.flightMode ?: JSONObject.NULL)

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) { }
    }
}
