package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** Several alerts at once: who keeps its design, and where the small ones go. */
class AlertArbiterTest {

    @Test
    fun aloneAnAlertKeepsItsDesign() {
        assertEquals(AlertStyle.FULL, arbitratedStyle(AlertKind.DOORS, AlertStyle.FULL, mapOf(AlertKind.DOORS to AlertStyle.FULL)))
    }

    @Test
    fun behindACallABigDesignShrinksToThePill() {
        val up = mapOf(AlertKind.CALL to AlertStyle.CARD, AlertKind.DOORS to AlertStyle.FULL)
        assertEquals(AlertStyle.PILL, arbitratedStyle(AlertKind.DOORS, AlertStyle.FULL, up))
        // The call is ahead: it keeps its card.
        assertEquals(AlertStyle.CARD, arbitratedStyle(AlertKind.CALL, AlertStyle.CARD, up))
    }

    @Test
    fun aFullScreenCallLeavesTheOthersThePill() {
        val up = mapOf(AlertKind.CALL to AlertStyle.FULL, AlertKind.DOORS to AlertStyle.CARD, AlertKind.TYRES to AlertStyle.CARD)
        assertEquals(AlertStyle.FULL, arbitratedStyle(AlertKind.CALL, AlertStyle.FULL, up))
        assertEquals(AlertStyle.PILL, arbitratedStyle(AlertKind.DOORS, AlertStyle.CARD, up))
        assertEquals(AlertStyle.PILL, arbitratedStyle(AlertKind.TYRES, AlertStyle.CARD, up))
    }

    @Test
    fun smallDesignsStayAsChosen() {
        val up = mapOf(AlertKind.CALL to AlertStyle.CARD, AlertKind.DOORS to AlertStyle.CARD, AlertKind.TYRES to AlertStyle.BANNER)
        assertEquals(AlertStyle.CARD, arbitratedStyle(AlertKind.DOORS, AlertStyle.CARD, up))
        assertEquals(AlertStyle.BANNER, arbitratedStyle(AlertKind.TYRES, AlertStyle.BANNER, up))
    }

    @Test
    fun onTheMoveABigDesignShrinksToThePillAfterAFewSeconds() {
        listOf(AlertStyle.PANEL, AlertStyle.FULL).forEach { big ->
            assertEquals(big, drivingStyle(big, moving = true, bigForMs = SHRINK_AFTER_MS))
            assertEquals(AlertStyle.PILL, drivingStyle(big, moving = true, bigForMs = SHRINK_AFTER_MS + 1))
            // Parked, it stays as long as it likes.
            assertEquals(big, drivingStyle(big, moving = false, bigForMs = 10 * 60_000L))
        }
    }

    @Test
    fun onTheMoveTheSmallDesignsStayAsChosen() {
        listOf(AlertStyle.PILL, AlertStyle.CARD, AlertStyle.BANNER).forEach { small ->
            assertEquals(small, drivingStyle(small, moving = true, bigForMs = 10 * 60_000L))
        }
        // Unless the alert counts it among its big ones (Gemini Live's card shows a whole screen).
        assertEquals(AlertStyle.PILL, drivingStyle(AlertStyle.CARD, moving = true, bigForMs = 60_000L, big = setOf(AlertStyle.CARD)))
    }

    @Test
    fun alertsOnTheSameSpotLineUp() {
        // The door card took the corner: the tyre card, same place and size, goes under it.
        val doors = AlertSpot(left = 900, top = 24, right = 1256, bottom = 144)
        assertEquals(152, stackedTop(own = 24, height = 120, left = 900, right = 1256, taken = listOf(doors), gap = 8))
        // A call card in the middle of the top edge isn't in the way.
        val call = AlertSpot(left = 380, top = 24, right = 880, bottom = 160)
        assertEquals(24, stackedTop(own = 24, height = 120, left = 900, right = 1256, taken = listOf(call), gap = 8))
        // Three pills in the middle: each under the one before.
        val first = AlertSpot(440, 12, 840, 76)
        val second = AlertSpot(440, 84, 840, 148)
        assertEquals(156, stackedTop(own = 12, height = 64, left = 440, right = 840, taken = listOf(second, first), gap = 8))
    }
}
