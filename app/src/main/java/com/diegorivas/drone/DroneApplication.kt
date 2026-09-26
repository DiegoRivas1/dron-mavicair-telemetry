package com.diegorivas.drone

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper

/**
 * Application requerida por dji-sdk 4.18.
 *
 * El SDK 4.18 usa un mecanismo propio de carga/protección de clases
 * (Helper.install) que debe ejecutarse ANTES de tocar cualquier clase
 * de DJISDKManager. Si esto falta, aparece:
 *   NoClassDefFoundError: dji/sdk/sdkmanager/DJISDKManager
 */
class DroneApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
    }
}
