package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneKeyboardTest {
    @Test
    fun goesInAtTheCursor() {
        assertEquals("rue de la Paix" to 7, PhoneKeyboard.inserted("rue la Paix", 4, 4, "de "))
    }

    @Test
    fun replacesTheSelectionWhicheverWayItWasMade() {
        assertEquals("Lyon" to 4, PhoneKeyboard.inserted("Paris", 0, 5, "Lyon"))
        assertEquals("Lyon" to 4, PhoneKeyboard.inserted("Paris", 5, 0, "Lyon"))
    }

    @Test
    fun endsUpAtTheEndWithoutACursor() {
        assertEquals("Paris 8e" to 8, PhoneKeyboard.inserted("Paris", -1, -1, " 8e"))
    }

    @Test
    fun aCursorPastTheTextStaysInIt() {
        assertEquals("ab!" to 3, PhoneKeyboard.inserted("ab", 9, 9, "!"))
    }
}
