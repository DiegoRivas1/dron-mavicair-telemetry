package com.diegorivas.drone.dji

import android.os.Handler
import android.os.Looper
import com.diegorivas.drone.data.model.FlightRoute
import dji.common.error.DJIError
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionDownloadEvent
import dji.common.mission.waypoint.WaypointMissionExecutionEvent
import dji.common.mission.waypoint.WaypointMissionFinishedAction
import dji.common.mission.waypoint.WaypointMissionFlightPathMode
import dji.common.mission.waypoint.WaypointMissionGotoWaypointMode
import dji.common.mission.waypoint.WaypointMissionHeadingMode
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.mission.waypoint.WaypointMissionUploadEvent
import dji.common.util.CommonCallbacks
import dji.sdk.mission.MissionControl
import dji.sdk.mission.waypoint.WaypointMissionOperator
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener

/**
 * Misión Waypoint del DJI MSDK 4.18 (la ejecuta el propio controlador del dron):
 *
 *   WaypointMission.Builder → loadMission() → uploadMission() → startMission() → monitorear
 *
 * Reglas:
 * - Antes de startMission() hay que DESACTIVAR Virtual Stick (lo hace MainActivity).
 * - Mientras la misión corre, el modo de navegación es WAYPOINT y Virtual Stick
 *   no envía comandos.
 * - finishedAction = NO_ACTION: al terminar queda en HOVER en el último punto;
 *   el aterrizaje lo decides tú (botón ATERRIZAR o el control remoto).
 *
 * Todos los callbacks se entregan en el HILO PRINCIPAL.
 */
class WaypointMissionManager(
    private val flight: FlightControllerManager,
    private val onLog: (Char, String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onEvent: (type: String, details: String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var listenerAdded = false

    var loadedRoute: FlightRoute? = null
        private set

    private val operator: WaypointMissionOperator?
        get() = MissionControl.getInstance()?.waypointMissionOperator

    val state: WaypointMissionState?
        get() = operator?.currentState

    val isExecuting: Boolean
        get() = state == WaypointMissionState.EXECUTING || state == WaypointMissionState.EXECUTION_PAUSED

    private val listener = object : WaypointMissionOperatorListener {
        override fun onDownloadUpdate(event: WaypointMissionDownloadEvent) {}

        override fun onUploadUpdate(event: WaypointMissionUploadEvent) {
            val p = event.progress
            main.post {
                if (event.error != null) {
                    onLog('E', "WAYPOINT upload error: ${event.error?.description}")
                    onStatus("ERROR SUBIENDO MISIÓN\n\n${event.error?.description}")
                } else if (p != null) {
                    onStatus(
                        "SUBIENDO MISIÓN\n\nPunto ${p.uploadedWaypointIndex + 1}/${p.totalWaypointCount}\n" +
                                "Estado: ${event.currentState}"
                    )
                }
                if (event.currentState == WaypointMissionState.READY_TO_EXECUTE) {
                    onStatus("MISIÓN SUBIDA\n\nEstado: READY_TO_EXECUTE\nPulsa INICIAR MISIÓN.")
                }
            }
        }

        override fun onExecutionUpdate(event: WaypointMissionExecutionEvent) {
            val p = event.progress ?: return
            main.post {
                onStatus(
                    "MISIÓN EN CURSO\n\nHacia punto ${p.targetWaypointIndex + 1}/${p.totalWaypointCount}" +
                            (if (p.isWaypointReached) " (alcanzado)" else "") +
                            "\nEstado: ${p.executeState}"
                )
            }
        }

        override fun onExecutionStart() {
            main.post {
                onLog('I', "WAYPOINT: ejecución iniciada")
                onEvent("MISSION_STARTED", loadedRoute?.name ?: "")
            }
        }

        override fun onExecutionFinish(error: DJIError?) {
            main.post {
                flight.release(NavigationMode.WAYPOINT)
                if (error == null) {
                    onLog('I', "WAYPOINT: misión finalizada")
                    onStatus("MISIÓN FINALIZADA\n\nEl dron queda en hover.\nUsa ATERRIZAR cuando quieras.")
                    onEvent("MISSION_FINISHED", loadedRoute?.name ?: "")
                } else {
                    onLog('E', "WAYPOINT: misión terminada con error: ${error.description}")
                    onStatus("MISIÓN INTERRUMPIDA\n\n${error.description}")
                    onEvent("MISSION_ERROR", error.description ?: "")
                }
            }
        }
    }

    fun attach() {
        if (listenerAdded) return
        operator?.addListener(listener)
        listenerAdded = operator != null
    }

    fun detach() {
        if (listenerAdded) operator?.removeListener(listener)
        listenerAdded = false
    }

    /** Construye la misión y la carga en el operador (todavía NO la sube al dron). */
    fun load(route: FlightRoute): String? {
        attach()
        val op = operator ?: return "WaypointMissionOperator no disponible"

        val waypoints = route.points.map { p ->
            Waypoint(p.latitude, p.longitude, p.altitudeMeters).apply {
                speed = p.speedMetersPerSecond
            }
        }
        val autoSpeed = route.points.map { it.speedMetersPerSecond }.average().toFloat().coerceIn(0.5f, 5f)

        val mission = WaypointMission.Builder()
            .autoFlightSpeed(autoSpeed)
            .maxFlightSpeed(5f)
            .setExitMissionOnRCSignalLostEnabled(false)
            .finishedAction(WaypointMissionFinishedAction.NO_ACTION)
            .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
            .headingMode(WaypointMissionHeadingMode.AUTO)
            .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
            .waypointList(waypoints)
            .waypointCount(waypoints.size)
            .build()

        val error = op.loadMission(mission)
        return if (error == null) {
            loadedRoute = route
            onLog('I', "WAYPOINT: misión cargada '${route.name}' (${waypoints.size} puntos, ${"%.1f".format(autoSpeed)} m/s)")
            null
        } else {
            onLog('E', "WAYPOINT loadMission: ${error.description}")
            error.description
        }
    }

    fun upload(onResult: (String?) -> Unit) {
        val op = operator ?: return onResult("WaypointMissionOperator no disponible")
        if (loadedRoute == null) return onResult("Primero carga una ruta")
        op.uploadMission(callback("uploadMission", onResult))
    }

    /** Requiere que Virtual Stick esté DESACTIVADO (MainActivity lo hace antes). */
    fun start(onResult: (String?) -> Unit) {
        val op = operator ?: return onResult("WaypointMissionOperator no disponible")
        if (op.currentState != WaypointMissionState.READY_TO_EXECUTE) {
            return onResult("La misión no está lista (estado ${op.currentState}). Súbela primero.")
        }
        if (!flight.acquire(NavigationMode.WAYPOINT)) {
            return onResult("Otro modo de navegación activo (${flight.navigationMode})")
        }
        op.startMission(callback("startMission") { err ->
            if (err != null) flight.release(NavigationMode.WAYPOINT)
            onResult(err)
        })
    }

    fun pause(onResult: (String?) -> Unit) {
        val op = operator ?: return onResult("WaypointMissionOperator no disponible")
        op.pauseMission(callback("pauseMission", onResult))
    }

    fun resume(onResult: (String?) -> Unit) {
        val op = operator ?: return onResult("WaypointMissionOperator no disponible")
        op.resumeMission(callback("resumeMission", onResult))
    }

    /** Detiene la misión: el dron queda en hover donde esté. */
    fun stop(onResult: (String?) -> Unit) {
        val op = operator ?: return onResult("WaypointMissionOperator no disponible")
        op.stopMission(callback("stopMission") { err ->
            flight.release(NavigationMode.WAYPOINT)
            onResult(err)
        })
    }

    private fun callback(name: String, onResult: (String?) -> Unit) =
        object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                main.post {
                    if (error == null) onLog('I', "WAYPOINT $name OK")
                    else onLog('E', "WAYPOINT $name: ${error.description}")
                    onResult(error?.description)
                }
            }
        }
}
