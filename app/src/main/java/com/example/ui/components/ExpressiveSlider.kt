package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

@Composable
fun ExpressiveSlider(
    value: Int, // 0 - 100
    onValueChange: (Int) -> Unit,
    labelPrefix: String = "Brightness",
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    trackBrush: Brush? = null,
    modifier: Modifier = Modifier
) {
    // Resolve inactiveColor to ensure high contrast even if callers explicitly pass the old low-contrast color
    val resolvedInactiveColor = if (inactiveColor == MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    } else {
        inactiveColor
    }

    var dragPercent by remember(value) { mutableStateOf(value / 100f) }

    val animatedPercent by animateFloatAsState(
        targetValue = dragPercent,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "slider_percent"
    )

    val hapticFeedback = LocalHapticFeedback.current
    val hapticType = remember {
        runCatching {
            val companion = HapticFeedbackType.Companion
            val method = companion::class.java.getMethod("getSegmentTick")
            method.invoke(companion) as HapticFeedbackType
        }.getOrElse {
            runCatching {
                val companion = HapticFeedbackType.Companion
                val method = companion::class.java.getMethod("getSegmentFrequentTick")
                method.invoke(companion) as HapticFeedbackType
            }.getOrElse {
                HapticFeedbackType.TextHandleMove
            }
        }
    }

    var isTapped by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    val isDragged = isTapped || isDragging

    var scaleTarget by remember { mutableStateOf(1f) }
    LaunchedEffect(isDragged) {
        scaleTarget = if (isDragged) 0.98f else 1.0f
    }
    val scale by animateFloatAsState(
        targetValue = scaleTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "sliderBouncyScale"
    )

    var lastTick by remember { mutableIntStateOf(-1) }
    LaunchedEffect(value, isDragged) {
        if (isDragged) {
            val currentTick = value
            if (lastTick != currentTick) {
                hapticFeedback.performHapticFeedback(hapticType)
            }
            lastTick = currentTick
        } else {
            lastTick = -1
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .let {
                if (trackBrush != null) {
                    it.background(trackBrush)
                } else {
                    it.background(resolvedInactiveColor)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        isTapped = true
                        val percent = (offset.x / size.width).coerceIn(0f, 1f)
                        dragPercent = percent
                        onValueChange((percent * 100).toInt())
                        val success = tryAwaitRelease()
                        isTapped = false
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false }
                ) { change, dragAmount ->
                    change.consume()
                    val percent = (change.position.x / size.width).coerceIn(0f, 1f)
                    dragPercent = percent
                    onValueChange((percent * 100).toInt())
                }
            }
    ) {
        val width = constraints.maxWidth.toFloat()

        if (trackBrush != null) {
            // Inactive track overlay on the right (unselected side)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(1f - animatedPercent)
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        } else {
            // Active Track (Filled)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedPercent)
                    .background(activeColor)
            )
        }


    }
}
