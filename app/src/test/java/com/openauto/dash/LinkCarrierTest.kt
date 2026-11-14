package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkCarrierTest {
    @Test
    fun automaticTriesBluetoothBeforeTheHotspot() {
        assertEquals(listOf(LinkCarrier.BLUETOOTH, LinkCarrier.WIFI), LinkCarrier.AUTO.tries())
    }

    @Test
    fun aSingleCarrierTriesOnlyItself() {
        assertEquals(listOf(LinkCarrier.BLUETOOTH), LinkCarrier.BLUETOOTH.tries())
        assertEquals(listOf(LinkCarrier.WIFI), LinkCarrier.WIFI.tries())
    }

    @Test
    fun aMissingOrUnknownStoredChoiceIsAutomatic() {
        assertEquals(LinkCarrier.AUTO, LinkCarrier.parse(null))
        assertEquals(LinkCarrier.AUTO, LinkCarrier.parse("CABLE"))
        assertEquals(LinkCarrier.WIFI, LinkCarrier.parse("WIFI"))
    }
}
