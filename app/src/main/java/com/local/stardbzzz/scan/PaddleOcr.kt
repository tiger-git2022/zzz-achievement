package com.local.stardbzzz.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Packed ARGB pixels, row by row (the layout of Bitmap.getPixels and BufferedImage.getRGB). */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "${pixels.size} pixels for $width x $height" }
    }
}

/** A detected text line in image pixels. */
data class TextBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * PaddleOCR PP-OCRv6 small (text detection + recognition) on ONNX Runtime, without OpenCV.
 *
 * Follows RapidOCR's pipeline (tools/ocr-compare), which read the achievement page better than ML Kit:
 * DB detection on the frame shrunk to at most [DetMaxSide] px, then CTC recognition of each line cropped from
 * the full-size frame. Text on the achievement page is never rotated, so boxes are axis-aligned and there is no
 * direction classifier. Both models take BGR input, as they were trained on OpenCV images.
 */
class PaddleOcr(detModel: ByteArray, recModel: ByteArray, dictionary: String, threads: Int = 4) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val detector = environment.createSession(detModel, options)
    private val recognizer = environment.createSession(recModel, options)

    /**
     * CTC classes: blank, one per [dictionary] line, then a space. The model also stores the list in its
     * metadata, but ONNX Runtime's Java API garbles the emoji in it (four-byte UTF-8), shifting every class after them.
     */
    private val characters: List<String> = buildList {
        add("")
        addAll(dictionary.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") })
        add(" ")
    }

    init {
        val classes = (recognizer.outputInfo.values.first().info as TensorInfo).shape.last()
        check(classes == characters.size.toLong()) { "dictionary has ${characters.size} classes, the model $classes" }
    }

    fun recognize(image: RgbImage): List<OcrBox> {
        val width = image.width.toFloat()
        val height = image.height.toFloat()
        return detect(image).mapNotNull { box ->
            val (text, score) = read(image, box)
            if (text.isBlank() || score < MinTextScore) return@mapNotNull null
            OcrBox(text, box.left / width, box.top / height, box.right / width, box.bottom / height)
        }
    }

    /** Text line boxes in [image] pixels, top to bottom, then left to right. */
    fun detect(image: RgbImage): List<TextBox> {
        val (inputWidth, inputHeight) = detInputSize(image.width, image.height)
        val input = FloatArray(3 * inputWidth * inputHeight)
        writeBgr(image, 0, 0, image.width, image.height, inputWidth, inputHeight, inputWidth, input, DetNormalization)
        val probability = run(detector, input, longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong()))
        return DbPostProcess.boxes(probability, inputWidth, inputHeight, image.width, image.height)
            .sortedWith(compareBy<TextBox> { it.top }.thenBy { it.left })
    }

    /** The text in [box] and its confidence (mean probability of its characters). */
    fun read(image: RgbImage, box: TextBox): Pair<String, Float> {
        val resizedWidth = min(RecMaxWidth, ceil(RecHeight * box.width.toFloat() / box.height).toInt()).coerceAtLeast(1)
        // Narrow lines are padded to the width the model was trained on, as PaddleOCR does.
        val inputWidth = max(RecMinWidth, resizedWidth)
        val input = FloatArray(3 * RecHeight * inputWidth)
        writeBgr(image, box.left, box.top, box.width, box.height, resizedWidth, RecHeight, inputWidth, input, RecNormalization)
        val output = run(recognizer, input, longArrayOf(1, 3, RecHeight.toLong(), inputWidth.toLong()))
        return CtcDecoder.decode(output, characters)
    }

    private fun run(session: OrtSession, input: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val buffer = (result.get(0) as OnnxTensor).floatBuffer
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    override fun close() {
        detector.close()
        recognizer.close()
        options.close()
    }

    companion object {
        /** Like RapidOCR, which shrinks larger images to 2000 px before detection. */
        const val DetMaxSide = 2000
        const val DetMinSide = 736
        const val RecHeight = 48
        const val RecMinWidth = 320
        /** Wider than any line on the page; bounds the work and memory of one recognition run. */
        const val RecMaxWidth = 2400
        const val MinTextScore = 0.5f

        /** Detection input size: long side at most [DetMaxSide], short side at least [DetMinSide], multiples of 32. */
        fun detInputSize(width: Int, height: Int): Pair<Int, Int> {
            var scale = min(1.0, DetMaxSide.toDouble() / max(width, height))
            if (min(width, height) * scale < DetMinSide) scale = DetMinSide.toDouble() / min(width, height)
            fun fit(side: Int) = max(32, (side * scale / 32).roundToInt() * 32)
            return fit(width) to fit(height)
        }

        /** Per channel (B, G, R) mean and standard deviation of 0..1 pixel values, as in the models' training configs. */
        class Normalization(val mean: FloatArray, val std: FloatArray)

        /** PP-OCRv6 small detection: ImageNet statistics, applied in the image's BGR channel order as PaddleOCR does. */
        val DetNormalization = Normalization(floatArrayOf(0.485f, 0.456f, 0.406f), floatArrayOf(0.229f, 0.224f, 0.225f))

        /** Recognition: -1..1. */
        val RecNormalization = Normalization(floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))

        /**
         * Writes the region (left, top, regionWidth x regionHeight) of [image], bilinearly resized to
         * [outWidth] x [outHeight], into [out] as CHW planes of B, G, R normalized by [normalization], each row
         * [rowStride] wide (the rest stays 0, the padding).
         */
        fun writeBgr(
            image: RgbImage,
            left: Int,
            top: Int,
            regionWidth: Int,
            regionHeight: Int,
            outWidth: Int,
            outHeight: Int,
            rowStride: Int,
            out: FloatArray,
            normalization: Normalization,
        ) {
            val plane = rowStride * outHeight
            val scaleX = regionWidth.toFloat() / outWidth
            val scaleY = regionHeight.toFloat() / outHeight
            val maxX = image.width - 1
            val maxY = image.height - 1
            val xs0 = IntArray(outWidth)
            val xs1 = IntArray(outWidth)
            val wx = FloatArray(outWidth)
            for (x in 0 until outWidth) {
                // Pixel centers, as OpenCV's INTER_LINEAR.
                val source = ((x + 0.5f) * scaleX - 0.5f).coerceAtLeast(0f)
                val x0 = floor(source).toInt()
                wx[x] = source - x0
                xs0[x] = (left + x0).coerceIn(0, maxX)
                xs1[x] = (left + x0 + 1).coerceAtMost(left + regionWidth - 1).coerceIn(0, maxX)
            }
            for (y in 0 until outHeight) {
                val source = ((y + 0.5f) * scaleY - 0.5f).coerceAtLeast(0f)
                val y0 = floor(source).toInt()
                val wy = source - y0
                val row0 = (top + y0).coerceIn(0, maxY) * image.width
                val row1 = (top + y0 + 1).coerceAtMost(top + regionHeight - 1).coerceIn(0, maxY) * image.width
                for (x in 0 until outWidth) {
                    val p00 = image.pixels[row0 + xs0[x]]
                    val p01 = image.pixels[row0 + xs1[x]]
                    val p10 = image.pixels[row1 + xs0[x]]
                    val p11 = image.pixels[row1 + xs1[x]]
                    val index = y * rowStride + x
                    for (channel in 0..2) {
                        val value = lerp(p00, p01, p10, p11, ChannelShifts[channel], wx[x], wy)
                        out[channel * plane + index] = (value / 255f - normalization.mean[channel]) / normalization.std[channel]
                    }
                }
            }
        }

        /** ARGB bit offsets of blue, green and red. */
        private val ChannelShifts = intArrayOf(0, 8, 16)

        /** One channel (0..255) of four neighboring pixels, interpolated. */
        private fun lerp(p00: Int, p01: Int, p10: Int, p11: Int, shift: Int, wx: Float, wy: Float): Float {
            val c00 = (p00 shr shift and 0xFF).toFloat()
            val c01 = (p01 shr shift and 0xFF).toFloat()
            val c10 = (p10 shr shift and 0xFF).toFloat()
            val c11 = (p11 shr shift and 0xFF).toFloat()
            val top = c00 + (c01 - c00) * wx
            val bottom = c10 + (c11 - c10) * wx
            return top + (bottom - top) * wy
        }
    }
}

/** Differentiable Binarization post-processing: text line boxes from the detector's probability map. */
object DbPostProcess {
    private const val Threshold = 0.3f
    private const val BoxThreshold = 0.5f
    private const val UnclipRatio = 1.6f
    private const val MinSize = 3
    private const val MaxCandidates = 1000

    /**
     * Boxes, in [destWidth] x [destHeight] pixels, of the connected regions of [probability] (a [width] x
     * [height] map) above [Threshold] whose mean probability reaches [BoxThreshold], each grown by the DB
     * unclip distance (area x 1.6 / perimeter).
     */
    fun boxes(probability: FloatArray, width: Int, height: Int, destWidth: Int, destHeight: Int): List<TextBox> {
        val mask = BooleanArray(width * height) { probability[it] > Threshold }
        // A 2x2 dilation (as PaddleOCR's use_dilation) joins characters split by a one-pixel gap.
        val dilated = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            mask[index] || (x > 0 && mask[index - 1]) || (y > 0 && mask[index - width]) ||
                (x > 0 && y > 0 && mask[index - width - 1])
        }
        val result = mutableListOf<TextBox>()
        for (region in regions(dilated, width, height).take(MaxCandidates)) {
            // Corner coordinates, as OpenCV's minAreaRect of the region's contour gives for an upright region.
            val x0 = region[0].toFloat()
            val y0 = region[1].toFloat()
            val x1 = region[2].toFloat()
            val y1 = region[3].toFloat()
            if (min(x1 - x0, y1 - y0) < MinSize) continue
            if (meanInside(probability, width, region) < BoxThreshold) continue
            val distance = (x1 - x0) * (y1 - y0) * UnclipRatio / (2 * ((x1 - x0) + (y1 - y0)))
            if (min(x1 - x0, y1 - y0) + 2 * distance < MinSize + 2) continue
            fun scaleX(x: Float) = (x / width * destWidth).roundToInt().coerceIn(0, destWidth - 1)
            fun scaleY(y: Float) = (y / height * destHeight).roundToInt().coerceIn(0, destHeight - 1)
            val box = TextBox(scaleX(x0 - distance), scaleY(y0 - distance), scaleX(x1 + distance), scaleY(y1 + distance))
            if (box.width <= 3 || box.height <= 3) continue
            result += box
        }
        return result
    }

    /** Bounding boxes (minX, minY, maxX, maxY) of the 8-connected regions of [mask], in scan order. */
    fun regions(mask: BooleanArray, width: Int, height: Int): List<IntArray> {
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val regions = mutableListOf<IntArray>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            val bounds = intArrayOf(start % width, start / width, start % width, start / width)
            var size = 0
            stack[size++] = start
            seen[start] = true
            while (size > 0) {
                val index = stack[--size]
                val x = index % width
                val y = index / width
                bounds[0] = min(bounds[0], x)
                bounds[1] = min(bounds[1], y)
                bounds[2] = max(bounds[2], x)
                bounds[3] = max(bounds[3], y)
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val next = ny * width + nx
                        if (mask[next] && !seen[next]) {
                            seen[next] = true
                            stack[size++] = next
                        }
                    }
                }
            }
            regions += bounds
        }
        return regions
    }

    private fun meanInside(probability: FloatArray, width: Int, region: IntArray): Float {
        var sum = 0.0
        for (y in region[1]..region[3]) {
            for (x in region[0]..region[2]) sum += probability[y * width + x]
        }
        return (sum / ((region[2] - region[0] + 1) * (region[3] - region[1] + 1))).toFloat()
    }
}

/** Greedy CTC decoding of the recognizer's per-step class probabilities. */
object CtcDecoder {
    /**
     * Text and confidence from [output] (time steps x [characters] probabilities): the most likely class at each
     * step, with repeats merged and blanks (class 0) dropped.
     */
    fun decode(output: FloatArray, characters: List<String>): Pair<String, Float> {
        val classes = characters.size
        require(output.size % classes == 0) { "output of ${output.size} values isn't a multiple of $classes classes" }
        val text = StringBuilder()
        var scoreSum = 0f
        var count = 0
        var previous = -1
        for (step in 0 until output.size / classes) {
            val offset = step * classes
            var best = 0
            for (c in 1 until classes) if (output[offset + c] > output[offset + best]) best = c
            if (best != 0 && best != previous) {
                text.append(characters[best])
                scoreSum += output[offset + best]
                count++
            }
            previous = best
        }
        return text.toString() to if (count == 0) 0f else scoreSum / count
    }
}
