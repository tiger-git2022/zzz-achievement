package com.local.stardbzzz.scan

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementPageScannerTest {
    @Test
    fun visitsEveryCategoryAndRowOnce() = runBlocking {
        val game = FakeGame(
            mapOf(
                "故事" to (1..8).map { c -> "故事分类$c" to rows("故事$c", 12) },
                "城市" to listOf("城市分类" to rows("城市", 3)),
                "战术" to listOf("战术分类" to rows("战术", 5)),
                "探索" to listOf("探索分类" to rows("探索", 0)),
            ),
        )
        val scanner = AchievementPageScanner(game, game, ScanTimings(0, 0, 0, 0))

        val capture = scanner.scan()

        val expected = game.tabs.flatMap { (tab, categories) ->
            categories.flatMap { (category, rows) -> rows.map { Triple(tab, category, it) } }
        }.toSet()
        val captured = capture.rows.map { Triple(it.tab, it.category, it.name) }.toSet()
        assertEquals(expected, captured)
        assertEquals(42, capture.gameCompletedCount)
        assertTrue(capture.rows.all { it.status == RowStatus.Completed("2026-01-01") })
    }

    @Test
    fun returnsToTopWhenGameKeepsScrollPosition() = runBlocking {
        // Every list and sidebar starts scrolled to the bottom, and a fling only moves three entries up.
        val game = FakeGame(
            GameScreenParser.TabNames.associateWith { tab -> (1..9).map { c -> "${tab}分类$c" to rows("$tab$c", 8) } },
            startAtBottom = true,
        )

        val capture = AchievementPageScanner(game, game, ScanTimings(0, 0, 0, 0)).scan()

        assertEquals(36, capture.rows.map { it.category }.toSet().size)
        assertEquals(4 * 9 * 8, capture.rows.map { it.name }.toSet().size)
    }

    @Test
    fun oneEmptySidebarReadDoesNotEndTheTab() = runBlocking {
        val game = FakeGame(
            mapOf(
                "故事" to (1..9).map { c -> "故事分类$c" to rows("故事$c", 2) },
                "城市" to listOf("城市分类" to rows("城市", 2)),
                "战术" to listOf("战术分类" to rows("战术", 2)),
                "探索" to listOf("探索分类" to rows("探索", 2)),
            ),
            dropSidebarAfterSidebarSwipe = true,
        )

        val capture = AchievementPageScanner(game, game, ScanTimings(0, 0, 0, 0)).scan()

        assertEquals(12, capture.rows.map { it.category }.toSet().size)
    }

    @Test
    fun ocrJitterBetweenFramesStillEndsEachList() = runBlocking {
        // Real ML Kit reads of an unchanged frame differ by a character (叩响 → D响), so exact comparison
        // never saw the list settle and rewound the first category until the page cap.
        val game = FakeGame(
            GameScreenParser.TabNames.associateWith { tab -> (1..3).map { c -> "${tab}分类$c" to rows("$tab$c", 8) } },
            startAtBottom = true,
            jitterRows = true,
        )

        val capture = AchievementPageScanner(game, game, ScanTimings(0, 0, 0, 0)).scan()

        val expected = game.tabs.values.flatten().flatMap { it.second }.toSet()
        assertEquals(expected, capture.rows.map { game.unjitter(it.name) }.toSet())
        // 12 categories of 8 rows need a handful of swipes each, nowhere near the 120-page cap.
        assertTrue("swipes: ${game.swipes}", game.swipes < 12 * 12)
    }

    @Test
    fun misreadSidebarLabelIsNotVisitedTwice() = runBlocking {
        // 代理人秘闻 was read as 代理人秘圓 on some frames; that must not count as a new category.
        val game = FakeGame(
            GameScreenParser.TabNames.associateWith { tab ->
                listOf("法厄同纪事", "代理人秘闻", "独家视界", "代理人信赖", "际会之时", "绳网热议", "空洞调查", "新艾利都")
                    .map { "$tab$it" to rows("$tab$it", 4) }
            },
            jitterSidebar = true,
        )

        val capture = AchievementPageScanner(game, game, ScanTimings(0, 0, 0, 0)).scan()

        assertEquals(4 * 8, game.categoryTaps)
        assertEquals(4 * 8 * 4, capture.rows.map { it.name }.toSet().size)
    }

    @Test
    fun keepsTheFirstCleanCompletedCount() = runBlocking {
        // The first frame can have the counter covered (the overlay was still laid out for portrait).
        val game = FakeGame(GameScreenParser.TabNames.associateWith { listOf("${it}分类" to rows(it, 3)) })
        var reads = 0
        val coveredFirst = ScreenReader {
            game.read().let { boxes -> if (reads++ == 0) boxes.filterNot { it.text == "完成数" } else boxes }
        }

        val capture = AchievementPageScanner(coveredFirst, game, ScanTimings(0, 0, 0, 0)).scan()

        assertEquals(42, capture.gameCompletedCount)
    }

    @Test
    fun sameContentToleratesMisreadsButNotScrolling() {
        val page = listOf("叩响命运之门", "「英雄」的名字", "从零开始的绳匠生活", "欢迎新员工!", "治安官的答案")
        assertTrue(AchievementPageScanner.sameContent(page, listOf("D响命运之门", "『英雄｣的名字", "从雪开始的绳匠生活", "欢迎新员工!", "治安官的答案")))
        assertTrue(AchievementPageScanner.sameContent(listOf("从零"), listOf("从雪")))
        // One unreadable or missing entry on a full page is forgiven; two are not.
        assertTrue(AchievementPageScanner.sameContent(page, page.toMutableList().also { it[2] = "xxxx" }))
        assertFalse(AchievementPageScanner.sameContent(page, page.toMutableList().also { it[2] = "xxxx"; it[3] = "yyyy" }))
        assertTrue(AchievementPageScanner.sameContent(page, page - page[2]))
        assertTrue(AchievementPageScanner.sameContent(page - page[0], page))
        assertFalse(AchievementPageScanner.sameContent(page, page.drop(2)))
        // A one-row scroll shifts every position.
        assertFalse(AchievementPageScanner.sameContent(page, page.drop(1) + "新的成就名称"))
        assertTrue(AchievementPageScanner.sameContent(emptyList(), emptyList()))
    }

    @Test
    fun tiersOfOneAchievementAreNotTakenForEachOther() {
        // Same name, descriptions differing only in the number: a scrolled list must not read as unchanged.
        val tiers = listOf(10, 20, 50, 100, 200, 500, 1000, 2000).map { "空洞探索者 累计完成${it}次委托" }
        assertFalse(AchievementPageScanner.sameContent(tiers.take(5), tiers.drop(3).take(5)))
        assertFalse(AchievementPageScanner.sameContent(tiers.take(5), tiers.drop(1).take(5)))
        assertTrue(AchievementPageScanner.sameContent(tiers.take(5), tiers.take(5).map { it.replace("空洞", "空同") }))
    }

    @Test(expected = MissingTabsException::class)
    fun failsInsteadOfSkippingAnUnreadableTab() {
        runBlocking {
            val game = FakeGame(GameScreenParser.TabNames.associateWith { listOf("${it}分类" to rows(it, 3)) })
            val withoutExplore = ScreenReader { game.read().filterNot { it.text == "探索" } }
            AchievementPageScanner(withoutExplore, game, ScanTimings(0, 0, 0, 0)).scan()
        }
    }

    @Test(expected = NotOnAchievementPageException::class)
    fun failsWhenNotOnAchievementPage() {
        runBlocking {
            AchievementPageScanner({ emptyList() }, FakeGame(emptyMap()), ScanTimings(0, 0, 0, 0)).scan()
        }
    }

    private fun rows(prefix: String, count: Int) = (1..count).map { "${prefix}成就$it" }

    /** A minimal model of the achievement page: 6 visible sidebar labels, 5 visible rows, 3-row scroll steps. */
    private class FakeGame(
        val tabs: Map<String, List<Pair<String, List<String>>>>,
        startAtBottom: Boolean = false,
        /** Simulates one OCR read that misses the sidebar right after it first scrolls. */
        private val dropSidebarAfterSidebarSwipe: Boolean = false,
        /** Every other read garbles one character of each row name, like real OCR of an unchanged frame. */
        private val jitterRows: Boolean = false,
        /** Every other read garbles the last character of each sidebar label. */
        private val jitterSidebar: Boolean = false,
    ) : ScreenReader, GestureInput {
        var swipes = 0
            private set
        var categoryTaps = 0
            private set
        private var reads = 0

        fun unjitter(name: String) = name.replace(JitterChar, "成")

        private fun jitter(name: String) = if (jitterRows && reads % 2 == 0) name.replace("成", JitterChar) else name

        private var hideSidebarOnNextRead = false
        private var droppedSidebar = false
        private var tab = tabs.keys.firstOrNull()
        private var category = 0

        // The game remembers each tab's sidebar position.
        private val sidebarOffsets = tabs.mapValues { (_, categories) ->
            if (startAtBottom) (categories.size - VisibleLabels).coerceAtLeast(0) else 0
        }.toMutableMap<String?, Int>()
        private var sidebarOffset: Int
            get() = sidebarOffsets[tab] ?: 0
            set(value) {
                sidebarOffsets[tab] = value
            }

        // The game remembers each category's scroll position.
        private val listOffsets = tabs.flatMap { (tab, categories) ->
            categories.mapIndexed { index, (_, rows) ->
                (tab to index) to if (startAtBottom) (rows.size - VisibleRows).coerceAtLeast(0) else 0
            }
        }.toMap<Pair<String?, Int>, Int>().toMutableMap()
        private var listOffset: Int
            get() = listOffsets[tab to category] ?: 0
            set(value) {
                listOffsets[tab to category] = value
            }

        private val categories get() = tabs[tab].orEmpty()
        private val rowsInCategory get() = categories.getOrNull(category)?.second.orEmpty()

        override suspend fun read(): List<OcrBox> {
            reads++
            val boxes = mutableListOf<OcrBox>()
            boxes += OcrBox("完成数", 0.10f, 0.03f, 0.14f, 0.06f)
            boxes += OcrBox("42", 0.10f, 0.064f, 0.13f, 0.094f)
            GameScreenParser.TabNames.forEachIndexed { index, name ->
                val x = 0.50f + index * 0.125f
                boxes += OcrBox(name, x, 0.04f, x + 0.03f, 0.08f)
            }
            val showSidebar = !hideSidebarOnNextRead
            hideSidebarOnNextRead = false
            if (showSidebar) categories.drop(sidebarOffset).take(VisibleLabels).forEachIndexed { index, (name, _) ->
                val y = 0.35f + index * 0.0845f
                val label = if (jitterSidebar && reads % 2 == 0) name.dropLast(1) + "圓" else name
                boxes += OcrBox(label, 0.11f, y - 0.015f, 0.18f, y + 0.015f)
            }
            rowsInCategory.drop(listOffset).take(VisibleRows).forEachIndexed { index, name ->
                val y = 0.30f + index * 0.136f
                boxes += OcrBox(jitter(name), 0.29f, y - 0.032f, 0.40f, y - 0.005f)
                boxes += OcrBox("描述$name", 0.29f, y + 0.003f, 0.45f, y + 0.028f)
                boxes += OcrBox("2026年1月1日", 0.81f, y - 0.016f, 0.90f, y + 0.016f)
            }
            return boxes
        }

        override suspend fun tap(point: ScreenPoint) {
            if (point.y < 0.12f) {
                tab = GameScreenParser.TabNames[((point.x - 0.50f) / 0.125f).toInt()]
                category = 0
            } else if (point.x < 0.25f) {
                categoryTaps++
                category = sidebarOffset + ((point.y - 0.35f + 0.04f) / 0.0845f).toInt()
            }
        }

        override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMillis: Long) {
            swipes++
            val forward = from.y > to.y
            if (from.x < 0.25f) {
                val max = (categories.size - VisibleLabels).coerceAtLeast(0)
                sidebarOffset = (sidebarOffset + if (forward) 3 else -3).coerceIn(0, max)
                if (forward && dropSidebarAfterSidebarSwipe && !droppedSidebar) {
                    hideSidebarOnNextRead = true
                    droppedSidebar = true
                }
            } else {
                val max = (rowsInCategory.size - VisibleRows).coerceAtLeast(0)
                listOffset = (listOffset + if (forward) 3 else -3).coerceIn(0, max)
            }
        }

        private companion object {
            const val VisibleLabels = 6
            const val VisibleRows = 5
            const val JitterChar = "戍"
        }
    }
}
