package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric provides a real android.graphics.Bitmap implementation (getPixel/setPixel behave
// correctly), unlike plain JVM unit tests where these Android SDK stubs throw at runtime.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CameraCalibrationLogicTest {

    private fun bitmapOf(width: Int, height: Int, colorAt: (x: Int, y: Int) -> Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, colorAt(x, y))
            }
        }
        return bitmap
    }

    // === sampleAlongLine ===

    @Test
    fun `samples distinct colors at each position along a horizontal line`() {
        // 10x1 strip where pixel x has color (x*20, 0, 0) — zero sample radius so neighbor
        // averaging can't blend adjacent, differently-colored pixels together.
        val bitmap = bitmapOf(10, 1) { x, _ -> Color.rgb(x * 20, 0, 0) }

        val samples = sampleAlongLine(bitmap, Offset(0f, 0f), Offset(9f, 0f), count = 10, sampleRadiusPx = 0)

        assertEquals(10, samples.size)
        samples.forEachIndexed { index, sample ->
            assertEquals(index, sample.positionIndex)
            assertEquals(index * 20, sample.r)
            assertEquals(0, sample.g)
            assertEquals(0, sample.b)
        }
    }

    @Test
    fun `averages a neighborhood on a solid color bitmap`() {
        val bitmap = bitmapOf(20, 20) { _, _ -> Color.rgb(100, 150, 200) }

        val samples = sampleAlongLine(bitmap, Offset(5f, 5f), Offset(15f, 15f), count = 4, sampleRadiusPx = 3)

        assertEquals(4, samples.size)
        samples.forEach { sample ->
            assertEquals(100, sample.r)
            assertEquals(150, sample.g)
            assertEquals(200, sample.b)
        }
    }

    @Test
    fun `count of zero or less returns no samples`() {
        val bitmap = bitmapOf(4, 4) { _, _ -> Color.BLACK }

        assertTrue(sampleAlongLine(bitmap, Offset.Zero, Offset(3f, 3f), count = 0).isEmpty())
        assertTrue(sampleAlongLine(bitmap, Offset.Zero, Offset(3f, 3f), count = -5).isEmpty())
    }

    @Test
    fun `single position samples at p1`() {
        val bitmap = bitmapOf(4, 4) { x, _ -> Color.rgb(x * 10, 0, 0) }

        val samples = sampleAlongLine(bitmap, Offset(1f, 0f), Offset(3f, 0f), count = 1, sampleRadiusPx = 0)

        assertEquals(1, samples.size)
        assertEquals(10, samples[0].r) // x=1
    }

    @Test
    fun `out-of-bounds endpoints are clamped instead of crashing`() {
        val bitmap = bitmapOf(4, 4) { _, _ -> Color.rgb(5, 5, 5) }

        val samples = sampleAlongLine(bitmap, Offset(-50f, -50f), Offset(50f, 50f), count = 3, sampleRadiusPx = 1)

        assertEquals(3, samples.size)
        samples.forEach { assertEquals(5, it.r) }
    }

    // === getAverageColor ===

    @Test
    fun `getAverageColor averages a uniform bitmap exactly`() {
        val bitmap = bitmapOf(5, 5) { _, _ -> Color.rgb(30, 60, 90) }

        val average = getAverageColor(bitmap)

        assertEquals(30, average[0])
        assertEquals(60, average[1])
        assertEquals(90, average[2])
    }
}
