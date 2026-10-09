package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbObdTest {

    @Test
    fun anElmAtTheRightSpeedIsKnown() {
        assertTrue(looksLikeElm("ATI\rELM327 v1.5\r\r>"))
        assertTrue(looksLikeElm("\r\nOBDLink SX r4.2\r\n\r\n>"))
        // Echo off and only the prompt: still an adapter.
        assertTrue(looksLikeElm("?\r\r>"))
    }

    @Test
    fun noiseAtTheWrongSpeedIsNot() {
        assertFalse(looksLikeElm("\u0080ø\u0000þ\u0098>à\u0080\u0000ø"))
        assertFalse(looksLikeElm(""))
    }

    @Test
    fun aModemWithoutThePromptIsNot() {
        assertFalse(looksLikeElm("ATI\r\nQuectel\r\nEC25\r\nRevision: EC25EFAR06A06M4G\r\n\r\nOK\r\n"))
    }
}
