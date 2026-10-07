package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Pro purchase keeps (Premium.kt), in both flavours: the test runs
 * under the GitHub build, where everything is open, and under the Play one,
 * where nothing is bought until Play says so.
 */
class PremiumTest {

    @Test
    fun theLockedThemesAreWholeDesignSkinsAndTheFallbackIsFree() {
        Premium.LOCKED_THEMES.forEach { mode ->
            assertTrue("$mode is a skin", paletteFor(mode, light = false).Skin != DashSkin.STANDARD)
        }
        assertFalse(Premium.FALLBACK_THEME in Premium.LOCKED_THEMES)
        // One skin stays free, so the free catalogue still shows what a skin is.
        assertTrue(DashThemeMode.entries.any { paletteFor(it, false).Skin != DashSkin.STANDARD && it !in Premium.LOCKED_THEMES })
    }

    @Test
    fun theLockedWidgetsAreInTheCatalogueAndNeverTheCoreOnes() {
        Premium.LOCKED_KINDS.forEach { kind -> assertTrue("$kind is offered", kind.offered) }
        val core = setOf(
            BuiltinKind.NAVMAP, BuiltinKind.NAVIGATION, BuiltinKind.MEDIA, BuiltinKind.TELEMETRY, BuiltinKind.OBD_DTC,
            BuiltinKind.RANGE, BuiltinKind.SPEED_HUD, BuiltinKind.CLOCK, BuiltinKind.WEATHER, BuiltinKind.NOTIFICATIONS,
            BuiltinKind.SERVICE, BuiltinKind.BATTERY, BuiltinKind.WARMUP, BuiltinKind.FILTER_CARE
        )
        core.forEach { kind -> assertFalse("$kind stays free", kind in Premium.LOCKED_KINDS) }
    }

    @Test
    fun theStoreDecidesWhetherAnythingIsLocked() {
        val play = Edition.playStore
        assertEquals(!play, Edition.unlocked.value)
        assertEquals(play, Premium.locked(DashThemeMode.COCKPIT))
        assertEquals(play, Premium.locked(BuiltinKind.GFORCE))
        assertFalse(Premium.locked(DashThemeMode.ORBIT))
        assertFalse(Premium.locked(BuiltinKind.MEDIA))
        assertEquals(if (play) Premium.FALLBACK_THEME else DashThemeMode.COCKPIT, Premium.allowed(DashThemeMode.COCKPIT))
        assertEquals(DashThemeMode.AURORA, Premium.allowed(DashThemeMode.AURORA))
        assertEquals(if (play) Premium.LOCKED_KINDS else emptySet<BuiltinKind>(), Premium.lockedKinds())
        assertEquals(play, Edition.donationUrl == null)
        assertEquals(!play, Edition.updatesFromGitHub)
    }

    @Test
    fun theStartersAreNeverLocked() {
        assertEquals(StarterThemes, Premium.starterThemes(unlocked = true))
        val free = Premium.starterThemes(unlocked = false)
        assertEquals(3, free.size)
        free.forEach { assertFalse("$it", it in Premium.LOCKED_THEMES) }
        assertEquals(Premium.FALLBACK_THEME, free.first())
    }

    @Test
    fun aTemplateLeavesTheLockedWidgetsOff() {
        val page = TemplatePage(listOf(BuiltinKind.NAVMAP, BuiltinKind.ECO_DRIVE, BuiltinKind.MEDIA, BuiltinKind.GFORCE))
        val open = TemplateScreen(cellAspect = 1.1)
        assertEquals(page.kinds, TemplatePlacer.kindsFor(page, open))
        val locked = open.copy(locked = Premium.LOCKED_KINDS)
        assertEquals(listOf(BuiltinKind.NAVMAP, BuiltinKind.MEDIA), TemplatePlacer.kindsFor(page, locked))
        // Nothing stands in for a locked widget, and the pages still come out whole.
        val pages = TemplatePlacer.pages(DashTemplate.DAILY, locked)
        assertEquals(DashboardStore.PAGE_COUNT, pages.size)
        pages.flatten().filterIsInstance<DashboardItem.BuiltinWidget>().forEach {
            assertFalse("${it.kind} placed", it.kind in Premium.LOCKED_KINDS)
        }
    }
}
