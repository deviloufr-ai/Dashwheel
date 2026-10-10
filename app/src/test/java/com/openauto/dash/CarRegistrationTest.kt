package com.openauto.dash

import com.openauto.dash.link.CarRegistration
import com.openauto.dash.link.CritAir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/** The registration certificate in the car's profile, and the dates it gives. */
class CarRegistrationTest {
    private val scanned = CarRegistration("AB-123-CD", "2011-03-15", "VF7UDRHJ8BJ512345", "CITROEN", "C4 PICASSO", "GO", 82, 1560, 5)

    @Test
    fun aNewCarIsNamedFromItsCertificate() {
        val car = CarProfile.NONE.withRegistration(scanned)
        assertEquals("AB-123-CD", car.plate)
        assertEquals("VF7UDRHJ8BJ512345", car.vin)
        assertEquals("Citroën", car.make)
        assertEquals("C4 Picasso", car.model)
        assertEquals(2011, car.year)
        assertEquals("Citroën C4 Picasso 2011", car.name)
        assertEquals(FuelType.DIESEL, car.fuel)
        assertEquals(111, car.powerHp)
        assertEquals(2, car.critAir)
    }

    @Test
    fun theDriversOwnSpecsStay() {
        val mine = CarProfile.PRESET.copy(make = "Citroën", model = "C4 Picasso", year = 2011, powerHp = 110)
        val car = mine.withRegistration(scanned.copy(model = "C4 PICASSO II"))
        assertEquals(mine.name, car.name)
        assertEquals("C4 Picasso", car.model)
        assertEquals(110, car.powerHp)
        assertEquals("AB-123-CD", car.plate)
    }

    @Test
    fun aBlankFieldKeepsWhatWasThere() {
        val car = CarProfile.NONE.withRegistration(scanned).withRegistration(CarRegistration(plate = "EF-456-GH"))
        assertEquals("EF-456-GH", car.plate)
        assertEquals("2011-03-15", car.firstRegistration)
    }

    @Test
    fun critAirWithoutAScanFollowsTheProfilesFuel() {
        assertEquals(1, CarProfile(name = "x", fuel = FuelType.PETROL, firstRegistration = "2015-01-01").critAir)
        assertEquals(CritAir.UNCLASSED, CarProfile(name = "x", fuel = FuelType.DIESEL, firstRegistration = "1995-01-01").critAir)
        assertNull(CarProfile(name = "x", fuel = FuelType.DIESEL).critAir)
    }

    @Test
    fun keptThroughJson() {
        val car = CarProfile.NONE.withRegistration(scanned)
        assertEquals(car, CarProfile.fromJson(car.toJson()))
    }

    @Test
    fun theFirstInspectionCountsFromTheFirstRegistration() {
        val now = 1_790_000_000_000L
        fun monthsAgo(n: Int) = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -n) }.timeInMillis
        val plan = listOf(UpkeepInterval(UpkeepKind.INSPECTION, everyMonths = 24))

        // Registered 47 months ago: the first inspection is a month away.
        val young = UpkeepRules.statuses(plan, emptyMap(), null, now, registeredAt = monthsAgo(47)).single()
        assertEquals(UpkeepStage.SOON, young.stage)
        // Older than four years: only the logged inspection says when the next is.
        val old = UpkeepRules.statuses(plan, emptyMap(), null, now, registeredAt = monthsAgo(60)).single()
        assertEquals(UpkeepStage.UNKNOWN, old.stage)
        // A logged inspection wins.
        val logged = UpkeepRules.statuses(plan, mapOf(UpkeepKind.INSPECTION to UpkeepDone(at = monthsAgo(1))), null, now, registeredAt = monthsAgo(47)).single()
        assertEquals(UpkeepStage.OK, logged.stage)
    }
}
