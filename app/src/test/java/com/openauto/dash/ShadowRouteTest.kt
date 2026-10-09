package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ShadowRouteTest {

    // North 1 km along a meridian, then east 1 km, near Paris.
    private val route = RouteProgress(listOf(48.8500 to 2.3500, 48.8590 to 2.3500, 48.8590 to 2.3637))

    @Test
    fun aPositionIsPlacedAlongTheRouteAndOffIt() {
        assertEquals(2_000.0, route.lengthM, 30.0)
        // Halfway up the first leg, 50 m to the east of it.
        val fix = route.locate(48.8545, 2.3500 + 50.0 / (111_320.0 * Math.cos(Math.toRadians(48.85))))
        assertEquals(500.0, fix.alongM, 15.0)
        assertEquals(50.0, fix.offM, 2.0)
        // On the second leg, a quarter of the way along it.
        val on = route.locate(48.8590, 2.3500 + (2.3637 - 2.3500) / 4)
        assertEquals(1_250.0, on.alongM, 20.0)
        assertEquals(0.0, on.offM, 1.0)
        // Before the start: at its start, off by the distance to it.
        val before = route.locate(48.8480, 2.3500)
        assertEquals(0.0, before.alongM, 0.1)
        assertEquals(222.0, before.offM, 10.0)
    }

    @Test
    fun theHandedOffDestinationArmsAndFades() {
        val t0 = 1_000_000L
        val home = NavHandoff.Target("Home", 48.85, 2.35, null, t0)
        // A fresh hand-off takes over, whether or not the app runs yet.
        assertSame(home, ShadowRoute.armed(null, home, running = false, nowMs = t0 + 1_000))
        // It stays while the app runs, and while it has only just stopped.
        assertSame(home, ShadowRoute.armed(home, home, running = true, nowMs = t0 + 3_600_000))
        assertSame(home, ShadowRoute.armed(home, home, running = false, nowMs = t0 + 60_000))
        // Gone for a while without the app: dropped, so a trip typed into the app gets no stale shadow.
        assertNull(ShadowRoute.armed(home, home, running = false, nowMs = t0 + ShadowRoute.HANDOFF_FRESH_MS + 1))
        // An old hand-off never arms by itself.
        assertNull(ShadowRoute.armed(null, home, running = true, nowMs = t0 + ShadowRoute.HANDOFF_FRESH_MS + 1))
        // A new one replaces the current.
        val work = NavHandoff.Target("Work", null, null, "Work street", t0 + 7_200_000)
        assertSame(work, ShadowRoute.armed(home, work, running = true, nowMs = t0 + 7_200_500))
    }
}
