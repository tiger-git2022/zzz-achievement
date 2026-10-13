package com.local.stardbzzz.scan

import kotlinx.coroutines.delay

/** Captures the current screen and returns its OCR lines (normalized coordinates). */
fun interface ScreenReader {
    suspend fun read(): List<OcrBox>
}

/** Injects touches at normalized screen positions. */
interface GestureInput {
    suspend fun tap(point: ScreenPoint)
    suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMillis: Long)
}

data class ScanTimings(
    val afterTapMillis: Long = 1_200,
    val afterSwipeMillis: Long = 1_000,
    val swipeMillis: Long = 700,
    val flingMillis: Long = 150,
)

data class ScanProgress(
    val tab: String?,
    val category: String?,
    val pages: Int,
    val rows: Int,
)

data class ScanCapture(
    val rows: List<ObservedRow>,
    val gameCompletedCount: Int?,
)

class NotOnAchievementPageException : IllegalStateException("没有识别到游戏的成就页，请先打开成就页面再开始扫描")

class MissingTabsException(tabs: Collection<String>) :
    IllegalStateException("没有识别到页签：${tabs.joinToString("、")}，请确认成就页完整显示后重试")

/**
 * Walks every tab → sidebar category → list page of the in-game achievement screen and
 * collects the rows it reads. Scrolling stops when a page reads the same as the previous one
 * (allowing for OCR misreading a character or two between frames).
 */
class AchievementPageScanner(
    private val reader: ScreenReader,
    private val input: GestureInput,
    private val timings: ScanTimings = ScanTimings(),
    private val onProgress: (ScanProgress) -> Unit = {},
) {
    private val rows = mutableListOf<ObservedRow>()
    private val seenNames = mutableSetOf<String>()
    private var pages = 0
    private var currentTab: String? = null
    private var currentCategory: String? = null

    suspend fun scan(): ScanCapture {
        val first = readPage()
        val tabs = findAllTabs(first)
        for (tab in GameScreenParser.TabNames) {
            val position = tabs.getValue(tab)
            currentTab = tab
            currentCategory = null
            report()
            input.tap(position)
            delay(timings.afterTapMillis)
            scanTab(tab)
        }
        return ScanCapture(rows.toList(), first.completedCount)
    }

    /** A page counts as the achievement page with 3 of 4 tabs; re-read so a single OCR miss can't drop a whole tab. */
    private suspend fun findAllTabs(first: ParsedScreen): Map<String, ScreenPoint> {
        val tabs = first.tabs.toMutableMap()
        repeat(PageReadAttempts) {
            if (tabs.size == GameScreenParser.TabNames.size) return tabs
            delay(timings.afterSwipeMillis)
            readPage().tabs.forEach { (name, position) -> tabs.putIfAbsent(name, position) }
        }
        val missing = GameScreenParser.TabNames.filterNot { it in tabs }
        if (missing.isNotEmpty()) throw MissingTabsException(missing)
        return tabs
    }

    private suspend fun scanTab(tab: String) {
        // The sidebar may keep an earlier scroll position too; categories above it would never be seen.
        scrollUntilStable(SidebarSwipeTo, SidebarSwipeFrom, timings.flingMillis, { page -> page.sidebar.map { it.text } }) {}

        val visited = mutableListOf<String>()
        var sidebarScrolls = 0
        var readsWithoutNewCategory = 0
        while (visited.size < MaxCategoriesPerTab) {
            val page = readPage()
            val next = page.sidebar.firstOrNull { label -> visited.none { isSameLabel(it, label.text) } }
            if (next == null) {
                // Every visible category is done: scroll the sidebar to reveal the rest. Only give up after
                // several reads in a row show nothing new, so one incomplete OCR read can't end the tab early.
                if (++readsWithoutNewCategory > StableReads || sidebarScrolls >= MaxSidebarScrolls) return
                sidebarScrolls++
                input.swipe(SidebarSwipeFrom, SidebarSwipeTo, timings.swipeMillis)
                delay(timings.afterSwipeMillis)
                continue
            }
            readsWithoutNewCategory = 0
            visited += next.text
            currentCategory = next.text
            report()
            input.tap(next.center)
            delay(timings.afterTapMillis)
            scanCategory(tab, next.text)
        }
    }

    private suspend fun scanCategory(tab: String, category: String) {
        // The game may keep an earlier scroll position, so fling back up until the list stops moving.
        scrollUntilStable(ListSwipeTo, ListSwipeFrom, timings.flingMillis, ::rowNames) {}

        scrollUntilStable(ListSwipeFrom, ListSwipeTo, timings.swipeMillis, ::rowNames) { page ->
            page.rows.forEach { row ->
                rows += ObservedRow(tab, category, row.name, row.description, row.status)
                seenNames += AchievementMatcher.normalizeText(row.name)
            }
            report()
        }
    }

    private fun rowNames(page: ParsedScreen): List<String> = page.rows.map { it.name }

    /**
     * Reads a page, swipes, and repeats until [contentOf] reads the same [StableReads] times in a row.
     * Requiring more than one unchanged read (each after another swipe) keeps a slow animation or a
     * frame that hasn't arrived yet from being mistaken for the end of the list.
     */
    private suspend fun scrollUntilStable(
        from: ScreenPoint,
        to: ScreenPoint,
        swipeMillis: Long,
        contentOf: (ParsedScreen) -> List<String>,
        onPage: (ParsedScreen) -> Unit,
    ) {
        var previousNames: List<String>? = null
        var unchangedReads = 0
        repeat(MaxPagesPerCategory) {
            val page = readPage()
            onPage(page)
            val names = contentOf(page)
            if (previousNames?.let { sameContent(it, names) } == true) {
                if (++unchangedReads >= StableReads) return
            } else {
                unchangedReads = 0
            }
            previousNames = names
            input.swipe(from, to, swipeMillis)
            delay(timings.afterSwipeMillis)
        }
    }

    /** Reads the screen, retrying briefly through transition animations before giving up. */
    private suspend fun readPage(): ParsedScreen {
        repeat(PageReadAttempts) { attempt ->
            val page = GameScreenParser.parse(reader.read())
            pages++
            if (page.isAchievementPage) return page
            if (attempt < PageReadAttempts - 1) delay(timings.afterSwipeMillis)
        }
        throw NotOnAchievementPageException()
    }

    private fun report() {
        onProgress(ScanProgress(currentTab, currentCategory, pages, seenNames.size))
    }

    /** Labels like 代理人秘闻 / 代理人信赖 differ by few characters, so only exact or partial (clipped) reads count. */
    private fun isSameLabel(a: String, b: String): Boolean {
        val x = AchievementMatcher.normalizeText(a)
        val y = AchievementMatcher.normalizeText(b)
        return x == y || (minOf(x.length, y.length) >= 2 && (x.contains(y) || y.contains(x)))
    }

    companion object {
        private const val MaxCategoriesPerTab = 30
        private const val MaxSidebarScrolls = 12
        private const val MaxPagesPerCategory = 120
        private const val PageReadAttempts = 3
        private const val StableReads = 2
        private const val SameNameSimilarity = 0.7

        /**
         * Whether two reads of the same screen region show the same entries. OCR of an unchanged frame still
         * varies (从零 → 从雪, 叩响 → D响, mangled brackets), so entries are compared position by position
         * with some slack, and one unreadable entry is forgiven on a full page. Lists can hold near-identical
         * names (a numbered series), so the reads also have to line up better as they are than shifted by
         * any number of rows; otherwise the list moved.
         */
        fun sameContent(previous: List<String>, current: List<String>): Boolean {
            val a = previous.map(AchievementMatcher::normalizeText)
            val b = current.map(AchievementMatcher::normalizeText)
            if (a.size != b.size) return false
            if (a.isEmpty()) return true
            val mismatches = a.indices.count { !similarEntry(a[it], b[it]) }
            if (mismatches > if (a.size >= 4) 1 else 0) return false
            val inPlace = alignment(a, b, 0)
            return (1 until a.size).all { shift -> alignment(a, b, shift) < inPlace && alignment(a, b, -shift) < inPlace }
        }

        private fun similarEntry(a: String, b: String): Boolean =
            a == b || AchievementMatcher.levenshtein(a, b) <= 1 ||
                AchievementMatcher.similarity(a, b) >= SameNameSimilarity

        /** Mean similarity of [a] against [b] moved by [shift] rows, over the rows both still show. */
        private fun alignment(a: List<String>, b: List<String>, shift: Int): Double {
            val pairs = a.indices.mapNotNull { i -> b.getOrNull(i - shift)?.let { a[i] to it } }
            return pairs.sumOf { (x, y) -> AchievementMatcher.similarity(x, y) } / pairs.size
        }

        // A ~0.40 screen-height drag moves the list about three rows (rows are ~0.136 apart, five are visible),
        // so consecutive pages overlap and no row is skipped.
        val ListSwipeFrom = ScreenPoint(0.55f, 0.78f)
        val ListSwipeTo = ScreenPoint(0.55f, 0.38f)
        val SidebarSwipeFrom = ScreenPoint(0.145f, 0.76f)
        val SidebarSwipeTo = ScreenPoint(0.145f, 0.40f)
    }
}
