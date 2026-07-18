package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
fun Modifier.joyfulPress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true
): Modifier {
    var isPressed by remember { mutableStateOf(false) }

    if (!enabled) {
        isPressed = false
    }

    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.92f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "joyfulPressScale"
    )

    DisposableEffect(Unit) {
        onDispose {
            isPressed = false
        }
    }

    val haptic = LocalHapticFeedback.current
    LaunchedEffect(interactionSource, enabled) {
        if (enabled) {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        isPressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    is PressInteraction.Release,
                    is PressInteraction.Cancel -> {
                        isPressed = false
                    }
                }
            }
        }
    }

    return this.graphicsLayer(scaleX = scale, scaleY = scale)
}
