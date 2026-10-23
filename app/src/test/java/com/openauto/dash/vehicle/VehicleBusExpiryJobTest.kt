package com.openauto.dash.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The expiry job runs on the injected scope and clock. Real (short) time is used so the
 * tests need no extra dependency: a 300 ms key, and the 10 Hz conflation of a fast one.
 */
class VehicleBusExpiryJobTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val start = System.nanoTime()
    private val bus = VehicleBus({ (System.nanoTime() - start) / 1_000_000 }, scope)
    private val obd = Source("obd", SourceKind.OBD, "OBD")

    @After
    fun stop() = scope.cancel()

    private fun waitFor(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val until = System.nanoTime() + ms * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < until) { "timed out: $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun theJobExpiresAValueWithNoHelp() {
        val quick = Key.Measurement<Double>("quick", Domain.MOTION, Criticality.DISPLAY, ttlMs = 300)
        bus.register(obd).put(quick, 1.0)
        val flow = bus.sample(quick)
        assertNotNull(flow.value)
        waitFor("the value to expire") { flow.value == null }
        assertNull(bus.now(quick))
    }

    @Test
    fun theJobFlushesAConflatedValue() {
        val p = bus.register(obd)
        val flow = bus.sample(K.RPM)               // 10 Hz
        p.put(K.RPM, 800.0)
        p.put(K.RPM, 850.0)                        // inside the 100 ms window: held back
        waitFor("the held value to show") { flow.value?.value == 850.0 }
        assertEquals(850.0, flow.value!!.value, 0.0)
    }
}
