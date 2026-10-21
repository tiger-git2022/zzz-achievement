package com.local.stardbzzz.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSyncTest {
    private val candidates = listOf(
        // 际会之时: two the game listed unfinished, one done.
        candidate("1", "舞蹈练习生", "际会之时", tab = "故事", percent = 0.6),
        candidate("2", "海上狙神", "际会之时", tab = "故事", percent = 0.3),
        candidate("3", "相聚一刻！", "际会之时", tab = "故事", completed = true),
        // 代理人信赖: one listed unfinished, one the game hides (an agent you don't have).
        candidate("4", "为了每一份心意", "代理人信赖", tab = "故事", percent = 0.1),
        candidate("5", "甜系少女成长挚友", "代理人信赖", tab = "故事", percent = 0.5),
        // 敌对目标: listed unfinished, high completion rate.
        candidate("6", "无所遁形", "敌对目标", tab = "战术", percent = 0.9),
        // 卫非地: everything done locally, nothing to read.
        candidate("7", "应算尽算", "卫非地", tab = "城市", completed = true),
        // The arcade games aren't on the achievement page: neither counted nor read.
        candidate("8", "安全驾驶", "25马赫", tab = "25马赫", completed = true),
        candidate("9", "风驰电掣", "25马赫", tab = "25马赫"),
    )
    private val baseline = QuickSyncBaseline(offset = -2, inProgressIds = setOf("1", "2", "4", "6"))

    @Test
    fun countsOnlyAchievementsOnThePage() {
        assertEquals(2, QuickSync.localCompleted(candidates))
        // 完成数 5 against 2 done locally and a standing gap of -2: 5 new.
        assertEquals(5, QuickSync.expectedNew(candidates, baseline, gameCompletedCount = 5))
    }

    @Test
    fun plansUnfinishedTopsFirstThenHiddenOnesByCompletionRate() {
        val plan = QuickSync.plan(candidates, baseline)

        assertEquals(
            listOf(
                // Heads: 故事 (0.6 + 0.3 + 0.1 = 1.0) before 战术 (0.9); 际会之时 (0.9) before 代理人信赖 (0.1).
                QuickTask("故事", "际会之时", headOnly = true, priority = 0.6 + 0.3),
                QuickTask("故事", "代理人信赖", headOnly = true, priority = 0.1),
                QuickTask("战术", "敌对目标", headOnly = true, priority = 0.9),
                // Then whole lists, for what the game hid while unfinished.
                QuickTask("故事", "代理人信赖", headOnly = false, priority = 0.5),
            ),
            plan,
        )
        // 卫非地 (all done) and the arcade games are never read.
        assertTrue(plan.none { it.category == "卫非地" || it.category == "25马赫" })
    }

    @Test
    fun infersWhatLeftTheUnfinishedTop() {
        // 舞蹈练习生 is no longer at the top of 际会之时; 海上狙神 still is.
        val reads = listOf(
            read("故事", "际会之时", unfinished = listOf("海上狙神"), done = listOf("相聚一刻！")),
        )

        val report = QuickSync.report(candidates, baseline, 3, expectedNew = 1, reads = reads, planned = 3)

        val diff = report.diffs.single()
        assertEquals("1", diff.id)
        assertTrue(diff.markCompleted)
        assertTrue(diff.inferred)
        assertFalse(diff.uncertain)
        assertEquals(QuickSyncSummary(expectedNew = 1, found = 1, categoriesRead = 1, categoriesPlanned = 3), report.quick)
    }

    @Test
    fun countsCompletionsReadDirectly() {
        // A whole list read shows 甜系少女成长挚友 (hidden while unfinished) with a date.
        val reads = listOf(
            read("故事", "代理人信赖", unfinished = listOf("为了每一份心意"), done = listOf("甜系少女成长挚友"), headOnly = false),
        )

        val report = QuickSync.report(candidates, baseline, 3, expectedNew = 1, reads = reads, planned = 4)

        val diff = report.diffs.single()
        assertEquals("5", diff.id)
        assertFalse(diff.inferred)
        assertTrue(report.quick!!.consistent)
    }

    @Test
    fun anUnreadableUnfinishedRowMakesInferencesUncertain() {
        // One unfinished row matched nothing: it may be 舞蹈练习生, misread.
        val reads = listOf(read("故事", "际会之时", unfinished = listOf("海上狙神", "XYZ"), done = emptyList()))

        val report = QuickSync.report(candidates, baseline, 3, expectedNew = 1, reads = reads, planned = 3)

        assertTrue(report.diffs.single().uncertain)
    }

    @Test
    fun inferencesAreUncertainWhenTheCountDoesNotAddUp() {
        val reads = listOf(read("故事", "际会之时", unfinished = emptyList(), done = emptyList()))

        // Both 舞蹈练习生 and 海上狙神 are gone, but 完成数 only went up by one.
        val report = QuickSync.report(candidates, baseline, 3, expectedNew = 1, reads = reads, planned = 3)

        assertEquals(2, report.quick!!.found)
        assertFalse(report.quick!!.consistent)
        assertTrue(report.diffs.all { it.inferred && it.uncertain })
    }

    @Test
    fun noInferenceFromAnUnfinishedRead() {
        // The read stopped before reaching a date (e.g. the page cap): absence means nothing.
        val reads = listOf(read("故事", "际会之时", unfinished = listOf("海上狙神"), done = emptyList(), complete = false))

        val report = QuickSync.report(candidates, baseline, 3, expectedNew = 1, reads = reads, planned = 3)

        assertTrue(report.diffs.isEmpty())
        assertEquals(0, report.quick!!.found)
    }

    @Test
    fun storyBranchInferencesAreUncertain() {
        val branches = listOf(
            candidate("10", "战场「出片」指南", "法厄同纪事", tab = "故事", group = "10"),
            candidate("11", "战场「出片」指南", "法厄同纪事", tab = "故事", group = "10", description = "受到爆炸冲击波的伤害。"),
        )
        val reads = listOf(read("故事", "法厄同纪事", unfinished = emptyList(), done = emptyList()))

        val report = QuickSync.report(branches, QuickSyncBaseline(0, setOf("10", "11")), 1, expectedNew = 1, reads = reads, planned = 1)

        // The group counts once, and which branch was taken isn't known.
        assertEquals(1, report.quick!!.found)
        assertTrue(report.diffs.single().uncertain)
    }

    @Test
    fun fullScanBaselineAllowsForTheCountsStandingGap() {
        // The game says 4; locally 2 are done and the scan found 舞蹈练习生 done too: a standing gap of 1.
        val report = ScanReportBuilder.build(
            listOf(
                row("故事", "际会之时", "舞蹈练习生", RowStatus.Completed("2026-10-01")),
                row("故事", "际会之时", "海上狙神", RowStatus.InProgress(1, 3)),
                row("战术", "敌对目标", "无所遁形", RowStatus.InProgress(0, 1)),
            ),
            candidates,
            gameCompletedCount = 4,
        )

        val next = QuickSync.baselineAfterFullScan(candidates, report)!!

        assertEquals(QuickSyncBaseline(offset = 2, inProgressIds = setOf("2", "6")), next)
        // Nothing applied yet: the same 完成数 means nothing new.
        assertEquals(0, QuickSync.expectedNew(candidates, next, 4))
        // With 舞蹈练习生 applied, still nothing new.
        val applied = candidates.map { if (it.id == "1") it.copy(isCompleted = true) else it }
        val afterApply = QuickSync.baselineAfterApply(next, candidates, applied)
        assertEquals(QuickSyncBaseline(offset = 1, inProgressIds = setOf("2", "6")), afterApply)
        assertEquals(0, QuickSync.expectedNew(applied, afterApply, 4))
    }

    @Test
    fun appliedUncompletionsDoNotLookLikeNewCompletions() {
        // 相聚一刻！ is done locally but the game lists it unfinished; 完成数 is 1 (卫非地's 应算尽算).
        val report = ScanReportBuilder.build(
            listOf(row("故事", "际会之时", "相聚一刻！", RowStatus.InProgress(0, 1))),
            candidates,
            gameCompletedCount = 1,
        )
        assertTrue(report.diffs.any { it.id == "3" && !it.markCompleted })
        val next = QuickSync.baselineAfterFullScan(candidates, report)!!

        // Applied: 1 done locally, 完成数 1, nothing new.
        val applied = candidates.map { if (it.id == "3") it.copy(isCompleted = false) else it }
        assertEquals(0, QuickSync.expectedNew(applied, QuickSync.baselineAfterApply(next, candidates, applied), 1))
        // Left unapplied (the default for un-completing): nothing new either.
        assertEquals(0, QuickSync.expectedNew(candidates, next, 1))
    }

    @Test
    fun quickSyncBaselineDropsWhatWasFoundAndKeepsTheGap() {
        val reads = listOf(read("故事", "际会之时", unfinished = listOf("海上狙神"), done = emptyList()))
        val report = QuickSync.report(candidates, baseline, 1, expectedNew = 1, reads = reads, planned = 3)

        val next = QuickSync.baselineAfterQuickSync(candidates, baseline, report, readEverything = false)

        // Found, so no longer to find: the next sync before applying expects nothing new.
        assertEquals(QuickSyncBaseline(offset = -1, inProgressIds = setOf("2", "4", "6")), next)
        assertEquals(0, QuickSync.expectedNew(candidates, next, 1))
        // Applied: back to the standing gap.
        val applied = candidates.map { if (it.id == "1") it.copy(isCompleted = true) else it }
        val afterApply = QuickSync.baselineAfterApply(next, candidates, applied)
        assertEquals(-2, afterApply.offset)
        assertEquals(0, QuickSync.expectedNew(applied, afterApply, 1))
    }

    @Test
    fun quickSyncBaselineKeepsWhatIsStillMissingToFind() {
        // 完成数 went up by 2 but the read stopped short (a category not found or cut off): one is still to find.
        val reads = listOf(read("故事", "际会之时", unfinished = listOf("海上狙神"), done = emptyList()))
        val report = QuickSync.report(candidates, baseline, 2, expectedNew = 2, reads = reads, planned = 3)

        val next = QuickSync.baselineAfterQuickSync(candidates, baseline, report, readEverything = false)

        val applied = candidates.map { if (it.id == "1") it.copy(isCompleted = true) else it }
        assertEquals(1, QuickSync.expectedNew(applied, QuickSync.baselineAfterApply(next, candidates, applied), 2))
    }

    @Test
    fun quickSyncBaselineTakesTheGapWhenEverythingWasReadAndSomeStayedMissing() {
        // 完成数 went up by 2, everything planned was read, and only one was found.
        val reads = listOf(read("故事", "际会之时", unfinished = listOf("海上狙神"), done = emptyList()))
        val report = QuickSync.report(candidates, baseline, 2, expectedNew = 2, reads = reads, planned = 1)

        val next = QuickSync.baselineAfterQuickSync(candidates, baseline, report, readEverything = true)

        // Once the found one is applied (3 done locally), 完成数 2 means nothing new.
        assertEquals(0, next.offset)
        val applied = candidates.map { if (it.id == "1") it.copy(isCompleted = true) else it }
        val afterApply = QuickSync.baselineAfterApply(next, candidates, applied)
        assertEquals(-1, afterApply.offset)
        assertEquals(0, QuickSync.expectedNew(applied, afterApply, 2))
    }

    private fun read(
        tab: String,
        category: String,
        unfinished: List<String>,
        done: List<String>,
        headOnly: Boolean = true,
        complete: Boolean = true,
    ): QuickRead {
        val rows = unfinished.map { row(tab, category, it, RowStatus.InProgress(0, 1)) } +
            done.map { row(tab, category, it, RowStatus.Completed("2026-10-01")) }
        return QuickRead(QuickTask(tab, category, headOnly, 1.0), category, rows, complete)
    }

    private fun row(tab: String, category: String, name: String, status: RowStatus) =
        ObservedRow(tab, category, name, candidates.firstOrNull { it.name == name }?.description.orEmpty(), status)

    private fun candidate(
        id: String,
        name: String,
        category: String,
        tab: String,
        completed: Boolean = false,
        percent: Double = -1.0,
        group: String = "",
        description: String = "",
    ) = ScanCandidate(id, name, description, category, completed, "1.0", group, tab, percent)
}
