package com.example.hardware.audio

/**
 * Deterministic, fully synchronous fake of [AudioCaptureSource] for JUnit tests — no real
 * threading. [start] just records the callbacks; call [emitFrame]/[emitError] from the test to
 * synchronously invoke them, and inspect [stopCallCount]/[isStarted] for wiring assertions.
 */
class FakeAudioCaptureSource(
    override val backend: AudioBackend = AudioBackend.AUDIO_RECORD,
    private val startResult: Boolean = true
) : AudioCaptureSource {

    var isStarted: Boolean = false
        private set
    var stopCallCount: Int = 0
        private set
    var lastIsRunningProbe: (() -> Boolean)? = null
        private set

    private var onFrame: ((AudioCaptureFrame) -> Unit)? = null
    private var onLog: ((String) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null

    override fun start(
        onFrame: (AudioCaptureFrame) -> Unit,
        onLog: (String) -> Unit,
        isRunning: () -> Boolean,
        onError: (Throwable) -> Unit
    ): Boolean {
        this.onFrame = onFrame
        this.onLog = onLog
        this.onError = onError
        this.lastIsRunningProbe = isRunning
        isStarted = startResult
        return startResult
    }

    override fun stop() {
        stopCallCount++
        isStarted = false
    }

    /** Synchronously invokes the stored onFrame callback, as if a real frame had arrived. */
    fun emitFrame(frame: AudioCaptureFrame) {
        onFrame?.invoke(frame)
    }

    /** Synchronously invokes the stored onError callback. */
    fun emitError(t: Throwable) {
        onError?.invoke(t)
    }

    /** Synchronously invokes the stored onLog callback, for assertions on forwarded log text. */
    fun emitLog(message: String) {
        onLog?.invoke(message)
    }
}
