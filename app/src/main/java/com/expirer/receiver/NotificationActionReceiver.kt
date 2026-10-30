package com.expirer.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.expirer.core.ExpirerJni
import java.io.File

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_KEEP = "com.expirer.ACTION_KEEP"
        const val ACTION_DELETE_NOW = "com.expirer.ACTION_DELETE_NOW"

        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_URI_STRING = "extra_uri_string"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
        private const val TAG = "NotifActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        val uriString = intent.getStringExtra(EXTRA_URI_STRING)

        // Cancel notification
        if (notifId != -1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }

        // Initialize JNI if needed
        ExpirerJni.init(context)

        when (action) {
            ACTION_KEEP -> {
                Log.i(TAG, "User requested KEEP for $filePath")
                val success = ExpirerJni.markFileKept(filePath)
                Toast.makeText(
                    context,
                    if (success) "Screenshot marked as Kept Forever" else "Could not mark as kept",
                    Toast.LENGTH_SHORT
                ).show()
            }
            ACTION_DELETE_NOW -> {
                Log.i(TAG, "User requested DELETE NOW for $filePath")
                val success = ExpirerJni.deleteFileNow(filePath)

                // Also clean from MediaStore
                if (uriString != null) {
                    try {
                        val uri = Uri.parse(uriString)
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaStore deletion warning: ${e.message}")
                    }
                }

                Toast.makeText(
                    context,
                    if (success) "Temporary screenshot deleted" else "Failed to delete file",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

