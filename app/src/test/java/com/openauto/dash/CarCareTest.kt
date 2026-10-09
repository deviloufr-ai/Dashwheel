package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drive monitor's rules: drives, the particle filter streak, alerts and the break timer. */
class CareRulesTest {

    private val car = CarProfile.PRESET
    private val t0 = 1_000_000_000L

    /** Feeds [d] every second for [seconds], returning the state and every event. */
    private fun run(s: CareState, d: ObdData, from: Long, seconds: Int): Triple<CareState, List<CareEvent>, Long> {
        var state = s
        val events = mutableListOf<CareEvent>()
        var now = from
        repeat(seconds) {
            val (next, e) = CareRules.step(state, d, now, car)
            state = next
            events += e
            now += 1000
        }
        return Triple(state, events, now)
    }

    private val cruising = ObdData(speedKmh = 90, rpm = 2000, coolantTempC = 90, engineLoadPct = 30)
    private val town = ObdData(speedKmh = 30, rpm = 1500, coolantTempC = 60, engineLoadPct = 25)
    private val off = ObdData()

    @Test
    fun aLongFastHotDriveResetsTheShortStreak() {
        val start = CareState(filter = FilterLog(shortStreak = 4, warnedStreak = 3))
        val (driving, _, t1) = run(start, cruising, t0, 11 * 60)
        assertTrue(driving.drive!!.filterFriendly)
        // Engine off long enough ends the drive.
        val (after, _, _) = run(driving, off, t1, 4 * 60)
        assertNull(after.drive)
        assertEquals(0, after.filter.shortStreak)
        assertTrue(after.filter.lastLongAt > 0)
    }

    @Test
    fun aTownDriveCountsAsShort() {
        val (driving, _, t1) = run(CareState(), town, t0, 8 * 60)
        val (after, _, _) = run(driving, off, t1, 4 * 60)
        assertEquals(1, after.filter.shortStreak)
        assertNotNull(after.lastDrive)
    }

    @Test
    fun aQuickRestartDoesNotCount() {
        val (driving, _, t1) = run(CareState(), town, t0, 60)
        val (after, _, _) = run(driving, off, t1, 4 * 60)
        assertEquals(0, after.filter.shortStreak)
    }

    @Test
    fun idlingWithoutMovingIsNotAShortTrip() {
        val idling = ObdData(speedKmh = 0, rpm = 800, coolantTempC = 80, engineLoadPct = 15)
        val (running, _, t1) = run(CareState(filter = FilterLog(shortStreak = 2)), idling, t0, 24 * 60)
        val (after, _, _) = run(running, off, t1, 4 * 60)
        assertNull(after.drive)
        assertEquals(2, after.filter.shortStreak)
    }

    @Test
    fun anEngineComputerGoneSilentEndsTheDrive() {
        // Key back to accessory: the adapter stays linked but nothing answers, and the
        // poll publishes the last readings with the engine stopped.
        val (driving, _, t1) = run(CareState(), town, t0, 8 * 60)
        val (after, _, _) = run(driving, town.engineStopped(), t1, 4 * 60)
        assertNull(after.drive)
        assertEquals(1, after.filter.shortStreak)
    }

    @Test
    fun aGapInTheReadingsEndsTheDrive() {
        val (driving, _, t1) = run(CareState(), town, t0, 5 * 60)
        val (next, _) = CareRules.step(driving, town, t1 + 10 * 60_000L, car)
        assertEquals(1, next.filter.shortStreak)
        // A new drive started at once.
        assertEquals(t1 + 10 * 60_000L, next.drive!!.startedAt)
    }

    @Test
    fun theFilterReminderComesEveryOtherShortDrive() {
        fun startWith(streak: Int, warned: Int) =
            CareRules.step(CareState(filter = FilterLog(shortStreak = streak, warnedStreak = warned)), town, t0, car)
        assertTrue(startWith(2, 0).second.isEmpty())
        val (s3, e3) = startWith(3, 0)
        assertEquals(listOf(CareEvent.FilterNeedsDrive(3)), e3)
        assertEquals(3, s3.filter.warnedStreak)
        assertTrue(startWith(4, 3).second.isEmpty())
        assertEquals(listOf(CareEvent.FilterNeedsDrive(5)), startWith(5, 3).second)
    }

    @Test
    fun noFilterReminderForACarWithoutOne() {
        val petrol = car.copy(particleFilter = false)
        val (_, e) = CareRules.step(CareState(filter = FilterLog(shortStreak = 5)), town, t0, petrol)
        assertTrue(e.isEmpty())
    }

    @Test
    fun revvingAColdEngineIsSaidOncePerDrive() {
        val cold = ObdData(speedKmh = 50, rpm = 3200, coolantTempC = 40)
        val (_, events, _) = run(CareState(), cold, t0, 60)
        assertEquals(1, events.count { it == CareEvent.ColdRevs })
    }

    @Test
    fun anUnknownCoolantTemperatureIsNotJudged() {
        val (_, events, _) = run(CareState(), ObdData(speedKmh = 50, rpm = 3200, coolantTempC = 0), t0, 30)
        assertTrue(events.isEmpty())
    }

    @Test
    fun holdingTheRobotisedGearboxOnTheThrottleIsCounted() {
        val holding = ObdData(speedKmh = 0, rpm = 1500, coolantTempC = 85, engineLoadPct = 60)
        val (s, events, _) = run(CareState(), holding, t0, 10)
        assertEquals(1, s.drive!!.clutchHolds)
        assertEquals(listOf(CareEvent.ClutchHold), events)
    }

    @Test
    fun aManualGearboxIsNotWarnedAboutTheClutch() {
        val holding = ObdData(speedKmh = 0, rpm = 1500, coolantTempC = 85, engineLoadPct = 60)
        var state = CareState()
        repeat(10) { state = CareRules.step(state, holding, t0 + it * 1000L, car.copy(gearbox = GearboxType.MANUAL)).first }
        assertEquals(0, state.drive!!.clutchHolds)
    }

    @Test
    fun hardBrakingIsCountedFromReadingsCloserThanTheSpan() {
        // An adapter polled every 150 ms: each step alone is under the 300 ms span.
        var (s, _) = CareRules.step(CareState(), ObdData(speedKmh = 80, rpm = 2000, coolantTempC = 90), t0, car)
        s = CareRules.step(s, ObdData(speedKmh = 80, rpm = 2000, coolantTempC = 90), t0 + 1000, car).first
        s = CareRules.step(s, ObdData(speedKmh = 75, rpm = 1900, coolantTempC = 90), t0 + 1150, car).first
        s = CareRules.step(s, ObdData(speedKmh = 70, rpm = 1800, coolantTempC = 90), t0 + 1300, car).first
        assertEquals(1, s.drive!!.hardBrake)
    }

    @Test
    fun hardBrakingIsCounted() {
        var (s, _) = CareRules.step(CareState(), ObdData(speedKmh = 80, rpm = 2000, coolantTempC = 90), t0, car)
        s = CareRules.step(s, ObdData(speedKmh = 80, rpm = 2000, coolantTempC = 90), t0 + 1000, car).first
        s = CareRules.step(s, ObdData(speedKmh = 60, rpm = 1600, coolantTempC = 90), t0 + 2000, car).first
        assertEquals(1, s.drive!!.hardBrake)
        assertEquals(0, s.drive!!.hardAccel)
    }

    @Test
    fun theBreakReminderComesAtTwoHoursThenEveryHalfHour() {
        val (s, events, _) = run(CareState(), cruising, t0, 151 * 60)
        val breaks = events.filterIsInstance<CareEvent.BreakDue>()
        assertEquals(listOf(120, 150), breaks.map { it.minutes })
        assertEquals(150, s.rest.spokenMin)
    }

    @Test
    fun aFifteenMinuteStopResetsTheBreakTimer() {
        val (driving, _, t1) = run(CareState(), cruising, t0, 60 * 60)
        val (stopped, _, _) = run(driving, off.copy(rpm = 800), t1, 16 * 60)
        assertEquals(0L, stopped.rest.drivingMs)
    }

    @Test
    fun theRemainingDistanceIsReadFromTheEtaLine() {
        assertEquals(6.4, CareRules.remainingKm("12 min · 6.4 km · 09:48")!!, 0.001)
        assertEquals(346.0, CareRules.remainingKm("3 h 10 · 346 km · 17:42")!!, 0.001)
        assertEquals(0.8, CareRules.remainingKm("2 min · 800 m")!!, 0.001)
        assertEquals(16.09, CareRules.remainingKm("20 min · 10 mi")!!, 0.01)
        assertEquals(12.5, CareRules.remainingKm("15 min · 12,5 km")!!, 0.001)
        assertNull(CareRules.remainingKm("Arrival 09:48"))
    }

    @Test
    fun theFuelVerdictLeavesASafetyMargin() {
        assertEquals(FuelVerdict.ENOUGH, CareRules.fuelVerdict(412, 346.0))
        assertEquals(FuelVerdict.TIGHT, CareRules.fuelVerdict(380, 346.0))
        assertEquals(FuelVerdict.SHORT, CareRules.fuelVerdict(300, 346.0))
        assertNull(CareRules.fuelVerdict(null, 346.0))
        assertNull(CareRules.fuelVerdict(400, null))
    }

    @Test
    fun theEcoScoreWaitsForAKilometreThenPenalisesHarshDriving() {
        assertNull(Drive(startedAt = t0, distanceKm = 0.5, movingMs = 60_000).ecoScore)
        assertEquals(100, Drive(startedAt = t0, distanceKm = 10.0, movingMs = 600_000).ecoScore)
        val harsh = Drive(startedAt = t0, distanceKm = 10.0, movingMs = 600_000, hardAccel = 5, hardBrake = 5)
        assertEquals(65, harsh.ecoScore)
    }
}

/** The car profile: saving and reading it back, and what it derives. */
class CarProfileTest {

    @Test
    fun aProfileSurvivesSavingAndReading() {
        val p = CarProfile.PRESET.copy(
            tyreSize = "215/55 R16", tyreFrontBar = 2.3, serviceKm = 20000, notes = listOf("Injector seals"),
            fuelPrice = 1.69, currency = "CHF", source = SpecSource.AI, updatedAt = 42
        )
        assertEquals(p, CarProfile.fromJson(p.toJson()))
    }

    @Test
    fun unknownSpecsStayUnknown() {
        val p = CarProfile.fromJson(CarProfile(name = "Test car").toJson())
        assertNull(p.tankL)
        assertNull(p.torqueRpm)
        assertEquals(TANK_LITERS, p.tank, 0.0)
    }

    @Test
    fun thePresetIsTheDriversC4PicassoWithTheBmp6() {
        val p = CarProfile.PRESET
        assertEquals(GearboxType.ROBOTISED, p.gearbox)
        assertTrue(p.particleFilter && p.filterAdditive)
        assertEquals(1750..2750, p.sweetBand)
        assertEquals(2500, p.coldRpmLimit)
        val line = p.promptDescription()
        assertTrue(line, line.contains("C4 Picasso 1.6 HDi 110"))
        assertTrue(line, line.contains("6-speed robotised single-clutch gearbox (BMP6)"))
        assertTrue(line, line.contains("Eolys"))
    }

    @Test
    fun aPetrolCarGetsPetrolDefaults() {
        val p = CarProfile(name = "Some petrol car", fuel = FuelType.PETROL)
        assertEquals(3000, p.coldRpmLimit)
        assertEquals(2500..3500, p.sweetBand)
    }
}

/** Reading Gemini's spec answer. */
class CarSpecsTest {

    @Test
    fun knownSpecsReplaceTheBaseAndNullsKeepIt() {
        val answer = """
            ```json
            {"engine":"1.6 HDi 110 FAP (DV6TED4)","fuel":"DIESEL","power_hp":109,"torque_nm":240,"torque_rpm":1750,
             "redline_rpm":null,"gearbox":"ROBOTISED","gearbox_name":"BMP6","gears":6,"tank_l":60,"consumption_l100":5.9,
             "particle_filter":true,"filter_additive":true,"oil_capacity_l":3.75,"oil_spec":"5W-30 PSA B71 2290",
             "service_km":20000,"service_months":24,"timing":"belt","tyre_size":"215/55 R16","tyre_front_bar":2.4,
             "tyre_rear_bar":2.3,"battery_ah":null,"operating_temp_c":90,"notes":["Injector copper seals leak"]}
            ```
        """.trimIndent()
        val base = CarProfile.PRESET.copy(redlineRpm = 4500, fuelPrice = 1.8)
        val p = CarSpecs.read(answer, base, now = 7)
        assertEquals(109, p.powerHp)
        assertEquals(4500, p.redlineRpm)
        assertEquals(5.9, p.consumptionL100!!, 0.0)
        assertEquals("215/55 R16", p.tyreSize)
        assertEquals(24, p.serviceMonths)
        assertNull(p.batteryAh)
        assertEquals(listOf("Injector copper seals leak"), p.notes)
        // The driver's own figures are kept.
        assertEquals(base.name, p.name)
        assertEquals(1.8, p.fuelPrice, 0.0)
        assertEquals(SpecSource.AI, p.source)
        assertEquals(7L, p.updatedAt)
    }

    @Test
    fun thePromptNamesTheCarAndTheNotesLanguage() {
        val prompt = CarSpecs.prompt(CarProfile.PRESET.name, AiLanguage.FRENCH)
        assertTrue(prompt.contains("C4 Picasso 1.6 HDi 110 FAP Exclusive 2011, BMP6"))
        assertTrue(prompt.contains("in French"))
    }

    @Test
    fun theSchemaAsksForEverySpec() {
        val required = CarSpecs.SCHEMA.getJSONArray("required")
        val names = (0 until required.length()).map { required.getString(it) }
        assertTrue(names.containsAll(listOf("engine", "tank_l", "tyre_size", "notes", "gearbox")))
    }
}
