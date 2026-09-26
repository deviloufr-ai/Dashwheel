package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The TPMS app's tyres, as Dashwheel reads and judges them. */
class TyresTest {

    private fun tyre(kPa: Int, celsius: Int = 25, leak: Boolean = false, noSignal: Boolean = false) =
        Tyre(kPa, celsius, sensorBatteryLow = false, leak = leak, noSignal = noSignal, at = 0)

    @Test
    fun theTpmsAppsTyreNumbers() {
        assertEquals(TyrePos.REAR_LEFT, tyrePosition(0))
        assertEquals(TyrePos.FRONT_LEFT, tyrePosition(1))
        assertEquals(TyrePos.FRONT_RIGHT, tyrePosition(2))
        assertEquals(TyrePos.REAR_RIGHT, tyrePosition(3))
        assertEquals(TyrePos.SPARE, tyrePosition(5))
        assertNull(tyrePosition(4))
    }

    @Test
    fun problemsWithTheTpmsAppsDefaults() {
        assertNull(tyreProblem(tyre(230)))
        assertEquals(TyreProblem.LOW, tyreProblem(tyre(TYRE_LOW_KPA)))
        assertEquals(TyreProblem.HIGH, tyreProblem(tyre(TYRE_HIGH_KPA)))
        assertEquals(TyreProblem.HOT, tyreProblem(tyre(230, celsius = TYRE_HOT_C)))
        // A leak is the worst, whatever the pressure says.
        assertEquals(TyreProblem.LEAK, tyreProblem(tyre(150, leak = true)))
        // A sensor not heard says nothing about the tyre.
        assertNull(tyreProblem(tyre(0, noSignal = true)))
        assertNull(tyreProblem(tyre(0)))
    }

    @Test
    fun pressureInTheDriversUnit() {
        Locale.setDefault(Locale.UK)
        assertEquals("2.3 bar", formatPressure(230, PressureUnit.BAR))
        assertEquals("230 kPa", formatPressure(230, PressureUnit.KPA))
        assertEquals("33 psi", formatPressure(230, PressureUnit.PSI))
        assertEquals(PressureUnit.KPA, pressureUnit("Kpa"))
        assertEquals(PressureUnit.BAR, pressureUnit("Bar"))
        assertNull(pressureUnit(null))
    }

    @Test
    fun theUnitsNumberedKeys() {
        assertEquals(1, HeadUnitKeys.customNumber(354))
        assertEquals(15, HeadUnitKeys.customNumber(368))
        assertNull(HeadUnitKeys.customNumber(298))
    }
}
