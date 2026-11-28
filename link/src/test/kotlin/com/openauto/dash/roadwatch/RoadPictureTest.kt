package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class RoadPictureTest {
    private val band = RoadBand(horizonY = 0.5f, bonnetY = 0.92f)
    private val focal = 1f / (2 * tan(Math.toRadians(33.0)).toFloat())
    private val aspect = 0.75f

    /** A [kind] as the camera (66° across, 1.3 m up) boxes it [d] metres ahead and [dx] metres aside, its foot on the road. */
    private fun boxOf(kind: ThingKind, d: Float, dx: Float = 0f): Box {
        val w = kind.widthM * focal / d
        val hgt = kind.heightM * focal / d / aspect
        val cx = 0.5f + dx * focal / d
        val foot = 0.5f + GroundPlace.CAMERA_HEIGHT_M * focal / d / aspect
        return Box(cx - w / 2, foot - hgt, cx + w / 2, foot)
    }

    private fun place(box: Box, kind: ThingKind) = GroundPlace.of(box, kind, band, LaneState(), 0.5f, 66f, aspect)

    @Test
    fun aCarIsPlacedWhereItStands() {
        val p = place(boxOf(ThingKind.CAR, 30f, 3.5f), ThingKind.CAR)
        assertEquals(30f, p.z, 0.5f)
        assertEquals(3.5f, p.x, 0.2f)
        assertTrue(p.sure)
    }

    @Test
    fun aPersonIsMeasuredByTheirHeight() {
        val p = place(boxOf(ThingKind.PERSON, 15f, -4f), ThingKind.PERSON)
        assertEquals(15f, p.z, 0.3f)
        assertEquals(-4f, p.x, 0.2f)
    }

    @Test
    fun aBoxWhoseFootAndSizeDisagreeIsUnsure() {
        // A car on a rise: as wide as one 30 m ahead, its foot where one 10 m ahead would stand.
        val far = boxOf(ThingKind.CAR, 30f)
        val near = boxOf(ThingKind.CAR, 10f)
        val p = place(Box(far.left, near.bottom - far.height, far.right, near.bottom), ThingKind.CAR)
        assertFalse(p.sure)
        assertEquals(30f, p.z, 0.5f)
    }

    @Test
    fun aTrafficLightAboveTheHorizonIsMeasuredByItsSize() {
        val h = ThingKind.TRAFFIC_LIGHT.heightM * focal / 40f / aspect
        val p = place(Box(0.6f, 0.3f, 0.61f, 0.3f + h), ThingKind.TRAFFIC_LIGHT)
        assertEquals(40f, p.z, 0.5f)
        assertTrue(p.x > 0f)
    }

    @Test
    fun theLitLampIsRead() {
        // A dark housing with one lamp lit in its top, middle or bottom third.
        fun lamp(third: Int, colour: Int): (Float, Float) -> Int = { _, y -> if ((y * 3).toInt() == third) colour else 0x202020 }
        assertEquals(LightColour.RED, LightColour.of(lamp(0, 0xFF3C32)))
        assertEquals(LightColour.AMBER, LightColour.of(lamp(1, 0xFFB428)))
        assertEquals(LightColour.GREEN, LightColour.of(lamp(2, 0x3CFFC8)))
        assertNull(LightColour.of { _, _ -> 0x202020 })
        // Washed out to white: no telling.
        assertNull(LightColour.of { _, _ -> 0xFFFFFF })
    }

    @Test
    fun aThingMissedForAFrameStaysAndThenGoes() {
        val tracker = ThingTracker()
        val seen = Seen(boxOf(ThingKind.CAR, 20f), ThingKind.CAR)
        assertEquals(1, tracker.update(listOf(seen to Placed(0f, 20f)), null, 0).size)
        assertEquals(1, tracker.update(emptyList(), null, 300).size)
        assertEquals(0, tracker.update(emptyList(), null, 600).size)
    }

    @Test
    fun theLeadIsDrawnAtTheWarningsDistance() {
        val box = boxOf(ThingKind.CAR, 20f)
        val things = ThingTracker().update(listOf(Seen(box, ThingKind.CAR) to Placed(0f, 23f, sure = false)), Lead(box, VehicleKind.CAR, 20f), 0)
        assertTrue(things.single().lead)
        assertEquals(20f, things.single().z, 0.01f)
    }

    @Test
    fun aLaneIsDrawnOnlyWhereAVehicleWasSeen() {
        val p = RoadPicture(listOf(Thing(ThingKind.CAR, 3.4f, 20f), Thing(ThingKind.PERSON, -6f, 10f)))
        assertEquals(1, p.lanesBeside(1))
        assertEquals(0, p.lanesBeside(-1))
    }

    @Test
    fun thePictureCrossesTheLinkWhole() {
        val picture = RoadPicture(
            listOf(Thing(ThingKind.TRAFFIC_LIGHT, 5f, 40f, light = LightColour.RED), Thing(ThingKind.CAR, 0f, 20f, sure = false, lead = true)),
            offset = -0.1f, leftSeen = true, warning = RoadWarning.TOO_CLOSE, travelledM = 12.5f,
            around = Surroundings(listOf(0f, 0f, 2f, 50f), listOf(SideRoad(listOf(0f, 30f, 40f, 30f), 3f)), listOf(Block(listOf(8f, 10f, 18f, 10f, 18f, 20f), 9f)))
        )
        val sent = com.openauto.dash.link.LinkCodec.encode(com.openauto.dash.link.RoadSketch(picture))
        assertEquals(com.openauto.dash.link.RoadSketch(picture), com.openauto.dash.link.LinkCodec.decode(sent))
    }

    @Test
    fun aBendCarriesWhatStandsOnTheRoadWithIt() {
        // The road bends 10 m to the right by 50 m ahead: a car there is drawn that much further right, whole.
        val car = Thing(ThingKind.CAR, 0f, 50f)
        fun shadow(p: RoadPicture) = RoadPainter.paint(p, 1000f, 500f, RoadLook.DAY).filterIsInstance<RoadShape.Poly>()
            .first { it.argb ushr 24 in 1..90 && it.argb and 0xFFFFFF == 0x5A6373 }.points
        val straight = shadow(RoadPicture(listOf(car)))
        val bent = shadow(RoadPicture(listOf(car), around = Surroundings(listOf(0f, 0f, 10f, 50f, 20f, 100f))))
        assertTrue(bent[0] > straight[0] + 20f)
        // Not sheared: as wide at its far end as on the straight road.
        assertEquals(straight[4] - straight[6], bent[4] - bent[6], 0.01f)
        assertEquals(straight[2] - straight[0], bent[2] - bent[0], 0.01f)
    }

    @Test
    fun farThingsArePaintedFirstAndSmaller() {
        val picture = RoadPicture(listOf(Thing(ThingKind.CAR, 0f, 10f, lead = true), Thing(ThingKind.CAR, 0f, 60f)), leadLabel = "10 m")
        val shapes = RoadPainter.paint(picture, 1000f, 500f, RoadLook.DAY)
        // Each vehicle starts with its shadow, the only see-through four-cornered shapes after the road's.
        val shadows = shapes.filterIsInstance<RoadShape.Poly>().filter { it.argb ushr 24 in 1..90 && it.argb and 0xFFFFFF == 0x5A6373 }
        assertEquals(3, shadows.size)
        fun width(s: RoadShape.Poly) = s.points[2] - s.points[0]
        // The far car, the near car, then the driver's own: each lower on the screen and wider than the last.
        assertTrue(width(shadows[0]) < width(shadows[1]) && width(shadows[1]) < width(shadows[2]))
        assertTrue(shadows[0].points[1] < shadows[1].points[1] && shadows[1].points[1] < shadows[2].points[1])
        val label = shapes.last() as RoadShape.Label
        assertEquals("10 m", label.text)
        assertEquals(500f, label.x, 0.5f)
    }
}
