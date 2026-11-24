package com.openauto.dash

import com.openauto.dash.DashboardFocus.Reason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardFocusTest {

    @Test
    fun `the window takes the keys again only once nobody asks it not to`() {
        val reasons = HashSet<Reason>()
        assertTrue(DashboardFocus.changes(reasons, Reason.DOCKED_WINDOW, true))
        // A second reason, and the first one asked again: already declined.
        assertFalse(DashboardFocus.changes(reasons, Reason.TILE_TYPING, true))
        assertFalse(DashboardFocus.changes(reasons, Reason.DOCKED_WINDOW, true))
        // One of the two stops asking: the other still holds.
        assertFalse(DashboardFocus.changes(reasons, Reason.TILE_TYPING, false))
        assertTrue(DashboardFocus.changes(reasons, Reason.DOCKED_WINDOW, false))
        // Stopping what was never asked changes nothing.
        assertFalse(DashboardFocus.changes(reasons, Reason.TILE_TYPING, false))
    }
}
