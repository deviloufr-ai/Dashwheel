package com.openauto.dash.companion

import com.openauto.dash.link.RecentCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneListsTest {
    private fun row(id: Long, name: String, number: String, primary: Boolean = false, pinned: Int = 0) =
        FavouriteRow(id, name, number, primary, pinned, null)

    @Test
    fun oneNumberPerContactItsDefaultFirst() {
        val rows = listOf(
            row(1, "Camille", "+33 1 00 00 00 01"),
            row(1, "Camille", "+33 6 39 98 00 01", primary = true),
            row(2, "Léo", "+33 6 39 98 00 02"),
            row(2, "Léo", "+33 1 00 00 00 02")
        )
        val picked = pickFavourites(rows, max = 8)
        assertEquals(listOf("+33 6 39 98 00 01", "+33 6 39 98 00 02"), picked.map { it.number })
    }

    @Test
    fun pinnedFirstInTheirOrderThenByName() {
        val rows = listOf(
            row(1, "zoé", "1"),
            row(2, "Adam", "2"),
            row(3, "Mum", "3", pinned = 2),
            row(4, "Work", "4", pinned = 1),
            row(5, "Bea", "5", pinned = -1)
        )
        assertEquals(listOf("Work", "Mum", "Adam", "Bea", "zoé"), pickFavourites(rows, max = 8).map { it.name })
        assertEquals(listOf("Work", "Mum"), pickFavourites(rows, max = 2).map { it.name })
    }

    @Test
    fun callTypes() {
        assertEquals(RecentCall.Type.INCOMING, callType(1))
        assertEquals(RecentCall.Type.OUTGOING, callType(2))
        assertEquals(RecentCall.Type.MISSED, callType(3))
        // Rejected: the caller still waits for an answer.
        assertEquals(RecentCall.Type.MISSED, callType(5))
        assertEquals(RecentCall.Type.INCOMING, callType(7))
        // Voicemail, blocked.
        assertNull(callType(4))
        assertNull(callType(6))
    }
}
