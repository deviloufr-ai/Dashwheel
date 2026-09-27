package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `am stack list` parsing for the pinned (picture-in-picture) stack. */
class PipAnchorTest {

    private val android10 = """
        Stack id=1 bounds=[0,0][1280,720] displayId=0 userId=0
         configuration={1.0 ?mcc?mnc [fr_FR] ldltr sw720dp w1280dp h672dp 160dpi lrg long land finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1280, 720) mAppBounds=Rect(0, 0 - 1280, 672) mWindowingMode=fullscreen mActivityType=standard} s.12}
          taskId=41: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true topActivity=ComponentInfo{com.openauto.dash/com.openauto.dash.MainActivity}
        Stack id=3 bounds=[960,420][1264,608] displayId=0 userId=0
         configuration={1.0 ?mcc?mnc [fr_FR] ldltr sw720dp w1280dp h672dp 160dpi lrg long land finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(960, 420 - 1264, 608) mAppBounds=Rect(960, 420 - 1264, 608) mWindowingMode=pinned mActivityType=standard} s.6}
          taskId=57: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[960,420][1264,608] userId=0 visible=true topActivity=ComponentInfo{com.google.android.apps.maps/com.google.android.maps.MapsActivity}
        Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
         configuration={... mWindowingMode=fullscreen mActivityType=home ...}
          taskId=2: com.android.launcher3/.Launcher bounds=[0,0][1280,720] userId=0 visible=false
    """.trimIndent()

    @Test
    fun findsThePinnedStackAndItsPackage() {
        val pinned = WindowListing.parseFloatingWindow(android10)
        assertNotNull(pinned)
        assertEquals(3, pinned!!.stackId)
        assertEquals("com.google.android.apps.maps", pinned.packageName)
        assertEquals(ScreenRect(960, 420, 1264, 608), pinned.bounds)
    }

    @Test
    fun noPinnedStackMeansNoPip() {
        val without = android10.lines().filterNot { it.contains("Stack id=3") || it.contains("pinned") || it.contains("taskId=57") }
            .joinToString("\n")
        assertNull(WindowListing.parseFloatingWindow(without))
        assertNull(WindowListing.parseFloatingWindow(""))
        assertNull(WindowListing.parseFloatingWindow("Error: no such command"))
    }

    @Test
    fun freeformWindowIsFoundWhenThereIsNoPip() {
        val freeform = """
            Stack id=1 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=41: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=7 bounds=[640,80][1240,660] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[640,80][1240,660] userId=0 visible=true
        """.trimIndent()
        val win = WindowListing.parseFloatingWindow(freeform)!!
        assertEquals("freeform", win.mode)
        assertEquals(7, win.stackId)
        assertEquals(63, win.taskId)
        assertEquals("com.google.android.apps.maps", win.packageName)
        assertEquals("fullscreen dash \u00b7 freeform maps", WindowListing.summarizeStacks(freeform))
    }

    @Test
    fun fullscreenStackIsTheDashboardsOwn() {
        // Stack 1 holds the dashboard; stack 0 is Home and never a candidate.
        assertEquals(1, WindowListing.fullscreenStackId(android10))
    }

    @Test
    fun fullscreenStackFallsBackToAnyStandardOne() {
        val other = """
            Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={... mWindowingMode=fullscreen mActivityType=home ...}
              taskId=2: com.android.launcher3/.Launcher bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=4 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=70: com.android.chrome/com.google.android.apps.chrome.Main bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=7 bounds=[640,80][1240,660] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[640,80][1240,660] userId=0 visible=true
        """.trimIndent()
        assertEquals(4, WindowListing.fullscreenStackId(other))
    }

    @Test
    fun fullscreenStackOnTheHiddenDisplayIsNotWhereAWindowGoesFullScreen() {
        val hiddenOnly = """
            Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={... mWindowingMode=fullscreen mActivityType=home ...}
              taskId=2: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=9 bounds=[0,0][1280,720] displayId=3 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=70: com.android.chrome/com.google.android.apps.chrome.Main bounds=[0,0][1280,720] userId=0 visible=true
        """.trimIndent()
        assertNull(WindowListing.fullscreenStackId(hiddenOnly))
    }

    @Test
    fun mapsInsideATileIsNotAFloatingWindow() {
        // Maps on the tile's own display (5), still in the freeform mode of its old window.
        val listing = """
            Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={... mWindowingMode=fullscreen mActivityType=home ...}
              taskId=2: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=12 bounds=[0,0][640,560] displayId=5 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=80: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][640,560] userId=0 visible=true
        """.trimIndent()
        try {
            // Unknown display: taken for a parked window, which the tiles would bring back onto the screen.
            assertEquals(true, WindowListing.parseFloatingWindow(listing, packageName = "com.google.android.apps.maps")?.offDisplay)
            WindowListing.embeddedDisplays = setOf(5)
            assertNull(WindowListing.parseFloatingWindow(listing, packageName = "com.google.android.apps.maps"))
            assertTrue(WindowListing.strayWindows(listing, setOf("com.google.android.apps.maps"), emptySet()).isEmpty())
        } finally {
            WindowListing.embeddedDisplays = emptySet()
        }
    }

    @Test
    fun appStacksFindMapsOnEveryDisplay() {
        val listing = """
            Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={... mWindowingMode=fullscreen mActivityType=home ...}
              taskId=2: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=7 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[20,80][640,760] userId=0 visible=true
            Stack id=12 bounds=[0,0][522,362] displayId=7 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=80: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][522,362] userId=0 visible=true
        """.trimIndent()
        try {
            // Seen even while display 7 is a tile's, which the window listings leave out.
            WindowListing.embeddedDisplays = setOf(7)
            assertEquals(
                listOf(WindowListing.AppStack(7, 0, "freeform"), WindowListing.AppStack(12, 7, "fullscreen")),
                WindowListing.appStacks(listing, "com.google.android.apps.maps")
            )
            assertTrue(WindowListing.appStacks(listing, "com.waze").isEmpty())
        } finally {
            WindowListing.embeddedDisplays = emptySet()
        }
    }

    @Test
    fun noFullscreenStackForAppsMeansNowhereToMoveTo() {
        val homeOnly = """
            Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={... mWindowingMode=fullscreen mActivityType=home ...}
              taskId=2: com.android.launcher3/.Launcher bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=7 bounds=[640,80][1240,660] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[640,80][1240,660] userId=0 visible=true
        """.trimIndent()
        assertNull(WindowListing.fullscreenStackId(homeOnly))
        assertNull(WindowListing.fullscreenStackId(""))
    }

    private val mapsFullscreen = """
        Stack id=12 bounds=[0,0][1280,720] displayId=0 userId=0
         configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
          taskId=80: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][1280,720] userId=0 visible=true
        Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
         configuration={... mWindowingMode=fullscreen mActivityType=home ...}
          taskId=2: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=false
    """.trimIndent()

    @Test
    fun appCoveringTheDashboardIsFoundInFront() {
        val full = WindowListing.fullscreenInFront(mapsFullscreen, "com.google.android.apps.maps")!!
        assertEquals("fullscreen", full.mode)
        assertEquals(12, full.stackId)
        assertEquals(80, full.taskId)
        // Not a floating window: the tile's usual lookup does not see it.
        assertNull(WindowListing.parseFloatingWindow(mapsFullscreen, packageName = "com.google.android.apps.maps"))
    }

    @Test
    fun appBehindTheDashboardIsNotInFront() {
        val behind = """
            Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={... mWindowingMode=fullscreen mActivityType=home ...}
              taskId=2: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=12 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=80: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][1280,720] userId=0 visible=false
        """.trimIndent()
        assertNull(WindowListing.fullscreenInFront(behind, "com.google.android.apps.maps"))
        assertNull(WindowListing.fullscreenInFront("", "com.google.android.apps.maps"))
    }

    @Test
    fun windowAboveAnotherFullscreenAppIsNotTheAppInFront() {
        // Maps in its window over Chrome: Chrome covers the screen, not Maps.
        val overChrome = """
            Stack id=7 bounds=[640,80][1240,660] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[640,80][1240,660] userId=0 visible=true
            Stack id=4 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=70: com.android.chrome/com.google.android.apps.chrome.Main bounds=[0,0][1280,720] userId=0 visible=true
        """.trimIndent()
        assertNull(WindowListing.fullscreenInFront(overChrome, "com.google.android.apps.maps"))
    }

    @Test
    fun appFullscreenOnTheHiddenDisplayCoversNothing() {
        val hidden = mapsFullscreen.replace("Stack id=12 bounds=[0,0][1280,720] displayId=0", "Stack id=12 bounds=[0,0][1280,720] displayId=3")
        assertNull(WindowListing.fullscreenInFront(hidden, "com.google.android.apps.maps"))
    }

    @Test
    fun numericWindowingModeIsUnderstood() {
        val numeric = """
            Stack id=4 bounds=[0,0][600,400] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=5 mActivityType=standard} }
              taskId=9: com.waze/com.waze.MainActivity bounds=[0,0][600,400] userId=0 visible=true
        """.trimIndent()
        assertEquals("freeform", WindowListing.parseFloatingWindow(numeric)!!.mode)
    }

    @Test
    fun pinnedStackWithoutATaskIsIgnored() {
        val empty = """
            Stack id=3 bounds=[960,420][1264,608] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=pinned mActivityType=standard} }
        """.trimIndent()
        assertNull(WindowListing.parseFloatingWindow(empty))
    }

    @Test
    fun closeEnoughAllowsSystemAspectAdjustments() {
        val target = ScreenRect(400, 100, 900, 400)
        assertEquals(true, WindowListing.isClose(ScreenRect(400, 100, 900, 400), target))
        // Same centre, width shrunk by the aspect-ratio rule: still docked.
        assertEquals(true, WindowListing.isClose(ScreenRect(450, 120, 850, 380), target))
        // Parked in a corner: not docked.
        assertEquals(false, WindowListing.isClose(ScreenRect(960, 420, 1264, 608), target))
        // Centre inside but a quarter of the size: not docked.
        assertEquals(false, WindowListing.isClose(ScreenRect(600, 200, 700, 300), target))
    }

    @Test
    fun freeformBoundsComeFromTheTaskNotTheStack() {
        val listing = """
            Stack id=9 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mBounds=Rect(0, 0 - 1280, 720) mWindowingMode=freeform mActivityType=standard} }
              taskId=513: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[432,73][954,683] userId=0 visible=true
        """.trimIndent()
        val win = WindowListing.parseFloatingWindow(listing)!!
        assertEquals(ScreenRect(432, 73, 954, 683), win.bounds)
        assertEquals(513, win.taskId)
    }

    @Test
    fun hiddenWindowIsReportedAsNotVisible() {
        val listing = """
            Stack id=9 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=513: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[432,73][954,683] userId=0 visible=false
        """.trimIndent()
        assertEquals(false, WindowListing.parseFloatingWindow(listing)!!.visible)
    }

    @Test
    fun packageFilterPicksTheRightWindowAmongSeveral() {
        val listing = """
            Stack id=7 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,80][640,660] userId=0 visible=true
            Stack id=8 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=64: com.google.android.apps.youtube.music/.activities.MusicActivity bounds=[640,80][1280,660] userId=0 visible=true
        """.trimIndent()
        assertEquals(64, WindowListing.parseFloatingWindow(listing, packageName = "com.google.android.apps.youtube.music")!!.taskId)
        assertEquals(63, WindowListing.parseFloatingWindow(listing, packageName = "com.google.android.apps.maps")!!.taskId)
        assertNull(WindowListing.parseFloatingWindow(listing, packageName = "com.waze"))
    }

    @Test
    fun windowTallerThanItsTileIsMovedUpAboveTheBar() {
        val area = ScreenRect(0, 80, 1280, 640)
        // Grown past the bottom of the content area: shifted up, size kept.
        val grown = ScreenRect(200, 300, 800, 700)
        assertEquals(false, WindowListing.withinArea(grown, area))
        assertEquals(ScreenRect(200, 240, 800, 640), WindowListing.keepInside(grown, area))
        // Already inside: untouched.
        val ok = ScreenRect(200, 100, 800, 600)
        assertEquals(true, WindowListing.withinArea(ok, area))
        assertEquals(ok, WindowListing.keepInside(ok, area))
        // Taller than the whole area: the bottom edge wins, the top overflows.
        val huge = ScreenRect(0, 0, 640, 900)
        assertEquals(640, WindowListing.keepInside(huge, area).bottom)
    }

    @Test
    fun windowListedBehindTheDashboardIsFlagged() {
        val dash = """
            Stack id=1 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=41: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
        """.trimIndent()
        val maps = """
            Stack id=7 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,80][640,660] userId=0 visible=true
        """.trimIndent()
        assertEquals(true, WindowListing.parseFloatingWindow(dash + "\n" + maps)!!.behindDashboard)
        assertEquals(false, WindowListing.parseFloatingWindow(maps + "\n" + dash)!!.behindDashboard)
    }

    @Test
    fun windowParkedOnTheHiddenDisplayIsOffDisplayAndCoversNothing() {
        // Stacks are listed display by display, the hidden display's after the
        // screen's: listed "behind" the dashboard, but on another display.
        val listing = """
            Stack id=1 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=41: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=8 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=64: com.google.android.apps.youtube.music/.MusicActivity bounds=[640,80][1280,660] userId=0 visible=true
            Stack id=7 bounds=[0,0][1280,720] displayId=3 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,80][640,660] userId=0 visible=true
        """.trimIndent()
        val maps = WindowListing.parseFloatingWindow(listing, packageName = "com.google.android.apps.maps")!!
        assertEquals(3, maps.displayId)
        assertEquals(true, maps.offDisplay)
        assertEquals(false, maps.behindDashboard)
        // Its size and place survive the trip, for the tile to place it from.
        assertEquals(ScreenRect(0, 80, 640, 660), maps.bounds)
        val music = WindowListing.parseFloatingWindow(listing, packageName = "com.google.android.apps.youtube.music")!!
        assertEquals(0, music.displayId)
        assertEquals(false, music.offDisplay)
        assertEquals(true, music.behindDashboard)
        // A listing without display ids (an older format) counts everything as on the screen.
        val plain = listing.replace(Regex(" displayId=\\d+"), "")
        assertEquals(false, WindowListing.parseFloatingWindow(plain, packageName = "com.google.android.apps.maps")!!.offDisplay)
    }

    @Test
    fun hiddenWindowsWithoutATileStillCountAsStraysSoTheyStayParked() {
        val listing = """
            Stack id=7 bounds=[0,0][1280,720] displayId=3 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,80][640,660] userId=0 visible=true
        """.trimIndent()
        val strays = WindowListing.strayWindows(listing, managed = setOf("com.google.android.apps.maps"), active = emptySet())
        assertEquals(listOf(true), strays.map { it.offDisplay })
    }

    @Test
    fun strayWindowsAreTheManagedOnesWithoutATile() {
        val listing = """
            Stack id=7 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,80][640,660] userId=0 visible=true
            Stack id=8 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=64: com.google.android.apps.youtube.music/.MusicActivity bounds=[1276,80][1916,660] userId=0 visible=true
        """.trimIndent()
        val strays = WindowListing.strayWindows(listing, managed = setOf("com.google.android.apps.maps", "com.google.android.apps.youtube.music"), active = setOf("com.google.android.apps.maps"))
        assertEquals(listOf(8), strays.map { it.stackId })
    }

    @Test
    fun strayCheckIgnoresPinnedWindowsAndUnmanagedApps() {
        val listing = """
            Stack id=3 bounds=[960,420][1264,608] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=pinned mActivityType=standard} }
              taskId=57: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[960,420][1264,608] userId=0 visible=true
            Stack id=8 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=64: com.waze/com.waze.MainActivity bounds=[0,80][640,660] userId=0 visible=true
        """.trimIndent()
        val strays = WindowListing.strayWindows(listing, managed = setOf("com.google.android.apps.maps"), active = emptySet())
        assertEquals(emptyList<Int>(), strays.map { it.stackId })
    }

    @Test
    fun keepInsideAlsoPullsBackHorizontally() {
        val area = ScreenRect(0, 80, 1280, 640)
        assertEquals(ScreenRect(880, 100, 1280, 400), WindowListing.keepInside(ScreenRect(1000, 100, 1400, 400), area))
        assertEquals(ScreenRect(0, 100, 400, 400), WindowListing.keepInside(ScreenRect(-50, 100, 350, 400), area))
        assertEquals(true, WindowListing.withinArea(ScreenRect(-50, 100, 350, 400), null))
    }

    @Test
    fun popUpsOverlapATileOnlyWhenTheyTouchIt() {
        val tile = ScreenRect(8, 80, 540, 630)
        // The ⋮ menu at the bottom right is far from a Maps tile on the left.
        assertEquals(false, WindowListing.overlaps(ScreenRect(900, 300, 1270, 660), tile, margin = 12))
        // A centred dialog reaches into it.
        assertEquals(true, WindowListing.overlaps(ScreenRect(360, 150, 920, 570), tile, margin = 12))
        // Just clear of the tile, but within the shadow margin.
        assertEquals(true, WindowListing.overlaps(ScreenRect(548, 300, 800, 400), tile, margin = 12))
        assertEquals(false, WindowListing.overlaps(ScreenRect(548, 300, 800, 400), tile))
    }

    @Test
    fun frontTaskIsTheTopOneOnTheScreenBesidesPictureInPicture() {
        // Dashwheel's stack is listed first on display 0; the pinned one never counts.
        assertEquals(41, WindowListing.frontTask(android10))
        val tileInFront = """
            Stack id=9 bounds=[0,0][800,400] displayId=7 userId=0
             configuration={... mWindowingMode=fullscreen mActivityType=standard ...}
              taskId=70: com.google.android.apps.youtube.music/.MainActivity bounds=[0,0][800,400] userId=0 visible=true
            Stack id=4 bounds=[600,100][1200,500] displayId=0 userId=0
             configuration={... mWindowingMode=freeform mActivityType=standard ...}
              taskId=66: com.waze/.FreeMapAppActivity bounds=[600,100][1200,500] userId=0 visible=true
        """.trimIndent() + "\n" + android10
        // A window floating over the dashboard is the one raised, so it stays over it.
        assertEquals(66, WindowListing.frontTask(tileInFront))
        assertEquals(70, WindowListing.frontTask(tileInFront, displayId = 7))
    }
}
