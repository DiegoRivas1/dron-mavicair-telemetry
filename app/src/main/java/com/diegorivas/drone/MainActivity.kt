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
import dji.keysdk.BatteryKey
import dji.keysdk.DJIKey
import dji.keysdk.FlightControllerKey
import dji.keysdk.KeyManager
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
import dji.keysdk.callback.GetCallback
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
    private lateinit var videoSurface: TextureView
    private var codecManager: DJICodecManager? = null

    // ---------- Estado de telemetría ----------
    private var batteryPercent: Int? = null
    private var location: LocationCoordinate3D? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        telemetryText = findViewById(R.id.telemetryText)
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
                        runOnUiThread { updateStatus("REGISTRATION SUCCESS") }
                        Log.i(TAG, "REGISTRATION SUCCESS")
                        DJISDKManager.getInstance().startConnectionToProduct()
                    } else {
                        runOnUiThread { updateStatus("Registro fallido: ${error?.description}") }
                        Log.e(TAG, "Registro fallido: ${error?.description}")
                    }
                }

                override fun onProductDisconnect() {
                    runOnUiThread { updateStatus("PRODUCT DISCONNECTED") }
                }

                override fun onProductConnect(product: BaseProduct?) {
                    val model: Model? = product?.model
                    runOnUiThread { updateStatus("DRONE CONNECTED: $model") }
                    Log.i(TAG, "DRONE CONNECTED: $model")
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

                override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                    Log.i(TAG, "onInitProcess: $event ($totalProcess%)")
                }

                override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                    // No-op por ahora
                }
            }
        )
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    // ---------- Telemetría (batería, GPS, altitud) ----------

    private fun startTelemetryListeners() {
        val batteryKey: DJIKey = BatteryKey.create(BatteryKey.CHARGE_REMAINING_IN_PERCENT)
        val locationKey: DJIKey = FlightControllerKey.create(FlightControllerKey.AIRCRAFT_LOCATION)

        // Pide el valor actual de una vez (addListener solo avisa de cambios futuros)
        KeyManager.getInstance()?.getValue(batteryKey, object : GetCallback {
            override fun onSuccess(value: Any) {
                batteryPercent = value as? Int
                runOnUiThread { updateTelemetryText() }
            }
            override fun onFailure(error: DJIError) {
                Log.e(TAG, "Error leyendo batería: ${error.description}")
            }
        })

        KeyManager.getInstance()?.getValue(locationKey, object : GetCallback {
            override fun onSuccess(value: Any) {
                location = value as? LocationCoordinate3D
                runOnUiThread { updateTelemetryText() }
            }
            override fun onFailure(error: DJIError) {
                Log.e(TAG, "Error leyendo ubicación: ${error.description}")
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
    }

    private fun updateTelemetryText() {
        val bat = batteryPercent?.let { "$it%" } ?: "--"
        val lat = location?.latitude?.let { "%.5f".format(it) } ?: "--"
        val lon = location?.longitude?.let { "%.5f".format(it) } ?: "--"
        val alt = location?.altitude?.let { "%.1f m".format(it) } ?: "--"
        telemetryText.text = "BAT: $bat\nLAT: $lat  LON: $lon\nALT: $alt"
    }

    // ---------- Video en vivo (VideoFeeder + DJICodecManager) ----------

    private val videoDataListener =
        VideoFeeder.VideoDataListener { videoBuffer, size ->
            codecManager?.sendDataToDecoder(videoBuffer, size)
        }

    private fun startVideoFeed() {
        VideoFeeder.getInstance()
            ?.primaryVideoFeed
            ?.addVideoDataListener(videoDataListener)
    }

    private fun stopVideoFeed() {
        VideoFeeder.getInstance()
            ?.primaryVideoFeed
            ?.removeVideoDataListener(videoDataListener)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (codecManager == null) {
            codecManager = DJICodecManager(this, surface, width, height)
        }
        startVideoFeed()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // No-op
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopVideoFeed()
        codecManager?.cleanSurface()
        codecManager?.destroyCodec()
        codecManager = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // No-op
    }

    override fun onDestroy() {
        stopVideoFeed()
        codecManager?.destroyCodec()
        super.onDestroy()
    }
}