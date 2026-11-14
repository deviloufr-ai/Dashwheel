package com.openauto.dash

import com.openauto.dash.vehicle.Indicators
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.VehicleBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SLCAN frames as a CANable sends them. */
class SlcanTest {
    @Test
    fun standardAndExtendedFramesAreRead() {
        assertEquals(CanFrame(0x408, false, listOf(0x14, 0x00, 0x3A)), Slcan.frame("t408314003A"))
        assertEquals(CanFrame(0x18DAF110, true, listOf(0x02, 0x10)), Slcan.frame("T18DAF11020210"))
        assertEquals(CanFrame(0x210, false, emptyList()), Slcan.frame("t2100"))
        assertEquals(CanFrame(0x7FF, false, List(8) { 0xFF }), Slcan.frame("t7FF8FFFFFFFFFFFFFFFF"))
    }

    @Test
    fun aTimeStampAfterTheDataIsReadPast() {
        assertEquals(CanFrame(0x200, false, listOf(0x02)), Slcan.frame("t200102ABCD"))
    }

    @Test
    fun anythingElseIsNoFrame() {
        assertNull(Slcan.frame(""))
        assertNull(Slcan.frame("r2100"))         // a remote request
        assertNull(Slcan.frame("V1013"))         // a version answer
        assertNull(Slcan.frame("t8001FF"))       // an 11-bit id past 7FF
        assertNull(Slcan.frame("t2009" + "00".repeat(9)))
        assertNull(Slcan.frame("t20020"))        // a byte short
        assertNull(Slcan.frame("t2001GG"))
    }

    @Test
    fun bitRatesHaveTheirCommands() {
        assertEquals("S6\r", Slcan.bitrateCommand(500_000))
        assertEquals("S4\r", Slcan.bitrateCommand(125_000))
        assertEquals("S8\r", Slcan.bitrateCommand(1_000_000))
        assertNull(Slcan.bitrateCommand(83_333))
    }

    @Test
    fun linesAreJoinedAcrossUsbPieces() {
        val r = SlcanLineReader()
        val first = r.feed("t2001".toByteArray())
        assertTrue(first.frames.isEmpty())
        val second = r.feed("02\rt21".toByteArray())
        assertEquals(listOf(CanFrame(0x200, false, listOf(0x02))), second.frames)
        val third = r.feed("03000080\r".toByteArray())
        assertEquals(listOf(CanFrame(0x210, false, listOf(0x00, 0x00, 0x80))), third.frames)
    }

    @Test
    fun answersAreCounted() {
        val r = SlcanLineReader()
        val ok = r.feed("\r".toByteArray())
        assertEquals(1, ok.oks)
        val refused = r.feed(byteArrayOf(7))
        assertEquals(1, refused.errors)
        // "z\r", the answer some firmwares give to a frame sent: taken.
        assertEquals(1, r.feed("z\r".toByteArray()).oks)
    }
}

/** CAN readings in My readings, and what they show as in the car. */
class CanReadingsTest {
    private fun can(key: String, id: String, formula: String, feeds: ReadingFeed? = null) =
        MyReading(key, key, "", MyReadingKind.CAN, formula, id, feeds = feeds)

    @Test
    fun idsAreThreeOrEightHexDigits() {
        assertEquals(0x408, can("tank", "408", "A").canId)
        assertFalse(can("tank", "408", "A").canExtended)
        assertEquals(0x18DAF110, can("x", "18DAF110", "A").canId)
        assertTrue(can("x", "18DAF110", "A").canExtended)
        assertNull(can("x", "800", "A").canId)
        assertNull(can("x", "4080", "A").canId)
        assertNull(can("x", "3FFFFFFF", "A").canId)
    }

    @Test
    fun aCanReadingIsCheckedLikeARequest() {
        assertNull(MyReadingRules.problem(can("tank", "408", "A*100/80"), emptyList()))
        assertEquals(MyReadingProblem.BAD_CAN_ID, MyReadingRules.problem(can("tank", "", "A"), emptyList()))
        assertEquals(MyReadingProblem.BAD_CAN_ID, MyReadingRules.problem(can("tank", "1234", "A"), emptyList()))
        // Bytes mean nothing in a worked-out reading, still.
        val formula = MyReading("x", "x", "", MyReadingKind.FORMULA, "A + 1")
        assertEquals(MyReadingProblem.NO_BYTES, MyReadingRules.problem(formula, emptyList()))
    }

    @Test
    fun twoReadingsCantFeedOneElement() {
        val first = can("brake", "200", "bit(A, 1)", ReadingFeed.HANDBRAKE)
        val second = can("brake2", "201", "A", ReadingFeed.HANDBRAKE)
        assertEquals(MyReadingProblem.FEED_TAKEN, MyReadingRules.problem(second, listOf(first)))
        assertNull(MyReadingRules.problem(second.copy(feeds = null), listOf(first)))
    }

    @Test
    fun aFrameIsReadThroughTheFormula() {
        val pedal = can("pedal", "210", "C * 100 / 250")
        assertEquals(50.0, MyReadingRules.read(pedal, CanFrame(0x210, false, listOf(0, 0, 125))).value!!, 1e-9)
        val brake = can("brake", "200", "bit(A, 1)")
        assertEquals(1.0, MyReadingRules.read(brake, CanFrame(0x200, false, listOf(0x02))).value!!, 0.0)
        assertEquals(0.0, MyReadingRules.read(brake, CanFrame(0x200, false, listOf(0x00))).value!!, 0.0)
        // A byte the frame doesn't have: no value.
        assertNull(MyReadingRules.read(pedal, CanFrame(0x210, false, listOf(0))).value)
    }

    @Test
    fun canReadingsTakeTheirFramesValueAndKeepTheirFeedThroughJson() {
        val r = can("tank", "408", "A", ReadingFeed.FUEL_LEVEL)
        assertEquals(r, MyReading.fromJson(r.toJson()))
        val values = MyReadingRules.values(listOf(r), emptyMap(), mapOf("tank" to 40.0), emptyMap())
        assertEquals(40.0, values["tank"]!!, 0.0)
        assertEquals(mapOf(ReadingFeed.FUEL_LEVEL to 40.0), MyReadingRules.feeds(listOf(r), values))
    }

    @Test
    fun feedsAddUpToTheCarsKeys() {
        val v = ReadingFeedRules.values(
            mapOf(
                ReadingFeed.FUEL_LEVEL to 62.5,
                ReadingFeed.HANDBRAKE to 2.0,
                ReadingFeed.DOOR_FRONT_LEFT to 1.0,
                ReadingFeed.DOOR_FRONT_RIGHT to 0.0,
                ReadingFeed.INDICATOR_RIGHT to 1.0
            )
        )
        assertEquals(62.5, v[K.FUEL_LEVEL])
        assertEquals(true, v[K.HANDBRAKE])
        assertEquals(McuReader.DoorState(frontLeft = true), v[K.DOORS])
        assertEquals(Indicators(left = false, right = true), v[K.INDICATORS])
        assertFalse(K.REVERSE in v)
    }
}

/** The fed values on the bus: the driver's, so they win over the car's built-in ones. */
class ReadingFeedPublisherTest {
    private var t = 0L
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val bus = VehicleBus({ t }, scope)
    private val car = bus.register(Source("unit.bus", SourceKind.CANBOX, "Unit bus"))
    private val feeds = ReadingFeedPublisher(bus.register(Source("mine", SourceKind.LEARNED, "My readings"))) { it + 2 }

    @After
    fun stop() = scope.cancel()

    @Test
    fun fedValuesOutrankTheCarsAndLeaveWhenTheReadingDoes() {
        car.put(K.HANDBRAKE, false)
        feeds.set(ReadingFeed.HANDBRAKE, 2.0)
        assertEquals(true, bus.now(K.HANDBRAKE))
        feeds.setAll(emptyMap())
        assertEquals(false, bus.now(K.HANDBRAKE))
    }

    @Test
    fun theSpeedIsCorrectedAsEveryCarSpeedIs() {
        feeds.set(ReadingFeed.SPEED, 50.0)
        assertEquals(52.0, bus.now(K.VEHICLE_SPEED)!!, 0.0)
    }

    @Test
    fun oneDoorHeardKeepsTheOthersItKnows() {
        feeds.setAll(mapOf(ReadingFeed.DOOR_FRONT_LEFT to 0.0, ReadingFeed.DOOR_FRONT_RIGHT to 1.0))
        feeds.set(ReadingFeed.DOOR_FRONT_LEFT, 1.0)
        assertEquals(McuReader.DoorState(frontLeft = true, frontRight = true), bus.now(K.DOORS))
        feeds.clear()
        assertNull(bus.now(K.DOORS))
    }
}
