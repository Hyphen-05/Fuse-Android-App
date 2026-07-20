package com.example.hardware.audio

/**
 * In-place Radix-2 Cooley-Tukey Fast Fourier Transform.
 *
 * Moved verbatim (algorithm unchanged) out of `RgbControllerViewModel.kt`'s private nested
 * `Fft` object as part of Phase 4 hardware isolation — its only caller was the AudioRecord
 * capture backend's read loop, which now lives in [AudioRecordCaptureSource]. Not part of the
 * pure DSP pipeline (that's `com.example.core.audio.AudioDspProcessor`) because it operates on
 * raw PCM samples as a step of spectrum *reconstruction*, not spectrum *interpretation*.
 */
internal object Fft {
    fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n == 0) return

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR

                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // Cooley-Tukey decimation-in-time
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlenR = Math.cos(ang).toFloat()
            val wlenI = Math.sin(ang).toFloat()
            for (i in 0 until n step len) {
                var wR = 1.0f
                var wI = 0.0f
                val halfLen = len shr 1
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]

                    val tR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                    val tI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR

                    real[i + k] = uR + tR
                    imag[i + k] = uI + tI

                    real[i + k + halfLen] = uR - tR
                    imag[i + k + halfLen] = uI - tI

                    val nextWR = wR * wlenR - wI * wlenI
                    wI = wR * wlenI + wI * wlenR
                    wR = nextWR
                }
            }
            len = len shl 1
        }
    }
}
