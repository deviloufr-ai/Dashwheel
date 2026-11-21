package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lights-left-on warning (#178): lights on, a front door open, engine not running. */
class LightsLeftOnTest {
    private val driver = McuReader.DoorState(frontLeft = true)

    @Test fun warnsWhenGettingOutWithTheLightsOn() {
        assertTrue(CarCare.lightsLeftOn(true, driver, false))
        assertTrue(CarCare.lightsLeftOn(true, McuReader.DoorState(frontRight = true), null))
    }

    @Test fun quietOtherwise() {
        assertFalse(CarCare.lightsLeftOn(true, driver, true))
        assertFalse(CarCare.lightsLeftOn(false, driver, false))
        assertFalse(CarCare.lightsLeftOn(null, driver, false))
        assertFalse(CarCare.lightsLeftOn(true, McuReader.DoorState(rearLeft = true, tailgate = true), false))
        assertFalse(CarCare.lightsLeftOn(true, null, false))
    }
}
