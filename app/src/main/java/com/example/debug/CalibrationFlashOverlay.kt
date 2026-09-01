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
    val on by CalibrationScreenFlash.on.collectAsState()
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(runActive) {
        val activity = context.findActivity()
        val window = activity?.window
        val restore = window?.attributes?.screenBrightness
        if (runActive) {
            view.keepScreenOn = true
            window?.attributes = window.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
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

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
