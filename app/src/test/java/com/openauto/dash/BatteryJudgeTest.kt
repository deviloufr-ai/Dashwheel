package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The bar's battery alert: steady problems only, never a single reading. */
class BatteryJudgeTest {

    /** Feeds one reading a second from [fromS] to [toS] (inclusive); returns the last state. */
    private fun BatteryJudge.run(fromS: Int, toS: Int, volts: (Int) -> Double, rpm: Int = 850, fromEcu: Boolean = true): BatteryState {
        var last = BatteryState()
        for (s in fromS..toS) last = feed(rpm, volts(s), fromEcu, s * 1000L)
        return last
    }

    @Test
    fun aHealthyChargeRaisesNothing() {
        val judge = BatteryJudge()
        assertNull(judge.run(0, 120, { 14.4 }).level)
    }

    @Test
    fun oneSpikeIsIgnored() {
        // The screenshot's case: a single 16.8 V sample among 14.5 V ones.
        val judge = BatteryJudge()
        val state = judge.run(0, 90, { if (it == 60) 16.8 else 14.5 })
        assertNull(state.level)
    }

    @Test
    fun theAdaptersOwnVoltageNeverAlerts() {
        // ATRV from a clone adapter, reading 15.8 V all along: not trusted.
        val judge = BatteryJudge()
        assertNull(judge.run(0, 120, { 15.8 }, fromEcu = false).level)
    }

    @Test
    fun theCrankingDipIsIgnored() {
        // Glow plugs and the starter: 11 V for the first 20 s, then a normal charge.
        val judge = BatteryJudge()
        assertNull(judge.run(0, 90, { if (it < 20) 11.0 else 14.3 }).level)
    }

    @Test
    fun aSteadyLowChargeIsCritical() {
        // Alternator gone: 11.8 V held after the start-up grace.
        val judge = BatteryJudge()
        val state = judge.run(0, 70, { 11.8 })
        assertEquals(AlertLevel.CRITICAL, state.level)
        assertEquals(11.8, state.volts, 0.001)
    }

    @Test
    fun aSteadyOverchargeIsAWarningThenCritical() {
        val judge = BatteryJudge()
        assertEquals(AlertLevel.WARNING, judge.run(0, 70, { 15.2 }).level)
        assertEquals(AlertLevel.CRITICAL, judge.run(71, 110, { 15.8 }).level)
    }

    @Test
    fun smartChargingDipsAreNormal() {
        // e-HDi smart charging holds 12.6–13 V on purpose.
        val judge = BatteryJudge()
        assertNull(judge.run(0, 120, { 12.7 }).level)
    }

    @Test
    fun theEngineOffRaisesNothingButKeepsTheTripRange() {
        val judge = BatteryJudge()
        judge.run(0, 60, { if (it == 45) 14.9 else 14.2 })
        val off = judge.run(61, 90, { 11.9 }, rpm = 0)
        assertNull(off.level)
        assertEquals(14.2, off.tripMin!!, 0.001)
        assertEquals(14.9, off.tripMax!!, 0.001)
    }

    @Test
    fun aNewStartIsANewTrip() {
        val judge = BatteryJudge()
        judge.run(0, 60, { 14.8 })
        judge.run(61, 70, { 12.4 }, rpm = 0)
        val second = judge.run(71, 140, { 14.1 })
        assertEquals(14.1, second.tripMin!!, 0.001)
        assertEquals(14.1, second.tripMax!!, 0.001)
    }
}
