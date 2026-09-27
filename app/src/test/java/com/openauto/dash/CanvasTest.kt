package com.openauto.dash

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The Canvas theme: reading the route, the rain ahead, sunset and its home arrangement. */
class CanvasTest {

    private fun today(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun minutesLeftAreReadFromTheEtaLine() {
        assertEquals(26, etaMinutes("26 min · 18 km · 14:58"))
        assertEquals(65, etaMinutes("1 h 5 min · 92 km"))
        assertEquals(120, etaMinutes("2 h · 180 km"))
        assertNull(etaMinutes("18 km"))
    }

    @Test
    fun arrivalIsTheClockTimeOrNowPlusTheMinutes() {
        val now = today(14, 32)
        assertEquals(today(14, 58), arrivalMs("26 min · 18 km · 14:58", now))
        assertEquals(today(14, 58), arrivalMs("26 min · 18 km · 2:58 PM", now))
        assertEquals(now + 26 * 60_000L, arrivalMs("26 min · 18 km", now))
        assertNull(arrivalMs("18 km", now))
        // Past midnight: tomorrow's clock time.
        val late = today(23, 50)
        assertEquals(today(0, 10) + 24 * 3_600_000L, arrivalMs("20 min · 0:10", late))
    }

    @Test
    fun theFirstWetSpellAheadIsFound() {
        // Local times at UTC+2; now is 14:30 local (12:30 UTC).
        val utc = TimeZone.getTimeZone("UTC")
        fun utcAt(hour: Int, minute: Int = 0) = Calendar.getInstance(utc).apply { clear(); set(2026, 8, 28, hour, minute, 0) }.timeInMillis
        val hourly = JSONObject()
            .put("time", JSONArray((14..20).map { "2026-09-28T%02d:00".format(it) }))
            .put("precipitation", JSONArray(listOf(0.0, 0.0, 0.5, 0.8, 0.0, 0.0, 0.0)))
            .put("precipitation_probability", JSONArray(listOf(10, 20, 70, 80, 30, 10, 0)))
        val spell = rainSpell(hourly, 7200, utcAt(12, 30))
        assertNotNull(spell)
        assertEquals(utcAt(14), spell!!.first)
        assertEquals(utcAt(16), spell.second)
        // A chance alone counts too; a dry forecast gives nothing.
        val likely = JSONObject().put("time", JSONArray(listOf("2026-09-28T15:00"))).put("precipitation_probability", JSONArray(listOf(65)))
        assertEquals(utcAt(13), rainSpell(likely, 7200, utcAt(12, 30))!!.first)
        val dry = JSONObject().put("time", JSONArray(listOf("2026-09-28T15:00"))).put("precipitation", JSONArray(listOf(0.1)))
        assertNull(rainSpell(dry, 7200, utcAt(12, 30)))
    }

    @Test
    fun sunsetOverParisOnMidsummerIsFoundToTheMinutes() {
        val utc = TimeZone.getTimeZone("UTC")
        fun at(hour: Int) = Calendar.getInstance(utc).apply { clear(); set(2025, 5, 21, hour, 0, 0) }.timeInMillis
        val sunset = nextSunset(at(17), at(23), 48.8566, 2.3522)
        assertNotNull(sunset)
        // About 19:58 UTC.
        assertTrue("$sunset", sunset!! in (at(19) + 45 * 60_000L)..(at(20) + 15 * 60_000L))
        assertNull(nextSunset(at(8), at(12), 48.8566, 2.3522))
    }

    @Test
    fun theCanvasHomeFitsTheGridAndOnlyReplacesTheMiddlePage() {
        val home = DashboardStore.canvasHome()
        home.forEach { t -> assertTrue("$t", t.x >= 0 && t.y >= 0 && t.x + t.w <= GRID_COLS && t.y + t.h <= GRID_ROWS) }
        for (i in home.indices) for (j in i + 1 until home.size) {
            val a = home[i]
            val b = home[j]
            val apart = a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y
            assertTrue("$a overlaps $b", apart)
        }
        val own = List(7) { page -> listOf<DashboardItem>(DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, x = page)) }
        val seeded = DashboardStore.withCanvasHome(own)
        assertEquals(home, seeded[DashboardStore.CENTER])
        seeded.forEachIndexed { i, page -> if (i != DashboardStore.CENTER) assertEquals(own[i], page) }
    }
}
