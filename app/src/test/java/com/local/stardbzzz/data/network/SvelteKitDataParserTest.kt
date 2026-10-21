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
        // Both branches name their group after its first member; groups of one have none.
        assertEquals(achievements.getValue(1005030).id, achievements.getValue(1005030).group)
        assertEquals(achievements.getValue(1005030).id, achievements.getValue(1005031).group)
        assertEquals("", achievements.getValue(1005001).group)
        // stardb's completion rate, which orders 快速同步's categories.
        assertEquals(0.8776, achievements.getValue(1005001).percent!!, 0.0001)
        // Series 1005 (法厄同纪事) is on the 故事 tab.
        assertEquals(setOf("故事"), achievements.values.map { it.primaryCategory }.toSet())
    }

    @Test
    fun tabComesFromTheSeriesNumber() {
        // Categories added by game updates, which a fixed list of names put under 其他.
        assertEquals("城市", tabOfSeries("4006")) // 罗斯凯利法
        assertEquals("战术", tabOfSeries("2006")) // 迷宫诡域
        assertEquals("探索", tabOfSeries("3002.0")) // 零号密钥
        assertEquals("故事", tabOfSeries("1009")) // 绳网热议
        // The arcade games (25马赫 is series 4) aren't on the achievement page.
        assertEquals(null, tabOfSeries("4"))
        assertEquals(null, tabOfSeries(null))
    }
}
