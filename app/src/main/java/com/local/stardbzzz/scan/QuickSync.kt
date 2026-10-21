package com.local.stardbzzz.scan

/**
 * What a 快速同步 compares against, saved when a scan has seen the whole achievement page (or caught up with it).
 *
 * The game lists each category's unfinished achievements first (x/y on the right), then the finished ones (a date).
 * It doesn't list everything stardb has, though: hidden achievements and the trust achievements of agents you
 * don't have only show up once done (a full scan found ~140 such unfinished ones). So "no longer in the unfinished
 * part" only means "done" for achievements a scan has actually seen there: [inProgressIds].
 */
data class QuickSyncBaseline(
    /**
     * The game's 完成数 minus the local completed count (achievements on the achievement page, counted once), less
     * any completions still to be found. The two never quite agree (stardb lists some achievements twice, the game
     * counts a few differently), but the gap stays put, so the next 完成数 tells how many achievements were
     * completed since. It is taken against the local state when a scan ends, and moves with every change applied
     * from its report ([QuickSync.baselineAfterApply]): differences left unapplied are then not counted as new.
     */
    val offset: Int,
    /** Local achievements the game listed as unfinished. */
    val inProgressIds: Set<String>,
)

interface QuickSyncStore {
    fun load(): QuickSyncBaseline?
    fun save(baseline: QuickSyncBaseline)
}

/**
 * One sidebar category to read: only its unfinished part at the top ([headOnly]), or the whole list, which a
 * newly done achievement the game hid while unfinished needs. [priority] is how many completions it likely holds:
 * the summed stardb completion rates of its open achievements.
 */
data class QuickTask(val tab: String, val category: String, val headOnly: Boolean, val priority: Double)

/** The rows read from one category's list; [complete] once the read reached its end (or, head only, a date). */
data class QuickRead(val task: QuickTask, val label: String, val rows: List<ObservedRow>, val complete: Boolean)

data class QuickSyncSummary(
    /** Achievements completed since the baseline, by the game's 完成数. */
    val expectedNew: Int,
    /** Completions this sync found (read or inferred), counted like 完成数. */
    val found: Int,
    val categoriesRead: Int,
    val categoriesPlanned: Int,
    /** Planned categories the sidebar never showed. */
    val notFound: List<String> = emptyList(),
) {
    val consistent: Boolean get() = found == expectedNew
}

object QuickSync {
    /** Completed achievements on the achievement page, each counted once, as the game's 完成数 counts them. */
    fun localCompleted(candidates: List<ScanCandidate>): Int =
        candidates.filter { it.onAchievementPage && it.isCompleted }.map { it.countKey }.distinct().size

    fun expectedNew(candidates: List<ScanCandidate>, baseline: QuickSyncBaseline, gameCompletedCount: Int): Int =
        gameCompletedCount - localCompleted(candidates) - baseline.offset

    /**
     * Categories worth reading, most likely first: those with achievements a scan saw unfinished (only their top,
     * cheap), then those with achievements the game may have hidden while unfinished (the whole list). Categories
     * with nothing left to complete locally are skipped. Within each pass, tabs and categories go by [QuickTask.priority]
     * (tabs by their categories' sum), keeping each tab's categories together so a tab is opened once per pass.
     */
    fun plan(candidates: List<ScanCandidate>, baseline: QuickSyncBaseline): List<QuickTask> {
        val open = openAchievements(candidates)
        val (seen, hidden) = open.partition { it.id in baseline.inProgressIds }
        return order(tasks(seen, headOnly = true)) + order(tasks(hidden, headOnly = false))
    }

    /**
     * The report of a 快速同步 so far. Rows read as completed become diffs as in a full scan. A category read to the
     * end of its unfinished part also tells which of the achievements a scan saw unfinished there are gone from it:
     * those are inferred done. An inference is [ScanDiff.uncertain] (not selected by default) when the category had
     * unfinished rows that matched no local achievement (one may be the "missing" one, misread), when it is a story
     * branch (another branch may have been taken), or when the completions found don't add up to 完成数.
     */
    fun report(
        candidates: List<ScanCandidate>,
        baseline: QuickSyncBaseline,
        gameCompletedCount: Int?,
        expectedNew: Int,
        reads: List<QuickRead>,
        planned: Int,
        notFound: List<String> = emptyList(),
    ): ScanReport {
        val base = ScanReportBuilder.build(reads.flatMap { it.rows }, candidates, gameCompletedCount)
        val matcher = AchievementMatcher(candidates)
        val byId = candidates.associateBy { it.id }
        val open = openAchievements(candidates).filter { it.id in baseline.inProgressIds }

        val readDone = base.diffs.filter { it.markCompleted }.mapNotNull { byId[it.id] }
        val doneKeys = readDone.map { it.countKey }.toMutableSet()
        val doneGroups = readDone.map { it.group }.filter { it.isNotBlank() }.toMutableSet()
        val inferred = mutableListOf<ScanDiff>()
        reads.filter { it.complete }.groupBy { it.task.tab to it.task.category }.forEach { (place, categoryReads) ->
            val rows = categoryReads.flatMap { it.rows }
            val matched = rows.mapNotNull { row -> matcher.match(row.name, row.description, row.category)?.let { it to row } }
            val seenKeys = matched.map { it.first.countKey }.toSet()
            val unmatchedOpenRows = rows.count { row ->
                row.status is RowStatus.InProgress && matched.none { it.second === row }
            }
            open.filter { it.tab == place.first && it.category == place.second }
                .filter { it.countKey !in seenKeys && it.countKey !in doneKeys && it.group !in doneGroups }
                .distinctBy { it.countKey }
                .forEach { candidate ->
                    doneKeys += candidate.countKey
                    if (candidate.group.isNotBlank()) doneGroups += candidate.group
                    inferred += ScanDiff(
                        id = candidate.id,
                        name = candidate.name,
                        category = candidate.category,
                        markCompleted = true,
                        gameStatus = "不在未完成列表里",
                        inferred = true,
                        uncertain = unmatchedOpenRows > 0 || candidate.group.isNotBlank(),
                    )
                }
        }

        val summary = QuickSyncSummary(
            expectedNew = expectedNew,
            found = doneKeys.size,
            categoriesRead = reads.map { it.task.tab to it.task.category }.distinct().size,
            categoriesPlanned = planned,
            notFound = notFound,
        )
        val diffs = base.diffs + inferred.map { if (summary.consistent) it else it.copy(uncertain = true) }
        return base.copy(
            // Only part of the page was read: a completed achievement not seen says nothing.
            localCompletedNotSeen = emptyList(),
            diffs = diffs.sortedByDescending { it.markCompleted },
            quick = summary,
        )
    }

    /** The baseline after a full scan: the gap as the local state stands, before any of its report is applied. */
    fun baselineAfterFullScan(candidates: List<ScanCandidate>, report: ScanReport): QuickSyncBaseline? {
        val count = report.gameCompletedCount ?: return null
        return QuickSyncBaseline(offset = count - localCompleted(candidates), inProgressIds = report.inProgressIds)
    }

    /**
     * The baseline after a 快速同步: achievements found done leave [QuickSyncBaseline.inProgressIds], ones read as
     * unfinished join it (an agent you just got). What was found no longer counts as still to find. When every
     * planned category was found and read to its end and the completions still fell short of 完成数, the rest can't
     * be found this way, so the gap is taken as it stands.
     */
    fun baselineAfterQuickSync(
        candidates: List<ScanCandidate>,
        baseline: QuickSyncBaseline,
        report: ScanReport,
        readEverything: Boolean,
    ): QuickSyncBaseline {
        val summary = report.quick ?: return baseline
        val done = report.diffs.filter { it.markCompleted }.map { it.id }.toSet()
        val offset = if (readEverything && summary.found < summary.expectedNew && report.gameCompletedCount != null) {
            report.gameCompletedCount - localCompleted(candidates)
        } else {
            baseline.offset + summary.found
        }
        return QuickSyncBaseline(offset, baseline.inProgressIds - done + report.inProgressIds)
    }

    /**
     * The baseline once changes from a report were applied: the local completed count moved, and 完成数 didn't, so
     * the gap moves with it. Whatever the user left unapplied stays out of the next count of new completions.
     */
    fun baselineAfterApply(
        baseline: QuickSyncBaseline,
        before: List<ScanCandidate>,
        after: List<ScanCandidate>,
    ): QuickSyncBaseline = baseline.copy(offset = baseline.offset - (localCompleted(after) - localCompleted(before)))

    /** Achievements on the page that can still be completed: not done, and no other branch of their group done. */
    private fun openAchievements(candidates: List<ScanCandidate>): List<ScanCandidate> {
        val doneGroups = candidates.filter { it.isCompleted && it.group.isNotBlank() }.map { it.group }.toSet()
        return candidates.filter { it.onAchievementPage && !it.isCompleted && it.group !in doneGroups }
    }

    private fun tasks(achievements: List<ScanCandidate>, headOnly: Boolean): List<QuickTask> =
        achievements.groupBy { it.tab to it.category }.map { (place, members) ->
            val priority = members.distinctBy { it.countKey }.sumOf { it.percent.coerceAtLeast(0.0) }
            QuickTask(place.first, place.second, headOnly, priority)
        }

    private fun order(tasks: List<QuickTask>): List<QuickTask> =
        tasks.groupBy { it.tab }.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<QuickTask>>> { tab -> tab.value.sumOf { it.priority } }.thenBy { GameScreenParser.TabNames.indexOf(it.key) })
            .flatMap { (_, inTab) -> inTab.sortedWith(compareByDescending<QuickTask> { it.priority }.thenBy { it.category }) }
}
