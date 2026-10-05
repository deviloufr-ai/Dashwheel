package com.openauto.dash

import com.openauto.dash.link.DriveSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NewWidgetsTest {

    @Test
    fun maxspeedTagsAreRead() {
        assertEquals(80, parseMaxspeed("80"))
        assertEquals(48, parseMaxspeed("30 mph"))
        assertEquals(50, parseMaxspeed("FR:urban"))
        assertEquals(80, parseMaxspeed("FR:rural"))
        assertEquals(130, parseMaxspeed("FR:motorway"))
        assertNull(parseMaxspeed("signals"))
        assertNull(parseMaxspeed(null))
    }

    @Test
    fun theRoadIsTheNearestOneGoingTheCarsWay() {
        // A north-south road 10 m east, an east-west one 15 m north.
        val northSouth = LimitedRoad(50, listOf(45.0 to 5.00013, 45.01 to 5.00013))
        val eastWest = LimitedRoad(90, listOf(45.000135 to 4.99, 45.000135 to 5.01))
        val roads = listOf(northSouth, eastWest)
        assertSame(northSouth, roadAt(45.0, 5.0, 0f, roads))
        assertSame(northSouth, roadAt(45.0, 5.0, 180f, roads))
        assertSame(eastWest, roadAt(45.0, 5.0, 90f, roads))
        assertSame(northSouth, roadAt(45.0, 5.0, null, roads))
        assertNull(roadAt(45.1, 5.0, null, roads))
    }

    private val feed = """
        <feed><entry>
          <cap:areaDesc>Drôme</cap:areaDesc><cap:event>Moderate thunderstorm warning</cap:event>
          <cap:expires>2026-10-06T22:00:00+00:00</cap:expires><cap:onset>2026-10-05T22:00:00+00:00</cap:onset>
          <cap:severity>Moderate</cap:severity>
          <title>Orange Thunderstorm Warning issued for France - Drôme</title>
        </entry><entry>
          <cap:areaDesc>Ain</cap:areaDesc><cap:event>Fog</cap:event>
          <cap:expires>2020-01-01T00:00:00+00:00</cap:expires><cap:severity>Moderate</cap:severity>
          <title>Yellow Fog Warning issued for France - Ain</title>
        </entry></feed>
    """.trimIndent()

    @Test
    fun meteoAlarmEntriesAreRead() {
        val now = java.time.OffsetDateTime.parse("2026-10-05T18:00:00+00:00").toInstant().toEpochMilli()
        val w = WeatherWarnings.parse(feed, now)
        assertEquals(1, w.size)
        assertEquals("Drôme", w[0].area)
        assertEquals("thunderstorm", w[0].hazard)
        assertEquals(2, w[0].level)
    }

    @Test
    fun areasMatchAcrossSpellings() {
        val w = WeatherWarning("Drôme", "rain", "", 1, 0, 0)
        assertTrue(WeatherWarnings.inArea(w, listOf("Drome", "Auvergne-Rhône-Alpes")))
        assertFalse(WeatherWarnings.inArea(WeatherWarning("Loire", "rain", "", 1, 0, 0), listOf("Haute-Loire")))
        assertTrue(WeatherWarnings.inArea(WeatherWarning("Kreis Kassel", "rain", "", 1, 0, 0), listOf("Landkreis Kassel")))
    }

    private fun drive(start: Long, minutes: Int, lat: Double?, lng: Double?) =
        DriveSummary(startedAt = start, endedAt = start + minutes * 60_000L, distanceKm = 10.0, movingMs = 0, maxSpeedKmh = 90, endLat = lat, endLng = lng)

    @Test
    fun commutesAreTheDrivesEndingAtThePlace() {
        val flat = { aLat: Double, aLng: Double, bLat: Double, bLng: Double ->
            (Math.hypot(aLat - bLat, aLng - bLng) * 111_000).toFloat()
        }
        val drives = listOf(
            drive(5_000_000, 25, 45.0, 5.0),
            drive(4_000_000, 31, 45.001, 5.0),
            drive(3_000_000, 40, 45.1, 5.0),
            drive(2_000_000, 22, null, null)
        )
        val c = Commutes.to(drives, 45.0, 5.0, flat)
        assertEquals(listOf(25, 31), c.minutes)
        assertEquals(28, c.average)
        assertEquals(25, c.best)
    }
}
