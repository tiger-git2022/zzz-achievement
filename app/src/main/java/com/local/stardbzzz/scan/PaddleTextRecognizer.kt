package com.local.stardbzzz.scan

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * On-device Chinese OCR with the bundled PaddleOCR models (assets/ppocr); returns one box per recognized line,
 * normalized to the bitmap size.
 */
class PaddleTextRecognizer private constructor(private val ocr: PaddleOcr) : Closeable {
    suspend fun recognize(bitmap: Bitmap): List<OcrBox> = withContext(Dispatchers.Default) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        ocr.recognize(RgbImage(bitmap.width, bitmap.height, pixels))
    }

    override fun close() {
        ocr.close()
    }

    companion object {
        /** Loads the models (about 31 MB). */
        suspend fun create(context: Context): PaddleTextRecognizer = withContext(Dispatchers.IO) {
            val assets = context.assets
            fun read(name: String) = assets.open("ppocr/$name").use { it.readBytes() }
            PaddleTextRecognizer(
                PaddleOcr(
                    detModel = read("det.onnx"),
                    recModel = read("rec.onnx"),
                    dictionary = read("dict.txt").decodeToString(),
                    threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                ),
            )
        }
    }
}
