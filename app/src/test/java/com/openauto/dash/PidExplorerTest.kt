package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The experimental reading finder: formulas, replies, and what the AI may propose. */
class FormulaTest {

    private val bytes = listOf(0x12, 0x34, 0xFF, 0x00)

    @Test
    fun theUsualObdFormulasWork() {
        assertEquals(0x12 - 40.0, Formula.eval("A-40", bytes)!!, 0.0)
        assertEquals(((0x12 * 256) + 0x34) / 10.0 - 40, Formula.eval("(A*256+B)/10-40", bytes)!!, 1e-9)
        assertEquals(0x12 * 100 / 255.0, Formula.eval("A*100/255", bytes)!!, 1e-9)
        assertEquals(0xFF * 0.5, Formula.eval(" C * 0.5 ", bytes)!!, 1e-9)
        assertEquals(-18.0, Formula.eval("-A", bytes)!!, 0.0)
    }

    @Test
    fun theStandardFilterTemperatureSkipsTheSupportByte() {
        // 017C: A says which sensors exist, the inlet temperature is in B and C (J1979).
        val formula = PidProbe.STANDARD.first { it.request == "017C" }.formula
        assertEquals(0x1388 / 10.0 - 40, Formula.eval(formula, listOf(0x03, 0x13, 0x88, 0x00, 0x00))!!, 1e-9)
    }

    @Test
    fun onlyAnOldGpuIsRefusedTheMap() {
        assertTrue(gles3("196610"))   // 3.2
        assertTrue(gles3("196608"))   // 3.0
        assertTrue(!gles3("131072"))  // 2.0
        assertTrue(gles3(null))       // a unit that does not say
        assertTrue(gles3("unknown"))
    }

    @Test
    fun nonsenseIsRejected() {
        assertNull(Formula.eval("A+", bytes))
        assertNull(Formula.eval("(A", bytes))
        assertNull(Formula.eval("A/0", bytes))
        assertNull(Formula.eval("E", bytes))
        assertNull(Formula.eval("A;B", bytes))
        assertNull(Formula.eval("", bytes))
    }
}

class PidProbeTest {

    private val soot = PidCandidate(ExtraReading.SOOT_LOAD, "7E0", "221A5B", "A*100/255", 0.0, 100.0, fromAi = true)

    @Test
    fun theReplyHeaderEchoesTheRequest() {
        assertEquals("621A5B", soot.replyHeader)
        assertEquals("415C", PidProbe.STANDARD.first { it.reading == ExtraReading.OIL_TEMP }.replyHeader)
    }

    @Test
    fun aGoodReplyGivesAValue() {
        val r = PidProbe.read(soot, "62 1A 5B 80")
        assertEquals(ProbeVerdict.OK, r.verdict)
        assertEquals(0x80 * 100 / 255.0, r.value!!, 1e-9)
        // Headers on, CAN frame length byte first: the data is still found after the echo.
        assertEquals(ProbeVerdict.OK, PidProbe.read(soot, "7E8 04 62 1A 5B 40").verdict)
    }

    @Test
    fun silenceRefusalsAndNonsenseAreToldApart() {
        assertEquals(ProbeVerdict.NO_ANSWER, PidProbe.read(soot, null).verdict)
        assertEquals(ProbeVerdict.NO_ANSWER, PidProbe.read(soot, "NO DATA").verdict)
        assertEquals(ProbeVerdict.REFUSED, PidProbe.read(soot, "7F 22 31").verdict)
        assertEquals(ProbeVerdict.UNREADABLE, PidProbe.read(soot, "62 1A 5C 80").verdict)
        assertEquals(ProbeVerdict.UNREADABLE, PidProbe.read(soot, "62 1A 5B").verdict)
        // 0xFF * 100 / 255 = 100 is in range, but a temperature of 215 °C on a -30..150 scale isn't.
        val oil = PidProbe.STANDARD.first { it.reading == ExtraReading.OIL_TEMP }
        assertEquals(ProbeVerdict.IMPLAUSIBLE, PidProbe.read(oil, "41 5C FF").verdict)
    }

    @Test
    fun twoReadsMustAgree() {
        val a = PidProbe.read(soot, "62 1A 5B 80")
        val b = PidProbe.read(soot, "62 1A 5B 84")
        assertEquals(ProbeVerdict.OK, PidProbe.stable(a, b).verdict)
        val far = PidProbe.read(soot, "62 1A 5B 10")
        assertEquals(ProbeVerdict.UNSTABLE, PidProbe.stable(a, far).verdict)
        val silent = PidProbe.read(soot, null)
        assertEquals(ProbeVerdict.NO_ANSWER, PidProbe.stable(a, silent).verdict)
    }

    @Test
    fun onlyReadRequestsWithWorkingFormulasAreKept() {
        val list = PidProbe.readCandidates(
            """{"items":[
              {"reading":"SOOT_LOAD","header":"7E0","request":"22 1A 5B","formula":"A*100/255","min":0,"max":100},
              {"reading":"SOOT_LOAD","header":"7E0","request":"2F1A5B","formula":"A","min":0,"max":100},
              {"reading":"DPF_TEMP","header":"","request":"221B22","formula":"(A*256+B)/10-40","min":-30,"max":900},
              {"reading":"REGEN_ACTIVE","header":"7E0","request":"221A60","formula":"A &&","min":0,"max":1},
              {"reading":"KM_SINCE_REGEN","header":"7E0","request":"221A6","formula":"A","min":0,"max":5000},
              {"reading":"WHATEVER","header":"7E0","request":"221A5B","formula":"A","min":0,"max":1}
            ]}"""
        )
        assertEquals(2, list.size)
        assertEquals("221A5B", list[0].request)
        assertEquals("7E0", list[0].header)
        assertTrue(list[0].fromAi)
        assertNull(list[1].header)
    }

    @Test
    fun aRoundTripThroughJsonKeepsACandidate() {
        val back = PidCandidate.fromJson(soot.toJson())
        assertEquals(soot, back)
        val psa = soot.copy(header = "6A8", replyAddress = "688", session = "10C0", request = "2181")
        assertEquals(psa, PidCandidate.fromJson(psa.toJson()))
        assertEquals("6A8→688 10C0 2181", psa.label)
        assertEquals("7E0 221A5B", soot.label)
    }

    @Test
    fun aLongAnswerSplitOverFramesIsReadInOrder() {
        // 13 bytes: "61 81" then data; the "00D" count and "0:" / "1:" frame numbers are not data.
        val block = soot.copy(header = "6A8", replyAddress = "688", request = "2181", formula = "C*256+D")
        val r = PidProbe.read(block, "00D\r0: 61 81 01 02 03 04\r1: 05 06 07 08 09 0A 0B")
        assertEquals(ProbeVerdict.IMPLAUSIBLE, r.verdict)
        assertEquals(0x0304.toDouble(), r.value!!, 0.0)
        assertEquals("61 81 01 02 03 04 05 06 07 08 09 0A 0B", PidProbe.frames("00D\r0: 61 81 01 02 03 04\r1: 05 06 07 08 09 0A 0B"))
    }

    @Test
    fun busyThenAnsweredIsAnAnswer() {
        assertEquals(ProbeVerdict.OK, PidProbe.read(soot, "7F 22 78\r62 1A 5B 80").verdict)
        assertEquals(ProbeVerdict.REFUSED, PidProbe.read(soot, "7F 22 78\r7F 22 31").verdict)
    }

    @Test
    fun ownAddressesAndOnlySafeSessionsAreKept() {
        val list = PidProbe.readCandidates(
            """{"items":[
              {"reading":"SOOT_LOAD","header":"6A8","replyAddress":"688","session":"10C0","request":"2181","formula":"A","min":0,"max":100},
              {"reading":"SOOT_LOAD","header":"6A8","replyAddress":"688","session":"1002","request":"2182","formula":"A","min":0,"max":100},
              {"reading":"SOOT_LOAD","header":"6A8","replyAddress":"688","session":"1085","request":"2183","formula":"A","min":0,"max":100},
              {"reading":"DPF_TEMP","header":"7E0","replyAddress":"","session":"","request":"22F40E","formula":"A","min":0,"max":900},
              {"reading":"OIL_TEMP","header":"","replyAddress":"7E8","session":"1003","request":"22F434","formula":"A","min":0,"max":150}
            ]}"""
        )
        assertEquals(listOf("2181", "22F40E"), list.map { it.request })
        assertEquals("688", list[0].replyAddress)
        assertEquals("10C0", list[0].session)
        assertNull(list[1].replyAddress)
        assertNull(list[1].session)
    }
}
