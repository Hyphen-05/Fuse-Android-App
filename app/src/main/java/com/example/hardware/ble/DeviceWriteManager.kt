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
    private val commandQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile var isWriting = false
    @Volatile var lastWriteTime = 0L
    val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    var currentPacingMs = pacingMsProvider()

    private var framesSent = 0
    private var lastFpsTime = System.currentTimeMillis()
    @Volatile private var pendingJob: Job? = null

    private var consecutiveWatchdogTriggers = 0
    private var lastQueueLogTime = 0L

    fun updateCommand(command: ByteArray) {
        val processed = calibrate(address, command)

        val type = if (processed.size >= 3) processed[2] else null
        if (type != null) {
            val iterator = commandQueue.iterator()
            while (iterator.hasNext()) {
                val existing = iterator.next()
                if (existing.size >= 3 && existing[2] == type) {
                    iterator.remove()
                }
            }
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
        commandQueue.offer(processed)
        val qSizeAfter = commandQueue.size
        com.example.DiagnosticLogger.log(
            "DeviceWriteManager",
            "Write enqueued: address=$address, cmdHex=${processed.joinToString("") { String.format("%02X", it) }}, queueSizeBefore=$qSizeBefore, queueSizeAfter=$qSizeAfter. (${diagAttribution(address)})"
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
        }
        tryWrite()
    }

    @Synchronized
    private fun tryWrite() {
        if (isWriting) return
        val cmd = commandQueue.peek() ?: return

        val now = System.currentTimeMillis()
        val elapsed = now - lastWriteTime

        if (currentPacingMs > 0) {
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

        charac.writeType = writeType
        try {
            val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(charac, cmdToWrite, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                charac.value = cmdToWrite
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(charac)
            }

            if (!success) {
                Log.w("BleWriteQueue", "writeCharacteristic() returned false for $address")
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "writeCharacteristic() returned false (write failure) for $address. (${diagAttribution(address)})"
                )
            } else {
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "writeCharacteristic() initiated (write success) for $address. (${diagAttribution(address)})"
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
