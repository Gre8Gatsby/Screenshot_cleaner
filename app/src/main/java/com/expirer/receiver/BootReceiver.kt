package com.expirer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.expirer.service.ScreenshotService
import com.expirer.worker.ExpireWorker

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "Device booted. Resuming ScreenshotService and periodic worker.")
            ScreenshotService.start(context)
            ExpireWorker.schedulePeriodic(context)
        }
    }
}

