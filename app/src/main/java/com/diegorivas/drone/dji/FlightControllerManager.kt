package com.diegorivas.drone.dji

import android.os.Handler
import android.os.Looper
import com.diegorivas.drone.data.model.TelemetryData
import com.diegorivas.drone.utils.FlightValidator
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightController

/** Quién tiene el control del movimiento del dron en cada momento. */
enum class NavigationMode { IDLE, VIRTUAL_STICK, WAYPOINT }

/** Fase del vuelo según la app (se corrige con la telemetría real del dron). */
enum class FlightPhase { ON_GROUND, TAKING_OFF, IN_AIR, LANDING }

/**
 * Despegue y aterrizaje, rehechos como una máquina de estados sencilla.
 *
 * DESPEGUE
 *   1. Comprobaciones previas (FlightValidator + estado del FlightController).
 *   2. Virtual Stick DESACTIVADO en el dron, se crea o no la app que lo está.
 *      Con Virtual Stick activo el dron ignora el mando y algunos firmwares
 *      rechazan startTakeoff/startLanding. Ahora sólo se activa para moverse.
 *   3. startTakeoff(). Si el SDK devuelve error se reintenta (MAX_TAKEOFF_ATTEMPTS)
 *      salvo que los motores ya hayan arrancado.
 *   4. Esperar a que IS_FLYING sea true y la altura llegue al objetivo
 *      (el despegue automático de DJI sube solo a ~1.2 m y queda en hover).
 *
 * ATERRIZAJE
 *   1. Cancelar cualquier espera de despegue y movimiento.
 *   2. Virtual Stick DESACTIVADO (antes no se hacía: por eso ATERRIZAR fallaba).
 *   3. startLanding() con reintentos.
 *   4. Confirmar el aterrizaje cuando el dron lo pide (cerca del suelo).
 *   5. Fin cuando IS_FLYING = false y motores apagados.
 *
 * Cada operación lleva un número de "generación": STOP, aterrizar o desconectar
 * invalida las respuestas pendientes de la operación anterior.
 *
 * Todos los callbacks se entregan en el HILO PRINCIPAL.
 */
class FlightControllerManager(
    private val fcProvider: () -> FlightController?,
    private val telemetry: () -> TelemetryData,
    private val onLog: (Char, String) -> Unit
) {
    companion object {
        private const val MAX_TAKEOFF_ATTEMPTS = 2
        private const val MAX_LANDING_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1500L
        private const val AFTER_VS_OFF_DELAY_MS = 400L
        /** Si vuela y no llega a la altura objetivo en este tiempo, se da por despegado. */
        private const val AIRBORNE_GRACE_MS = 6000L
        private const val LANDING_TIMEOUT_MS = 120_000L
    }

    private val main = Handler(Looper.getMainLooper())

    /** Modo de navegación activo. Sólo se cambia desde el hilo principal. */
    var navigationMode: NavigationMode = NavigationMode.IDLE
        private set

    var phase: FlightPhase = FlightPhase.ON_GROUND
        private set

    val takeoffRunning: Boolean get() = phase == FlightPhase.TAKING_OFF
    val landingRunning: Boolean get() = phase == FlightPhase.LANDING

    /**
     * Se llama cuando este gestor apaga Virtual Stick en el dron por su cuenta
     * (antes de despegar/aterrizar), para que VirtualStickController lo sepa.
     */
    var onVirtualStickForcedOff: (() -> Unit)? = null

    private var generation = 0
    private var pending: Runnable? = null

    val flightController: FlightController? get() = fcProvider()

    // =========================================================
    // ARBITRAJE Virtual Stick / Waypoint
    // =========================================================

    /** Pide el modo; devuelve false si otro modo está activo. */
    fun acquire(mode: NavigationMode): Boolean {
        if (navigationMode == mode) return true
        if (navigationMode != NavigationMode.IDLE) {
            onLog('W', "Modo $mode bloqueado: ya está activo $navigationMode")
            return false
        }
        navigationMode = mode
        onLog('I', "Modo de navegación → $mode")
        return true
    }

    fun release(mode: NavigationMode) {
        if (navigationMode == mode) {
            navigationMode = NavigationMode.IDLE
            onLog('I', "Modo de navegación → IDLE")
        }
    }

    // =========================================================
    // ESTADO
    // =========================================================

    /**
     * Llamar con cada telemetría. Corrige la fase si el piloto despega o aterriza
     * con el mando, o si el dron cambia de estado sin que la app lo pidiera.
     */
    fun syncWithTelemetry(t: TelemetryData) {
        when (phase) {
            FlightPhase.ON_GROUND -> if (t.isFlying) {
                phase = FlightPhase.IN_AIR
                onLog('I', "Fase → IN_AIR (despegue detectado por telemetría)")
            }
            FlightPhase.IN_AIR -> if (!t.isFlying && !t.areMotorsOn) {
                phase = FlightPhase.ON_GROUND
                onLog('I', "Fase → ON_GROUND (aterrizaje detectado por telemetría)")
            }
            else -> Unit // TAKING_OFF / LANDING los cierran sus propios bucles
        }
    }

    /** Cancela la operación en curso (no manda órdenes al dron). */
    fun cancelAll() {
        generation++
        pending?.let { main.removeCallbacks(it) }
        pending = null
        val t = telemetry()
        phase = if (t.isFlying || t.areMotorsOn) FlightPhase.IN_AIR else FlightPhase.ON_GROUND
    }

    /** Al desconectar el dron. */
    fun reset() {
        cancelAll()
        phase = FlightPhase.ON_GROUND
        navigationMode = NavigationMode.IDLE
    }

    private fun schedule(gen: Int, delayMs: Long, block: () -> Unit) {
        val r = Runnable { if (gen == generation) block() }
        pending = r
        main.postDelayed(r, delayMs)
    }

    // =========================================================
    // VIRTUAL STICK: apagado forzado
    // =========================================================

    /**
     * Desactiva Virtual Stick EN EL DRON aunque la app crea que ya está desactivado
     * (puede haber quedado activo de una sesión anterior o tras un cierre de la app).
     * El error se registra pero no detiene la secuencia.
     */
    fun forceVirtualStickOff(onDone: () -> Unit = {}) {
        val fc = fcProvider()
        if (fc == null) {
            onDone()
            return
        }
        fc.setVirtualStickModeEnabled(false, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                main.post {
                    if (error != null) {
                        onLog('W', "Virtual Stick OFF: ${error.description} (se continúa)")
                    } else {
                        onLog('I', "Virtual Stick OFF confirmado por el dron")
                    }
                    if (navigationMode == NavigationMode.VIRTUAL_STICK) release(NavigationMode.VIRTUAL_STICK)
                    onVirtualStickForcedOff?.invoke()
                    onDone()
                }
            }
        })
    }

    // =========================================================
    // DESPEGUE
    // =========================================================

    /** Motivo por el que no se puede despegar, o null si se puede. */
    fun takeoffBlocker(): String? {
        if (phase != FlightPhase.ON_GROUND) return "Hay una operación de vuelo en curso ($phase)."
        val fc = fcProvider() ?: return "FlightController no disponible. ¿Dron encendido y conectado?"
        val t = telemetry()
        FlightValidator.canTakeoff(t)?.let { return it }
        if (navigationMode == NavigationMode.WAYPOINT) return "Hay una misión Waypoint activa."
        val st = try { fc.state } catch (_: Throwable) { null }
        if (st != null) {
            if (st.isIMUPreheating) return "La IMU se está calentando. Espera 1-2 minutos."
            if (st.isGoingHome) return "El dron está en Return-To-Home."
        }
        val compassError = try { fc.compass?.hasError() == true } catch (_: Throwable) { false }
        if (compassError) return "Error de brújula. Calíbrala en DJI GO 4 y aléjate de metales."
        return null
    }

    /**
     * @param onProgress   texto de estado para la pantalla
     * @param onAirborne   el dron está en el aire (altitud alcanzada o vuela > AIRBORNE_GRACE_MS)
     * @param onError      no despegó; el texto explica qué hacer
     */
    fun takeoff(
        targetAltitude: Double,
        timeoutMs: Long,
        onProgress: (String) -> Unit,
        onAirborne: (Float?) -> Unit,
        onError: (String) -> Unit
    ) {
        takeoffBlocker()?.let { return onError(it) }
        val gen = ++generation
        phase = FlightPhase.TAKING_OFF
        onLog('I', "TAKEOFF: inicio | ${diagnostics()}")
        onProgress("Preparando despegue...\nLiberando Virtual Stick")

        forceVirtualStickOff {
            if (gen != generation) return@forceVirtualStickOff
            schedule(gen, AFTER_VS_OFF_DELAY_MS) {
                sendTakeoff(gen, 1, targetAltitude, timeoutMs, onProgress, onAirborne, onError)
            }
        }
    }

    private fun sendTakeoff(
        gen: Int,
        attempt: Int,
        targetAltitude: Double,
        timeoutMs: Long,
        onProgress: (String) -> Unit,
        onAirborne: (Float?) -> Unit,
        onError: (String) -> Unit
    ) {
        val fc = fcProvider()
        if (fc == null) {
            phase = FlightPhase.ON_GROUND
            return onError("FlightController no disponible")
        }
        onLog('I', "TAKEOFF intento $attempt/$MAX_TAKEOFF_ATTEMPTS: startTakeoff()")
        onProgress("Despegando (intento $attempt)...")

        fc.startTakeoff(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                main.post {
                    if (gen != generation) return@post
                    val t = telemetry()
                    if (error == null) {
                        onLog('I', "TAKEOFF aceptado (intento $attempt)")
                        waitAirborne(gen, targetAltitude, timeoutMs, onProgress, onAirborne, onError)
                        return@post
                    }
                    onLog(
                        'E', "TAKEOFF ERROR intento $attempt: code=${error.errorCode} " +
                                "description=${error.description} | ${diagnostics()}"
                    )
                    when {
                        // A veces el SDK responde con error pero el dron sí arrancó.
                        t.areMotorsOn || t.isFlying -> {
                            onLog('W', "TAKEOFF: error del SDK pero los motores están encendidos; se espera la altura")
                            waitAirborne(gen, targetAltitude, timeoutMs, onProgress, onAirborne, onError)
                        }
                        attempt < MAX_TAKEOFF_ATTEMPTS -> {
                            onProgress("Despegue rechazado: ${error.description}\nReintentando...")
                            schedule(gen, RETRY_DELAY_MS) {
                                sendTakeoff(gen, attempt + 1, targetAltitude, timeoutMs, onProgress, onAirborne, onError)
                            }
                        }
                        else -> {
                            phase = FlightPhase.ON_GROUND
                            onError(explainTakeoffError(error))
                        }
                    }
                }
            }
        })
    }

    private fun waitAirborne(
        gen: Int,
        target: Double,
        timeoutMs: Long,
        onProgress: (String) -> Unit,
        onAirborne: (Float?) -> Unit,
        onError: (String) -> Unit
    ) {
        val start = System.currentTimeMillis()
        var flyingSince = 0L
        var lastLog = 0L
        onProgress("Despegando...\nEsperando ~${"%.1f".format(target)} m")

        val checker = object : Runnable {
            override fun run() {
                if (gen != generation) return
                val t = telemetry()
                val now = System.currentTimeMillis()
                val alt = t.altitude
                if (t.isFlying && flyingSince == 0L) flyingSince = now
                if (now - lastLog >= 1000L) {
                    onLog('I', "TAKEOFF CHECK: alt=$alt flying=${t.isFlying} motors=${t.areMotorsOn}")
                    lastLog = now
                }
                val reached = t.isFlying && alt != null && alt >= target
                val graceOver = flyingSince != 0L && now - flyingSince >= AIRBORNE_GRACE_MS
                if (reached || graceOver) {
                    phase = FlightPhase.IN_AIR
                    if (!reached) onLog('W', "TAKEOFF: vuela pero alt=$alt < $target; se da por despegado")
                    onAirborne(alt)
                    return
                }
                if (now - start >= timeoutMs) {
                    if (t.isFlying) {
                        phase = FlightPhase.IN_AIR
                        onAirborne(alt)
                    } else {
                        phase = if (t.areMotorsOn) FlightPhase.IN_AIR else FlightPhase.ON_GROUND
                        onError(
                            "El dron aceptó la orden pero no se elevó en ${timeoutMs / 1000} s " +
                                    "(motores=${t.areMotorsOn}).\nRevisa hélices, suelo plano y avisos en DJI GO 4."
                        )
                    }
                    return
                }
                pending = this
                main.postDelayed(this, 100L)
            }
        }
        pending = checker
        main.post(checker)
    }

    private fun explainTakeoffError(error: DJIError): String {
        val d = error.description ?: error.toString()
        val hint = when {
            d.contains("mode", ignoreCase = true) ->
                "Pon el interruptor del mando en P."
            d.contains("timeout", ignoreCase = true) || d.contains("SDK error", ignoreCase = true) ->
                "El dron no aceptó la orden (rechazo genérico del SDK).\n" +
                        "Abre DJI GO 4 en el mismo sitio y mira los avisos en rojo/amarillo " +
                        "(brújula, IMU, zona GEO, firmware). Si DJI GO 4 tampoco despega, el bloqueo es del dron."
            else -> "Revisa los avisos del dron en DJI GO 4."
        }
        return "$d\n\n$hint"
    }

    // =========================================================
    // ATERRIZAJE
    // =========================================================

    fun land(
        onProgress: (String) -> Unit,
        onLanded: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (phase == FlightPhase.LANDING) return
        if (fcProvider() == null) return onError("FlightController no disponible")
        val gen = ++generation           // cancela la espera de despegue si la había
        pending?.let { main.removeCallbacks(it) }
        phase = FlightPhase.LANDING
        onLog('I', "LANDING: inicio | ${diagnostics()}")
        onProgress("Aterrizando...\nLiberando Virtual Stick")

        forceVirtualStickOff {
            if (gen != generation) return@forceVirtualStickOff
            schedule(gen, AFTER_VS_OFF_DELAY_MS) { sendLanding(gen, 1, onProgress, onLanded, onError) }
        }
    }

    private fun sendLanding(
        gen: Int,
        attempt: Int,
        onProgress: (String) -> Unit,
        onLanded: () -> Unit,
        onError: (String) -> Unit
    ) {
        val fc = fcProvider()
        if (fc == null) {
            phase = FlightPhase.IN_AIR
            return onError("FlightController no disponible")
        }
        onLog('I', "LANDING intento $attempt/$MAX_LANDING_ATTEMPTS: startLanding()")
        fc.startLanding(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                main.post {
                    if (gen != generation) return@post
                    val t = telemetry()
                    if (error == null) {
                        onLog('I', "LANDING aceptado (intento $attempt)")
                        onProgress("ATERRIZANDO...\n\nEl dron baja solo. Con el mando puedes corregir.")
                        monitorLanding(gen, fc, onLanded, onError)
                        return@post
                    }
                    onLog('E', "LANDING ERROR intento $attempt: code=${error.errorCode} description=${error.description} | ${diagnostics()}")
                    when {
                        !t.isFlying && !t.areMotorsOn -> {
                            phase = FlightPhase.ON_GROUND
                            onLanded()
                        }
                        attempt < MAX_LANDING_ATTEMPTS -> {
                            onProgress("Aterrizaje rechazado: ${error.description}\nReintentando...")
                            schedule(gen, RETRY_DELAY_MS) { sendLanding(gen, attempt + 1, onProgress, onLanded, onError) }
                        }
                        else -> {
                            phase = FlightPhase.IN_AIR
                            onError(
                                "${error.description}\n\nATERRIZA CON EL MANDO:\n" +
                                        "stick izquierdo abajo hasta que paren los motores."
                            )
                        }
                    }
                }
            }
        })
    }

    private fun monitorLanding(gen: Int, fc: FlightController, onLanded: () -> Unit, onError: (String) -> Unit) {
        var confirmationSent = false
        val start = System.currentTimeMillis()
        val checker = object : Runnable {
            override fun run() {
                if (gen != generation) return
                val needsConfirmation = try {
                    fc.state?.isLandingConfirmationNeeded == true
                } catch (_: Throwable) { false }

                if (needsConfirmation && !confirmationSent) {
                    confirmationSent = true
                    onLog('I', "Aterrizaje: el dron pide confirmación (cerca del suelo) → confirmLanding()")
                    fc.confirmLanding { err ->
                        main.post {
                            if (err != null) {
                                onLog('E', "confirmLanding: ${err.description}")
                                confirmationSent = false // reintentar en la próxima vuelta
                            }
                        }
                    }
                }

                val t = telemetry()
                if (!t.isFlying && !t.areMotorsOn) {
                    phase = FlightPhase.ON_GROUND
                    onLog('I', "LANDING completado: motores apagados")
                    onLanded()
                    return
                }
                if (System.currentTimeMillis() - start > LANDING_TIMEOUT_MS) {
                    phase = if (t.isFlying || t.areMotorsOn) FlightPhase.IN_AIR else FlightPhase.ON_GROUND
                    onError("No se confirmó el aterrizaje en ${LANDING_TIMEOUT_MS / 1000} s. Termina con el mando.")
                    return
                }
                pending = this
                main.postDelayed(this, 250L)
            }
        }
        pending = checker
        main.post(checker)
    }

    // =========================================================
    // DIAGNÓSTICO
    // =========================================================

    /** Resumen del estado del dron para diagnosticar errores de despegue/aterrizaje. */
    fun diagnostics(): String {
        val t = telemetry()
        val fc = try { fcProvider() } catch (_: Throwable) { null }
        val fcState = try { fc?.state } catch (_: Throwable) { null }
        val extra = if (fcState != null) {
            "goHome=${fcState.isGoingHome} imuPreheating=${fcState.isIMUPreheating} " +
                    "ultrasonic=${fcState.isUltrasonicBeingUsed} lowBattWarn=${fcState.isLowerThanBatteryWarningThreshold} " +
                    "landingConfirm=${fcState.isLandingConfirmationNeeded}"
        } else "fcState=null"
        val compass = try { fc?.compass?.hasError()?.toString() ?: "?" } catch (_: Throwable) { "?" }
        return "phase=$phase nav=$navigationMode mode=${t.flightMode} flying=${t.isFlying} motors=${t.areMotorsOn} " +
                "bat=${t.batteryPercent}% sats=${t.satelliteCount} gps=${t.gpsSignalLevel} alt=${t.altitude} " +
                "compassError=$compass $extra"
    }
}
