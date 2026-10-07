package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondScreenRulesTest {

    private fun output(
        config: SecondScreenConfig,
        connected: Boolean = true,
        canStream: Boolean = true,
        moving: Boolean = false,
        appIsVideo: Boolean = false,
        canMoveApps: Boolean = true
    ) = SecondScreenRules.output(config, connected, canStream, moving, appIsVideo, canMoveApps)

    @Test
    fun appsNeedAnAndroidThatMovesThem() {
        val app = SecondScreenConfig(mode = SecondScreenMode.APP, appPackage = "com.waze")
        assertEquals(SecondScreenOutput.VIDEO_CLUSTER to SecondScreenBlock.APPS_NOT_SUPPORTED, output(app, canMoveApps = false))
        assertTrue(SecondScreenRules.appsMovable(29))
        assertFalse(SecondScreenRules.appsMovable(35))
    }

    @Test
    fun offOrNoDisplaySendsNothing() {
        assertEquals(SecondScreenOutput.NONE to SecondScreenBlock.NONE, output(SecondScreenConfig(mode = SecondScreenMode.OFF)))
        assertEquals(SecondScreenOutput.NONE to SecondScreenBlock.NONE, output(SecondScreenConfig(), connected = false))
    }

    @Test
    fun clusterIsStreamedWhenItCanBe() {
        assertEquals(SecondScreenOutput.VIDEO_CLUSTER to SecondScreenBlock.NONE, output(SecondScreenConfig()))
        // No encoder here, or a display without a decoder: it draws the cluster from readings.
        assertEquals(SecondScreenOutput.DATA to SecondScreenBlock.NONE, output(SecondScreenConfig(), canStream = false))
        // Or the driver asked for readings only.
        assertEquals(SecondScreenOutput.DATA to SecondScreenBlock.NONE, output(SecondScreenConfig(video = false)))
    }

    @Test
    fun appModeFallsBackToTheClusterAndSaysWhy() {
        val app = SecondScreenConfig(mode = SecondScreenMode.APP, appPackage = "com.google.android.apps.maps")
        assertEquals(SecondScreenOutput.VIDEO_APP to SecondScreenBlock.NONE, output(app))
        assertEquals(SecondScreenOutput.VIDEO_CLUSTER to SecondScreenBlock.NO_APP_CHOSEN, output(app.copy(appPackage = null)))
        assertEquals(SecondScreenOutput.DATA to SecondScreenBlock.NO_VIDEO, output(app, canStream = false))
    }

    @Test
    fun videoAppsWaitForTheCarToStopUnlessTheScreenIsForTheBack() {
        val film = SecondScreenConfig(mode = SecondScreenMode.APP, appPackage = "com.netflix.mediaclient")
        assertEquals(SecondScreenOutput.VIDEO_CLUSTER to SecondScreenBlock.VIDEO_WHILE_MOVING, output(film, moving = true, appIsVideo = true))
        assertEquals(SecondScreenOutput.VIDEO_APP to SecondScreenBlock.NONE, output(film, moving = false, appIsVideo = true))
        assertEquals(SecondScreenOutput.VIDEO_APP to SecondScreenBlock.NONE, output(film.copy(rearSeat = true), moving = true, appIsVideo = true))
        // Maps is no video: it stays while driving.
        assertEquals(SecondScreenOutput.VIDEO_APP to SecondScreenBlock.NONE, output(film.copy(appPackage = "com.google.android.apps.maps"), moving = true))
    }

    @Test
    fun pagesWrapAndSkipThoseLeftOut() {
        val pages = listOf(ClusterPage.DRIVE, ClusterPage.NAV, ClusterPage.OBD)
        assertEquals(ClusterPage.NAV, SecondScreenRules.nextPage(pages, ClusterPage.DRIVE))
        assertEquals(ClusterPage.DRIVE, SecondScreenRules.nextPage(pages, ClusterPage.OBD))
        assertEquals(ClusterPage.OBD, SecondScreenRules.nextPage(pages, ClusterPage.DRIVE, step = -1))
        // A page no longer in the list: back to the first.
        assertEquals(ClusterPage.DRIVE, SecondScreenRules.nextPage(pages, ClusterPage.MEDIA))
        assertEquals(ClusterPage.MEDIA, SecondScreenRules.nextPage(emptyList(), ClusterPage.MEDIA))
    }

    @Test
    fun streamKeepsTheMonitorsShapeInSixteens() {
        assertEquals(1024 to 608, SecondScreenRules.streamSize(1024, 600, maxHeight = 720))
        assertEquals(1280 to 720, SecondScreenRules.streamSize(1920, 1080, maxHeight = 720))
        assertEquals(848 to 480, SecondScreenRules.streamSize(1920, 1080, maxHeight = 480))
        assertEquals(720 to 576, SecondScreenRules.streamSize(720, 576, maxHeight = 720))
        // Never bigger than the display's decoder takes.
        assertEquals(1920 to 1088, SecondScreenRules.streamSize(3840, 2160, maxHeight = 1080))
        assertEquals(1280 to 720, SecondScreenRules.streamSize(3840, 2160, maxHeight = 1080, decoderWidth = 1280, decoderHeight = 720))
        SecondScreenRules.STREAM_HEIGHTS.forEach { h ->
            val (w, hh) = SecondScreenRules.streamSize(1366, 768, h)
            assertTrue(w % 16 == 0 && hh % 16 == 0)
        }
    }

    @Test
    fun hotspotProbeCoversItsOwnSubnetOnly() {
        fun ip(a: Int, b: Int, c: Int, d: Int) = (a shl 24) or (b shl 16) or (c shl 8) or d
        val own = ip(192, 168, 43, 17)
        val phone = ip(192, 168, 43, 1)
        val hosts = SecondScreenRules.hostsToProbe(own, 24, setOf(phone))
        assertEquals(252, hosts.size)
        assertFalse(own in hosts || phone in hosts)
        assertTrue(ip(192, 168, 43, 2) in hosts && ip(192, 168, 43, 254) in hosts)
        assertFalse(ip(192, 168, 43, 255) in hosts || ip(192, 168, 43, 0) in hosts)
        // A /16 hotspot: only this unit's own /24 is tried.
        assertEquals(253, SecondScreenRules.hostsToProbe(ip(10, 0, 5, 9), 16).size)
        // A small subnet stays small: in a /30, .0 is the network and .3 the broadcast.
        assertEquals(listOf(ip(172, 20, 10, 1)), SecondScreenRules.hostsToProbe(ip(172, 20, 10, 2), 30))
        assertEquals(5, SecondScreenRules.hostsToProbe(ip(172, 20, 10, 2), 29).size)
    }

    @Test
    fun settingsListsSurviveOddValues() {
        assertEquals("DRIVE,NAV", SecondScreenCodec.encodePages(listOf(ClusterPage.DRIVE, ClusterPage.NAV)))
        assertEquals(listOf(ClusterPage.DRIVE, ClusterPage.NAV), SecondScreenCodec.decodePages("DRIVE, NAV,WARP,DRIVE"))
        assertEquals(ClusterPage.entries, SecondScreenCodec.decodePages(null))
        assertEquals(ClusterPage.entries, SecondScreenCodec.decodePages("nonsense"))
        assertEquals(setOf(24, 87), SecondScreenCodec.decodeKeys("87,24,x,-3,"))
        assertEquals("24,87", SecondScreenCodec.encodeKeys(setOf(87, 24)))
        assertEquals(emptySet<Int>(), SecondScreenCodec.decodeKeys(null))
    }

    @Test
    fun throttleSendsChangesPromptlyButNotTooOften() {
        val t = ClusterThrottle(minGapMs = 200, heartbeatMs = 5_000)
        assertTrue(t.shouldSend("a", 0))
        assertFalse(t.shouldSend("a", 100))
        assertFalse(t.shouldSend("b", 150)) // changed, but too soon
        assertTrue(t.shouldSend("b", 250))
        assertFalse(t.shouldSend("b", 4_000))
        assertTrue(t.shouldSend("b", 5_300)) // heartbeat
    }

    @Test
    fun videoAppsAreKnownByCategoryOrName() {
        assertTrue(VideoApps.isVideo("com.netflix.mediaclient", null))
        assertTrue(VideoApps.isVideo("com.google.android.youtube", null))
        assertTrue(VideoApps.isVideo("org.example.player", VideoApps.CATEGORY_VIDEO))
        assertFalse(VideoApps.isVideo("com.google.android.apps.maps", 6))
        assertFalse(VideoApps.isVideo("com.netflixer", null))
    }

    @Test
    fun stackOfFindsAnAppWhateverItsModeAndDisplay() {
        val listing = """
            Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={... mWindowingMode=fullscreen mActivityType=home ...}
              taskId=2: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=7 bounds=[640,80][1240,660] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[640,80][1240,660] userId=0 visible=true
            Stack id=12 bounds=[0,0][1024,608] displayId=5 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=81: com.spotify.music/.MainActivity bounds=[0,0][1024,608] userId=0 visible=true
        """.trimIndent()
        assertEquals(7 to 0, WindowListing.stackOf(listing, "com.google.android.apps.maps"))
        assertEquals(12 to 5, WindowListing.stackOf(listing, "com.spotify.music"))
        assertNull(WindowListing.stackOf(listing, "com.waze"))
        // The launcher's own home stack is never one to move.
        assertNull(WindowListing.stackOf(listing, "com.openauto.dash"))
    }

    @Test
    fun bitrateBacksOffAndRemembersTheRateThatFailed() {
        val start = SecondScreenRules.BitrateState(2500)
        // 10 of 100 frames lost: a quarter down, and 2500 is the ceiling.
        val cut = SecondScreenRules.adaptBitrate(start, 2500, shown = 90, dropped = 10, nowMs = 0)
        assertEquals(SecondScreenRules.BitrateState(1875, ceilingKbps = 2500), cut)
        // Never below the floor.
        assertEquals(SecondScreenRules.MIN_BITRATE_KBPS, SecondScreenRules.adaptBitrate(SecondScreenRules.BitrateState(700), 2500, 50, 50, 0).kbps)
        // A frame or two lost: hold, and the clean stretch starts over.
        val held = SecondScreenRules.BitrateState(2000, 2500, cleanSinceMs = 5_000)
        assertEquals(held.copy(cleanSinceMs = null), SecondScreenRules.adaptBitrate(held, 2500, 75, 1, 10_000))
        // Nothing played: hold; a lowered target applies at once.
        assertEquals(2000, SecondScreenRules.adaptBitrate(SecondScreenRules.BitrateState(2000), 2500, 0, 0, 0).kbps)
        assertEquals(1500, SecondScreenRules.adaptBitrate(SecondScreenRules.BitrateState(2000), 1500, 75, 0, 0).kbps)
    }

    @Test
    fun bitrateClimbsBackOnlyToJustUnderTheCeiling() {
        var s = SecondScreenRules.BitrateState(1875, ceilingKbps = 2500)
        val seen = mutableListOf<Int>()
        // Clean reports every 3 s for 50 s: up a tenth of the target at a time, then it holds at 2250.
        for (t in 0..50_000L step 3_000) {
            s = SecondScreenRules.adaptBitrate(s, 2500, 75, 0, t)
            seen += s.kbps
        }
        assertEquals(listOf(2125, 2250, 2250), seen.take(3))
        assertEquals(2250, s.kbps)
        assertEquals(2500, s.ceilingKbps)
    }

    @Test
    fun bitrateSettlesInsteadOfSawing() {
        // A link that drops frames at 2000 kbit/s and above, clean below.
        var s = SecondScreenRules.BitrateState(2500)
        val rates = mutableListOf<Int>()
        var t = 0L
        repeat(200) {
            val dropped = if (s.kbps >= 2000) 10 else 0
            s = SecondScreenRules.adaptBitrate(s, 2500, 90, dropped, t)
            rates += s.kbps
            t += 3_000
        }
        // Over the last five minutes (100 reports) it fails at most a couple of times, one probe every few minutes,
        // where the old rule failed every few reports.
        val failures = rates.takeLast(100).zipWithNext().count { (a, b) -> b < a }
        assertTrue("failed $failures times", failures <= 3)
        assertTrue(rates.takeLast(100).all { it in 1500..2200 })
    }

    @Test
    fun bitrateProbesAboveTheCeilingAfterACleanMinuteInSmallSteps() {
        var s = SecondScreenRules.BitrateState(2250, ceilingKbps = 2500, cleanSinceMs = 0)
        // Under a minute: stays put.
        s = SecondScreenRules.adaptBitrate(s, 3000, 75, 0, 59_000)
        assertEquals(2250, s.kbps)
        // A clean minute: the ceiling lifts a twentieth of the target, the rate follows just under it.
        s = SecondScreenRules.adaptBitrate(s, 3000, 75, 0, 60_000)
        assertEquals(2650, s.ceilingKbps)
        assertEquals(2385, s.kbps)
        assertEquals(60_000L, s.cleanSinceMs)
        // Next lift only a minute later.
        s = SecondScreenRules.adaptBitrate(s, 3000, 75, 0, 90_000)
        assertEquals(2650, s.ceilingKbps)
        // Lifted past the target: the ceiling is gone and the rate goes back to the target.
        var u = SecondScreenRules.BitrateState(2430, ceilingKbps = 2700, cleanSinceMs = 0)
        u = SecondScreenRules.adaptBitrate(u, 2500, 75, 0, 60_000)
        assertNull(u.ceilingKbps)
        assertEquals(2500, u.kbps)
    }

    @Test
    fun aNewStreamStartsJustUnderAKnownCeiling() {
        assertEquals(SecondScreenRules.BitrateState(2500), SecondScreenRules.BitrateState(0).restart(2500))
        assertEquals(SecondScreenRules.BitrateState(1800, 2000), SecondScreenRules.BitrateState(1500, 2000, 9_000).restart(2500))
        // A ceiling above a lowered target no longer matters.
        assertEquals(SecondScreenRules.BitrateState(1500), SecondScreenRules.BitrateState(1500, 2000).restart(1500))
    }

    @Test
    fun theClusterStaysBlackUnlessAskedToFollowDayAndNight() {
        val plain = SecondScreenConfig()
        assertEquals(ClusterBackground.BLACK, SecondScreenRules.background(plain, day = true))
        assertEquals(ClusterBackground.BLACK, SecondScreenRules.background(plain, day = false))
        val follows = plain.copy(followDayNight = true)
        assertEquals(ClusterBackground.LIGHT, SecondScreenRules.background(follows, day = true))
        assertEquals(ClusterBackground.BLACK, SecondScreenRules.background(follows, day = false))
        // Light chosen and following: dark at night all the same.
        assertEquals(ClusterBackground.DARK, SecondScreenRules.background(follows.copy(background = ClusterBackground.LIGHT), day = false))
    }
}
