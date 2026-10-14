package com.local.stardbzzz.scan

import kotlin.math.abs

/** A rectangle of the screen, normalized to 0..1 on both axes. */
data class ScreenRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val Full = ScreenRegion(0f, 0f, 1f, 1f)
    }
}

/** One captured screen. It is only valid until the next capture; [keep] copies what is read later. */
interface ScreenFrame {
    /** The OCR lines inside [region], with coordinates normalized to the whole frame. */
    suspend fun read(region: ScreenRegion = ScreenRegion.Full): List<OcrBox>

    /** A small grayscale copy of [region]; two of an unchanged screen are nearly identical. */
    fun thumbnail(region: ScreenRegion): Thumbnail

    /** A copy of [region] that can be read after later captures, so OCR can run off the scan's path. */
    suspend fun keep(region: ScreenRegion): KeptFrame
}

/** A region of an earlier frame, waiting to be read. */
fun interface KeptFrame {
    /** Its OCR lines, normalized to the whole frame as [ScreenFrame.read] gives them; releases the copy. */
    suspend fun read(): List<OcrBox>
}

fun interface ScreenSource {
    suspend fun capture(): ScreenFrame
}

/** Grayscale pixels (0..255) of a downscaled screen region. */
class Thumbnail(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(pixels.size == width * height) { "${pixels.size} pixels for $width x $height" }
    }

    /** Mean absolute difference per pixel; [Float.MAX_VALUE] when the sizes differ (e.g. after a rotation). */
    fun difference(other: Thumbnail): Float {
        if (width != other.width || height != other.height || pixels.isEmpty()) return Float.MAX_VALUE
        var sum = 0.0
        for (i in pixels.indices) sum += abs(pixels[i] - other.pixels[i])
        return (sum / pixels.size).toFloat()
    }

    companion object {
        /** Thumbnails are this many times smaller than the screen on each axis. */
        const val Downscale = 8

        /**
         * The grayscale thumbnail of ([left], [top], [width] x [height]) in packed ARGB [argb] rows of
         * [stride] pixels: each thumbnail pixel averages one [Downscale] x [Downscale] block.
         */
        fun of(argb: IntArray, stride: Int, left: Int, top: Int, width: Int, height: Int): Thumbnail {
            val w = (width / Downscale).coerceAtLeast(1)
            val h = (height / Downscale).coerceAtLeast(1)
            val pixels = FloatArray(w * h)
            val blockWidth = width / w
            val blockHeight = height / h
            for (ty in 0 until h) {
                for (tx in 0 until w) {
                    var sum = 0
                    for (y in top + ty * blockHeight until top + (ty + 1) * blockHeight) {
                        val row = y * stride
                        for (x in left + tx * blockWidth until left + (tx + 1) * blockWidth) {
                            val p = argb[row + x]
                            // ITU-R BT.601 luma in integer weights (sum 256).
                            sum += ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                        }
                    }
                    pixels[ty * w + tx] = sum.toFloat() / (blockWidth * blockHeight)
                }
            }
            return Thumbnail(w, h, pixels)
        }
    }
}
