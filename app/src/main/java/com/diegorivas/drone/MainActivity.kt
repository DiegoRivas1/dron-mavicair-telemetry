package com.diegorivas.drone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.flightcontroller.LocationCoordinate3D
import dji.common.product.Model
import dji.keysdk.AirLinkKey
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
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager

// NOTA: los nombres exactos de métodos de DJICodecManager (cleanSurface, destroyCodec,
// sendDataToDecoder) y de las keys de BatteryKey/FlightControllerKey pueden variar
// levemente entre builds del SDK 4.18. Si Android Studio marca algo en rojo, compara
// contra el sample code de DJI (VideoFeederView.java, BatteryView / FlightControllerView
// en Mobile-SDK-Android-4.18/Sample Code/.../demo/) y ajusta solo esa línea.
import dji.logic.manager.DJIUSBWifiSwitchManager
import dji.sdk.airlink.WiFiLink


class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "MavicAirTelemetry"

        // Permisos que dji-sdk necesita para registrarse y conectarse
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
    }

    private lateinit var statusText: TextView
    private lateinit var telemetryText: TextView
    private lateinit var airLinkText: TextView
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

    // ---------- Estado AirLink ----------
    private var downlinkSignalQuality: Any? = null
    private var channelInterference: Any? = null
    private var dataRate: Any? = null
    private var channelNumber: Any? = null
    private var bandwidth: Any? = null
    private var dynamicDataRate: Any? = null



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        telemetryLogger = TelemetryLogger(this)

        statusText = findViewById(R.id.statusText)
        telemetryText = findViewById(R.id.telemetryText)
        airLinkText = findViewById(R.id.airLinkText)
        videoSurface = findViewById(R.id.videoSurface)

        videoSurface.surfaceTextureListener = this

        if (hasAllPermissions()) {
            registerDjiApp()
        } else {
            requestMissingPermissions()
        }
    }

    // ---------- Permisos ----------

    private fun hasAllPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestMissingPermissions() {
        ActivityCompat.requestPermissions(
            this,
            REQUIRED_PERMISSIONS,
            REQUEST_CODE_PERMISSIONS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_CODE_PERMISSIONS) {

            if (hasAllPermissions()) {

                registerDjiApp()

            } else {

                Toast.makeText(
                    this,
                    "Se necesitan todos los permisos para conectar con el dron",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------- Registro y conexión DJI ----------

    private fun registerDjiApp() {

        updateStatus("Registrando app con DJI...")

        DJISDKManager.getInstance().registerApp(
            applicationContext,
            object : DJISDKManager.SDKManagerCallback {

                override fun onRegister(error: DJIError?) {

                    if (error == DJISDKError.REGISTRATION_SUCCESS) {

                        runOnUiThread {
                            updateStatus("REGISTRATION SUCCESS")
                        }

                        Log.i(TAG, "REGISTRATION SUCCESS")

                        DJISDKManager
                            .getInstance()
                            .startConnectionToProduct()

                    } else {

                        runOnUiThread {
                            updateStatus(
                                "Registro fallido: ${error?.description}"
                            )
                        }

                        Log.e(
                            TAG,
                            "Registro fallido: ${error?.description}"
                        )
                    }
                }

                override fun onProductDisconnect() {

                    runOnUiThread {
                        updateStatus("PRODUCT DISCONNECTED")
                    }
                }

                override fun onProductConnect(product: BaseProduct?) {

                    val model: Model? = product?.model

                    runOnUiThread {
                        updateStatus("DRONE CONNECTED: $model")
                    }

                    Log.i(
                        TAG,
                        "DRONE CONNECTED: $model"
                    )

                    startTelemetryListeners()
                }

                override fun onProductChanged(product: BaseProduct?) {
                    // No-op por ahora
                }

                override fun onComponentChange(
                    key: BaseProduct.ComponentKey?,
                    oldComponent: BaseComponent?,
                    newComponent: BaseComponent?
                ) {
                    // No-op por ahora
                }

                override fun onInitProcess(
                    event: DJISDKInitEvent?,
                    totalProcess: Int
                ) {

                    Log.i(
                        TAG,
                        "onInitProcess: $event ($totalProcess%)"
                    )
                }

                override fun onDatabaseDownloadProgress(
                    current: Long,
                    total: Long
                ) {
                    // No-op por ahora
                }
            }
        )
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    // ---------- Diagnóstico AirLink ----------

    private fun testAirLinkKeys() {

        val keyManager = KeyManager.getInstance()

        // Calidad de señal descendente
        val downlinkKey =
            AirLinkKey.create(
                AirLinkKey.DOWNLINK_SIGNAL_QUALITY
            )

        // Interferencia del canal
        val interferenceKey =
            AirLinkKey.create(
                AirLinkKey.CHANNEL_INTERFERENCE
            )

        // Tasa de datos
        val dataRateKey =
            AirLinkKey.create(
                AirLinkKey.DATA_RATE
            )

        // Número de canal
        val channelKey =
            AirLinkKey.create(
                AirLinkKey.CHANNEL_NUMBER
            )

        // Ancho de banda
        val bandwidthKey =
            AirLinkKey.create(
                AirLinkKey.BANDWIDTH
            )

        // Tasa de datos dinámica
        val dynamicDataRateKey =
            AirLinkKey.create(
                AirLinkKey.DYNAMIC_DATA_RATE
            )

        // ---------- DOWNLINK SIGNAL QUALITY ----------

        keyManager?.getValue(
            downlinkKey,
            object : GetCallback {

                override fun onSuccess(value: Any) {

                    downlinkSignalQuality = value

                    updateAirLinkText()
                }

                override fun onFailure(error: DJIError) {

                    downlinkSignalQuality =
                        "ERROR: ${error.description}"

                    updateAirLinkText()
                }
            }
        )

        // ---------- CHANNEL INTERFERENCE ----------

        keyManager?.getValue(
            interferenceKey,
            object : GetCallback {

                override fun onSuccess(value: Any) {

                    channelInterference = value

                    updateAirLinkText()
                }

                override fun onFailure(error: DJIError) {

                    channelInterference =
                        "ERROR: ${error.description}"

                    updateAirLinkText()
                }
            }
        )

        // ---------- DATA RATE ----------

        keyManager?.getValue(
            dataRateKey,
            object : GetCallback {

                override fun onSuccess(value: Any) {

                    dataRate = value

                    updateAirLinkText()
                }

                override fun onFailure(error: DJIError) {

                    dataRate =
                        "ERROR: ${error.description}"

                    updateAirLinkText()
                }
            }
        )

        // ---------- CHANNEL NUMBER ----------

        keyManager?.getValue(
            channelKey,
            object : GetCallback {

                override fun onSuccess(value: Any) {

                    channelNumber = value

                    updateAirLinkText()
                }

                override fun onFailure(error: DJIError) {

                    channelNumber =
                        "ERROR: ${error.description}"

                    updateAirLinkText()
                }
            }
        )

        // ---------- BANDWIDTH ----------

        keyManager?.getValue(
            bandwidthKey,
            object : GetCallback {

                override fun onSuccess(value: Any) {

                    bandwidth = value

                    updateAirLinkText()
                }

                override fun onFailure(error: DJIError) {

                    bandwidth =
                        "ERROR: ${error.description}"

                    updateAirLinkText()
                }
            }
        )

        // ---------- DYNAMIC DATA RATE ----------

        keyManager?.getValue(
            dynamicDataRateKey,
            object : GetCallback {

                override fun onSuccess(value: Any) {

                    dynamicDataRate = value

                    updateAirLinkText()
                }

                override fun onFailure(error: DJIError) {

                    dynamicDataRate =
                        "ERROR: ${error.description}"

                    updateAirLinkText()
                }
            }
        )
    }

    private fun updateAirLinkText() {

        val text = """
            AIRLINK DIAGNOSTIC
            
            DOWNLINK SIGNAL QUALITY:
            ${downlinkSignalQuality ?: "--"}
            
            CHANNEL INTERFERENCE:
            ${channelInterference ?: "--"}
            
            DATA RATE:
            ${dataRate ?: "--"}
            
            CHANNEL:
            ${channelNumber ?: "--"}
            
            BANDWIDTH:
            ${bandwidth ?: "--"}
            
            DYNAMIC DATA RATE:
            ${dynamicDataRate ?: "--"}
        """.trimIndent()

        runOnUiThread {
            airLinkText.text = text
        }
    }

    // ---------- Telemetría (batería, GPS, altitud) ----------

    private fun startTelemetryListeners() {

        // ---------- Diagnóstico AirLink ----------
        testAirLinkKeys()

        //
        val batteryKey: DJIKey =
            BatteryKey.create(
                BatteryKey.CHARGE_REMAINING_IN_PERCENT
            )

        val locationKey: DJIKey =
            FlightControllerKey.create(
                FlightControllerKey.AIRCRAFT_LOCATION
            )

        // Pide el valor actual de una vez
        // (addListener solo avisa de cambios futuros)
        KeyManager.getInstance()?.getValue(
            batteryKey,
            object : GetCallback {

                override fun onSuccess(value: Any) {

                    batteryPercent = value as? Int

                    runOnUiThread {
                        updateTelemetryText()
                    }
                }

                override fun onFailure(error: DJIError) {

                    Log.e(
                        TAG,
                        "Error leyendo batería: ${error.description}"
                    )
                }
            }
        )

        KeyManager.getInstance()?.getValue(
            locationKey,
            object : GetCallback {

                override fun onSuccess(value: Any) {

                    location =
                        value as? LocationCoordinate3D

                    runOnUiThread {
                        updateTelemetryText()
                    }
                }

                override fun onFailure(error: DJIError) {

                    Log.e(
                        TAG,
                        "Error leyendo ubicación: ${error.description}"
                    )
                }
            }
        )

        KeyManager.getInstance()?.addListener(
            batteryKey,
            object : KeyListener {

                override fun onValueChange(
                    oldValue: Any?,
                    newValue: Any?
                ) {

                    batteryPercent =
                        newValue as? Int

                    runOnUiThread {
                        updateTelemetryText()
                    }
                }
            }
        )

        KeyManager.getInstance()?.addListener(
            locationKey,
            object : KeyListener {

                override fun onValueChange(
                    oldValue: Any?,
                    newValue: Any?
                ) {

                    location =
                        newValue as? LocationCoordinate3D

                    runOnUiThread {
                        updateTelemetryText()
                    }
                }
            }
        )

        // Telemetría extendida:
        // velocidad, heading, RSSI

        val velXKey: DJIKey =
            FlightControllerKey.create(
                FlightControllerKey.VELOCITY_X
            )

        val velYKey: DJIKey =
            FlightControllerKey.create(
                FlightControllerKey.VELOCITY_Y
            )

        val headingKey: DJIKey =
            FlightControllerKey.create(
                FlightControllerKey.COMPASS_HEADING
            )

        KeyManager.getInstance()?.addListener(
            velXKey,
            object : KeyListener {

                override fun onValueChange(
                    oldValue: Any?,
                    newValue: Any?
                ) {

                    velocityX =
                        newValue as? Float

                    runOnUiThread {
                        updateTelemetryText()
                    }
                }
            }
        )

        KeyManager.getInstance()?.addListener(
            velYKey,
            object : KeyListener {

                override fun onValueChange(
                    oldValue: Any?,
                    newValue: Any?
                ) {

                    velocityY =
                        newValue as? Float

                    runOnUiThread {
                        updateTelemetryText()
                    }
                }
            }
        )

        KeyManager.getInstance()?.addListener(
            headingKey,
            object : KeyListener {

                override fun onValueChange(
                    oldValue: Any?,
                    newValue: Any?
                ) {

                    heading =
                        newValue as? Float

                    runOnUiThread {
                        updateTelemetryText()
                    }
                }
            }
        )
    }

    private fun updateTelemetryText() {

        val bat =
            batteryPercent?.let {
                "$it%"
            } ?: "--"

        val lat =
            location?.latitude?.let {
                "%.5f".format(it)
            } ?: "--"

        val lon =
            location?.longitude?.let {
                "%.5f".format(it)
            } ?: "--"

        val alt =
            location?.altitude?.let {
                "%.1f m".format(it)
            } ?: "--"

        val speed =
            if (velocityX != null && velocityY != null) {

                "%.1f m/s".format(
                    kotlin.math.sqrt(
                        velocityX!! * velocityX!! +
                                velocityY!! * velocityY!!
                    )
                )

            } else {
                "--"
            }

        val head =
            heading?.let {
                "%.0f°".format(it)
            } ?: "--"

        //
        telemetryLogger?.log(
            event = "TELEMETRY",
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude?.toDouble(),
            speed = if (velocityX != null && velocityY != null) {
                kotlin.math.sqrt(
                    velocityX!! * velocityX!! +
                            velocityY!! * velocityY!!
                ).toDouble()
            } else {
                null
            },
            heading = heading,
            battery = batteryPercent
        )

        // Todavía no tenemos un RSSI confirmado.
        // No convertimos DOWNLINK_SIGNAL_QUALITY en RSSI
        // hasta conocer exactamente qué representa.
        val signal = "--"

        telemetryText.text =
            "BAT: $bat\n" +
                    "LAT: $lat  LON: $lon\n" +
                    "ALT: $alt\n" +
                    "VEL: $speed  HDG: $head\n" +
                    "RSSI: $signal"
    }

    // ---------- Video en vivo (VideoFeeder + DJICodecManager) ----------

    private val videoDataListener =
        VideoFeeder.VideoDataListener { videoBuffer, size ->

            codecManager?.sendDataToDecoder(
                videoBuffer,
                size
            )
        }

    private fun startVideoFeed() {

        VideoFeeder.getInstance()
            ?.primaryVideoFeed
            ?.addVideoDataListener(
                videoDataListener
            )
    }

    private fun stopVideoFeed() {

        VideoFeeder.getInstance()
            ?.primaryVideoFeed
            ?.removeVideoDataListener(
                videoDataListener
            )
    }

    override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int
    ) {

        if (codecManager == null) {

            codecManager =
                DJICodecManager(
                    this,
                    surface,
                    width,
                    height
                )
        }

        startVideoFeed()
    }

    override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int
    ) {
        // No-op
    }

    override fun onSurfaceTextureDestroyed(
        surface: SurfaceTexture
    ): Boolean {

        stopVideoFeed()

        codecManager?.cleanSurface()
        codecManager?.destroyCodec()

        codecManager = null

        return true
    }

    override fun onSurfaceTextureUpdated(
        surface: SurfaceTexture
    ) {
        // No-op
    }

    override fun onDestroy() {

        stopVideoFeed()

        codecManager?.destroyCodec()

        super.onDestroy()
    }
}