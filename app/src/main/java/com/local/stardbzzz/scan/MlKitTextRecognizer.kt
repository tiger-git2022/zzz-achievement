package com.local.stardbzzz.scan

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device Chinese OCR; returns one box per recognized line, normalized to the bitmap size. */
class MlKitTextRecognizer : Closeable {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognize(bitmap: Bitmap): List<OcrBox> {
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrBox(line.text, box.left / width, box.top / height, box.right / width, box.bottom / height)
        }
    }

    override fun close() {
        recognizer.close()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
