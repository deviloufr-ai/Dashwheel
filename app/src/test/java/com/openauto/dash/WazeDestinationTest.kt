package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WazeDestinationTest {
    private val home = WazeDestination.Read("Home", 49.53, 1.99, 1_000_000L)
    private val school = WazeDestination.Read("École", 49.54, 2.01, 2_000_000L)

    @Test
    fun sessionLinesGiveTheTrip() {
        val read = WazeDestination.parseSession(
            listOf(
                "Destination.Position: 2010000,49540000",
                "Navigation.Last dest name: École",
                "Navigation.Last dest street: Grande Rue",
                "GPS.Position: 1990000,49530000"
            ),
            5_000L
        )
        assertEquals(WazeDestination.Read("École", 49.54, 2.01, 5_000L), read)
    }

    @Test
    fun sessionWithoutPositionOrAtZeroGivesNothing() {
        assertNull(WazeDestination.parseSession(listOf("Navigation.Last dest name: École"), 0L))
        assertNull(WazeDestination.parseSession(listOf("Destination.Position: 0,0"), 0L))
        assertNull(WazeDestination.parseSession(listOf("Destination.Position: "), 0L))
    }

    @Test
    fun streetStandsInForAMissingName() {
        val read = WazeDestination.parseSession(
            listOf("Destination.Position: 2010000,49540000", "Navigation.Last dest number: 12", "Navigation.Last dest street: Grande Rue", "Navigation.Last dest city: Beauvais"),
            0L
        )
        assertEquals("12 Grande Rue Beauvais", read?.name)
    }

    @Test
    fun anOldRecentIsNotTheTripUnderWay() {
        assertNull(WazeDestination.pick(home, null, null, sinceMs = 1_500_000L))
        assertEquals(home, WazeDestination.pick(home, null, null, sinceMs = 900_000L))
    }

    @Test
    fun theSessionCountsOnceItChanged() {
        assertNull(WazeDestination.pick(null, home, home, sinceMs = 0L))
        assertEquals(school, WazeDestination.pick(null, school, home, sinceMs = 0L))
    }

    @Test
    fun theLaterOfTheTwoWins() {
        assertEquals(school, WazeDestination.pick(home, school, null, sinceMs = 0L))
        assertEquals(school, WazeDestination.pick(school, home.copy(atMs = 1L), null, sinceMs = 0L))
    }
}
