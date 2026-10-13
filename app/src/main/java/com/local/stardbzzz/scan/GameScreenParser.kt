package com.local.stardbzzz.scan

import java.util.Locale
import kotlin.math.abs

/** One recognized text line; coordinates are normalized to the screenshot size (0..1). */
data class OcrBox(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** A normalized screen position (0..1 on both axes). */
data class ScreenPoint(val x: Float, val y: Float)

sealed interface RowStatus {
    /** The row shows a completion date (or the reward is claimable). */
    data class Completed(val date: String?) : RowStatus

    /** The row shows a progress counter such as 07/12. */
    data class InProgress(val current: Long, val target: Long) : RowStatus
}

data class GameRow(
    val name: String,
    val description: String,
    val status: RowStatus,
)

data class SidebarLabel(val text: String, val center: ScreenPoint)

data class ParsedScreen(
    val tabs: Map<String, ScreenPoint>,
    val sidebar: List<SidebarLabel>,
    val rows: List<GameRow>,
    val completedCount: Int?,
) {
    /** The tab bar is the most reliable marker that the in-game achievement page is open. */
    val isAchievementPage: Boolean get() = tabs.size >= 3
}

/**
 * Parses the Zenless Zone Zero achievement page from OCR output.
 *
 * Layout (landscape, normalized): tab bar along the top right, the "完成数" counter top left,
 * sub-category sidebar on the left, and the achievement list in the middle with one row per
 * achievement: name and description on the left, status (completion date or x/y progress) on the right.
 */
object GameScreenParser {
    val TabNames = listOf("故事", "城市", "战术", "探索")

    private val DateRegex = Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})""")
    private val ProgressRegex = Regex("""(\d+)\s*/\s*(\d+)""")
    private val DigitsRegex = Regex("""\d+""")

    /** A counter some rows show between name and description, e.g. 当前最多拥有: 91. */
    private val ProgressLineRegex = Regex("""^\D{1,12}[:：]\s*\d+(\s*/\s*\d+)?$""")

    fun parse(boxes: List<OcrBox>): ParsedScreen = ParsedScreen(
        tabs = findTabs(boxes),
        sidebar = findSidebar(boxes),
        rows = findRows(boxes),
        completedCount = findCompletedCount(boxes),
    )

    fun parseStatus(text: String): RowStatus? {
        DateRegex.find(text)?.let { match ->
            val (year, month, day) = match.destructured
            return RowStatus.Completed("%s-%02d-%02d".format(Locale.ROOT, year, month.toInt(), day.toInt()))
        }
        ProgressRegex.find(text)?.let { match ->
            val current = match.groupValues[1].toLongOrNull() ?: return null
            val target = match.groupValues[2].toLongOrNull() ?: return null
            // A counter above its target can only be a misread (e.g. a dropped digit); don't guess.
            if (target <= 0 || current > target) return null
            // Reached but not yet claimed: the game would show a claim button instead of a date.
            return if (current == target) RowStatus.Completed(null) else RowStatus.InProgress(current, target)
        }
        if (text.contains("领取")) return RowStatus.Completed(null)
        return null
    }

    private fun findTabs(boxes: List<OcrBox>): Map<String, ScreenPoint> {
        val tabs = linkedMapOf<String, ScreenPoint>()
        // The tab bar starts at ~0.43 of the width; the scan overlay sits left of it.
        val bar = boxes.filter { it.centerY < 0.12f && it.centerX > 0.45f }
        // Characters already taken by a tab, so one read can't stand for two tabs (故市 ≈ 故事 and 城市).
        val claimed = bar.map { BooleanArray(it.text.length) }
        // Exact names first; then, for any still missing, a read with one of its two characters wrong
        // (ML Kit reads 探索 as 採索 on every frame of one phone). A near match only counts as a box of its
        // own: inside a merged line its midpoint could fall between two tabs.
        bar.forEachIndexed { boxIndex, box ->
            // The tabs may come back as one merged line; place each name proportionally inside the box.
            TabNames.forEach { name ->
                if (name in tabs) return@forEach
                val index = findName(box.text, name, claimed[boxIndex], exact = true)
                if (index >= 0) tabs[name] = claim(box, claimed[boxIndex], name, index)
            }
        }
        bar.forEachIndexed { boxIndex, box ->
            // A read one character off two missing names (故市: 故事 or 城市?) can't be placed; skip it.
            val candidates = TabNames.filter { name ->
                name !in tabs && box.text.length == name.length &&
                    findName(box.text, name, claimed[boxIndex], exact = false) == 0
            }
            candidates.singleOrNull()?.let { name -> tabs[name] = claim(box, claimed[boxIndex], name, 0) }
        }
        return tabs
    }

    private fun claim(box: OcrBox, claimed: BooleanArray, name: String, index: Int): ScreenPoint {
        name.indices.forEach { claimed[index + it] = true }
        val fraction = (index + name.length / 2f) / box.text.length
        return ScreenPoint(box.left + (box.right - box.left) * fraction, box.centerY)
    }

    /** Start of the first unclaimed window of [text] matching [name] exactly, or in all but one character. */
    private fun findName(text: String, name: String, claimed: BooleanArray, exact: Boolean): Int =
        (0..text.length - name.length).firstOrNull { start ->
            val free = name.indices.none { claimed[start + it] }
            val same = name.indices.count { text[start + it] == name[it] }
            free && same >= if (exact) name.length else name.length - 1
        } ?: -1

    private fun findCompletedCount(boxes: List<OcrBox>): Int? {
        val label = boxes.firstOrNull { it.text.contains("完成数") && it.centerY < 0.15f } ?: return null
        DigitsRegex.find(label.text.substringAfter("完成数"))?.let { return it.value.toIntOrNull() }
        return boxes
            .filter { it !== label && it.top >= label.centerY && it.top <= label.bottom + 0.08f }
            .filter { it.right >= label.left && it.left <= label.right + 0.05f }
            .sortedBy { it.top }
            .firstNotNullOfOrNull { box ->
                DigitsRegex.matchEntire(box.text.trim())?.value?.toIntOrNull()
            }
    }

    private fun findSidebar(boxes: List<OcrBox>): List<SidebarLabel> =
        boxes
            .filter { it.centerX in 0.05f..0.25f && it.centerY in 0.22f..0.86f }
            .filter { box -> box.text.count { it.isCjk() } >= 2 }
            .sortedBy { it.centerY }
            .map { SidebarLabel(it.text.trim(), ScreenPoint(it.centerX, it.centerY)) }

    private fun findRows(boxes: List<OcrBox>): List<GameRow> {
        val textColumn = boxes
            .filter { it.left in 0.25f..0.45f && it.centerY in 0.2f..0.97f }
            .filterNot { ProgressLineRegex.matches(it.text.trim()) }
            .sortedBy { it.centerY }
        val statuses = boxes
            .filter { it.left >= 0.78f && it.centerY in 0.22f..0.97f }
            .mapNotNull { box -> parseStatus(box.text)?.let { box to it } }
            .sortedBy { it.first.centerY }

        return statuses.mapNotNull { (statusBox, status) ->
            val sameRow = textColumn.filter { abs(it.centerY - statusBox.centerY) <= RowHalfHeight }
            // The name sits above the status' vertical center; a row cut off at the top shows only its description.
            val name = sameRow.firstOrNull { it.centerY < statusBox.centerY } ?: return@mapNotNull null
            val description = sameRow.firstOrNull { it.centerY > name.centerY + 0.005f }
            GameRow(
                name = name.text.trim(),
                description = description?.text?.trim().orEmpty(),
                status = status,
            )
        }
    }

    private fun Char.isCjk(): Boolean = this in '一'..'鿿'

    /** Rows are ~0.136 of the screen height apart; name and description sit within ±0.03 of the status. */
    private const val RowHalfHeight = 0.055f
}
