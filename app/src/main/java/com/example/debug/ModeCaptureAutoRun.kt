package com.example.debug

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Drives the Mode Capture screen from adb, so the one remaining part of a capture session that
 * needed a human at the phone no longer does.
 *
 * ## Why this is a flag object and not a method call
 *
 * Mode Capture is deliberately outside the `RgbUiState`/`RgbIntent`/reducer chain — it is a
 * self-contained `AndroidViewModel` with its own camera source, and it needs a live `PreviewView`
 * and a `LifecycleOwner` to do anything at all. Neither of those exists anywhere a broadcast
 * receiver can reach. So the receiver leaves a request here, `MainActivity` notices it and opens
 * the screen, and the screen — which has the camera and the lifecycle — carries it out.
 *
 * ## The endpoints
 *
 * Normally tapped on the preview. Tapping is the part that cannot be automated and the part most
 * likely to be wrong: a fingertip on a preview is not repeatable, and a missed tap yields a run
 * that samples the wall.
 *
 * They are passed in instead. The default is a vertical line down the middle of the frame, which is
 * what a phone propped in front of the wall starburst's long strand sees. Better than the default:
 * take them from the `chase_probe` grid shot minutes earlier on the same phone in the same
 * position, which says exactly where in frame the strip is — the laptop script does that, so the
 * numbers come from a measurement rather than from an assumption about the framing.
 *
 * [status] is published for the adb `status` dump, because an unattended run that has quietly
 * failed to start looks exactly like one that is still going.
 */
object ModeCaptureAutoRun {

    data class Request(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val positions: Int
    )

    /** Non-null when a run has been asked for and not yet finished. */
    val requested = MutableStateFlow<Request?>(null)

    /** Where the auto-run has got to, for the adb status dump. */
    val status = MutableStateFlow("idle")

    /** Set by the screen when it exports, so the script knows what to pull without guessing. */
    @Volatile
    var lastExportPath: String? = null

    fun request(x0: Float, y0: Float, x1: Float, y1: Float, positions: Int) {
        lastExportPath = null
        status.value = "requested"
        requested.value = Request(x0, y0, x1, y1, positions)
    }

    fun clear() {
        requested.value = null
    }
}
