package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class BargeInGateTest {
    private fun BargeInGate.learnEcho(rms: Float) = repeat(3) { assertEquals(0, hear(rms)) }

    @Test
    fun geminisOwnEchoStaysOut() {
        val gate = BargeInGate()
        gate.learnEcho(0.1f)
        repeat(50) { assertEquals(0, gate.hear(0.12f)) }
    }

    @Test
    fun aVoiceOverTheEchoGoesUpWithTheChunkBeforeIt() {
        val gate = BargeInGate()
        gate.learnEcho(0.02f)
        assertEquals(0, gate.hear(0.2f))
        assertEquals(2, gate.hear(0.2f))
        assertEquals(1, gate.hear(0.01f))
    }

    @Test
    fun aBumpAloneDoesNotOpen() {
        val gate = BargeInGate()
        gate.learnEcho(0.02f)
        assertEquals(0, gate.hear(0.5f))
        assertEquals(0, gate.hear(0.02f))
        assertEquals(0, gate.hear(0.5f))
    }

    @Test
    fun theGateClosesAgain() {
        val gate = BargeInGate()
        gate.learnEcho(0.02f)
        gate.hear(0.2f)
        gate.hear(0.2f)
        repeat(15) { assertEquals(1, gate.hear(0.02f)) }
        assertEquals(0, gate.hear(0.02f))
    }

    @Test
    fun rmsOfFullScaleIsOneAndSilenceZero() {
        val full = ByteArray(4) { if (it % 2 == 0) 0 else Byte.MIN_VALUE }
        assertEquals(1f, rms(full, 4), 0.001f)
        assertEquals(0f, rms(ByteArray(4), 4), 0f)
    }
}
