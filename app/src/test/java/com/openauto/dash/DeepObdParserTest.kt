package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepObdParserTest {

    // As Deep OBD's DataContractJsonSerializer writes it.
    private val frame = """
        {"ObdData":[
          {"Name":"Motordrehzahl","Result":"STAT_MOTORDREHZAHL_WERT","Value":"2.150 1/min"},
          {"Name":"Vehicle speed","Result":"STAT_V_FZG_WERT","Value":"87,5 km/h"},
          {"Name":"Coolant temperature","Result":"STAT_KUEHLMITTELTEMPERATUR_WERT","Value":"92 °C"},
          {"Name":"Battery voltage","Result":"STAT_UBATT_WERT","Value":"14,2 V"},
          {"Name":"Intake manifold pressure","Result":"STAT_DRUCK","Value":"1012 hPa"},
          {"Name":"","Result":"x","Value":"1"}
        ],"PageName":"Motor"}
    """.trimIndent()

    @Test
    fun readsAFrame() {
        val f = DeepObdParser.frame(frame)!!
        assertEquals("Motor", f.page)
        assertEquals(5, f.items.size)
        assertEquals("87,5 km/h", f.items[1].value)
    }

    @Test
    fun brokenJsonIsNull() {
        assertNull(DeepObdParser.frame("not json"))
        assertNull(DeepObdParser.frame("{\"PageName\":\"x\"}"))
    }

    @Test
    fun guessesFromLabelsAndResults() {
        val f = DeepObdParser.frame(frame)!!
        assertEquals(listOf(ObdField.RPM, ObdField.SPEED, ObdField.COOLANT, ObdField.VOLTAGE, null), f.items.map(DeepObdParser::guess))
        assertEquals(ObdField.RPM, DeepObdParser.guess(DeepItem("Engine speed", "", "800 rpm")))
        assertNull(DeepObdParser.guess(DeepItem("Fan speed", "", "40 %")))
        assertNull(DeepObdParser.guess(DeepItem("Fuel pressure", "", "350 kPa")))
        assertEquals(ObdField.INTAKE, DeepObdParser.guess(DeepItem("Intake air temperature", "", "25 °C")))
    }

    @Test
    fun readsNumbersAndUnits() {
        assertEquals(87.5, DeepObdParser.reading(ObdField.SPEED, "87,5 km/h")!!, 0.001)
        assertEquals(80.47, DeepObdParser.reading(ObdField.SPEED, "50 mph")!!, 0.01)
        assertEquals(90.0, DeepObdParser.reading(ObdField.COOLANT, "194 °F")!!, 0.001)
        assertEquals(-7.0, DeepObdParser.reading(ObdField.INTAKE, "-7 °C")!!, 0.001)
        assertNull(DeepObdParser.reading(ObdField.RPM, "---"))
        assertEquals(2150.0, DeepObdParser.reading(ObdField.RPM, "2.150 1/min")!!, 0.001)
        assertEquals(850.0, DeepObdParser.reading(ObdField.RPM, "850 rpm")!!, 0.001)
    }

    @Test
    fun laysReadingsOverTheLastOnes() {
        val base = ObdData(speedKmh = 10, rpm = 900, fuelLevelPct = 40)
        val next = DeepObdParser.apply(base, mapOf(ObdField.SPEED to 87.5, ObdField.VOLTAGE to 14.2, ObdField.THROTTLE to 140.0)) { it + 2 }
        assertEquals(90, next.speedKmh)
        assertEquals(900, next.rpm)
        assertEquals(40, next.fuelLevelPct)
        assertEquals(100, next.throttlePct)
        assertEquals(14.2, next.voltage, 0.001)
        assertEquals(true, next.voltageFromEcu)
    }
}
