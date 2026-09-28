package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Volume that follows speed: how many steps for which speed. */
class SpeedVolumeTest {

    /** What Medium was: a step at 40, 65, 90 and 115 km/h. */
    private val four = SpeedVolumeCurve(startKmh = 40, fullKmh = 115, maxSteps = 4)
    private val gentle = SpeedVolumeCurve.DEFAULT

    @Test
    fun underTheStartSpeedNothingIsAdded() {
        assertEquals(0, SpeedVolume.boostFor(0, four))
        assertEquals(0, SpeedVolume.boostFor(39, four))
        assertEquals(0, SpeedVolume.boostFor(59, gentle))
    }

    @Test
    fun stepsComeOnEvenlyFromTheStartToTheTopSpeed() {
        assertEquals(1, SpeedVolume.boostFor(40, four))
        assertEquals(1, SpeedVolume.boostFor(64, four))
        assertEquals(2, SpeedVolume.boostFor(65, four))
        assertEquals(3, SpeedVolume.boostFor(90, four))
        assertEquals(4, SpeedVolume.boostFor(115, four))
        assertEquals(4, SpeedVolume.boostFor(200, four))
    }

    @Test
    fun theDefaultAddsOneStepThenASecondOnTheMotorway() {
        assertEquals(1, SpeedVolume.boostFor(60, gentle))
        assertEquals(1, SpeedVolume.boostFor(119, gentle))
        assertEquals(2, SpeedVolume.boostFor(120, gentle))
        assertEquals(2, SpeedVolume.boostFor(160, gentle))
    }

    @Test
    fun aSingleStepComesOnAtTheStartSpeed() {
        val one = SpeedVolumeCurve(startKmh = 90, fullKmh = 130, maxSteps = 1)
        assertEquals(0, SpeedVolume.boostFor(89, one))
        assertEquals(1, SpeedVolume.boostFor(90, one))
        assertEquals(1, SpeedVolume.boostFor(160, one))
    }

    @Test
    fun speedingUpRaisesAtOnce() {
        assertEquals(2, SpeedVolume.target(65, 1, four))
    }

    @Test
    fun aStepOnlyComesOffWellUnderItsThreshold() {
        // Step 2 came on at 65 km/h: it stays down to 58, and goes at 56.
        assertEquals(2, SpeedVolume.target(60, 2, four))
        assertEquals(2, SpeedVolume.target(58, 2, four))
        assertEquals(1, SpeedVolume.target(56, 2, four))
    }

    @Test
    fun stoppingGivesEverythingBack() {
        assertEquals(0, SpeedVolume.target(0, 4, four))
    }

    @Test
    fun anUnknownSpeedHolds() {
        assertEquals(3, SpeedVolume.target(null, 3, four))
    }

    @Test
    fun aWeakerCurveCapsWhatIsHeld() {
        assertEquals(2, SpeedVolume.target(null, 5, gentle))
        assertEquals(2, SpeedVolume.target(130, 5, gentle))
    }

    @Test
    fun aCurveStaysWithinWhatTheSettingsOffer() {
        assertEquals(SpeedVolumeCurve(20, 30, 1), SpeedVolumeCurve(0, 0, 0).fixed())
        assertEquals(SpeedVolumeCurve(130, 160, 12), SpeedVolumeCurve(300, 300, 99).fixed())
        assertEquals(gentle, gentle.fixed())
    }

    @Test
    fun theTopSpeedStaysAboveTheStart() {
        assertEquals(SpeedVolumeCurve(100, 110, 3), SpeedVolumeCurve(100, 80, 3).fixed())
    }

    @Test
    fun oldLevelsBecomeCurves() {
        // On a 32-step unit a notch was 2 steps: Low counts single steps now, the others stay as loud.
        assertEquals(SpeedVolumeCurve(40, 80, 2), SpeedVolume.fromOldLevel("LOW", 2))
        assertEquals(SpeedVolumeCurve(40, 120, 8), SpeedVolume.fromOldLevel("MEDIUM", 2))
        assertEquals(SpeedVolumeCurve(40, 120, 12), SpeedVolume.fromOldLevel("HIGH", 2))
        assertEquals(SpeedVolumeCurve(40, 120, 6), SpeedVolume.fromOldLevel("HIGH", 1))
        assertNull(SpeedVolume.fromOldLevel("OFF", 2))
    }

    @Test
    fun aNotchWasAboutAFifteenthOfTheRange() {
        assertEquals(1, SpeedVolume.unitFor(15))
        assertEquals(1, SpeedVolume.unitFor(7))
        assertEquals(2, SpeedVolume.unitFor(30))
        assertEquals(3, SpeedVolume.unitFor(40))
        assertEquals(1, SpeedVolume.unitFor(0))
    }
}
