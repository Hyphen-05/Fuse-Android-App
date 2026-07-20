package com.example.hardware.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

// Phase 5, part B: moved verbatim out of RgbControllerViewModel.kt — raw AudioTrack synthesis
// for the calibration metronome click, not tangled up with the ViewModel's other responsibilities.
// Lives in hardware/audio/ alongside AudioCaptureSource et al. since it drives real audio-output
// hardware (AudioTrack), unlike the pure-logic BeatDetector/AudioDspProcessor in core/audio/.
class MetronomePlayer {
    private val sampleRate = 44100
    private var clickData: ShortArray? = null

    init {
        val durationMs = 50
        val numSamples = (sampleRate * durationMs) / 1000
        clickData = ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val frequency = 1200.0 // crisp tick frequency
            val envelope = (numSamples - i).toDouble() / numSamples
            val value = Math.sin(2.0 * Math.PI * frequency * t) * envelope * Short.MAX_VALUE
            value.toInt().toShort()
        }
    }

    fun playClick() {
        try {
            val data = clickData ?: return
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                data.size * 2,
                AudioTrack.MODE_STATIC
            )
            track.write(data, 0, data.size)
            track.play()
            Thread {
                try {
                    Thread.sleep(120)
                    track.stop()
                    track.release()
                } catch (e: Exception) {}
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
