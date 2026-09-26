package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seat belt reminder's rule, and the navigation bar setting written while apps are docked. */
class BeltAndNavBarTest {

    @Test
    fun beltReminderOnlyOnTheMove() {
        assertTrue(beltReminder(driverUnbuckled = true, moving = true))
        assertFalse(beltReminder(driverUnbuckled = true, moving = false))
        assertFalse(beltReminder(driverUnbuckled = false, moving = true))
    }

    @Test
    fun navigationBarPolicyListsTheDockedAppsAndDashwheel() {
        assertEquals(
            "immersive.navigation=com.google.android.apps.maps,com.openauto.dash",
            immersivePolicy(setOf("com.openauto.dash", "com.google.android.apps.maps", ""))
        )
    }
}
