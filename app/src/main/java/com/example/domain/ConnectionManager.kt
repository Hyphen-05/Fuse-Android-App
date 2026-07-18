package com.example.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed class ConnectionState {
    object Connected : ConnectionState()
    object Connecting : ConnectionState()
    data class Disconnected(val isManual: Boolean) : ConnectionState()
}

class ConnectionManager {
    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    fun connect(address: String) {
        _connectionStates.update { map ->
            map + (address to ConnectionState.Connecting)
        }
    }

    fun setConnected(address: String) {
        _connectionStates.update { map ->
            map + (address to ConnectionState.Connected)
        }
    }

    fun disconnect(address: String, manual: Boolean) {
        _connectionStates.update { map ->
            map + (address to ConnectionState.Disconnected(manual))
        }
    }

    fun isManuallyDisconnected(address: String): Boolean {
        val state = _connectionStates.value[address]
        return state is ConnectionState.Disconnected && state.isManual
    }
}
