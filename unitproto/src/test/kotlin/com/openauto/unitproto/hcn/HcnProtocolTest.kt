package com.openauto.unitproto.hcn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HcnProtocolTest {
    @Test
    fun doors_areUnknownUntilTheAppWroteThem() {
        assertNull(HcnProtocol.doors { null })
    }

    @Test
    fun doors_readTwoAsOpen_andAnythingElseAsClosed() {
        val props = mapOf("persist.vendor.cem.cardoor1" to "2", "persist.vendor.cem.cardoor2" to "1", "persist.vendor.cem.cardoor5" to "2")
        assertEquals(listOf(true, false, false, false, true), HcnProtocol.doors(props::get))
    }
}
