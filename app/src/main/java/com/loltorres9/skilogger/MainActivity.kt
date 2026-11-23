package com.loltorres9.skilogger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnStart: Button
    private lateinit var btnMarker: Button
    private lateinit var sessionsBox: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var lastRunning: Boolean? = null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        tvHint = findViewById(R.id.tvHint)
        tvStats = findViewById(R.id.tvStats)
        btnStart = findViewById(R.id.btnStart)
        btnMarker = findViewById(R.id.btnMarker)
        sessionsBox = findViewById(R.id.sessions)

        btnStart.setOnClickListener { toggleRecording() }
        btnMarker.setOnClickListener {
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_MARKER))
            Toast.makeText(this, "Markierung gesetzt", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSensors).setOnClickListener { showSensorInfo() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { showBatteryHelp() }

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        refreshSessions()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------------- Aufnahme

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun toggleRecording() {
        if (LoggerService.running) {
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_STOP))
            handler.postDelayed({ refreshSessions() }, 1500)
        } else {
            if (!hasLocationPermission()) {
                Toast.makeText(this, "Bitte zuerst den genauen Standort erlauben", Toast.LENGTH_LONG).show()
                requestNeededPermissions()
                return
            }
            ContextCompat.startForegroundService(
                this, Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_START)
            )
            handler.postDelayed({ refreshSessions() }, 1000)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refresh() {
        val running = LoggerService.running
        if (running != lastRunning) {
            lastRunning = running
            btnStart.text = if (running) "■  Aufnahme stoppen" else "●  Aufnahme starten"
            btnMarker.isEnabled = running
            refreshSessions()
        }

        tvStatus.text = if (running) "Aufnahme läuft" else "Bereit"
        tvHint.text = buildString {
            if (!hasLocationPermission()) append("⚠ Standort nicht erlaubt – kein GPS.\n")
            if (!isIgnoringBatteryOptimizations()) append("⚠ Akku-Optimierung aktiv – siehe „Akku / Autostart“.\n")
            LoggerService.lastError?.let { append("⚠ ").append(it).append('\n') }
            if (isEmpty()) append("Handy eng am Körper tragen (Innentasche). Bildschirm darf aus sein.")
        }.trim()

        if (!running) {
            tvStats.text = ""
            return
        }
        val elapsedMs = SystemClock.elapsedRealtime() - LoggerService.startElapsedMs
        val secs = (elapsedMs / 1000.0).coerceAtLeast(1.0)
        val sinceFix = if (LoggerService.lastGpsElapsedMs == 0L) -1L
        else (SystemClock.elapsedRealtime() - LoggerService.lastGpsElapsedMs) / 1000
        val de = Locale.GERMANY
        tvStats.text = buildString {
            append("Dauer:          ${LoggerService.formatDuration(elapsedMs)}\n")
            append(String.format(de, "Beschleunigung: %,d (%.0f Hz)\n", LoggerService.accelCount, LoggerService.accelCount / secs))
            append(String.format(de, "Gyroskop:       %,d (%.0f Hz)\n", LoggerService.gyroCount, LoggerService.gyroCount / secs))
            append(String.format(de, "Kompass:        %,d (%.0f Hz)\n", LoggerService.magCount, LoggerService.magCount / secs))
            if (LoggerService.gpsCount == 0L) {
                append("GPS:            warte auf Signal …\n")
            } else {
                append(String.format(de, "GPS:            %,d Punkte, ±%.0f m, vor %d s\n",
                    LoggerService.gpsCount, LoggerService.lastAccuracyM, sinceFix))
                val now = if (LoggerService.lastSpeedKmh >= 0) String.format(de, "%.0f", LoggerService.lastSpeedKmh) else "–"
                append(String.format(de, "Tempo:          %s km/h (max %.0f)\n", now, LoggerService.maxSpeedKmh))
            }
            append(String.format(de, "Max. Beschl.:   %.1f g", LoggerService.maxG))
            if (LoggerService.accelRangeG > 0) append(String.format(de, " (Grenze %.0f g)", LoggerService.accelRangeG))
            if (LoggerService.clippedCount > 0) append(String.format(de, "\n                %,d Werte am Messlimit", LoggerService.clippedCount))
            append(String.format(de, "\nMarkierungen:   %d", LoggerService.markerCount))
        }
    }

    // ---------------------------------------------------------------- Aufnahmen-Liste

    private fun refreshSessions() {
        sessionsBox.removeAllViews()
        val dirs = LoggerService.sessionsDir(this).listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.name }.orEmpty()
        if (dirs.isEmpty()) {
            sessionsBox.addView(TextView(this).apply { text = "Noch keine Aufnahmen." })
            return
        }
        for (dir in dirs) {
            val active = LoggerService.running && dir.name == LoggerService.sessionName
            val sizeMb = (dir.listFiles()?.sumOf { it.length() } ?: 0L) / 1_048_576.0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = String.format(Locale.GERMANY, "%s\n%.1f MB%s", dir.name.replace('_', ' '), sizeMb,
                    if (active) " · läuft" else "")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val share = Button(this).apply {
                text = "Teilen"
                isEnabled = !active
                setOnClickListener { shareSession(dir) }
            }
            val delete = Button(this).apply {
                text = "Löschen"
                isEnabled = !active
                setOnClickListener { confirmDelete(dir) }
            }
            row.addView(label)
            row.addView(share)
            row.addView(delete)
            sessionsBox.addView(row)
        }
    }

    private fun shareSession(dir: File) {
        Toast.makeText(this, "Datei wird vorbereitet …", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val zip = zipSession(dir)
                val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
                runOnUiThread {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Ski Logger ${dir.name}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Aufnahme teilen"))
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun zipSession(dir: File): File {
        val outDir = File(cacheDir, "exports").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val zip = File(outDir, "ski_${dir.name}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                zos.putNextEntry(ZipEntry("${dir.name}/${f.name}"))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zip
    }

    private fun confirmDelete(dir: File) {
        AlertDialog.Builder(this)
            .setTitle("Aufnahme löschen?")
            .setMessage(dir.name.replace('_', ' '))
            .setPositiveButton("Löschen") { _, _ ->
                dir.deleteRecursively()
                refreshSessions()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------------------------------------------------------------- Dialoge

    private fun showSensorInfo() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        AlertDialog.Builder(this)
            .setTitle("Sensoren")
            .setMessage(SensorInfo.describe(sm))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showBatteryHelp() {
        val status = if (isIgnoringBatteryOptimizations()) "✓ Akku-Optimierung ist aus." else "⚠ Akku-Optimierung ist noch an."
        AlertDialog.Builder(this)
            .setTitle("Xiaomi: im Hintergrund laufen lassen")
            .setMessage(
                "Damit HyperOS die Aufzeichnung in der Tasche nicht beendet:\n\n" +
                    "1. Autostart für Ski Logger einschalten\n" +
                    "2. App-Info → Akkusparmodus → „Keine Einschränkungen“\n" +
                    "3. Akku-Optimierung für Ski Logger ausschalten\n" +
                    "4. Optional: in der App-Übersicht die App nach unten ziehen, um sie zu sperren (Schloss)\n\n" +
                    status
            )
            .setPositiveButton("Autostart") { _, _ -> openAutostart() }
            .setNeutralButton("App-Info") { _, _ -> openAppDetails() }
            .setNegativeButton("Optimierung aus") { _, _ -> requestIgnoreOptimizations() }
            .show()
    }

    private fun openAutostart() {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
        )
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {}
        }
        openAppDetails()
    }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestIgnoreOptimizations() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                openAppDetails()
            }
        }
    }

    @Suppress("unused")
    private fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}
