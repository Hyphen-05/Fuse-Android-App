package com.example.ambiance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.RgbControllerViewModel
import com.example.DuoCoProtocol

class AmbianceCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var processor: AmbianceProcessor? = null
    private var outputInterpolator: AmbianceOutputInterpolator? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode == 0 || data == null) {
            Log.e(TAG, "Missing resultCode or intent data. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground()

        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mediaProjectionManager.getMediaProjection(resultCode, data)
            if (mp == null) {
                Log.e(TAG, "Failed to obtain MediaProjection.")
                stopSelf()
                return START_NOT_STICKY
            }
            mediaProjection = mp

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped via callback.")
                    cleanup()
                    stopSelf()
                }
            }
            projectionCallback = callback
            mp.registerCallback(callback, Handler(Looper.getMainLooper()))

            val vm = RgbControllerViewModel.getActiveInstance()
            vm?.broadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(true))

            setupCapture()
            AmbianceCaptureState.setIsActive(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MediaProjection", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun setupCapture() {
        val metrics = resources.displayMetrics
        val scaleFactor = 4
        val width = (metrics.widthPixels / scaleFactor).coerceAtLeast(1)
        val height = (metrics.heightPixels / scaleFactor).coerceAtLeast(1)

        handlerThread = HandlerThread("AmbianceCaptureThread").apply { start() }
        handler = Handler(handlerThread!!.looper)

        outputInterpolator = AmbianceOutputInterpolator(this)
        outputInterpolator?.start()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        processor = AmbianceProcessor(this) { color, isSceneCut ->
            if (AmbianceCaptureState.isActive.value) {
                outputInterpolator?.updateTargetColor(color, isSceneCut)
            }
        }

        val dpi = metrics.densityDpi

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AmbianceCaptureDisplay",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            processor?.processFrame(reader)
        }, handler)
    }

    private fun cleanup() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            handlerThread?.quitSafely()
            outputInterpolator?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error during callback-initiated cleanup", e)
        }
        virtualDisplay = null
        imageReader = null
        handlerThread = null
        handler = null
        outputInterpolator = null
        mediaProjection = null
        projectionCallback = null
        processor?.clear()
        processor = null

        val vm = RgbControllerViewModel.getActiveInstance()
        vm?.broadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(false))

        AmbianceCaptureState.setIsActive(false)
        AmbianceCaptureState.updateZoneColors(emptyList())
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        
        try {
            projectionCallback?.let { callback ->
                mediaProjection?.unregisterCallback(callback)
            }
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up capture components", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ambiance Capture Active")
            .setContentText("Analyzing screen content for ambient lighting...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ambiance Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "AmbianceCaptureService"
        private const val CHANNEL_ID = "ambiance_capture_channel"
        private const val NOTIFICATION_ID = 2

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AmbianceCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AmbianceCaptureService::class.java)
            context.stopService(intent)
        }
    }
}
