package com.expirer.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.expirer.R
import com.expirer.core.ExpirerJni
import com.expirer.core.ScreenshotRecord
import com.expirer.service.ScreenshotService
import com.expirer.util.OcrHelper
import com.expirer.worker.ExpireWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var switchService: SwitchCompat
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvActiveCount: TextView
    private lateinit var tvDeletedCount: TextView
    private lateinit var tvFreedSpace: TextView
    private lateinit var btnCleanNow: Button
    private lateinit var btnScanExisting: Button
    private lateinit var btnGrantPerms: Button
    private lateinit var cardPermsNotice: View
    private lateinit var rvScreenshots: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var adapter: ScreenshotAdapter

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshData()
            refreshHandler.postDelayed(this, 30_000) // Refresh every 30 seconds while open
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
            startWatcherService()
        } else {
            Toast.makeText(this, "Permissions required to monitor screenshots", Toast.LENGTH_LONG).show()
        }
        updatePermissionNotice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Rust native database
        ExpirerJni.init(this)

        initViews()
        setupRecyclerView()
        checkAndRequestPermissions()
        ExpireWorker.schedulePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        refreshData()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun initViews() {
        switchService = findViewById(R.id.switchService)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvActiveCount = findViewById(R.id.tvActiveCount)
        tvDeletedCount = findViewById(R.id.tvDeletedCount)
        tvFreedSpace = findViewById(R.id.tvFreedSpace)
        btnCleanNow = findViewById(R.id.btnCleanNow)
        btnScanExisting = findViewById(R.id.btnScanExisting)
        btnGrantPerms = findViewById(R.id.btnGrantPerms)
        cardPermsNotice = findViewById(R.id.cardPermsNotice)
        rvScreenshots = findViewById(R.id.rvScreenshots)
        tvEmptyState = findViewById(R.id.tvEmptyState)

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasRequiredPermissions()) {
                    startWatcherService()
                } else {
                    switchService.isChecked = false
                    requestAppPermissions()
                }
            } else {
                stopWatcherService()
            }
        }

        btnGrantPerms.setOnClickListener {
            requestAppPermissions()
        }

        btnScanExisting.setOnClickListener {
            scanExistingScreenshots()
        }

        btnCleanNow.setOnClickListener {
            btnCleanNow.isEnabled = false
            btnCleanNow.text = "Cleaning..."
            ExpireWorker.runOnce(this)
            refreshHandler.postDelayed({
                refreshData()
                btnCleanNow.isEnabled = true
                btnCleanNow.text = "Clean Expired Now"
                Toast.makeText(this, "Auto-cleaning completed!", Toast.LENGTH_SHORT).show()
            }, 1500)
        }
    }

    private fun setupRecyclerView() {
        adapter = ScreenshotAdapter(
            onKeepClicked = { item ->
                handleKeepScreenshot(item)
            },
            onDeleteClicked = { item ->
                handleDeleteScreenshot(item)
            }
        )
        rvScreenshots.layoutManager = LinearLayoutManager(this)
        rvScreenshots.adapter = adapter
    }

    private fun scanExistingScreenshots() {
        if (!hasRequiredPermissions()) {
            requestAppPermissions()
            return
        }

        btnScanExisting.isEnabled = false
        btnScanExisting.text = "Scanning..."
        Toast.makeText(this, "Scanning gallery for temporary screenshots...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            var scannedCount = 0
            var taggedCount = 0

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE
            )

            // Look back 30 days
            val cutoff = (System.currentTimeMillis() / 1000) - (30L * 24 * 3600)
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf(cutoff.toString())
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

                    while (cursor.moveToNext() && scannedCount < 50) {
                        val id = cursor.getLong(idCol)
                        val filePath = cursor.getString(dataCol) ?: ""
                        val fileName = cursor.getString(nameCol) ?: ""
                        val sizeBytes = cursor.getLong(sizeCol)

                        val isScreenshot = filePath.contains("/Screenshots/", ignoreCase = true)
                                || fileName.contains("screenshot", ignoreCase = true)

                        if (!isScreenshot) continue
                        scannedCount++

                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                        // 1. Filename heuristic
                        var classification = ExpirerJni.classify("", fileName)

                        // 2. OCR if needed
                        if (!classification.shouldAutoExpire) {
                            val ocrText = OcrHelper.extractText(this@MainActivity, uri)
                            if (ocrText.isNotBlank()) {
                                classification = ExpirerJni.classify(ocrText, fileName)
                            }
                        }

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
                                taggedCount++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Log and ignore
            }

            withContext(Dispatchers.Main) {
                btnScanExisting.isEnabled = true
                btnScanExisting.text = "Scan Gallery"
                refreshData()
                Toast.makeText(
                    this@MainActivity,
                    "Scanned $scannedCount screenshots · Tagged $taggedCount temporary",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleKeepScreenshot(item: ScreenshotRecord) {
        val success = ExpirerJni.markFileKept(item.filePath)
        if (success) {
            Toast.makeText(this, "Marked as Kept Forever", Toast.LENGTH_SHORT).show()
            refreshData()
        }
    }

    private fun handleDeleteScreenshot(item: ScreenshotRecord) {
        val success = ExpirerJni.deleteFileNow(item.filePath)
        item.uriString?.let { uriStr ->
            try {
                contentResolver.delete(Uri.parse(uriStr), null, null)
            } catch (ignored: Exception) {}
        }
        if (success) {
            Toast.makeText(this, "Screenshot deleted", Toast.LENGTH_SHORT).show()
            refreshData()
        }
    }

    private fun refreshData() {
        // Fetch stats from Rust SQLite
        val stats = ExpirerJni.fetchStats()
        tvActiveCount.text = stats.totalActive.toString()
        tvDeletedCount.text = stats.totalDeleted.toString()

        val freedMb = stats.estimatedBytesFreed / (1024.0 * 1024.0)
        tvFreedSpace.text = if (freedMb >= 1024) {
            String.format(Locale.getDefault(), "%.2f GB", freedMb / 1024.0)
        } else {
            String.format(Locale.getDefault(), "%.1f MB", freedMb)
        }

        // Fetch active screenshot records
        val activeItems = ExpirerJni.listActiveScreenshots()
        adapter.submitList(activeItems)

        if (activeItems.isEmpty()) {
            tvEmptyState.visibility = View.VISIBLE
            rvScreenshots.visibility = View.GONE
        } else {
            tvEmptyState.visibility = View.GONE
            rvScreenshots.visibility = View.VISIBLE
        }

        updatePermissionNotice()
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = getNeededPermissions()
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getNeededPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return list
    }

    private fun requestAppPermissions() {
        val needed = getNeededPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun updatePermissionNotice() {
        val hasPerms = hasRequiredPermissions()
        cardPermsNotice.visibility = if (hasPerms) View.GONE else View.VISIBLE
        switchService.isChecked = hasPerms
        tvServiceStatus.text = if (hasPerms) "Watcher Active" else "Permissions Needed"
    }

    private fun startWatcherService() {
        ScreenshotService.start(this)
        tvServiceStatus.text = "Watcher Active"
        switchService.isChecked = true
    }

    private fun stopWatcherService() {
        ScreenshotService.stop(this)
        tvServiceStatus.text = "Watcher Stopped"
        switchService.isChecked = false
    }
}

