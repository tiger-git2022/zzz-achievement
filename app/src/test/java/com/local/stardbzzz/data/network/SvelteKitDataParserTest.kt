package com.local.stardbzzz.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SvelteKitDataParserTest {
    /**
     * A slice of stardb's tracker data: two single-achievement groups and the story-branch group of
     * 战场「出片」指南 (two achievements, same name and version, different descriptions) whose `complete`
     * names its second member.
     */
    @Test
    fun onlyTheMemberAGroupRecordsAsCompleteIsCompleted() {
        val raw = javaClass.classLoader!!.getResourceAsStream("stardb/tracker-groups.json")!!.bufferedReader().readText()

        val achievements = parseAchievementTrackerData(raw).associateBy { it.id.toDouble().toLong() }

        assertEquals(true, achievements.getValue(1005001).completed)
        assertEquals(false, achievements.getValue(1005002).completed)
        assertEquals(false, achievements.getValue(1005030).completed)
        assertEquals(true, achievements.getValue(1005030).completedViaGroup)
        assertEquals(true, achievements.getValue(1005031).completed)
        assertEquals(false, achievements.getValue(1005031).completedViaGroup)
        assertEquals("战场「出片」指南", achievements.getValue(1005031).name)
    }
}
