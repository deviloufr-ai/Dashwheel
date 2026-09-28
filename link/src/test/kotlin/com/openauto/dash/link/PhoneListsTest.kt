package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneListsTest {
    @Test
    fun contactsAgendaAndDestinationRoundTrip() {
        val contacts = PhoneContacts(
            listOf(PhoneFavourite("Camille Martin", "+33 6 39 98 00 01", "iVBORw0K")),
            listOf(RecentCall("+33 6 39 98 00 02", "Léo", RecentCall.Type.MISSED, 1_700_000_000_000))
        )
        assertEquals(contacts, LinkCodec.decode(LinkCodec.encode(contacts)))
        val agenda = AgendaSync(listOf(PhoneEvent("Dentist", 1L, 2L, allDay = false, location = "12 rue de Rivoli, Paris")))
        assertEquals(agenda, LinkCodec.decode(LinkCodec.encode(agenda)))
        val destination = Destination("Tour Eiffel", 48.8583701, 2.2944813)
        assertEquals(destination, LinkCodec.decode(LinkCodec.encode(destination)))
        val byAddress = Destination("Gare de Lyon", query = "Place Louis-Armand, 75012 Paris")
        assertEquals(byAddress, LinkCodec.decode(LinkCodec.encode(byAddress)))
        assertTrue(destination.hasPosition)
        assertTrue(!byAddress.hasPosition)
    }

    @Test
    fun dialCarriesItsNumberAndAnOlderCommandStillReads() {
        val dial = CallCommand(CallCommand.Action.DIAL, "+33639980001")
        assertEquals(dial, LinkCodec.decode(LinkCodec.encode(dial)))
        // What an older head unit sends: no number.
        assertEquals(CallCommand(CallCommand.Action.HANG_UP), LinkCodec.decode("""{"t":"call_cmd","action":"HANG_UP"}""".encodeToByteArray()))
        assertEquals(DialResult("+33639980001", placed = false), LinkCodec.decode(LinkCodec.encode(DialResult("+33639980001", false))))
    }

    @Test
    fun anOlderPeerSkipsAnActionItDoesNotKnow() {
        // How an older companion sees DIAL (or a newer head unit's next action): skipped, the link stays up.
        assertNull(LinkCodec.decode("""{"t":"call_cmd","action":"CONFERENCE","number":"1"}""".encodeToByteArray()))
    }

    @Test
    fun contactsFitTheirCapByDroppingPhotosFromTheEnd() {
        val photo = "A".repeat(40_000)
        val many = (1..12).map { PhoneFavourite("Friend $it", "+3360000000$it", photo) }
        val calls = (1..9).map { RecentCall("+33100000$it", null, RecentCall.Type.INCOMING, it.toLong()) }
        val sent = PhoneContacts.of(many, calls)
        assertEquals(PhoneContacts.MAX_FAVOURITES, sent.favourites.size)
        assertEquals(PhoneContacts.MAX_CALLS, sent.recentCalls.size)
        assertTrue(LinkCodec.encode(sent).size <= PhoneContacts.MAX_BYTES)
        // The first ones keep theirs.
        assertEquals(photo, sent.favourites.first().photoPng)
        assertNull(sent.favourites.last().photoPng)
    }

    @Test
    fun agendaIsCappedAndItsTextKeptShort() {
        val events = (1..20).map { PhoneEvent("x".repeat(500), it.toLong(), it + 1L, location = "y".repeat(500)) }
        val sent = AgendaSync.of(events)
        assertEquals(AgendaSync.MAX_ITEMS, sent.events.size)
        assertEquals(AgendaSync.MAX_TEXT, sent.events.first().title.length)
        assertEquals(AgendaSync.MAX_TEXT, sent.events.first().location.length)
    }
}
