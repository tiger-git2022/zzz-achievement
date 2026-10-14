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
    fun aNearMatchForTwoMissingTabsIsSkipped() {
        // 故市 is one character off both 故事 and 城市; guessing could send the 故事 tap to 城市.
        val boxes = loadFixture("mlkit-story-frame2")
            .filterNot { it.text == "故事" }
            .map { if (it.text == "城市") it.copy(text = "故市") else it }

        val screen = GameScreenParser.parse(boxes)

        assertEquals(setOf("战术", "探索"), screen.tabs.keys)
    }

    @Test
    fun aSkippedNearMatchIsSettledOnceAnotherTabIs() {
        // 故市 fits 故事 or 城市 at first; once 城区 settles 城市, 故市 can only be 故事.
        val frame = loadFixture("mlkit-story-frame2")
        val story = frame.single { it.text == "故事" }
        val boxes = frame.map {
            when (it.text) {
                "故事" -> it.copy(text = "故市")
                "城市" -> it.copy(text = "城区")
                else -> it
            }
        }

        val screen = GameScreenParser.parse(boxes)

        assertEquals(GameScreenParser.TabNames.toSet(), screen.tabs.keys)
        assertEquals(story.centerX, screen.tabs.getValue("故事").x, 0.001f)
    }

    @Test
    fun aNearMatchForOneMissingTabCounts() {
        // With 城市 read correctly, 故市 can only be 故事.
        val frame = loadFixture("mlkit-story-frame2")
        val story = frame.single { it.text == "故事" }
        val boxes = frame.map { if (it === story) it.copy(text = "故市") else it }

        val screen = GameScreenParser.parse(boxes)

        assertEquals(story.centerX, screen.tabs.getValue("故事").x, 0.001f)
        assertEquals(4, screen.tabs.size)
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

    @Test
    fun skipsTheProgressLineBetweenNameAndDescription() {
        // Sample C from the second phone run (叱咤 read as 比咤).
        val screen = GameScreenParser.parse(
            boxes(
                """
                0.2932 0.6639 0.3677 0.6896 比咤以太之王
                0.2936 0.6979 0.3763 0.7208 当前最多拥有: 91
                0.2907 0.7250 0.5117 0.7542 通关枯萎之都时,拥有的鸣徽不少于50枚。
                0.8176 0.6938 0.8968 0.7250 2026年8月2日
                """,
            ),
        )

        val row = screen.rows.single()
        assertEquals("比咤以太之王", row.name)
        assertEquals("通关枯萎之都时,拥有的鸣徽不少于50枚。", row.description)
        assertEquals(RowStatus.Completed("2026-08-02"), row.status)
    }

    @Test
    fun readsTieredRowsAndAStatusWithALeadingCheckMark() {
        // Samples B and D from the second phone run.
        val screen = GameScreenParser.parse(
            boxes(
                """
                0.2876 0.2604 0.3551 0.2924 断金裂石1
                0.2932 0.2986 0.4940 0.3264 在战斗中,累计触发10次『毁伤」效果。
                0.2929 0.4014 0.3523 0.4278 断金製石=
                0.2917 0.4347 0.4940 0.4625 在战斗中,累计触发50次「毁伤｣效果。
                0.2869 0.5354 0.3595 0.5688 断金裂石=
                0.2910 0.5701 0.5016 0.6007 在战斗中,累计触发200次『毁伤」效果。
                0.8138 0.2833 0.9003 0.3076 2026年9月12日
                0.8138 0.4201 0.9006 0.4444 2026年9月15日
                0.8128 0.5597 0.9012 0.5813 2026年10月12日
                0.2936 0.8160 0.3677 0.8417 只是來串个们
                0.2932 0.8500 0.4366 0.8743 破译[罗新威克的绘本残页。
                0.7936 0.8306 0.9006 0.8618 O2026年10月1日
                """,
            ),
        )

        assertEquals(listOf("断金裂石1", "断金製石=", "断金裂石=", "只是來串个们"), screen.rows.map { it.name })
        assertEquals(
            listOf(listOf("10"), listOf("50"), listOf("200")),
            screen.rows.take(3).map { AchievementMatcher.numbersIn(it.description) },
        )
        assertEquals(RowStatus.Completed("2026-10-01"), screen.rows.last().status)
    }

    @Test
    fun joinsADescriptionSplitIntoTwoBoxes() {
        // PaddleOCR on the phone, frame 206 of the second run: the tier's number ended up in a box of its own,
        // and the row went unmatched. The reward count further right on the same line stays out.
        val screen = GameScreenParser.parse(
            boxes(
                """
                0.2895 0.6715 0.3561 0.7118 以为太难Ⅱ
                0.8141 0.6917 0.8999 0.7271 2026年7月1日
                0.2907 0.7063 0.5849 0.7437 累计50次，使用[以太]属性代理人使敌人陷入属性异常。
                0.7468 0.7118 0.7639 0.7403 10
                0.2904 0.8090 0.3573 0.8472 以为太难IⅢ
                0.2566 0.8257 0.2771 0.8625 西
                0.8141 0.8285 0.8999 0.8646 2026年7月5日
                0.2907 0.8444 0.3617 0.8799 累计200次，
                0.3598 0.8444 0.5919 0.8785 使用[以太]属性代理人使敌人陷入属性异常。
                0.7478 0.8493 0.7642 0.8778 20
                """,
            ),
        )

        assertEquals(
            listOf("累计50次，使用[以太]属性代理人使敌人陷入属性异常。", "累计200次，使用[以太]属性代理人使敌人陷入属性异常。"),
            screen.rows.map { it.description },
        )
        // The row's medal, read as 西 half a line below the name, isn't joined to it.
        assertEquals(listOf("以为太难Ⅱ", "以为太难IⅢ"), screen.rows.map { it.name })
    }

    @Test
    fun parsesThePaddleOcrReadOfACityPage() {
        val screen = GameScreenParser.parse(loadFixture("paddle-city"))

        assertEquals(GameScreenParser.TabNames.toSet(), screen.tabs.keys)
        assertEquals(631, screen.completedCount)
        assertEquals(
            listOf("绳匠业务", "雅努斯区", "卫非地", "外环地带", "索恩区", "罗斯凯利法"),
            screen.sidebar.map { it.text },
        )
        assertEquals(
            listOf(
                GameRow("LV60，恭迎王的回归", "绳网等级达到60级。", RowStatus.InProgress(57, 60)),
                GameRow(
                    "「德丰大押」荣誉顾客",
                    "在「德丰大押』中，用云纹徽兑换[鹤唳天青]、[翠影朱绾]和[柠火溯流]。",
                    RowStatus.InProgress(2, 3),
                ),
                GameRow("潮头浪尖", "收集6套主题和挂坠。", RowStatus.InProgress(7, 12)),
                GameRow("精选帖核心受众", "在绳网上阅读并回复10条『Fairy精选』帖子。", RowStatus.InProgress(3, 10)),
                GameRow("少女神明的七日箱庭", "通过[调整时段]功能，累计推移28个时段。", RowStatus.Completed("2026-06-26")),
            ),
            screen.rows,
        )
    }

    /** Space-separated "left top right bottom text" lines, as in the round-two notes. */
    private fun boxes(lines: String): List<OcrBox> =
        lines.trimIndent().lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.trim().split(' ', limit = 5)
            OcrBox(parts[4], parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
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
