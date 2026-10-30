package com.openauto.dash.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadMinutesTest {
    private fun RoadMinutes.at(now: Long) = add(now) { RoadMinute(now, 6, 100f, 170f, true, 0) }

    @Test
    fun `keeps one reading a minute`() {
        val minutes = RoadMinutes()
        minutes.at(0)
        minutes.at(59_999)
        minutes.at(60_000)
        assertEquals(listOf(0L, 60_000L), minutes.all().map { it.at })
    }

    @Test
    fun `drops the oldest past three hours`() {
        val minutes = RoadMinutes()
        for (m in 0L..180L) minutes.at(m * 60_000)
        assertEquals(180, minutes.all().size)
        assertEquals(60_000L, minutes.all().first().at)
    }
}
