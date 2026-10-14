package com.local.stardbzzz.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanReportTest {
    private val candidates = listOf(
        candidate("1", "目标是骸影大师", "在战术道具栏位装备12个骸影时进入调查。", "迷宫诡域"),
        candidate("2", "「德丰大押」荣誉顾客", "在「德丰大押」中，用云纹徽兑换[鹤喉天青]。", "雅努斯区"),
        candidate("3", "{M#兄长}{F#妹妹}的约定", "完成委托。", "代理人秘闻"),
        candidate("4", "潮头浪尖", "收集6套主题和挂坠。", "绳匠业务", completed = true),
        candidate("5", "公平竞争制度", "救出所有走失的邦布商人。", "空洞探索指南"),
        candidate("6", "公平竞争制度", "在其他关卡中完成挑战。", "零号密钥"),
    )
    private val matcher = AchievementMatcher(candidates)

    @Test
    fun matchesNameWithMissingCharacter() {
        assertEquals("1", matcher.match("目标是影大师", "在战术道具栏位装备12个影时进入调查。", "迷宫诡域")?.id)
    }

    @Test
    fun ignoresBracketVariants() {
        assertEquals("2", matcher.match("『德丰大押』荣誉顾客", "", null)?.id)
    }

    @Test
    fun matchesEitherProtagonistVariant() {
        assertEquals("3", matcher.match("妹妹的约定", "", null)?.id)
        assertEquals("3", matcher.match("兄长的约定", "", null)?.id)
    }

    @Test
    fun usesCategoryHintForDuplicateNames() {
        assertEquals("6", matcher.match("公平竞争制度", "", "零号密钥")?.id)
        assertEquals("5", matcher.match("公平竞争制度", "", "空洞探索指南")?.id)
    }

    @Test
    fun usesDescriptionWithoutCategoryHint() {
        assertEquals("6", matcher.match("公平竞争制度", "在其他关卡中完成挑战。", null)?.id)
    }

    @Test
    fun leavesAmbiguousDuplicateNamesUnmatched() {
        // Same name in two categories, no category hint and no description to tell them apart.
        assertNull(matcher.match("公平竞争制度", "", null))
    }

    @Test
    fun acceptsDuplicateRecordsOfTheSameAchievement() {
        val duplicated = AchievementMatcher(
            listOf(
                candidate("7", "治安官的答案", "完成法厄同纪事「非常任务」。", "法厄同纪事"),
                candidate("8", "治安官的答案", "完成法厄同纪事「非常任务」。", "法厄同纪事"),
            ),
        )
        assertEquals("7", duplicated.match("治安官的答案", "", null)?.id)
    }

    @Test
    fun rejectsUnrelatedText() {
        assertNull(matcher.match("在「实习生事故实录」中，救出所有走失的邦布商人。", "", null))
        assertNull(matcher.match("全部领取", "", null))
    }

    @Test
    fun buildsDiffsInBothDirections() {
        val observed = listOf(
            ObservedRow("战术", "迷宫诡域", "目标是影大师", "", RowStatus.Completed("2026-07-01")),
            // Seen again on the next page.
            ObservedRow("战术", "迷宫诡域", "目标是影大师", "", RowStatus.Completed("2026-07-01")),
            ObservedRow("城市", "绳匠业务", "潮头浪尖", "", RowStatus.InProgress(7, 12)),
            ObservedRow("城市", "雅努斯区", "『德丰大押』荣誉顾客", "", RowStatus.InProgress(2, 3)),
            ObservedRow("城市", "雅努斯区", "完全不存在的成就", "", RowStatus.Completed(null)),
        )

        val report = ScanReportBuilder.build(observed, candidates, gameCompletedCount = 631)

        assertEquals(631, report.gameCompletedCount)
        assertEquals(1, report.localCompletedCount)
        assertEquals(4, report.observedCount)
        assertEquals(3, report.matchedCount)
        assertEquals(listOf("完全不存在的成就"), report.unmatched.map { it.name })
        assertEquals(
            listOf("1" to true, "4" to false),
            report.diffs.map { it.id to it.markCompleted },
        )
        assertEquals("已完成（2026-07-01）", report.diffs[0].gameStatus)
        assertEquals("进行中 7/12", report.diffs[1].gameStatus)
    }

    @Test
    fun keepsSameNameAchievementsWithDifferentDescriptions() {
        val observed = listOf(
            ObservedRow("探索", "空洞探索指南", "公平竞争制度", "救出所有走失的邦布商人。", RowStatus.Completed("2026-06-25")),
            ObservedRow("探索", "空洞探索指南", "公平竞争制度", "在其他关卡中完成挑战。", RowStatus.Completed("2026-06-26")),
        )
        val sameCategory = listOf(
            candidate("5", "公平竞争制度", "救出所有走失的邦布商人。", "空洞探索指南"),
            candidate("6", "公平竞争制度", "在其他关卡中完成挑战。", "空洞探索指南"),
        )

        val report = ScanReportBuilder.build(observed, sameCategory, gameCompletedCount = null)

        assertEquals(listOf("5", "6"), report.diffs.map { it.id })
        assertEquals(2, report.observedCount)
    }

    @Test
    fun countsSameNameAchievementsWithDifferentDescriptionsSeparately() {
        val candidates = listOf(
            candidate("5", "公平竞争制度", "救出所有走失的邦布商人。", "空洞探索指南", completed = true),
            candidate("6", "公平竞争制度", "在其他关卡中完成挑战。", "空洞探索指南", completed = true),
            // stardb listing achievement 5 a second time.
            candidate("5b", "公平竞争制度", "救出所有走失的邦布商人", "空洞探索指南", completed = true),
        )
        val observed = listOf(
            ObservedRow("探索", "空洞探索指南", "公平竞争制度", "救出所有走失的邦布商人。", RowStatus.Completed("2026-06-25")),
        )

        val report = ScanReportBuilder.build(observed, candidates, gameCompletedCount = null)

        assertEquals(2, report.localCompletedCount)
        assertEquals(1, report.gameCompletedSeen)
        assertEquals(listOf("6"), report.localCompletedNotSeen.map { it.id })
    }

    @Test
    fun foldsTraditionalAndLookAlikeCharacters() {
        // Unmatched in the second phone run: 採/探, 來/来, 樹/树, 購/购, カ/力.
        val readings = AchievementMatcher(
            listOf(
                candidate("w1", "工业化空洞探索", "", "空洞探索指南"),
                candidate("w2", "只是来串个门", "破译[罗斯威克]的绘本残页。", "勘域探境"),
                candidate("w3", "树间所遗忘的", "", "外环地带"),
                candidate("w4", "小心别购秀了", "", "代理人秘闻"),
                candidate("w5", "核心力量训练", "", "绳匠业务"),
            ),
        )

        assertEquals("w1", readings.match("I业化空洞採索", "", "空洞採索指南")?.id)
        assertEquals("w2", readings.match("只是來串个们", "破译[罗新威克的绘本残页。", "勘域探境")?.id)
        assertEquals("w3", readings.match("樹间所遗忘的", "", null)?.id)
        assertEquals("w4", readings.match("小心别購秀了", "", null)?.id)
        assertEquals("w5", readings.match("核心カ量训练", "", null)?.id)
    }

    @Test
    fun misreadCategoryNamesStillPickTheirCategory() {
        val categories = listOf(
            "法厄同纪事", "代理人秘闻", "独家视界", "代理人信赖", "际会之时", "绳网热议",
            "绳匠业务", "雅努斯区", "布亚斯特", "卫非地", "外环地带", "索恩区", "罗斯凯利法",
            "迷宫诡域", "作战技巧", "敌对目标", "战斗成就", "迷失之地", "枯萎之都",
            "空洞探索指南", "零号密钥", "勘域探境",
        )
        // The same name in every category, so only the category hint can pick one.
        val everywhere = AchievementMatcher(categories.map { candidate(it, "同名成就", "说明$it", it) })
        val misreads = mapOf(
            "代理人信" to "代理人信赖",
            "罗野凯利法" to "罗斯凯利法",
            "空洞採索指南" to "空洞探索指南",
            "枯藝之都" to "枯萎之都",
            "迷官诡域" to "迷宫诡域",
        )

        misreads.forEach { (read, actual) ->
            assertEquals(read, actual, everywhere.match("同名成就", "", read)?.id)
        }
    }

    @Test
    fun tellsTiersApartByTheNumbersInTheirDescription() {
        val tiers = AchievementMatcher(
            listOf(
                candidate("t1", "断金裂石Ⅰ", "在战斗中，累计触发10次「毁伤」效果。", "战斗成就"),
                candidate("t2", "断金裂石Ⅱ", "在战斗中，累计触发50次「毁伤」效果。", "战斗成就"),
                candidate("t3", "断金裂石Ⅲ", "在战斗中，累计触发200次「毁伤」效果。", "战斗成就"),
            ),
        )

        // Sample B from the second phone run: Ⅰ read as 1, Ⅱ and Ⅲ both read as =, 裂 once read as 製.
        assertEquals("t1", tiers.match("断金裂石1", "在战斗中,累计触发10次『毁伤」效果。", "战斗成就")?.id)
        assertEquals("t2", tiers.match("断金製石=", "在战斗中,累计触发50次「毁伤｣效果。", "战斗成就")?.id)
        assertEquals("t3", tiers.match("断金裂石=", "在战斗中,累计触发200次『毁伤」效果。", "战斗成就")?.id)
        // Without a readable number the tier can't be told; don't guess.
        assertNull(tiers.match("断金裂石=", "", "战斗成就"))
        assertNull(tiers.match("断金裂石|", "在战斗中,累计触发次『毁伤」效果。", "战斗成就"))
    }

    @Test
    fun tiersWithIdenticalDescriptionsAreNotTreatedAsDuplicates() {
        val tiers = AchievementMatcher(
            listOf(
                candidate("t1", "风起云涌Ⅰ", "完成挑战。", "战斗成就"),
                candidate("t2", "风起云涌Ⅱ", "完成挑战。", "战斗成就"),
            ),
        )

        assertNull(tiers.match("风起云涌|", "完成挑战。", "战斗成就"))
    }

    @Test
    fun reconcilesCompletedCountsAndListsWhatTheScanDidNotSee() {
        val local = listOf(
            candidate("a", "治安官的答案", "完成法厄同纪事。", "法厄同纪事", completed = true),
            // stardb lists this achievement twice; it counts once.
            candidate("a2", "治安官的答案", "完成法厄同纪事。", "法厄同纪事", completed = true),
            candidate("b", "叩响命运之门", "完成法厄同纪事。", "法厄同纪事"),
            candidate("c", "从未出现的成就", "说明。", "法厄同纪事", completed = true),
            candidate("d", "合理利用", "找到音擎。", "空洞探索指南", completed = true),
        )
        val observed = listOf(
            ObservedRow("故事", "法厄同纪事", "治安官的答案", "", RowStatus.Completed("2026-06-29")),
            ObservedRow("故事", "法厄同纪事", "叩响命运之门", "", RowStatus.Completed("2026-07-08")),
            ObservedRow("探索", "空洞探索指南", "合理利用", "", RowStatus.Completed("2024-07-07")),
            ObservedRow("探索", "空洞探索指南", "没有这条", "", RowStatus.Completed(null)),
        )

        val report = ScanReportBuilder.build(observed, local, gameCompletedCount = 4)

        assertEquals(4, report.gameCompletedSeen)
        assertEquals(3, report.localCompletedCount)
        assertEquals(4, report.localCompletedRecords)
        assertEquals(listOf("c"), report.localCompletedNotSeen.map { it.id })
        assertEquals(listOf("b"), report.diffs.map { it.id })
        assertEquals(4, report.localCompletedAfterSync)
    }

    @Test
    fun countsSameNameUnmatchedRowsWithDifferentDescriptionsSeparately() {
        val observed = listOf(
            ObservedRow("探索", "空洞探索指南", "没有收录的成就", "救出所有走失的邦布商人。", RowStatus.Completed(null)),
            // The same row on the next page, its description read slightly differently.
            ObservedRow("探索", "空洞探索指南", "没有收录的成就", "救出所有走失的邦布商。", RowStatus.Completed(null)),
            ObservedRow("探索", "空洞探索指南", "没有收录的成就", "在其他关卡中完成挑战。", RowStatus.Completed(null)),
        )

        val report = ScanReportBuilder.build(observed, candidates, gameCompletedCount = null)

        assertEquals(2, report.unmatched.size)
        assertEquals(2, report.gameCompletedSeen)
    }

    @Test
    fun aRowWhoseDescriptionWasDroppedDoesNotSwallowDifferentSameNameRows() {
        fun row(description: String) =
            ObservedRow("探索", "空洞探索指南", "没有收录的成就", description, RowStatus.Completed(null))

        // The description line missing on the first page must not make the two achievements below one.
        val ambiguous = ScanReportBuilder.build(
            listOf(row(""), row("救出所有走失的邦布商人。"), row("在其他关卡中完成挑战。")),
            candidates,
            gameCompletedCount = null,
        )
        assertEquals(listOf("救出所有走失的邦布商人。", "在其他关卡中完成挑战。"), ambiguous.unmatched.map { it.description })
        assertEquals(2, ambiguous.gameCompletedSeen)

        // With one same-name row it is that row; with none it counts once however often it is read.
        val single = ScanReportBuilder.build(listOf(row(""), row("救出所有走失的邦布商人。")), candidates, gameCompletedCount = null)
        assertEquals(1, single.gameCompletedSeen)
        val blankOnly = ScanReportBuilder.build(listOf(row(""), row("")), candidates, gameCompletedCount = null)
        assertEquals(1, blankOnly.unmatched.size)
    }

    @Test
    fun matchesEitherNameOfASlashPair() {
        val pair = AchievementMatcher(listOf(candidate("p", "执事的晚安/晚安大小姐", "", "代理人秘闻")))

        assertEquals("p", pair.match("执事的晚安", "", "代理人秘闻")?.id)
        assertEquals("p", pair.match("晚安大小姐", "", "代理人秘闻")?.id)
    }

    @Test
    fun lookAlikeSwapsCostLessThanOtherEdits() {
        val local = AchievementMatcher(
            listOf(
                candidate("m", "梦魇的梦魇", "", "敌对目标"),
                candidate("f", "风起云涌Ⅰ", "", "战斗成就"),
                candidate("d", "如越山峦叠嶂", "", "勘域探境"),
                candidate("y", "营业时间", "", "勘域探境"),
            ),
        )

        // Two swapped characters in five: a plain edit distance (0.6) would reject this.
        assertEquals("m", local.match("梦魔的梦魔", "", "敌对目标")?.id)
        assertEquals("f", local.match("凤起云涌|", "", "战斗成就")?.id)
        // Read both ways in the second phone run.
        assertEquals("d", local.match("如越山峦營峰", "", "勘域探境")?.id)
        assertEquals("d", local.match("如越山峦參峰", "", "勘域探境")?.id)
        // 營 is also the traditional form of 营, which still matches as itself.
        assertEquals("y", local.match("營业时间", "", "勘域探境")?.id)
        // Two unrelated characters are still too many.
        assertNull(local.match("梦话的梦想", "", "敌对目标"))
    }

    @Test
    fun aSharedNameIsNotMatchedInAnotherCategory() {
        val local = AchievementMatcher(
            listOf(
                candidate("h1", "英雄之旅", "在「预言之下」中，赢得一场史诗般的单挑。", "空洞探索指南"),
                candidate("h2", "英雄之旅", "完成所有英雄委托。", "代理人秘闻"),
                candidate("u", "只是来串个门", "破译绘本残页。", "勘域探境"),
            ),
        )

        // The row's own category doesn't list it locally (an outdated list): don't hand it to a namesake.
        assertNull(local.match("英雄之旅", "", "零号密钥"))
        // A unique name is still safe outside its category, e.g. after a category rename.
        assertEquals("u", local.match("只是來串个们", "", "零号密钥")?.id)
        // In its own category the right namesake is found.
        assertEquals("h2", local.match("英雄之旅", "", "代理人秘闻")?.id)
    }

    @Test
    fun dropsClippedHeadsAndTailsOfLocalNames() {
        val local = listOf(candidate("s", "献给第一位主人的使命", "", "代理人秘闻"))
        val observed = listOf(
            ObservedRow("故事", "代理人秘闻", "献给第一位主人的使命", "", RowStatus.Completed(null)),
            // The same row cut off at the top of the list on the previous page.
            ObservedRow("故事", "代理人秘闻", "位主人的使命", "", RowStatus.Completed(null)),
            ObservedRow("故事", "代理人秘闻", "完全没有的成就", "", RowStatus.Completed(null)),
        )

        val report = ScanReportBuilder.build(observed, local, gameCompletedCount = null)

        assertEquals(listOf("完全没有的成就"), report.unmatched.map { it.name })
        assertEquals(1, report.clippedReads)
        assertEquals(2, report.gameCompletedSeen)
    }

    private fun candidate(id: String, name: String, description: String, category: String, completed: Boolean = false) =
        ScanCandidate(id, name, description, category, completed)
}
