package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** The servicing planner's rules: intervals, what's left, what gets said. */
class UpkeepRulesTest {

    private val now = 1_700_000_000_000L
    private val day = 24 * 3_600_000L

    private fun monthsAgo(n: Int) = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -n) }.timeInMillis

    @Test
    fun theDefaultPlanFollowsTheCar() {
        val diesel = UpkeepRules.defaultPlan(CarProfile.PRESET.copy(serviceKm = 20_000, serviceMonths = 24))
        val oil = diesel.first { it.kind == UpkeepKind.OIL }
        assertEquals(20_000, oil.everyKm)
        assertEquals(24, oil.everyMonths)
        assertTrue(diesel.any { it.kind == UpkeepKind.FUEL_FILTER })
        assertTrue(diesel.any { it.kind == UpkeepKind.ADDITIVE })
        assertTrue(diesel.any { it.kind == UpkeepKind.GEARBOX_OIL })
        assertTrue(diesel.none { it.kind == UpkeepKind.SPARK_PLUGS })

        val petrolChain = UpkeepRules.defaultPlan(CarProfile(name = "x", fuel = FuelType.PETROL, timing = "chain"))
        assertTrue(petrolChain.any { it.kind == UpkeepKind.SPARK_PLUGS })
        assertTrue(petrolChain.none { it.kind == UpkeepKind.TIMING_BELT })
        assertTrue(petrolChain.none { it.kind == UpkeepKind.ADDITIVE })
    }

    @Test
    fun kmAndDatesDecideTheStage() {
        val oil = UpkeepInterval(UpkeepKind.OIL, 20_000, 24)
        assertEquals(UpkeepStage.UNKNOWN, UpkeepRules.status(oil, null, 150_000, now).stage)
        // Done 19,500 km ago: soon.
        val soon = UpkeepRules.status(oil, UpkeepDone(km = 130_500), 150_000, now)
        assertEquals(500, soon.kmLeft)
        assertEquals(UpkeepStage.SOON, soon.stage)
        // Done 21,000 km ago: due.
        assertEquals(UpkeepStage.DUE, UpkeepRules.status(oil, UpkeepDone(km = 129_000), 150_000, now).stage)
        // Recent by km but 25 months old: due by date.
        val old = UpkeepRules.status(oil, UpkeepDone(km = 148_000, at = monthsAgo(25)), 150_000, now)
        assertEquals(UpkeepStage.DUE, old.stage)
        assertTrue(old.daysLeft!! < 0)
        // Both fine.
        assertEquals(UpkeepStage.OK, UpkeepRules.status(oil, UpkeepDone(km = 140_000, at = monthsAgo(3)), 150_000, now).stage)
        // No mileage known: only the date counts.
        assertEquals(UpkeepStage.OK, UpkeepRules.status(oil, UpkeepDone(km = 100, at = monthsAgo(3)), null, now).stage)
    }

    @Test
    fun theDueOnesComeFirst() {
        val plan = listOf(
            UpkeepInterval(UpkeepKind.OIL, 20_000, 24),
            UpkeepInterval(UpkeepKind.BRAKE_FLUID, everyMonths = 24),
            UpkeepInterval(UpkeepKind.TIMING_BELT)
        )
        val done = mapOf(UpkeepKind.OIL to UpkeepDone(km = 140_000), UpkeepKind.BRAKE_FLUID to UpkeepDone(at = monthsAgo(30)))
        val list = UpkeepRules.statuses(plan, done, 150_000, now)
        assertEquals(listOf(UpkeepKind.BRAKE_FLUID, UpkeepKind.OIL, UpkeepKind.TIMING_BELT), list.map { it.kind })
        assertEquals(UpkeepStage.UNKNOWN, list.last().stage)
    }

    @Test
    fun eachStageIsSaidOnce() {
        val soon = UpkeepDue(UpkeepKind.OIL, 800, null, UpkeepStage.SOON)
        val ok = UpkeepDue(UpkeepKind.COOLANT, 9_000, null, UpkeepStage.OK)
        assertEquals(listOf(soon), UpkeepRules.toSpeak(listOf(soon, ok), emptyMap()))
        assertTrue(UpkeepRules.toSpeak(listOf(soon, ok), mapOf(UpkeepKind.OIL to UpkeepStage.SOON)).isEmpty())
        // Slipping into "due" is news again.
        val due = soon.copy(kmLeft = -100, stage = UpkeepStage.DUE)
        assertEquals(listOf(due), UpkeepRules.toSpeak(listOf(due), mapOf(UpkeepKind.OIL to UpkeepStage.SOON)))
    }

    @Test
    fun theSentenceNamesTheItemAndTheCount() {
        val km = UpkeepRules.line(UpkeepDue(UpkeepKind.OIL, 800, 200, UpkeepStage.SOON))
        assertEquals(R.string.upkeep_say_soon_km, km.res)
        assertEquals(800, km.args[1])
        assertEquals(R.string.upkeep_oil, (km.args[0] as SpokenLine).res)
        val days = UpkeepRules.line(UpkeepDue(UpkeepKind.BRAKE_FLUID, null, 12, UpkeepStage.SOON))
        assertEquals(R.string.upkeep_say_soon_days, days.res)
        assertEquals(12, days.args[1])
        val overdue = UpkeepRules.line(UpkeepDue(UpkeepKind.OIL, -300, 100, UpkeepStage.DUE))
        assertEquals(R.string.upkeep_say_overdue_km, overdue.res)
        assertEquals(300, overdue.args[1])
        val late = UpkeepRules.line(UpkeepDue(UpkeepKind.BRAKE_FLUID, 5_000, -20, UpkeepStage.DUE))
        assertEquals(R.string.upkeep_say_overdue_days, late.res)
        assertEquals(20, late.args[1])
    }

    @Test
    fun theMileageAdvancesWithTheDrives() {
        val odo = Odometer(km = 150_000, readAt = now, drivenSince = 12.6)
        assertEquals(150_013, odo.nowKm)
    }
}

/** Reading Gemini's service-plan answer. */
class UpkeepPlanTest {

    @Test
    fun knownIntervalsReplaceTheDefaults() {
        val base = UpkeepRules.defaultPlan(CarProfile.PRESET)
        val ai = UpkeepPlan.read(
            """{"items":[{"kind":"OIL","every_km":20000,"every_months":24},{"kind":"TIMING_BELT","every_km":240000,"every_months":120},
               {"kind":"BRAKE_FLUID","every_km":null,"every_months":null},{"kind":"SPARK_PLUGS","every_km":60000,"every_months":null},
               {"kind":"NOT_A_KIND","every_km":1,"every_months":1}]}"""
        )
        assertEquals(4, ai.size)
        val merged = UpkeepRules.merge(base, ai)
        assertEquals(20_000, merged.first { it.kind == UpkeepKind.OIL }.everyKm)
        assertEquals(240_000, merged.first { it.kind == UpkeepKind.TIMING_BELT }.everyKm)
        // The AI didn't know: the default (24 months) stays.
        assertEquals(24, merged.first { it.kind == UpkeepKind.BRAKE_FLUID }.everyMonths)
        // A kind the defaults left out but the AI knows is added.
        assertEquals(60_000, merged.first { it.kind == UpkeepKind.SPARK_PLUGS }.everyKm)
        assertNull(merged.first { it.kind == UpkeepKind.COOLANT }.everyKm)
    }

    @Test
    fun whatTheDriverTypedSurvivesTheMakersIntervals() {
        val typed = UpkeepRules.defaultPlan(CarProfile.PRESET).map {
            if (it.kind == UpkeepKind.OIL) it.copy(everyKm = 15_000, everyMonths = null, own = true) else it
        } + UpkeepInterval(UpkeepKind.SPARK_PLUGS, everyKm = 45_000, own = true)
        val ai = listOf(UpkeepInterval(UpkeepKind.OIL, 20_000, 24), UpkeepInterval(UpkeepKind.COOLANT, 120_000, 60))
        val plan = UpkeepRules.keepOwn(UpkeepRules.merge(UpkeepRules.defaultPlan(CarProfile.PRESET), ai), typed)
        val oil = plan.first { it.kind == UpkeepKind.OIL }
        assertEquals(15_000, oil.everyKm)
        assertNull(oil.everyMonths)
        // The rest still takes the maker's figures, and an item only the driver has stays.
        assertEquals(120_000, plan.first { it.kind == UpkeepKind.COOLANT }.everyKm)
        assertEquals(45_000, plan.first { it.kind == UpkeepKind.SPARK_PLUGS }.everyKm)
        assertEquals(plan.size, plan.distinctBy { it.kind }.size)
    }

    @Test
    fun thePromptAndSchemaNameEveryKind() {
        val prompt = UpkeepPlan.prompt("Citroën C4 Picasso")
        assertTrue(prompt.contains("Citroën C4 Picasso"))
        assertTrue(prompt.contains("TIMING_BELT"))
        val kinds = UpkeepPlan.SCHEMA.getJSONObject("properties").getJSONObject("items").getJSONObject("items")
            .getJSONObject("properties").getJSONObject("kind").getJSONArray("enum")
        assertEquals(UpkeepKind.entries.size, kinds.length())
    }
}
