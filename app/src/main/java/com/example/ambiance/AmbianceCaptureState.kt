package com.example.ambiance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton state-holder bridging live capture frame data to Compose UI without tight coupling.
 */
object AmbianceCaptureState {
    private val _zoneColors = MutableStateFlow<List<ZoneColor>>(emptyList())
    val zoneColors: StateFlow<List<ZoneColor>> = _zoneColors.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _diagnosticLog = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLog: StateFlow<List<String>> = _diagnosticLog.asStateFlow()
    private const val MAX_LOG_ENTRIES = 5000

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    fun startRecording() {
        _diagnosticLog.value = emptyList()
        _isRecording.value = true
    }

    fun stopRecording() {
        _isRecording.value = false
    }

    fun logDiagnostic(entry: String) {
        if (!_isRecording.value) return
        val current = _diagnosticLog.value
        val updated = if (current.size >= MAX_LOG_ENTRIES) current.drop(1) + entry else current + entry
        _diagnosticLog.value = updated
    }

    fun updateZoneColors(colors: List<ZoneColor>) {
        _zoneColors.value = colors
    }

    fun setIsActive(active: Boolean) {
        _isActive.value = active
    }
}
