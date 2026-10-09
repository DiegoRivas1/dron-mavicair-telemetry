
package com.diegorivas.drone

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelemetryLogger(context: Context) {

    private val file: File

    init {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val directory = File(
            context.getExternalFilesDir(null),
            "telemetry"
        )

        if (!directory.exists()) {
            directory.mkdirs()
        }

        file = File(directory, "flight_$timestamp.csv")

        FileWriter(file, false).use { writer ->
            writer.append(
                "timestamp,event,latitude,longitude," +
                        "altitude_m,speed_mps,heading_deg,battery_pct,details\n"
            )
        }
    }

    @Synchronized
    fun log(
        event: String,
        latitude: Double?,
        longitude: Double?,
        altitude: Double?,
        speed: Double?,
        heading: Float?,
        battery: Int?,
        details: String = ""
    ) {
        FileWriter(file, true).use { writer ->
            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                Locale.US
            ).format(Date())

            val values = listOf(
                timestamp,
                event,
                latitude?.toString() ?: "",
                longitude?.toString() ?: "",
                altitude?.toString() ?: "",
                speed?.toString() ?: "",
                heading?.toString() ?: "",
                battery?.toString() ?: "",
                details
            )

            writer.append(
                values.joinToString(",") {
                    "\"${it.replace("\"", "\"\"")}\""
                }
            )
            writer.append("\n")
        }
    }

    fun getFile(): File = file
}