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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The crisp per-step tick used for sliders and toggles, resolved by reflection because
 * `HapticFeedbackType.SegmentTick` only exists on newer Compose Foundation versions. Falls back to
 * `SegmentFrequentTick`, then to `TextHandleMove`, which exists everywhere.
 *
 * Extracted from four near-identical copies of this block (MainActivity, HomeScreen,
 * ExpressiveSlider, HapticBouncySlider) — HomeScreen's copy was missing the middle fallback.
 */
@Composable
fun rememberExpressiveHapticType(): HapticFeedbackType = remember {
    runCatching {
        val companion = HapticFeedbackType.Companion
        companion::class.java.getMethod("getSegmentTick").invoke(companion) as HapticFeedbackType
    }.getOrElse {
        runCatching {
            val companion = HapticFeedbackType.Companion
            companion::class.java.getMethod("getSegmentFrequentTick").invoke(companion) as HapticFeedbackType
        }.getOrElse {
            HapticFeedbackType.TextHandleMove
        }
    }
}

/**
 * Dims content to the Material disabled opacity and swallows every touch that lands inside it, so a
 * card can read as unavailable instead of disappearing.
 *
 * Used by the Home control deck while power is off: hiding the CCT/brightness/colour cards left the
 * screen nearly blank at the moment the user most needs a hint, with nothing pointing at the power
 * button that brings them back.
 */
fun Modifier.inertWhen(inert: Boolean): Modifier = this
    .alpha(if (inert) 0.38f else 1f)
    .then(
        if (!inert) Modifier else Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    )

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
