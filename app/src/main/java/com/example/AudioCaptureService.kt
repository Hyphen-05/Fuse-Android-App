package com.example

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

class AudioCaptureService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode") ?: "phone_mic"
        promoteToForeground(mode)
        isForegroundActive = true
        return START_NOT_STICKY
    }

    private fun promoteToForeground(mode: String) {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
                startForeground(1, notification, type)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startForeground(1, notification)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Capture Active")
            .setContentText("Listening to music on your device...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "audio_capture_channel"

        // Set once onStartCommand() has actually promoted this service to the foreground, cleared
        // on every start()/stop(). Real capture (Visualizer(0) attaching to the global output mix)
        // is gated by Android's background-audio-capture restrictions on whether this process is
        // already an eligible foreground-audio-capture client -- but start() here only *requests*
        // that promotion asynchronously (posts an Intent to the main thread's Service dispatch);
        // it does not wait for onStartCommand()/startForeground() to actually run. The caller
        // (RgbControllerViewModel.startAudioEngine, on_device branch) awaits this flag with a
        // bounded timeout before constructing the Visualizer, closing the race where capture
        // attaches while the app is still (from the platform's point of view) a background process
        // -- see awaitForeground().
        @Volatile private var isForegroundActive = false

        fun start(context: Context, mode: String) {
            isForegroundActive = false
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                putExtra("mode", mode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            isForegroundActive = false
            val intent = Intent(context, AudioCaptureService::class.java)
            context.stopService(intent)
        }

        // Blocks the calling thread (must not be the main thread) until onStartCommand() has
        // actually promoted this service to the foreground, or timeoutMs elapses, whichever comes
        // first -- never hangs indefinitely on a failed/slow promotion.
        fun awaitForeground(timeoutMs: Long) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!isForegroundActive && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(15L)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }
}
