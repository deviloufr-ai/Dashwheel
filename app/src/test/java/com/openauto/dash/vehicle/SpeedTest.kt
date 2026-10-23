package com.openauto.dash.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedTest {
    private val car = Source("obd", SourceKind.OBD, "OBD")
    private val gps = Source("gps", SourceKind.GPS, "GPS")
    private val plus3: (Int) -> Int = { if (it < 15) it else it + 3 }

    private fun sample(kmh: Double, source: Source) = Sample(kmh, source, 0L)

    // --- displaySpeedKmh -----------------------------------------------------

    @Test
    fun unknownStaysUnknown() = assertNull(displaySpeedKmh(null, plus3))

    @Test
    fun aCarSpeedIsShownAsItComes() = assertEquals(92, displaySpeedKmh(sample(92.0, car), plus3))

    @Test
    fun aGpsSpeedGetsTheGpsCorrection() = assertEquals(63, displaySpeedKmh(sample(60.0, gps), plus3))

    @Test
    fun aStoppedGpsStaysStopped() = assertEquals(1, displaySpeedKmh(sample(1.0, gps), plus3))

    @Test
    fun everyCarKindIsLeftAlone() {
        for (kind in listOf(SourceKind.CANBOX, SourceKind.VEHICLE_APP, SourceKind.MCU, SourceKind.OBD, SourceKind.DEMO)) {
            assertEquals(kind.name, 60, displaySpeedKmh(sample(60.0, Source("x", kind, "x")), plus3))
        }
    }

    @Test
    fun theValueIsRoundedToWholeKmh() {
        assertEquals(51, displaySpeedKmh(sample(50.6, car), plus3))
        assertEquals(50, displaySpeedKmh(sample(50.4, car), plus3))
    }

    // --- The bus's resolution of SPEED (what the display helper is handed) ------

    private var t = 0L
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val bus = VehicleBus({ t }, scope)

    @After
    fun stop() = scope.cancel()

    @Test
    fun theCarBeatsTheGpsAndTheGpsCoversForIt() {
        val c = bus.register(car)
        val g = bus.register(gps)
        g.put(K.GPS_SPEED, 58.0)
        assertEquals("gps", bus.sample(K.SPEED).value!!.source.id)
        c.put(K.VEHICLE_SPEED, 60.0)
        assertEquals("obd", bus.sample(K.SPEED).value!!.source.id)
        assertEquals(60, displaySpeedKmh(bus.sample(K.SPEED).value, plus3))
        c.put(K.VEHICLE_SPEED, null)
        assertEquals(61, displaySpeedKmh(bus.sample(K.SPEED).value, plus3))
        g.put(K.GPS_SPEED, null)
        assertNull(displaySpeedKmh(bus.sample(K.SPEED).value, plus3))
    }
}
