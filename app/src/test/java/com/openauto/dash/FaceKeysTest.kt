package com.openauto.dash

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import org.junit.Assert.assertEquals
import org.junit.Test

/** A design short of room drops a side button, never the main one. */
class FaceKeysTest {

    private fun key(name: String, primary: Boolean = false) = FaceAction(Icons.Filled.Speed, name, onClick = {}, primary = primary)

    private fun names(keys: List<FaceAction>) = keys.map { it.label }

    @Test
    fun theMainButtonIsTheLastToGo() {
        val media = listOf(key("previous"), key("play", primary = true), key("next"))
        assertEquals(listOf("previous", "play", "next"), names(keyActions(media, 3)))
        assertEquals(listOf("play", "next"), names(keyActions(media, 2)))
        assertEquals(listOf("play"), names(keyActions(media, 1)))
        assertEquals(emptyList<String>(), names(keyActions(media, 0)))
        // Fault codes: Scan before Clear.
        val codes = listOf(key("scan", primary = true), key("clear"))
        assertEquals(listOf("scan"), names(keyActions(codes, 1)))
        // No main button: the first ones.
        assertEquals(listOf("a", "b"), names(keyActions(listOf(key("a"), key("b"), key("c")), 2)))
    }

    @Test
    fun aLineHoldsTheButtonsThatFitAndAlwaysOne() {
        // Three 56 dp buttons 6 dp apart need 180 dp.
        assertEquals(3, actionsThatFit(180f, 56f, 6f))
        assertEquals(2, actionsThatFit(179f, 56f, 6f))
        assertEquals(1, actionsThatFit(100f, 56f, 6f))
        // Never none: the main button stays even on a line too short for it.
        assertEquals(1, actionsThatFit(20f, 56f, 6f))
        assertEquals(1, actionsThatFit(-30f, 56f, 6f))
        assertEquals(3, actionsThatFit(900f, 56f, 6f))
        assertEquals(2, actionsThatFit(900f, 56f, 6f, most = 2))
    }
}
