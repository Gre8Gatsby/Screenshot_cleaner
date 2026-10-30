package com.expirer.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCancellableCoroutine

object OcrHelper {
    private const val TAG = "OcrHelper"
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun extractText(context: Context, uri: Uri): String {
        return suspendCancellableCoroutine { continuation ->
            try {
                val inputImage = InputImage.fromFilePath(context, uri)
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        continuation.resume(visionText.text)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "OCR recognition error: ${e.message}")
                        continuation.resume("")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load image for OCR: ${e.message}")
                continuation.resume("")
            }
        }
    }
}

