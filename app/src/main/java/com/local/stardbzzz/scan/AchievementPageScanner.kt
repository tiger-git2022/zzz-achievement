package com.local.stardbzzz.scan

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Injects touches at normalized screen positions. */
interface GestureInput {
    suspend fun tap(point: ScreenPoint)
    suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMillis: Long)
}

data class ScanTimings(
    val afterTapMillis: Long = 1_200,
    /** After a sidebar swipe, and between retries of a page that didn't read as the achievement page. */
    val afterSwipeMillis: Long = 1_000,
    val swipeMillis: Long = 700,
    val flingMillis: Long = 150,
    /** After a list swipe or a category tap: wait this long, then until two captures this far apart match. */
    val settleMinMillis: Long = 250,
    val settlePollMillis: Long = 120,
    val settleMaxMillis: Long = 2_000,
) {
    companion object {
        /** For tests: no waiting at all. */
        val None = ScanTimings(0, 0, 0, 0, 0, 0, 0)
    }
}

data class ScanProgress(
    val tab: String?,
    val category: String?,
    val pages: Int,
    val rows: Int,
    /** Whether the scan is on the downward pass through a category, queuing its pages for OCR. */
    val collecting: Boolean = false,
    /** List pages queued for OCR, and how many of them have been read. */
    val queued: Int = 0,
    val read: Int = 0,
    /** False once every page is captured: the game is no longer touched and only OCR is left. */
    val capturing: Boolean = true,
)

data class ScanCapture(
    val rows: List<ObservedRow>,
    val gameCompletedCount: Int?,
)

class NotOnAchievementPageException : IllegalStateException("没有识别到游戏的成就页，请先打开成就页面再开始扫描")

class MissingTabsException(tabs: Collection<String>) :
    IllegalStateException("没有识别到页签：${tabs.joinToString("、")}，请确认成就页完整显示后重试")

/**
 * Walks every tab → sidebar category → list page of the in-game achievement screen and collects the rows on it.
 *
 * Only what steers the walk is read on the way: the whole first page (tabs and 完成数) and the sidebar. Whether a
 * list moved is told from a thumbnail of it, and each new list page is queued and read by OCR alongside the walk,
 * so the game is released as soon as the last page is captured instead of after the last page is read.
 */
class AchievementPageScanner(
    private val source: ScreenSource,
    private val input: GestureInput,
    private val timings: ScanTimings = ScanTimings(),
    private val onProgress: (ScanProgress) -> Unit = {},
) {
    private class QueuedPage(val frame: KeptFrame, val tab: String, val category: String)

    /** A capture with the thumbnails the walk compares. */
    private class Shot(val frame: ScreenFrame, val list: Thumbnail, val tabBar: Thumbnail)

    // The walk and the OCR of queued pages run concurrently; everything they share is guarded by this.
    private val lock = Any()
    private val rows = mutableListOf<ObservedRow>()
    private val seenNames = mutableSetOf<String>()
    private var queued = 0
    private var read = 0
    private var capturing = true

    private var pages = 0
    private var currentTab: String? = null
    private var currentCategory: String? = null
    private var gameCompletedCount: Int? = null
    private var collecting = false

    /** The tab bar right after switching tabs; a very different one means the game left the achievement page. */
    private var tabBar: Thumbnail? = null

    suspend fun scan(): ScanCapture = coroutineScope {
        val queue = Channel<QueuedPage>(Channel.UNLIMITED)
        val reader = launch {
            for (page in queue) {
                val parsed = GameScreenParser.parse(page.frame.read())
                synchronized(lock) {
                    parsed.rows.forEach { row ->
                        rows += ObservedRow(page.tab, page.category, row.name, row.description, row.status)
                        seenNames += AchievementMatcher.normalizeText(row.name)
                    }
                    read++
                }
                report()
            }
        }
        try {
            walk(queue)
        } finally {
            queue.close()
        }
        synchronized(lock) { capturing = false }
        report()
        reader.join()
        ScanCapture(synchronized(lock) { rows.toList() }, gameCompletedCount)
    }

    private suspend fun walk(queue: SendChannel<QueuedPage>) {
        val tabs = findAllTabs(readWholePage())
        for (tab in GameScreenParser.TabNames) {
            currentTab = tab
            currentCategory = null
            report()
            input.tap(tabs.getValue(tab))
            delay(timings.afterTapMillis)
            // The highlighted tab moved, so the tab bar looks different: shoot() confirms the page with a full read
            // and takes this tab bar as the new reference.
            val shot = shoot()
            tabBar = shot.tabBar
            // The first page can have the counter covered (the overlay still laid out for portrait).
            if (gameCompletedCount == null) gameCompletedCount = GameScreenParser.parse(shot.frame.read(CounterRegion)).completedCount
            scanTab(tab, queue)
        }
    }

    /** A page counts as the achievement page with 3 of 4 tabs; re-read so a single OCR miss can't drop a whole tab. */
    private suspend fun findAllTabs(first: ParsedScreen): Map<String, ScreenPoint> {
        val tabs = first.tabs.toMutableMap()
        repeat(PageReadAttempts) {
            if (tabs.size == GameScreenParser.TabNames.size) return tabs
            delay(timings.afterSwipeMillis)
            readWholePage().tabs.forEach { (name, position) -> tabs.putIfAbsent(name, position) }
        }
        val missing = GameScreenParser.TabNames.filterNot { it in tabs }
        if (missing.isNotEmpty()) throw MissingTabsException(missing)
        return tabs
    }

    private suspend fun scanTab(tab: String, queue: SendChannel<QueuedPage>) {
        rewindSidebar()

        val visited = mutableListOf<String>()
        var sidebarScrolls = 0
        var readsWithoutNewCategory = 0
        while (visited.size < MaxCategoriesPerTab) {
            val next = readSidebar().firstOrNull { label -> visited.none { isSameLabel(it, label.text) } }
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
            scanCategory(tab, next.text, queue)
        }
    }

    /** The sidebar may keep an earlier scroll position too; categories above it would never be seen. */
    private suspend fun rewindSidebar() {
        var previous: List<String>? = null
        var unchanged = 0
        for (step in 0 until MaxPagesPerCategory) {
            val labels = readSidebar().map { it.text }
            if (previous != null && sameContent(previous, labels)) {
                if (++unchanged >= StableReads) return
            } else {
                unchanged = 0
            }
            previous = labels
            input.swipe(SidebarSwipeTo, SidebarSwipeFrom, timings.flingMillis)
            delay(timings.afterSwipeMillis)
        }
    }

    private suspend fun scanCategory(tab: String, category: String, queue: SendChannel<QueuedPage>) {
        // The game may keep an earlier scroll position, so fling back up until the list stops moving.
        // Requiring it to stay put twice keeps one fling the game dropped from passing for the top.
        var page = settle()
        var unchanged = 0
        for (step in 0 until MaxPagesPerCategory) {
            input.swipe(ListSwipeTo, ListSwipeFrom, timings.flingMillis)
            val next = settle()
            unchanged = if (unchangedList(page, next)) unchanged + 1 else 0
            page = next
            if (unchanged >= StableReads) break
        }

        collecting = true
        report()
        enqueue(page, tab, category, queue)
        unchanged = 0
        for (step in 1 until MaxPagesPerCategory) {
            input.swipe(ListSwipeFrom, ListSwipeTo, timings.swipeMillis)
            val next = settle()
            // A page that didn't move holds nothing new; requiring it twice keeps one swipe the game dropped
            // from ending the list early.
            if (unchangedList(page, next)) {
                if (++unchanged >= StableReads) break
                continue
            }
            unchanged = 0
            page = next
            enqueue(page, tab, category, queue)
        }
        collecting = false
        report()
    }

    private suspend fun enqueue(shot: Shot, tab: String, category: String, queue: SendChannel<QueuedPage>) {
        val kept = shot.frame.keep(RowsRegion)
        synchronized(lock) { queued++ }
        queue.send(QueuedPage(kept, tab, category))
        report()
    }

    private fun unchangedList(a: Shot, b: Shot): Boolean = a.list.difference(b.list) < ListUnchanged

    /** Waits for the screen to stop moving (a fling, a list swapped in) and returns its capture. */
    private suspend fun settle(): Shot {
        delay(timings.settleMinMillis)
        var previous = shoot()
        val polls = if (timings.settlePollMillis > 0) (timings.settleMaxMillis - timings.settleMinMillis) / timings.settlePollMillis else 0
        repeat(polls.toInt()) {
            delay(timings.settlePollMillis)
            val current = shoot()
            if (unchangedList(previous, current)) return current
            previous = current
        }
        return previous
    }

    /** Captures the screen, making sure the game still shows the achievement page. */
    private suspend fun shoot(): Shot {
        repeat(PageReadAttempts) { attempt ->
            val shot = capture()
            val reference = tabBar
            if (reference == null || reference.difference(shot.tabBar) < TabBarChanged) return shot
            // The tab bar changed a lot: only a full read can tell whether the game left the page.
            if (GameScreenParser.parse(shot.frame.read()).isAchievementPage) {
                tabBar = shot.tabBar
                return shot
            }
            if (attempt < PageReadAttempts - 1) delay(timings.afterSwipeMillis)
        }
        throw NotOnAchievementPageException()
    }

    private suspend fun capture(): Shot {
        val frame = source.capture()
        pages++
        return Shot(frame, frame.thumbnail(ListRegion), frame.thumbnail(TabBarRegion))
    }

    /** The sidebar's labels, read from the sidebar alone. */
    private suspend fun readSidebar(): List<SidebarLabel> {
        val page = GameScreenParser.parse(shoot().frame.read(SidebarRegion))
        report()
        return page.sidebar
    }

    /** Reads a whole page, retrying briefly through transition animations before giving up. */
    private suspend fun readWholePage(): ParsedScreen {
        repeat(PageReadAttempts) { attempt ->
            val page = GameScreenParser.parse(capture().frame.read())
            if (page.isAchievementPage) {
                // Any one frame can have the counter misread or briefly covered; keep the first clean read.
                if (gameCompletedCount == null) gameCompletedCount = page.completedCount
                return page
            }
            if (attempt < PageReadAttempts - 1) delay(timings.afterSwipeMillis)
        }
        throw NotOnAchievementPageException()
    }

    private fun report() {
        val progress = synchronized(lock) {
            ScanProgress(currentTab, currentCategory, pages, seenNames.size, collecting, queued, read, capturing)
        }
        onProgress(progress)
    }

    /**
     * Labels like 代理人秘闻 / 代理人信赖 differ by two characters, so beyond exact or partial (clipped) reads
     * only a single misread character counts (代理人秘圓), and never one that changes a number.
     */
    private fun isSameLabel(a: String, b: String): Boolean {
        val x = AchievementMatcher.normalizeText(a)
        val y = AchievementMatcher.normalizeText(b)
        if (x == y || (minOf(x.length, y.length) >= 2 && (x.contains(y) || y.contains(x)))) return true
        return minOf(x.length, y.length) >= 3 && AchievementMatcher.levenshtein(x, y) <= 1 &&
            x.filter { it.isDigit() } == y.filter { it.isDigit() }
    }

    companion object {
        private const val MaxCategoriesPerTab = 30
        private const val MaxSidebarScrolls = 12
        private const val MaxPagesPerCategory = 120
        private const val PageReadAttempts = 3
        private const val StableReads = 2
        private const val SameNameSimilarity = 0.7

        /**
         * Mean difference of two list thumbnails (0..255 gray) below which the list didn't move. On the phone's
         * frames an unchanged list differed by 0.25..1.2 (animated medals, JPEG noise) and a swiped one by 3.1..11.
         */
        const val ListUnchanged = 2.0f

        /**
         * The tab bar animates a little (up to 16 between frames of one tab); another tab or another screen
         * differs by ~68.
         */
        const val TabBarChanged = 35f

        /** Where the rows scroll; the medals and status column move with them. */
        val ListRegion = ScreenRegion(0.25f, 0.25f, 0.92f, 0.95f)
        val TabBarRegion = ScreenRegion(0.43f, 0.01f, 0.96f, 0.10f)

        /** The sidebar labels; GameScreenParser takes labels centered in 0.05..0.25 x 0.22..0.86. */
        val SidebarRegion = ScreenRegion(0.04f, 0.20f, 0.27f, 0.88f)

        /** 完成数 and the number under it (~0.10..0.14 x 0.02..0.10). */
        val CounterRegion = ScreenRegion(0.05f, 0f, 0.20f, 0.13f)

        /** Every row's name, description and status (left from 0.25, statuses up to ~0.905). */
        val RowsRegion = ScreenRegion(0.24f, 0.19f, 0.94f, 1f)

        /**
         * Whether two reads of the same screen region show the same entries. OCR of an unchanged frame still
         * varies (从零 → 从雪, 叩响 → D响, mangled brackets) and can drop an entry, so on a full page one
         * entry may be misread or missing. Entries are compared position by position: a scroll of even one row
         * shifts every position, and numbers must match exactly, so tiers of one achievement (same name,
         * 10 vs 50 in the description) are never taken for each other.
         */
        fun sameContent(previous: List<String>, current: List<String>): Boolean {
            val a = previous.map(AchievementMatcher::normalizeText)
            val b = current.map(AchievementMatcher::normalizeText)
            val forgiven = if (maxOf(a.size, b.size) >= 4) 1 else 0
            return when (a.size - b.size) {
                0 -> a.indices.count { !similarEntry(a[it], b[it]) } <= forgiven
                1 -> forgiven == 1 && missingOne(longer = a, shorter = b)
                -1 -> forgiven == 1 && missingOne(longer = b, shorter = a)
                else -> false
            }
        }

        /** [shorter] is [longer] with one entry left out and everything else read alike. */
        private fun missingOne(longer: List<String>, shorter: List<String>): Boolean =
            longer.indices.any { skipped ->
                shorter.indices.all { i -> similarEntry(longer[if (i < skipped) i else i + 1], shorter[i]) }
            }

        private fun similarEntry(a: String, b: String): Boolean =
            a.filter { it.isDigit() } == b.filter { it.isDigit() } &&
                (a == b || AchievementMatcher.levenshtein(a, b) <= 1 || AchievementMatcher.similarity(a, b) >= SameNameSimilarity)

        // A ~0.40 screen-height drag moves the list about three rows (rows are ~0.136 apart, five are visible),
        // so consecutive pages overlap and no row is skipped.
        val ListSwipeFrom = ScreenPoint(0.55f, 0.78f)
        val ListSwipeTo = ScreenPoint(0.55f, 0.38f)
        val SidebarSwipeFrom = ScreenPoint(0.145f, 0.76f)
        val SidebarSwipeTo = ScreenPoint(0.145f, 0.40f)
    }
}
