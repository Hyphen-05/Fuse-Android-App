package com.example

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object DiagnosticLogger {
    @Volatile
    private var recording = false

    private const val MAX_ENTRIES = 5000
    private const val MAX_BYTES_ESTIMATE = 2 * 1024 * 1024 // 2MB

    // Tags excluded for the duration of the current recording. Used to keep high-frequency BLE
    // write/watchdog logging (DeviceWriteManager/BleWriteWatchdog/BLE ticks roughly every 50ms
    // per device) from evicting lower-frequency audio-diagnostic entries out of the ring buffer.
    @Volatile
    private var excludedTags: Set<String> = emptySet()

    private val entries = ArrayList<String>()
    private var approxBytes = 0

    fun start(excludedTags: Set<String> = emptySet()) {
        synchronized(entries) {
            recording = true
            this.excludedTags = excludedTags
            entries.clear()
            approxBytes = 0
            logInternal("DiagnosticLogger", "Diagnostic recording started" + if (excludedTags.isNotEmpty()) " (excluding tags: ${excludedTags.joinToString(", ")})" else "")
        }
    }

    fun stop() {
        synchronized(entries) {
            if (recording) {
                logInternal("DiagnosticLogger", "Diagnostic recording stopped")
                recording = false
            }
        }
    }

    fun isRecording(): Boolean {
        return recording
    }

    fun currentExcludedTags(): Set<String> {
        return excludedTags
    }

    fun log(tag: String, message: String) {
        if (!recording) return
        if (tag in excludedTags) return
        logInternal(tag, message)
    }

    private fun logInternal(tag: String, message: String) {
        val elapsed = SystemClock.elapsedRealtime()
        val threadName = Thread.currentThread().name
        val line = "[$elapsed] [$threadName] [$tag] $message"
        
        synchronized(entries) {
            val lineBytes = line.length * 2
            entries.add(line)
            approxBytes += lineBytes

            while (entries.size > MAX_ENTRIES || approxBytes > MAX_BYTES_ESTIMATE) {
                if (entries.isEmpty()) break
                val removed = entries.removeAt(0)
                approxBytes -= removed.length * 2
            }
        }
    }

    fun exportToFile(context: Context): Uri? {
        val linesToExport = synchronized(entries) {
            ArrayList(entries)
        }
        
        val dir = context.getExternalFilesDir("diagnostics") ?: context.filesDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "diagnostics_log_${System.currentTimeMillis()}.txt")
        try {
            FileOutputStream(file).use { fos ->
                for (line in linesToExport) {
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
                fos.flush()
            }
            
            val authority = "${context.packageName}.fileprovider"
            return FileProvider.getUriForFile(context, authority, file)
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
    }
}
