package com.diegorivas.drone.dji

import android.os.Handler
import android.os.Looper
import com.diegorivas.drone.data.model.TelemetryData
import com.diegorivas.drone.utils.GeoUtils
import dji.common.error.DJIError
import dji.common.flightcontroller.virtualstick.FlightControlData
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem
import dji.common.flightcontroller.virtualstick.RollPitchControlMode
import dji.common.flightcontroller.virtualstick.VerticalControlMode
import dji.common.flightcontroller.virtualstick.YawControlMode
import dji.common.util.CommonCallbacks
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Virtual Stick: la app envía comandos de velocidad continuamente (cada 100 ms).
 *
 * La configuración (VELOCITY / BODY) y la forma de enviar comandos son las mismas
 * que en el MainActivity anterior, para no alterar lo que ya funcionaba.
 *
 * Todos los métodos se llaman desde el HILO PRINCIPAL.
 */
class VirtualStickController(
    private val flight: FlightControllerManager,
    private val telemetry: () -> TelemetryData,
    private val onLog: (Char, String) -> Unit
) {
    companion object {
        const val COMMAND_INTERVAL_MS = 100L

        /**
         * Mapeo de ejes en modo VELOCITY.
         * false = como hasta ahora: pitch = adelante (BODY) / Norte (GROUND), roll = derecha / Este.
         *
         * Varios desarrolladores reportan que en DJI MSDK 4 los ejes van cruzados en modo
         * VELOCITY (roll = adelante, pitch = derecha). VERIFÍCALO con "4. AVANZAR 1 s":
         * si el dron se desplaza hacia un LADO en vez de hacia adelante, cambia esto a true.
         * Afecta a las pruebas por tiempo y a la navegación GPS.
         */
        const val VELOCITY_AXES_SWAPPED = false
    }

    private val main = Handler(Looper.getMainLooper())

    var isEnabled = false
        private set

    /** true mientras hay un movimiento por tiempo o por GPS en curso. */
    var isMoving = false
        private set

    private var forwardCmd = 0f
    private var rightCmd = 0f
    private var sender: Runnable? = null
    private var gpsLoop: Runnable? = null
    private var groundFrame = false

    // =========================================================
    // ACTIVAR / DESACTIVAR
    // =========================================================

    fun enable(onResult: (String?) -> Unit) {
        val fc = flight.flightController ?: return onResult("FlightController no disponible")
        if (!flight.acquire(NavigationMode.VIRTUAL_STICK)) {
            return onResult("Hay otro modo de navegación activo (${flight.navigationMode})")
        }
        fc.setVirtualStickModeEnabled(true, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                main.post {
                    if (error == null) {
                        isEnabled = true
                        configure(ground = false)
                        onLog('I', "VirtualStick ACTIVADO")
                        onResult(null)
                    } else {
                        isEnabled = false
                        flight.release(NavigationMode.VIRTUAL_STICK)
                        onLog('E', "ERROR ACTIVANDO VIRTUAL STICK: ${error.description}")
                        onResult(error.description)
                    }
                }
            }
        })
    }

    fun disable(onResult: (String?) -> Unit = {}) {
        stopMovement()
        val fc = flight.flightController
        if (fc == null || !isEnabled) {
            isEnabled = false
            flight.release(NavigationMode.VIRTUAL_STICK)
            return onResult(null)
        }
        sendOnce(0f, 0f)
        fc.setVirtualStickModeEnabled(false, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                main.post {
                    if (error == null) {
                        // Solo liberamos el estado si el SDK confirmó la desactivación
                        isEnabled = false
                        flight.release(NavigationMode.VIRTUAL_STICK)
                        onLog('I', "VirtualStick DESACTIVADO")
                        onResult(null)
                    } else {
                        // El dron puede seguir en modo virtual stick: NO tocar isEnabled
                        onLog('E', "ERROR DESACTIVANDO VIRTUAL STICK: ${error.description}")
                        onResult(error.description)
                    }
                }
            }
        })
    }

    /** Llamar cuando el dron se desconecta. */
    fun reset() {
        stopMovement()
        isEnabled = false
        flight.release(NavigationMode.VIRTUAL_STICK)
    }

    private fun configure(ground: Boolean) {
        val fc = flight.flightController ?: return
        fc.rollPitchControlMode = RollPitchControlMode.VELOCITY
        fc.verticalControlMode = VerticalControlMode.VELOCITY
        fc.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        fc.rollPitchCoordinateSystem = if (ground) FlightCoordinateSystem.GROUND else FlightCoordinateSystem.BODY
        groundFrame = ground
        onLog(
            'I', "VirtualStick configurado: RollPitch=VELOCITY, Vertical=VELOCITY, " +
                    "Yaw=ANGULAR_VELOCITY, Coord=${if (ground) "GROUND" else "BODY"}"
        )
    }

    // =========================================================
    // COMANDOS
    // =========================================================

    /**
     * Envía un comando. forward/right en m/s (en BODY: adelante/derecha;
     * en GROUND: Norte/Este). Throttle 0 = mantener altura, yaw 0 = sin giro.
     */
    fun sendOnce(forward: Float, right: Float) {
        val fc = flight.flightController ?: return
        if (!isEnabled) return
        if (flight.navigationMode != NavigationMode.VIRTUAL_STICK) return

        val data = if (VELOCITY_AXES_SWAPPED) {
            FlightControlData(right, forward, 0f, 0f)
        } else {
            FlightControlData(forward, right, 0f, 0f)
        }
        fc.sendVirtualStickFlightControlData(data, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error != null) main.post { onLog('E', "VirtualStick: ${error.description}") }
            }
        })
    }

    /** Envío continuo del comando actual cada 100 ms (como virtualStickSender). */
    private fun startSender() {
        sender?.let { main.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!isMoving) return
                sendOnce(forwardCmd, rightCmd)
                main.postDelayed(this, COMMAND_INTERVAL_MS)
            }
        }
        sender = r
        main.post(r)
    }

    /** Mueve el dron a la velocidad indicada durante durationMs y luego envía 0. */
    private var moveEndRunnable: Runnable? = null

    /** Mueve el dron a la velocidad indicada durante durationMs y luego envía 0. */
    fun moveFor(forward: Float, right: Float, durationMs: Long, onDone: () -> Unit) {
        if (!isEnabled) return
        if (groundFrame) configure(ground = false)
        isMoving = true
        forwardCmd = forward
        rightCmd = right
        startSender()
        moveEndRunnable?.let { main.removeCallbacks(it) }
        val end = Runnable {
            if (!isMoving) return@Runnable
            stopMovement()
            sendOnce(0f, 0f)
            onDone()
        }
        moveEndRunnable = end
        main.postDelayed(end, durationMs)
    }

    /** Cambia la velocidad sin cortar el envío continuo (para A → B → A por tiempo). */
    fun changeCommand(forward: Float, right: Float) {
        forwardCmd = forward
        rightCmd = right
    }

    /** Detiene envíos/bucles. No manda ningún comando por sí mismo. */
    fun stopMovement() {
        isMoving = false
        forwardCmd = 0f
        rightCmd = 0f
        sender?.let { main.removeCallbacks(it) }
        gpsLoop?.let { main.removeCallbacks(it) }
        sender = null
        gpsLoop = null
        moveEndRunnable = null;
        //main.removeCallbacksAndMessages(null)
    }

    /** STOP / HOVER: corta todo y manda 0 cinco veces (como emergencyStop anterior). */
    fun hover() {
        stopMovement()
        repeat(5) { i -> main.postDelayed({ sendOnce(0f, 0f) }, i * 100L) }
        if (groundFrame) main.postDelayed({ configure(ground = false) }, 600L)
    }

    // =========================================================
    // NAVEGACIÓN GPS CON VIRTUAL STICK (coordenadas del DRON)
    // =========================================================

    data class GoToParams(
        val maxSpeed: Float = 0.5f,          // m/s
        val toleranceM: Double = 1.0,        // llegada
        val timeoutMs: Long = 30_000L,
        val kp: Double = 0.4,                // velocidad = kp * distancia (limitada)
        val divergenceM: Double = 2.0        // aborta si se aleja esto del mejor valor
    )

    /**
     * Vuela hacia (lat, lon) manteniendo la altura, corrigiendo con la posición GPS del dron.
     * Usa el sistema de coordenadas GROUND (Norte/Este).
     *
     * Seguridad: aborta (y queda en hover) si se pierde el GPS, si se supera el tiempo
     * o si la distancia al objetivo AUMENTA más de divergenceM (ej. ejes mal mapeados).
     */
    fun goTo(
        targetLat: Double,
        targetLon: Double,
        params: GoToParams = GoToParams(),
        onProgress: (distanceM: Double) -> Unit,
        onDone: (success: Boolean, message: String) -> Unit
    ) {
        if (!isEnabled) return onDone(false, "Virtual Stick no activo")
        stopMovement()
        configure(ground = true)
        isMoving = true

        val start = System.currentTimeMillis()
        var bestDistance = Double.MAX_VALUE
        var lastValidFix = start

        val finish = { ok: Boolean, msg: String ->
            stopMovement()
            repeat(5) { i -> main.postDelayed({ sendOnce(0f, 0f) }, i * 100L) }
            main.postDelayed({ configure(ground = false) }, 600L)
            onLog(if (ok) 'I' else 'W', "GOTO: $msg")
            onDone(ok, msg)
        }

        val loop = object : Runnable {
            override fun run() {
                if (!isMoving) return
                val t = telemetry()
                val now = System.currentTimeMillis()

                if (!t.hasValidPosition) {
                    sendOnce(0f, 0f)
                    if (now - lastValidFix > 2000L) return finish(false, "GPS perdido, hover")
                    main.postDelayed(this, COMMAND_INTERVAL_MS)
                    return
                }
                lastValidFix = now

                val (north, east) = GeoUtils.offsetNorthEastMeters(
                    t.latitude!!, t.longitude!!, targetLat, targetLon
                )
                val dist = hypot(north, east)
                onProgress(dist)

                if (dist <= params.toleranceM) return finish(true, "Llegada (error ${"%.2f".format(dist)} m)")
                if (now - start > params.timeoutMs) return finish(false, "Timeout (faltan ${"%.1f".format(dist)} m)")
                if (dist > bestDistance + params.divergenceM) {
                    return finish(false, "Divergencia: se aleja del objetivo (${"%.1f".format(dist)} m). Revisa VELOCITY_AXES_SWAPPED")
                }
                bestDistance = min(bestDistance, dist)

                val speed = min(params.maxSpeed.toDouble(), max(0.15, params.kp * dist))
                val vNorth = (speed * north / dist).toFloat()
                val vEast = (speed * east / dist).toFloat()
                sendOnce(vNorth, vEast)
                main.postDelayed(this, COMMAND_INTERVAL_MS)
            }
        }
        gpsLoop = loop
        main.post(loop)
    }
}
