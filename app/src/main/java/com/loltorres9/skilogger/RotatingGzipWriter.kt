package com.loltorres9.skilogger

import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Schreibt CSV-Zeilen gzip-komprimiert und beginnt alle [rotateMs] eine neue Datei.
 * Falls die App unerwartet beendet wird, geht so höchstens der letzte Teil verloren.
 */
class RotatingGzipWriter(
    private val dir: File,
    private val prefix: String,
    private val header: String,
    private val rotateMs: Long = 5 * 60_000L,
) {
    private var out: BufferedWriter? = null
    private var part = 0
    private var openedAt = 0L

    fun write(line: CharSequence) {
        val now = SystemClock.elapsedRealtime()
        if (out == null || now - openedAt > rotateMs) rotate(now)
        out!!.append(line)
    }

    fun flush() {
        out?.flush()
    }

    fun close() {
        out?.close()
        out = null
    }

    private fun rotate(now: Long) {
        out?.close()
        val file = File(dir, String.format(Locale.US, "%s_%03d.csv.gz", prefix, part++))
        val gz = GZIPOutputStream(FileOutputStream(file), 65536, true)
        out = BufferedWriter(OutputStreamWriter(gz, Charsets.UTF_8), 65536).also {
            it.append(header).append('\n')
        }
        openedAt = now
    }
}
