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
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * The top bar's power switch, built to feel like a physical switch rather than a tinted icon.
 *
 * Joe, 2026-09-14: *"the power on/off button and animation and haptics need an overhaul they could be
 * so much nicer and more satisfying."* What it replaced was an `IconButton` whose background
 * crossfaded over 300ms and which gave the same `LongPress` buzz as every other button in the app, on
 * touch-down, whichever way it was switching. So on and off felt identical, and neither felt like
 * anything had been switched.
 *
 * Three things carry the feel, and each is split into a press and a commit, the way a real switch is
 * travel and then a snap:
 *
 * - **Touch-down**: the button squashes and gives a faint low tick. That is travel, not action.
 * - **On**: the shape blooms from a circle into a cookie and twists, colour floods outward from the
 *   centre, a ring flies off the edge, and the haptic rises and then clicks.
 * - **Off**: the flood drains back into the centre, the cookie relaxes into a circle, there is no
 *   ring, and the haptic falls away into a soft tick. Off is deliberately the smaller gesture.
 *
 * Haptics use `VibrationEffect.Composition` primitives where the handset has them (Pixels do). A
 * handset without them gets the nearest `HapticFeedbackConstants`, which is coarser but still
 * different for on and off.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PowerButton(
    isOn: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    val view = LocalView.current
    val haptics = remember(view) { PowerHaptics(view) }

    val onColor = MaterialTheme.colorScheme.primary
    val offColor = MaterialTheme.colorScheme.surfaceVariant
    val onIcon = MaterialTheme.colorScheme.onPrimary
    val offIcon = MaterialTheme.colorScheme.onSurfaceVariant

    // How "on" the button looks. A spring with a little overshoot, so the bloom lands and settles
    // rather than stopping dead - the shape morph clamps it, the rotation does not.
    val progress by animateFloatAsState(
        targetValue = if (isOn) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "powerProgress"
    )
    // The colour flood is its own, quicker curve: it should read as the result of the snap, not
    // ride the same wobble as the shape.
    val flood by animateFloatAsState(
        targetValue = if (isOn) 1f else 0f,
        animationSpec = if (isOn) spring(dampingRatio = 1f, stiffness = 900f) else tween(220),
        label = "powerFlood"
    )

    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 1400f),
        label = "powerPress"
    )
    val kick = remember { Animatable(1f) }
    val ring = remember { Animatable(1f) }

    // The first composition shows the state it finds without playing a switch that nobody pressed.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(isOn) {
        if (!settled) { settled = true; return@LaunchedEffect }
        if (isOn) {
            launch {
                ring.snapTo(0f)
                ring.animateTo(1f, tween(420))
            }
            kick.snapTo(1.14f)
        } else {
            kick.snapTo(0.93f)
        }
        kick.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
    }

    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val path = remember { Path() }
    val matrix = remember { Matrix() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .testTag("power_button")
            .semantics {
                role = Role.Switch
                contentDescription = "Power"
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
                        haptics.press()
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
                    val shapeProgress = progress.coerceIn(0f, 1f)
                    path.rewind()
                    morph.toPath(shapeProgress, path)
                    // MaterialShapes polygons are normalised into the unit square. Place them in
                    // this box, twisting about the centre by an angle that follows the unclamped
                    // spring, so the twist overshoots even where the morph cannot.
                    matrix.reset()
                    matrix.translate(this.size.width / 2f, this.size.height / 2f)
                    matrix.rotateZ(progress * 40f)
                    matrix.scale(this.size.width, this.size.height)
                    matrix.translate(-0.5f, -0.5f)
                    path.transform(matrix)

                    val centre = Offset(this.size.width / 2f, this.size.height / 2f)
                    val reach = this.size.minDimension * 0.75f

                    clipPath(path) {
                        drawRect(offColor)
                        if (flood > 0.001f) drawCircle(onColor, radius = reach * flood, center = centre)
                    }

                    val r = ring.value
                    if (r < 1f) {
                        drawCircle(
                            color = onColor.copy(alpha = 0.55f * (1f - r)),
                            radius = this.size.minDimension / 2f * (1f + 0.7f * r),
                            center = centre,
                            style = Stroke(width = (2.5f * (1f - r) + 0.5f).dp.toPx())
                        )
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
 * The switch's three haptic moments.
 *
 * Kept apart from the composable so the choice of primitive sits in one place and can be tuned by
 * ear - or rather by thumb - without touching the drawing.
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

    private fun supports(vararg primitives: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            vibrator?.hasVibrator() == true &&
            vibrator.areAllPrimitivesSupported(*primitives)

    /** Travel: barely there. */
    fun press() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            supports(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
        ) {
            vibrator?.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.5f)
                    .compose()
            )
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /** A rise that snaps: charge, then click. */
    fun on() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            supports(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, VibrationEffect.Composition.PRIMITIVE_CLICK)
        ) {
            vibrator?.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.45f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 10)
                    .compose()
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /** Power draining away: a fall into a soft tick. Smaller than on, on purpose. */
    fun off() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            supports(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, VibrationEffect.Composition.PRIMITIVE_TICK)
        ) {
            vibrator?.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.5f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 20)
                    .compose()
            )
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}
