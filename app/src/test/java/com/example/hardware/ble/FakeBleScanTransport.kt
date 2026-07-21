package com.example.hardware.ble

import android.bluetooth.le.ScanResult

/**
 * Deterministic, fully synchronous fake of [BleScanTransport] for JUnit tests — no real BLE stack.
 * [startScan] just records the callbacks; call [emitScanResult]/[emitScanFailed] to synchronously
 * invoke them, and inspect [isScanning]/[stopCallCount]/[startCallCount] for wiring assertions.
 * The [startResult] returned by [startScan] is configurable to exercise the caller's failure paths.
 */
class FakeBleScanTransport(
    var startResult: ScanStartResult = ScanStartResult.STARTED
) : BleScanTransport {

    var isScanning: Boolean = false
        private set
    var startCallCount: Int = 0
        private set
    var stopCallCount: Int = 0
        private set

    private var onResult: ((ScanResult) -> Unit)? = null
    private var onFailed: ((Int) -> Unit)? = null

    override fun startScan(onResult: (ScanResult) -> Unit, onFailed: (Int) -> Unit): ScanStartResult {
        this.onResult = onResult
        this.onFailed = onFailed
        startCallCount++
        isScanning = startResult == ScanStartResult.STARTED
        return startResult
    }

    override fun stopScan() {
        stopCallCount++
        isScanning = false
    }

    /** Synchronously invokes the stored onResult callback, as if a device had been discovered. */
    fun emitScanResult(result: ScanResult) {
        onResult?.invoke(result)
    }

    /** Synchronously invokes the stored onFailed callback (platform ScanCallback.onScanFailed). */
    fun emitScanFailed(errorCode: Int) {
        onFailed?.invoke(errorCode)
    }
}
