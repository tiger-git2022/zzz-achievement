package com.local.stardbzzz.scan

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Replays a debug scan session offline: parses every saved frame with the current parser and matcher and writes
 * the report next to the frames. Skipped unless SCAN_REPLAY_DIR is set, e.g. on Windows:
 *
 *     set SCAN_REPLAY_DIR=C:\...\scan-debug\20261014-032011
 *     gradlew testReleaseUnitTest --tests "*ScanReplayTest" --rerun
 *
 * The folder needs the frame_NNNN.tsv files and candidates.tsv (the app writes it at the start of every debug
 * scan). frames.tsv (tab/category per frame) is optional; without it rows get no category hint.
 * SCAN_REPLAY_SUFFIX picks another OCR engine's output, e.g. `.paddle.tsv` for frame_NNNN.paddle.tsv.
 * Output: replay-report<suffix>.txt — the scan report plus, for each unmatched row, its nearest local names.
 *
 * With SCAN_REPLAY_COMPARE set to a second suffix (e.g. `.paddle.tsv`), it also writes replay-compare.txt:
 * the two engines' rows side by side on every frame, and how many of each matched a local achievement.
 */
class ScanReplayTest {
    @Test
    fun replayDebugSession() {
        val path = System.getenv("SCAN_REPLAY_DIR")
        assumeTrue("SCAN_REPLAY_DIR not set; nothing to replay", !path.isNullOrBlank())
        val dir = File(path!!)
        val suffix = System.getenv("SCAN_REPLAY_SUFFIX")?.takeIf { it.isNotBlank() } ?: ".tsv"
        val candidatesFile = File(dir, ScanDebugFiles.Candidates)
        check(candidatesFile.isFile) { "$candidatesFile missing: start a scan with 保存调试数据 on to export it" }

        val result = replay(dir, suffix, ScanDebugFiles.parseCandidates(candidatesFile.readText()))
        val out = File(dir, "replay-report${suffix.removeSuffix(".tsv")}.txt")
        out.writeText(result)
        println(result.lineSequence().take(8).joinToString("\n"))
        println("Full report: $out")

        System.getenv("SCAN_REPLAY_COMPARE")?.takeIf { it.isNotBlank() }?.let { other ->
            val comparison = compare(dir, suffix, other, ScanDebugFiles.parseCandidates(candidatesFile.readText()))
            val compareOut = File(dir, "replay-compare.txt")
            compareOut.writeText(comparison)
            println(comparison.lineSequence().take(8).joinToString("\n"))
            println("Full comparison: $compareOut")
        }
    }

    @Test
    fun replaysAFolderOfFrames() {
        val dir = Files.createTempDirectory("scan-replay").toFile()
        try {
            listOf("story", "city", "explore").forEachIndexed { i, fixture ->
                val text = javaClass.classLoader!!.getResourceAsStream("ocr/$fixture.tsv")!!.bufferedReader().readText()
                File(dir, "frame_%04d.tsv".format(Locale.ROOT, i + 1)).writeText(text)
            }
            File(dir, ScanDebugFiles.FrameIndex).writeText(
                ScanDebugFiles.frameIndexLine("frame_0001", "故事", "法厄同纪事", collected = true) +
                    ScanDebugFiles.frameIndexLine("frame_0002", "城市", "绳匠业务", collected = true) +
                    // Read while switching tabs: the live scan didn't collect these rows, so the replay mustn't.
                    ScanDebugFiles.frameIndexLine("frame_0003", "探索", "", collected = false),
            )
            val candidates = listOf(
                ScanCandidate("1", "从零开始的绳匠生活", "", "法厄同纪事", isCompleted = true),
                ScanCandidate("2", "叩响命运之门", "", "法厄同纪事", isCompleted = false),
                ScanCandidate("3", "少女神明的七日箱庭", "", "绳匠业务", isCompleted = true),
                ScanCandidate("4", "从未出现的成就", "", "绳匠业务", isCompleted = true),
            )
            File(dir, ScanDebugFiles.Candidates).writeText(ScanDebugFiles.candidatesTsv(candidates))

            val report = replay(dir, ".tsv", ScanDebugFiles.parseCandidates(File(dir, ScanDebugFiles.Candidates).readText()))

            assertTrue(report, report.contains("游戏完成数\t631"))
            assertTrue(report, report.contains("标记完成\t法厄同纪事\t叩响命运之门"))
            assertTrue(report, report.contains("## 本地已完成但本次没看到（1）\n绳匠业务\t从未出现的成就"))
            // Every unmatched row lists its nearest local names.
            assertTrue(report, report.contains("LV60，恭迎王的回归\t→ "))
            assertTrue(report, !report.contains("英雄之旅"))
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        /**
         * Rows of two engines' reads of the same frames, paired by their order on the page. Counts how many of
         * each engine's rows match a local achievement, and lists every row where the two read differently.
         */
        fun compare(dir: File, suffixA: String, suffixB: String, candidates: List<ScanCandidate>): String {
            val matcher = AchievementMatcher(candidates)
            val index = File(dir, ScanDebugFiles.FrameIndex).takeIf { it.isFile }
                ?.let { ScanDebugFiles.parseFrameIndex(it.readText()) }
                .orEmpty()
            val framesA = frameFiles(dir, suffixA).toMap()
            val framesB = frameFiles(dir, suffixB).toMap()
            val shared = framesA.keys.intersect(framesB.keys).sorted()
            check(shared.isNotEmpty()) { "No frame has both $suffixA and $suffixB" }

            var rowsA = 0
            var rowsB = 0
            var matchedA = 0
            var matchedB = 0
            var countMismatch = 0
            val differences = mutableListOf<String>()
            shared.forEach { frame ->
                val a = GameScreenParser.parse(ScanDebugFiles.parseFrameTsv(framesA.getValue(frame).readText())).rows
                val b = GameScreenParser.parse(ScanDebugFiles.parseFrameTsv(framesB.getValue(frame).readText())).rows
                val category = index[frame]?.category
                rowsA += a.size
                rowsB += b.size
                if (a.size != b.size) countMismatch++
                fun matchOf(row: GameRow) = matcher.match(row.name, row.description, category)
                a.forEach { if (matchOf(it) != null) matchedA++ }
                b.forEach { if (matchOf(it) != null) matchedB++ }
                (0 until maxOf(a.size, b.size)).forEach { i ->
                    val rowA = a.getOrNull(i)
                    val rowB = b.getOrNull(i)
                    if (rowA?.name != rowB?.name || rowA?.status != rowB?.status) {
                        fun describe(row: GameRow?) = row?.let { "${it.name}${if (matchOf(it) != null) "" else "（未匹配）"}" } ?: "（无）"
                        differences += "$frame\t${describe(rowA)}\t${describe(rowB)}"
                    }
                }
            }

            return buildString {
                appendLine("对比\t$suffixA\t$suffixB")
                appendLine("帧数\t${shared.size}\t${shared.size}")
                appendLine("识别出的行\t$rowsA\t$rowsB")
                appendLine("能匹配本地成就的行\t$matchedA\t$matchedB")
                appendLine("行数不同的帧\t$countMismatch")
                appendLine("读法不同的行\t${differences.size}")
                appendLine()
                appendLine("## 读法不同的行（帧\t$suffixA\t$suffixB）")
                differences.forEach(::appendLine)
            }
        }

        private fun frameFiles(dir: File, suffix: String): List<Pair<String, File>> {
            val framePattern = Regex("""(frame_\d+)""" + Regex.escape(suffix))
            return dir.listFiles().orEmpty()
                .mapNotNull { file -> framePattern.matchEntire(file.name)?.let { it.groupValues[1] to file } }
                .sortedBy { it.first }
        }

        fun replay(dir: File, suffix: String, candidates: List<ScanCandidate>): String {
            val frames = frameFiles(dir, suffix)
            check(frames.isNotEmpty()) { "No frame_NNNN$suffix files in $dir" }
            val index = File(dir, ScanDebugFiles.FrameIndex).takeIf { it.isFile }
                ?.let { ScanDebugFiles.parseFrameIndex(it.readText()) }
                .orEmpty()

            var completedCount: Int? = null
            val rows = frames.flatMap { (name, file) ->
                val page = GameScreenParser.parse(ScanDebugFiles.parseFrameTsv(file.readText()))
                if (completedCount == null) completedCount = page.completedCount
                val info = index[name]
                // Like the live scan, keep only rows read while going down a category's list.
                if (info?.collected == false) return@flatMap emptyList()
                page.rows.map { ObservedRow(info?.tab.orEmpty(), info?.category.orEmpty(), it.name, it.description, it.status) }
            }
            val report = ScanReportBuilder.build(rows, candidates, completedCount)

            return buildString {
                appendLine("帧数\t${frames.size}（$suffix）")
                append(ScanDebugFiles.reportText(report))
                appendLine()
                appendLine("## 未匹配行的最接近本地名称")
                report.unmatched.forEach { row -> appendLine("${row.name}\t→ ${nearest(row.name, candidates)}") }
            }
        }

        /** The three local names closest to an unmatched OCR name, to spot misread characters. */
        private fun nearest(name: String, candidates: List<ScanCandidate>): String {
            val stem = AchievementMatcher.nameStem(AchievementMatcher.normalizeText(name))
            return candidates.distinctBy { it.name }
                .map { it to AchievementMatcher.similarity(AchievementMatcher.nameStem(AchievementMatcher.normalizeText(it.name)), stem) }
                .sortedByDescending { it.second }
                .take(3)
                .joinToString(" / ") { (candidate, score) -> "%s (%.2f)".format(Locale.ROOT, candidate.name, score) }
        }
    }
}
