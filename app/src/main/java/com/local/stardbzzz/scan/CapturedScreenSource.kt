package com.local.stardbzzz.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Where the scan's frames go when 保存调试数据 is on. */
interface FrameLog {
    /** A frame read right away, with its OCR lines. */
    fun read(frame: Bitmap, boxes: List<OcrBox>)

    /** A frame queued for OCR; the returned handle comes back with its lines in [readLater]. */
    fun queued(frame: Bitmap): String?

    fun readLater(handle: String, boxes: List<OcrBox>)
}

/**
 * Screen frames from [grab], read with the bundled PaddleOCR. A frame's pixels are copied once per capture into a
 * reused buffer, so a frame is only valid until the next capture. A queued region is saved as a JPEG in
 * [queueDirectory] until it is read: on the phone's frames PaddleOCR read every row of a JPEG (quality 90) the same
 * as the original, and a page of the list takes ~10 MB as pixels but ~0.5 MB as a JPEG.
 */
class CapturedScreenSource(
    private val grab: suspend () -> Bitmap,
    private val ocr: PaddleTextRecognizer,
    private val queueDirectory: File,
    private val log: FrameLog? = null,
) : ScreenSource {
    private var buffer = IntArray(0)
    private var queuedFrames = 0

    override suspend fun capture(): ScreenFrame {
        val bitmap = grab()
        val size = bitmap.width * bitmap.height
        if (buffer.size != size) buffer = IntArray(size)
        bitmap.getPixels(buffer, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Frame(bitmap, buffer)
    }

    private inner class Frame(private val bitmap: Bitmap, private val pixels: IntArray) : ScreenFrame {
        private val width = bitmap.width
        private val height = bitmap.height

        override suspend fun read(region: ScreenRegion): List<OcrBox> {
            val area = Area.of(region, width, height)
            val boxes = area.toFrame(ocr.recognize(area.copy(pixels, width)), width, height)
            log?.read(bitmap, boxes)
            return boxes
        }

        override fun thumbnail(region: ScreenRegion): Thumbnail {
            val area = Area.of(region, width, height)
            return Thumbnail.of(pixels, width, area.left, area.top, area.width, area.height)
        }

        override suspend fun keep(region: ScreenRegion): KeptFrame {
            val area = Area.of(region, width, height)
            val crop = Bitmap.createBitmap(pixels, area.top * width + area.left, width, area.width, area.height, Bitmap.Config.ARGB_8888)
            val file = File(queueDirectory, "page_%04d.jpg".format(Locale.ROOT, queuedFrames++))
            withContext(Dispatchers.IO) {
                queueDirectory.mkdirs()
                file.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, QueueJpegQuality, it) }
            }
            crop.recycle()
            val handle = log?.queued(bitmap)
            val frameWidth = width
            val frameHeight = height
            return KeptFrame {
                val image = withContext(Dispatchers.IO) {
                    val decoded = BitmapFactory.decodeFile(file.path) ?: error("截图缓存读取失败：${file.name}")
                    try {
                        RgbImage(decoded.width, decoded.height, IntArray(decoded.width * decoded.height).also {
                            decoded.getPixels(it, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                        })
                    } finally {
                        decoded.recycle()
                        file.delete()
                    }
                }
                area.toFrame(ocr.recognize(image), frameWidth, frameHeight).also { boxes ->
                    handle?.let { log?.readLater(it, boxes) }
                }
            }
        }
    }

    /** A region in pixels. */
    private class Area(val left: Int, val top: Int, val width: Int, val height: Int) {
        fun copy(pixels: IntArray, stride: Int): RgbImage {
            val out = IntArray(width * height)
            for (y in 0 until height) System.arraycopy(pixels, (top + y) * stride + left, out, y * width, width)
            return RgbImage(width, height, out)
        }

        /** Boxes normalized to this area, normalized to the whole frame instead. */
        fun toFrame(boxes: List<OcrBox>, frameWidth: Int, frameHeight: Int): List<OcrBox> = boxes.map { box ->
            fun x(value: Float) = (left + value * width) / frameWidth
            fun y(value: Float) = (top + value * height) / frameHeight
            box.copy(left = x(box.left), top = y(box.top), right = x(box.right), bottom = y(box.bottom))
        }

        companion object {
            fun of(region: ScreenRegion, frameWidth: Int, frameHeight: Int): Area {
                val left = (region.left * frameWidth).toInt().coerceIn(0, frameWidth - 1)
                val top = (region.top * frameHeight).toInt().coerceIn(0, frameHeight - 1)
                val right = (region.right * frameWidth).toInt().coerceIn(left + 1, frameWidth)
                val bottom = (region.bottom * frameHeight).toInt().coerceIn(top + 1, frameHeight)
                return Area(left, top, right - left, bottom - top)
            }
        }
    }

    private companion object {
        const val QueueJpegQuality = 92
    }
}
