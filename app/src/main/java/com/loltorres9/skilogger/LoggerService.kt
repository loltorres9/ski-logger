package com.loltorres9.skilogger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Vordergrund-Dienst, der Beschleunigung, Gyroskop, Kompass und GPS aufzeichnet.
 * Läuft mit ausgeschaltetem Bildschirm weiter (Wakelock + Foreground Service).
 */
class LoggerService : Service(), SensorEventListener, LocationListener {

    companion object {
        const val ACTION_START = "com.loltorres9.skilogger.START"
        const val ACTION_STOP = "com.loltorres9.skilogger.STOP"
        const val ACTION_MARKER = "com.loltorres9.skilogger.MARKER"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        const val IMU_PERIOD_US = 5_000   // 200 Hz
        const val MAG_PERIOD_US = 20_000  // 50 Hz
        const val G = 9.80665

        @Volatile var running = false
        @Volatile var sessionName = ""
        @Volatile var startElapsedMs = 0L
        @Volatile var accelCount = 0L
        @Volatile var gyroCount = 0L
        @Volatile var magCount = 0L
        @Volatile var gpsCount = 0L
        @Volatile var markerCount = 0
        @Volatile var maxG = 0.0
        @Volatile var clippedCount = 0L
        @Volatile var accelRangeG = 0.0
        @Volatile var lastSpeedKmh = -1.0
        @Volatile var maxSpeedKmh = 0.0
        @Volatile var lastAccuracyM = -1.0
        @Volatile var lastGpsElapsedMs = 0L
        @Volatile var lastError: String? = null

        fun sessionsDir(ctx: Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "sessions").apply { mkdirs() }

        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return String.format(Locale.GERMANY, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        }
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var sessionDir: File? = null
    private var imuWriter: RotatingGzipWriter? = null
    private var gpsWriter: RotatingGzipWriter? = null
    private var eventWriter: BufferedWriter? = null

    private var accelMaxRange = Float.MAX_VALUE
    private var startUtcMs = 0L
    private var startElapsedNs = 0L
    private var lastFlushMs = 0L
    private val line = StringBuilder(96)

    private val notifUpdater = object : Runnable {
        override fun run() {
            if (!running) return
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(statusText()))
            mainHandler.postDelayed(this, 5_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            ACTION_MARKER -> {
                if (running) {
                    markerCount++
                    addEvent("marker", "Markierung $markerCount")
                }
            }
            else -> if (!running) startRecording()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) stopRecording()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Start / Stopp

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun startRecording() {
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            lastError = "Start fehlgeschlagen: ${e.message}"
            stopSelf()
            return
        }
        resetStats()
        startUtcMs = System.currentTimeMillis()
        startElapsedNs = SystemClock.elapsedRealtimeNanos()
        startElapsedMs = SystemClock.elapsedRealtime()

        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(startUtcMs))
        sessionName = name
        val dir = File(sessionsDir(this), name).apply { mkdirs() }
        sessionDir = dir
        imuWriter = RotatingGzipWriter(dir, "imu", "sensor,t_ns,x,y,z")
        gpsWriter = RotatingGzipWriter(
            dir, "gps",
            "t_ns,utc_ms,lat,lon,alt_m,speed_ms,bearing_deg,acc_m,vacc_m,speed_acc_ms"
        )
        eventWriter = File(dir, "events.csv").bufferedWriter().apply { write("t_ns,utc_ms,type,note\n") }
        writeMeta(final = false)
        addEvent("start", "")

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SkiLogger:recording").apply {
            setReferenceCounted(false)
            acquire()
        }

        val thread = HandlerThread("sensors", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            accelMaxRange = it.maximumRange
            accelRangeG = it.maximumRange / G
            sensorManager.registerListener(this, it, IMU_PERIOD_US, handler)
        } ?: run { lastError = "Kein Beschleunigungssensor" }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, IMU_PERIOD_US, handler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, MAG_PERIOD_US, handler)
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            lastError = "Keine Standort-Berechtigung – es wird ohne GPS aufgezeichnet"
        } catch (e: Exception) {
            lastError = "GPS: ${e.message}"
        }

        running = true
        mainHandler.postDelayed(notifUpdater, 2_000)
    }

    private fun stopRecording() {
        if (!running) {
            stopSelfCompat()
            return
        }
        running = false
        mainHandler.removeCallbacks(notifUpdater)
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        sensorManager.unregisterListener(this)

        // IMU-Datei auf dem Sensor-Thread schließen, damit nichts gleichzeitig schreibt.
        val handler = sensorHandler
        if (handler != null) {
            val latch = CountDownLatch(1)
            handler.post {
                try { imuWriter?.close() } catch (_: Exception) {}
                imuWriter = null
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
        }
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null

        try { gpsWriter?.close() } catch (_: Exception) {}
        gpsWriter = null
        addEvent("stop", "")
        try { eventWriter?.close() } catch (_: Exception) {}
        eventWriter = null
        try { writeMeta(final = true) } catch (_: Exception) {}

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopSelfCompat()
    }

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resetStats() {
        accelCount = 0; gyroCount = 0; magCount = 0; gpsCount = 0; markerCount = 0
        maxG = 0.0; clippedCount = 0; lastSpeedKmh = -1.0; maxSpeedKmh = 0.0
        lastAccuracyM = -1.0; lastGpsElapsedMs = 0L; lastError = null
    }

    // ---------------------------------------------------------------- Sensoren

    override fun onSensorChanged(e: SensorEvent) {
        val w = imuWriter ?: return
        val tag = when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelCount++
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val g = sqrt((x * x + y * y + z * z).toDouble()) / G
                if (g > maxG) maxG = g
                val limit = accelMaxRange * 0.98f
                if (abs(x) >= limit || abs(y) >= limit || abs(z) >= limit) clippedCount++
                'A'
            }
            Sensor.TYPE_GYROSCOPE -> { gyroCount++; 'G' }
            Sensor.TYPE_MAGNETIC_FIELD -> { magCount++; 'M' }
            else -> return
        }
        line.setLength(0)
        line.append(tag).append(',').append(e.timestamp).append(',')
            .append(e.values[0]).append(',')
            .append(e.values[1]).append(',')
            .append(e.values[2]).append('\n')
        try {
            w.write(line)
            val now = SystemClock.elapsedRealtime()
            if (now - lastFlushMs > 10_000) {
                lastFlushMs = now
                w.flush()
            }
        } catch (ex: Exception) {
            lastError = "Schreibfehler: ${ex.message}"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- GPS

    override fun onLocationChanged(l: Location) {
        gpsCount++
        lastGpsElapsedMs = SystemClock.elapsedRealtime()
        lastAccuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else -1.0
        if (l.hasSpeed()) {
            val kmh = l.speed * 3.6
            lastSpeedKmh = kmh
            if (kmh > maxSpeedKmh) maxSpeedKmh = kmh
        } else {
            lastSpeedKmh = -1.0
        }
        val row = buildString {
            append(l.elapsedRealtimeNanos).append(',').append(l.time).append(',')
            append(l.latitude).append(',').append(l.longitude).append(',')
            append(if (l.hasAltitude()) l.altitude.toString() else "").append(',')
            append(if (l.hasSpeed()) l.speed.toString() else "").append(',')
            append(if (l.hasBearing()) l.bearing.toString() else "").append(',')
            append(if (l.hasAccuracy()) l.accuracy.toString() else "").append(',')
            append(if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toString() else "").append(',')
            append(if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond.toString() else "").append('\n')
        }
        try {
            gpsWriter?.write(row)
            gpsWriter?.flush()
        } catch (e: Exception) {
            lastError = "GPS-Schreibfehler: ${e.message}"
        }
    }

    override fun onProviderEnabled(provider: String) {
        addEvent("gps_on", provider)
    }

    override fun onProviderDisabled(provider: String) {
        addEvent("gps_off", provider)
        lastError = "GPS ist ausgeschaltet"
    }

    // ---------------------------------------------------------------- Dateien

    private fun addEvent(type: String, note: String) {
        val w = eventWriter ?: return
        try {
            w.write("${SystemClock.elapsedRealtimeNanos()},${System.currentTimeMillis()},$type,${note.replace(',', ' ')}\n")
            w.flush()
        } catch (_: Exception) {}
    }

    private fun writeMeta(final: Boolean) {
        val dir = sessionDir ?: return
        val o = JSONObject()
        o.put("app", "ski-logger")
        o.put("app_version", BuildConfig.VERSION_NAME)
        o.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        o.put("android", Build.VERSION.RELEASE)
        o.put("sdk", Build.VERSION.SDK_INT)
        o.put("start_utc_ms", startUtcMs)
        o.put("start_elapsed_ns", startElapsedNs)
        o.put("time_note", "t_ns = SystemClock.elapsedRealtimeNanos; utc_ms = start_utc_ms + (t_ns - start_elapsed_ns) / 1e6")
        o.put("imu_period_us", IMU_PERIOD_US)
        o.put("mag_period_us", MAG_PERIOD_US)
        o.put("units", JSONObject().put("A", "m/s^2 (inkl. Erdbeschleunigung)").put("G", "rad/s").put("M", "uT"))
        o.put("sensors", SensorInfo.toJson(sensorManager))
        if (final) {
            o.put("stop_utc_ms", System.currentTimeMillis())
            o.put("stop_elapsed_ns", SystemClock.elapsedRealtimeNanos())
            o.put(
                "counts", JSONObject()
                    .put("accel", accelCount).put("gyro", gyroCount).put("mag", magCount)
                    .put("gps", gpsCount).put("markers", markerCount)
            )
            o.put("max_g", maxG)
            o.put("accel_range_g", accelRangeG)
            o.put("accel_clipped_samples", clippedCount)
            o.put("max_gps_speed_kmh", maxSpeedKmh)
        }
        File(dir, "meta.json").writeText(o.toString(2))
    }

    // ---------------------------------------------------------------- Benachrichtigung

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Aufzeichnung", NotificationManager.IMPORTANCE_LOW)
        )
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Aufzeichnung startet …"), type)
    }

    private fun statusText(): String {
        val dur = formatDuration(SystemClock.elapsedRealtime() - startElapsedMs)
        val gps = if (gpsCount == 0L) "kein GPS" else String.format(Locale.GERMANY, "GPS ±%.0f m", lastAccuracyM)
        return String.format(Locale.GERMANY, "%s · max %.1f g · %s", dur, maxG, gps)
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LoggerService::class.java).setAction(ACTION_STOP), flags
        )
        val mark = PendingIntent.getService(
            this, 2, Intent(this, LoggerService::class.java).setAction(ACTION_MARKER), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ski Logger zeichnet auf")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Markierung", mark)
            .addAction(0, "Stopp", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
