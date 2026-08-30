package nl.icthorse.miraicastlab.projection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import nl.icthorse.miraicastlab.R
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.SessionLogger

/**
 * Foreground service that must be running before [android.media.projection.MediaProjection] may be
 * started on API 29+ (and which Android 14+ enforces with a dedicated foregroundServiceType).
 *
 * It holds no capture logic itself: it exists purely so the platform permits capture. The capture
 * pipeline lives in the MediaProjection experiment, owned by the projection module agent.
 */
class ProjectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.projection_notification_title))
            .setContentText(getString(R.string.projection_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection_service_started",
            LabStatus.CONFIRMED,
            mapOf("sdk" to Build.VERSION.SDK_INT.toString()),
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection_service_stopped",
            LabStatus.CONFIRMED,
        )
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "miraicastlab.projection"
        const val NOTIFICATION_ID = 4711

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.projection_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        fun start(context: Context) {
            ensureChannel(context)
            context.startForegroundService(Intent(context, ProjectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProjectionService::class.java))
        }
    }
}
