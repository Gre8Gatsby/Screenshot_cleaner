package com.expirer.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ClassificationResult(
    val tag: String,
    val tagName: String,
    val confidence: Float,
    val shouldAutoExpire: Boolean,
    val recommendedTtlHours: Long,
    val matchedCues: List<String>,
    val explanation: String
)

data class ScreenshotRecord(
    val id: Long,
    val filePath: String,
    val uriString: String?,
    val tag: String,
    val confidence: Float,
    val createdAt: Long,
    val expiresAt: Long,
    val status: String,
    val fileSizeBytes: Long
) {
    fun getRemainingTimeMillis(): Long {
        val now = System.currentTimeMillis() / 1000
        val diff = expiresAt - now
        return if (diff > 0) diff * 1000 else 0
    }
}

data class ExpirationSummary(
    val deletedCount: Int,
    val freedBytes: Long,
    val deletedPaths: List<String>,
    val deletedUris: List<String>,
    val errors: List<String>
)

data class DBStats(
    val totalActive: Long,
    val totalKept: Long,
    val totalDeleted: Long,
    val estimatedBytesFreed: Long
)

object ExpirerJni {
    private const val TAG = "ExpirerJni"
    private var isLoaded = false
    private var isInitialized = false

    init {
        try {
            System.loadLibrary("expirer")
            isLoaded = true
            Log.i(TAG, "libexpirer.so loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libexpirer.so: ${e.message}")
        }
    }

    fun init(context: Context): Boolean {
        if (!isLoaded) {
            Log.e(TAG, "Cannot initialize: native library not loaded.")
            return false
        }
        if (isInitialized) return true

        val dbFile = File(context.filesDir, "expirer_database.db")
        isInitialized = initNative(dbFile.absolutePath)
        return isInitialized
    }

    // --- Raw Native JNI Methods ---
    private external fun initNative(dbPath: String): Boolean
    private external fun classifyText(ocrText: String, fileName: String): String
    private external fun tagScreenshot(
        filePath: String,
        uriString: String?,
        tag: String,
        confidence: Float,
        ttlHours: Long,
        fileSizeBytes: Long
    ): Long
    private external fun getActiveScreenshots(): String
    private external fun markKept(filePath: String): Boolean
    private external fun deleteNow(filePath: String): Boolean
    private external fun runExpiration(trashDir: String?): String
    private external fun getStats(): String

    // --- High-Level Type-Safe Helpers ---

    fun classify(ocrText: String, fileName: String): ClassificationResult {
        if (!isLoaded) {
            return ClassificationResult("OTHER", "OTHER", 0f, false, 0, emptyList(), "Native lib not loaded")
        }
        return try {
            val jsonStr = classifyText(ocrText, fileName)
            val obj = JSONObject(jsonStr)
            val cuesArray = obj.optJSONArray("matched_cues")
            val cues = mutableListOf<String>()
            if (cuesArray != null) {
                for (i in 0 until cuesArray.length()) {
                    cues.add(cuesArray.getString(i))
                }
            }
            ClassificationResult(
                tag = obj.optString("tag_name", "OTHER"),
                tagName = obj.optString("tag_name", "OTHER"),
                confidence = obj.optDouble("confidence", 0.0).toFloat(),
                shouldAutoExpire = obj.optBoolean("should_auto_expire", false),
                recommendedTtlHours = obj.optLong("recommended_ttl_hours", 24),
                matchedCues = cues,
                explanation = obj.optString("explanation", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse classify json: ${e.message}")
            ClassificationResult("OTHER", "OTHER", 0f, false, 0, emptyList(), e.message ?: "")
        }
    }

    fun tagFile(
        filePath: String,
        uriString: String?,
        tag: String,
        confidence: Float,
        ttlHours: Long,
        fileSizeBytes: Long
    ): Long {
        if (!isLoaded) return -1
        return tagScreenshot(filePath, uriString, tag, confidence, ttlHours, fileSizeBytes)
    }

    fun listActiveScreenshots(): List<ScreenshotRecord> {
        if (!isLoaded) return emptyList()
        return try {
            val jsonStr = getActiveScreenshots()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<ScreenshotRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ScreenshotRecord(
                        id = obj.optLong("id"),
                        filePath = obj.optString("file_path"),
                        uriString = if (obj.has("uri_string") && !obj.isNull("uri_string")) obj.getString("uri_string") else null,
                        tag = obj.optString("tag"),
                        confidence = obj.optDouble("confidence", 0.0).toFloat(),
                        createdAt = obj.optLong("created_at"),
                        expiresAt = obj.optLong("expires_at"),
                        status = obj.optString("status"),
                        fileSizeBytes = obj.optLong("file_size_bytes")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse active screenshots: ${e.message}")
            emptyList()
        }
    }

    fun markFileKept(filePath: String): Boolean {
        if (!isLoaded) return false
        return markKept(filePath)
    }

    fun deleteFileNow(filePath: String): Boolean {
        if (!isLoaded) return false
        return deleteNow(filePath)
    }

    fun executeExpiration(trashDir: String? = null): ExpirationSummary {
        if (!isLoaded) {
            return ExpirationSummary(0, 0, emptyList(), emptyList(), listOf("Native lib not loaded"))
        }
        return try {
            val jsonStr = runExpiration(trashDir)
            val obj = JSONObject(jsonStr)
            val paths = mutableListOf<String>()
            val uris = mutableListOf<String>()
            val errors = mutableListOf<String>()

            obj.optJSONArray("deleted_paths")?.let { arr ->
                for (i in 0 until arr.length()) paths.add(arr.getString(i))
            }
            obj.optJSONArray("deleted_uris")?.let { arr ->
                for (i in 0 until arr.length()) uris.add(arr.getString(i))
            }
            obj.optJSONArray("errors")?.let { arr ->
                for (i in 0 until arr.length()) errors.add(arr.getString(i))
            }

            ExpirationSummary(
                deletedCount = obj.optInt("deleted_count", 0),
                freedBytes = obj.optLong("freed_bytes", 0),
                deletedPaths = paths,
                deletedUris = uris,
                errors = errors
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse expiration summary: ${e.message}")
            ExpirationSummary(0, 0, emptyList(), emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }

    fun fetchStats(): DBStats {
        if (!isLoaded) return DBStats(0, 0, 0, 0)
        return try {
            val jsonStr = getStats()
            val obj = JSONObject(jsonStr)
            DBStats(
                totalActive = obj.optLong("total_active", 0),
                totalKept = obj.optLong("total_kept", 0),
                totalDeleted = obj.optLong("total_deleted", 0),
                estimatedBytesFreed = obj.optLong("estimated_bytes_freed", 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch stats: ${e.message}")
            DBStats(0, 0, 0, 0)
        }
    }
}

