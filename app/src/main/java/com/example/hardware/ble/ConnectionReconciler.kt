package com.example.hardware.ble

/**
 * Which connections the app believes in that Android no longer does.
 *
 * The app's idea of "connected" is its own bookkeeping, updated only by GATT callbacks. When the app
 * sits in the background for hours Android freezes its process, and a disconnect that happens while
 * it is frozen can fail to reach it. The process then wakes still holding a connection that is gone,
 * and the home screen loads straight into tiles for strips that are not there - Joe's report of
 * 2026-09-14, on his Pixel, whose log showed `freezing ... com.github.hyphen05.fuse`.
 *
 * So on every return to the foreground the app asks the Bluetooth stack directly, and anything the
 * stack says is not linked is treated as a real disconnect. This does not depend on *why* the
 * callback went missing, which is the point - it corrects the state rather than guessing the cause.
 *
 * Only devices the app thinks are **connected** are checked. A device still connecting is not yet
 * linked as far as the stack is concerned either, and reconciling it would abort a connection in
 * progress.
 */
object ConnectionReconciler {

    /** `BluetoothProfile.STATE_CONNECTED`, restated so this stays free of Android types. */
    const val STATE_CONNECTED = 2

    /**
     * @param believedConnected addresses the app currently treats as connected.
     * @param systemState the stack's own link state for an address, or null when it cannot say
     *   (Bluetooth off, no permission). Null is **not** treated as disconnected: a check that could
     *   not be made must not tear down connections, or a permission hiccup on resume would drop
     *   every strip.
     */
    fun stale(believedConnected: Set<String>, systemState: (String) -> Int?): Set<String> =
        believedConnected.filterTo(mutableSetOf()) { address ->
            val state = systemState(address)
            state != null && state != STATE_CONNECTED
        }
}
