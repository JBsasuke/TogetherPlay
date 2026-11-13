package com.juntosplay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        private const val CHANNEL_ID = "togetherplay_screen_capture"
        private const val NOTIFICATION_ID = 7402
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notificationBuilder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = notificationBuilder
            .setContentTitle("TogetherPlay")
            .setContentText("Captura de pantalla activa")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val density = metrics.densityDpi
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            }
            projection?.registerCallback(projectionCallback!!, null)
            virtualDisplay = projection?.createVirtualDisplay(
                "TogetherPlayScreen",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
            if (virtualDisplay == null) stopCapture()
        } catch (_: Exception) {
            stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Compartir pantalla", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projectionCallback = null
        projection?.stop()
        projection = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
