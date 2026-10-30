package com.expirer.service

import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.expirer.core.ExpirerJni
import com.expirer.util.OcrHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.LinkedHashSet

class ScreenshotService : Service() {

    companion object {
        private const val TAG = "ScreenshotService"
        const val ACTION_START = "com.expirer.START_SERVICE"
        const val ACTION_STOP = "com.expirer.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, ScreenshotService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenshotService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var contentObserver: ContentObserver? = null
    private val processedIds = Collections.synchronizedSet(LinkedHashSet<Long>())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ScreenshotService onCreate")
        NotificationHelper.createChannels(this)
        ExpirerJni.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NotificationHelper.NOTIF_ID_SERVICE,
            NotificationHelper.buildServiceNotification(this)
        )

        registerScreenshotObserver()
        return START_STICKY
    }

    private fun registerScreenshotObserver() {
        if (contentObserver != null) return

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                serviceScope.launch {
                    checkLatestScreenshots()
                }
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
        Log.i(TAG, "ContentObserver registered on MediaStore images.")
    }

    private suspend fun checkLatestScreenshots() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )

        // Only look at images added in the last 2 minutes
        val recentCutoff = (System.currentTimeMillis() / 1000) - 120
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(recentCutoff.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                var checkedCount = 0
                while (cursor.moveToNext() && checkedCount < 5) {
                    checkedCount++
                    val id = cursor.getLong(idCol)
                    val filePath = cursor.getString(dataCol) ?: ""
                    val fileName = cursor.getString(nameCol) ?: ""
                    val sizeBytes = cursor.getLong(sizeCol)

                    // Deduplicate
                    if (processedIds.contains(id)) continue

                    // Check if screenshot directory or screenshot name
                    val isScreenshot = filePath.contains("/Screenshots/", ignoreCase = true)
                            || fileName.contains("screenshot", ignoreCase = true)

                    if (!isScreenshot) continue

                    // Mark as seen
                    processedIds.add(id)
                    // Keep LRU bounded
                    if (processedIds.size > 200) {
                        val iterator = processedIds.iterator()
                        if (iterator.hasNext()) {
                            iterator.next()
                            iterator.remove()
                        }
                    }

                    val imageUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    processPotentialScreenshot(id, filePath, fileName, imageUri, sizeBytes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking latest screenshots: ${e.message}")
        }
    }

    private suspend fun processPotentialScreenshot(
        mediaId: Long,
        filePath: String,
        fileName: String,
        uri: Uri,
        sizeBytes: Long
    ) {
        Log.d(TAG, "Analyzing new screenshot: $fileName ($filePath)")

        // Tier 1: Check filename directly in Rust
        var classification = ExpirerJni.classify("", fileName)

        // Tier 2: If filename was not enough to confirm, extract OCR text
        if (!classification.shouldAutoExpire) {
            val ocrText = OcrHelper.extractText(this, uri)
            if (ocrText.isNotBlank()) {
                classification = ExpirerJni.classify(ocrText, fileName)
            }
        }

        Log.i(
            TAG,
            "Classification for $fileName -> Tag: ${classification.tag}, Conf: ${classification.confidence}, AutoExpire: ${classification.shouldAutoExpire}"
        )

        if (classification.shouldAutoExpire) {
            val recordId = ExpirerJni.tagFile(
                filePath = filePath,
                uriString = uri.toString(),
                tag = classification.tagName,
                confidence = classification.confidence,
                ttlHours = classification.recommendedTtlHours,
                fileSizeBytes = sizeBytes
            )

            if (recordId > 0) {
                NotificationHelper.notifyScreenshotTagged(
                    context = this,
                    notificationId = (mediaId % 10000).toInt(),
                    filePath = filePath,
                    uriString = uri.toString(),
                    tag = classification.tagName,
                    ttlHours = classification.recommendedTtlHours
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        contentObserver?.let {
            contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
        serviceScope.cancel()
        Log.i(TAG, "ScreenshotService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

