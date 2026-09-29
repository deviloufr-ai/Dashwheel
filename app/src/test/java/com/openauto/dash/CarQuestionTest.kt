package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Asking the mechanic about the car as it is now: what Gemini is told about it. */
class CarQuestionTest {

    private val car = CarProfile.PRESET.promptDescription()

    @Test
    fun onlyWhatIsKnownIsSent() {
        // No adapter, no fuel signal, no scan: nothing to report but that.
        val lines = CarQuestionPrompt.readings(CarSnapshot(car))
        assertEquals(listOf("no live engine readings: the OBD adapter is not connected"), lines)
        val prompt = CarQuestionPrompt.build(CarSnapshot(car), AiLanguage.ENGLISH)
        assertTrue(prompt.contains("- no live engine readings"))
        assertFalse(prompt.contains("coolant"))
        assertFalse(prompt.contains("fault codes"))
        assertTrue(prompt.contains("never invent"))
    }

    @Test
    fun liveReadingsAreSpelledOut() {
        val s = CarSnapshot(
            car, obdLive = true, rpm = 850, speedKmh = 50, coolantC = 88, hotC = 90, volts = 14.12, voltsFromEcu = true,
            fuelPercent = 23, rangeKm = 180, rangeFromCar = true, lampOn = false, codes = emptyList()
        )
        val lines = CarQuestionPrompt.readings(s)
        assertTrue(lines.contains("engine running at 850 rpm, speed 50 km/h"))
        assertTrue(lines.contains("coolant 88 °C (this engine runs at about 90 °C once warm)"))
        assertTrue(lines.contains("battery 14.1 V with the engine running"))
        assertTrue(lines.contains("fuel 23 %, about 180 km of range (the car's own figure)"))
        assertTrue(lines.contains("engine warning lamp off"))
        assertTrue(lines.contains("no stored fault codes at the last scan"))
    }

    @Test
    fun engineOffAndAnAdapterVoltageAreSaidAsSuch() {
        val s = CarSnapshot(car, obdLive = true, rpm = 0, speedKmh = 0, volts = 12.2)
        val lines = CarQuestionPrompt.readings(s)
        assertTrue(lines.contains("engine not running"))
        assertTrue(lines.contains("battery 12.2 V with the engine off (read at the adapter, which reads about 0.4 V low)"))
        assertFalse(lines.any { it.contains("speed") })
    }

    @Test
    fun codesCarryTheirMeaningAndTheMechanicsAdvice() {
        val advice = CodeAdvice("P1352", "Glow plug relay", listOf("Faulty relay"), "The fuse", driving = "Fine once warm")
        val d = Diagnosis(Severity.SOON, "Glow plugs to check.", listOf(advice))
        val s = CarSnapshot(
            car, lampOn = true, codes = listOf("P1352", "P0401"), pending = setOf("P0401"), diagnosis = d,
            codeMeanings = mapOf("P0401" to "EGR flow insufficient")
        )
        val prompt = CarQuestionPrompt.build(s, AiLanguage.ENGLISH)
        assertTrue(prompt.contains("engine warning lamp on"))
        assertTrue(prompt.contains("stored fault codes: P1352\n"))
        assertTrue(prompt.contains("pending fault codes, not confirmed yet: P0401 (EGR flow insufficient)"))
        assertTrue(prompt.contains("What you already told the driver about the codes: Glow plugs to check."))
        assertTrue(prompt.contains("- P1352: Glow plug relay. likely causes: Faulty relay. check first: The fuse. driving: Fine once warm"))
    }

    @Test
    fun filterServicingAndExtraReadingsAreIncluded() {
        val s = CarSnapshot(
            car,
            filterShortStreak = 4,
            upkeep = listOf(
                UpkeepDue(UpkeepKind.OIL, kmLeft = -300, daysLeft = 20, stage = UpkeepStage.DUE),
                UpkeepDue(UpkeepKind.TIMING_BELT, kmLeft = 800, daysLeft = null, stage = UpkeepStage.SOON)
            ),
            extras = listOf(
                CarQuestionPrompt.extraLine(ExtraReading.SOOT_LOAD, 45.0),
                CarQuestionPrompt.extraLine(ExtraReading.REGEN_ACTIVE, 1.0)
            )
        )
        val lines = CarQuestionPrompt.readings(s)
        assertTrue(lines.contains("particulate filter: 4 short drives in a row since the last long one"))
        assertTrue(lines.contains("servicing: oil: due now, 300 km over; timing belt: soon, 800 km left"))
        assertTrue(lines.contains("other readings: soot load 45 %, particle filter regeneration running"))
    }

    @Test
    fun theAnswerFollowsTheMechanicsLanguageAndTheDriversUnits() {
        val s = CarSnapshot(car, obdLive = true, rpm = 900, volts = 13.9, voltsFromEcu = true)
        val prompt = CarQuestionPrompt.build(s, AiLanguage.GERMAN, UnitSystem(imperial = true), Exchange("Is it OK?", "Yes."))
        assertTrue(prompt.contains("Answer in German"))
        assertTrue(prompt.contains("written in German"))
        assertTrue(prompt.contains("attached recording"))
        // Figures are written one way for Gemini, whatever the language.
        assertTrue(prompt.contains("battery 13.9 V"))
        assertTrue(prompt.contains("the driver uses miles and mph"))
        assertTrue(prompt.contains("previous question was \"Is it OK?\""))
        assertTrue(prompt.contains("stop somewhere safe"))
        assertFalse(CarQuestionPrompt.build(s, AiLanguage.FRENCH).contains("miles"))
    }
}
