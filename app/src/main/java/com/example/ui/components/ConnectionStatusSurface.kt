package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * How long an error stays on screen before clearing itself. Errors here replace what used to be a
 * bottom snackbar, so they keep snackbar-ish dismissal semantics rather than sitting there.
 */
private const val ERROR_AUTO_DISMISS_MS = 6_000L

/**
 * How long the connection indicator shows before hiding itself. The retry ladder in
 * `handleConnectionStateChange` carries on regardless — this only bounds the *UI*, which is the
 * whole point: the original full-screen "Fusing… Connecting BLE" scrim had no timeout and no
 * dismiss, so one powered-off saved light covered every tab forever.
 *
 * This timeout is now the *only* way the hunting indicator goes away on its own, since the
 * indicator is deliberately non-interactive (see [ConnectionStatusSurface]) — so it must stay.
 */
private const val HUNT_TIMEOUT_MS = 15_000L

/** Deliberately large: this is the app's "something is happening" moment, not a status chip. */
private val INDICATOR_SIZE = 200.dp

/**
 * The single surface for "the app is trying to reach a light" and "the app failed at something you
 * asked for". These used to be two widgets in two places — a pill under the top bar and a snackbar
 * at the bottom — which could both be on screen at once saying contradictory things (most visibly:
 * "Looking for Bedroom Strip…" while the error underneath said Bluetooth was off).
 *
 * The two states are deliberately *not* styled alike:
 *  - **Hunting** is a big bare [LoadingIndicator] floating in the middle of the screen. No card, no
 *    label, no button — it's ambient, and it carries no information a caption would add. It is also
 *    non-interactive on purpose: nothing here takes a pointer, so every tab underneath stays fully
 *    usable and reachable. That constraint is what killed the original blocking overlay, and the
 *    reason [HUNT_TIMEOUT_MS] matters — it's the only thing that dismisses this.
 *  - **Error** keeps a card, because it has a message to read and actions to take.
 *
 * [errorMessage] takes precedence over [huntingDeviceName] — an error is actionable and a hunt is
 * ambient, and showing both was the bug.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectionStatusSurface(
    huntingDeviceName: String?,
    errorMessage: String?,
    offerEnableBluetooth: Boolean,
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
            visible = showHunt,
            enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.85f)
        ) {
            // The real Material 3 Expressive indicator (material3 1.5.0-alpha25): a morph through
            // the MaterialShapes sequence. Was hand-rolled on androidx.graphics:graphics-shapes
            // while this project was on material3 1.4.0, which ships the Expressive opt-in marker
            // and LoadingIndicatorTokens but not the component itself.
            LoadingIndicator(
                modifier = Modifier
                    .size(INDICATOR_SIZE)
                    .testTag("connection_status_banner"),
                color = MaterialTheme.colorScheme.primary
            )
        }

        AnimatedVisibility(
            visible = showError,
            enter = fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.9f)
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .testTag("connection_error_surface"),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                }
            }
        }
    }
}
