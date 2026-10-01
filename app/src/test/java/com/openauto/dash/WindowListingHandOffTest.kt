package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Gemini's icon opens its screen in the Google app: the tile follows that app. Listings from the K706. */
class WindowListingHandOffTest {

    private val dashInFront = """
Stack id=78 bounds=[0,0][522,638] displayId=20 userId=0
  taskId=2109: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][522,638] userId=0 visible=true
Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
  taskId=2108: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
Stack id=63 bounds=[0,0][1280,720] displayId=0 userId=0
  taskId=2092: com.android.systemui/com.android.systemui.recents.RecentsActivity bounds=[0,0][1280,720] userId=0 visible=false
""".trimIndent()

    private val googleInFront = """
Stack id=78 bounds=[0,0][522,638] displayId=20 userId=0
  taskId=2109: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][522,638] userId=0 visible=true
Stack id=83 bounds=[0,0][1280,720] displayId=0 userId=0
  taskId=2114: com.google.android.googlequicksearchbox/com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
  taskId=2108: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=false
""".trimIndent()

    @Test
    fun geminiHandsItsScreenToTheGoogleApp() {
        assertEquals(
            "com.google.android.googlequicksearchbox",
            WindowListing.handedTo(dashInFront, googleInFront, "com.google.android.apps.bard", "com.openauto.dash")
        )
    }

    @Test
    fun nothingNewInFrontIsNoHandOff() {
        assertNull(WindowListing.handedTo(dashInFront, dashInFront, "com.google.android.apps.bard", "com.openauto.dash"))
        // Already in front before the start: not something it opened.
        assertNull(WindowListing.handedTo(googleInFront, googleInFront, "com.google.android.apps.bard", "com.openauto.dash"))
    }

    @Test
    fun theAppItselfInFrontIsNoHandOff() {
        assertNull(WindowListing.handedTo(dashInFront, googleInFront, "com.google.android.googlequicksearchbox", "com.openauto.dash"))
    }

    @Test
    fun anotherTilesAppIsNoHandOff() {
        // Seen on the unit: Maps took YouTube Music, started on its own tile at the same moment.
        assertNull(
            WindowListing.handedTo(
                dashInFront, googleInFront, "com.google.android.apps.maps", "com.openauto.dash",
                others = setOf("com.google.android.googlequicksearchbox")
            )
        )
    }
}
