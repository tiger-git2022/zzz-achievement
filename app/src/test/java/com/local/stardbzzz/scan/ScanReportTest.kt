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

    private fun candidate(id: String, name: String, description: String, category: String, completed: Boolean = false) =
        ScanCandidate(id, name, description, category, completed)
}
