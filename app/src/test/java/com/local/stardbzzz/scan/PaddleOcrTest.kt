package com.local.stardbzzz.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PaddleOcrTest {
    @Test
    fun ctcMergesRepeatsAndDropsBlanks() {
        val characters = listOf("", "完", "成", " ")
        // Steps: 完 完 blank 成 blank 成 → 完成成 (a blank between two 成 keeps both).
        val steps = listOf(1, 1, 0, 2, 0, 2)
        val output = steps.flatMap { best -> List(characters.size) { if (it == best) 0.9f else 0.01f } }.toFloatArray()

        val (text, score) = CtcDecoder.decode(output, characters)

        assertEquals("完成成", text)
        assertEquals(0.9f, score, 1e-6f)
    }

    @Test
    fun ctcOfOnlyBlanksIsEmpty() {
        val (text, score) = CtcDecoder.decode(floatArrayOf(0.9f, 0.1f, 0.8f, 0.2f), listOf("", "a"))
        assertEquals("", text)
        assertEquals(0f, score)
    }

    @Test
    fun detectionInputKeepsThePhoneFrameUnder2000PxInMultiplesOf32() {
        assertEquals(1984 to 896, PaddleOcr.detInputSize(3168, 1440))
        // Small images are enlarged so the short side reaches 736.
        assertEquals(1312 to 736, PaddleOcr.detInputSize(1280, 720))
    }

    @Test
    fun dbBoxesAreGrownByTheUnclipDistanceAndScaledToTheImage() {
        val width = 100
        val height = 40
        val probability = FloatArray(width * height)
        // One 41 x 11 text region (x 20..60, y 10..20) and a speck too small to be text.
        for (y in 10..20) for (x in 20..60) probability[y * width + x] = 0.9f
        probability[35 * width + 90] = 0.9f

        val boxes = DbPostProcess.boxes(probability, width, height, destWidth = 200, destHeight = 80)

        // The 2x2 dilation adds a pixel right and below: corners (20,10)-(61,21), 41 x 11.
        // Unclip distance = 41 * 11 * 1.6 / (2 * (41 + 11)) ≈ 6.94, then everything doubles.
        assertEquals(listOf(TextBox(26, 6, 136, 56)), boxes)
    }

    @Test
    fun dbIgnoresRegionsWithALowMeanProbability() {
        val width = 60
        val height = 30
        val probability = FloatArray(width * height)
        // Above the 0.3 threshold but below the 0.5 box threshold on average.
        for (y in 5..20) for (x in 5..50) probability[y * width + x] = 0.35f

        assertEquals(emptyList<TextBox>(), DbPostProcess.boxes(probability, width, height, width, height))
    }

    @Test
    fun connectedRegionsJoinDiagonalNeighbors() {
        val mask = BooleanArray(16)
        // A diagonal line (0,0) (1,1) (2,2) and a separate pixel at (0,3).
        mask[0] = true
        mask[5] = true
        mask[10] = true
        mask[12] = true

        val regions = DbPostProcess.regions(mask, 4, 4).map { it.toList() }

        assertEquals(listOf(listOf(0, 0, 2, 2), listOf(0, 3, 0, 3)), regions)
    }

    /** The bundled models on a drawn counter and date, end to end (BGR order, normalization, dictionary). */
    @Test
    fun readsDrawnDigitsWithTheBundledModels() {
        val image = runCatching {
            DesktopImages.drawText(1200, 400, size = 56, lines = listOf(Triple("07/12", 120, 140), Triple("2026-07-11", 600, 300)))
        }.getOrNull()
        assumeTrue("no font to draw with on this machine", image != null)

        val texts = bundledOcr().use { ocr -> ocr.recognize(image!!) }.map { it.text.replace(" ", "") }

        assertTrue(texts.toString(), "07/12" in texts)
        assertTrue(texts.toString(), "2026-07-11" in texts)
    }

    /**
     * Re-reads a debug scan session's frames with the app's OCR, so a session saved by an older version
     * (ML Kit) can be replayed with the current engine. Skipped unless SCAN_OCR_DIR is set, e.g. on Windows:
     *
     *     set SCAN_OCR_DIR=C:\...\scan-debug\20261014-032011
     *     gradlew testReleaseUnitTest --tests "*PaddleOcrTest" --rerun
     *
     * Writes frame_NNNN.paddle.tsv next to each frame_NNNN.jpg (SCAN_OCR_SUFFIX picks another suffix);
     * then SCAN_REPLAY_SUFFIX=.paddle.tsv makes ScanReplayTest use them.
     */
    @Test
    fun reReadDebugSession() {
        val path = System.getenv("SCAN_OCR_DIR")
        assumeTrue("SCAN_OCR_DIR not set; nothing to read", !path.isNullOrBlank())
        val suffix = System.getenv("SCAN_OCR_SUFFIX")?.takeIf { it.isNotBlank() } ?: ".paddle.tsv"
        val frames = File(path!!).listFiles { file -> file.name.matches(Regex("""frame_\d+\.jpg""")) }.orEmpty().sorted()
        check(frames.isNotEmpty()) { "No frame_NNNN.jpg in $path" }

        bundledOcr().use { ocr ->
            val millis = frames.map { frame ->
                val started = System.nanoTime()
                val boxes = ocr.recognize(DesktopImages.read(frame))
                val elapsed = (System.nanoTime() - started) / 1_000_000
                File(frame.parentFile, frame.nameWithoutExtension + suffix).writeText(ScanDebugFiles.frameTsv(boxes))
                println("${frame.name}: ${boxes.size} lines, $elapsed ms")
                elapsed
            }
            println("${frames.size} frames, median ${millis.sorted()[millis.size / 2]} ms")
        }
    }

    private fun bundledOcr(): PaddleOcr {
        val models = File("src/main/assets/ppocr")
        return PaddleOcr(
            File(models, "det.onnx").readBytes(),
            File(models, "rec.onnx").readBytes(),
            File(models, "dict.txt").readText(),
        )
    }
}

/**
 * java.awt and javax.imageio through reflection: unit tests compile against android.jar, which lacks them,
 * but run on a desktop JDK, which has them.
 */
private object DesktopImages {
    private val int = Int::class.javaPrimitiveType!!

    fun read(file: File): RgbImage =
        toRgbImage(Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)!!)

    /** Light bold sans-serif [lines] (text, x, baseline y) on a dark background, like the game's. */
    fun drawText(width: Int, height: Int, size: Int, lines: List<Triple<String, Int, Int>>): RgbImage {
        val imageClass = Class.forName("java.awt.image.BufferedImage")
        val image = imageClass.getConstructor(int, int, int).newInstance(width, height, 1) // TYPE_INT_RGB
        val graphicsClass = Class.forName("java.awt.Graphics2D")
        val graphics = imageClass.getMethod("createGraphics").invoke(image)
        val hints = Class.forName("java.awt.RenderingHints")
        graphicsClass.getMethod("setRenderingHint", Class.forName("java.awt.RenderingHints\$Key"), Any::class.java)
            .invoke(graphics, hints.getField("KEY_TEXT_ANTIALIASING").get(null), hints.getField("VALUE_TEXT_ANTIALIAS_ON").get(null))
        val colorClass = Class.forName("java.awt.Color")
        fun setColor(gray: Int) = graphicsClass.getMethod("setColor", colorClass)
            .invoke(graphics, colorClass.getConstructor(int, int, int).newInstance(gray, gray, gray))
        setColor(24)
        graphicsClass.getMethod("fillRect", int, int, int, int).invoke(graphics, 0, 0, width, height)
        setColor(235)
        val fontClass = Class.forName("java.awt.Font")
        graphicsClass.getMethod("setFont", fontClass)
            .invoke(graphics, fontClass.getConstructor(String::class.java, int, int).newInstance("SansSerif", 1, size)) // BOLD
        val drawString = graphicsClass.getMethod("drawString", String::class.java, int, int)
        lines.forEach { (text, x, y) -> drawString.invoke(graphics, text, x, y) }
        graphicsClass.getMethod("dispose").invoke(graphics)
        return toRgbImage(image)
    }

    private fun toRgbImage(image: Any): RgbImage {
        val imageClass = image.javaClass
        val width = imageClass.getMethod("getWidth").invoke(image) as Int
        val height = imageClass.getMethod("getHeight").invoke(image) as Int
        val pixels = imageClass.getMethod("getRGB", int, int, int, int, IntArray::class.java, int, int)
            .invoke(image, 0, 0, width, height, null, 0, width) as IntArray
        return RgbImage(width, height, pixels)
    }
}
