package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.roundToInt

@Composable
fun HapticBouncySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    totalSteps: Int
) {
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

    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    
    // Scale for bouncy effect on release
    // But wait, the prompt says "On release (onValueChangeFinished), play a subtle settle-bounce 
    // (reuse the existing joyfulPress spring constants — DampingRatioMediumBouncy/StiffnessMedium — don't redefine them) on the thumb/track."
    // Actually, animateFloatAsState animating a scale value would bounce on change.
    // If we want it to bounce on release, we can drop scale when dragged, and bounce back to 1.0f on release.
    // Or just "play a subtle settle-bounce... on the thumb/track".
    // Bouncing the whole slider:
    var scaleTarget by remember { mutableStateOf(1f) }
    
    LaunchedEffect(isDragged) {
        if (isDragged) {
            scaleTarget = 0.98f
        } else {
            scaleTarget = 1.0f
        }
    }

    val scale by animateFloatAsState(
        targetValue = scaleTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "sliderBouncyScale"
    )

    // Calculate ticks
    var lastTick by remember { mutableIntStateOf(-1) }
    
    LaunchedEffect(value, totalSteps, valueRange) {
        if (isDragged && totalSteps > 0) {
            val rangeSize = valueRange.endInclusive - valueRange.start
            val normalizedValue = (value - valueRange.start) / rangeSize
            val currentTick = (normalizedValue * totalSteps).roundToInt()
            
            if (lastTick != -1 && currentTick != lastTick) {
                hapticFeedback.performHapticFeedback(hapticType)
            }
            lastTick = currentTick
        } else if (!isDragged) {
            lastTick = -1
        }
    }

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.graphicsLayer(scaleX = scale, scaleY = scale),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource
    )
}
