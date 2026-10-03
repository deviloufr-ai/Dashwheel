package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotTest {
    @Test
    fun anAskIsAnsweredOnce() {
        assertFalse("nothing asked yet", OneShot.fresh("test-home", 0L))
        assertTrue(OneShot.fresh("test-home", 1_000L))
        // The dashboard built anew reads the same last press again.
        assertFalse(OneShot.fresh("test-home", 1_000L))
        assertTrue(OneShot.fresh("test-home", 2_000L))
        // The unit's clock set back by GPS: a later press can carry an earlier time.
        assertTrue(OneShot.fresh("test-home", 500L))
    }

    @Test
    fun eachAskHasItsOwnMemory() {
        assertTrue(OneShot.fresh("test-a", 7L))
        assertTrue(OneShot.fresh("test-b", 7L))
        assertFalse(OneShot.fresh("test-a", 7L))
    }

    @Test
    fun widgetIdsAreReadFromEitherSchema() {
        val tiles = """[{"t":"builtin","k":"MEDIA"},{"t":"widget","id":12,"gx":0,"gy":0,"gw":3,"gh":2}],[],[{"t":"widget","id":7}]"""
        assertEquals(setOf(12, 7), DashboardStore.widgetIdsIn("""{"v":1,"pages":[$tiles]}"""))
        assertEquals(setOf(12, 7), DashboardStore.widgetIdsIn("[$tiles]"))
        assertEquals(emptySet<Int>(), DashboardStore.widgetIdsIn("[[],[]]"))
        // Not a layout: what is in use can't be told, and nothing is let go of.
        assertNull(DashboardStore.widgetIdsIn("not a layout"))
    }

    @Test
    fun widgetIdsOfPages() {
        val pages = listOf(listOf<DashboardItem>(DashboardItem.SystemWidget(3)), emptyList(), listOf(DashboardItem.SystemWidget(9)))
        assertEquals(setOf(3, 9), DashboardStore.widgetIds(pages))
    }
}
