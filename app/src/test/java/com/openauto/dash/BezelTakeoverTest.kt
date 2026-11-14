package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class BezelTakeoverTest {
    // The K706's file as read on 2026-11-14 (first 6 of 30 lines, the rest empty).
    private val file = listOf("000/0000/0000", "604/-070/0230", "606/-091/0361", "602/-091/0480", "603/-090/0593", "624/-072/0106")

    @Test fun `vol down goes to a spare and comes back`() {
        val (taken, kept) = BezelTakeover.rewrite(file, silenced = setOf(294), originals = emptyMap())
        assertEquals("660/-090/0593", taken[4])
        assertEquals(file - file[4], taken - taken[4])
        assertEquals(mapOf(4 to 603), kept)

        val (back, none) = BezelTakeover.rewrite(taken, silenced = emptySet(), originals = kept)
        assertEquals(file, back)
        assertEquals(emptyMap<Int, Int>(), none)
    }

    @Test fun `home and back each get their own spare, and stay on a second pass`() {
        val (taken, kept) = BezelTakeover.rewrite(file, silenced = setOf(3, 4), originals = emptyMap())
        assertEquals(listOf("660/-070/0230", "661/-091/0361"), taken.subList(1, 3))
        assertEquals(taken to kept, BezelTakeover.rewrite(taken, silenced = setOf(3, 4), originals = kept))
    }

    @Test fun `a spare line Dashwheel did not write stays`() {
        val odd = file.toMutableList().also { it[5] = "661/-072/0106" }
        assertEquals(odd, BezelTakeover.rewrite(odd, silenced = emptySet(), originals = emptyMap()).first)
        // and its spare is not handed out again
        assertEquals("660/-090/0593", BezelTakeover.rewrite(odd, setOf(294), emptyMap()).first[4])
    }

    @Test fun `originals survive the prefs`() {
        val m = mapOf(4 to 603, 1 to 604)
        assertEquals(m, BezelTakeover.decode(BezelTakeover.encode(m)))
        assertEquals(emptyMap<Int, Int>(), BezelTakeover.decode(""))
    }
}
