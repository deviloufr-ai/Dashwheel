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
        appIsVideo: Boolean = false
    ) = SecondScreenRules.output(config, connected, canStream, moving, appIsVideo)

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
    fun bitrateBacksOffWhenFramesAreLostAndRecoversSlowly() {
        // 10 of 100 frames lost: a quarter down.
        assertEquals(1875, SecondScreenRules.adaptBitrate(2500, 2500, shown = 90, dropped = 10))
        // Never below the floor.
        assertEquals(SecondScreenRules.MIN_BITRATE_KBPS, SecondScreenRules.adaptBitrate(700, 2500, 50, 50))
        // A clean period: a tenth of the target back, never past it.
        assertEquals(2125, SecondScreenRules.adaptBitrate(1875, 2500, 75, 0))
        assertEquals(2500, SecondScreenRules.adaptBitrate(2450, 2500, 75, 0))
        // A frame or two lost: hold.
        assertEquals(2000, SecondScreenRules.adaptBitrate(2000, 2500, 75, 1))
        // Nothing played: hold; a lowered target applies at once.
        assertEquals(2000, SecondScreenRules.adaptBitrate(2000, 2500, 0, 0))
        assertEquals(1500, SecondScreenRules.adaptBitrate(2000, 1500, 75, 0))
    }
}
