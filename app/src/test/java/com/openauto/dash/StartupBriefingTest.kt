package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The start-up briefing: when the car counts as started, and what gets said. */
class StartupBriefingTest {

    private val gap = CarStart.OFF_GAP_MS

    @Test
    fun theVeryFirstRunBriefs() {
        assertTrue(CarStart.detected(null, Heartbeat(boot = 7, elapsed = 60_000, wall = 1_000_000)))
    }

    @Test
    fun aRunningUnitDoesNot() {
        val last = Heartbeat(boot = 7, elapsed = 600_000, wall = 5_000_000)
        assertFalse(CarStart.detected(last, last.copy(elapsed = 620_000, wall = 5_020_000)))
    }

    @Test
    fun wakingFromALongSleepBriefs() {
        val last = Heartbeat(boot = 7, elapsed = 600_000, wall = 5_000_000)
        assertTrue(CarStart.detected(last, last.copy(elapsed = 600_000 + gap, wall = 5_000_000 + gap)))
        assertFalse(CarStart.detected(last, last.copy(elapsed = 600_000 + gap - 1, wall = 5_000_000 + gap - 1)))
    }

    @Test
    fun aClockCorrectedMidDriveIsNotAStart() {
        val last = Heartbeat(boot = 7, elapsed = 600_000, wall = 5_000_000)
        assertFalse(CarStart.detected(last, last.copy(elapsed = 620_000, wall = 5_000_000 + 10 * gap)))
    }

    @Test
    fun aRebootBriefsUnlessTheStopWasShort() {
        val last = Heartbeat(boot = 7, elapsed = 3_600_000, wall = 5_000_000)
        // Parked for an hour.
        assertTrue(CarStart.detected(last, Heartbeat(boot = 8, elapsed = 40_000, wall = 5_000_000 + 2 * gap)))
        // Five minutes at the bakery.
        assertFalse(CarStart.detected(last, Heartbeat(boot = 8, elapsed = 40_000, wall = 5_000_000 + 300_000)))
        // Clock not set yet after boot (behind the last beat): count it as a start.
        assertTrue(CarStart.detected(last, Heartbeat(boot = 8, elapsed = 40_000, wall = 1_000)))
        // No boot counter on this unit: uptime going backwards gives the reboot away.
        val noCounter = last.copy(boot = -1)
        assertTrue(CarStart.detected(noCounter, Heartbeat(boot = -1, elapsed = 40_000, wall = 5_000_000 + 2 * gap)))
    }

    private fun weather(tempC: Double, code: Int = 3) = Weather(tempC, tempC, code, 10.0, tempC + 2, tempC - 2, 0L)

    @Test
    fun nothingKnownMeansSilence() {
        assertTrue(BriefingLines.compose(BriefingFacts(hour = 8)).isEmpty())
    }

    @Test
    fun aNormalStartIsSilent() {
        // Mild weather, a full tank and a clean engine: nothing worth interrupting for.
        val fine = BriefingFacts(hour = 8, weather = weather(12.4), fuel = FuelInfo(80, 700, "CANbox"), faults = emptyList())
        assertTrue(BriefingLines.compose(fine).isEmpty())
    }

    @Test
    fun noGreetingWhateverTheHour() {
        listOf(8, 14, 21, 2).forEach { hour ->
            assertEquals(listOf(R.string.briefing_ice), BriefingLines.compose(BriefingFacts(hour = hour, weather = weather(-2.0))).map { it.res })
        }
    }

    @Test
    fun threeLinesAtMostTheAppointmentRightAfterIceAndFuel() {
        val due = UpkeepDue(UpkeepKind.OIL, kmLeft = 800, daysLeft = null, stage = UpkeepStage.SOON)
        val everything = BriefingFacts(
            hour = 8, weather = weather(-2.0), fuel = FuelInfo(12, 110, "CANbox"), faults = listOf("P0420"),
            faultTitles = listOf("catalyst efficiency below threshold"), event = UpcomingEvent("Dentist", "10:30"), upkeep = listOf(due)
        )
        val lines = BriefingLines.compose(everything)
        assertEquals(BriefingLines.MAX_LINES, lines.size)
        assertEquals(listOf(R.string.briefing_ice, R.string.briefing_fuel_low, R.string.briefing_event), lines.map { it.res })
        // Without ice and fuel, the engine and the servicing get their turn, in that order.
        val quiet = everything.copy(weather = weather(12.0), fuel = FuelInfo(80, 700, "CANbox"))
        assertEquals(listOf(R.string.briefing_event, R.plurals.briefing_faults, R.plurals.upkeep_say_soon_km), BriefingLines.compose(quiet).map { it.res })
    }

    @Test
    fun coldOrSnowWarnsOfIce() {
        fun ice(w: Weather) = BriefingLines.compose(BriefingFacts(hour = 8, weather = w)).any { it.res == R.string.briefing_ice }
        assertTrue(ice(weather(2.0)))
        assertTrue(ice(weather(-4.0)))
        assertTrue(ice(weather(5.0, code = 71)))
        assertFalse(ice(weather(4.0)))
    }

    @Test
    fun onlyLowFuelIsMentioned() {
        assertTrue(BriefingLines.compose(BriefingFacts(hour = 8, fuel = FuelInfo(40, 370, "CANbox"))).isEmpty())
        val low = BriefingLines.compose(BriefingFacts(hour = 8, fuel = FuelInfo(12, 110, "CANbox")))
        assertEquals(listOf(SpokenLine(R.string.briefing_fuel_low, listOf(110))), low)
    }

    @Test
    fun engineLineFollowsWhatTheScanFoundByNameNeverByCode() {
        fun engine(f: BriefingFacts) = BriefingLines.compose(f)
        assertTrue(engine(BriefingFacts(hour = 8, faults = emptyList())).isEmpty())
        // Named faults: the count and the names; the codes' letters are never said.
        assertEquals(
            listOf(SpokenLine(R.plurals.briefing_faults, listOf(2, "coolant below thermostat regulating temperature, particle filter"), quantity = 2)),
            engine(BriefingFacts(hour = 8, faults = listOf("P0128", "P2002"), faultTitles = listOf("coolant below thermostat regulating temperature", "particle filter")))
        )
        // Codes the table doesn't know: the count alone.
        assertEquals(
            listOf(SpokenLine(R.plurals.briefing_faults_untitled, listOf(1), quantity = 1)),
            engine(BriefingFacts(hour = 8, faults = listOf("P1F00")))
        )
        assertEquals(
            listOf(SpokenLine(R.string.briefing_verbatim, listOf("The thermostat is stuck open."))),
            engine(BriefingFacts(hour = 8, faults = listOf("P0128"), faultSummary = "The thermostat is stuck open."))
        )
        // Already announced by the mechanic moments ago: not repeated.
        assertTrue(BriefingLines.compose(BriefingFacts(hour = 8, faults = listOf("P0128"), faultsJustSaid = true)).isEmpty())
    }

    @Test
    fun theNextAppointmentAloneIsWorthSaying() {
        val lines = BriefingLines.compose(
            BriefingFacts(hour = 8, weather = weather(12.0), faults = emptyList(), event = UpcomingEvent("Dentist", "10:30"))
        )
        assertEquals(listOf(SpokenLine(R.string.briefing_event, listOf("Dentist", "10:30"))), lines)
    }

    @Test
    fun servicingComingDueIsSaidAfterTheAppointment() {
        val due = UpkeepDue(UpkeepKind.OIL, kmLeft = 800, daysLeft = null, stage = UpkeepStage.SOON)
        val lines = BriefingLines.compose(BriefingFacts(hour = 8, upkeep = listOf(due), event = UpcomingEvent("Dentist", "10:30")))
        assertEquals(listOf(R.string.briefing_event, R.plurals.upkeep_say_soon_km), lines.map { it.res })
        assertEquals(800, lines[1].args[1])
        // Nothing due: nothing said.
        assertTrue(BriefingLines.compose(BriefingFacts(hour = 8, upkeep = emptyList())).isEmpty())
    }
}
