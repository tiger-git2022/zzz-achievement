package com.local.stardbzzz.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementEntityTest {
    private fun entity(id: String, completed: Boolean, group: String = "") = AchievementEntity(
        id = id,
        name = "战场「出片」指南",
        description = id,
        primaryCategory = "故事",
        category = "法厄同纪事",
        reward = "5",
        version = "3.1",
        displayOrder = 0,
        iconTier = null,
        isCompleted = completed,
        syncStatus = SyncStatus.Synced,
        updatedAtEpochMillis = 0,
        groupKey = group,
    )

    @Test
    fun hidesOtherBranchesOnceOneIsCompletedAndShowsThemAgainWhenUncompleted() {
        val done = listOf(entity("a", true, "a"), entity("b", false, "a"), entity("c", false))
        assertEquals(listOf("a", "c"), done.withoutCoveredBranches().map { it.id })
        assertEquals(listOf("a", "b", "c"), done.withoutCoveredBranches(keep = setOf("b")).map { it.id })

        val undone = listOf(entity("a", false, "a"), entity("b", false, "a"), entity("c", false))
        assertEquals(listOf("a", "b", "c"), undone.withoutCoveredBranches().map { it.id })
    }

    @Test
    fun countsAGroupAsOneAchievement() {
        val all = listOf(entity("a", true, "a"), entity("b", false, "a"), entity("c", false))
        assertEquals(2, all.map { it.countKey }.distinct().size)
        assertEquals(1, all.filter { it.isCompleted }.map { it.countKey }.distinct().size)
    }
}
