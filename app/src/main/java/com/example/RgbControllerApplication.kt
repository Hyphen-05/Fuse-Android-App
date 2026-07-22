package com.example

import android.app.Application
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.example.debug.AdbControlReceiver
import com.example.di.AppContainer

class RgbControllerApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Debug-only self-driving test harness control surface (see AdbControlReceiver's doc
        // comment). Registered dynamically rather than via a manifest <receiver> entry, and
        // gated on BuildConfig.DEBUG here as well as inside the receiver itself — a release
        // build never registers this at all, so it's not just inert but structurally absent.
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this,
                AdbControlReceiver(),
                IntentFilter(AdbControlReceiver.ACTION_CONTROL),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }
}
