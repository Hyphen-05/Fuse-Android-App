package com.example.ui.components

import android.graphics.Color.HSVToColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun ColorWheel(
    selectedColor: Color,
    onColorChanged: (r: Int, g: Int, b: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var center by remember { mutableStateOf(Offset.Zero) }
    var radius by remember { mutableStateOf(0f) }

    // Convert current RGB to Hue & Saturation to position the handle initially
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (selectedColor.red * 255).toInt(),
        (selectedColor.green * 255).toInt(),
        (selectedColor.blue * 255).toInt(),
        hsv
    )
    val hue = hsv[0]
    val saturation = hsv[1]

    // Calculate handle position based on hue and saturation
    val angleRad = Math.toRadians(hue.toDouble())
    val handleX = center.x + radius * saturation * cos(angleRad).toFloat()
    val handleY = center.y + radius * saturation * sin(angleRad).toFloat()
    val handlePosition = Offset(handleX, handleY)

    fun updateColorFromOffset(offset: Offset) {
        val dx = offset.x - center.x
        val dy = offset.y - center.y
        val dist = sqrt(dx * dx + dy * dy)
        val r = min(radius, dist)

        val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360
        val sat = if (radius > 0) min(1f, r / radius) else 0f

        val rawColor = HSVToColor(floatArrayOf(angle.toFloat(), sat, 1.0f))
        val redValue = android.graphics.Color.red(rawColor)
        val greenValue = android.graphics.Color.green(rawColor)
        val blueValue = android.graphics.Color.blue(rawColor)

        onColorChanged(redValue, greenValue, blueValue)
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        updateColorFromOffset(offset)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        updateColorFromOffset(change.position)
                    }
                }
        ) {
            center = Offset(size.width / 2f, size.height / 2f)
            radius = min(size.width, size.height) / 2f - 12f // Leave padding for handle stroke

            if (radius <= 0f) return@Canvas

            // Draw Hue spectrum
            val colors = List(360) { i ->
                val hsvColor = HSVToColor(floatArrayOf(i.toFloat(), 1.0f, 1.0f))
                Color(hsvColor)
            } + Color(HSVToColor(floatArrayOf(0f, 1.0f, 1.0f))) // Close the circle

            drawCircle(
                brush = Brush.sweepGradient(colors, center),
                radius = radius
            )

            // Draw Saturation overlay (white fade towards the center)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius
            )

            // Draw beautiful outline border for the wheel
            drawCircle(
                color = Color.White.copy(alpha = 0.5f),
                radius = radius,
                style = Stroke(width = 3f)
            )

            // Draw active handle selection coordinate with spring-like aesthetic
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = 16f,
                center = handlePosition
            )
            drawCircle(
                color = Color.White,
                radius = 12f,
                center = handlePosition,
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = selectedColor,
                radius = 8f,
                center = handlePosition
            )
        }
    }
}
