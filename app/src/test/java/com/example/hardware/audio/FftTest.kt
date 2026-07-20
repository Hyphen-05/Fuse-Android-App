package com.example.hardware.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/**
 * Pure-math coverage for the in-place radix-2 FFT moved verbatim out of the ViewModel in
 * Phase 4. No Android hardware excuse for this gap (unlike the capture-source classes) — this
 * is plain numeric code, fully testable against known closed-form transforms.
 */
class FftTest {

    @Test
    fun `empty array is left untouched`() {
        val real = FloatArray(0)
        val imag = FloatArray(0)

        Fft.fft(real, imag)

        assertEquals(0, real.size)
        assertEquals(0, imag.size)
    }

    @Test
    fun `single-sample array is unchanged (trivial DFT of one point)`() {
        val real = floatArrayOf(3.5f)
        val imag = floatArrayOf(-1.2f)

        Fft.fft(real, imag)

        assertEquals(3.5f, real[0], 0.0001f)
        assertEquals(-1.2f, imag[0], 0.0001f)
    }

    @Test
    fun `impulse at index 0 produces a flat spectrum`() {
        val n = 8
        val real = FloatArray(n) { if (it == 0) 1f else 0f }
        val imag = FloatArray(n)

        Fft.fft(real, imag)

        for (k in 0 until n) {
            assertEquals("real[$k]", 1f, real[k], 0.0001f)
            assertEquals("imag[$k]", 0f, imag[k], 0.0001f)
        }
    }

    @Test
    fun `constant DC signal collapses to a single spike at bin 0`() {
        val n = 8
        val real = FloatArray(n) { 1f }
        val imag = FloatArray(n)

        Fft.fft(real, imag)

        assertEquals(n.toFloat(), real[0], 0.001f)
        assertEquals(0f, imag[0], 0.001f)
        for (k in 1 until n) {
            assertEquals("real[$k]", 0f, real[k], 0.001f)
            assertEquals("imag[$k]", 0f, imag[k], 0.001f)
        }
    }

    @Test
    fun `single-frequency cosine produces symmetric peaks at bin 1 and bin N-1`() {
        val n = 8
        val real = FloatArray(n) { i -> cos(2.0 * PI * 1.0 * i / n).toFloat() }
        val imag = FloatArray(n)

        Fft.fft(real, imag)

        fun magnitude(k: Int) = kotlin.math.sqrt(real[k] * real[k] + imag[k] * imag[k])

        // A pure cos(2*pi*n/N) signal has energy only at bin 1 and its conjugate bin N-1, each
        // with magnitude N/2.
        assertEquals(n / 2f, magnitude(1), 0.01f)
        assertEquals(n / 2f, magnitude(n - 1), 0.01f)
        for (k in 2..n - 2) {
            assertEquals("magnitude at bin $k should be ~0", 0f, magnitude(k), 0.01f)
        }
    }

    @Test
    fun `zero signal transforms to zero spectrum`() {
        val n = 16
        val real = FloatArray(n)
        val imag = FloatArray(n)

        Fft.fft(real, imag)

        real.forEach { assertEquals(0f, it, 0.0001f) }
        imag.forEach { assertEquals(0f, it, 0.0001f) }
    }
}
