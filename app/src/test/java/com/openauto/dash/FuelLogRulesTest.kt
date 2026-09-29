package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelLogRulesTest {
    /** Feeds [levels] one every 10 s from [start]; returns the gauge and every refuel seen. */
    private fun feed(levels: List<Int>, start: FuelLogRules.Gauge = FuelLogRules.Gauge(), t0: Long = 0L): Pair<FuelLogRules.Gauge, List<FuelLogRules.Rise>> {
        var g = start
        val rises = ArrayList<FuelLogRules.Rise>()
        levels.forEachIndexed { i, p ->
            val (next, rise) = FuelLogRules.step(g, p, t0 + i * 10_000L)
            g = next
            rise?.let(rises::add)
        }
        return g to rises
    }

    @Test
    fun theFirstSettledLevelIsTheBaselineNotARefuel() {
        val (g, rises) = feed(listOf(30, 30, 30, 30))
        assertEquals(30, g.baseline)
        assertEquals(emptyList<FuelLogRules.Rise>(), rises)
    }

    @Test
    fun aJumpAfterTheEngineWasOffIsARefuel() {
        // Parked at 20 %, filled up while the unit slept, read at 85 % on the next start.
        val (_, rises) = feed(listOf(85, 85, 86, 85, 85), start = FuelLogRules.Gauge(baseline = 20))
        assertEquals(listOf(FuelLogRules.Rise(20, 85)), rises)
        assertEquals(39.0, FuelLogRules.liters(rises.single(), 60.0), 0.01)
    }

    @Test
    fun theGaugeWobblingOnASlopeIsNoRefuel() {
        val (g, rises) = feed(listOf(40, 44, 38, 45, 41, 40, 40, 40, 40), start = FuelLogRules.Gauge(baseline = 40))
        assertEquals(emptyList<FuelLogRules.Rise>(), rises)
        // Settled within the gauge's own wobble of where it was.
        assert(g.baseline in 39..42)
    }

    @Test
    fun aLevelIsOnlyTrustedOnceItSettled() {
        // Two reads of 90 then it moves on: not settled, no refuel.
        val (_, rises) = feed(listOf(90, 90, 50, 50, 50, 50), start = FuelLogRules.Gauge(baseline = 50))
        assertEquals(emptyList<FuelLogRules.Rise>(), rises)
    }

    @Test
    fun theLevelGoingDownFollowsTheBaseline() {
        val (g, _) = feed(listOf(60, 60, 60, 60, 55, 55, 55, 55), start = FuelLogRules.Gauge(baseline = 60))
        assertEquals(55, g.baseline)
    }

    @Test
    fun thePumpPriceComesFromTheStationWhereTheCarStands() {
        val near = FuelStation(1, "Rue A", "Beauvais", 49.4300, 2.0800, mapOf(FuelGrade.entries.first() to 1.789), "Total")
        val far = FuelStation(2, "Rue B", "Beauvais", 49.4400, 2.0800, mapOf(FuelGrade.entries.first() to 1.699))
        val hit = FuelLogRules.stationAt(listOf(far, near), 49.4301, 2.0801, listOf(FuelGrade.entries.first()))
        assertEquals(1.789, hit!!.second, 0.0)
        assertNull(FuelLogRules.stationAt(listOf(far), 49.4301, 2.0801, listOf(FuelGrade.entries.first())))
    }

    @Test
    fun onMyWayTellsTheArrivalAndWhereWhenKnown() {
        val strings: (Int, Array<Any>) -> String = { id, args ->
            when (id) {
                R.string.onmyway_text_to -> "to ${args[0]} at ${args[1]}"
                R.string.onmyway_text_eta -> "at ${args[0]}"
                else -> "on my way"
            }
        }
        val time: (Long) -> String = { "18:40" }
        assertEquals("to Mum at 18:40", OnMyWayText.of(1L, "Mum", time, strings))
        assertEquals("at 18:40", OnMyWayText.of(1L, null, time, strings))
        assertEquals("on my way", OnMyWayText.of(null, "Mum", time, strings))
    }
}
