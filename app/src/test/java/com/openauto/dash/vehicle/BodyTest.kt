package com.openauto.dash.vehicle

import com.openauto.dash.CarSignal
import com.openauto.dash.LearnedDoors
import com.openauto.dash.McuReader.DoorState
import com.openauto.dash.builtInDoors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The body, fuel and environment on the bus: the learned doors' merge, fuel by rank, the ambient temperature. */
class BodyTest {
    private var t = 0L
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val bus = VehicleBus({ t }, scope)

    private val mcu = bus.register(Source("qf.mcu", SourceKind.MCU, "MCU"))
    private val carApp = bus.register(Source("qf.car_app", SourceKind.VEHICLE_APP, "Car app"))
    private val unitBus = bus.register(Source("unit.bus", SourceKind.CANBOX, "Unit bus"))
    private val learned = bus.register(Source("learned", SourceKind.LEARNED, "Signal Finder"))
    private val learnedCan = bus.register(Source("learned.can", SourceKind.LEARNED, "Learned fuel"))
    private val obd = bus.register(Source("obd", SourceKind.OBD, "OBD"))
    private val broadcast = bus.register(Source("qf.broadcast", SourceKind.VEHICLE_APP, "Broadcasts"))
    private val weather = bus.register(Source("weather", SourceKind.NETWORK, "Weather"))

    @After
    fun stop() = scope.cancel()

    private fun doors() = bus.sample(K.DOORS).value

    // --- Doors: a learned door overrides the car's own, door by door -------------------------------------

    @Test
    fun aLearnedDoorIsLaidOverTheCarsOwnDoors() {
        val merge = LearnedDoors(learned)
        scope.launch { merge.follow(bus) }
        mcu.put(K.DOORS, DoorState(frontLeft = true, bonnet = true))
        assertEquals(DoorState(frontLeft = true, bonnet = true), doors()!!.value)

        merge.learned(mapOf(CarSignal.REAR_LEFT to true, CarSignal.FRONT_LEFT to false))
        // The learned view wins as a whole, and keeps the doors the driver did not teach (the bonnet).
        assertEquals(DoorState(frontLeft = false, rearLeft = true, bonnet = true), doors()!!.value)
        assertEquals("learned", doors()!!.source.id)
    }

    @Test
    fun theMergedViewFollowsTheCarsOwnDoors() {
        val merge = LearnedDoors(learned)
        scope.launch { merge.follow(bus) }
        merge.learned(mapOf(CarSignal.TAILGATE to true))
        // No door from the car yet: the learned one alone.
        assertEquals(DoorState(tailgate = true), doors()!!.value)
        // The car's own doors move, the learned one stays on top of them.
        carApp.put(K.DOORS, DoorState(rearRight = true))
        assertEquals(DoorState(rearRight = true, tailgate = true), doors()!!.value)
        // Another built-in source that says it later takes over as the car's own view.
        t += 10
        unitBus.put(K.DOORS, DoorState(frontRight = true))
        assertEquals(DoorState(frontRight = true, tailgate = true), doors()!!.value)
    }

    @Test
    fun withNoLearnedDoorTheCarsOwnStandAlone() {
        val merge = LearnedDoors(learned)
        scope.launch { merge.follow(bus) }
        mcu.put(K.DOORS, DoorState(frontLeft = true))
        merge.learned(mapOf(CarSignal.REAR_LEFT to true))
        assertEquals("learned", doors()!!.source.id)
        // The driver forgets it: the car's own doors are the answer again, from their own source.
        merge.learned(emptyMap())
        assertEquals("qf.mcu", doors()!!.source.id)
        assertEquals(DoorState(frontLeft = true), doors()!!.value)
        // Other learned signals (reverse, headlights) are not doors.
        merge.learned(mapOf(CarSignal.REVERSE to true))
        assertEquals("qf.mcu", doors()!!.source.id)
    }

    @Test
    fun theBuiltInDoorsAreNeverTheLearnedOrTheDemosOwn() {
        val demo = bus.register(Source("demo", SourceKind.DEMO, "Demo"))
        learned.put(K.DOORS, DoorState(bonnet = true))
        demo.put(K.DOORS, DoorState(tailgate = true))
        assertNull(builtInDoors(bus.candidates(K.DOORS).value))
        carApp.put(K.DOORS, DoorState(frontLeft = true))
        assertEquals(DoorState(frontLeft = true), builtInDoors(bus.candidates(K.DOORS).value))
    }

    // --- Fuel ---------------------------------------------------------------------------------------------

    @Test
    fun fuelLearnedBeatsTheCarAppBeatsTheAdapter() {
        obd.put(K.FUEL_LEVEL, 40.0)
        assertEquals("obd", bus.sample(K.FUEL_LEVEL).value!!.source.id)
        carApp.put(K.FUEL_LEVEL, 55.0, ttlMs = FUEL_HOLD_MS)
        assertEquals("qf.car_app", bus.sample(K.FUEL_LEVEL).value!!.source.id)
        learnedCan.put(K.FUEL_LEVEL, 58.0, ttlMs = FUEL_HOLD_MS)
        assertEquals(58.0, bus.sample(K.FUEL_LEVEL).value!!.value, 0.0)
        // The car app says it again, later: the driver's own reading still stands.
        t += 1_000
        carApp.put(K.FUEL_LEVEL, 54.0, ttlMs = FUEL_HOLD_MS)
        assertEquals("learned.can", bus.sample(K.FUEL_LEVEL).value!!.source.id)
    }

    @Test
    fun aCarSaysItsFuelOnAChangeSoItIsBelievedForHours() {
        carApp.put(K.FUEL_LEVEL, 55.0, ttlMs = FUEL_HOLD_MS)
        carApp.put(K.RANGE, 480.0, ttlMs = FUEL_HOLD_MS)
        t += 5 * 3_600_000L
        bus.expireNow()
        assertEquals(55.0, bus.sample(K.FUEL_LEVEL).value!!.value, 0.0)
        assertEquals(480.0, bus.sample(K.RANGE).value!!.value, 0.0)
        t += 2 * 3_600_000L
        bus.expireNow()
        assertNull(bus.sample(K.FUEL_LEVEL).value)
    }

    @Test
    fun theAdaptersZeroIsNoReading() {
        obd.put(K.FUEL_LEVEL, 0.0)
        val level = bus.sample(K.FUEL_LEVEL).value
        assertNull(usableLevelPct(level))
        assertNull(fuelInfoOf(level, null))
        // The car's own 0 is dropped by its provider; one that is kept (no range) is a real empty tank.
        carApp.put(K.FUEL_LEVEL, 0.0)
        assertEquals(0, usableLevelPct(bus.sample(K.FUEL_LEVEL).value))
    }

    @Test
    fun theFuelsSourceNamesTheAdapterOnlyForTheAdapter() {
        carApp.put(K.RANGE, 400.0)
        obd.put(K.FUEL_LEVEL, 50.0)
        assertEquals("OBD", fuelInfoOf(bus.sample(K.FUEL_LEVEL).value, bus.sample(K.RANGE).value)!!.source)
        carApp.put(K.FUEL_LEVEL, 50.0)
        assertEquals("CANbox", fuelInfoOf(bus.sample(K.FUEL_LEVEL).value, bus.sample(K.RANGE).value)!!.source)
    }

    // --- The outside temperature ----------------------------------------------------------------------------

    @Test
    fun theCarsSensorBeatsTheForecastAndTheForecastFillsIn() {
        weather.put(K.FORECAST_TEMP, 18.0)
        assertEquals(18.0, bus.sample(K.AMBIENT_TEMP).value!!.value, 0.0)
        broadcast.put(K.OUTSIDE_TEMP, 21.5)
        assertEquals(21.5, bus.sample(K.AMBIENT_TEMP).value!!.value, 0.0)
        // The forecast is still the forecast.
        assertEquals(18.0, bus.sample(K.FORECAST_TEMP).value!!.value, 0.0)
    }

    @Test
    fun aRestoredTemperatureRunsOutWhenItsTwoHoursWould() {
        // Heard 90 minutes ago, before a restart: half an hour of its 2 h are left.
        t = 10 * 3_600_000L
        broadcast.put(K.OUTSIDE_TEMP, 12.5, atMs = broadcast.clock() - 90 * 60_000L)
        assertEquals(12.5, bus.sample(K.OUTSIDE_TEMP).value!!.value, 0.0)
        t += 31 * 60_000L
        bus.expireNow()
        assertNull(bus.sample(K.OUTSIDE_TEMP).value)
    }

    @Test
    fun aForecastAgesFromItsFetchNotFromItsPublishing() {
        t = 5 * 3_600_000L
        weather.put(K.FORECAST_TEMP, 9.0, atMs = weather.clock() - 2 * 3_600_000L - 50 * 60_000L)
        assertNotNull(bus.sample(K.FORECAST_TEMP).value)
        t += 11 * 60_000L
        bus.expireNow()
        assertNull(bus.sample(K.FORECAST_TEMP).value)
    }

    // --- Headlights, reverse: by rank --------------------------------------------------------------------------

    @Test
    fun aLearnedReverseOutranksTheCarsOwn() {
        carApp.put(K.REVERSE, false)
        learned.put(K.REVERSE, true)
        assertEquals(true, bus.sample(K.REVERSE).value!!.value)
        learned.put(K.REVERSE, null)
        assertEquals(false, bus.sample(K.REVERSE).value!!.value)
    }
}
