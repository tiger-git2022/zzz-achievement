package com.local.stardbzzz.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameScreenParserTest {
    @Test
    fun storyTab_allRowsCompleted() {
        val screen = GameScreenParser.parse(loadFixture("story"))

        assertTrue(screen.isAchievementPage)
        assertEquals(GameScreenParser.TabNames, screen.tabs.keys.toList())
        assertEquals(631, screen.completedCount)
        assertEquals(
            listOf("从零开始的绳匠生活", "欢迎新员工！", "『英雄』的名字", "治安官的答案", "叩响命运之门"),
            screen.rows.map { it.name },
        )
        assertEquals(RowStatus.Completed("2024-07-07"), screen.rows.first().status)
        assertTrue(screen.rows.all { it.status is RowStatus.Completed })
        assertEquals(
            listOf("法厄同纪事", "代理人秘闻", "独家视界", "代理人信赖", "际会之时", "绳网热议"),
            screen.sidebar.map { it.text },
        )
    }

    @Test
    fun cityTab_mixesProgressAndDates() {
        val screen = GameScreenParser.parse(loadFixture("city"))

        assertEquals(5, screen.rows.size)
        assertEquals("LV60，恭迎王的回归", screen.rows[0].name)
        assertEquals("绳网等级达到60级", screen.rows[0].description)
        assertEquals(RowStatus.InProgress(57, 60), screen.rows[0].status)
        assertEquals(RowStatus.InProgress(7, 12), screen.rows[2].status)
        assertEquals("少女神明的七日箱庭", screen.rows[4].name)
        assertEquals(RowStatus.Completed("2026-06-26"), screen.rows[4].status)
        assertEquals("罗斯凯利法", screen.sidebar.last().text)
    }

    @Test
    fun tacticsTab_largeProgressCounter() {
        val screen = GameScreenParser.parse(loadFixture("tactics"))

        assertEquals(5, screen.rows.size)
        assertEquals("亿万富豪", screen.rows[0].name)
        assertEquals(RowStatus.InProgress(18_329_724, 100_000_000), screen.rows[0].status)
        assertTrue(screen.rows.none { it.status is RowStatus.Completed })
    }

    @Test
    fun exploreTab() {
        val screen = GameScreenParser.parse(loadFixture("explore"))

        assertEquals(
            listOf("英雄之旅", "来场猫鼠游戏？", "合理利用", "公平竞争制度", "安全距离"),
            screen.rows.map { it.name },
        )
        assertEquals(RowStatus.InProgress(0, 1), screen.rows[0].status)
        assertEquals(RowStatus.Completed("2026-07-11"), screen.rows[1].status)
        assertEquals(listOf("空洞探索指南", "零号密钥", "勘域探境"), screen.sidebar.map { it.text })
    }

    @Test
    fun parseStatus_variants() {
        assertEquals(RowStatus.Completed("2026-07-11"), GameScreenParser.parseStatus("@ 2026年7月11日"))
        assertEquals(RowStatus.Completed(null), GameScreenParser.parseStatus("01/01"))
        assertEquals(RowStatus.InProgress(0, 1), GameScreenParser.parseStatus("00/01"))
        // 18329724/100000000 with a dropped digit must not turn into a completion.
        assertNull(GameScreenParser.parseStatus("18329724/10000000"))
        assertEquals(RowStatus.Completed(null), GameScreenParser.parseStatus("领取"))
        assertNull(GameScreenParser.parseStatus("20"))
        assertNull(GameScreenParser.parseStatus("TITLE"))
    }

    @Test
    fun mergedTabLineAndCounter() {
        val screen = GameScreenParser.parse(
            listOf(
                OcrBox("故事  城市  战术  探索", 0.48f, 0.04f, 0.90f, 0.08f),
                OcrBox("完成数631", 0.10f, 0.03f, 0.14f, 0.06f),
            ),
        )

        assertEquals(GameScreenParser.TabNames, screen.tabs.keys.toList())
        val xs = GameScreenParser.TabNames.map { screen.tabs.getValue(it).x }
        assertEquals(xs.sorted(), xs)
        assertEquals(631, screen.completedCount)
    }

    @Test
    fun rowCutOffAtTop_isSkipped() {
        val screen = GameScreenParser.parse(
            listOf(
                // Only the description and status of the first row are visible.
                OcrBox("完成法厄同纪事「猫的失物」。", 0.29f, 0.24f, 0.40f, 0.26f),
                OcrBox("2026年6月24日", 0.81f, 0.22f, 0.90f, 0.25f),
                OcrBox("治安官的答案", 0.29f, 0.36f, 0.38f, 0.39f),
                OcrBox("2026年6月29日", 0.81f, 0.38f, 0.90f, 0.41f),
                OcrBox("完成法厄同纪事『非常任务』。", 0.29f, 0.40f, 0.42f, 0.42f),
            ),
        )

        assertEquals(listOf("治安官的答案"), screen.rows.map { it.name })
        assertEquals("完成法厄同纪事『非常任务』。", screen.rows.single().description)
    }

    @Test
    fun consecutiveMlKitReadsOfAnUnchangedScreenCountAsTheSame() {
        // Two ML Kit reads of the same, unmoving screen from the first real-phone scan.
        val first = GameScreenParser.parse(loadFixture("mlkit-story-frame1"))
        val second = GameScreenParser.parse(loadFixture("mlkit-story-frame2"))

        assertEquals(5, first.rows.size)
        assertEquals(5, second.rows.size)
        assertEquals(631, second.completedCount)
        val entries = { screen: ParsedScreen -> screen.rows.map { "${it.name} ${it.description}" } }
        assertTrue(AchievementPageScanner.sameContent(entries(first), entries(second)))
        assertTrue(AchievementPageScanner.sameContent(first.sidebar.map { it.text }, second.sidebar.map { it.text }))
    }

    @Test
    fun tabReadWithOneWrongCharacterStillCounts() {
        // ML Kit at full resolution read 探索 as 採索 on every frame of the second phone run.
        val boxes = loadFixture("mlkit-story-frame2").map { if (it.text == "探索") it.copy(text = "採索") else it }

        val screen = GameScreenParser.parse(boxes)

        assertEquals(GameScreenParser.TabNames.toSet(), screen.tabs.keys)
        assertEquals(0.88f, screen.tabs.getValue("探索").x, 0.01f)
    }

    @Test
    fun oneNearMatchCannotStandForTwoTabs() {
        // 故市 is one character off both 故事 and 城市; it may fill only one of them.
        val boxes = loadFixture("mlkit-story-frame2")
            .filterNot { it.text == "城市" }
            .map { if (it.text == "故事") it.copy(text = "故市") else it }

        val screen = GameScreenParser.parse(boxes)

        assertEquals(3, screen.tabs.size)
    }

    @Test
    fun nearMatchInsideAMergedLineIsIgnored() {
        // One wide box spanning two tabs, with only a near match: its midpoint is no tab's position.
        val boxes = loadFixture("mlkit-story-frame2")
            .filterNot { it.text == "故事" || it.text == "城市" } +
            OcrBox("故市 x", 0.4875f, 0.0468f, 0.6400f, 0.0743f)

        val screen = GameScreenParser.parse(boxes)

        assertEquals(setOf("战术", "探索"), screen.tabs.keys)
    }

    private fun loadFixture(name: String): List<OcrBox> {
        val stream = javaClass.classLoader!!.getResourceAsStream("ocr/$name.tsv")
        return stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('\t', limit = 5)
                OcrBox(parts[4], parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
            }
    }
}
