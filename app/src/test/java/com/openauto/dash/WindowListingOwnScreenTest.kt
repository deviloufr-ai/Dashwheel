package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowListingOwnScreenTest {

    // As `am stack list` printed them on the K706 (2026-10-09).
    private fun listing(top: String) = """
        Stack id=227 bounds=[0,0][1280,720] displayId=0 userId=0
         configuration={1.0 ?mcc?mnc [fr_FR] ldltr sw720dp w1280dp h648dp 160dpi xlrg long land finger -keyb/v/h -nav/h winConfig={ mWindowingMode=fullscreen } s.569}
          taskId=2995: com.waze/com.waze.MainActivity bounds=[0,0][1280,720] userId=0 visible=true topActivity=ComponentInfo{$top}

        Stack id=164 bounds=[0,0][1280,720] displayId=0 userId=0
          taskId=2934: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=false topActivity=ComponentInfo{com.openauto.dash/com.openauto.dash.MainActivity}
    """.trimIndent()

    @Test
    fun wazeListeningForAPlaceShowsAScreenOfItsOwn() {
        assertTrue(WindowListing.showsOwnScreen(listing("com.waze/com.waze.google_assistant.SpeechRecognizerActivity"), 2995))
        // The permission request over it counts too.
        assertTrue(WindowListing.showsOwnScreen(listing("com.android.permissioncontroller/com.android.packageinstaller.permission.ui.GrantPermissionsActivity"), 2995))
    }

    @Test
    fun wazeOnItsMapIsReadyToGoBack() {
        assertFalse(WindowListing.showsOwnScreen(listing("com.waze/com.waze.MainActivity"), 2995))
    }

    @Test
    fun theVoicePromptIsToldApart() {
        val voice = WindowListing.ownScreenOf(listing("com.waze/com.waze.google_assistant.SpeechRecognizerActivity"), 2995)
        assertEquals("com.waze/com.waze.google_assistant.SpeechRecognizerActivity", voice)
        assertTrue(WindowListing.isVoiceScreen(voice!!))
        // A place card or a permission request is a screen of its own, but no voice prompt.
        assertFalse(WindowListing.isVoiceScreen(WindowListing.ownScreenOf(listing("com.waze/com.waze.navigate.location_preview.LocationPreviewActivity"), 2995)!!))
        assertFalse(WindowListing.isVoiceScreen("com.android.permissioncontroller/com.android.packageinstaller.permission.ui.GrantPermissionsActivity"))
        assertNull(WindowListing.ownScreenOf(listing("com.waze/com.waze.MainActivity"), 2995))
    }

    @Test
    fun theSearchIsForTypingAndGoesBackToTheTile() {
        // As the K706 listed it on 2026-10-10: the search opened in a task of its own, over MainActivity.
        val search = WindowListing.ownScreenOf(listing("com.waze/com.waze.search_v2.SearchV2Activity"), 2995)
        assertEquals("com.waze/com.waze.search_v2.SearchV2Activity", search)
        assertTrue(WindowListing.isTypingScreen(search!!))
        // The voice prompt, a place card and a permission request are waited for, not moved.
        assertFalse(WindowListing.isTypingScreen("com.waze/com.waze.google_assistant.SpeechRecognizerActivity"))
        assertFalse(WindowListing.isTypingScreen("com.waze/com.waze.navigate.location_preview.LocationPreviewActivity"))
        assertFalse(WindowListing.isTypingScreen("com.android.permissioncontroller/com.android.packageinstaller.permission.ui.GrantPermissionsActivity"))
        assertFalse(WindowListing.isTypingScreen("com.waze/com.waze.reports.AddPlaceFlowActivity"))
    }

    @Test
    fun anotherTaskOrNoneIsNotAsked() {
        assertFalse(WindowListing.showsOwnScreen(listing("com.waze/com.waze.google_assistant.SpeechRecognizerActivity"), 1234))
        assertFalse(WindowListing.showsOwnScreen("", 2995))
    }
}
