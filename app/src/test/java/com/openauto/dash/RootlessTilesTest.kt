package com.openauto.dash

import com.openauto.dash.PrivilegedShell.Access
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootlessTilesTest {

    private val doors = DashboardItem.BuiltinWidget(BuiltinKind.DOORS)
    private val carStatus = DashboardItem.BuiltinWidget(BuiltinKind.CAR_STATUS)
    private val clock = DashboardItem.BuiltinWidget(BuiltinKind.CLOCK)
    private val window = DashboardItem.AppWindow("com.waze")
    private val inside = DashboardItem.AppWindow("com.google.android.apps.maps", inside = true)
    private val shortcut = DashboardItem.AppShortcut("com.waze")

    @Test
    fun rootAllowsEveryTile() {
        listOf(doors, carStatus, clock, window, inside, shortcut).forEach { assertTrue(Access.ROOT.allows(it)) }
    }

    @Test
    fun adbShellLeavesOutOnlyTheCanboxTiles() {
        assertFalse(Access.ADB.allows(doors))
        listOf(carStatus, clock, window, inside, shortcut).forEach { assertTrue(Access.ADB.allows(it)) }
    }

    @Test
    fun noShellLeavesOutEveryTileThatNeedsOne() {
        listOf(doors, carStatus, window, inside).forEach { assertFalse(Access.NONE.allows(it)) }
        listOf(clock, shortcut).forEach { assertTrue(Access.NONE.allows(it)) }
    }
}
