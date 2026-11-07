package com.diegorivas.drone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.TextureView
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.diegorivas.drone.data.model.FlightRoute
import com.diegorivas.drone.data.model.TelemetryData
import com.diegorivas.drone.data.repository.FlightRepository
import com.diegorivas.drone.data.repository.RouteRepository
import com.diegorivas.drone.dji.DjiConnectionManager
import com.diegorivas.drone.dji.FlightControllerManager
import com.diegorivas.drone.dji.NavigationMode
import com.diegorivas.drone.dji.TelemetryManager
import com.diegorivas.drone.dji.VideoManager
import com.diegorivas.drone.dji.VirtualStickController
import com.diegorivas.drone.dji.WaypointMissionManager
import com.diegorivas.drone.network.ApiClient
import com.diegorivas.drone.network.TelemetryApi
import com.diegorivas.drone.network.VideoStreamClient
import com.diegorivas.drone.utils.FlightValidator
import dji.common.mission.waypoint.WaypointMissionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dji.sdk.sdkmanager.DJISDKManager
/**
 * Coordinador de la interfaz. La lógica de vuelo vive en:
 *  - dji/DjiConnectionManager    registro y conexión
 *  - dji/TelemetryManager        GPS, altitud, velocidad, heading, batería
 *  - dji/FlightControllerManager despegue, aterrizaje, modo de navegación
 *  - dji/VirtualStickController  movimiento por tiempo y por GPS
 *  - dji/WaypointMissionManager  misiones GPS de DJI
 *  - dji/VideoManager            video en vivo + grabación + envío a PC
 *  - network (ApiClient, ...)    comunicación con FastAPI en la PC
 */
class MainActivity : AppCompatActivity(), DjiConnectionManager.Listener {

    companion object {
        private const val TAG = "MavicAirTelemetry"

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.READ_PHONE_STATE
        )

        private const val REQUEST_CODE_PERMISSIONS = 101
        private const val MAX_LOG_CHARS = 8000

        // Mismos valores que la versión anterior
        private const val TEST_SPEED = 0.25f
        private const val LEG_DURATION_MS = 4000L
        private const val TAKEOFF_TARGET_ALTITUDE = 0.9
        private const val TAKEOFF_TIMEOUT_MS = 15000L
        private const val TAKEOFF_STABILIZATION_MS = 2000L
        private const val FORWARD_TEST_DURATION_MS = 1000L

        // Navegación GPS con Virtual Stick (botón 7)
        private const val GPS_TEST_DISTANCE_M = 3.0
        // Waypoint de prueba
        private const val WP_TEST_DISTANCE_M = 5.0
        private const val WP_TEST_ALTITUDE_M = 3.0f
        private const val WP_TEST_SPEED_MPS = 1.0f
    }

    // ---------- Vistas ----------
    private lateinit var statusText: TextView
    private lateinit var telemetryText: TextView
    private lateinit var videoSurface: TextureView
    private lateinit var virtualStickText: TextView
    private lateinit var errorLogText: TextView
    private lateinit var errorLogScroll: ScrollView

    private lateinit var enableVirtualStickButton: Button
    private lateinit var takeoffButton: Button
    private lateinit var autoTestButton: Button
    private lateinit var testForwardButton: Button
    private lateinit var returnButton: Button
    private lateinit var testOneMeterButton: Button
    private lateinit var gpsOutAndBackButton: Button
    private lateinit var emergencyStopButton: Button
    private lateinit var landingButton: Button
    private lateinit var recButton: Button
    private lateinit var serverButton: Button
    private lateinit var wpLoadTestButton: Button
    private lateinit var wpLoadServerButton: Button
    private lateinit var wpUploadButton: Button
    private lateinit var wpStartButton: Button
    private lateinit var wpStopButton: Button

    // ---------- Módulos ----------
    private lateinit var apiClient: ApiClient
    private lateinit var telemetryApi: TelemetryApi
    private lateinit var videoStreamClient: VideoStreamClient
    private lateinit var flightRepository: FlightRepository
    private lateinit var routeRepository: RouteRepository
    private lateinit var connection: DjiConnectionManager
    private lateinit var telemetryManager: TelemetryManager
    private lateinit var flight: FlightControllerManager
    private lateinit var virtualStick: VirtualStickController
    private lateinit var waypoints: WaypointMissionManager
    private lateinit var video: VideoManager

    /** Handler para la UI y log. */
    private val uiHandler = Handler(Looper.getMainLooper())
    /** Handler sólo para secuencias de vuelo (STOP lo vacía sin tocar el log). */
    private val flightHandler = Handler(Looper.getMainLooper())
    private val logBuffer = StringBuilder()

    private var forwardTestCompleted = false
    private var stabilizing = false
    private var gpsNavRunning = false
    private var djiStatus = "Inicializando..."
    private var serverOk = false

    private val t: TelemetryData get() = telemetryManager.current

    // =========================================================
    // CICLO DE VIDA
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        createModules()
        bindButtons()

        videoSurface.surfaceTextureListener = video
        telemetryApi.start()
        refreshButtons()
        updateStatus()

        if (hasAllPermissions()) {
            connection.register()
        } else {
            requestMissingPermissions()
        }
        logI("App iniciada. Vuelo: ${flightRepository.session.id}")
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        telemetryText = findViewById(R.id.telemetryText)
        videoSurface = findViewById(R.id.videoSurface)
        virtualStickText = findViewById(R.id.virtualStickText)
        errorLogText = findViewById(R.id.errorLogText)
        errorLogScroll = findViewById(R.id.errorLogScroll)

        enableVirtualStickButton = findViewById(R.id.enableVirtualStickButton)
        takeoffButton = findViewById(R.id.takeoffButton)
        autoTestButton = findViewById(R.id.autoTestButton)
        testForwardButton = findViewById(R.id.testForwardButton)
        returnButton = findViewById(R.id.returnButton)
        testOneMeterButton = findViewById(R.id.testOneMeterButton)
        gpsOutAndBackButton = findViewById(R.id.gpsOutAndBackButton)
        emergencyStopButton = findViewById(R.id.emergencyStopButton)
        landingButton = findViewById(R.id.landingButton)
        recButton = findViewById(R.id.recButton)
        serverButton = findViewById(R.id.serverButton)
        wpLoadTestButton = findViewById(R.id.wpLoadTestButton)
        wpLoadServerButton = findViewById(R.id.wpLoadServerButton)
        wpUploadButton = findViewById(R.id.wpUploadButton)
        wpStartButton = findViewById(R.id.wpStartButton)
        wpStopButton = findViewById(R.id.wpStopButton)
    }

    private fun createModules() {
        apiClient = ApiClient(this)
        telemetryApi = TelemetryApi(apiClient) { ok ->
            uiHandler.post {
                serverOk = ok
                updateStatus()
                logI("Servidor PC: ${if (ok) "CONECTADO" else "SIN CONEXIÓN"} (${apiClient.baseUrl})")
            }
        }
        videoStreamClient = VideoStreamClient(apiClient) { msg -> uiHandler.post { logI(msg) } }
        flightRepository = FlightRepository(this, telemetryApi)
        routeRepository = RouteRepository(telemetryApi)

        connection = DjiConnectionManager(this, this)
        telemetryManager = TelemetryManager(
            onUpdate = { data -> onTelemetry(data) },
            onLog = { level, msg -> log(level, msg) }
        )
        flight = FlightControllerManager(
            fcProvider = { connection.flightController },
            telemetry = { telemetryManager.current },
            onLog = { level, msg -> log(level, msg) }
        )
        virtualStick = VirtualStickController(
            flight = flight,
            telemetry = { telemetryManager.current },
            onLog = { level, msg -> log(level, msg) }
        )
        waypoints = WaypointMissionManager(
            flight = flight,
            onLog = { level, msg -> log(level, msg) },
            onStatus = { msg -> showStatus(msg); refreshButtons() },
            onEvent = { type, details -> event(type, details) }
        )
        video = VideoManager(this, videoStreamClient)
        serverButton.text = "PC: ${apiClient.baseUrl}"
    }

    private fun bindButtons() {
        enableVirtualStickButton.setOnClickListener { toggleVirtualStick() }
        takeoffButton.setOnClickListener { startAutomaticTakeoff() }
        autoTestButton.setOnClickListener { startAutomaticTest() }
        testForwardButton.setOnClickListener { startShortForwardTest() }
        returnButton.setOnClickListener { returnLastForwardMovement() }
        testOneMeterButton.setOnClickListener { startOneMeterTest() }
        gpsOutAndBackButton.setOnClickListener { startGpsOutAndBack() }
        emergencyStopButton.setOnClickListener { emergencyStop() }
        landingButton.setOnClickListener { startLanding() }
        recButton.setOnClickListener { toggleRecording() }
        serverButton.setOnClickListener { showServerDialog() }
        wpLoadTestButton.setOnClickListener { loadTestWaypointRoute() }
        wpLoadServerButton.setOnClickListener { loadServerWaypointRoute() }
        wpUploadButton.setOnClickListener { uploadWaypointMission() }
        wpStartButton.setOnClickListener { startWaypointMission() }
        wpStopButton.setOnClickListener { stopWaypointMission() }
    }

    override fun onDestroy() {
        logI("onDestroy: cerrando app")

        // 1) Cancelar operaciones de vuelo en curso
        flightHandler.removeCallbacksAndMessages(null)
        flight.cancelAll()
        virtualStick.stopMovement()

        // 2) Intentar liberar Virtual Stick con confirmación (no bloqueante)
        if (virtualStick.isEnabled) {
            virtualStick.sendOnce(0f, 0f)
            virtualStick.disable { err ->
                if (err != null) logW("onDestroy: no se pudo liberar VS: $err")
                else logI("onDestroy: VS liberado")
            }
        }

        // 3) Cortar la conexión con el producto
        try {
            dji.sdk.sdkmanager.DJISDKManager.getInstance().stopConnectionToProduct()
        } catch (_: Exception) { }

        // 4) Liberar el resto de módulos
        video.release()
        waypoints.detach()
        telemetryManager.stop()
        event("APP_CLOSED", "")
        telemetryApi.shutdown()
        videoStreamClient.shutdown()
        flightRepository.close()
        Log.i(TAG, "onDestroy: recursos liberados")
        super.onDestroy()
    }

    // =========================================================
    // LOG UNIFICADO: pantalla + Logcat + archivo
    // =========================================================

    private fun log(level: Char, message: String) {
        when (level) {
            'E' -> Log.e(TAG, message)
            'W' -> Log.w(TAG, message)
            'I' -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$ts [$level] $message\n"

        uiHandler.post {
            logBuffer.append(line)
            if (logBuffer.length > MAX_LOG_CHARS) {
                logBuffer.delete(0, logBuffer.length - MAX_LOG_CHARS)
            }
            errorLogText.text = logBuffer.toString()
            errorLogScroll.post { errorLogScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        if (level == 'E' || level == 'W') {
            try {
                File(getExternalFilesDir(null), "dji_errors.log").appendText(line)
            } catch (_: Exception) { }
        }
    }

    private fun logE(message: String) = log('E', message)
    private fun logW(message: String) = log('W', message)
    private fun logI(message: String) = log('I', message)

    /** Evento de vuelo: CSV local + servidor PC. */
    private fun event(type: String, details: String) {
        flightRepository.event(type, details, telemetryManager.current)
    }

    // =========================================================
    // PERMISOS
    // =========================================================

    private fun hasAllPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestMissingPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (hasAllPermissions()) {
                connection.register()
            } else {
                Toast.makeText(this, "Se necesitan todos los permisos para conectar con el dron", Toast.LENGTH_LONG).show()
                logE("Permisos incompletos: no se puede registrar DJI")
            }
        }
    }

    // =========================================================
    // CONEXIÓN DJI (DjiConnectionManager.Listener)
    // =========================================================

    override fun onRegistrationResult(success: Boolean, message: String) {
        djiStatus = message
        updateStatus()
        if (success) logI(message) else logE(message)
    }

    override fun onProductConnected(model: String?, flightControllerAvailable: Boolean) {
        djiStatus = "DRONE CONNECTED: $model\nFlightController: ${if (flightControllerAvailable) "OK" else "ESPERANDO..."}"
        updateStatus()
        logI("DRONE CONNECTED: $model, FlightController=$flightControllerAvailable")
        flightRepository.setDroneModel(model)
        event("DRONE_CONNECTED", model ?: "")
        telemetryManager.start()
        waypoints.attach()
        video.startVideoFeed()
        refreshButtons()
    }

    override fun onProductDisconnected() {
        djiStatus = "PRODUCT DISCONNECTED\nFlightController: NO DISPONIBLE"
        updateStatus()
        flightHandler.removeCallbacksAndMessages(null)
        flight.cancelAll()
        virtualStick.reset()
        flight.release(NavigationMode.WAYPOINT)
        gpsNavRunning = false
        stabilizing = false
        showStatus("DRON DESCONECTADO")
        logW("Producto DJI desconectado")
        event("DRONE_DISCONNECTED", "")
        refreshButtons()
    }

    override fun onFlightControllerChanged(available: Boolean) {
        djiStatus = "DRONE CONNECTED\nFlightController: ${if (available) "OK" else "NO DISPONIBLE"}"
        updateStatus()
        logI("FlightController component changed: $available")
        refreshButtons()
    }

    override fun onLog(level: Char, message: String) = log(level, message)

    private fun updateStatus() {
        statusText.text = "$djiStatus\nPC: ${if (serverOk) "CONECTADA" else "sin conexión"} (${apiClient.baseUrl})"
    }

    // =========================================================
    // TELEMETRÍA
    // =========================================================

    private var lastButtonsRefresh = 0L

    private fun onTelemetry(data: TelemetryData) {
        flightRepository.onTelemetry(data)

        val bat = data.batteryPercent?.let { "$it%" } ?: "--"
        val lat = if (data.hasValidPosition) "%.6f".format(data.latitude) else "--"
        val lon = if (data.hasValidPosition) "%.6f".format(data.longitude) else "--"
        val alt = data.altitude?.let { "%.1f m".format(it) } ?: "--"
        val speed = data.horizontalSpeed?.let { "%.1f m/s".format(it) } ?: "--"
        val head = data.heading?.let { "%.0f°".format(it) } ?: "--"
        val sats = data.satelliteCount?.toString() ?: "--"
        val gps = data.gpsSignalLevel?.toString() ?: "--"

        telemetryText.text =
            "BAT: $bat\n" +
                    "LAT: $lat  LON: $lon\n" +
                    "ALT: $alt\n" +
                    "VEL: $speed  HDG: $head\n" +
                    "SAT: $sats  GPS: $gps/5\n" +
                    "MODO: ${data.flightMode ?: "--"}  ${if (data.isFlying) "VOLANDO" else "EN TIERRA"}"

        // Los botones dependen de isFlying; refrescar como mucho 4 veces por segundo.
        val now = System.currentTimeMillis()
        if (now - lastButtonsRefresh > 250L) {
            lastButtonsRefresh = now
            refreshButtons()
        }
    }

    // =========================================================
    // ESTADO DE BOTONES (un único lugar)
    // =========================================================

    private val busy: Boolean
        get() = flight.takeoffRunning || stabilizing || virtualStick.isMoving ||
                gpsNavRunning || flight.landingRunning || waypoints.isExecuting

    private fun refreshButtons() {
        val vs = virtualStick.isEnabled
        val flying = t.isFlying
        val wpActive = waypoints.isExecuting || flight.navigationMode == NavigationMode.WAYPOINT

        enableVirtualStickButton.isEnabled = !wpActive && !busy
        enableVirtualStickButton.text = if (vs) "CONTROL ACTIVO (pulsar = desactivar)" else "1. ACTIVAR CONTROL"

        takeoffButton.isEnabled = vs && !flying && !busy
        autoTestButton.isEnabled = vs && !flying && !busy
        testForwardButton.isEnabled = vs && flying && !busy
        testOneMeterButton.isEnabled = vs && flying && !busy
        returnButton.isEnabled = vs && flying && !busy && forwardTestCompleted
        gpsOutAndBackButton.isEnabled = vs && flying && !busy

        landingButton.isEnabled = (flying || t.areMotorsOn) && !flight.landingRunning

        val state = waypoints.state
        wpLoadTestButton.isEnabled = !wpActive
        wpLoadServerButton.isEnabled = !wpActive
        wpUploadButton.isEnabled = !wpActive && waypoints.loadedRoute != null
        wpStartButton.isEnabled = !wpActive && !busy && state == WaypointMissionState.READY_TO_EXECUTE
        wpStopButton.isEnabled = wpActive
    }

    private fun showStatus(message: String) {
        uiHandler.post { virtualStickText.text = "ESTADO\n\n$message" }
    }

    // =========================================================
    // VIRTUAL STICK: activar / desactivar
    // =========================================================

    private fun toggleVirtualStick() {
        if (virtualStick.isEnabled) {
            virtualStick.disable { err ->
                showStatus(if (err == null) "VIRTUAL STICK: DESACTIVADO\n\nEl control vuelve al mando." else "ERROR DESACTIVANDO:\n$err")
                refreshButtons()
            }
            return
        }
        if (connection.flightController == null) {
            showStatus("ERROR\nFlightController no disponible")
            logE("enableVirtualStick: FlightController no disponible")
            return
        }
        showStatus("Activando Virtual Stick...")
        virtualStick.enable { err ->
            if (err == null) {
                showStatus(
                    "VIRTUAL STICK: ACTIVO\n\n" +
                            "El dron NO despega al activar control.\n" +
                            "Usa DESPEGAR → 1 m o PRUEBA AUTOMÁTICA."
                )
            } else {
                showStatus("ERROR ACTIVANDO:\n$err")
            }
            refreshButtons()
        }
    }

    // =========================================================
    // DESPEGUE AUTOMÁTICO (misma secuencia que la versión que funcionaba)
    // =========================================================

    private fun startAutomaticTakeoff() = takeoffSequence(thenForward = false)

    private fun startAutomaticTest() = takeoffSequence(thenForward = true)

    private fun takeoffSequence(thenForward: Boolean) {
        if (busy) {
            Toast.makeText(this, "Ya hay una prueba ejecutándose", Toast.LENGTH_SHORT).show()
            return
        }
        if (!virtualStick.isEnabled) {
            showStatus("Primero activa Virtual Stick")
            return
        }
        if (t.isFlying) {
            showStatus("EL DRON YA ESTÁ VOLANDO\n\nNo se ejecutará otro despegue.")
            return
        }
        FlightValidator.canTakeoff(t)?.let {
            showStatus("DESPEGUE BLOQUEADO\n\n$it")
            logW("TAKEOFF bloqueado: $it")
            return
        }

        forwardTestCompleted = false
        val eventName = if (thenForward) "AUTO_TEST_START" else "TAKEOFF_START"
        event(eventName, if (thenForward) "Takeoff -> 1m -> forward 1s -> stop" else "Despegue automático solicitado")
        logI("$eventName solicitado | ${flight.diagnostics()}")
        showStatus(
            if (thenForward) "PRUEBA AUTOMÁTICA\n\n1. Despegue → 1 m\n2. Estabilización\n3. Avance 1 s\n4. STOP"
            else "DESPEGUE AUTOMÁTICO\n\nSubiendo aproximadamente a 1 m..."
        )

        flight.takeoff(
            targetAltitude = TAKEOFF_TARGET_ALTITUDE,
            timeoutMs = TAKEOFF_TIMEOUT_MS,
            onError = { err ->
                showStatus("ERROR DE DESPEGUE\n\n$err\n\n${flight.diagnostics()}")
                event("TAKEOFF_ERROR", err)
                refreshButtons()
            },
            onTakeoffAccepted = {
                showStatus("DESPEGANDO...\n\nEsperando aproximadamente 1 m")
                refreshButtons()
            },
            onAltitudeReached = { alt ->
                showStatus("ALTURA ALCANZADA\n\nAltitud: ${alt?.let { "%.2f m".format(it) } ?: "--"}\n\nEstabilizando...")
                logI("TAKEOFF ALTURA ALCANZADA: $alt")
                event("TAKEOFF_ALTITUDE_REACHED", "Altura objetivo alcanzada")
                stabilizeThen(thenForward)
            },
            onTimeout = { alt ->
                virtualStick.sendOnce(0f, 0f)
                showStatus("TIMEOUT DESPEGUE\n\nNo se confirmó altura de 1 m.\nComando horizontal = 0")
                logE("TAKEOFF TIMEOUT: no se alcanzó $TAKEOFF_TARGET_ALTITUDE m en $TAKEOFF_TIMEOUT_MS ms (alt=$alt)")
                event("TAKEOFF_TIMEOUT", "No se alcanzó la altura objetivo")
                refreshButtons()
            }
        )
        refreshButtons()
    }

    private fun stabilizeThen(thenForward: Boolean) {
        stabilizing = true
        flightHandler.postDelayed({
            if (!stabilizing) return@postDelayed
            stabilizing = false
            flight.finishTakeoff()
            virtualStick.sendOnce(0f, 0f)
            event("TAKEOFF_STABILIZED", "Dron estabilizado aproximadamente a 1 m")
            logI("TAKEOFF ESTABILIZADO")
            if (thenForward) {
                showStatus("DESPEGUE COMPLETADO\n\nAltura aproximada: 1 m\nIniciando prueba adelante...")
                refreshButtons()
                startShortForwardTest()
            } else {
                showStatus("DRON ESTABILIZADO\n\nAltura aproximada: 1 m\n\nListo para la prueba.")
                refreshButtons()
            }
        }, TAKEOFF_STABILIZATION_MS)
    }

    // =========================================================
    // PRUEBAS POR TIEMPO (sin cambios de comportamiento)
    // =========================================================

    private fun canMoveByTime(name: String): Boolean {
        if (busy) return false
        if (!virtualStick.isEnabled) {
            showStatus("Primero activa Virtual Stick")
            return false
        }
        if (!t.isFlying) {
            showStatus("PRUEBA BLOQUEADA\n\nEl dron no está volando.")
            logW("$name bloqueado: no está volando")
            return false
        }
        return true
    }

    private fun startShortForwardTest() {
        if (!canMoveByTime("FORWARD_TEST")) return
        val altitude = t.altitude
        if (altitude == null || altitude < 0.7) {
            showStatus("PRUEBA BLOQUEADA\n\nPrimero alcanza aproximadamente 1 m.")
            logW("FORWARD_TEST bloqueado: alt=$altitude < 0.7")
            return
        }
        forwardTestCompleted = false
        showStatus("PRUEBA ADELANTE\n\nAvanzando durante 1 segundo...")
        event("FORWARD_TEST_START", "Forward $TEST_SPEED m/s durante 1 s")
        logI("FORWARD_TEST_START a $TEST_SPEED m/s durante ${FORWARD_TEST_DURATION_MS}ms")

        virtualStick.moveFor(TEST_SPEED, 0f, FORWARD_TEST_DURATION_MS) {
            forwardTestCompleted = true
            showStatus("PRUEBA FINALIZADA\n\nAvance: ~0.25 m teórico\nSTOP enviado\n\nPuedes mantenerlo aquí o pulsar REGRESAR.")
            logI("FORWARD_TEST_FINISHED")
            event("FORWARD_TEST_FINISHED", "Prueba adelante finalizada")
            refreshButtons()
        }
        refreshButtons()
    }

    private fun returnLastForwardMovement() {
        if (!canMoveByTime("RETURN")) return
        if (!forwardTestCompleted) {
            showStatus("REGRESO BLOQUEADO\n\nPrimero ejecuta la prueba adelante.")
            return
        }
        showStatus("REGRESANDO\n\nInvirtiendo el último movimiento durante 1 s...")
        event("RETURN_START", "Regreso por inversión del comando durante 1 s")
        logI("RETURN_START")

        virtualStick.moveFor(-TEST_SPEED, 0f, FORWARD_TEST_DURATION_MS) {
            forwardTestCompleted = false
            showStatus("REGRESO FINALIZADO\n\nSTOP enviado.\nEl dron queda en hover.")
            logI("RETURN_FINISHED")
            event("RETURN_FINISHED", "Regreso finalizado")
            refreshButtons()
        }
        refreshButtons()
    }

    private fun startOneMeterTest() {
        if (!canMoveByTime("TEST A→1m→A")) return
        event("TEST_START", "A -> 1m -> A; speed=$TEST_SPEED")
        logI("TEST_START A→1m→A")
        showStatus("PRUEBA INICIADA\n\nA → B\nAvanzando...")

        virtualStick.moveFor(TEST_SPEED, 0f, LEG_DURATION_MS) {
            event("POINT_B", "Fin tramo A -> B")
            logI("POINT_B alcanzado")
            showStatus("PUNTO B\n\nRegresando B → A...")
            virtualStick.moveFor(-TEST_SPEED, 0f, LEG_DURATION_MS) {
                event("TEST_FINISHED", "A -> B -> A finalizado")
                logI("TEST_FINISHED A→B→A")
                showStatus("PRUEBA FINALIZADA\n\nA → B → A\nComando: ~1 m + ~1 m\nDron detenido")
                refreshButtons()
            }
        }
        refreshButtons()
    }

    // =========================================================
    // GPS A → B → A CON VIRTUAL STICK (posición real del dron)
    // =========================================================

    private fun startGpsOutAndBack() {
        if (!canMoveByTime("GPS_ABA")) return
        val route = routeRepository.buildOutAndBack(t, GPS_TEST_DISTANCE_M, t.altitude ?: 1f, 0.5f)
            .getOrElse {
                showStatus("GPS A→B→A BLOQUEADO\n\n${it.message}")
                logW("GPS_ABA bloqueado: ${it.message}")
                return
            }
        val a = route.points[0]
        val b = route.points[1]
        gpsNavRunning = true
        event("GPS_ABA_START", "A=(${a.latitude},${a.longitude}) B=(${b.latitude},${b.longitude})")
        logI("GPS_ABA_START: B a $GPS_TEST_DISTANCE_M m, rumbo ${t.heading}")
        showStatus("GPS A → B\n\nYendo a B ($GPS_TEST_DISTANCE_M m)...")
        refreshButtons()

        val params = VirtualStickController.GoToParams()
        virtualStick.goTo(b.latitude, b.longitude, params,
            onProgress = { d -> showStatus("GPS A → B\n\nDistancia a B: ${"%.1f".format(d)} m") },
            onDone = { ok, msg ->
                event(if (ok) "GPS_POINT_B" else "GPS_ABA_ABORTED", msg)
                if (!ok) {
                    gpsNavRunning = false
                    showStatus("GPS A→B→A INTERRUMPIDO\n\n$msg\n\nDron en hover.")
                    refreshButtons()
                } else {
                    showStatus("PUNTO B\n\n$msg\nRegresando a A...")
                    flightHandler.postDelayed({ gpsReturnToA(a.latitude, a.longitude, params) }, 1500L)
                }
            })
    }

    private fun gpsReturnToA(lat: Double, lon: Double, params: VirtualStickController.GoToParams) {
        if (!gpsNavRunning) return
        virtualStick.goTo(lat, lon, params,
            onProgress = { d -> showStatus("GPS B → A\n\nDistancia a A: ${"%.1f".format(d)} m") },
            onDone = { ok, msg ->
                gpsNavRunning = false
                event(if (ok) "GPS_ABA_FINISHED" else "GPS_ABA_ABORTED", msg)
                showStatus(
                    if (ok) "GPS A → B → A FINALIZADO\n\n$msg\nDron en hover."
                    else "REGRESO INTERRUMPIDO\n\n$msg\nDron en hover."
                )
                refreshButtons()
            })
    }

    // =========================================================
    // STOP / HOVER y ATERRIZAJE
    // =========================================================

    private fun emergencyStop() {
        stabilizing = false
        gpsNavRunning = false
        flight.cancelTakeoffWait()
        flightHandler.removeCallbacksAndMessages(null)

        if (waypoints.isExecuting || flight.navigationMode == NavigationMode.WAYPOINT) {
            waypoints.stop { err ->
                logW("STOP: misión Waypoint detenida ${err ?: ""}")
                refreshButtons()
            }
        }
        virtualStick.hover()

        event("MANUAL_STOP", "STOP solicitado por usuario")
        logW("MANUAL_STOP solicitado por usuario")
        showStatus("STOP\n\nComando horizontal = 0\nEl dron queda en hover donde está.")
        refreshButtons()
    }

    private fun startLanding() {
        if (flight.landingRunning) return
        AlertDialog.Builder(this)
            .setTitle("Aterrizar")
            .setMessage("¿Iniciar aterrizaje automático en la posición actual?")
            .setPositiveButton("ATERRIZAR") { _, _ -> doLanding() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doLanding() {
        stabilizing = false
        gpsNavRunning = false
        flightHandler.removeCallbacksAndMessages(null)
        virtualStick.stopMovement()
        if (waypoints.isExecuting) waypoints.stop { }
        virtualStick.sendOnce(0f, 0f)

        event("LANDING_START", "Aterrizaje solicitado")
        showStatus("ATERRIZANDO...")
        flight.land(
            onStarted = {
                logI("LANDING iniciado")
                refreshButtons()
            },
            onError = { err ->
                showStatus("ERROR DE ATERRIZAJE\n\n$err")
                event("LANDING_ERROR", err)
                refreshButtons()
            },
            onLanded = {
                forwardTestCompleted = false
                event("LANDED", "")
                logI("LANDING completado")
                showStatus("ATERRIZADO\n\nLiberando control...")
                if (virtualStick.isEnabled) {
                    virtualStick.disable { err ->
                        if (err == null) {
                            showStatus("ATERRIZADO\n\nControl liberado.\nListo para otro vuelo.")
                            logI("VS liberado tras aterrizaje")
                        } else {
                            showStatus("ATERRIZADO\n\nAviso: no se pudo liberar VS:\n$err")
                            logW("Fallo liberando VS tras aterrizaje: $err")
                        }
                        refreshButtons()
                    }
                } else {
                    showStatus("ATERRIZADO\n\nMotores apagados.")
                    refreshButtons()
                }
            }
        )
        refreshButtons()
    }

    // =========================================================
    // WAYPOINT MISSION
    // =========================================================

    private fun loadTestWaypointRoute() {
        val route = routeRepository.buildOutAndBack(t, WP_TEST_DISTANCE_M, WP_TEST_ALTITUDE_M, WP_TEST_SPEED_MPS)
            .getOrElse {
                showStatus("RUTA NO DISPONIBLE\n\n${it.message}")
                return
            }
        loadRoute(route)
    }

    private fun loadServerWaypointRoute() {
        showStatus("Descargando ruta activa del panel...")
        telemetryApi.runInBackground {
            val route = routeRepository.fetchActiveRouteFromServer()
            uiHandler.post {
                if (route == null) {
                    showStatus("SIN RUTA\n\nNo hay ruta activa en el panel o la PC no responde.")
                } else {
                    loadRoute(route)
                }
            }
        }
    }

    private fun loadRoute(route: FlightRoute) {
        routeRepository.validate(route, t)?.let {
            showStatus("RUTA RECHAZADA\n\n$it")
            logW("Ruta '${route.name}' rechazada: $it")
            return
        }
        val err = waypoints.load(route)
        if (err != null) {
            showStatus("ERROR CARGANDO MISIÓN\n\n$err")
        } else {
            routeRepository.select(route)
            val desc = route.points.joinToString("\n") {
                "${it.id}: %.6f, %.6f  %.1f m".format(it.latitude, it.longitude, it.altitudeMeters)
            }
            showStatus("MISIÓN CARGADA: ${route.name}\n\n$desc\n\nPulsa SUBIR AL DRON.")
            event("MISSION_LOADED", route.name)
        }
        refreshButtons()
    }

    private fun uploadWaypointMission() {
        showStatus("Subiendo misión al dron...")
        waypoints.upload { err ->
            if (err != null) showStatus("ERROR SUBIENDO MISIÓN\n\n$err")
            refreshButtons()
        }
    }

    private fun startWaypointMission() {
        FlightValidator.canStartMission(t)?.let {
            showStatus("MISIÓN BLOQUEADA\n\n$it")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Iniciar misión Waypoint")
            .setMessage(
                "El dron volará SOLO la ruta '${waypoints.loadedRoute?.name}'.\n\n" +
                        "Ten el control remoto en la mano. STOP / HOVER detiene la misión.\n" +
                        "Al terminar queda en hover (no aterriza solo)."
            )
            .setPositiveButton("INICIAR") { _, _ -> doStartWaypointMission() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doStartWaypointMission() {
        val begin = {
            waypoints.start { err ->
                if (err != null) showStatus("ERROR INICIANDO MISIÓN\n\n$err")
                else showStatus("MISIÓN INICIADA\n\nSTOP / HOVER para detener.")
                refreshButtons()
            }
        }
        // Virtual Stick y Waypoint nunca a la vez: desactivar primero.
        if (virtualStick.isEnabled) {
            showStatus("Desactivando Virtual Stick antes de la misión...")
            virtualStick.disable { err ->
                if (err != null) {
                    showStatus("No se pudo desactivar Virtual Stick:\n$err")
                    refreshButtons()
                } else begin()
            }
        } else begin()
    }

    private fun stopWaypointMission() {
        waypoints.stop { err ->
            showStatus(if (err == null) "MISIÓN DETENIDA\n\nEl dron queda en hover." else "ERROR DETENIENDO:\n$err")
            event("MISSION_STOPPED", err ?: "")
            refreshButtons()
        }
    }

    // =========================================================
    // VIDEO: grabación local + envío a la PC
    // =========================================================

    private fun toggleRecording() {
        if (video.isRecording) {
            val file = video.stopRecording()
            recButton.text = "● REC"
            Toast.makeText(this, "Guardado: ${file?.name}", Toast.LENGTH_LONG).show()
            logI("Grabación detenida: ${file?.name}")
            event("VIDEO_STOP", file?.name ?: "")
        } else {
            val sessionId = flightRepository.session.id
            val recId = sessionId + "_rec_" + SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val file = video.startRecording(recId, sessionId)
            recButton.text = "■ STOP"
            Toast.makeText(this, "Grabando: ${file?.name}", Toast.LENGTH_SHORT).show()
            logI("Grabación iniciada: ${file?.name} (también se envía a la PC)")
            event("VIDEO_START", file?.name ?: "")
        }
    }

    // =========================================================
    // CONFIGURACIÓN DEL SERVIDOR
    // =========================================================

    private fun showServerDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(apiClient.baseUrl)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Servidor de la PC (FastAPI)")
            .setMessage("Misma red Wi-Fi. Ejemplo: http://192.168.1.50:8000")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                apiClient.setBaseUrl(input.text.toString())
                serverButton.text = "PC: ${apiClient.baseUrl}"
                updateStatus()
                logI("Servidor configurado: ${apiClient.baseUrl}")
                telemetryApi.runInBackground {
                    val ok = apiClient.healthCheck()
                    uiHandler.post {
                        serverOk = ok
                        updateStatus()
                        Toast.makeText(this, if (ok) "PC conectada" else "La PC no responde", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
