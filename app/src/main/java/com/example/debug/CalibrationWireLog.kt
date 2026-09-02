package com.example.debug

import java.io.File

/**
 * The wire side of a calibration run: when each write was handed to the BLE stack, and when the
 * stack said it was done with it.
 *
 * ## Why the colour CSV was never enough
 *
 * `CalibrationSequences` logs the moment a command was *sent*. That is the moment the app let go of
 * it, not the moment it reached the strip — everything between the two is queueing, pacing, radio
 * scheduling and connection-interval alignment, and all of it is invisible in that file. A sequence
 * that asks for red-blue-red at even spacing can therefore log perfectly even spacing while the
 * strip visibly does not, which is exactly what Joe saw on 2026-09-02: the red/blue alternation
 * ran at uneven intervals, and the two strips were not in step with each other.
 *
 * That is not a fault to fix before measuring. It IS the measurement — a strip model that cannot
 * reproduce it is not a model of this hardware. This file is what makes it a number rather than an
 * impression.
 *
 * ## What comes out
 *
 * One row per event, per device:
 *  - `send` — the command left the app, with the sequence number it was given.
 *  - `ack`  — `onCharacteristicWrite` came back for that device, with its GATT status.
 *
 * From which fall out, per device and per connected-device-count: round-trip per write, how many
 * writes were outstanding at any moment (the queue depth the app never had visibility of), whether
 * acks arrive in send order, and how far the two devices drift apart over a run.
 */
object CalibrationWireLog {

    private val rows = StringBuilder()
    private var startedAt = 0L

    @Volatile
    private var active = false

    /** Sequence numbers are per-device: a device's own Nth write is what an ack has to line up to. */
    private val nextSeq = mutableMapOf<String, Int>()

    /** Outstanding writes per device, sampled into every row so queue depth needs no reconstruction. */
    private val outstanding = mutableMapOf<String, Int>()

    @Synchronized
    fun begin(atMs: Long) {
        rows.setLength(0)
        rows.append("elapsed_ms,event,address,seq,status,outstanding,devices\n")
        nextSeq.clear()
        outstanding.clear()
        startedAt = atMs
        active = true
    }

    @Synchronized
    fun send(address: String, deviceCount: Int) {
        if (!active) return
        val seq = (nextSeq[address] ?: 0) + 1
        nextSeq[address] = seq
        val out = (outstanding[address] ?: 0) + 1
        outstanding[address] = out
        rows.append("${System.currentTimeMillis() - startedAt},send,$address,$seq,,$out,$deviceCount\n")
    }

    /**
     * The GATT callback carries no sequence number, so an ack is attributed to that device's oldest
     * unacked write. Android delivers write completions per connection in order, so this holds — and
     * if it ever does not, `outstanding` going negative is the visible symptom rather than a silent
     * mis-pairing.
     */
    @Synchronized
    fun ack(address: String, status: Int, deviceCount: Int) {
        if (!active) return
        val out = (outstanding[address] ?: 0) - 1
        outstanding[address] = out
        rows.append("${System.currentTimeMillis() - startedAt},ack,$address,,$status,$out,$deviceCount\n")
    }

    @Synchronized
    fun finish(sequence: String, outputDir: File?): File? {
        active = false
        if (outputDir == null) return null
        return try {
            val file = File(outputDir, "fuse_wire_${sequence}_${startedAt}.csv")
            file.writeText(rows.toString())
            file
        } catch (e: Exception) {
            android.util.Log.w("CalibrationWireLog", "Could not write wire log", e)
            null
        }
    }
}
