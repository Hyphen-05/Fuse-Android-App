package com.example.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the process alive and unfrozen for the length of a calibration run.
 *
 * ## Why this exists
 *
 * A calibration run has to be filmed, and the phone cannot film itself — so the driving phone spends
 * the whole run in the background with the camera app in front. On 2026-08-16 that killed Fuse
 * mid-run: Android froze the cached process while BLE callbacks kept arriving at it, and the process
 * died with *"Async binder space running out while frozen"*. The session worked around it by
 * disabling `cached_apps_freezer` **globally** on Joe's own phone — a global setting, on his primary
 * device, that then had to be remembered and put back.
 *
 * A foreground service is the supported way to say "this process is doing something the user asked
 * for, do not freeze it". With this in place a future capture session needs no device surgery, and
 * nothing has to be remembered afterwards.
 *
 * ## What it does not fix
 *
 * Backgrounding still costs throughput — measured at ~57%, 36.8ms per write against 4.6ms in the
 * foreground. That is scheduling and radio priority, not freezing, and no service type changes it.
 * A filmed run is therefore still not measuring the same system as normal use, which matters for
 * any *rate* measurement and not at all for a response-curve or spacing one. See
 * `tools/calibration/README.md`.
 *
 * Typed `dataSync` because that is what a timed scripted transfer to a peripheral is; the permission
 * was already declared for it. Debug tooling, started only from [AdbControlReceiver]'s path.
 */
class CalibrationForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sequence = intent?.getStringExtra(EXTRA_SEQUENCE) ?: "calibration"
        promoteToForeground(sequence)
        // Deliberately not sticky: if the process dies anyway, the run is void and restarting the
        // service without the sequence that was driving it would only produce a misleading
        // notification.
        return START_NOT_STICKY
    }

    private fun promoteToForeground(sequence: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Calibration run in progress")
            .setContentText("Running '$sequence' — leave the phone alone until it finishes.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Never let the service take the run down with it: an unfrozen process is an
            // optimisation over the old workaround, not a precondition for the sequence running.
            android.util.Log.w(TAG, "Could not promote calibration service to foreground", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ex: Exception) {
                android.util.Log.w(TAG, "Foreground promotion failed outright", ex)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Calibration Runs",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "CalibrationService"
        private const val CHANNEL_ID = "calibration_run_channel"

        // Distinct from AudioCaptureService's id 1: both can be up at once in principle, and
        // reusing the id would have one silently replace the other's notification.
        private const val NOTIFICATION_ID = 4471

        const val EXTRA_SEQUENCE = "sequence"

        /**
         * Starts the service, returning whether it was started — callers treat failure as "run
         * anyway", since the run is still valid, just freezable.
         */
        fun start(context: Context, sequence: String): Boolean = try {
            val intent = Intent(context, CalibrationForegroundService::class.java)
                .putExtra(EXTRA_SEQUENCE, sequence)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not start calibration foreground service", e)
            false
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CalibrationForegroundService::class.java))
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not stop calibration foreground service", e)
            }
        }
    }
}
