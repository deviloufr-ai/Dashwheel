package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving the setup, the bar's coolant limits, the battery boiling over, the catalogue, the music resume. */
class SetupAndAlertsTest {

    // --- The setup file ------------------------------------------------------------------

    @Test
    fun everyPreferenceComesBackWithItsType() {
        val files = mapOf(
            "dashboard_theme" to mapOf("mode" to "AURORA", "effects" to 2, "since" to 1_727_000_000_000L),
            "speed_volume" to mapOf("on" to true, "gain" to 0.5f),
            "canvas_tabs" to mapOf("pinned" to setOf("maps", "music"))
        )
        val back = SetupBackup.decode(SetupBackup.encode(files, "1.0.500").toString())
        assertEquals(files, back)
        // An Int stays an Int and a Long a Long: SharedPreferences tells them apart.
        assertTrue(back.getValue("dashboard_theme")["effects"] is Int)
        assertTrue(back.getValue("dashboard_theme")["since"] is Long)
        assertTrue(back.getValue("speed_volume")["gain"] is Float)
    }

    @Test
    fun aFileThatIsNotASetupIsRefused() {
        assertTrue(runCatching { SetupBackup.decode("""{"pages":[]}""") }.isFailure)
        assertTrue(runCatching { SetupBackup.decode("not json") }.isFailure)
        // A newer format than this build knows is refused too, rather than half read.
        assertTrue(runCatching { SetupBackup.decode("""{"dashwheel_setup":99,"prefs":{}}""") }.isFailure)
    }

    @Test
    fun anUnknownValueTypeIsSkippedNotFatal() {
        val text = """{"dashwheel_setup":1,"prefs":{"x":{"a":{"t":"s","v":"ok"},"b":{"t":"blob","v":1}}}}"""
        assertEquals(mapOf("x" to mapOf("a" to "ok")), SetupBackup.decode(text))
    }

    // --- The bar's coolant limits ------------------------------------------------------------

    @Test
    fun theBarsCoolantLimitsFollowTheEngineAndTheVoice() {
        assertEquals(105, coolantWarningC(90))
        assertEquals(110, coolantCriticalC(90))
        // Red where the voice says it is overheating.
        assertEquals(Overheat.alarmC(105), coolantCriticalC(105))
    }

    // --- The battery boiling over ---------------------------------------------------------------

    @Test
    fun aBatteryOverchargedForHalfAMinuteIsSaidOnce() {
        val w = LiveWatch()
        var t = 0L
        while (t < LiveWatch.OVERCHARGE_MS) {
            assertNull(w.check(ObdData(rpm = 900, voltage = 15.7, voltageFromEcu = true), t))
            t += 500
        }
        assertEquals(LiveWatch.Alert.OVERCHARGING, w.check(ObdData(rpm = 900, voltage = 15.7, voltageFromEcu = true), t))
        assertNull(w.check(ObdData(rpm = 900, voltage = 15.7, voltageFromEcu = true), t + 500))
    }

    @Test
    fun theAdaptersOwnHighReadingIsNotAnOvercharge() {
        val w = LiveWatch()
        var t = 0L
        while (t <= LiveWatch.OVERCHARGE_MS + 5_000) {
            assertNull(w.check(ObdData(rpm = 900, voltage = 15.8), t))
            t += 500
        }
    }

    // --- The catalogue -----------------------------------------------------------------------

    @Test
    fun theCatalogueLeavesOutDoublesAndTilesWithNothingToShow() {
        listOf(BuiltinKind.OBD_ALL, BuiltinKind.PIP_ANCHOR, BuiltinKind.MAPS_INSIDE, BuiltinKind.MY_CAR, BuiltinKind.PARKING)
            .forEach { assertFalse("$it", it.offered) }
        assertTrue(BuiltinKind.TELEMETRY.offered)
        assertTrue(BuiltinKind.NAVMAP.offered)
        // The fuel tiles sit together.
        assertEquals(
            setOf(WidgetCategory.VEHICLE),
            setOf(BuiltinKind.RANGE, BuiltinKind.FUEL_TO_DEST, BuiltinKind.FUEL_PRICES).map { it.category }.toSet()
        )
    }

    // --- The music at start ----------------------------------------------------------------------

    @Test
    fun musicPlayingJustBeforeTheSwitchOffIsResumed() {
        assertTrue(wasPlaying(playingAt = 100_000, now = 110_000))
        assertTrue(wasPlaying(playingAt = 100_000, now = 100_000 + MediaResume.PLAYING_BEFORE_OFF_MS))
        assertFalse(wasPlaying(playingAt = 100_000, now = 100_001 + MediaResume.PLAYING_BEFORE_OFF_MS))
        assertFalse(wasPlaying(playingAt = 0, now = 10_000))
    }

    // --- The break without the adapter --------------------------------------------------------------

    @Test
    fun drivingTimeCountsFromAnySpeed() {
        var rest = RestTimer()
        var t = 1_000_000L
        val events = mutableListOf<CareEvent>()
        // Ten minutes at 90 km/h, one reading a second, as the car box gives it.
        repeat(600) {
            rest = CareRules.restStep(rest, ObdData(speedKmh = 90), t, events)
            t += 1_000
        }
        assertTrue(rest.drivingMs in 590_000L..600_000L)
        assertTrue(events.isEmpty())
    }

    // --- The setup's steps ------------------------------------------------------------------------

    @Test
    fun backGoesAStepBackAndOutOnlyFromTheFirst() {
        assertEquals(SetupStep.ACCESS, SetupStep.LOOK.previous())
        assertEquals(SetupStep.CAR, SetupStep.ACCESS.previous())
        // Nothing before the first step: Back leaves the setup there.
        assertNull(SetupStep.CAR.previous())
    }
}
