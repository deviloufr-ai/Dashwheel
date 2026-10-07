package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LpgTankTest {
    private val s = LpgSettings(enabled = true, tankL = 40.0, useL100 = 10.0)

    @Test
    fun noLevelBeforeTheFirstFullFill() {
        assertNull(LpgRules.reading(s, LpgState(), petrolUse = 7.0, burning = true))
    }

    @Test
    fun theLevelFallsWithTheKilometresOnLpg() {
        val filled = LpgRules.fill(LpgState(), now = 1_000, pricePerL = 1.0)
        val r = LpgRules.reading(s, LpgRules.drove(filled, 200.0), petrolUse = 7.0, burning = true)!!
        assertEquals(20.0, r.liters, 0.001)
        assertEquals(50, r.percent)
        assertEquals(200.0, r.rangeKm, 0.001)
        assertEquals(10.0, r.costPer100!!, 0.001)
        assertFalse(r.low)
        val empty = LpgRules.reading(s, LpgRules.drove(filled, 900.0), petrolUse = 7.0, burning = true)!!
        assertEquals(0, empty.percent)
        assertTrue(empty.low)
    }

    @Test
    fun withoutASettingTheUseIsTheCarsAFifthMore() {
        val (use, learned) = LpgRules.use(s.copy(useL100 = null), emptyList(), petrolUse = 7.0)
        assertEquals(8.4, use, 0.001)
        assertFalse(learned)
    }

    @Test
    fun theLitresPumpedTeachTheRealUse() {
        var st = LpgRules.fill(LpgState(), now = 1, pricePerL = null)
        st = LpgRules.pumped(st, 30.0) // the first fill: nothing driven before it, so it teaches nothing
        st = LpgRules.fill(LpgRules.drove(st, 200.0), now = 2, pricePerL = null)
        st = LpgRules.pumped(st, 24.0)
        assertNull(LpgRules.learnedUse(st.fills)) // 200 km is too few
        st = LpgRules.fill(LpgRules.drove(st, 200.0), now = 3, pricePerL = null)
        st = LpgRules.pumped(st, 24.0)
        assertEquals(12.0, LpgRules.learnedUse(st.fills)!!, 0.001)
        val (use, learned) = LpgRules.use(s, st.fills, petrolUse = 7.0)
        assertEquals(12.0, use, 0.001)
        assertTrue(learned)
    }

    @Test
    fun aFullFillResetsTheLevelAndTheWarning() {
        val st = LpgRules.drove(LpgRules.fill(LpgState(), now = 1, pricePerL = null), 350.0).copy(warned = true)
        val next = LpgRules.fill(st, now = 2, pricePerL = 0.95)
        assertEquals(0.0, next.kmSinceFill, 0.0)
        assertFalse(next.warned)
        assertEquals(350.0, next.fills.first().kmBefore, 0.0)
        assertEquals(0.95, LpgRules.price(s, next.fills)!!, 0.0)
        assertEquals(1.1, LpgRules.price(s.copy(pricePerL = 1.1), next.fills)!!, 0.0)
    }

    @Test
    fun autoCountsLpgOnceTheEngineIsWarm() {
        assertFalse(LpgRules.burning(s, onLpg = true, coolantC = 20, tripKm = 10.0))
        assertTrue(LpgRules.burning(s, onLpg = true, coolantC = 40, tripKm = 0.1))
        // Without an adapter: the first kilometres are on petrol.
        assertFalse(LpgRules.burning(s, onLpg = true, coolantC = null, tripKm = 0.5))
        assertTrue(LpgRules.burning(s, onLpg = true, coolantC = null, tripKm = 1.5))
        assertTrue(LpgRules.burning(s.copy(mode = LpgMode.ALWAYS), onLpg = false, coolantC = 10, tripKm = 0.0))
        assertFalse(LpgRules.burning(s.copy(mode = LpgMode.MANUAL), onLpg = false, coolantC = 90, tripKm = 9.0))
    }

    @Test
    fun settingsAndStateSurviveARestart() {
        val settings = LpgSettings(enabled = true, tankL = 42.5, useL100 = null, pricePerL = 0.99, mode = LpgMode.MANUAL, switchC = 30)
        assertEquals(settings, LpgRules.settingsFrom(LpgRules.settingsJson(settings)))
        val st = LpgRules.pumped(LpgRules.fill(LpgRules.drove(LpgRules.fill(LpgState(), 1, null), 120.0), 2, 0.9), 15.0).copy(onLpg = false)
        assertEquals(st, LpgRules.stateFrom(LpgRules.stateJson(st)))
    }
}
