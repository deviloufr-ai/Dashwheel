package com.openauto.dash

import com.openauto.dash.link.RecentCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PhoneListsTest {
    private val none = PermissionState(granted = true, request = {})

    @Test
    fun thePhonesListWinsWhenItHasOne() {
        val phone = listOf("Camille")
        val unit = listOf("Unit contact")
        assertSame(phone, phoneOrUnit(phone, unit))
        // No link, or an older companion: the unit's own, as before.
        assertSame(unit, phoneOrUnit(null, unit))
        // The phone has none but the unit has some: the unit's rather than an empty tile.
        assertSame(unit, phoneOrUnit(emptyList(), unit))
        val empty = emptyList<String>()
        assertSame(empty, phoneOrUnit(empty, emptyList()))
    }

    @Test
    fun theEmptyTileAsksToLinkThePhoneOnlyWhenItSentNothing() {
        assertEquals(R.string.phone_agenda_empty, AgendaSource(emptyList(), fromPhone = false, phoneSent = false, access = none).emptyText)
        assertEquals(R.string.info_agenda_empty, AgendaSource(emptyList(), fromPhone = true, phoneSent = true, access = none).emptyText)
        assertEquals(R.string.phone_quickdial_empty, QuickDialSource(emptyList(), null, phoneSent = false, access = none).emptyText)
        assertEquals(R.string.info_quickdial_empty, QuickDialSource(emptyList(), null, phoneSent = true, access = none).emptyText)
    }

    @Test
    fun eventsThatEndedAreLeftOut() {
        val now = 10_000L
        val events = listOf(
            AgendaEvent("Over", 1_000, 9_000, false, ""),
            AgendaEvent("Now", 9_000, 11_000, false, "Office"),
            AgendaEvent("Later", 12_000, 13_000, false, "")
        )
        assertEquals(listOf("Now", "Later"), upcoming(events, now).map { it.title })
        assertEquals(listOf("Now"), upcoming(events, now, max = 1).map { it.title })
    }

    @Test
    fun callBackIsTheLatestCallWhenMissedAndRecent() {
        val now = 100 * 3_600_000L
        val missed = RecentCall("1", "Camille", RecentCall.Type.MISSED, now - 20 * 60_000L)
        val older = RecentCall("2", "Léo", RecentCall.Type.INCOMING, now - 3 * 3_600_000L)
        assertEquals(missed, callBack(listOf(older, missed), now))
        // Called back since (or any later call): nothing to return.
        val calledBack = RecentCall("1", "Camille", RecentCall.Type.OUTGOING, now - 5 * 60_000L)
        assertNull(callBack(listOf(missed, calledBack), now))
        // Missed last night.
        assertNull(callBack(listOf(missed.copy(at = now - 13 * 3_600_000L)), now))
        assertNull(callBack(null, now))
        assertNull(callBack(emptyList(), now))
    }

    @Test
    fun aDestinationByAddressForEachNavigationApp() {
        val q = "12 rue de Rivoli, Paris & co"
        assertEquals("google.navigation:q=12%20rue%20de%20Rivoli%2C%20Paris%20%26%20co&mode=d", NavHandoff.queryUri(NavHandoff.MAPS, q))
        assertEquals("waze://?q=12%20rue%20de%20Rivoli%2C%20Paris%20%26%20co&navigate=yes", NavHandoff.queryUri(NavHandoff.WAZE, q))
        assertEquals("geo:0,0?q=Gare%20de%20Lyon", NavHandoff.queryUri(null, "  Gare de Lyon "))
        assertEquals("geo:0,0?q=Saint-%C3%89tienne", NavHandoff.queryUri(null, "Saint-Étienne"))
    }
}
