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
import com.diegorivas.drone.network.LiveFrameStreamer
import com.diegorivas.drone.network.TelemetryApi
import com.diegorivas.drone.network.RecorridoClient
import com.diegorivas.drone.network.VideoStreamClient
import com.diegorivas.drone.recording.TramoRecorder
import com.diegorivas.drone.utils.FlightValidator
import dji.common.mission.waypoint.WaypointMissionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coordinador de la interfaz. La lógica de vuelo vive en:
 *  - dji/DjiConnectionManager    registro y conexión
 *  - dji/TelemetryManager        GPS, altitud, velocidad, heading, batería
 *  - dji/FlightControllerManager despegue, aterrizaje, modo de navegación
 *  - dji/VirtualStickController  movimiento por tiempo y por GPS
 *  - dji/WaypointMissionManager  misiones GPS de DJI
 *  - dji/VideoManager            video en vivo + grabación + envío a PC
 *  - network/LiveFrameStreamer   video en vivo (JPEG) al panel de la PC
 *  - network (ApiClient, ...)    comunicación con FastAPI en la PC
 *
 * Un VUELO va del despegue al aterrizaje (IS_FLYING). Al despegar se abre un vuelo
 * nuevo y se graba el video automáticamente; al aterrizar se cierra y la PC guarda
 * su carpeta con telemetria.csv y video.mp4.
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
        private const val TAKEOFF_TIMEOUT_MS = 20000L
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
    private lateinit var resendButton: Button
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
    private lateinit var tramoRecorder: TramoRecorder
    private lateinit var recorridoClient: RecorridoClient
    private lateinit var liveStreamer: LiveFrameStreamer

    /** Handler para la UI y log. */
    private val uiHandler = Handler(Looper.getMainLooper())
    /** Handler sólo para secuencias de vuelo (STOP lo vacía sin tocar el log). */
    private val flightHandler = Handler(Looper.getMainLooper())
    private val logBuffer = StringBuilder()

    private var forwardTestCompleted = false
    private var stabilizing = false
    private var gpsNavRunning = false
    /** Último IS_FLYING procesado para abrir/cerrar vuelos. */
    private var flightOpen = false
    /** La grabación en curso la inició el vuelo (no el botón REC). */
    private var autoRecording = false
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
        refreshResendButton()
        refreshButtons()
        updateStatus()

        if (hasAllPermissions()) {
            connection.register()
        } else {
            requestMissingPermissions()
        }
        logI("App iniciada. Sesión: ${flightRepository.session.id}")
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
        resendButton = findViewById(R.id.resendButton)
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
        tramoRecorder = TramoRecorder(this)
        recorridoClient = RecorridoClient(apiClient)
        liveStreamer = LiveFrameStreamer(apiClient, videoSurface) { msg -> logI(msg) }
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
        resendButton.setOnClickListener { reenviarPendientes() }
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
        liveStreamer.shutdown()
        if (flightRepository.inFlight) closeFlight("app cerrada")

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

        // Se guarda TODO (también lo que funcionó): sin eso no se puede saber
        // qué pasó en un vuelo que salió bien y en el siguiente que falló.
        try {
            File(getExternalFilesDir(null), "dji_errors.log").appendText(line)
        } catch (_: Exception) { }
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
        liveStreamer.start()
        // Estado limpio: Virtual Stick pudo quedar activo en el dron de una sesión anterior.
        if (flightControllerAvailable) flight.forceVirtualStickOff { refreshButtons() }
        refreshButtons()
    }

    override fun onProductDisconnected() {
        djiStatus = "PRODUCT DISCONNECTED\nFlightController: NO DISPONIBLE"
        updateStatus()
        flightHandler.removeCallbacksAndMessages(null)
        virtualStick.reset()
        flight.reset()
        liveStreamer.stop()
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
        if (available) flight.forceVirtualStickOff { refreshButtons() }
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
        flight.syncWithTelemetry(data)
        trackFlight(data)
        flightRepository.onTelemetry(data)
        tramoRecorder.onTelemetry(data)

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

        // Despegar ya NO necesita Virtual Stick: se desactiva solo antes de despegar.
        // Las pruebas de movimiento lo activan solas cuando hacen falta.
        val fcOk = connection.flightController != null
        takeoffButton.isEnabled = fcOk && !flying && !t.areMotorsOn && !busy && !wpActive
        autoTestButton.isEnabled = fcOk && !flying && !t.areMotorsOn && !busy && !wpActive
        testForwardButton.isEnabled = flying && !busy && !wpActive
        testOneMeterButton.isEnabled = flying && !busy && !wpActive
        returnButton.isEnabled = flying && !busy && !wpActive && forwardTestCompleted
        gpsOutAndBackButton.isEnabled = flying && !busy && !wpActive

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
                            "Mientras está activo, el mando NO mueve el dron.\n" +
                            "No hace falta para despegar: DESPEGAR lo desactiva solo."
                )
            } else {
                showStatus("ERROR ACTIVANDO:\n$err")
            }
            refreshButtons()
        }
    }

    // =========================================================
    // DESPEGUE (rehecho: ver FlightControllerManager)
    // =========================================================

    private fun startAutomaticTakeoff() = takeoffSequence(thenForward = false)

    private fun startAutomaticTest() = takeoffSequence(thenForward = true)

    /**
     * 1. Comprobaciones (modo P, batería, brújula, IMU...).
     * 2. FlightControllerManager desactiva Virtual Stick en el dron y llama a startTakeoff().
     * 3. Al llegar a ~1 m: estabilizar y, si es la prueba automática, avanzar 1 s.
     */
    private fun takeoffSequence(thenForward: Boolean) {
        if (busy) {
            Toast.makeText(this, "Ya hay una operación en curso", Toast.LENGTH_SHORT).show()
            return
        }
        flight.takeoffBlocker()?.let {
            showStatus("DESPEGUE BLOQUEADO\n\n$it")
            logW("TAKEOFF bloqueado: $it")
            return
        }

        forwardTestCompleted = false
        val eventName = if (thenForward) "AUTO_TEST_START" else "TAKEOFF_START"
        event(eventName, if (thenForward) "Takeoff -> 1m -> forward 1s -> stop" else "Despegue automático solicitado")

        flight.takeoff(
            targetAltitude = TAKEOFF_TARGET_ALTITUDE,
            timeoutMs = TAKEOFF_TIMEOUT_MS,
            onProgress = { msg ->
                showStatus(if (thenForward) "PRUEBA AUTOMÁTICA\n\n$msg" else "DESPEGUE\n\n$msg")
                refreshButtons()
            },
            onAirborne = { alt ->
                logI("TAKEOFF en el aire: alt=$alt")
                event("TAKEOFF_ALTITUDE_REACHED", "alt=$alt")
                showStatus("EN EL AIRE\n\nAltitud: ${alt?.let { "%.2f m".format(it) } ?: "--"}\n\nEstabilizando...")
                stabilizeThen(thenForward)
            },
            onError = { err ->
                event("TAKEOFF_ERROR", err)
                logE("TAKEOFF falló: $err")
                showStatus("NO DESPEGÓ\n\n$err\n\n${flight.diagnostics()}")
                refreshButtons()
            }
        )
        refreshButtons()
    }

    private fun stabilizeThen(thenForward: Boolean) {
        stabilizing = true
        refreshButtons()
        flightHandler.postDelayed({
            if (!stabilizing) return@postDelayed
            stabilizing = false
            event("TAKEOFF_STABILIZED", "Dron estabilizado aproximadamente a 1 m")
            logI("TAKEOFF ESTABILIZADO")
            if (thenForward) {
                showStatus("DESPEGUE COMPLETADO\n\nIniciando prueba adelante...")
                refreshButtons()
                startShortForwardTest()
            } else {
                showStatus(
                    "DRON EN HOVER (~1 m)\n\n" +
                            "El mando sigue teniendo el control.\n" +
                            "Pruebas 4-7: activan Virtual Stick solas.\nATERRIZAR cuando quieras."
                )
                refreshButtons()
            }
        }, TAKEOFF_STABILIZATION_MS)
    }

    // =========================================================
    // VUELO = despegue → aterrizaje (telemetría + video por vuelo)
    // =========================================================

    /** Abre/cierra el vuelo según IS_FLYING, venga el despegue de la app o del mando. */
    private fun trackFlight(data: TelemetryData) {
        if (!flightOpen && data.isFlying) {
            flightOpen = true
            openFlight()
        } else if (flightOpen && !data.isFlying && !data.areMotorsOn) {
            flightOpen = false
            closeFlight("aterrizado")
        }
    }

    private fun openFlight() {
        val id = flightRepository.startFlight()
        event("FLIGHT_START", "Vuelo $id")
        logI("VUELO INICIADO: $id")
        if (!video.isRecording) {
            video.startRecording(recordingId = id, flightId = id)
            autoRecording = true
            recButton.text = "■ STOP (vuelo)"
            logI("Grabando video del vuelo $id (automático)")
        }
    }

    private fun closeFlight(reason: String) {
        if (autoRecording) stopAutoRecording()
        // FLIGHT_END va con el id del vuelo: la PC genera su carpeta (CSV + MP4).
        event("FLIGHT_END", reason)
        val id = flightRepository.endFlight()
        logI("VUELO CERRADO: $id ($reason). La PC guarda telemetria.csv y video.mp4")
    }

    private fun stopAutoRecording() {
        val file = video.stopRecording()
        autoRecording = false
        recButton.text = "● REC"
        logI("Video del vuelo cerrado (${file?.length()?.div(1024) ?: 0} KB), enviando a la PC")
    }

    // =========================================================
    // PRUEBAS DE MOVIMIENTO (activan Virtual Stick solas)
    // =========================================================

    /** Activa Virtual Stick si hace falta y luego ejecuta la acción. */
    private fun withVirtualStick(action: () -> Unit) {
        if (virtualStick.isEnabled) return action()
        showStatus("Activando Virtual Stick...")
        virtualStick.ensureEnabled { err ->
            if (err != null) {
                showStatus("NO SE PUDO ACTIVAR VIRTUAL STICK\n\n$err")
                refreshButtons()
            } else {
                refreshButtons()
                action()
            }
        }
    }

    private fun canMoveByTime(name: String): Boolean {
        if (busy) return false
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
        withVirtualStick { doShortForwardTest() }
    }

    private fun doShortForwardTest() {
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
        withVirtualStick {
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
    }

    private fun startOneMeterTest() {
        if (!canMoveByTime("TEST A→1m→A")) return
        withVirtualStick { doOneMeterTest() }
    }

    private fun doOneMeterTest() {
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
        withVirtualStick { doGpsOutAndBack(a.latitude, a.longitude, b.latitude, b.longitude) }
    }

    private fun doGpsOutAndBack(aLat: Double, aLon: Double, bLat: Double, bLon: Double) {
        gpsNavRunning = true
        event("GPS_ABA_START", "A=($aLat,$aLon) B=($bLat,$bLon)")
        logI("GPS_ABA_START: B a $GPS_TEST_DISTANCE_M m, rumbo ${t.heading}")
        showStatus("GPS A → B\n\nYendo a B ($GPS_TEST_DISTANCE_M m)...")
        refreshButtons()

        val params = VirtualStickController.GoToParams()
        virtualStick.goTo(bLat, bLon, params,
            onProgress = { d -> showStatus("GPS A → B\n\nDistancia a B: ${"%.1f".format(d)} m") },
            onDone = { ok, msg ->
                event(if (ok) "GPS_POINT_B" else "GPS_ABA_ABORTED", msg)
                if (!ok) {
                    gpsNavRunning = false
                    showStatus("GPS A→B→A INTERRUMPIDO\n\n$msg\n\nDron en hover.")
                    refreshButtons()
                } else {
                    showStatus("PUNTO B\n\n$msg\nRegresando a A...")
                    flightHandler.postDelayed({ gpsReturnToA(aLat, aLon, params) }, 1500L)
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
        flightHandler.removeCallbacksAndMessages(null)

        if (waypoints.isExecuting || flight.navigationMode == NavigationMode.WAYPOINT) {
            waypoints.stop { err ->
                logW("STOP: misión Waypoint detenida ${err ?: ""}")
                refreshButtons()
            }
        }
        val wasLanding = flight.landingRunning
        flight.cancelAll()
        virtualStick.hover()
        if (wasLanding) {
            // STOP durante el aterrizaje: el dron se queda en el aire (hover).
            connection.flightController?.cancelLanding { err ->
                uiHandler.post { if (err != null) logW("cancelLanding: ${err.description}") }
            }
        }

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

    /**
     * 1. Corta pruebas/misiones en curso.
     * 2. FlightControllerManager desactiva Virtual Stick (antes no se hacía y el dron
     *    rechazaba startLanding con Virtual Stick activo) y llama a startLanding().
     * 3. Confirma el aterrizaje cerca del suelo y espera a que se apaguen los motores.
     */
    private fun doLanding() {
        stabilizing = false
        gpsNavRunning = false
        flightHandler.removeCallbacksAndMessages(null)
        virtualStick.stopMovement()
        virtualStick.sendOnce(0f, 0f)
        if (waypoints.isExecuting) waypoints.stop { }

        event("LANDING_START", "Aterrizaje solicitado")
        flight.land(
            onProgress = { msg ->
                showStatus(msg)
                refreshButtons()
            },
            onLanded = {
                forwardTestCompleted = false
                event("LANDED", "")
                showStatus("ATERRIZADO\n\nMotores apagados.\nListo para otro vuelo.")
                refreshButtons()
            },
            onError = { err ->
                event("LANDING_ERROR", err)
                logE("LANDING falló: $err")
                showStatus("ERROR DE ATERRIZAJE\n\n$err")
                refreshButtons()
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

    /**
     * REC  → nueva carpeta de tramo (video + telemetría) y la PC asigna "tramo N del recorrido X".
     * STOP → se cierra el tramo y se sube solo a la PC. Si no se puede, queda pendiente
     *        en el teléfono y se reenvía con REENVIAR TRAMOS PENDIENTES.
     */
    private fun toggleRecording() {
        if (autoRecording) {
            // ■ STOP (vuelo): corta el video automático del vuelo (se guarda igual como
            // video.mp4 del vuelo). Volver a pulsar ● REC empieza un tramo.
            stopAutoRecording()
            return
        }
        if (video.isRecording) {
            val file = video.stopRecording()
            val dir = tramoRecorder.stop(video.firstVideoDataMs, connection.connectedModel)
            recButton.text = "● REC"
            logI("Tramo cerrado: ${dir?.name} (${file?.length()?.div(1024) ?: 0} KB de video)")
            event("VIDEO_STOP", dir?.name ?: "")
            if (dir != null) subirTramo(dir)
        } else {
            val sessionId = flightRepository.session.id
            val recId = sessionId + "_rec_" + SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val dir = tramoRecorder.start()
            video.startRecordingTo(File(dir, TramoRecorder.VIDEO_FILE), recId, sessionId)
            recButton.text = "■ STOP"
            logI("Grabando ${dir.name}")
            event("VIDEO_START", dir.name)
            showStatus("GRABANDO TRAMO\n\nConsultando a la PC qué tramo es...")

            // Reservar el tramo en la PC (no bloquea la grabación si la PC no responde).
            recorridoClient.enqueue {
                val r = recorridoClient.reservar("grabando")
                r.onSuccess { reserva -> tramoRecorder.guardarReserva(dir, reserva) }
                uiHandler.post {
                    r.onSuccess {
                        showStatus("GRABANDO TRAMO ${it.tramo}\n\nRecorrido: ${it.recorrido}\n\nPulsa ■ STOP al terminar el tramo.")
                        logI("PC: tramo ${it.tramo} del recorrido '${it.recorrido}'")
                    }.onFailure {
                        showStatus("GRABANDO (sólo en el teléfono)\n\n${it.message}\n\nAl pulsar STOP se intentará enviar igual.")
                        logW("Reserva de tramo: ${it.message}")
                    }
                }
            }
        }
        refreshResendButton()
    }

    private fun subirTramo(dir: File) {
        showStatus("ENVIANDO TRAMO A LA PC...\n\n${dir.name}")
        recorridoClient.enqueue {
            val reserva = tramoRecorder.reserva(dir)
                ?: recorridoClient.reservar("subiendo").getOrElse { e ->
                    uiHandler.post {
                        showStatus("TRAMO GUARDADO EN EL TELÉFONO\n\nNo se envió: ${e.message}\n\nUsa REENVIAR TRAMOS PENDIENTES.")
                        logW("Tramo ${dir.name} pendiente: ${e.message}")
                        refreshResendButton()
                    }
                    return@enqueue
                }
            tramoRecorder.guardarReserva(dir, reserva)
            val error = recorridoClient.subir(dir, reserva) { msg -> uiHandler.post { logI(msg) } }
            if (error == null) tramoRecorder.marcarSubido(dir)
            uiHandler.post {
                if (error == null) {
                    showStatus("TRAMO ${reserva.tramo} ENVIADO ✓\n\nRecorrido: ${reserva.recorrido}\n\nEn la PC: NUEVO TRAMO y aquí ● REC para el siguiente.")
                    logI("Tramo ${reserva.tramo} enviado a la PC")
                    Toast.makeText(this, "Tramo ${reserva.tramo} enviado a la PC", Toast.LENGTH_LONG).show()
                } else {
                    showStatus("TRAMO GUARDADO EN EL TELÉFONO\n\n$error\n\nUsa REENVIAR TRAMOS PENDIENTES.")
                    logE("Tramo ${dir.name}: $error")
                }
                refreshResendButton()
            }
        }
    }

    private fun reenviarPendientes() {
        if (tramoRecorder.isRecording) {
            Toast.makeText(this, "Primero detén la grabación", Toast.LENGTH_SHORT).show()
            return
        }
        val pendientes = tramoRecorder.pendientes()
        if (pendientes.isEmpty()) {
            Toast.makeText(this, "No hay tramos pendientes", Toast.LENGTH_SHORT).show()
            return
        }
        logI("Reenviando ${pendientes.size} tramo(s) pendiente(s)...")
        pendientes.forEach { subirTramo(it) }
    }

    private fun refreshResendButton() {
        val n = tramoRecorder.pendientes().size
        resendButton.text = if (n == 0) "SIN TRAMOS PENDIENTES" else "REENVIAR TRAMOS PENDIENTES ($n)"
        resendButton.isEnabled = n > 0
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
