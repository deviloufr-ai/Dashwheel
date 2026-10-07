package com.openauto.dash

import com.openauto.dash.link.CallState
import com.openauto.dash.link.ConversationLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the car does without a tap: places, the voice's manners, low fuel, the apps used most. */
class HandsFreeTest {

    private val home = Place("Home", 48.8566, 2.3522)
    private val bakery = Place("Bakery", 48.8600, 2.3400)
    private val pool = Place("Pool", 48.9000, 2.4000)

    // --- Places ---------------------------------------------------------------

    @Test
    fun theLatestDestinationComesFirstAndOnce() {
        val places = Places().visited(bakery).visited(pool).visited(bakery)
        assertEquals(listOf("Bakery", "Pool"), places.recent.map { it.name })
    }

    @Test
    fun theSameSpotUnderAnotherNameIsNotListedTwice() {
        val again = Place("Boulangerie", bakery.lat + 0.0002, bakery.lng)
        val places = Places().visited(bakery).visited(again)
        assertEquals(listOf("Boulangerie"), places.recent.map { it.name })
    }

    @Test
    fun homeAndWorkStayOutOfTheLastDestinations() {
        val places = Places(home = home).visited(home).visited(bakery)
        assertEquals(listOf("Bakery"), places.recent.map { it.name })
    }

    @Test
    fun onlyTheLastFewDestinationsAreKept() {
        var places = Places()
        repeat(Places.MAX_RECENT + 3) { i -> places = places.visited(Place("P$i", 40.0 + i, 2.0)) }
        assertEquals(Places.MAX_RECENT, places.recent.size)
        assertEquals("P${Places.MAX_RECENT + 2}", places.recent.first().name)
    }

    @Test
    fun placesSurviveBeingSaved() {
        val places = Places(home = home, work = null, recent = listOf(bakery, pool))
        assertEquals(places, PlacesStore.decode(PlacesStore.encode(places)))
        assertEquals(Places(), PlacesStore.decode(PlacesStore.encode(Places())))
    }

    @Test
    fun aSpotOnTheMapIsNamedByItsStreetAndTown() {
        val json = """{"address":{"house_number":"12","road":"Rue de Rivoli","city":"Paris","country":"France"}}"""
        assertEquals("12 Rue de Rivoli, Paris", PlaceSearch.shortAddress(json))
        assertEquals("Lyon", PlaceSearch.shortAddress("""{"address":{"town":"Lyon"}}"""))
        assertNull(PlaceSearch.shortAddress("""{"error":"Unable to geocode"}"""))
    }

    @Test
    fun theDriversOwnNavigationAppIsAskedFirst() {
        assertEquals(listOf(NavHandoff.WAZE, NavHandoff.MAPS), NavHandoff.order(NavHandoff.WAZE, mapsInstalled = true, wazeInstalled = true))
        assertEquals(listOf(NavHandoff.MAPS, NavHandoff.WAZE), NavHandoff.order(null, mapsInstalled = true, wazeInstalled = true))
        // A NAVI key set to an app that takes no destination changes nothing.
        assertEquals(listOf(NavHandoff.MAPS, NavHandoff.WAZE), NavHandoff.order("io.github.deviloufr.dashwheel", mapsInstalled = true, wazeInstalled = true))
        assertEquals(listOf(NavHandoff.WAZE), NavHandoff.order(NavHandoff.MAPS, mapsInstalled = false, wazeInstalled = true))
        assertTrue(NavHandoff.order(null, mapsInstalled = false, wazeInstalled = false).isEmpty())
    }

    // --- The voice ------------------------------------------------------------

    private fun call(phase: CallState.Phase, dialing: Boolean = false, preview: Boolean = false) =
        PhoneCall(phase, "0612", "Alex", null, 0, canControl = true, dialing = dialing, preview = preview)

    @Test
    fun theVoiceWaitsWhileTheDriverIsOnThePhone() {
        assertTrue(talking(call(CallState.Phase.ACTIVE)))
        assertTrue(talking(call(CallState.Phase.ACTIVE, dialing = true)))
    }

    @Test
    fun aCallStillRingingIsAnnounced() {
        assertFalse(talking(call(CallState.Phase.RINGING)))
        assertFalse(talking(null))
    }

    @Test
    fun theStylePickersMadeUpCallHoldsNothingBack() {
        assertFalse(talking(call(CallState.Phase.ACTIVE, preview = true)))
    }

    @Test
    fun aChatThatGoesOnIsAnnouncedOnce() {
        val every = 3 * 60_000L
        assertTrue(messageDue(null, now = 1_000, every = every))
        assertFalse(messageDue(1_000, now = 1_000 + every - 1, every = every))
        assertTrue(messageDue(1_000, now = 1_000 + every, every = every))
    }

    private fun message(title: String, text: String, lines: List<ConversationLine> = emptyList()) =
        NotifItem("phone:k", "org.chat", "Chat", title, text, 0L, null, null, fromPhone = true, canReply = true, messages = lines)

    @Test
    fun aMessageIsReadWithWhoItIsFrom() {
        assertEquals("Alex. On my way. ", spokenMessage(message("Alex", "On my way")))
    }

    @Test
    fun aGroupsLastLinesAreReadEachWithItsSender() {
        val lines = listOf(
            ConversationLine("Ann", "one", 1), ConversationLine("Bob", "two", 2),
            ConversationLine("Ann", "three", 3), ConversationLine("Family", "four", 4)
        )
        assertEquals("Family. Bob: two. Ann: three. four. ", spokenMessage(message("Family", "four", lines)))
    }

    @Test
    fun anAppWithoutATitleIsNamedInstead() {
        assertEquals("Chat. Hello. ", spokenMessage(message("", "Hello")))
    }

    // --- Low fuel -------------------------------------------------------------

    @Test
    fun lowFuelIsSaidOnceOnTheMove() {
        val (speak, armed) = LowFuel.step(armed = true, rangeKm = 75, moving = true)
        assertTrue(speak)
        assertFalse(armed)
        assertEquals(false to false, LowFuel.step(armed, rangeKm = 60, moving = true))
    }

    @Test
    fun parkedItWaitsForTheDrive() {
        assertEquals(false to true, LowFuel.step(armed = true, rangeKm = 75, moving = false))
    }

    @Test
    fun aFillUpArmsItAgain() {
        assertEquals(false to true, LowFuel.step(armed = false, rangeKm = LowFuel.REARM_KM, moving = false))
        // Not a few kilometres gained going downhill.
        assertEquals(false to false, LowFuel.step(armed = false, rangeKm = LowFuel.WARN_KM + 20, moving = true))
    }

    @Test
    fun aRangeTheCarDoesNotGiveSaysNothing() {
        assertEquals(false to true, LowFuel.step(armed = true, rangeKm = null, moving = true))
        assertEquals(false to true, LowFuel.step(armed = true, rangeKm = 0, moving = true))
    }

    // --- Asked how the car is ---------------------------------------------------

    @Test
    fun askedHowTheCarIsTheAnswerIsNeverSilence() {
        assertEquals(listOf(R.string.voice_status_nothing), BriefingLines.status(BriefingFacts(hour = 8)).map { it.res })
    }

    @Test
    fun whatIsFineIsSaidTooWhenAsked() {
        val fine = BriefingFacts(hour = 8, fuel = FuelInfo(80, 700, "CANbox"), faults = emptyList())
        assertEquals(listOf(R.string.voice_status_range, R.string.voice_status_no_faults), BriefingLines.status(fine).map { it.res })
    }

    @Test
    fun whatIsWrongIsSaidAsTheBriefingWould() {
        val low = BriefingFacts(hour = 8, fuel = FuelInfo(10, 60, "CANbox"), faults = listOf("P0101", "P0401"))
        val lines = BriefingLines.status(low)
        assertEquals(listOf(R.string.briefing_fuel_low, R.plurals.briefing_faults), lines.map { it.res })
        assertEquals(2, lines[1].quantity)
    }

    // --- The apps used most -----------------------------------------------------

    @Test
    fun theAppsOpenedMostComeFirst() {
        val counts = mapOf("maps" to 9, "music" to 14, "radio" to 2, "files" to 1)
        assertEquals(listOf("music", "maps", "radio"), AppUsage.mostUsed(listOf("files", "maps", "music", "radio"), counts))
    }

    @Test
    fun anAppThatWasRemovedIsNotOffered() {
        val counts = mapOf("maps" to 9, "music" to 14, "gone" to 30)
        assertEquals(listOf("music", "maps"), AppUsage.mostUsed(listOf("maps", "music"), counts))
    }

    @Test
    fun oneHabitIsNotARow() {
        assertTrue(AppUsage.mostUsed(listOf("maps", "music"), mapOf("maps" to 9, "music" to 1)).isEmpty())
        assertTrue(AppUsage.mostUsed(listOf("maps"), emptyMap()).isEmpty())
    }

    @Test
    fun oldHabitsMakeRoomForNewOnes() {
        var counts = mapOf("old" to 190, "new" to 4)
        repeat(10) { counts = AppUsage.counted(counts, "old") }
        assertEquals(100, counts["old"])
        assertEquals(2, counts["new"])
    }
}
