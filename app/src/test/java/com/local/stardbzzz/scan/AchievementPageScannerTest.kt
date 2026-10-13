package com.local.stardbzzz.scan

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test(expected = NotOnAchievementPageException::class)
    fun failsWhenNotOnAchievementPage() {
        runBlocking {
            AchievementPageScanner({ emptyList() }, FakeGame(emptyMap()), ScanTimings(0, 0, 0, 0)).scan()
        }
    }

    private fun rows(prefix: String, count: Int) = (1..count).map { "${prefix}成就$it" }

    /** A minimal model of the achievement page: 6 visible sidebar labels, 5 visible rows, 3-row scroll steps. */
    private class FakeGame(val tabs: Map<String, List<Pair<String, List<String>>>>) : ScreenReader, GestureInput {
        private var tab = tabs.keys.firstOrNull()
        private var category = 0
        private var sidebarOffset = 0
        private var listOffset = 0

        private val categories get() = tabs[tab].orEmpty()
        private val rowsInCategory get() = categories.getOrNull(category)?.second.orEmpty()

        override suspend fun read(): List<OcrBox> {
            val boxes = mutableListOf<OcrBox>()
            boxes += OcrBox("完成数", 0.10f, 0.03f, 0.14f, 0.06f)
            boxes += OcrBox("42", 0.10f, 0.064f, 0.13f, 0.094f)
            GameScreenParser.TabNames.forEachIndexed { index, name ->
                val x = 0.50f + index * 0.125f
                boxes += OcrBox(name, x, 0.04f, x + 0.03f, 0.08f)
            }
            categories.drop(sidebarOffset).take(VisibleLabels).forEachIndexed { index, (name, _) ->
                val y = 0.35f + index * 0.0845f
                boxes += OcrBox(name, 0.11f, y - 0.015f, 0.18f, y + 0.015f)
            }
            rowsInCategory.drop(listOffset).take(VisibleRows).forEachIndexed { index, name ->
                val y = 0.30f + index * 0.136f
                boxes += OcrBox(name, 0.29f, y - 0.032f, 0.40f, y - 0.005f)
                boxes += OcrBox("描述$name", 0.29f, y + 0.003f, 0.45f, y + 0.028f)
                boxes += OcrBox("2026年1月1日", 0.81f, y - 0.016f, 0.90f, y + 0.016f)
            }
            return boxes
        }

        override suspend fun tap(point: ScreenPoint) {
            if (point.y < 0.12f) {
                tab = GameScreenParser.TabNames[((point.x - 0.50f) / 0.125f).toInt()]
                category = 0
                sidebarOffset = 0
                listOffset = 0
            } else if (point.x < 0.25f) {
                category = sidebarOffset + ((point.y - 0.35f + 0.04f) / 0.0845f).toInt()
                listOffset = 0
            }
        }

        override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMillis: Long) {
            val forward = from.y > to.y
            if (from.x < 0.25f) {
                val max = (categories.size - VisibleLabels).coerceAtLeast(0)
                sidebarOffset = if (forward) (sidebarOffset + 3).coerceAtMost(max) else 0
            } else {
                val max = (rowsInCategory.size - VisibleRows).coerceAtLeast(0)
                listOffset = if (forward) (listOffset + 3).coerceAtMost(max) else 0
            }
        }

        private companion object {
            const val VisibleLabels = 6
            const val VisibleRows = 5
        }
    }
}
