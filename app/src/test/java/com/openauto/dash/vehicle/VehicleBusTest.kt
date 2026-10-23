package com.openauto.dash.vehicle

import com.openauto.dash.McuReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class VehicleBusTest {
    private var t = 0L
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val bus = VehicleBus({ t }, scope)

    private val canbox = Source("canbox", SourceKind.CANBOX, "Canbox")
    private val mcu = Source("mcu", SourceKind.MCU, "MCU")
    private val obd = Source("obd", SourceKind.OBD, "OBD")
    private val gps = Source("gps", SourceKind.GPS, "GPS")
    private val demo = Source("demo", SourceKind.DEMO, "Demo")
    private val learned = Source("learned", SourceKind.LEARNED, "Learned")
    private val computed = Source("computed", SourceKind.COMPUTED, "Computed")

    @After
    fun stop() = scope.cancel()

    private fun <T : Any> read(key: Key<T>): T? = bus.sample(key).value?.value
    private fun sourceOf(key: Key<*>): String? = bus.sample(key).value?.source?.id

    // --- Resolution -----------------------------------------------------------

    @Test
    fun lowerRankWins() {
        val o = bus.register(obd)
        val c = bus.register(canbox)
        o.put(K.RPM, 800.0)
        assertEquals("obd", sourceOf(K.RPM))
        c.put(K.RPM, 900.0)
        assertEquals("canbox", sourceOf(K.RPM))
        assertEquals(900.0, read(K.RPM)!!, 0.0)
    }

    @Test
    fun rankOrderIsDemoLearnedCarObdComputedGpsNetwork() {
        val kinds = SourceKind.values().sortedBy { it.rank }.map { it.name }
        assertEquals(listOf("DEMO", "LEARNED"), kinds.take(2))
        assertEquals(setOf("CANBOX", "MCU", "VEHICLE_APP"), kinds.subList(2, 5).toSet())
        assertEquals(listOf("OBD", "COMPUTED", "GPS", "NETWORK"), kinds.drop(5))
        // Learned beats the built-in decoding; demo beats everything.
        bus.register(canbox).put(K.VEHICLE_SPEED, 50.0)
        bus.register(learned).put(K.VEHICLE_SPEED, 51.0)
        assertEquals("learned", sourceOf(K.VEHICLE_SPEED))
        bus.register(demo).put(K.VEHICLE_SPEED, 99.0)
        assertEquals("demo", sourceOf(K.VEHICLE_SPEED))
    }

    @Test
    fun sameRankPrefersTheNewest() {
        val a = bus.register(canbox)
        val b = bus.register(mcu)
        a.put(K.RPM, 1.0, atMs = 10)
        b.put(K.RPM, 2.0, atMs = 20)
        assertEquals("mcu", sourceOf(K.RPM))
        a.put(K.RPM, 3.0, atMs = 30)
        assertEquals("canbox", sourceOf(K.RPM))
    }

    @Test
    fun degradedLosesFromAnyRank() {
        bus.register(canbox).put(K.BATTERY_VOLTS, 11.0, Quality.DEGRADED)
        bus.register(gps).put(K.BATTERY_VOLTS, 12.5)
        assertEquals("gps", sourceOf(K.BATTERY_VOLTS))
        // Degraded is still better than nothing.
        bus.register(gps).put(K.BATTERY_VOLTS, null)
        assertEquals("canbox", sourceOf(K.BATTERY_VOLTS))
        assertEquals(Quality.DEGRADED, bus.sample(K.BATTERY_VOLTS).value!!.quality)
    }

    @Test
    fun goodBeatsEstimatedAtTheSameRankAndEstimatedBeatsDegraded() {
        bus.register(canbox).put(K.GEAR, Gear.Drive, Quality.ESTIMATED)
        bus.register(mcu).put(K.GEAR, Gear.Neutral, Quality.GOOD, atMs = -5)
        assertEquals(Gear.Neutral, read(K.GEAR))
        bus.register(mcu).put(K.GEAR, Gear.Neutral, Quality.DEGRADED)
        assertEquals(Gear.Drive, read(K.GEAR))
    }

    @Test
    fun estimateIsJustASourceAndTheCarOutranksIt() {
        val est = bus.register(computed)
        est.put(K.GEAR, Gear.Forward(3), Quality.ESTIMATED)
        assertEquals(Gear.Forward(3), read(K.GEAR))
        bus.register(canbox).put(K.GEAR, Gear.Park)
        assertEquals(Gear.Park, read(K.GEAR))
    }

    // --- Driver choice --------------------------------------------------------

    @Test
    fun driverChoiceBeatsRankAndFallsBackWhenTheChosenSourceDrops() {
        val c = bus.register(canbox)
        val o = bus.register(obd)
        c.put(K.COOLANT_TEMP, 90.0)
        o.put(K.COOLANT_TEMP, 85.0)
        assertEquals("canbox", sourceOf(K.COOLANT_TEMP))
        bus.choose(K.COOLANT_TEMP, "obd")
        assertEquals("obd", sourceOf(K.COOLANT_TEMP))
        o.up(false)
        assertEquals("canbox", sourceOf(K.COOLANT_TEMP))
        o.up(true)
        assertEquals("obd", sourceOf(K.COOLANT_TEMP))
        o.put(K.COOLANT_TEMP, null)
        assertEquals("canbox", sourceOf(K.COOLANT_TEMP))
        bus.choose(K.COOLANT_TEMP, null)
        o.put(K.COOLANT_TEMP, 86.0)
        assertEquals("canbox", sourceOf(K.COOLANT_TEMP))
    }

    @Test
    fun choiceCanBeMadeBeforeTheSourceExists() {
        bus.choose(K.RPM, "obd")
        bus.register(canbox).put(K.RPM, 1000.0)
        assertEquals("canbox", sourceOf(K.RPM))
        bus.register(obd).put(K.RPM, 1010.0)
        assertEquals("obd", sourceOf(K.RPM))
    }

    // --- Expiry, withdraw, down/up ---------------------------------------------

    @Test
    fun measurementExpiresAfterItsTtl() {
        val o = bus.register(obd)
        o.put(K.RPM, 800.0)                 // ttl 3 s
        t = 3_000
        bus.expireNow()
        assertEquals(800.0, read(K.RPM)!!, 0.0)
        t = 3_001
        bus.expireNow()
        assertNull(read(K.RPM))
        assertNull(bus.now(K.RPM))
    }

    @Test
    fun expiredValueFallsBackToALowerSource() {
        bus.register(canbox).put(K.RPM, 800.0)
        t = 2_000
        bus.register(obd).put(K.RPM, 810.0)
        t = 3_500                            // canbox is 3.5 s old, obd 1.5 s
        bus.expireNow()
        assertEquals("obd", sourceOf(K.RPM))
    }

    @Test
    fun statesCountersAndInfoDoNotExpire() {
        val info = Key.Info<String>("vin", Domain.IDENTITY, Criticality.DISPLAY)
        val c = bus.register(canbox)
        c.put(K.REVERSE, true)
        c.put(K.ODOMETER, 1234.0)
        c.put(info, "VIN123")
        t = 10 * 3_600_000L
        bus.expireNow()
        assertEquals(true, read(K.REVERSE))
        assertEquals(1234.0, read(K.ODOMETER)!!, 0.0)
        assertEquals("VIN123", read(info))
    }

    @Test
    fun putNullWithdraws() {
        val c = bus.register(canbox)
        val o = bus.register(obd)
        c.put(K.RPM, 800.0)
        o.put(K.RPM, 810.0)
        c.put(K.RPM, null)
        assertEquals("obd", sourceOf(K.RPM))
        o.put(K.RPM, null)
        assertNull(read(K.RPM))
    }

    @Test
    fun sourceDownDropsAllItsValuesAndUpBringsThemBack() {
        val c = bus.register(canbox)
        c.put(K.REVERSE, true)
        c.put(K.HANDBRAKE, true)
        c.up(false)
        assertNull(read(K.REVERSE))
        assertNull(read(K.HANDBRAKE))
        assertFalse(bus.has(K.REVERSE).value)
        c.up(true)
        assertEquals(true, read(K.REVERSE))
        assertEquals(true, read(K.HANDBRAKE))
    }

    @Test
    fun valueRestoredByUpStillExpires() {
        val c = bus.register(canbox)
        c.put(K.RPM, 800.0)
        c.up(false)
        t = 10_000
        c.up(true)
        assertNull(read(K.RPM))
    }

    // --- BestOf -----------------------------------------------------------------

    @Test
    fun bestOfPrefersTheFirstMemberAndFallsBackAndRecovers() {
        val c = bus.register(canbox)
        val g = bus.register(gps)
        g.put(K.GPS_SPEED, 40.0)
        assertEquals(40.0, read(K.SPEED)!!, 0.0)
        assertEquals("gps", sourceOf(K.SPEED))
        c.put(K.VEHICLE_SPEED, 42.0)
        assertEquals(42.0, read(K.SPEED)!!, 0.0)
        assertEquals("canbox", sourceOf(K.SPEED))
        c.put(K.VEHICLE_SPEED, null)                 // recovery is the other direction
        assertEquals(40.0, read(K.SPEED)!!, 0.0)
        c.put(K.VEHICLE_SPEED, 43.0)
        assertEquals(43.0, read(K.SPEED)!!, 0.0)
    }

    @Test
    fun bestOfFallsBackWhenTheFirstMemberExpires() {
        bus.register(canbox).put(K.VEHICLE_SPEED, 42.0)       // ttl 3 s
        bus.register(gps).put(K.GPS_SPEED, 40.0)              // ttl 5 s
        t = 3_500
        bus.expireNow()
        assertEquals(40.0, read(K.SPEED)!!, 0.0)
        t = 5_500
        bus.expireNow()
        assertNull(read(K.SPEED))
    }

    @Test
    fun ambientTempIsTheCarSensorThenTheForecast() {
        bus.register(Source("weather", SourceKind.NETWORK, "Weather")).put(K.FORECAST_TEMP, 7.0)
        assertEquals(7.0, read(K.AMBIENT_TEMP)!!, 0.0)
        bus.register(canbox).put(K.OUTSIDE_TEMP, 9.0)
        assertEquals(9.0, read(K.AMBIENT_TEMP)!!, 0.0)
    }

    @Test
    fun bestOfCannotBePublishedTo() {
        try {
            bus.register(canbox).put(K.SPEED, 1.0)
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun bestOfCandidatesAndHasFollowTheMembers() {
        assertFalse(bus.has(K.SPEED).value)
        bus.register(gps).put(K.GPS_SPEED, 40.0)
        assertTrue(bus.has(K.SPEED).value)
        bus.register(canbox).put(K.VEHICLE_SPEED, 41.0)
        assertEquals(listOf("canbox", "gps"), bus.candidates(K.SPEED).value.map { it.source.id })
    }

    // --- Pinned reads -----------------------------------------------------------

    @Test
    fun pinnedReadIgnoresRank() {
        bus.register(canbox).put(K.RPM, 900.0)
        bus.register(obd).put(K.RPM, 850.0)
        val pinned = bus.sample(K.RPM, from = SourceKind.OBD)
        assertEquals(850.0, pinned.value!!.value, 0.0)
        assertEquals("canbox", sourceOf(K.RPM))
        bus.choose(K.RPM, "canbox")                      // the driver's choice does not touch it either
        assertEquals("obd", pinned.value!!.source.id)
    }

    @Test
    fun pinnedReadIsNullWithoutThatKindAndStillExpires() {
        bus.register(canbox).put(K.RPM, 900.0)
        val pinned = bus.sample(K.RPM, from = SourceKind.OBD)
        assertNull(pinned.value)
        val o = bus.register(obd)
        o.put(K.RPM, 850.0)
        assertEquals(850.0, pinned.value!!.value, 0.0)
        t = 3_500
        bus.expireNow()
        assertNull(pinned.value)
    }

    @Test
    fun pinnedReadThroughBestOf() {
        bus.register(canbox).put(K.VEHICLE_SPEED, 42.0)
        bus.register(gps).put(K.GPS_SPEED, 40.0)
        assertEquals(40.0, bus.sample(K.SPEED, from = SourceKind.GPS).value!!.value, 0.0)
        assertNull(bus.sample(K.SPEED, from = SourceKind.OBD).value)
    }

    // --- Emit on change only ----------------------------------------------------

    @Test
    fun emitsOnlyOnValueSourceOrQualityChange() {
        val c = bus.register(canbox)
        val seen = ArrayList<Sample<Double>?>()
        val job = scope.launch { bus.sample(K.COOLANT_TEMP).collect { seen += it } }
        assertEquals(1, seen.size)                     // the initial null
        c.put(K.COOLANT_TEMP, 90.0, atMs = 1)
        assertEquals(2, seen.size)
        t = 100
        c.put(K.COOLANT_TEMP, 90.0, atMs = 100)        // a new timestamp alone
        assertEquals(2, seen.size)
        c.put(K.COOLANT_TEMP, 91.0, atMs = 101)        // value
        assertEquals(3, seen.size)
        c.put(K.COOLANT_TEMP, 91.0, Quality.DEGRADED, atMs = 102)  // quality
        assertEquals(4, seen.size)
        bus.register(mcu).put(K.COOLANT_TEMP, 91.0, atMs = 103)    // source, same value
        assertEquals(5, seen.size)
        job.cancel()
    }

    @Test
    fun valueFlowDistinctUntilChanged() {
        val c = bus.register(canbox)
        val seen = ArrayList<Double?>()
        val job = scope.launch { bus.value(K.COOLANT_TEMP).collect { seen += it } }
        c.put(K.COOLANT_TEMP, 90.0)
        bus.register(mcu).put(K.COOLANT_TEMP, 90.0)    // the source changes, the value does not
        c.put(K.COOLANT_TEMP, 92.0)
        assertEquals(listOf(null, 90.0, 92.0), seen)
        job.cancel()
    }

    @Test
    fun fastKeysAreConflatedToMaxHz() {
        val c = bus.register(canbox)
        val flow = bus.sample(K.RPM)                   // 10 Hz: 100 ms
        c.put(K.RPM, 1000.0)
        assertEquals(1000.0, flow.value!!.value, 0.0)
        t = 30
        c.put(K.RPM, 1010.0)
        t = 60
        c.put(K.RPM, 1020.0)
        assertEquals(1000.0, flow.value!!.value, 0.0)  // held back
        assertEquals(1020.0, bus.now(K.RPM)!!, 0.0)    // the snapshot is never behind
        t = 100
        bus.expireNow()
        assertEquals(1020.0, flow.value!!.value, 0.0)  // the latest wins
        t = 120
        c.put(K.RPM, 1030.0)
        assertEquals(1020.0, flow.value!!.value, 0.0)
        t = 200
        c.put(K.RPM, 1040.0)                           // past the period: at once
        assertEquals(1040.0, flow.value!!.value, 0.0)
    }

    @Test
    fun conflationDoesNotDelayAChangeOfSourceOrAWithdraw() {
        val c = bus.register(canbox)
        val o = bus.register(obd)
        val flow = bus.sample(K.RPM)
        c.put(K.RPM, 1000.0)
        t = 10
        c.put(K.RPM, null)
        assertNull(flow.value)
        o.put(K.RPM, 700.0)
        assertEquals("obd", flow.value!!.source.id)
        t = 20
        c.put(K.RPM, 1001.0)
        assertEquals("canbox", flow.value!!.source.id)
    }

    @Test
    fun conflatedValueSettlingBackToWhatIsShownEmitsNothing() {
        val c = bus.register(canbox)
        val flow = bus.sample(K.RPM)
        c.put(K.RPM, 1000.0)
        t = 10
        c.put(K.RPM, 1100.0)
        c.put(K.RPM, 1000.0)
        t = 150
        bus.expireNow()
        assertEquals(1000.0, flow.value!!.value, 0.0)
    }

    // --- Events -----------------------------------------------------------------

    @Test
    fun stateChangesProduceChangedEventsAndMeasurementsDoNot() {
        val events = ArrayList<VehicleEvent>()
        val job = scope.launch { bus.events.collect { events += it } }
        val c = bus.register(canbox)
        c.put(K.REVERSE, false)
        c.put(K.REVERSE, false)
        c.put(K.REVERSE, true)
        c.put(K.RPM, 800.0)
        bus.register(mcu).put(K.REVERSE, true)         // source changes, the value does not
        c.up(false)                                    // mcu still says true: still no change
        bus.register(mcu).put(K.REVERSE, null)
        assertEquals(
            listOf(
                VehicleEvent.Changed(K.REVERSE, false, canbox),
                VehicleEvent.Changed(K.REVERSE, true, canbox),
                VehicleEvent.Changed(K.REVERSE, null, null)
            ),
            events
        )
        job.cancel()
    }

    @Test
    fun structuredStateCarriesTheExistingType() {
        val closed = McuReader.DoorState()
        val open = closed.copy(frontLeft = true)
        val m = bus.register(mcu)
        m.put(K.DOORS, closed)
        m.put(K.DOORS, open)
        assertTrue(read(K.DOORS)!!.anyOpen)
    }

    // --- Counter ----------------------------------------------------------------

    @Test
    fun counterRejectsAValueBelowTheSameSourcesLast() {
        val c = bus.register(canbox)
        c.put(K.ODOMETER, 1000.0)
        c.put(K.ODOMETER, 1000.5)
        c.put(K.ODOMETER, 3.0)                          // glitch
        assertEquals(1000.5, read(K.ODOMETER)!!, 0.0)
        c.put(K.ODOMETER, 1000.5)
        c.put(K.ODOMETER, 1001.0)
        assertEquals(1001.0, read(K.ODOMETER)!!, 0.0)
    }

    @Test
    fun counterGlitchCheckIsPerSource() {
        bus.register(canbox).put(K.ODOMETER, 5000.0)
        val o = bus.register(obd)
        o.put(K.ODOMETER, 4000.0)                       // lower than canbox's, but obd's first
        assertEquals("canbox", sourceOf(K.ODOMETER))
        o.put(K.ODOMETER, 4001.0)
        assertEquals(4001.0, bus.candidates(K.ODOMETER).value.first { it.source.id == "obd" }.value, 0.0)
    }

    @Test
    fun counterCanBeRebaselinedByWithdrawing() {
        val c = bus.register(canbox)
        c.put(K.ODOMETER, 5000.0)
        c.put(K.ODOMETER, null)
        c.put(K.ODOMETER, 100.0)
        assertEquals(100.0, read(K.ODOMETER)!!, 0.0)
    }

    // --- Demand -----------------------------------------------------------------

    @Test
    fun wantedReflectsSubscribers() {
        assertTrue(bus.wanted.value.isEmpty())
        val job = scope.launch { bus.value(K.RPM).collect { } }
        assertEquals(setOf<Key<*>>(K.RPM), bus.wanted.value)
        val job2 = scope.launch { bus.value(K.THROTTLE).collect { } }
        assertEquals(setOf<Key<*>>(K.RPM, K.THROTTLE), bus.wanted.value)
        job.cancel()
        assertEquals(setOf<Key<*>>(K.THROTTLE), bus.wanted.value)
        job2.cancel()
        assertTrue(bus.wanted.value.isEmpty())
    }

    @Test
    fun publisherSeesTheSameWantedSet() {
        val p = bus.register(obd)
        val job = scope.launch { bus.value(K.RPM).collect { } }
        assertEquals(setOf<Key<*>>(K.RPM), p.wanted.value)
        job.cancel()
    }

    @Test
    fun subscribingToBestOfIsDemandForItsMembers() {
        val job = scope.launch { bus.value(K.SPEED).collect { } }
        assertEquals(setOf<Key<*>>(K.SPEED, K.VEHICLE_SPEED, K.GPS_SPEED), bus.wanted.value)
        job.cancel()
        assertTrue(bus.wanted.value.isEmpty())
    }

    @Test
    fun aPinnedReadCountsAsDemand() {
        val job = scope.launch { bus.value(K.RPM, from = SourceKind.OBD).collect { } }
        assertEquals(setOf<Key<*>>(K.RPM), bus.wanted.value)
        job.cancel()
        assertTrue(bus.wanted.value.isEmpty())
    }

    @Test
    fun pinnedReadThroughBestOfIsDemandForItsMembers() {
        val job = scope.launch { bus.value(K.SPEED, from = SourceKind.GPS).collect { } }
        assertEquals(setOf<Key<*>>(K.SPEED, K.VEHICLE_SPEED, K.GPS_SPEED), bus.wanted.value)
        job.cancel()
    }

    @Test
    fun holdIsStandingDemandUntilReleased() {
        val h1 = bus.hold(K.FUEL_LEVEL)
        val h2 = bus.hold(K.FUEL_LEVEL)
        assertEquals(setOf<Key<*>>(K.FUEL_LEVEL), bus.wanted.value)
        h1.release()
        h1.release()                                    // twice is harmless
        assertEquals(setOf<Key<*>>(K.FUEL_LEVEL), bus.wanted.value)
        h2.release()
        assertTrue(bus.wanted.value.isEmpty())
    }

    @Test
    fun holdOnBestOfWantsItsMembers() {
        val h = bus.hold(K.AMBIENT_TEMP)
        assertEquals(setOf<Key<*>>(K.AMBIENT_TEMP, K.OUTSIDE_TEMP, K.FORECAST_TEMP), bus.wanted.value)
        h.release()
        assertTrue(bus.wanted.value.isEmpty())
    }

    // --- has() ------------------------------------------------------------------

    @Test
    fun hasMeansSomeUpSourcePublishedIt() {
        val has = bus.has(K.DOORS)
        assertFalse(has.value)
        val m = bus.register(mcu)
        m.put(K.DOORS, McuReader.DoorState())
        assertTrue(has.value)
        m.up(false)
        assertFalse(has.value)
        m.up(true)
        assertTrue(has.value)
        m.put(K.DOORS, null)
        assertFalse(has.value)
    }

    @Test
    fun hasSurvivesAMeasurementGoingStale() {
        bus.register(obd).put(K.RPM, 800.0)
        t = 60_000
        bus.expireNow()
        assertNull(read(K.RPM))
        assertTrue(bus.has(K.RPM).value)
    }

    // --- Threading --------------------------------------------------------------

    @Test
    fun concurrentPutsFromSeveralThreads() {
        val threads = 8
        val perThread = 500
        val start = CountDownLatch(1)
        val ws = (0 until threads).map { n ->
            val p = bus.register(Source("s$n", SourceKind.CANBOX, "S$n"))
            thread {
                start.await()
                for (i in 1..perThread) {
                    p.put(K.ODOMETER, i.toDouble())
                    p.put(K.RPM, (n * 1000 + i).toDouble(), atMs = i.toLong())
                    p.put(K.REVERSE, i % 2 == 0)
                    bus.now(K.SPEED)
                }
            }
        }
        start.countDown()
        ws.forEach { it.join() }
        assertEquals(threads, bus.candidates(K.ODOMETER).value.size)
        assertTrue(bus.candidates(K.ODOMETER).value.all { it.value == perThread.toDouble() })
        assertEquals(threads, bus.candidates(K.RPM).value.size)
        assertEquals(true, read(K.REVERSE))
        assertEquals(perThread.toDouble(), read(K.ODOMETER)!!, 0.0)
    }

    @Test
    fun catalogueKeysAreUniqueAndBestOfsAreWiredToMembers() {
        assertEquals(K.all.size, K.all.map { it.id }.toSet().size)
        assertEquals(listOf<Key<*>>(K.VEHICLE_SPEED, K.GPS_SPEED), K.SPEED.members)
        assertEquals(Criticality.SAFETY, K.SPEED.criticality)
        assertEquals(3_000L, K.VEHICLE_SPEED.ttlMs)
        assertEquals(2 * 3_600_000L, K.OUTSIDE_TEMP.ttlMs)
    }
}
