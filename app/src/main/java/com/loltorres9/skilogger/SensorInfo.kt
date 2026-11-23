package com.loltorres9.skilogger

import android.hardware.Sensor
import android.hardware.SensorManager
import org.json.JSONObject
import java.util.Locale

object SensorInfo {
    private const val G = 9.80665
    private val TYPES = listOf(
        Sensor.TYPE_ACCELEROMETER to "accelerometer",
        Sensor.TYPE_GYROSCOPE to "gyroscope",
        Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
        Sensor.TYPE_PRESSURE to "barometer",
    )

    fun toJson(sm: SensorManager): JSONObject {
        val o = JSONObject()
        for ((type, key) in TYPES) {
            val s = sm.getDefaultSensor(type)
            o.put(
                key,
                if (s == null) JSONObject.NULL else JSONObject()
                    .put("name", s.name)
                    .put("vendor", s.vendor)
                    .put("max_range", s.maximumRange.toDouble())
                    .put("resolution", s.resolution.toDouble())
                    .put("min_delay_us", s.minDelay)
                    .put("max_delay_us", s.maxDelay)
                    .put("fifo_max_events", s.fifoMaxEventCount)
            )
        }
        return o
    }

    fun describe(sm: SensorManager): String {
        val sb = StringBuilder()
        fun rate(s: Sensor) = if (s.minDelay > 0) String.format(Locale.GERMANY, "%.0f Hz", 1e6 / s.minDelay) else "unbekannt"

        val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sb.append("BESCHLEUNIGUNG\n")
        if (a == null) sb.append("nicht vorhanden\n") else {
            sb.append("${a.name} (${a.vendor})\n")
            sb.append(String.format(Locale.GERMANY, "Messbereich: ±%.1f g\n", a.maximumRange / G))
            sb.append(String.format(Locale.GERMANY, "Auflösung: %.4f g\n", a.resolution / G))
            sb.append("Max. Rate: ${rate(a)}\n")
        }

        val g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sb.append("\nGYROSKOP\n")
        if (g == null) sb.append("nicht vorhanden\n") else {
            sb.append("${g.name} (${g.vendor})\n")
            sb.append(String.format(Locale.GERMANY, "Messbereich: ±%.0f °/s\n", Math.toDegrees(g.maximumRange.toDouble())))
            sb.append("Max. Rate: ${rate(g)}\n")
        }

        val m = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        sb.append("\nKOMPASS\n")
        sb.append(if (m == null) "nicht vorhanden\n" else "${m.name}, max. Rate: ${rate(m)}\n")

        val p = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        sb.append("\nBAROMETER\n")
        sb.append(if (p == null) "nicht vorhanden (Höhe kommt von GPS bzw. der Uhr)\n" else "${p.name}\n")

        sb.append("\nAufgezeichnet wird mit 200 Hz (Beschleunigung, Gyroskop) und 50 Hz (Kompass).")
        return sb.toString()
    }
}
