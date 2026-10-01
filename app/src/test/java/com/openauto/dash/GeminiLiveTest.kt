package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** Gemini Live's alert while driving: the chosen design first, the pill after a while on the move. */
class GeminiLiveTest {

    @Test
    fun parkedKeepsTheChosenDesign() {
        assertEquals(AlertStyle.PANEL, GeminiLive.design(AlertStyle.PANEL, moving = false, bigForMs = 60_000L))
    }

    @Test
    fun drivingShrinksToThePillAfterAFewSeconds() {
        assertEquals(AlertStyle.PANEL, GeminiLive.design(AlertStyle.PANEL, moving = true, bigForMs = 3_000L))
        assertEquals(AlertStyle.PILL, GeminiLive.design(AlertStyle.FULL, moving = true, bigForMs = 11_000L))
        assertEquals(AlertStyle.PILL, GeminiLive.design(AlertStyle.CARD, moving = true, bigForMs = 11_000L))
    }

    @Test
    fun geminiDefaultsToThePanel() {
        assertEquals(AlertStyle.PANEL, emptyMap<AlertKind, AlertStyle>().of(AlertKind.GEMINI))
        // A design it doesn't take falls back to its own default, not the card.
        assertEquals(AlertStyle.PANEL, mapOf(AlertKind.GEMINI to AlertStyle.BANNER).of(AlertKind.GEMINI))
    }

    @Test
    fun theRoundDesignsStayWhileDriving() {
        assertEquals(AlertStyle.BUBBLE, GeminiLive.design(AlertStyle.BUBBLE, moving = true, bigForMs = 60_000L))
        assertEquals(AlertStyle.ICON, GeminiLive.design(AlertStyle.ICON, moving = true, bigForMs = 60_000L))
    }

    @Test
    fun theRoundDesignsAreGeminisOnly() {
        // The other alerts keep their five designs.
        AlertKind.entries.filter { it != AlertKind.GEMINI }.forEach { kind ->
            assert(AlertStyle.BUBBLE !in kind.styles && AlertStyle.ICON !in kind.styles) { kind }
        }
    }
}
