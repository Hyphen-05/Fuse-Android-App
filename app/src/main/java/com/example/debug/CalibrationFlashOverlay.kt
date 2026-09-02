package com.example.debug

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * The white rectangle that makes absolute latency measurable — see [CalibrationScreenFlash] for why
 * it exists and what it does not fix.
 *
 * Three things it has to get right, all of which void a P4 run if missed:
 *  - **Full brightness.** The camera's exposure is locked against the strip, and a phone at its
 *    usual auto brightness can land under the noise floor of that exposure.
 *  - **Screen awake.** A run is twenty seconds of black between pulses, which is exactly what the
 *    display timeout is watching for.
 *  - **A recorded present time.** Reporting from the frame callback puts the compositor's share of
 *    the delay in the CSV instead of in the error bars.
 */
@Composable
fun CalibrationFlashOverlay() {
    val runActive by CalibrationScreenFlash.runActive.collectAsState()
    val brightScreen by CalibrationScreenFlash.brightScreen.collectAsState()
    val on by CalibrationScreenFlash.on.collectAsState()
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(runActive, brightScreen) {
        val activity = context.findActivity()
        val window = activity?.window
        val restore = window?.attributes?.screenBrightness
        if (runActive) {
            // Awake for every run: a sleeping display takes the app out of TOP, which costs the
            // foreground service and stalls the sequence outright.
            view.keepScreenOn = true
            window?.attributes = window.attributes?.apply {
                // Bright only where the screen IS the measurement. Everywhere else it is a light
                // source in a dark room, so it goes to the dimmest the platform will accept while
                // still counting as on.
                screenBrightness = if (brightScreen) {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                } else {
                    MINIMUM_VISIBLE_BRIGHTNESS
                }
            }
        }
        onDispose {
            view.keepScreenOn = false
            // Back to whatever the phone was doing, including "follow the system slider", which is
            // what the saved value is when nothing has overridden it.
            if (restore != null) {
                window.attributes = window.attributes?.apply { screenBrightness = restore }
            }
        }
    }

    if (on) {
        Box(Modifier.fillMaxSize().background(Color.White))
        LaunchedEffect(Unit) {
            // The first frame after this composition is the one carrying the white rectangle.
            withFrameNanos { CalibrationScreenFlash.reportPresented(System.currentTimeMillis()) }
        }
    }
}

/**
 * Not [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF], which on some devices reads as "screen
 * off" rather than "screen dim" and puts the app right back into the state this is avoiding.
 */
private const val MINIMUM_VISIBLE_BRIGHTNESS = 0.01f

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
