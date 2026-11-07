package com.diegorivas.drone.dji

import android.content.Context
import android.os.Handler
import android.os.Looper
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.flightcontroller.FlightController
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager

/**
 * Registro de la app en DJI y conexión/desconexión del producto.
 * Lógica extraída tal cual del MainActivity anterior (registerApp → startConnectionToProduct).
 *
 * Todas las llamadas al Listener se hacen en el HILO PRINCIPAL.
 */
class DjiConnectionManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onRegistrationResult(success: Boolean, message: String)
        fun onProductConnected(model: String?, flightControllerAvailable: Boolean)
        fun onProductDisconnected()
        fun onFlightControllerChanged(available: Boolean)
        fun onLog(level: Char, message: String)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedFlightController: FlightController? = null

    @Volatile
    var connectedModel: String? = null
        private set

    /** FlightController actual, con el mismo fallback que usaba el MainActivity anterior. */
    val flightController: FlightController?
        get() {
            val fc = cachedFlightController
                ?: (DJISDKManager.getInstance().product as? Aircraft)?.flightController
            if (cachedFlightController == null && fc != null) cachedFlightController = fc
            return fc
        }

    val isConnected: Boolean
        get() = DJISDKManager.getInstance().product?.isConnected == true

    private var retryCount = 0
    private val maxRetries = 3

    fun register() {
        listener.onLog('I', "Registrando app con DJI (intento ${retryCount + 1})...")
        DJISDKManager.getInstance().registerApp(
            context.applicationContext,
            object : DJISDKManager.SDKManagerCallback {

                override fun onRegister(error: DJIError?) {
                    if (error == DJISDKError.REGISTRATION_SUCCESS) {
                        retryCount = 0
                        post { listener.onRegistrationResult(true, "REGISTRATION SUCCESS") }
                        DJISDKManager.getInstance().startConnectionToProduct()
                    } else {
                        if (retryCount < maxRetries) {
                            retryCount++
                            val delay = 3000L * retryCount
                            post { listener.onRegistrationResult(false, "Reintentando en ${delay/1000}s...") }
                            main.postDelayed({ register() }, delay)
                        } else {
                            retryCount = 0
                            post { listener.onRegistrationResult(false, "Fallo tras $maxRetries intentos: ${error?.description}") }
                        }
                    }
                }

                override fun onProductDisconnect() {
                    cachedFlightController = null
                    connectedModel = null
                    post { listener.onProductDisconnected() }
                }

                override fun onProductConnect(product: BaseProduct?) {
                    if (product is Aircraft) {
                        cachedFlightController = product.flightController
                    }
                    if (cachedFlightController == null) {
                        cachedFlightController =
                            (DJISDKManager.getInstance().product as? Aircraft)?.flightController
                    }
                    connectedModel = product?.model?.toString()
                    val available = cachedFlightController != null
                    post { listener.onProductConnected(connectedModel, available) }
                }

                override fun onProductChanged(product: BaseProduct?) { }

                override fun onComponentChange(
                    key: BaseProduct.ComponentKey?,
                    oldComponent: BaseComponent?,
                    newComponent: BaseComponent?
                ) {
                    if (key == BaseProduct.ComponentKey.FLIGHT_CONTROLLER) {
                        cachedFlightController = newComponent as? FlightController
                        val available = cachedFlightController != null
                        post { listener.onFlightControllerChanged(available) }
                    }
                }

                override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                    post { listener.onLog('I', "onInitProcess: $event ($totalProcess%)") }
                }

                override fun onDatabaseDownloadProgress(current: Long, total: Long) { }
            }
        )
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }
}
