package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** A screen direction on trial: what its wait does next. */
class ScreenShapeTest {

    @Test
    fun theQuestionStandsWhileTheWaitRuns() {
        assertEquals(TryOutStep.ASK, tryOutStep(turned = true, secondsLeft = ScreenShape.KEEP_SECONDS))
        assertEquals(TryOutStep.ASK, tryOutStep(turned = true, secondsLeft = 1))
        // Not turned yet: the unit may still be turning, so nothing is decided.
        assertEquals(TryOutStep.ASK, tryOutStep(turned = false, secondsLeft = 1))
    }

    @Test
    fun unansweredTheTurnedScreenGoesBack() {
        assertEquals(TryOutStep.REVERT, tryOutStep(turned = true, secondsLeft = 0))
    }

    @Test
    fun aScreenThatNeverTurnedKeepsTheChoice() {
        assertEquals(TryOutStep.KEEP, tryOutStep(turned = false, secondsLeft = 0))
    }
}
