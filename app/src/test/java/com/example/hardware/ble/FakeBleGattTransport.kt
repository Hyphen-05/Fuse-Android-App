package com.example.hardware.ble

import android.bluetooth.BluetoothGatt
import android.content.Context
import java.util.UUID

/**
 * Deterministic, fully synchronous fake of [BleGattTransport] for JUnit tests — no real GATT stack
 * or threading. Registered callbacks/hooks are stored; call the `emit*` methods to synchronously
 * invoke them, as if the platform GATT callback had fired. Assertion hooks: [isConnected],
 * [connectCalls], [writtenCommands], [disconnectedAddresses], [pacingSet], [resetAllPacingCalls].
 *
 * [registrationResult] controls what [registerDuoCoCharacteristic] returns so tests can drive both
 * the write-ready and characteristic-not-found paths.
 */
class FakeBleGattTransport(
    var registrationResult: CharacteristicRegistration =
        CharacteristicRegistration.Registered(
            address = "AA:BB:CC:DD:EE:FF",
            charUuid = UUID.fromString("0000ffd9-0000-1000-8000-00805f9b34fb"),
            pacingMs = 50,
            ackSupported = false
        )
) : BleGattTransport {

    // Registered callbacks/hooks.
    var onConnectionStateChange: ((String, Int, Int) -> Unit)? = null
        private set
    var onServicesDiscovered: ((String, Int) -> Unit)? = null
        private set
    var onCharacteristicWrite: ((String, Int) -> Unit)? = null
        private set
    var onLog: ((String) -> Unit)? = null
        private set
    var pacingProvider: ((String) -> Int)? = null
        private set
    var calibrate: ((String, ByteArray) -> ByteArray)? = null
        private set
    var onFpsUpdate: ((String, Int) -> Unit)? = null
        private set
    var diagAttribution: ((String) -> String)? = null
        private set

    // Assertion state.
    val connectCalls = mutableListOf<String>()
    val disconnectedAddresses = mutableListOf<String>()
    val writtenCommands = mutableListOf<Pair<String, ByteArray>>()
    val writtenCommandPriorities = mutableListOf<Triple<String, Float, Boolean>>()
    val writeCompletedAddresses = mutableListOf<String>()
    val requestPriorityCalls = mutableListOf<String>()
    val discoverServicesCalls = mutableListOf<String>()
    val onConnectedCalls = mutableListOf<String>()
    val pacingSet = mutableListOf<Pair<String, Int>>()
    var resetAllPacingCalls = 0
        private set
    var restoreWriteManagersCalls = 0
        private set

    private val connected = mutableSetOf<String>()
    private val writeManagers = mutableSetOf<String>()
    private val retryAttempts = mutableMapOf<String, Int>()

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

    override fun connect(context: Context, address: String) {
        connectCalls.add(address)
        connected.add(address)
    }

    override fun onConnected(address: String) {
        onConnectedCalls.add(address)
        connected.add(address)
        retryAttempts.remove(address)
    }

    override fun requestHighPriorityAndMtu(address: String) {
        requestPriorityCalls.add(address)
    }

    override fun discoverServices(address: String) {
        discoverServicesCalls.add(address)
    }

    override fun registerDuoCoCharacteristic(address: String): CharacteristicRegistration {
        if (registrationResult is CharacteristicRegistration.Registered) {
            writeManagers.add(address)
        }
        return registrationResult
    }

    override fun writeCommand(address: String, command: ByteArray, priority: Float, bypassPacing: Boolean) {
        writtenCommands.add(address to command)
        writtenCommandPriorities.add(Triple(address, priority, bypassPacing))
    }

    override fun notifyWriteCompleted(address: String) {
        writeCompletedAddresses.add(address)
    }

    override fun getPacingMs(address: String, default: Int): Int {
        return pacingSet.lastOrNull { it.first == address }?.second ?: default
    }

    override fun removeConnection(address: String): BluetoothGatt? {
        disconnectedAddresses.add(address)
        connected.remove(address)
        writeManagers.remove(address)
        return null
    }

    override fun rawDisconnectAndClose(gatt: BluetoothGatt?) {
        // No-op: no real gatt in tests.
    }

    override fun isConnected(address: String): Boolean = connected.contains(address)

    override fun activeConnectionAddresses(): Set<String> = connected.toSet()

    override fun deviceWriteManagerAddresses(): Set<String> = writeManagers.toSet()

    override fun getRetryAttempt(address: String): Int = retryAttempts[address] ?: 0

    override fun setRetryAttempt(address: String, attempt: Int) {
        retryAttempts[address] = attempt
    }

    override fun setPacing(address: String, ms: Int) {
        pacingSet.add(address to ms)
    }

    override fun resetAllPacing(ms: Int) {
        resetAllPacingCalls++
    }

    override fun restoreWriteManagers(onRestored: (String) -> Unit) {
        restoreWriteManagersCalls++
    }

    // --- synchronous callback emitters (as if the platform GATT callback fired) ---

    fun emitConnectionStateChange(address: String, status: Int, newState: Int) {
        onConnectionStateChange?.invoke(address, status, newState)
    }

    fun emitServicesDiscovered(address: String, status: Int) {
        onServicesDiscovered?.invoke(address, status)
    }

    fun emitCharacteristicWrite(address: String, status: Int) {
        onCharacteristicWrite?.invoke(address, status)
    }
}
