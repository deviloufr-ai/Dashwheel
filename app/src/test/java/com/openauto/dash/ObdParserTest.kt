package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** ELM327 reply decoding, including the echo / prompt noise real adapters add. */
class ObdParserTest {

    @Test
    fun speedIsTheSingleDataByte() {
        assertEquals(50, ObdParser.parseSpeed("41 0D 32"))
        // Echoed command, CR line ends and the ">" prompt are all ignored.
        assertEquals(50, ObdParser.parseSpeed("010D\r41 0D 32\r\r>"))
    }

    @Test
    fun rpmIsSixteenBitsOverFour() {
        assertEquals(1726, ObdParser.parseRpm("41 0C 1A F8"))
        assertNull(ObdParser.parseRpm("41 0C 1A"))
    }

    @Test
    fun temperaturesAreOffsetByForty() {
        assertEquals(83, ObdParser.parseCoolant("41 05 7B"))
        assertEquals(-40, ObdParser.tempFrom("41 0F 00", "410F"))
    }

    @Test
    fun percentagesScaleFromByte() {
        assertEquals(50, ObdParser.percentFrom("41 11 80", "4111"))
        assertEquals(100, ObdParser.percentFrom("41 04 FF", "4104"))
    }

    @Test
    fun voltageFromAdapterAndFromEcu() {
        assertEquals(12.6, ObdParser.parseVoltage("12.6V")!!, 1e-9)
        assertEquals(12.5, ObdParser.parseControlModuleVoltage("41 42 30 D4")!!, 1e-9)
    }

    @Test
    fun noDataYieldsNull() {
        assertNull(ObdParser.parseSpeed("NO DATA"))
        assertNull(ObdParser.parseSpeed("SEARCHING..."))
        assertNull(ObdParser.parseVoltage("?"))
    }

    @Test
    fun troubleCodesDecodeAndSkipPadding() {
        assertEquals(listOf("P0133"), ObdParser.parseDtcs("43 01 33 00 00 00 00"))
        assertEquals(listOf("P0133", "C0300"), ObdParser.parseDtcs("43 01 33 43 00 00 00"))
        assertEquals(emptyList<String>(), ObdParser.parseDtcs("43 00 00 00 00 00 00"))
        assertEquals(emptyList<String>(), ObdParser.parseDtcs("NO DATA"))
    }

    @Test
    fun olderProtocolsSendOneLinePerThreeCodes() {
        assertEquals(
            listOf("P0133", "P0134", "P0135", "P0136"),
            ObdParser.parseDtcs("43 01 33 01 34 01 35\r43 01 36 00 00 00 00")
        )
    }

    @Test
    fun canRepliesCarryACodeCountThatIsNotACode() {
        assertEquals(listOf("P0133"), ObdParser.parseDtcs("43 01 01 33"))
        assertEquals(listOf("P0133", "P2002"), ObdParser.parseDtcs("SEARCHING...\r43 02 01 33 20 02"))
        assertEquals(emptyList<String>(), ObdParser.parseDtcs("43 00"))
    }

    @Test
    fun canMultiFrameRepliesAreJoined() {
        assertEquals(
            listOf("P0133", "P0134", "P0135", "P0136"),
            ObdParser.parseDtcs("00A\r0: 43 04 01 33 01 34\r1: 01 35 01 36 00 00 00")
        )
    }

    @Test
    fun engineLampAndStoredCountComeFromMonitorStatus() {
        assertEquals(EngineLamp(on = true, storedCodes = 3), ObdParser.parseEngineLamp("41 01 83 07 E5 00"))
        assertEquals(EngineLamp(on = false, storedCodes = 0), ObdParser.parseEngineLamp("SEARCHING...\r41 01 00 07 E5 00"))
        assertEquals(null, ObdParser.parseEngineLamp("NO DATA"))
    }

    @Test
    fun noAnswerIsNotTheSameAsNoFault() {
        assertEquals(null, ObdParser.parseDtcReply("NO DATA"))
        assertEquals(null, ObdParser.parseDtcReply("SEARCHING...\rSTOPPED"))
        assertEquals(emptyList<String>(), ObdParser.parseDtcReply("43 00"))
        assertEquals(listOf("P1352"), ObdParser.parseDtcReply("43 00\r43 01 13 52"))
    }

    @Test
    fun pendingCodesComeFromMode07() {
        assertEquals(listOf("P0401"), ObdParser.parseDtcReply("47 01 04 01", mode = 0x47))
        assertEquals(null, ObdParser.parseDtcReply("43 01 04 01", mode = 0x47))
    }

    @Test
    fun theLampIsOnWhenAnyComputerSaysSo() {
        // Gearbox first (lamp off), engine second (lamp on, one code).
        assertEquals(EngineLamp(on = true, storedCodes = 1), ObdParser.parseEngineLamp("41 01 00 04 00 00\r41 01 81 07 65 04"))
    }

    @Test
    fun canProtocolsAreRecognisedFromDescribeProtocolNumber() {
        assertTrue(ObdParser.isCan11Bit("A6"))
        assertTrue(ObdParser.isCan11Bit("6"))
        assertTrue(ObdParser.isCan11Bit("8"))
        assertFalse(ObdParser.isCan11Bit("A7"))
        assertFalse(ObdParser.isCan11Bit("3"))
        assertFalse(ObdParser.isCan11Bit("?"))
    }

    @Test
    fun theBusIsReadFromDescribeProtocolNumber() {
        assertEquals(11, ObdParser.canAddressBits("A6"))
        assertEquals(11, ObdParser.canAddressBits("8"))
        assertEquals(29, ObdParser.canAddressBits("A7"))
        assertEquals(29, ObdParser.canAddressBits("9\r"))
        assertEquals(0, ObdParser.canAddressBits("A3"))
        assertEquals(0, ObdParser.canAddressBits("5"))
        // Still searching, J1939, a clone's "?" or anything longer: not known.
        assertNull(ObdParser.canAddressBits("A0"))
        assertNull(ObdParser.canAddressBits("A"))
        assertNull(ObdParser.canAddressBits("?"))
        assertNull(ObdParser.canAddressBits("12.3V"))
    }

    // Built by hand from ISO 15765-2: two computers answer mode 03 with more codes than one frame
    // holds, and their first and following frames arrive interleaved.
    @Test
    fun twoComputersLongAnswersInterleavedAreReadPerSender() {
        val expected = listOf("P0133", "P0201", "P0300", "P0420", "P0700", "P0715", "P0730")
        val reply = "7E8 10 0A 43 04 01 33 02 01\r7E9 10 08 43 03 07 00 07 15\r" +
            "7E8 21 03 00 04 20 AA AA AA\r7E9 21 07 30 55 55 55 55 55"
        assertEquals(expected, ObdParser.parseDtcReply(reply, can = true))
        assertEquals(expected, ObdParser.parseDtcReply(reply.replace(" ", ""), can = true))
        assertEquals(expected, ObdParser.parseDtcReply(reply))
    }

    @Test
    fun theBusDecidesTheLayoutWhenItIsKnown() {
        // A clone that prints the CAN frame's padding: 7 bytes, which looks like the old layout.
        val padded = "43 01 01 33 00 00 00"
        assertEquals(listOf("P0133"), ObdParser.parseDtcReply(padded, can = true))
        assertEquals(listOf("P0101", "P3300"), ObdParser.parseDtcReply(padded))
        // An old bus whose line lost its last byte: 6 bytes, which looks like CAN.
        val cut = "43 01 33 02 01 00"
        assertEquals(listOf("P0133", "P0201"), ObdParser.parseDtcReply(cut, can = false))
        assertEquals(listOf("P3302"), ObdParser.parseDtcReply(cut))
        // A count that cannot be one (more codes than bytes): guessed as before.
        assertEquals(listOf("P0433", "P0201"), ObdParser.parseDtcReply("43 04 33 02 01", can = true))
    }

    @Test
    fun clearingNeedsAComputersYesAndNoRefusal() {
        assertTrue(ObdParser.clearAccepted("44"))
        assertTrue(ObdParser.clearAccepted("44\r44"))
        assertTrue(ObdParser.clearAccepted("7E8 01 44 00 00 00 00 00 00"))
        // "Wait" and then yes.
        assertTrue(ObdParser.clearAccepted("7F 04 78\r44"))
        // Conditions not correct (engine running), from the only computer or from one of two.
        assertFalse(ObdParser.clearAccepted("7F 04 22"))
        assertFalse(ObdParser.clearAccepted("44\r7F 04 22"))
        // A 44 that is somebody's data byte, the adapter's own words.
        assertFalse(ObdParser.clearAccepted("43 01 44 00"))
        assertFalse(ObdParser.clearAccepted("OK"))
        assertFalse(ObdParser.clearAccepted("NO DATA"))
    }

    @Test
    fun severalPidsComeBackInOneReply() {
        val lengths = mapOf(0x0D to 1, 0x0C to 2, 0x11 to 1, 0x04 to 1)
        val two = mapOf(0x0D to listOf(0x32), 0x0C to listOf(0x1A, 0xF8))
        assertEquals(two, ObdParser.parseMultiPid("41 0D 32 0C 1A F8", lengths))
        assertEquals(two, ObdParser.parseMultiPid("410D320C1AF8\r\r", lengths))
        assertEquals(two, ObdParser.parseMultiPid("7E8 06 41 0D 32 0C 1A F8 00", lengths))
        // Ten bytes: a first and a following frame, the last one padded.
        val four = two + mapOf(0x11 to listOf(0x80), 0x04 to listOf(0xFF))
        assertEquals(four, ObdParser.parseMultiPid("00A\r0: 41 0D 32 0C 1A F8\r1: 11 80 04 FF 00 00 00", lengths))
        assertEquals(four, ObdParser.parseMultiPid("00A\r0:410D320C1AF8\r1:118004FF000000", lengths))
        assertEquals(four, ObdParser.parseMultiPid("7E8 10 0A 41 0D 32 0C 1A F8\r7E8 21 11 80 04 FF 00 00 00", lengths))
        // The car answers only what it serves; a second computer adds its own.
        assertEquals(mapOf(0x0D to listOf(0x32)), ObdParser.parseMultiPid("41 0D 32", lengths))
        assertEquals(two, ObdParser.parseMultiPid("41 0D 32\r41 0C 1A F8 0D 00", lengths))
    }

    @Test
    fun aCombinedReplyThatDoesNotReadCleanlyIsRefused() {
        val lengths = mapOf(0x0D to 1, 0x0C to 2)
        assertNull(ObdParser.parseMultiPid("NO DATA", lengths))
        assertNull(ObdParser.parseMultiPid("?", lengths))
        // Padding printed, a reply cut short, a PID that was not asked for.
        assertNull(ObdParser.parseMultiPid("41 0D 32 0C 1A F8 00", lengths))
        assertNull(ObdParser.parseMultiPid("41 0D 32 0C 1A", lengths))
        assertNull(ObdParser.parseMultiPid("41 05 7B", lengths))
        // Only the first PID answered, the way some clones do, with the request's second byte echoed.
        assertNull(ObdParser.parseMultiPid("41 0D 32 AA", lengths))
    }

    // Replies captured from the C4 Picasso (2011, 1.6 HDi) on 2026-09-23: the adapter
    // puts the sender's address and the frame length in front, without spaces.
    @Test
    fun theC4PicassosRepliesWithHeadersAreRead() {
        assertEquals(listOf("P1352"), ObdParser.parseDtcReply("7E90243000000000000000\r7E80443011352FFFFFF"))
        assertEquals(listOf("P1352"), ObdParser.parseDtcReply("7E90247000000000000000\r7E80447011352FFFFFF", mode = 0x47))
        assertEquals(EngineLamp(on = true, storedCodes = 1), ObdParser.parseEngineLamp("7E8064101810EE000FF\r7E90641010004000000"))
    }

    @Test
    fun headersWithSpacesAnd29BitAddressesToo() {
        assertEquals(listOf("P1352"), ObdParser.parseDtcReply("7E8 04 43 01 13 52 FF FF FF"))
        assertEquals(listOf("P1352"), ObdParser.parseDtcReply("18DAF110 04 43 01 13 52 AA AA AA"))
        assertEquals(emptyList<String>(), ObdParser.parseDtcReply("7E9 02 43 00 00 00 00 00 00"))
    }

    @Test
    fun multiFrameRepliesWithHeadersAreJoinedPerSender() {
        assertEquals(
            listOf("P0133", "P0134", "P0135", "P0136"),
            ObdParser.parseDtcReply("7E8 10 0A 43 04 01 33 01 34\r7E9 02 43 00 00 00 00 00 00\r7E8 21 01 35 01 36 00 00 00")
        )
    }

    @Test
    fun everyAnsweringEcuIsRead() {
        assertEquals(listOf("P0133", "P0700"), ObdParser.parseDtcs("43 01 01 33\r43 01 07 00"))
    }

    @Test
    fun supportedPidBitmapIsReadMostSignificantFirst() {
        // BE 3E B8 11: 01 03-07, 0B-0F, 11 13-15, 1C 20.
        val pids = ObdParser.parseSupportedPids("41 00 BE 3E B8 11", 0x00)!!
        assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x14, 0x15, 0x1C, 0x20), pids)
        assertNull(ObdParser.parseSupportedPids("SEARCHING...\rUNABLE TO CONNECT", 0x00))
    }

    @Test
    fun everyAnsweringComputerAddsItsPids() {
        // Engine and gearbox answer on their own lines; with headers the sender comes first.
        val pids = ObdParser.parseSupportedPids("SEARCHING...\r7E8 06 41 00 80 00 00 00\r7E9 06 41 00 00 00 00 01", 0x00)!!
        assertEquals(setOf(0x01, 0x20), pids)
    }

    @Test
    fun supportedPidsFollowTheRanges() {
        val replies = mapOf(
            "0100" to "41 00 BE 3E B8 11",
            // 0120: only 0x21 and the next-range bit.
            "0120" to "41 20 80 00 00 01",
            // 0140: 0x42 (control-module voltage), no further range.
            "0140" to "41 40 40 00 00 00"
        )
        val asked = mutableListOf<String>()
        val s = ObdParser.supportedPids { asked += it; replies[it] }!!
        assertEquals(listOf("0100", "0120", "0140"), asked)
        assertTrue(s.has(0x0D))
        assertTrue(s.has(0x42))
        // The fuel level (012F) this car doesn't serve is skipped.
        assertFalse(s.has(0x2F))
        assertFalse(s.has(0x5C))
    }

    @Test
    fun anUnansweredRangeGivesItsPidsTheBenefitOfTheDoubt() {
        assertNull(ObdParser.supportedPids { "NO DATA" })
        // 0100 says a next range exists, but 0120 goes unanswered.
        val s = ObdParser.supportedPids { if (it == "0100") "41 00 BE 3E B8 11" else null }!!
        assertFalse(s.has(0x02))
        assertTrue(s.has(0x2F))
        assertTrue(s.has(0x42))
        // No next range: nothing past it is served.
        val short = ObdParser.supportedPids { if (it == "0100") "41 00 BE 3E B8 10" else null }!!
        assertFalse(short.has(0x42))
    }

    @Test
    fun aSilentEngineComputerReadsAsEngineOff() {
        val stopped = ObdData(speedKmh = 12, rpm = 820, coolantTempC = 88, throttlePct = 14, engineLoadPct = 22, voltage = 12.4).engineStopped()
        assertEquals(0, stopped.speedKmh)
        assertEquals(0, stopped.rpm)
        assertEquals(0, stopped.throttlePct)
        assertEquals(0, stopped.engineLoadPct)
        // What a gauge would still show stays.
        assertEquals(88, stopped.coolantTempC)
        assertEquals(12.4, stopped.voltage, 1e-9)
    }

    @Test
    fun dtcLettersFollowTheTopTwoBits() {
        assertEquals("P0133", ObdParser.decodeDtc(0x01, 0x33))
        assertEquals("C0300", ObdParser.decodeDtc(0x43, 0x00))
        assertEquals("B1234", ObdParser.decodeDtc(0x92, 0x34))
        assertEquals("U0123", ObdParser.decodeDtc(0xC1, 0x23))
    }
}
