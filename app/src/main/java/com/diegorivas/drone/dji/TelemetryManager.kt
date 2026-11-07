package com.diegorivas.drone.dji

import android.os.Handler
import android.os.Looper
import com.diegorivas.drone.data.model.TelemetryData
import dji.common.error.DJIError
import dji.common.flightcontroller.LocationCoordinate3D
import dji.keysdk.BatteryKey
import dji.keysdk.DJIKey
import dji.keysdk.FlightControllerKey
import dji.keysdk.KeyManager
import dji.keysdk.callback.GetCallback
import dji.keysdk.callback.KeyListener

/**
 * Telemetría del DRON vía KeyManager (mismas keys que el MainActivity anterior,
 * más satélites, señal GPS, motores y modo de vuelo para diagnosticar despegues).
 *
 * El estado se actualiza SIEMPRE en el hilo principal y se notifica con onUpdate.
 */
class TelemetryManager(
    private val onUpdate: (TelemetryData) -> Unit,
    private val onLog: (Char, String) -> Unit = { _, _ -> }
) {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<KeyListener>()

    /** Última telemetría conocida (leer sólo desde el hilo principal). */
    var current: TelemetryData = TelemetryData()
        private set

    fun start() {
        stop() // evita listeners duplicados si el dron se reconecta

        watch(BatteryKey.create(BatteryKey.CHARGE_REMAINING_IN_PERCENT), "batería") { v ->
            current.copy(batteryPercent = v as? Int)
        }
        watch(FlightControllerKey.create(FlightControllerKey.AIRCRAFT_LOCATION), "ubicación") { v ->
            val loc = v as? LocationCoordinate3D
            current.copy(
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                altitude = loc?.altitude
            )
        }
        watch(FlightControllerKey.create(FlightControllerKey.VELOCITY_X), null) { v ->
            current.copy(velocityX = v as? Float)
        }
        watch(FlightControllerKey.create(FlightControllerKey.VELOCITY_Y), null) { v ->
            current.copy(velocityY = v as? Float)
        }
        watch(FlightControllerKey.create(FlightControllerKey.VELOCITY_Z), null) { v ->
            current.copy(velocityZ = v as? Float)
        }
        watch(FlightControllerKey.create(FlightControllerKey.COMPASS_HEADING), null) { v ->
            current.copy(heading = v as? Float)
        }
        watch(FlightControllerKey.create(FlightControllerKey.IS_FLYING), "IS_FLYING") { v ->
            val flying = v as? Boolean ?: false
            if (flying != current.isFlying) onLog('I', "IS_FLYING = $flying")
            current.copy(isFlying = flying)
        }
        watch(FlightControllerKey.create(FlightControllerKey.ARE_MOTOR_ON), null) { v ->
            current.copy(areMotorsOn = v as? Boolean ?: false)
        }
        watch(FlightControllerKey.create(FlightControllerKey.SATELLITE_COUNT), null) { v ->
            current.copy(satelliteCount = v as? Int)
        }
        watch(FlightControllerKey.create(FlightControllerKey.GPS_SIGNAL_LEVEL), null) { v ->
            current.copy(gpsSignalLevel = gpsLevelToInt(v))
        }
        watch(FlightControllerKey.create(FlightControllerKey.FLIGHT_MODE_STRING), null) { v ->
            current.copy(flightMode = v as? String)
        }
    }

    fun stop() {
        val km = KeyManager.getInstance() ?: return
        listeners.forEach { km.removeListener(it) }
        listeners.clear()
    }

    /**
     * Lee el valor inicial y se suscribe a cambios.
     * errorLabel != null → registra en el log si falla la lectura inicial.
     */
    private fun watch(key: DJIKey, errorLabel: String?, apply: (Any?) -> TelemetryData) {
        val km = KeyManager.getInstance() ?: return

        km.getValue(key, object : GetCallback {
            override fun onSuccess(value: Any) {
                main.post { publish(apply(value)) }
            }

            override fun onFailure(error: DJIError) {
                if (errorLabel != null) {
                    main.post { onLog('E', "Error leyendo $errorLabel: ${error.description}") }
                }
            }
        })

        val listener = object : KeyListener {
            override fun onValueChange(oldValue: Any?, newValue: Any?) {
                main.post { publish(apply(newValue)) }
            }
        }
        km.addListener(key, listener)
        listeners.add(listener)
    }

    private fun publish(data: TelemetryData) {
        current = data.copy(timestampMs = System.currentTimeMillis())
        onUpdate(current)
    }

    /** GPSSignalLevel es un enum LEVEL_0..LEVEL_5 / NONE; se convierte por nombre. */
    private fun gpsLevelToInt(v: Any?): Int? {
        val name = (v as? Enum<*>)?.name ?: return null
        return name.removePrefix("LEVEL_").toIntOrNull() ?: 0
    }
}
