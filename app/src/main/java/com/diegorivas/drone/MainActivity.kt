package com.diegorivas.drone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.flightcontroller.LocationCoordinate3D
import dji.common.flightcontroller.virtualstick.FlightControlData
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem
import dji.common.flightcontroller.virtualstick.RollPitchControlMode
import dji.common.flightcontroller.virtualstick.VerticalControlMode
import dji.common.flightcontroller.virtualstick.YawControlMode
import dji.common.product.Model
import dji.common.util.CommonCallbacks
import dji.keysdk.BatteryKey
import dji.keysdk.DJIKey
import dji.keysdk.FlightControllerKey
import dji.keysdk.KeyManager
import dji.keysdk.callback.GetCallback
import dji.keysdk.callback.KeyListener
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.flightcontroller.FlightController
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// NOTA: los nombres exactos de métodos de DJICodecManager (cleanSurface, destroyCodec,
// sendDataToDecoder) y de las keys de BatteryKey/FlightControllerKey pueden variar
// levemente entre builds del SDK 4.18. Si Android Studio marca algo en rojo, compara
// contra el sample code de DJI y ajusta solo esa línea.

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

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
    }

    private lateinit var statusText: TextView
    private lateinit var telemetryText: TextView
    private lateinit var videoSurface: TextureView

    private var codecManager: DJICodecManager? = null

    // ---------- Estado de telemetría ----------
    private var batteryPercent: Int? = null
    private var location: LocationCoordinate3D? = null

    // ---------- Estado de telemetría extendida ----------
    private var velocityX: Float? = null
    private var velocityY: Float? = null
    private var heading: Float? = null

    private var telemetryLogger: TelemetryLogger? = null

    //Boton
    private lateinit var recButton: Button

    // =========================================================
    // VIRTUAL STICK
    // =========================================================

    private var flightController: FlightController? = null

    private lateinit var virtualStickText: TextView
    private lateinit var enableVirtualStickButton: Button
    private lateinit var testOneMeterButton: Button
    private lateinit var emergencyStopButton: Button
    private lateinit var takeoffButton: Button
    private lateinit var autoTestButton: Button
    private lateinit var testForwardButton: Button
    private lateinit var returnButton: Button

    private val flightHandler = Handler(Looper.getMainLooper())

    private var virtualStickEnabled = false
    private var testRunning = false
    private var takeoffRunning = false
    private var isFlying = false
    private var forwardTestCompleted = false

    private val TEST_SPEED = 0.25f
    private val TEST_DISTANCE = 1.0f
    private val LEG_DURATION_MS = 4000L
    private val COMMAND_INTERVAL_MS = 100L
    private val TAKEOFF_TARGET_ALTITUDE = 0.9
    private val TAKEOFF_TIMEOUT_MS = 15000L
    private val TAKEOFF_STABILIZATION_MS = 2000L
    private val FORWARD_TEST_DURATION_MS = 1000L

    private var testStartLat: Double? = null
    private var testStartLon: Double? = null

    private val videoRecorder by lazy { VideoRecorder(this) }

    // =========================================================
    // LOG EN PANTALLA
    // =========================================================

    private lateinit var errorLogText: TextView
    private lateinit var errorLogScroll: ScrollView

    private val logHandler = Handler(Looper.getMainLooper())
    private val logBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        telemetryLogger = TelemetryLogger(this)

        statusText = findViewById(R.id.statusText)
        telemetryText = findViewById(R.id.telemetryText)
        videoSurface = findViewById(R.id.videoSurface)

        // Log en pantalla (defensivo: si el layout no tuviera los IDs, no crashea)
        errorLogText = findViewById<TextView?>(R.id.errorLogText)
            ?: TextView(this).also { Log.w(TAG, "errorLogText no encontrado en layout") }
        errorLogScroll = findViewById<ScrollView?>(R.id.errorLogScroll)
            ?: ScrollView(this).also { Log.w(TAG, "errorLogScroll no encontrado en layout") }

        recButton = findViewById(R.id.recButton)
        recButton.setOnClickListener { toggleRecording() }

        videoSurface.surfaceTextureListener = this

        virtualStickText = findViewById(R.id.virtualStickText)
        enableVirtualStickButton = findViewById(R.id.enableVirtualStickButton)
        testOneMeterButton = findViewById(R.id.testOneMeterButton)
        emergencyStopButton = findViewById(R.id.emergencyStopButton)
        takeoffButton = findViewById(R.id.takeoffButton)
        autoTestButton = findViewById(R.id.autoTestButton)
        testForwardButton = findViewById(R.id.testForwardButton)
        returnButton = findViewById(R.id.returnButton)

        enableVirtualStickButton.setOnClickListener { enableVirtualStick() }
        testOneMeterButton.setOnClickListener { startOneMeterTest() }
        emergencyStopButton.setOnClickListener { emergencyStop() }
        takeoffButton.setOnClickListener { startAutomaticTakeoff() }
        autoTestButton.setOnClickListener { startAutomaticTest() }
        testForwardButton.setOnClickListener { startShortForwardTest() }
        returnButton.setOnClickListener { returnLastForwardMovement() }

        testOneMeterButton.isEnabled = false
        takeoffButton.isEnabled = false
        autoTestButton.isEnabled = false
        testForwardButton.isEnabled = false
        returnButton.isEnabled = false

        if (hasAllPermissions()) {
            registerDjiApp()
        } else {
            requestMissingPermissions()
        }

        logI(TAG, "App iniciada")
    }

    // =========================================================
    // LOG UNIFICADO: pantalla + Logcat + archivo
    // =========================================================

    private fun logToScreen(level: Char, tag: String, message: String) {
        when (level) {
            'E' -> Log.e(tag, message)
            'W' -> Log.w(tag, message)
            'I' -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }

        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$ts [$level] $tag: $message\n"

        logHandler.post {
            // Defensivo: solo escribir en pantalla si las vistas existen de verdad
            if (::errorLogText.isInitialized && ::errorLogScroll.isInitialized) {
                logBuffer.append(line)
                if (logBuffer.length > MAX_LOG_CHARS) {
                    logBuffer.delete(0, logBuffer.length - MAX_LOG_CHARS)
                }
                errorLogText.text = logBuffer.toString()
                errorLogScroll.post { errorLogScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        if (level == 'E' || level == 'W') {
            try {
                File(getExternalFilesDir(null), "dji_errors.log").appendText(line)
            } catch (_: Exception) {
                // ignorar
            }
        }
    }

    private fun logE(tag: String = TAG, message: String) = logToScreen('E', tag, message)
    private fun logW(tag: String = TAG, message: String) = logToScreen('W', tag, message)
    private fun logI(tag: String = TAG, message: String) = logToScreen('I', tag, message)

    //Funcion nueva
    private fun toggleRecording() {
        if (videoRecorder.isRecording) {
            videoRecorder.stop()
            recButton.text = "● REC"
            Toast.makeText(this, "Guardado: ${videoRecorder.currentFile?.name}", Toast.LENGTH_LONG).show()
            logI(TAG, "Grabación detenida: ${videoRecorder.currentFile?.name}")
        } else {
            videoRecorder.start()
            recButton.text = "■ STOP"
            Toast.makeText(this, "Grabando: ${videoRecorder.currentFile?.name}", Toast.LENGTH_SHORT).show()
            logI(TAG, "Grabación iniciada: ${videoRecorder.currentFile?.name}")
        }
    }

    // ---------- Permisos ----------

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
                registerDjiApp()
            } else {
                Toast.makeText(
                    this,
                    "Se necesitan todos los permisos para conectar con el dron",
                    Toast.LENGTH_LONG
                ).show()
                logE(TAG, "Permisos incompletos: no se puede registrar DJI")
            }
        }
    }

    // ---------- Registro y conexión DJI ----------

    private fun registerDjiApp() {
        updateStatus("Registrando app con DJI...")
        logI(TAG, "Registrando app con DJI...")

        DJISDKManager.getInstance().registerApp(
            applicationContext,
            object : DJISDKManager.SDKManagerCallback {

                override fun onRegister(error: DJIError?) {
                    if (error == DJISDKError.REGISTRATION_SUCCESS) {
                        runOnUiThread { updateStatus("REGISTRATION SUCCESS") }
                        logI(TAG, "REGISTRATION SUCCESS")
                        DJISDKManager.getInstance().startConnectionToProduct()
                    } else {
                        runOnUiThread {
                            updateStatus("Registro fallido: ${error?.description}")
                        }
                        logE(TAG, "Registro fallido: ${error?.description}")
                    }
                }

                override fun onProductDisconnect() {
                    flightController = null
                    virtualStickEnabled = false
                    isFlying = false
                    runOnUiThread {
                        updateStatus("PRODUCT DISCONNECTED\nFlightController: NO DISPONIBLE")
                        showVirtualStickStatus("DRON DESCONECTADO")
                    }
                    logW(TAG, "Producto DJI desconectado")
                }

                override fun onProductConnect(product: BaseProduct?) {
                    val model: Model? = product?.model

                    if (product is Aircraft) {
                        flightController = product.flightController
                    }
                    if (flightController == null) {
                        flightController =
                            (DJISDKManager.getInstance().product as? Aircraft)?.flightController
                    }

                    val fcAvailable = flightController != null
                    runOnUiThread {
                        updateStatus(
                            "DRONE CONNECTED: $model\n" +
                                    "FlightController: ${if (fcAvailable) "OK" else "ESPERANDO..."}"
                        )
                    }
                    logI(TAG, "DRONE CONNECTED: $model, FlightController=$fcAvailable")
                    startTelemetryListeners()
                }

                override fun onProductChanged(product: BaseProduct?) { }

                override fun onComponentChange(
                    key: BaseProduct.ComponentKey?,
                    oldComponent: BaseComponent?,
                    newComponent: BaseComponent?
                ) {
                    if (key == BaseProduct.ComponentKey.FLIGHT_CONTROLLER) {
                        flightController = newComponent as? FlightController
                        logI(TAG, "FlightController component changed: ${flightController != null}")
                        runOnUiThread {
                            updateStatus(
                                "DRONE CONNECTED\n" +
                                        "FlightController: ${
                                            if (flightController != null) "OK" else "NO DISPONIBLE"
                                        }"
                            )
                        }
                    }
                }

                override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                    logI(TAG, "onInitProcess: $event ($totalProcess%)")
                }

                override fun onDatabaseDownloadProgress(current: Long, total: Long) { }
            }
        )
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    // ---------- Telemetría ----------

    private fun startTelemetryListeners() {
        val batteryKey: DJIKey = BatteryKey.create(BatteryKey.CHARGE_REMAINING_IN_PERCENT)
        val locationKey: DJIKey = FlightControllerKey.create(FlightControllerKey.AIRCRAFT_LOCATION)

        KeyManager.getInstance()?.getValue(batteryKey, object : GetCallback {
            override fun onSuccess(value: Any) {
                batteryPercent = value as? Int
                runOnUiThread { updateTelemetryText() }
            }
            override fun onFailure(error: DJIError) {
                logE(TAG, "Error leyendo batería: ${error.description}")
            }
        })

        KeyManager.getInstance()?.getValue(locationKey, object : GetCallback {
            override fun onSuccess(value: Any) {
                location = value as? LocationCoordinate3D
                runOnUiThread { updateTelemetryText() }
            }
            override fun onFailure(error: DJIError) {
                logE(TAG, "Error leyendo ubicación: ${error.description}")
            }
        })

        KeyManager.getInstance()?.addListener(batteryKey, object : KeyListener {
            override fun onValueChange(oldValue: Any?, newValue: Any?) {
                batteryPercent = newValue as? Int
                runOnUiThread { updateTelemetryText() }
            }
        })

        KeyManager.getInstance()?.addListener(locationKey, object : KeyListener {
            override fun onValueChange(oldValue: Any?, newValue: Any?) {
                location = newValue as? LocationCoordinate3D
                runOnUiThread { updateTelemetryText() }
            }
        })

        val velXKey: DJIKey = FlightControllerKey.create(FlightControllerKey.VELOCITY_X)
        val velYKey: DJIKey = FlightControllerKey.create(FlightControllerKey.VELOCITY_Y)
        val headingKey: DJIKey = FlightControllerKey.create(FlightControllerKey.COMPASS_HEADING)

        KeyManager.getInstance()?.addListener(velXKey, object : KeyListener {
            override fun onValueChange(oldValue: Any?, newValue: Any?) {
                velocityX = newValue as? Float
                runOnUiThread { updateTelemetryText() }
            }
        })

        KeyManager.getInstance()?.addListener(velYKey, object : KeyListener {
            override fun onValueChange(oldValue: Any?, newValue: Any?) {
                velocityY = newValue as? Float
                runOnUiThread { updateTelemetryText() }
            }
        })

        KeyManager.getInstance()?.addListener(headingKey, object : KeyListener {
            override fun onValueChange(oldValue: Any?, newValue: Any?) {
                heading = newValue as? Float
                runOnUiThread { updateTelemetryText() }
            }
        })

        val flyingKey: DJIKey = FlightControllerKey.create(FlightControllerKey.IS_FLYING)

        KeyManager.getInstance()?.getValue(flyingKey, object : GetCallback {
            override fun onSuccess(value: Any) {
                isFlying = value as? Boolean ?: false
                logI(TAG, "IS_FLYING = $isFlying")
            }
            override fun onFailure(error: DJIError) {
                logE(TAG, "Error leyendo IS_FLYING: ${error.description}")
            }
        })

        KeyManager.getInstance()?.addListener(flyingKey, object : KeyListener {
            override fun onValueChange(oldValue: Any?, newValue: Any?) {
                isFlying = newValue as? Boolean ?: false
                logI(TAG, "IS_FLYING cambió = $isFlying")
            }
        })
    }

    private fun updateTelemetryText() {
        val bat = batteryPercent?.let { "$it%" } ?: "--"
        val lat = location?.latitude?.let { "%.5f".format(it) } ?: "--"
        val lon = location?.longitude?.let { "%.5f".format(it) } ?: "--"
        val alt = location?.altitude?.let { "%.1f m".format(it) } ?: "--"
        val speed = if (velocityX != null && velocityY != null) {
            "%.1f m/s".format(kotlin.math.sqrt(velocityX!! * velocityX!! + velocityY!! * velocityY!!))
        } else { "--" }
        val head = heading?.let { "%.0f°".format(it) } ?: "--"

        telemetryLogger?.log(
            event = "TELEMETRY",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = if (velocityX != null && velocityY != null) {
                kotlin.math.sqrt(velocityX!! * velocityX!! + velocityY!! * velocityY!!).toDouble()
            } else null,
            heading = heading,
            battery = batteryPercent
        )

        val signal = "--"

        telemetryText.text =
            "BAT: $bat\n" +
                    "LAT: $lat  LON: $lon\n" +
                    "ALT: $alt\n" +
                    "VEL: $speed  HDG: $head\n" +
                    "RSSI: $signal"
    }

    // ---------- Video en vivo ----------

    private val videoDataListener =
        VideoFeeder.VideoDataListener { videoBuffer, size ->
            codecManager?.sendDataToDecoder(videoBuffer, size)
            videoRecorder.write(videoBuffer, size)
        }

    private fun startVideoFeed() {
        VideoFeeder.getInstance()?.primaryVideoFeed?.addVideoDataListener(videoDataListener)
    }

    private fun stopVideoFeed() {
        VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(videoDataListener)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (codecManager == null) {
            codecManager = DJICodecManager(this, surface, width, height)
        }
        startVideoFeed()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopVideoFeed()
        codecManager?.cleanSurface()
        codecManager?.destroyCodec()
        codecManager = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { }

    override fun onDestroy() {
        testRunning = false
        takeoffRunning = false
        flightHandler.removeCallbacksAndMessages(null)

        if (virtualStickEnabled) {
            sendFlightCommand(0f)
            flightController?.setVirtualStickModeEnabled(false, null)
        }

        stopVideoFeed()
        codecManager?.destroyCodec()
        videoRecorder.stop()

        logI(TAG, "onDestroy: recursos liberados")
        super.onDestroy()
    }

    private fun enableVirtualStick() {
        val fc = flightController
            ?: (DJISDKManager.getInstance().product as? Aircraft)?.flightController

        if (flightController == null && fc != null) {
            flightController = fc
        }

        if (fc == null) {
            showVirtualStickStatus("ERROR\nFlightController no disponible")
            logE(TAG, "enableVirtualStick: FlightController no disponible")
            return
        }

        showVirtualStickStatus("Activando Virtual Stick...")

        fc.setVirtualStickModeEnabled(true, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                runOnUiThread {
                    if (error == null) {
                        virtualStickEnabled = true
                        configureVirtualStick()
                        takeoffButton.isEnabled = true
                        autoTestButton.isEnabled = true
                        testForwardButton.isEnabled = false
                        testOneMeterButton.isEnabled = false
                        returnButton.isEnabled = false
                        enableVirtualStickButton.text = "CONTROL ACTIVO"
                        showVirtualStickStatus(
                            "VIRTUAL STICK: ACTIVO\n\n" +
                                    "El dron NO despega al activar control.\n" +
                                    "Usa DESPEGAR → 1 m o PRUEBA AUTOMÁTICA."
                        )
                        logI(TAG, "VirtualStick ACTIVADO")
                    } else {
                        virtualStickEnabled = false
                        showVirtualStickStatus("ERROR ACTIVANDO:\n${error.description}")
                        logE(TAG, "ERROR ACTIVANDO VIRTUAL STICK: ${error.description}")
                    }
                }
            }
        })
    }

    // =========================================================
    // DESPEGUE AUTOMÁTICO
    // =========================================================

    private fun startAutomaticTakeoff() {
        if (takeoffRunning || testRunning) {
            Toast.makeText(this, "Ya hay una prueba ejecutándose", Toast.LENGTH_SHORT).show()
            return
        }
        if (!virtualStickEnabled) {
            showVirtualStickStatus("Primero activa Virtual Stick")
            return
        }
        if (isFlying) {
            showVirtualStickStatus("EL DRON YA ESTÁ VOLANDO\n\nNo se ejecutará otro despegue.")
            return
        }

        takeoffRunning = true
        forwardTestCompleted = false
        takeoffButton.isEnabled = false
        autoTestButton.isEnabled = false
        testForwardButton.isEnabled = false
        testOneMeterButton.isEnabled = false
        returnButton.isEnabled = false

        showVirtualStickStatus("DESPEGUE AUTOMÁTICO\n\nSubiendo aproximadamente a 1 m...")

        telemetryLogger?.log(
            event = "TAKEOFF_START",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = currentHorizontalSpeed(),
            heading = heading,
            battery = batteryPercent,
            details = "Despegue automático solicitado"
        )
        logI(TAG, "TAKEOFF_START solicitado")

        val fc = flightController
        if (fc == null) {
            takeoffRunning = false
            takeoffButton.isEnabled = virtualStickEnabled
            autoTestButton.isEnabled = virtualStickEnabled
            showVirtualStickStatus("ERROR\nFlightController no disponible")
            logE(TAG, "TAKEOFF: FlightController no disponible")
            return
        }

        fc.startTakeoff(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                runOnUiThread {
                    if (error != null) {
                        takeoffRunning = false
                        takeoffButton.isEnabled = virtualStickEnabled
                        autoTestButton.isEnabled = virtualStickEnabled
                        logE(TAG, "TAKEOFF ERROR: class=${error.javaClass.name}, description=${error.description}, error=$error")
                        showVirtualStickStatus("ERROR DE DESPEGUE\n\n${error.description}")
                        telemetryLogger?.log(
                            event = "TAKEOFF_ERROR",
                            latitude = location?.latitude,
                            longitude = location?.longitude,
                            altitude = location?.altitude?.toDouble(),
                            speed = currentHorizontalSpeed(),
                            heading = heading,
                            battery = batteryPercent,
                            details = error.description
                        )
                        return@runOnUiThread
                    }
                    showVirtualStickStatus("DESPEGANDO...\n\nEsperando aproximadamente 1 m")
                    waitForTakeoffAltitude()
                }
            }
        })
    }

    private fun waitForTakeoffAltitude() {
        val startTime = System.currentTimeMillis()

        val altitudeChecker = object : Runnable {
            override fun run() {
                if (!takeoffRunning) return
                val altitude = location?.altitude
                val elapsed = System.currentTimeMillis() - startTime

                logI(TAG, "TAKEOFF CHECK: altitude=$altitude flying=$isFlying")

                if (isFlying && altitude != null && altitude >= TAKEOFF_TARGET_ALTITUDE) {
                    finishAutomaticTakeoff(false)
                    return
                }
                if (elapsed >= TAKEOFF_TIMEOUT_MS) {
                    takeoffRunning = false
                    sendFlightCommand(0f)
                    takeoffButton.isEnabled = virtualStickEnabled
                    autoTestButton.isEnabled = virtualStickEnabled
                    showVirtualStickStatus(
                        "TIMEOUT DESPEGUE\n\nNo se confirmó altura de 1 m.\nComando horizontal = 0"
                    )
                    logE(TAG, "TAKEOFF TIMEOUT: no se alcanzó $TAKEOFF_TARGET_ALTITUDE m en ${TAKEOFF_TIMEOUT_MS} ms (alt=$altitude)")
                    telemetryLogger?.log(
                        event = "TAKEOFF_TIMEOUT",
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        altitude = location?.altitude?.toDouble(),
                        speed = currentHorizontalSpeed(),
                        heading = heading,
                        battery = batteryPercent,
                        details = "No se alcanzó la altura objetivo"
                    )
                    return
                }
                flightHandler.postDelayed(this, 100L)
            }
        }
        flightHandler.post(altitudeChecker)
    }

    private fun finishAutomaticTakeoff(startForwardAfterStabilization: Boolean) {
        showVirtualStickStatus(
            "ALTURA ALCANZADA\n\n" +
                    "Altitud: ${location?.altitude?.let { "%.2f m".format(it) } ?: "--"}\n\n" +
                    "Estabilizando..."
        )
        logI(TAG, "TAKEOFF ALTURA ALCANZADA: ${location?.altitude}")
        telemetryLogger?.log(
            event = "TAKEOFF_ALTITUDE_REACHED",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = currentHorizontalSpeed(),
            heading = heading,
            battery = batteryPercent,
            details = "Altura objetivo alcanzada"
        )

        flightHandler.postDelayed({
            if (!takeoffRunning) return@postDelayed
            takeoffRunning = false
            sendFlightCommand(0f)
            telemetryLogger?.log(
                event = "TAKEOFF_STABILIZED",
                latitude = location?.latitude,
                longitude = location?.longitude,
                altitude = location?.altitude?.toDouble(),
                speed = currentHorizontalSpeed(),
                heading = heading,
                battery = batteryPercent,
                details = "Dron estabilizado aproximadamente a 1 m"
            )
            logI(TAG, "TAKEOFF ESTABILIZADO")

            if (startForwardAfterStabilization) {
                showVirtualStickStatus("DESPEGUE COMPLETADO\n\nAltura aproximada: 1 m\nIniciando prueba adelante...")
                startShortForwardTest()
            } else {
                takeoffButton.isEnabled = true
                autoTestButton.isEnabled = true
                testForwardButton.isEnabled = true
                testOneMeterButton.isEnabled = true
                showVirtualStickStatus("DRON ESTABILIZADO\n\nAltura aproximada: 1 m\n\nListo para la prueba.")
            }
        }, TAKEOFF_STABILIZATION_MS)
    }

    // =========================================================
    // PRUEBA AUTOMÁTICA
    // =========================================================

    private fun startAutomaticTest() {
        if (takeoffRunning || testRunning) {
            Toast.makeText(this, "Ya hay una prueba ejecutándose", Toast.LENGTH_SHORT).show()
            return
        }
        if (!virtualStickEnabled) {
            showVirtualStickStatus("Primero activa Virtual Stick")
            return
        }
        if (isFlying) {
            showVirtualStickStatus("EL DRON YA ESTÁ VOLANDO\n\nUsa la prueba adelante directamente.")
            return
        }

        takeoffRunning = true
        forwardTestCompleted = false
        takeoffButton.isEnabled = false
        autoTestButton.isEnabled = false
        testForwardButton.isEnabled = false
        testOneMeterButton.isEnabled = false
        returnButton.isEnabled = false

        showVirtualStickStatus(
            "PRUEBA AUTOMÁTICA\n\n1. Despegue → 1 m\n2. Estabilización\n3. Avance 1 s\n4. STOP"
        )
        telemetryLogger?.log(
            event = "AUTO_TEST_START",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = currentHorizontalSpeed(),
            heading = heading,
            battery = batteryPercent,
            details = "Takeoff -> 1m -> forward 1s -> stop"
        )
        logI(TAG, "AUTO_TEST_START")

        val fc = flightController
        if (fc == null) {
            takeoffRunning = false
            takeoffButton.isEnabled = virtualStickEnabled
            autoTestButton.isEnabled = virtualStickEnabled
            showVirtualStickStatus("ERROR\nFlightController no disponible")
            logE(TAG, "AUTO_TEST: FlightController no disponible")
            return
        }

        fc.startTakeoff(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                runOnUiThread {
                    if (error != null) {
                        takeoffRunning = false
                        takeoffButton.isEnabled = virtualStickEnabled
                        autoTestButton.isEnabled = virtualStickEnabled
                        logE(TAG, "AUTO TEST TAKEOFF ERROR: class=${error.javaClass.name}, description=${error.description}, error=$error")
                        showVirtualStickStatus("ERROR DE PRUEBA AUTOMÁTICA\n\n${error.description}")
                        return@runOnUiThread
                    }
                    showVirtualStickStatus("PRUEBA AUTOMÁTICA\n\nDespegando...")
                    waitForAutomaticTestAltitude()
                }
            }
        })
    }

    private fun waitForAutomaticTestAltitude() {
        val startTime = System.currentTimeMillis()

        val checker = object : Runnable {
            override fun run() {
                if (!takeoffRunning) return
                val altitude = location?.altitude
                val elapsed = System.currentTimeMillis() - startTime

                if (isFlying && altitude != null && altitude >= TAKEOFF_TARGET_ALTITUDE) {
                    showVirtualStickStatus("PRUEBA AUTOMÁTICA\n\nAltura ≈ 1 m\nEstabilizando...")
                    logI(TAG, "AUTO_TEST altura alcanzada: $altitude")
                    flightHandler.postDelayed({
                        if (!takeoffRunning) return@postDelayed
                        takeoffRunning = false
                        sendFlightCommand(0f)
                        startShortForwardTest()
                    }, TAKEOFF_STABILIZATION_MS)
                    return
                }
                if (elapsed >= TAKEOFF_TIMEOUT_MS) {
                    takeoffRunning = false
                    sendFlightCommand(0f)
                    takeoffButton.isEnabled = virtualStickEnabled
                    autoTestButton.isEnabled = virtualStickEnabled
                    showVirtualStickStatus("TIMEOUT PRUEBA AUTOMÁTICA\n\nNo se confirmó altura de 1 m.")
                    logE(TAG, "AUTO_TEST TIMEOUT: alt=$altitude en ${elapsed}ms")
                    return
                }
                flightHandler.postDelayed(this, 100L)
            }
        }
        flightHandler.post(checker)
    }

    // =========================================================
    // PRUEBA ADELANTE 1 SEGUNDO
    // =========================================================

    private fun startShortForwardTest() {
        if (testRunning || takeoffRunning) return
        if (!virtualStickEnabled) {
            showVirtualStickStatus("Primero activa Virtual Stick")
            return
        }
        if (!isFlying) {
            showVirtualStickStatus("PRUEBA BLOQUEADA\n\nEl dron no está volando.")
            logW(TAG, "FORWARD_TEST bloqueado: no está volando")
            return
        }

        val altitude = location?.altitude
        if (altitude == null || altitude < 0.7) {
            showVirtualStickStatus("PRUEBA BLOQUEADA\n\nPrimero alcanza aproximadamente 1 m.")
            logW(TAG, "FORWARD_TEST bloqueado: alt=$altitude < 0.7")
            return
        }

        testRunning = true
        forwardTestCompleted = false
        testForwardButton.isEnabled = false
        testOneMeterButton.isEnabled = false
        takeoffButton.isEnabled = false
        autoTestButton.isEnabled = false
        returnButton.isEnabled = false

        showVirtualStickStatus("PRUEBA ADELANTE\n\nAvanzando durante 1 segundo...")
        telemetryLogger?.log(
            event = "FORWARD_TEST_START",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = currentHorizontalSpeed(),
            heading = heading,
            battery = batteryPercent,
            details = "Forward 0.25 m/s durante 1 s"
        )
        logI(TAG, "FORWARD_TEST_START a ${TEST_SPEED} m/s durante ${FORWARD_TEST_DURATION_MS}ms")

        currentPitchCommand = TEST_SPEED
        flightHandler.removeCallbacks(virtualStickSender)
        flightHandler.post(virtualStickSender)

        flightHandler.postDelayed({
            if (!testRunning) return@postDelayed
            currentPitchCommand = 0f
            sendFlightCommand(0f)
            testRunning = false
            flightHandler.removeCallbacks(virtualStickSender)
            forwardTestCompleted = true
            testForwardButton.isEnabled = true
            testOneMeterButton.isEnabled = true
            takeoffButton.isEnabled = true
            autoTestButton.isEnabled = true
            returnButton.isEnabled = true
            showVirtualStickStatus(
                "PRUEBA FINALIZADA\n\nAvance: ~0.25 m teórico\nSTOP enviado\n\nPuedes mantenerlo aquí o pulsar REGRESAR."
            )
            logI(TAG, "FORWARD_TEST_FINISHED")
            telemetryLogger?.log(
                event = "FORWARD_TEST_FINISHED",
                latitude = location?.latitude,
                longitude = location?.longitude,
                altitude = location?.altitude?.toDouble(),
                speed = currentHorizontalSpeed(),
                heading = heading,
                battery = batteryPercent,
                details = "Prueba adelante finalizada"
            )
        }, FORWARD_TEST_DURATION_MS)
    }

    // =========================================================
    // STOP / HOVER
    // =========================================================

    private fun hoverStop() {
        currentPitchCommand = 0f
        testRunning = false
        takeoffRunning = false
        flightHandler.removeCallbacksAndMessages(null)
        sendFlightCommand(0f, 0f)
        showVirtualStickStatus("STOP / HOVER\n\nComando horizontal = 0\nManteniendo la posición actual.")
        logI(TAG, "HOVER_STOP")
        telemetryLogger?.log(
            event = "HOVER_STOP",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = currentHorizontalSpeed(),
            heading = heading,
            battery = batteryPercent,
            details = "STOP: mantener posición"
        )
        if (virtualStickEnabled) {
            takeoffButton.isEnabled = !isFlying
            autoTestButton.isEnabled = !isFlying
            testForwardButton.isEnabled = isFlying
            testOneMeterButton.isEnabled = isFlying
            returnButton.isEnabled = forwardTestCompleted && isFlying
        }
    }

    // =========================================================
    // REGRESAR
    // =========================================================

    private fun returnLastForwardMovement() {
        if (testRunning || takeoffRunning) {
            Toast.makeText(this, "Primero termina o detén la prueba actual", Toast.LENGTH_LONG).show()
            return
        }
        if (!virtualStickEnabled || !isFlying) {
            showVirtualStickStatus("REGRESO BLOQUEADO\n\nEl dron debe estar volando con Virtual Stick activo.")
            return
        }
        if (!forwardTestCompleted) {
            showVirtualStickStatus("REGRESO BLOQUEADO\n\nPrimero ejecuta la prueba adelante.")
            return
        }

        testRunning = true
        returnButton.isEnabled = false
        testForwardButton.isEnabled = false
        testOneMeterButton.isEnabled = false
        takeoffButton.isEnabled = false
        autoTestButton.isEnabled = false

        showVirtualStickStatus("REGRESANDO\n\nInvirtiendo el último movimiento durante 1 s...")
        telemetryLogger?.log(
            event = "RETURN_START",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = currentHorizontalSpeed(),
            heading = heading,
            battery = batteryPercent,
            details = "Regreso por inversión del comando durante 1 s"
        )
        logI(TAG, "RETURN_START")

        currentPitchCommand = -TEST_SPEED
        flightHandler.removeCallbacks(virtualStickSender)
        flightHandler.post(virtualStickSender)

        flightHandler.postDelayed({
            currentPitchCommand = 0f
            sendFlightCommand(0f)
            testRunning = false
            forwardTestCompleted = false
            flightHandler.removeCallbacks(virtualStickSender)
            testForwardButton.isEnabled = true
            testOneMeterButton.isEnabled = true
            takeoffButton.isEnabled = false
            autoTestButton.isEnabled = false
            returnButton.isEnabled = false
            showVirtualStickStatus("REGRESO FINALIZADO\n\nSTOP enviado.\nEl dron queda en hover.")
            logI(TAG, "RETURN_FINISHED")
            telemetryLogger?.log(
                event = "RETURN_FINISHED",
                latitude = location?.latitude,
                longitude = location?.longitude,
                altitude = location?.altitude?.toDouble(),
                speed = currentHorizontalSpeed(),
                heading = heading,
                battery = batteryPercent,
                details = "Regreso finalizado"
            )
        }, FORWARD_TEST_DURATION_MS)
    }

    private fun configureVirtualStick() {
        val fc = flightController ?: return
        fc.rollPitchControlMode = RollPitchControlMode.VELOCITY
        fc.verticalControlMode = VerticalControlMode.VELOCITY
        fc.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        fc.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        logI(TAG, "VirtualStick configurado: RollPitch=VELOCITY, Vertical=VELOCITY, Yaw=ANGULAR_VELOCITY, Coord=BODY")
    }

    private fun sendFlightCommand(pitch: Float, roll: Float = 0f) {
        val fc = flightController ?: return
        if (!virtualStickEnabled) return

        val data = FlightControlData(pitch, roll, 0f, 0f)

        fc.sendVirtualStickFlightControlData(data, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error != null) {
                    logE(TAG, "VirtualStick: ${error.description}")
                }
            }
        })
    }

    private var currentPitchCommand = 0f

    private val virtualStickSender = object : Runnable {
        override fun run() {
            if (!testRunning) return
            sendFlightCommand(pitch = currentPitchCommand)
            flightHandler.postDelayed(this, COMMAND_INTERVAL_MS)
        }
    }

    private fun startOneMeterTest() {
        if (testRunning) {
            Toast.makeText(this, "Ya hay una prueba ejecutándose", Toast.LENGTH_SHORT).show()
            return
        }
        if (!virtualStickEnabled) {
            Toast.makeText(this, "Primero activa Virtual Stick", Toast.LENGTH_LONG).show()
            return
        }

        val currentLocation = location
        if (currentLocation == null) {
            Toast.makeText(this, "Esperando telemetría...", Toast.LENGTH_LONG).show()
            logW(TAG, "TEST A→1m→A bloqueada: sin ubicación aún")
            return
        }

        testStartLat = currentLocation.latitude
        testStartLon = currentLocation.longitude
        testRunning = true
        testOneMeterButton.isEnabled = false

        telemetryLogger?.log(
            event = "TEST_START",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = currentHorizontalSpeed(),
            heading = heading,
            battery = batteryPercent,
            details = "A -> 1m -> A; speed=$TEST_SPEED"
        )
        logI(TAG, "TEST_START A→1m→A")

        showVirtualStickStatus("PRUEBA INICIADA\n\nA → B\nAvanzando...")

        currentPitchCommand = TEST_SPEED
        flightHandler.removeCallbacks(virtualStickSender)
        flightHandler.post(virtualStickSender)

        flightHandler.postDelayed({
            if (!testRunning) return@postDelayed
            currentPitchCommand = 0f
            telemetryLogger?.log(
                event = "POINT_B",
                latitude = location?.latitude,
                longitude = location?.longitude,
                altitude = location?.altitude?.toDouble(),
                speed = currentHorizontalSpeed(),
                heading = heading,
                battery = batteryPercent,
                details = "Fin tramo A -> B"
            )
            logI(TAG, "POINT_B alcanzado")
            showVirtualStickStatus("PUNTO B\n\nRegresando B → A...")
            currentPitchCommand = -TEST_SPEED

            flightHandler.postDelayed({
                if (!testRunning) return@postDelayed
                finishOneMeterTest()
            }, LEG_DURATION_MS)
        }, LEG_DURATION_MS)
    }

    private fun finishOneMeterTest() {
        currentPitchCommand = 0f
        sendFlightCommand(0f)
        testRunning = false
        flightHandler.removeCallbacks(virtualStickSender)

        telemetryLogger?.log(
            event = "TEST_FINISHED",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = currentHorizontalSpeed(),
            heading = heading,
            battery = batteryPercent,
            details = "A -> B -> A finalizado"
        )
        logI(TAG, "TEST_FINISHED A→B→A")
        testOneMeterButton.isEnabled = true
        showVirtualStickStatus("PRUEBA FINALIZADA\n\nA → B → A\nComando: ~1 m + ~1 m\nDron detenido")
    }

    private fun emergencyStop() {
        currentPitchCommand = 0f
        testRunning = false
        takeoffRunning = false
        flightHandler.removeCallbacksAndMessages(null)

        repeat(5) {
            flightHandler.postDelayed({
                sendFlightCommand(pitch = 0f, roll = 0f)
            }, it * 100L)
        }

        telemetryLogger?.log(
            event = "MANUAL_STOP",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = currentHorizontalSpeed(),
            heading = heading,
            battery = batteryPercent,
            details = "STOP solicitado por usuario"
        )
        logW(TAG, "MANUAL_STOP solicitado por usuario")

        testOneMeterButton.isEnabled = virtualStickEnabled && isFlying
        testForwardButton.isEnabled = virtualStickEnabled && isFlying
        returnButton.isEnabled = virtualStickEnabled && isFlying && forwardTestCompleted
        takeoffButton.isEnabled = virtualStickEnabled && !isFlying
        autoTestButton.isEnabled = virtualStickEnabled && !isFlying

        showVirtualStickStatus("STOP\n\nComando horizontal = 0\nEl dron queda en hover donde está.")
    }

    private fun currentHorizontalSpeed(): Double? {
        val vx = velocityX
        val vy = velocityY
        if (vx == null || vy == null) return null
        return kotlin.math.sqrt(vx * vx + vy * vy).toDouble()
    }

    private fun showVirtualStickStatus(message: String) {
        runOnUiThread {
            virtualStickText.text = "PRUEBA VIRTUAL STICK\n\n$message"
        }
    }
}