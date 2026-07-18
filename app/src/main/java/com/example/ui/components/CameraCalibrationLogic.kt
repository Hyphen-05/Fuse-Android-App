package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
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
    
    // We only care about a central ROI
    val cx = image.width / 2
    val cy = image.height / 2
    val w = image.width / 24
    val h = image.height / 24
    
    yuvImage.compressToJpeg(Rect(cx - w/2, cy - h/2, cx + w/2, cy + h/2), 100, out)
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
