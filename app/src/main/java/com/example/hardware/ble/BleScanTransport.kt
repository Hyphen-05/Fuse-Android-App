package com.example.hardware.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context

/**
 * Outcome of [BleScanTransport.startScan]. The three failure kinds each map back to the exact
 * `_uiState`/addLog error messaging the ViewModel used to produce inline in `startBleScanHardware`
 * — kept as distinct cases rather than a bare `Boolean` so the ViewModel can reproduce those
 * per-branch messages without the transport touching `_uiState`. (Deviation from the brief's
 * `Boolean` sketch, which could not distinguish the three failure messages.)
 */
enum class ScanStartResult {
    STARTED,
    ADAPTER_UNAVAILABLE,
    SCANNER_UNAVAILABLE,
    PERMISSION_DENIED
}

/**
 * Hardware-facing BLE scan transport. Owns the [BluetoothAdapter]/`BluetoothLeScanner` and the
 * single [ScanCallback] instance, mirroring the former `RgbControllerViewModel.scanCallback` +
 * `startBleScanHardware`/`stopBleScanHardware`. The 12s scan-timeout scheduling stays caller-side
 * (a UI-timing policy decision), driven by the [ScanStartResult].
 */
interface BleScanTransport {
    /**
     * Starts a BLE scan. [onResult] is invoked per discovered [ScanResult]; [onFailed] mirrors the
     * platform `ScanCallback.onScanFailed(errorCode)`. Returns whether the scan physically started
     * (and if not, why) so the caller can reproduce its own error UI.
     */
    fun startScan(onResult: (ScanResult) -> Unit, onFailed: (Int) -> Unit): ScanStartResult

    /** Stops the in-progress scan. No-op if none is running. */
    fun stopScan()
}

@SuppressLint("MissingPermission")
class AndroidBleScanTransport(private val context: Context) : BleScanTransport {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var onResult: ((ScanResult) -> Unit)? = null
    private var onFailed: ((Int) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { onResult?.invoke(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { onResult?.invoke(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            onFailed?.invoke(errorCode)
        }
    }

    override fun startScan(onResult: (ScanResult) -> Unit, onFailed: (Int) -> Unit): ScanStartResult {
        this.onResult = onResult
        this.onFailed = onFailed

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            return ScanStartResult.ADAPTER_UNAVAILABLE
        }

        return try {
            val scanner = adapter.bluetoothLeScanner
                ?: return ScanStartResult.SCANNER_UNAVAILABLE
            scanner.startScan(scanCallback)
            ScanStartResult.STARTED
        } catch (e: SecurityException) {
            ScanStartResult.PERMISSION_DENIED
        }
    }

    override fun stopScan() {
        // Any SecurityException propagates to the caller, which logs
        // "SecurityException during stopScan." — mirroring the original try/catch home.
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }
}
