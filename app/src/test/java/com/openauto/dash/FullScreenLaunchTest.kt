package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenLaunchTest {
    private fun win(mode: String = "freeform", displayId: Int = 0, offDisplay: Boolean = false) =
        FloatingWindow(stackId = 7, taskId = 63, packageName = "com.google.android.apps.maps", bounds = ScreenRect(640, 90, 1260, 640),
            mode = mode, displayId = displayId, offDisplay = offDisplay)

    @Test
    fun aFreeformWindowOnTheScreenIsMadeFullScreen() {
        assertTrue(FullScreenLaunch.needsFullScreen(win()))
    }

    @Test
    fun noWindowAPipOrAWindowOffTheScreenIsLeftAlone() {
        assertFalse(FullScreenLaunch.needsFullScreen(null))
        assertFalse(FullScreenLaunch.needsFullScreen(win(mode = "pinned")))
        assertFalse(FullScreenLaunch.needsFullScreen(win(displayId = 5, offDisplay = true)))
    }
}
