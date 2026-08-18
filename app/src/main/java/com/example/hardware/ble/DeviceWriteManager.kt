package com.example.hardware.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pacing-aware BLE write queue with a stall watchdog for a single device.
 *
 * Extracted verbatim (Phase 6, part BLE) from the former inner class
 * `RgbControllerViewModel.DeviceWriteManager` — the queue/pacing/watchdog logic is byte-for-byte
 * identical. The only change is how it obtains its dependencies: instead of reaching into the
 * enclosing ViewModel for prefs/telemetry/calibration, it now takes them as constructor lambdas so
 * this class can live in `hardware/ble/` decoupled from `presentation`:
 *  - [pacingMsProvider] replaces the direct `prefsRepo.getPacingPrefInt(address, 50)` read.
 *  - [onFpsUpdate] replaces the direct `_telemetry.update { ... deviceAchievedFps ... }` write.
 *  - [calibrate] replaces the direct `processCommandWithCalibration(address, command)` call.
 *  - [diagAttribution] replaces the direct `getDiagAttribution(address)` call used in log strings.
 *
 * It still holds the raw [BluetoothGatt]/[BluetoothGattCharacteristic] and drives writes directly,
 * which is fine now that it lives alongside the GATT transport in `hardware/ble/` — the goal was
 * decoupling from the ViewModel's prefs/telemetry/calibration, not abstracting the GATT object.
 */
class DeviceWriteManager(
    val address: String,
    val gatt: BluetoothGatt,
    val charac: BluetoothGattCharacteristic,
    private val connectionScope: CoroutineScope,
    private val pacingMsProvider: () -> Int,
    private val calibrate: (String, ByteArray) -> ByteArray,
    private val onFpsUpdate: (String, Int) -> Unit,
    private val diagAttribution: (String) -> String
) {
    // Queued command plus the peak-hold/pacing-bypass metadata it was enqueued with
    // (visualizer-review-2026-07-21.md P2). [priority] compares only against other queued
    // commands of the *same type byte* (index 2) — see [updateCommand].
    private data class QueuedCommand(val bytes: ByteArray, val priority: Float, val bypassPacing: Boolean)

    private val commandQueue = java.util.concurrent.ConcurrentLinkedQueue<QueuedCommand>()
    @Volatile var isWriting = false
    @Volatile var lastWriteTime = 0L
    val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    var currentPacingMs = pacingMsProvider()

    // Incremented from the GATT callback thread, drained from the sampler coroutine below.
    private val framesSent = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var pendingJob: Job? = null

    private var consecutiveWatchdogTriggers = 0
    private var lastQueueLogTime = 0L

    /**
     * The last RGB colour actually handed to the radio, for [WriteDedupe].
     *
     * Issued rather than enqueued, because the queue drops what it replaces. Cleared whenever a
     * write fails, so a failure can always be retried by resending the same colour — and note that a
     * reconnect builds a whole new manager, which starts this at null, so nothing survives a
     * disconnect to suppress the first colour of a new connection.
     */
    @Volatile private var lastIssuedColour: ByteArray? = null

    /** Redundant colours suppressed since the last 1Hz telemetry tick. */
    private val identicalSkipped = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Samples the achieved write rate on a fixed 1Hz tick. This used to be computed inside
     * [onWriteCompleted], which meant the counter only advanced when a write landed — once the
     * strip went idle the last reported rate stuck in the telemetry map forever, so the FPS readout
     * froze at whatever it had been doing rather than dropping to 0. Ticking independently reports
     * a real 0 when nothing is being sent.
     *
     * F3 (IMPROVEMENT_PLAN.md) — must be cancelled explicitly via [release], which is why it's held
     * here rather than launched from a bare `init` block. [connectionScope] belongs to the whole
     * transport, not to one connection, so it is never cancelled on a per-device disconnect: every
     * manager rebuilt for an address (each service discovery, and `restoreWriteManagers` at VM init)
     * used to leave the previous manager's sampler running forever. Orphans never see a write, so
     * they published `0` into `deviceAchievedFps[address]` once a second, alternating with the live
     * manager's real count — the "27 fps, 0, 27 fps" flicker.
     */
    private val fpsSamplerJob: Job = connectionScope.launch(Dispatchers.IO) {
        while (true) {
            delay(1000L)
            val fps = framesSent.getAndSet(0)
            val skipped = identicalSkipped.getAndSet(0)
            onFpsUpdate(address, fps)
            if (fps > 0 || skipped > 0) {
                // P0 (visualizer-review-2026-07-21.md): what pacing actually settles at per
                // device during a real session. `identicalSkipped` rides along because the ratio of
                // skipped to sent is the direct read on how much of a preset's computed output the
                // byte grid cannot express — 97% on a slow fade, in simulation.
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "Pacing settled: address=$address, currentPacingMs=$currentPacingMs, fps=$fps, " +
                        "inFlightMs=${"%.1f".format(inFlightMsEstimate)}, identicalSkipped=$skipped. " +
                        "(${diagAttribution(address)})"
                )
            }
        }
    }

    /**
     * Stops this manager's background work. Must be called whenever the manager is dropped —
     * replaced in `deviceWriteManagers` or removed on disconnect — since [connectionScope] outlives
     * any single connection and will not do it for us. See [fpsSamplerJob].
     */
    fun release() {
        fpsSamplerJob.cancel()
        pendingJob?.cancel()
        pendingJob = null
    }

    /**
     * [priority] and [bypassPacing] implement the peak-hold/peak-priority write rule
     * (visualizer-review-2026-07-21.md P2): previously this dequeued *any* existing same-type
     * command in favor of the latest one, so a computed flash peak could be silently overwritten
     * by the very next (lower-value) DSP frame before the pacing timer ever let it write. Now, a
     * still-queued command of the same type is only replaced if its priority is <= the new one's —
     * a higher-priority command that hasn't been written yet survives lower-priority frames until
     * it's actually sent, at which point normal latest-wins resumes. [bypassPacing] marks the exact
     * frame a flash fires so [tryWrite] can skip the pacing wait for that one write.
     */
    fun updateCommand(command: ByteArray, priority: Float = Float.MAX_VALUE, bypassPacing: Boolean = false) {
        val processed = calibrate(address, command)

        // A colour identical to the one already on the strip changes nothing, and enqueuing it would
        // evict whatever real colour is still waiting (see [WriteDedupe]). Checked before the
        // peak-hold logic below, so a redundant frame cannot displace a held peak either.
        if (WriteDedupe.isRedundantColour(processed, lastIssuedColour)) {
            identicalSkipped.incrementAndGet()
            return
        }

        val type = if (processed.size >= 3) processed[2] else null
        // A held peak of the same type takes priority over this frame if it hasn't written yet —
        // checked read-only first, so a mixed-priority queue (shouldn't happen under the
        // single-entry-per-type invariant this maintains, but don't assume) never partially
        // mutates the queue before the decision is made.
        val supersededByExisting = type != null && commandQueue.any {
            it.bytes.size >= 3 && it.bytes[2] == type && it.priority > priority
        }
        if (type != null && !supersededByExisting) {
            commandQueue.removeAll { it.bytes.size >= 3 && it.bytes[2] == type }
        }
        if (supersededByExisting) {
            com.example.DiagnosticLogger.log(
                "DeviceWriteManager",
                "Write superseded by held peak: address=$address, droppedPriority=$priority. (${diagAttribution(address)})"
            )
            return
        }

        val qSizeBefore = commandQueue.size
        // Fallback limit just in case
        if (commandQueue.size > 20) {
            commandQueue.poll()
            com.example.DiagnosticLogger.log(
                "DeviceWriteManager",
                "Backpressure triggered (Queue size > 20)! Polled/dropped command. address=$address. (${diagAttribution(address)})"
            )
        }
        commandQueue.offer(QueuedCommand(processed, priority, bypassPacing))
        val qSizeAfter = commandQueue.size
        com.example.DiagnosticLogger.log(
            "DeviceWriteManager",
            "Write enqueued: address=$address, cmdHex=${processed.joinToString("") { String.format("%02X", it) }}, priority=$priority, bypassPacing=$bypassPacing, queueSizeBefore=$qSizeBefore, queueSizeAfter=$qSizeAfter. (${diagAttribution(address)})"
        )

        val now = System.currentTimeMillis()
        if (now - lastQueueLogTime >= 1000L) {
            Log.d("BleWriteQueue", "Queue size for $address: ${commandQueue.size}")
            lastQueueLogTime = now
        }

        tryWrite()
    }

    fun onWriteCompleted() {
        com.example.DiagnosticLogger.log(
            "DeviceWriteManager",
            "onWriteCompleted callback received for $address. (${diagAttribution(address)})"
        )
        consecutiveWatchdogTriggers = 0
        val completedAt = System.currentTimeMillis()
        recordInFlight(completedAt - lastWriteTime)
        lastWriteTime = completedAt
        isWriting = false
        framesSent.incrementAndGet()
        tryWrite()
    }

    /**
     * Rolling estimate of how long a write occupies the radio: issued → `onCharacteristicWrite`.
     *
     * This is the number every pacing decision actually depends on, and until now nothing measured
     * it — pacing was a stored guess, tuned once against conditions that were gone by the time the
     * value was saved. Hardware calibration put it at ~4.6ms per strip on a warm link and ~68ms for
     * the first write after a quiet spell (`tools/calibration/README.md`), but it varies by phone,
     * by distance and by what else is on the radio, which is exactly why it wants measuring here
     * rather than assuming.
     *
     * An EMA rather than a mean: the useful question is "what is the link doing now", not "what has
     * it averaged since connecting".
     */
    @Volatile var inFlightMsEstimate: Double = 0.0
        private set

    private fun recordInFlight(sample: Long) {
        // Guard the first callback after a connect, where lastWriteTime is still 0 and the
        // "elapsed" is really the epoch.
        if (sample <= 0 || sample > 5_000) return
        inFlightMsEstimate =
            if (inFlightMsEstimate <= 0.0) sample.toDouble() else inFlightMsEstimate * 0.8 + sample * 0.2
    }

    @Synchronized
    private fun tryWrite() {
        if (isWriting) return
        val cmd = commandQueue.peek() ?: return

        val now = System.currentTimeMillis()
        val elapsed = now - lastWriteTime

        // Peak-priority bypass (visualizer-review-2026-07-21.md P2): the frame a flash fires
        // skips the pacing wait entirely rather than risking the flash landing in the gap between
        // two paced writes.
        if (currentPacingMs > 0 && !cmd.bypassPacing) {
            if (elapsed < currentPacingMs) {
                if (pendingJob == null || pendingJob?.isActive != true) {
                    pendingJob = connectionScope.launch(Dispatchers.IO) {
                        delay(currentPacingMs - elapsed)
                        pendingJob = null
                        tryWrite()
                    }
                }
                return
            }
        }

        isWriting = true
        val cmdToWrite = commandQueue.poll()
        if (cmdToWrite == null) {
            isWriting = false
            return
        }
        val currentWriteTime = System.currentTimeMillis()
        lastWriteTime = currentWriteTime
        val cmdHex = cmdToWrite.bytes.joinToString("") { String.format("%02X", it) }

        charac.writeType = writeType
        try {
            val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(charac, cmdToWrite.bytes, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                charac.value = cmdToWrite.bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(charac)
            }

            if (!success) {
                // Forget the dedupe baseline: this colour did not reach the radio, so an identical
                // resend is a genuine retry rather than a redundant frame.
                if (WriteDedupe.isRgbColour(cmdToWrite.bytes)) lastIssuedColour = null
                Log.w("BleWriteQueue", "writeCharacteristic() returned false for $address")
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "writeCharacteristic() returned false (write failure) for $address, cmdHex=$cmdHex. (${diagAttribution(address)})"
                )
            } else {
                if (WriteDedupe.isRgbColour(cmdToWrite.bytes)) lastIssuedColour = cmdToWrite.bytes
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "writeCharacteristic() initiated (write success) for $address, cmdHex=$cmdHex. (${diagAttribution(address)})"
                )
            }
        } catch (e: Exception) {
            isWriting = false
            if (WriteDedupe.isRgbColour(cmdToWrite.bytes)) lastIssuedColour = null
            com.example.DiagnosticLogger.log(
                "DeviceWriteManager",
                "writeCharacteristic() Exception for $address: ${android.util.Log.getStackTraceString(e)}. (${diagAttribution(address)})"
            )
            return
        }

        connectionScope.launch(Dispatchers.IO) {
            com.example.DiagnosticLogger.log(
                "BleWriteWatchdog",
                "Watchdog check tick scheduled for device $address (currentWriteTime=$currentWriteTime). (${diagAttribution(address)})"
            )
            delay(2000)
            com.example.DiagnosticLogger.log(
                "BleWriteWatchdog",
                "Watchdog check tick running for device $address: isWriting=$isWriting, lastWriteTime=$lastWriteTime, expectedWriteTime=$currentWriteTime, consecutiveWatchdogTriggers=$consecutiveWatchdogTriggers. (${diagAttribution(address)})"
            )
            if (isWriting && lastWriteTime == currentWriteTime) {
                Log.w("BleWriteWatchdog", "Watchdog fired for device $address at timestamp $currentWriteTime — forcing reset")
                com.example.DiagnosticLogger.log(
                    "BleWriteWatchdog",
                    "Watchdog FIRED for device $address at timestamp $currentWriteTime — forcing reset. (${diagAttribution(address)})"
                )
                isWriting = false
                consecutiveWatchdogTriggers++

                if (consecutiveWatchdogTriggers >= 3) {
                    Log.e("BleWriteWatchdog", "device $address appears frozen — forcing reconnect")
                    com.example.DiagnosticLogger.log(
                        "BleWriteWatchdog",
                        "device $address appears frozen (consecutiveTriggers=$consecutiveWatchdogTriggers) — forcing reconnect. (${diagAttribution(address)})"
                    )
                    consecutiveWatchdogTriggers = 0
                    try {
                        gatt.disconnect()
                    } catch (e: Exception) {
                        Log.e("BleWriteWatchdog", "Exception forcing disconnect on frozen device", e)
                        com.example.DiagnosticLogger.log(
                            "BleWriteWatchdog",
                            "Exception forcing disconnect on frozen device $address: ${android.util.Log.getStackTraceString(e)}"
                        )
                    }
                } else {
                    tryWrite()
                }
            }
        }
    }
}
