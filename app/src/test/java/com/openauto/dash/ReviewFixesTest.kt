package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Small rules behind fixes from the 2026-10 code review. */
class ReviewFixesTest {

    @Test
    fun linesFromBeforeTheReaderStartedAreToldApart() {
        val stamp = "10-03 14:22:01.500"
        assertTrue(McuReader.loggedBefore("10-03 14:22:01.123  1234  1234 D mcu_services: dispatchToClients - cmdId: 3", stamp))
        assertTrue(McuReader.loggedBefore("10-02 23:59:59.999  1234  1234 D mcu_services: x", stamp))
        assertFalse(McuReader.loggedBefore("10-03 14:22:01.500  1234  1234 D mcu_services: x", stamp))
        assertFalse(McuReader.loggedBefore("10-03 14:22:02.000  1234  1234 D mcu_services: x", stamp))
        // No stamp of that shape (another log format, a header line): taken as new.
        assertFalse(McuReader.loggedBefore("--------- beginning of main", stamp))
        assertFalse(McuReader.loggedBefore("D/mcu_services( 1234): dispatchToClients - cmdId: 3", stamp))
        assertFalse(McuReader.loggedBefore("", stamp))
    }

    @Test
    fun theStampIsLogcatsOwnShape() {
        val stamp = McuReader.logStamp(0L)
        assertEquals(18, stamp.length)
        assertEquals('-', stamp[2])
        assertEquals(' ', stamp[5])
        assertEquals('.', stamp[14])
    }

    @Test
    fun onlyAPlainPolicyReachesTheShell() {
        assertTrue(isPlainPolicy("immersive.navigation=com.google.android.apps.maps,com.openauto.dash"))
        assertTrue(isPlainPolicy("immersive.full=apps,-com.example.app"))
        assertTrue(isPlainPolicy("immersive.status=*"))
        assertFalse(isPlainPolicy(""))
        assertFalse(isPlainPolicy("x; reboot"))
        assertFalse(isPlainPolicy("x' && rm -rf /data '"))
        assertFalse(isPlainPolicy("\$(id)"))
        assertFalse(isPlainPolicy("a`id`"))
        assertFalse(isPlainPolicy("a\nb"))
    }

    @Test
    fun aRefusedAskWaitsAndABrokenLinkDoesNot() {
        assertTrue(AiMechanic.retryAfterMs(GeminiException("quota", 429)) >= 10 * 60_000L)
        assertTrue(AiMechanic.retryAfterMs(GeminiException("busy", 503)) > 0)
        assertTrue(AiMechanic.retryAfterMs(GeminiException("key", 403)) > AiMechanic.retryAfterMs(GeminiException("quota", 429)))
        assertEquals(0L, AiMechanic.retryAfterMs(IOException("no network")))
        assertEquals(0L, AiMechanic.retryAfterMs(UnreadableAnswerException()))
    }

    @Test
    fun aTyreGoneFlatKeepsItsAlert() {
        fun tyre(kPa: Int) = Tyre(kPa, celsius = 20, sensorBatteryLow = false, leak = false, noSignal = false, at = 0L)
        assertEquals(TyreProblem.LOW, tyreProblem(tyre(60), before = TyreProblem.LOW))
        // Low, then nothing at all: flat, not fine.
        assertEquals(TyreProblem.LOW, tyreProblem(tyre(0), before = TyreProblem.LOW))
        // Nothing read and nothing wrong before: a sensor that hasn't spoken yet.
        assertEquals(null, tyreProblem(tyre(0)))
    }

    @Test
    fun heightsAreInFeetWhereDistancesAreInMiles() {
        assertEquals("312 m", UnitSystem().heightText(312.4))
        assertEquals("1024 ft", UnitSystem(imperial = true).heightText(312.0))
    }

    @Test
    fun guidanceIsKeptOverAStopAndNotOverANight() {
        assertTrue(GUIDANCE_KEPT_MS in 60 * 60_000L..6 * 60 * 60_000L)
    }
}
