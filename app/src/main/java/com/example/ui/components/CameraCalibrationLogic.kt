package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import com.example.core.modecapture.SpatialSample
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

// roi = null decodes the full frame (needed for sampleAlongLine, which samples across the whole
// strip); pass a Rect to keep the original central-patch-only behavior.
fun imageProxyToBitmap(image: ImageProxy, roi: Rect? = null): Bitmap? {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream()

    val cropRect = roi ?: Rect(0, 0, image.width, image.height)

    yuvImage.compressToJpeg(cropRect, 100, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

fun getAverageColor(bitmap: Bitmap): IntArray {
    var r = 0L
    var g = 0L
    var b = 0L
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    for (pixel in pixels) {
        r += android.graphics.Color.red(pixel)
        g += android.graphics.Color.green(pixel)
        b += android.graphics.Color.blue(pixel)
    }
    val count = pixels.size.coerceAtLeast(1)
    return intArrayOf((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
}

// Samples `count` evenly spaced points along the line from p1 to p2 (bitmap pixel coordinates),
// each averaged over a (2*sampleRadiusPx+1) square neighborhood to reduce sensor noise. Used to
// reconstruct color-over-position-over-time for a whole LED strip from one still frame.
fun sampleAlongLine(
    bitmap: Bitmap,
    p1: Offset,
    p2: Offset,
    count: Int,
    sampleRadiusPx: Int = 3
): List<SpatialSample> {
    if (count <= 0) return emptyList()
    val maxX = bitmap.width - 1
    val maxY = bitmap.height - 1
    if (maxX < 0 || maxY < 0) return emptyList()

    val samples = ArrayList<SpatialSample>(count)
    for (i in 0 until count) {
        val t = if (count == 1) 0f else i.toFloat() / (count - 1)
        val cx = (p1.x + (p2.x - p1.x) * t).roundToInt().coerceIn(0, maxX)
        val cy = (p1.y + (p2.y - p1.y) * t).roundToInt().coerceIn(0, maxY)

        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (dy in -sampleRadiusPx..sampleRadiusPx) {
            val py = cy + dy
            if (py < 0 || py > maxY) continue
            for (dx in -sampleRadiusPx..sampleRadiusPx) {
                val px = cx + dx
                if (px < 0 || px > maxX) continue
                val pixel = bitmap.getPixel(px, py)
                r += android.graphics.Color.red(pixel)
                g += android.graphics.Color.green(pixel)
                b += android.graphics.Color.blue(pixel)
                n++
            }
        }
        val pixelCount = n.coerceAtLeast(1)
        samples.add(SpatialSample(i, (r / pixelCount).toInt(), (g / pixelCount).toInt(), (b / pixelCount).toInt()))
    }
    return samples
}
