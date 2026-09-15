package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import kotlinx.coroutines.launch

/**
 * The power switch, built to feel like a physical switch rather than a tinted icon. The top bar has one
 * for every strip at once, and each device tile on Home has one for its own strip.
 *
 * Joe, 2026-09-14: *"the power on/off button and animation and haptics need an overhaul they could be
 * so much nicer and more satisfying."* History and his verdicts on each round are in
 * `docs/power-button.md`.
 *
 * Round 2, from his verdict on round 1: **both resting states are a plain circle**, and the expressive
 * `MaterialShapes` appear only *during* a switch. The outline morphs out into a shape while it spins,
 * then settles back into the circle. Round 1 rested on a cookie, and he said on "looks wrong".
 *
 * - **Touch-down**: the button squashes. No haptic here, because the switch is one click, not a tick
 *   followed by a click.
 * - **On**: spins clockwise through a soft burst, colour floods out from the centre, and there is one
 *   full-strength click.
 * - **Off**: spins back the other way through a pinched clover, colour drains into the centre, and the
 *   click is lighter. Round 1's off was "still too boring", so it has its own shape and direction
 *   instead of replaying on more quietly.
 *
 * Round 2 also drew a ring flying off (on) or collapsing in (off). Joe asked for it to go: the
 * transition should be the shape morph and nothing else.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PowerButton(
    isOn: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    testTag: String = "power_button",
    label: String = "Power"
) {
    val view = LocalView.current
    val haptics = remember(view) { PowerHaptics(view) }

    val onColor = MaterialTheme.colorScheme.primary
    val offColor = MaterialTheme.colorScheme.surfaceVariant
    val onIcon = MaterialTheme.colorScheme.onPrimary
    val offIcon = MaterialTheme.colorScheme.onSurfaceVariant

    // The flood should read as the result of the snap, so it is quick and does not wobble.
    val flood by animateFloatAsState(
        targetValue = if (isOn) 1f else 0f,
        animationSpec = if (isOn) spring(dampingRatio = 1f, stiffness = 900f) else tween(260),
        label = "powerFlood"
    )

    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 1400f),
        label = "powerPress"
    )
    val kick = remember { Animatable(1f) }
    // How far the outline has left the circle: 0 is the resting circle, 1 is the full expressive
    // shape. It only leaves 0 while switching.
    val shapeAmount = remember { Animatable(0f) }
    // Accumulates instead of resetting, so a tap mid-switch never makes the shape jump.
    val spin = remember { Animatable(0f) }
    // Which gesture is playing, which decides the shape.
    var switchingOn by remember { mutableStateOf(isOn) }

    // The first composition shows the state it finds without playing a switch that nobody pressed.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(isOn) {
        if (!settled) { settled = true; return@LaunchedEffect }
        switchingOn = isOn
        launch {
            spin.animateTo(
                spin.value + if (isOn) 180f else -135f,
                spring(dampingRatio = 0.75f, stiffness = 180f)
            )
        }
        launch {
            // Out fast, then back with a little bounce. The bounce is clamped out of the morph, so
            // the shape it lands on stays a circle.
            shapeAmount.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 1600f))
            shapeAmount.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 260f))
        }
        kick.snapTo(if (isOn) 1.14f else 0.88f)
        kick.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
    }

    val onMorph = remember { Morph(MaterialShapes.Circle, MaterialShapes.SoftBurst) }
    val offMorph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Clover4Leaf) }
    val path = remember { Path() }
    val matrix = remember { Matrix() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .testTag(testTag)
            .semantics {
                role = Role.Switch
                contentDescription = label
                stateDescription = if (isOn) "On" else "Off"
                // The tap handler below is raw pointer input, which accessibility services cannot
                // see. Without this action TalkBack would announce a switch it cannot flip.
                onClick(label = if (isOn) "Turn off" else "Turn on") {
                    val next = !isOn
                    if (next) haptics.on() else haptics.off()
                    onToggle(next)
                    true
                }
            }
            .pointerInput(isOn) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) {
                            val next = !isOn
                            if (next) haptics.on() else haptics.off()
                            onToggle(next)
                        }
                    }
                )
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = pressScale * kick.value
                    scaleX = s
                    scaleY = s
                }
                .drawBehind {
                    path.rewind()
                    val morph = if (switchingOn) onMorph else offMorph
                    morph.toPath(shapeAmount.value.coerceIn(0f, 1f), path)
                    // MaterialShapes polygons are normalised into the unit square. Place them in
                    // this box, spinning about the centre.
                    matrix.reset()
                    matrix.translate(this.size.width / 2f, this.size.height / 2f)
                    matrix.rotateZ(spin.value)
                    matrix.scale(this.size.width, this.size.height)
                    matrix.translate(-0.5f, -0.5f)
                    path.transform(matrix)

                    val centre = Offset(this.size.width / 2f, this.size.height / 2f)
                    val reach = this.size.minDimension * 0.75f

                    clipPath(path) {
                        drawRect(offColor)
                        if (flood > 0.001f) drawCircle(onColor, radius = reach * flood, center = centre)
                    }
                }
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = lerp(offIcon, onIcon, flood.coerceIn(0f, 1f)),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * The switch's haptic, which is one click each way.
 *
 * Joe on round 1's rise-then-click and fall-then-tick: *"isnt quite right for a switch, it should be
 * more like a click or something."* The ramps felt like swells. Each direction is now a single
 * `PRIMITIVE_CLICK`, full strength for on and lighter for off, like one switch clicking both ways.
 *
 * It is kept apart from the composable so the primitive can be tuned by thumb without touching the
 * drawing.
 */
private class PowerHaptics(private val view: View) {

    private val vibrator: Vibrator? = run {
        val context: Context = view.context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Play [effect], or fall back to [fallback] if the vibrator refuses.
     *
     * A haptic must never be able to take the app down. The first version called `vibrate` bare, the
     * manifest lacked `VIBRATE`, and touching the power button crashed on Joe's Pixel - the one
     * handset where the composition path actually runs.
     */
    private fun play(effect: () -> VibrationEffect, fallback: Int) {
        try {
            vibrator?.vibrate(effect())
        } catch (e: SecurityException) {
            view.performHapticFeedback(fallback)
        } catch (e: IllegalArgumentException) {
            view.performHapticFeedback(fallback)
        }
    }

    private fun click(scale: Float, fallback: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            vibrator?.hasVibrator() == true &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
        ) {
            play({
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                    .compose()
            }, fallback)
        } else {
            view.performHapticFeedback(fallback)
        }
    }

    fun on() = click(1f, HapticFeedbackConstants.VIRTUAL_KEY)

    fun off() = click(0.6f, HapticFeedbackConstants.KEYBOARD_TAP)
}
