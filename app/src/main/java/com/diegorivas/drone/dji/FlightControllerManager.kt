package com.diegorivas.drone.dji

import android.os.Handler
import android.os.Looper
import com.diegorivas.drone.data.model.TelemetryData
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.keysdk.FlightControllerKey
import dji.keysdk.KeyManager
import dji.keysdk.callback.ActionCallback
import dji.sdk.flightcontroller.FlightController

/** Quién tiene el control del movimiento del dron en cada momento. */
enum class NavigationMode { IDLE, VIRTUAL_STICK, WAYPOINT }

/** Forma de pedir el despegue al SDK. */
enum class TakeoffMethod { SDK_CALL, KEY_ACTION }

/**
 * Despegue, aterrizaje y espera de altura. También arbitra el modo de navegación
 * para que Virtual Stick y Waypoint Mission nunca manden órdenes a la vez.
 *
 * Todos los callbacks se entregan en el HILO PRINCIPAL.
 */
class FlightControllerManager(
    private val fcProvider: () -> FlightController?,
    private val telemetry: () -> TelemetryData,
    private val onLog: (Char, String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    /** Modo de navegación activo. Sólo se cambia desde el hilo principal. */
    var navigationMode: NavigationMode = NavigationMode.IDLE
        private set

    var takeoffRunning = false
        private set
    var landingRunning = false
        private set

    private var altitudeChecker: Runnable? = null
    private var landingChecker: Runnable? = null

    val flightController: FlightController? get() = fcProvider()

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
    // DESPEGUE (misma secuencia que antes: startTakeoff + esperar altura)
    // =========================================================

    /**
     * startTakeoff() y luego espera targetAltitude. Equivale a lo que hacían
     * startAutomaticTakeoff() + waitForTakeoffAltitude() en el MainActivity anterior.
     */
    fun takeoff(
        targetAltitude: Double,
        timeoutMs: Long,
        onError: (String) -> Unit,
        onTakeoffAccepted: () -> Unit,
        onAltitudeReached: (Float?) -> Unit,
        onTimeout: (Float?) -> Unit,
        method: TakeoffMethod = TakeoffMethod.SDK_CALL
    ) {
        val fc = fcProvider()
        if (fc == null) {
            onError("FlightController no disponible")
            return
        }
        takeoffRunning = true

        val handle: (DJIError?) -> Unit = { error ->
            main.post {
                if (error != null) {
                    takeoffRunning = false
                    onLog(
                        'E',
                        "TAKEOFF ERROR [$method]: code=${error.errorCode} class=${error.javaClass.simpleName} " +
                                "description=${error.description} | ${diagnostics()}"
                    )
                    onError(error.description ?: error.toString())
                } else {
                    onLog('I', "TAKEOFF aceptado [$method]")
                    onTakeoffAccepted()
                    waitForAltitude(targetAltitude, timeoutMs, onAltitudeReached, onTimeout)
                }
            }
        }

        when (method) {
            TakeoffMethod.SDK_CALL -> fc.startTakeoff(object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(error: DJIError?) = handle(error)
            })
            // Misma orden por la vía KeyManager (camino interno distinto del SDK).
            TakeoffMethod.KEY_ACTION -> {
                val km = KeyManager.getInstance()
                if (km == null) {
                    takeoffRunning = false
                    onError("KeyManager no disponible")
                    return
                }
                km.performAction(FlightControllerKey.create(FlightControllerKey.TAKE_OFF), object : ActionCallback {
                    override fun onSuccess() = handle(null)
                    override fun onFailure(error: DJIError) = handle(error)
                })
            }
        }
    }

    private fun waitForAltitude(
        target: Double,
        timeoutMs: Long,
        onReached: (Float?) -> Unit,
        onTimeout: (Float?) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        var lastLog = 0L
        val checker = object : Runnable {
            override fun run() {
                if (!takeoffRunning) return
                val t = telemetry()
                val altitude = t.altitude
                val now = System.currentTimeMillis()
                if (now - lastLog >= 1000L) {
                    onLog('I', "TAKEOFF CHECK: altitude=$altitude flying=${t.isFlying}")
                    lastLog = now
                }
                if (t.isFlying && altitude != null && altitude >= target) {
                    onReached(altitude)
                    return
                }
                if (now - startTime >= timeoutMs) {
                    takeoffRunning = false
                    onTimeout(altitude)
                    return
                }
                main.postDelayed(this, 100L)
            }
        }
        altitudeChecker = checker
        main.post(checker)
    }

    /** Marca el despegue como terminado (después de la estabilización). */
    fun finishTakeoff() {
        takeoffRunning = false
        altitudeChecker?.let { main.removeCallbacks(it) }
    }

    // =========================================================
    // ATERRIZAJE (con confirmación automática cerca del suelo)
    // =========================================================

    fun land(onStarted: () -> Unit, onError: (String) -> Unit, onLanded: () -> Unit) {
        val fc = fcProvider()
        if (fc == null) {
            onError("FlightController no disponible")
            return
        }
        cancelTakeoffWait()
        landingRunning = true

        fc.startLanding(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                main.post {
                    if (error != null) {
                        landingRunning = false
                        onLog('E', "LANDING ERROR: ${error.description} | ${diagnostics()}")
                        onError(error.description ?: error.toString())
                        return@post
                    }
                    onStarted()
                    monitorLanding(fc, onLanded)
                }
            }
        })
    }

    private fun monitorLanding(fc: FlightController, onLanded: () -> Unit) {
        var confirmationSent = false
        val startTime = System.currentTimeMillis()
        val checker = object : Runnable {
            override fun run() {
                if (!landingRunning) return
                val needsConfirmation = try {
                    fc.state?.isLandingConfirmationNeeded == true
                } catch (_: Exception) { false }

                if (needsConfirmation && !confirmationSent) {
                    confirmationSent = true
                    onLog('I', "Aterrizaje: el dron pide confirmación (cerca del suelo) → confirmLanding()")
                    fc.confirmLanding { err ->
                        if (err != null) main.post { onLog('E', "confirmLanding: ${err.description}") }
                    }
                }

                val t = telemetry()
                if (!t.isFlying && !t.areMotorsOn) {
                    landingRunning = false
                    // Liberar el modo de navegación para que el siguiente vuelo pueda adquirirlo.
                    // No desactivamos Virtual Stick aquí porque eso lo hace MainActivity
                    // con confirmación del SDK; aquí solo soltamos el arbitraje interno.
                    if (navigationMode == NavigationMode.VIRTUAL_STICK) {
                        onLog('I', "Aterrizaje confirmado: liberando NavigationMode.VIRTUAL_STICK")
                        navigationMode = NavigationMode.IDLE
                    }
                    onLanded()
                    return
                }
                if (System.currentTimeMillis() - startTime > 120_000L) {
                    landingRunning = false
                    onLog('W', "Aterrizaje: sin confirmación de motores apagados tras 120 s")
                    return
                }
                main.postDelayed(this, 250L)
            }
        }
        landingChecker = checker
        main.post(checker)
    }

    fun cancelLanding(onDone: (String?) -> Unit) {
        val fc = fcProvider() ?: return onDone("FlightController no disponible")
        landingRunning = false
        landingChecker?.let { main.removeCallbacks(it) }
        fc.cancelLanding { err -> main.post { onDone(err?.description) } }
    }

    fun cancelTakeoffWait() {
        takeoffRunning = false
        altitudeChecker?.let { main.removeCallbacks(it) }
    }

    fun cancelAll() {
        cancelTakeoffWait()
        landingRunning = false
        landingChecker?.let { main.removeCallbacks(it) }
    }

    /** Resumen del estado del dron para diagnosticar errores de despegue/aterrizaje. */
    fun diagnostics(): String {
        val t = telemetry()
        val fcState = try { fcProvider()?.state } catch (_: Exception) { null }
        val extra = if (fcState != null) {
            "goHome=${fcState.isGoingHome} imuPreheating=${fcState.isIMUPreheating} " +
                    "ultrasonic=${fcState.isUltrasonicBeingUsed} lowBattWarn=${fcState.isLowerThanBatteryWarningThreshold}"
        } else "fcState=null"
        return "mode=${t.flightMode} flying=${t.isFlying} motors=${t.areMotorsOn} bat=${t.batteryPercent}% " +
                "sats=${t.satelliteCount} gps=${t.gpsSignalLevel} alt=${t.altitude} $extra"
    }
}
