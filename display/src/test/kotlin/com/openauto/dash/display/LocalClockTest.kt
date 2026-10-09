package com.openauto.dash.display

import com.openauto.dash.link.DisplayTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

class LocalClockTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theHeadUnitsZoneIsKeptForTheNextStartAndItsClockSetsThisOne() {
        var set: Long? = null
        LocalClock.setsSystemClock = { set = it; true }
        val far = System.currentTimeMillis() + 3_600_000
        // A zone that isn't this computer's own.
        assertTrue(LocalClock.set(tmp.root, DisplayTime(far, 540, "Asia/Tokyo")))
        assertEquals(ZoneId.of("Asia/Tokyo"), LocalClock.zone)
        assertEquals(far, set)
        assertEquals("Asia/Tokyo", File(tmp.root, "zone.txt").readText().trim())
        // The same zone again: nothing to save.
        assertFalse(LocalClock.set(tmp.root, DisplayTime(far + 1, 540, "Asia/Tokyo")))
        // A few seconds off (a head unit beside a board on network time): the board's clock is
        // left alone, the pictures use the offset.
        set = null
        LocalClock.set(tmp.root, DisplayTime(System.currentTimeMillis() + 2_500, 540, "Asia/Tokyo"))
        assertEquals(null, set)
        assertTrue(kotlin.math.abs(LocalClock.now() - (System.currentTimeMillis() + 2_500)) < 200)
        // A zone this JVM doesn't know: its offset does.
        assertEquals(ZoneOffset.ofHours(-5), LocalClock.zoneOf("Mars/Olympus", -300))
        assertEquals(null, LocalClock.zoneOf("", null))
        // Loaded again at the next start.
        LocalClock.set(tmp.root, DisplayTime(far, 0, "UTC"))
        File(tmp.root, "zone.txt").writeText("Europe/Warsaw\n")
        LocalClock.load(tmp.root)
        assertEquals(ZoneId.of("Europe/Warsaw"), LocalClock.zone)
    }

    @Test
    fun theBoardsFlagsAreReadAsTheFirmwareWritesThem() {
        assertEquals(0x50005, BoardHealth.parseFlags("50005\n"))
        assertEquals(0, BoardHealth.parseFlags("0x0"))
        assertEquals("fine", BoardHealth.describe(0))
        assertTrue(BoardHealth.describe(0x50005).startsWith("under-voltage, throttled"))
        assertEquals("fine now (0x50000: trouble since the start)", BoardHealth.describe(0x50000))
        val throttled = tmp.newFile("t").apply { writeText("0x80008") }
        val temp = tmp.newFile("temp").apply { writeText("71234") }
        assertEquals(BoardHealth.Reading(0x80008, 71), BoardHealth.read(throttled, temp))
        assertEquals(BoardHealth.Reading(0, null), BoardHealth.read(File(tmp.root, "none"), File(tmp.root, "none")))
    }
}
