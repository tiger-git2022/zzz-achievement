package com.local.stardbzzz.scan

import java.util.Locale

/**
 * File formats of a debug scan session (Android/data/com.local.stardbzzz/files/scan-debug/<session>/), shared
 * by the app, which writes them, and ScanReplayTest, which replays a session offline:
 *
 * - frame_NNNN.tsv: one OCR line per row, `left top right bottom text`, coordinates normalized to the frame.
 * - frames.tsv: `frame tab category collected` — where the scanner was when it read each frame, and whether the
 *   scan collected that frame's rows (1) or only navigated with it (0: tab, sidebar and scroll-to-top reads).
 * - candidates.tsv: `id completed category version name description` — the local achievement list.
 * - report.txt: the scan report, readable and tab-separated.
 */
object ScanDebugFiles {
    const val FrameIndex = "frames.tsv"
    const val Candidates = "candidates.tsv"
    const val Report = "report.txt"

    fun frameTsv(boxes: List<OcrBox>): String =
        boxes.joinToString("\n", postfix = "\n") { box ->
            "%.4f\t%.4f\t%.4f\t%.4f\t%s".format(Locale.ROOT, box.left, box.top, box.right, box.bottom, clean(box.text))
        }

    /** Reads a frame TSV; blank lines and `#` comments are skipped. */
    fun parseFrameTsv(text: String): List<OcrBox> =
        text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 5)
                if (parts.size < 5) return@mapNotNull null
                val numbers = parts.take(4).map { it.toFloatOrNull() ?: return@mapNotNull null }
                OcrBox(parts[4], numbers[0], numbers[1], numbers[2], numbers[3])
            }
            .toList()

    data class FrameInfo(val tab: String, val category: String, val collected: Boolean)

    fun frameIndexLine(frame: String, tab: String, category: String, collected: Boolean): String =
        "$frame\t${clean(tab)}\t${clean(category)}\t${if (collected) 1 else 0}\n"

    /** frame name (e.g. frame_0001) → where it was read. Older sessions have no collected column: all count. */
    fun parseFrameIndex(text: String): Map<String, FrameInfo> =
        text.lineSequence()
            .filter { it.isNotBlank() }
            .map { it.split('\t') }
            .filter { it.size >= 3 }
            .associate { it[0] to FrameInfo(it[1], it[2], it.getOrNull(3) != "0") }

    fun candidatesTsv(candidates: List<ScanCandidate>): String =
        candidates.joinToString("\n", postfix = "\n") { candidate ->
            listOf(
                candidate.id,
                if (candidate.isCompleted) "1" else "0",
                candidate.category,
                candidate.version,
                candidate.name,
                candidate.description,
            ).joinToString("\t", transform = ::clean)
        }

    fun parseCandidates(text: String): List<ScanCandidate> =
        text.lineSequence()
            .filter { it.isNotBlank() }
            .map { it.split('\t', limit = 6) }
            .filter { it.size == 6 }
            .map { ScanCandidate(it[0], it[4], it[5], it[2], it[1] == "1", it[3]) }
            .toList()

    fun reportText(report: ScanReport): String = buildString {
        appendLine("游戏完成数\t${report.gameCompletedCount ?: "未识别"}")
        appendLine("本次看到已完成\t${report.gameCompletedSeen}")
        appendLine("本地已完成（成就）\t${report.localCompletedCount}")
        appendLine("本地已完成（记录）\t${report.localCompletedRecords}")
        appendLine("同步全部差异后\t${report.localCompletedAfterSync}")
        appendLine("识别\t${report.observedCount}")
        appendLine("匹配\t${report.matchedCount}")
        appendLine("未匹配\t${report.unmatched.size}")
        appendLine()
        appendLine("## 差异（${report.diffs.size}）")
        report.diffs.forEach { diff ->
            appendLine(listOf(if (diff.markCompleted) "标记完成" else "取消完成", diff.category, diff.name, diff.gameStatus, diff.id).joinToString("\t"))
        }
        appendLine()
        appendLine("## 未匹配（${report.unmatched.size}）")
        report.unmatched.forEach { row ->
            appendLine(listOf(row.tab, row.category, row.name, row.description, statusText(row.status)).joinToString("\t", transform = ::clean))
        }
        appendLine()
        appendLine("## 本地已完成但本次没看到（${report.localCompletedNotSeen.size}）")
        report.localCompletedNotSeen.forEach { appendLine(listOf(it.category, it.name, it.id).joinToString("\t", transform = ::clean)) }
    }

    private fun statusText(status: RowStatus): String = when (status) {
        is RowStatus.Completed -> status.date ?: "已完成"
        is RowStatus.InProgress -> "${status.current}/${status.target}"
    }

    private fun clean(text: String): String = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
