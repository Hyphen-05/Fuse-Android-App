package com.example.hardware.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.core.protocol.DuoCoProtocol
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Result of [BleGattTransport.registerDuoCoCharacteristic]. Mirrors the two outcomes of the former
 * `RgbControllerViewModel.findDuoCoCharacteristicForGatt` + `registerCharacteristic`: either the
 * write characteristic was found and a [DeviceWriteManager] was constructed ([Registered]), or the
 * DuoCo characteristic could not be located ([NotFound]). The transport does the raw GATT plumbing
 * (map writes + manager construction); the ViewModel reacts to this result to update `_uiState`
 * and emit its log lines, keeping business state in `presentation`.
 */
sealed interface CharacteristicRegistration {
    data class Registered(
        val address: String,
        val charUuid: UUID,
        val pacingMs: Int,
        val ackSupported: Boolean
    ) : CharacteristicRegistration

    data class NotFound(val address: String) : CharacteristicRegistration
}

/**
 * Hardware-facing per-device GATT transport. Owns the raw [BluetoothGatt] objects, the single
 * [BluetoothGattCallback], and the connection bookkeeping (`activeConnections`,
 * `writeCharacteristics`, `deviceWriteManagers`, `retryAttempts`, `connectionScope`) that used to
 * live on the `RgbControllerViewModel` companion object.
 *
 * Being a long-lived singleton (constructed once via `AppContainer`, surviving ViewModel
 * recreation), it always invokes whichever callback set was most recently registered via
 * [registerCallbacks]/[registerWriteHooks]. That is what removes the old
 * `getActiveInstance() ?: this@RgbControllerViewModel` self-reference hunt in the GATT callback —
 * the callback no longer needs to find "the active VM", it just calls the registered lambdas.
 *
 * The ViewModel keeps its business logic (backoff/retry decisions, `_uiState` updates,
 * `connectionManager` calls) and drives the transport's address-keyed operations in the original
 * order; the transport never exposes a raw [BluetoothGatt] back to the ViewModel.
 */
interface BleGattTransport {

    /** Points the live GATT-callback references at the current ViewModel. Called once from init. */
    fun registerCallbacks(
        onConnectionStateChange: (address: String, status: Int, newState: Int) -> Unit,
        onServicesDiscovered: (address: String, status: Int) -> Unit,
        onCharacteristicWrite: (address: String, status: Int) -> Unit,
        onLog: (String) -> Unit
    )

    /** Points the [DeviceWriteManager] dependency hooks at the current ViewModel. Called once from init. */
    fun registerWriteHooks(
        pacingProvider: (address: String) -> Int,
        calibrate: (address: String, command: ByteArray) -> ByteArray,
        onFpsUpdate: (address: String, fps: Int) -> Unit,
        diagAttribution: (address: String) -> String
    )

    /** Raw `getRemoteDevice(...).connectGatt(...)` + store into activeConnections. May throw. */
    fun connect(context: Context, address: String)

    /** STATE_CONNECTED bookkeeping: pin the callback gatt into activeConnections + clear retries. */
    fun onConnected(address: String)

    /** `requestConnectionPriority(HIGH)` + `requestMtu(512)`, with the original try/catch semantics. */
    fun requestHighPriorityAndMtu(address: String)

    /** `gatt.discoverServices()`, with the original SecurityException-swallow semantics. */
    fun discoverServices(address: String)

    /** Finds the DuoCo write characteristic and, if present, builds this device's write manager. */
    fun registerDuoCoCharacteristic(address: String): CharacteristicRegistration

    /** Enqueue a command onto the device's write manager (`deviceWriteManagers[address].updateCommand`). */
    fun writeCommand(address: String, command: ByteArray)

    /** Forward a GATT write-complete ack to the device's write manager. */
    fun notifyWriteCompleted(address: String)

    /** Remove all three per-device maps and return the removed [BluetoothGatt] (for disconnect/close). */
    fun removeConnection(address: String): BluetoothGatt?

    /** Raw `gatt.disconnect(); gatt.close()`. May throw SecurityException (caller logs). */
    fun rawDisconnectAndClose(gatt: BluetoothGatt?)

    fun isConnected(address: String): Boolean
    fun activeConnectionAddresses(): Set<String>
    fun deviceWriteManagerAddresses(): Set<String>

    fun getRetryAttempt(address: String): Int
    fun setRetryAttempt(address: String, attempt: Int)

    fun setPacing(address: String, ms: Int)
    fun resetAllPacing(ms: Int)

    /** Reconstruct write managers for surviving active connections; [onRestored] per address (for logging). */
    fun restoreWriteManagers(onRestored: (address: String) -> Unit)
}

@SuppressLint("MissingPermission")
class AndroidBleGattTransport(private val context: Context) : BleGattTransport {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // State moved off the RgbControllerViewModel companion object.
    private val activeConnections = ConcurrentHashMap<String, BluetoothGatt>()
    private val writeCharacteristics = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val deviceWriteManagers = ConcurrentHashMap<String, DeviceWriteManager>()
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Most-recent gatt seen per address at a callback entry, so address-keyed ops can find it.
    private val callbackGatt = ConcurrentHashMap<String, BluetoothGatt>()

    // Registered callbacks/hooks — re-pointed at the current ViewModel via register*().
    @Volatile private var onConnectionStateChange: (String, Int, Int) -> Unit = { _, _, _ -> }
    @Volatile private var onServicesDiscovered: (String, Int) -> Unit = { _, _ -> }
    @Volatile private var onCharacteristicWrite: (String, Int) -> Unit = { _, _ -> }
    @Volatile private var onLog: (String) -> Unit = {}

    @Volatile private var pacingProvider: (String) -> Int = { 50 }
    @Volatile private var calibrate: (String, ByteArray) -> ByteArray = { _, cmd -> cmd }
    @Volatile private var onFpsUpdate: (String, Int) -> Unit = { _, _ -> }
    @Volatile private var diagAttribution: (String) -> String = { "" }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val address = gatt?.device?.address ?: return
            callbackGatt[address] = gatt
            onConnectionStateChange(address, status, newState)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val address = gatt?.device?.address ?: return
            callbackGatt[address] = gatt
            onServicesDiscovered(address, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            val address = gatt?.device?.address ?: return
            onCharacteristicWrite(address, status)
        }
        // onCharacteristicRead / onCharacteristicChanged were no-ops in the ViewModel; omitted here.
    }

    override fun registerCallbacks(
        onConnectionStateChange: (String, Int, Int) -> Unit,
        onServicesDiscovered: (String, Int) -> Unit,
        onCharacteristicWrite: (String, Int) -> Unit,
        onLog: (String) -> Unit
    ) {
        this.onConnectionStateChange = onConnectionStateChange
        this.onServicesDiscovered = onServicesDiscovered
        this.onCharacteristicWrite = onCharacteristicWrite
        this.onLog = onLog
    }

    override fun registerWriteHooks(
        pacingProvider: (String) -> Int,
        calibrate: (String, ByteArray) -> ByteArray,
        onFpsUpdate: (String, Int) -> Unit,
        diagAttribution: (String) -> String
    ) {
        this.pacingProvider = pacingProvider
        this.calibrate = calibrate
        this.onFpsUpdate = onFpsUpdate
        this.diagAttribution = diagAttribution
    }

    private fun buildWriteManager(
        address: String,
        gatt: BluetoothGatt,
        charac: BluetoothGattCharacteristic
    ) = DeviceWriteManager(
        address = address,
        gatt = gatt,
        charac = charac,
        connectionScope = connectionScope,
        pacingMsProvider = { pacingProvider(address) },
        calibrate = { addr, cmd -> calibrate(addr, cmd) },
        onFpsUpdate = { addr, fps -> onFpsUpdate(addr, fps) },
        diagAttribution = { addr -> diagAttribution(addr) }
    )

    override fun connect(context: Context, address: String) {
        val adapter = bluetoothAdapter ?: return
        val device = adapter.getRemoteDevice(address)
        val gatt = device.connectGatt(context, false, gattCallback)
        activeConnections[address] = gatt
    }

    override fun onConnected(address: String) {
        callbackGatt[address]?.let { activeConnections[address] = it }
        retryAttempts.remove(address)
    }

    override fun requestHighPriorityAndMtu(address: String) {
        val gatt = activeConnections[address] ?: return
        try {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            gatt.requestMtu(512)
        } catch (e: SecurityException) {
            onLog("SecurityException requesting priority or MTU for $address: ${e.message}")
        } catch (e: Exception) {
            onLog("Error requesting priority or MTU for $address: ${e.message}")
        }
    }

    override fun discoverServices(address: String) {
        val gatt = activeConnections[address] ?: return
        try {
            gatt.discoverServices()
        } catch (e: SecurityException) {
            onLog("SecurityException: Service discovery denied for $address.")
        }
    }

    override fun registerDuoCoCharacteristic(address: String): CharacteristicRegistration {
        val gatt = callbackGatt[address] ?: activeConnections[address]
            ?: return CharacteristicRegistration.NotFound(address)

        var found: BluetoothGattCharacteristic? = null
        for (service in gatt.services) {
            val charac = service.getCharacteristic(DuoCoProtocol.CHARACTERISTIC_UUID)
            if (charac != null) {
                found = charac
                break
            }
        }
        if (found == null) {
            outer@ for (service in gatt.services) {
                for (charac in service.characteristics) {
                    if (charac.uuid == DuoCoProtocol.CHARACTERISTIC_UUID) {
                        found = charac
                        break@outer
                    }
                }
            }
        }

        val charac = found ?: return CharacteristicRegistration.NotFound(address)

        writeCharacteristics[address] = charac
        val manager = buildWriteManager(address, gatt, charac)
        deviceWriteManagers[address] = manager

        return CharacteristicRegistration.Registered(
            address = address,
            charUuid = charac.uuid,
            pacingMs = manager.currentPacingMs,
            ackSupported = manager.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
    }

    override fun writeCommand(address: String, command: ByteArray) {
        deviceWriteManagers[address]?.updateCommand(command)
    }

    override fun notifyWriteCompleted(address: String) {
        deviceWriteManagers[address]?.onWriteCompleted()
    }

    override fun removeConnection(address: String): BluetoothGatt? {
        val gatt = activeConnections.remove(address)
        writeCharacteristics.remove(address)
        deviceWriteManagers.remove(address)
        return gatt
    }

    override fun rawDisconnectAndClose(gatt: BluetoothGatt?) {
        gatt?.disconnect()
        gatt?.close()
    }

    override fun isConnected(address: String): Boolean = activeConnections.containsKey(address)

    override fun activeConnectionAddresses(): Set<String> = activeConnections.keys.toSet()

    override fun deviceWriteManagerAddresses(): Set<String> = deviceWriteManagers.keys.toSet()

    override fun getRetryAttempt(address: String): Int = retryAttempts.getOrDefault(address, 0)

    override fun setRetryAttempt(address: String, attempt: Int) {
        retryAttempts[address] = attempt
    }

    override fun setPacing(address: String, ms: Int) {
        deviceWriteManagers[address]?.currentPacingMs = ms
    }

    override fun resetAllPacing(ms: Int) {
        deviceWriteManagers.forEach { (_, manager) -> manager.currentPacingMs = ms }
    }

    override fun restoreWriteManagers(onRestored: (String) -> Unit) {
        activeConnections.forEach { (address, gatt) ->
            val charac = writeCharacteristics[address]
            if (charac != null) {
                deviceWriteManagers[address] = buildWriteManager(address, gatt, charac)
                onRestored(address)
            }
        }
    }
}
