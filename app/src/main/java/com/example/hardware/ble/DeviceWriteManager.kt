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

    private var framesSent = 0
    private var lastFpsTime = System.currentTimeMillis()
    @Volatile private var pendingJob: Job? = null

    private var consecutiveWatchdogTriggers = 0
    private var lastQueueLogTime = 0L

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
        lastWriteTime = System.currentTimeMillis()
        isWriting = false
        framesSent++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000L) {
            val fps = framesSent
            framesSent = 0
            lastFpsTime = now
            onFpsUpdate(address, fps)
            // P0 (visualizer-review-2026-07-21.md): what pacing actually settles at per device
            // during a real session — reuses this existing ~1s cadence rather than adding a new
            // timer, so it's free.
            com.example.DiagnosticLogger.log(
                "DeviceWriteManager",
                "Pacing settled: address=$address, currentPacingMs=$currentPacingMs, fps=$fps. (${diagAttribution(address)})"
            )
        }
        tryWrite()
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
                Log.w("BleWriteQueue", "writeCharacteristic() returned false for $address")
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "writeCharacteristic() returned false (write failure) for $address, cmdHex=$cmdHex. (${diagAttribution(address)})"
                )
            } else {
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "writeCharacteristic() initiated (write success) for $address, cmdHex=$cmdHex. (${diagAttribution(address)})"
                )
            }
        } catch (e: Exception) {
            isWriting = false
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
