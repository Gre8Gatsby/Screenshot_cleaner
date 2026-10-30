package com.expirer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.expirer.receiver.NotificationActionReceiver
import com.expirer.ui.MainActivity
import java.io.File

object NotificationHelper {
    const val CHANNEL_SERVICE = "channel_service_watcher"
    const val CHANNEL_TAGGED = "channel_tagged_alerts"
    const val CHANNEL_CLEANUP = "channel_cleanup_summary"

    const val NOTIF_ID_SERVICE = 1001
    const val NOTIF_ID_CLEANUP = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Screenshot Watcher Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps screenshot observer active in background"
                setShowBadge(false)
            }

            val taggedChannel = NotificationChannel(
                CHANNEL_TAGGED,
                "Temporary Screenshot Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when an OTP, payment, or boarding pass screenshot is detected"
                enableVibration(true)
                setShowBadge(true)
            }

            val cleanupChannel = NotificationChannel(
                CHANNEL_CLEANUP,
                "Auto-Cleanup Summaries",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Summaries of deleted expired screenshots and freed storage"
            }

            nm.createNotificationChannel(serviceChannel)
            nm.createNotificationChannel(taggedChannel)
            nm.createNotificationChannel(cleanupChannel)
        }
    }

    fun buildServiceNotification(context: Context): Notification {
        val launchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("Screenshot Expirer Active")
            .setContentText("Monitoring temporary screenshots (OTPs, payments, tickets)")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun notifyScreenshotTagged(
        context: Context,
        notificationId: Int,
        filePath: String,
        uriString: String?,
        tag: String,
        ttlHours: Long
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Content intent -> Open MainActivity
        val openIntent = Intent(context, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Keep Forever
        val keepIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_KEEP
            putExtra(NotificationActionReceiver.EXTRA_FILE_PATH, filePath)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notificationId)
        }
        val keepPending = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 1,
            keepIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Delete Now
        val deleteIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DELETE_NOW
            putExtra(NotificationActionReceiver.EXTRA_FILE_PATH, filePath)
            putExtra(NotificationActionReceiver.EXTRA_URI_STRING, uriString)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notificationId)
        }
        val deletePending = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 2,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_TAGGED)
            .setContentTitle("Temporary Screenshot: $tag")
            .setContentText("Tagged as temporary. Will auto-delete in ${ttlHours}h.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Tagged as temporary $tag screenshot. Auto-deleting in ${ttlHours} hours to keep your gallery clean and credentials safe.")
            )
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_save, "Keep Forever", keepPending)
            .addAction(android.R.drawable.ic_menu_delete, "Delete Now", deletePending)

        // Try to attach image thumbnail preview if accessible
        try {
            val file = File(filePath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .setSummaryText("Auto-deletes in ${ttlHours}h")
                    )
                }
            }
        } catch (ignored: Exception) {
            // Ignore preview decode errors to avoid crashing notification flow
        }

        nm.notify(notificationId, builder.build())
    }

    fun notifyCleanupSummary(context: Context, deletedCount: Int, freedBytes: Long) {
        if (deletedCount <= 0) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val mbFreed = String.format("%.2f MB", freedBytes / (1024.0 * 1024.0))

        val openIntent = Intent(context, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CLEANUP)
            .setContentTitle("Auto-Cleaned $deletedCount Screenshots")
            .setContentText("Deleted expired temporary screenshots, freeing $mbFreed.")
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(NOTIF_ID_CLEANUP, notification)
    }
}

