package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LpgTankTest {
    private val s = LpgSettings(enabled = true, tankL = 40.0, useL100 = 10.0)
    private val engine = s.copy(method = LpgMethod.ENGINE, factor = 1.33)

    private fun fill(st: LpgState, now: Long, price: Double? = null, settings: LpgSettings = s) =
        LpgRules.fill(settings, st, now, price, petrolUse = 7.0)

    @Test
    fun noLevelBeforeTheFirstFullFill() {
        assertNull(LpgRules.reading(s, LpgState(), petrolUse = 7.0))
    }

    @Test
    fun theLevelFallsWithTheKilometresOnLpg() {
        val filled = fill(LpgState(), now = 1_000, price = 1.0)
        val r = LpgRules.reading(s, LpgRules.drove(filled, 200.0), petrolUse = 7.0)!!
        assertEquals(20.0, r.liters, 0.001)
        assertEquals(50, r.percent)
        assertEquals(200.0, r.rangeKm, 0.001)
        assertEquals(10.0, r.costPer100!!, 0.001)
        assertFalse(r.low)
        val empty = LpgRules.reading(s, LpgRules.drove(filled, 900.0), petrolUse = 7.0)!!
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
        var st = fill(LpgState(), now = 1)
        st = LpgRules.pumped(st, 30.0) // the first fill: nothing driven before it, so it teaches nothing
        st = fill(LpgRules.drove(st, 200.0), now = 2)
        assertEquals(20.0, LpgRules.suggestedLiters(st), 0.001)
        st = LpgRules.pumped(st, 24.0)
        assertNull(LpgRules.learnedUse(st.fills)) // 200 km is too few
        st = fill(LpgRules.drove(st, 200.0), now = 3)
        st = LpgRules.pumped(st, 24.0)
        assertEquals(12.0, LpgRules.learnedUse(st.fills)!!, 0.001)
        val (use, learned) = LpgRules.use(s, st.fills, petrolUse = 7.0)
        assertEquals(12.0, use, 0.001)
        assertTrue(learned)
    }

    @Test
    fun aFullFillResetsTheLevelTheWarningAndLpgOff() {
        val st = LpgRules.drove(fill(LpgState(), now = 1), 350.0).copy(warned = true, lpgOff = true)
        val next = fill(st, now = 2, price = 0.95)
        assertEquals(0.0, next.kmSinceFill, 0.0)
        assertFalse(next.warned)
        assertFalse(next.lpgOff)
        assertEquals(350.0, next.fills.first().kmBefore, 0.0)
        assertEquals(0.95, LpgRules.price(s, next.fills)!!, 0.0)
        assertEquals(1.1, LpgRules.price(s.copy(pricePerL = 1.1), next.fills)!!, 0.0)
    }

    @Test
    fun autoCountsLpgOnceTheEngineIsWarm() {
        val st = LpgState()
        assertEquals(LpgStatus.WARMING, LpgRules.status(s, st, coolantC = 20, tripKm = 10.0))
        assertEquals(LpgStatus.LPG, LpgRules.status(s, st, coolantC = 40, tripKm = 0.1))
        // Without an adapter: the first kilometre is on petrol.
        assertEquals(LpgStatus.WARMING, LpgRules.status(s, st, coolantC = null, tripKm = 0.5))
        assertEquals(LpgStatus.LPG, LpgRules.status(s, st, coolantC = null, tripKm = 1.5))
        assertEquals(LpgStatus.LPG, LpgRules.status(s.copy(mode = LpgMode.ALWAYS), st, coolantC = 10, tripKm = 0.0))
        assertEquals(LpgStatus.PETROL, LpgRules.status(s.copy(mode = LpgMode.MANUAL), st.copy(onLpg = false), coolantC = 90, tripKm = 9.0))
    }

    @Test
    fun theDriverCanSayLpgIsOffInAnyMode() {
        val warm = LpgState()
        val off = LpgRules.toggle(s, warm)
        assertTrue(off.lpgOff)
        assertEquals(LpgStatus.LPG_OFF, LpgRules.status(s, off, coolantC = 90, tripKm = 9.0))
        assertEquals(LpgStatus.LPG_OFF, LpgRules.status(s.copy(mode = LpgMode.ALWAYS), off, coolantC = 90, tripKm = 9.0))
        assertEquals(LpgStatus.LPG, LpgRules.status(s, LpgRules.toggle(s, off), coolantC = 90, tripKm = 9.0))
        // In Manual the tap flips LPG and petrol instead.
        val manual = s.copy(mode = LpgMode.MANUAL)
        assertEquals(LpgStatus.PETROL, LpgRules.status(manual, LpgRules.toggle(manual, warm), coolantC = 90, tripKm = 9.0))
    }

    @Test
    fun theEngineMethodBurnsThePetrolDemandTimesTheFactor() {
        // 100 km with the engine asking 9 L of petrol, then 10 km without the reading.
        val st = LpgRules.drove(LpgRules.drove(fill(LpgState(), now = 1, settings = engine), 100.0, petrolL = 9.0), 10.0)
        assertEquals(9.0 * 1.33 + 10 * 10.0 / 100, LpgRules.used(engine, st, petrolUse = 7.0), 0.001)
        val r = LpgRules.reading(engine, st, petrolUse = 7.0)!!
        assertEquals(40 - 12.97, r.liters, 0.001)
        assertEquals(12.97 / 110 * 100, r.useL100, 0.01)
        assertFalse(r.learned)
        // The same drive by the average: 110 km at 10 L/100.
        assertEquals(11.0, LpgRules.used(s, st, petrolUse = 7.0), 0.001)
    }

    @Test
    fun theLitresPumpedTeachTheFactor() {
        var st = fill(LpgState(), now = 1, settings = engine)
        // 300 km: 27 L of petrol asked for, all with the reading; 36 L pumped.
        st = fill(LpgRules.drove(st, 300.0, petrolL = 27.0), now = 2, settings = engine)
        assertEquals(27.0 * 1.33, LpgRules.suggestedLiters(st), 0.001)
        st = LpgRules.pumped(st, 36.0)
        assertEquals(36.0 / 27.0, LpgRules.learnedFactor(st.fills, use = 10.0)!!, 0.001)
        val (factor, learned) = LpgRules.factor(engine, st.fills, use = 10.0)
        assertEquals(1.333, factor, 0.001)
        assertTrue(learned)
        // Too little petrol behind it: the driver's factor stays.
        assertNull(LpgRules.learnedFactor(listOf(LpgFill(1, 50.0, liters = 6.0, petrolBefore = 4.5)), use = 10.0))
    }

    @Test
    fun fuelFlowIsReadFromTheFuelRateOrTheAirFlow() {
        assertEquals(6.4, ObdParser.parseFuelRate("41 5E 00 80")!!, 0.001) // 128 / 20
        // 10 g/s of air: 3600 g/h of air / 14.7 / 745 g/L = 3.29 L/h of petrol.
        assertEquals(3.287, ObdParser.parseMafAsPetrol("41 10 03 E8")!!, 0.001)
        assertNull(ObdParser.parseFuelRate("NO DATA"))
    }

    @Test
    fun settingsAndStateSurviveARestart() {
        val settings = LpgSettings(
            enabled = true, tankL = 42.5, useL100 = null, pricePerL = 0.99, mode = LpgMode.MANUAL, switchC = 30,
            method = LpgMethod.ENGINE, factor = 1.33
        )
        assertEquals(settings, LpgRules.settingsFrom(LpgRules.settingsJson(settings)))
        val st = LpgRules.pumped(fill(LpgRules.drove(fill(LpgState(), 1), 120.0, petrolL = 8.0), 2, 0.9), 15.0).copy(onLpg = false, lpgOff = true)
        assertEquals(st, LpgRules.stateFrom(LpgRules.stateJson(st)))
    }
}
