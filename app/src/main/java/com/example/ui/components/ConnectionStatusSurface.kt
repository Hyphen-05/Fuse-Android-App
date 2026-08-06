package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/**
 * How long an error stays on screen before clearing itself. Errors here replace what used to be a
 * bottom snackbar, so they keep snackbar-ish dismissal semantics rather than sitting there.
 */
private const val ERROR_AUTO_DISMISS_MS = 6_000L

/**
 * How long the connection indicator nags before hiding itself. The retry ladder in
 * `handleConnectionStateChange` carries on regardless — this only bounds the *UI*, which is the
 * whole point: the original full-screen "Fusing… Connecting BLE" scrim had no timeout and no
 * dismiss, so one powered-off saved light covered every tab forever.
 */
private const val HUNT_TIMEOUT_MS = 15_000L

/**
 * The single surface for "the app is trying to reach a light" and "the app failed at something you
 * asked for". These used to be two widgets in two places — a pill under the top bar and a snackbar
 * at the bottom — which could both be on screen at once saying contradictory things (most visibly:
 * "Looking for Bedroom Strip…" while the error underneath said Bluetooth was off).
 *
 * Deliberately **non-blocking**: no scrim, no `clickable` on the outer Box, and the card is small
 * and centred, so every tab underneath stays usable and reachable. That constraint is the reason
 * the original overlay was torn out; don't reintroduce a full-bleed background here.
 *
 * [errorMessage] takes precedence over [huntingDeviceName] — an error is actionable and a hunt is
 * ambient, and showing both was the bug.
 */
@Composable
fun ConnectionStatusSurface(
    huntingDeviceName: String?,
    errorMessage: String?,
    offerEnableBluetooth: Boolean,
    onCancelHunt: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Keyed on the message so a *new* error restarts the clock rather than inheriting the remains
    // of the previous one's.
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            kotlinx.coroutines.delay(ERROR_AUTO_DISMISS_MS)
            onDismissError()
        }
    }

    var huntTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(huntingDeviceName) {
        huntTimedOut = false
        if (huntingDeviceName != null) {
            kotlinx.coroutines.delay(HUNT_TIMEOUT_MS)
            huntTimedOut = true
        }
    }

    val showError = errorMessage != null
    val showHunt = !showError && huntingDeviceName != null && !huntTimedOut

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = showError || showHunt,
            enter = fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.9f)
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .testTag(if (showError) "connection_error_surface" else "connection_status_banner"),
                shape = RoundedCornerShape(28.dp),
                color = if (showError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (showError) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (offerEnableBluetooth) {
                                TextButton(
                                    onClick = onEnableBluetooth,
                                    modifier = Modifier.testTag("connection_enable_bt_btn")
                                ) {
                                    Text(
                                        text = "Turn on Bluetooth",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            TextButton(
                                onClick = onDismissError,
                                modifier = Modifier.testTag("connection_error_dismiss_btn")
                            ) {
                                Text(
                                    text = "Dismiss",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    } else {
                        MorphingLoadingIndicator(
                            modifier = Modifier.size(56.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Looking for ${huntingDeviceName.orEmpty()}…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        TextButton(
                            onClick = onCancelHunt,
                            modifier = Modifier.testTag("connection_cancel_btn")
                        ) {
                            Text(text = "Stop looking")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Expressive shape-morphing loader, built directly rather than taken from material3.
 *
 * material3 1.4.0 is the newest published release and it ships `ExperimentalMaterial3ExpressiveApi`
 * and `LoadingIndicatorTokens` but **not** the `LoadingIndicator` composable itself — verified by
 * inspecting the artifact, `ProgressIndicatorKt` is the only indicator class in it. So this uses
 * `androidx.graphics:graphics-shapes`, which is exactly what that component morphs with internally:
 * a [Morph] between two [RoundedPolygon]s, swept by an infinite progress float, with a slow
 * continuous rotation on top so the silhouette never sits still.
 *
 * Replace this with the real `LoadingIndicator` if a material3 release ever ships it.
 */
@Composable
private fun MorphingLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color
) {
    // A soft 8-point star and a rounded 6-gon: different vertex counts, which is what makes the
    // in-between frames read as a shape *changing* rather than a shape rotating.
    val morph = remember {
        Morph(
            start = RoundedPolygon.star(
                numVerticesPerRadius = 8,
                innerRadius = 0.72f,
                rounding = CornerRounding(0.35f)
            ),
            end = RoundedPolygon(
                numVertices = 6,
                rounding = CornerRounding(0.30f)
            )
        )
    }

    val transition = rememberInfiniteTransition(label = "morphIndicator")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "morphProgress"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "morphSpin"
    )

    // Reused across frames rather than allocated per draw — this redraws continuously for as long
    // as the app is hunting for a device.
    val androidPath = remember { android.graphics.Path() }

    Canvas(modifier = modifier) {
        morph.toPath(progress, androidPath)
        rotate(spin) {
            // graphics-shapes emits its path in a unit space centred on the origin, so move the
            // origin to the middle of the canvas and scale up before filling.
            translate(size.width / 2f, size.height / 2f) {
                scale(size.minDimension / 2f, pivot = Offset.Zero) {
                    drawPath(path = androidPath.asComposePath(), color = color)
                }
            }
        }
    }
}
