package com.openauto.dash

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The steering wheel button map: what is saved, and which way of a press is kept. */
class WheelButtonMapTest {

    @Test
    fun buttonsLearnedBeforeTheMapComeBackUnplacedWithTheirAction() {
        val old = """[{"keyCode":87,"scanCode":163,"t":"preset","a":"MEDIA_NEXT"},{"keyCode":0,"scanCode":0,"canKey":"65.0C","canHex":"02 01","t":"app","pkg":"com.waze","label":"Waze"}]"""
        val buttons = wheelButtonsFromJson(old)
        assertEquals(2, buttons.size)
        assertEquals("k87", buttons[0].key!!.id)
        assertEquals("k87", buttons[0].uid)
        assertEquals(WheelZone.UNPLACED, buttons[0].zone)
        assertEquals(WheelAssignment.Preset(SteeringWheelAction.MEDIA_NEXT), buttons[0].assignment)
        assertEquals("c:65.0C=02 01", buttons[1].key!!.id)
        assertEquals(WheelAssignment.LaunchApp("com.waze", "Waze"), buttons[1].assignment)
    }

    @Test
    fun theMapIsSavedAndReadBackAsItWas() {
        val buttons = listOf(
            WheelButton("a1", WheelKey(KeyEvent.KEYCODE_MEDIA_NEXT, 163), "Next", WheelZone.RIGHT, WheelAssignment.Preset(SteeringWheelAction.MEDIA_NEXT)),
            WheelButton("b2", WheelKey.input("KEY_PHONE@event2"), "Phone", WheelZone.LEFT),
            WheelButton("c3", WheelKey.can("65.0C", "04 00"), "", WheelZone.STALK),
            // A button with no signal: nothing but its name and place.
            WheelButton("d4", null, "Cruise", WheelZone.LEFT),
            // A CAN box button the unit is told to ignore.
            WheelButton("e5", WheelKey.vendor(CarBoxKeys.VENDOR, 0x29), "Voice", WheelZone.LEFT, unitIgnores = true),
            // Something else in mode 2: an action, or an app.
            WheelButton("f6", WheelKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 165), "Back", WheelZone.RIGHT,
                WheelAssignment.Preset(SteeringWheelAction.PREVIOUS_DASHBOARD), second = WheelAssignment.Preset(SteeringWheelAction.SECOND_SCREEN_PREVIOUS)),
            WheelButton("g7", WheelKey.can("65.0C", "08 00"), "", WheelZone.RIGHT, second = WheelAssignment.LaunchApp("com.waze", "Waze")),
            // A button learned from a line of the unit's log, trimmed to what names it.
            WheelButton("h8", WheelKey.log("MainKey: key 576"), "Voice", WheelZone.LEFT, WheelAssignment.Preset(SteeringWheelAction.VOICE_ASSISTANT))
        )
        assertEquals(buttons, wheelButtonsFromJson(wheelButtonsToJson(buttons)))
    }

    @Test
    fun ofOnePressSeenSeveralWaysTheKeyIsKept() {
        val key = WheelKey(KeyEvent.KEYCODE_MEDIA_NEXT, 163)
        val input = WheelKey.input("KEY_NEXTSONG@event2")
        val can = WheelKey.can("65.0C", "02 01")
        val raw = WheelKey(KeyEvent.KEYCODE_UNKNOWN, 250)
        val log = WheelKey.log("MainKey: key 576")
        assertEquals(key, bestOf(listOf(can, input, key)))
        assertEquals(raw, bestOf(listOf(input, raw)))
        assertEquals(input, bestOf(listOf(can, input)))
        assertEquals(can, bestOf(listOf(log, can)))
        assertNull(bestOf(emptyList()))
    }

    @Test
    fun aLogLineIsLearnedWithoutItsProcessIdAndMatchedByItsText() {
        // logcat -v brief: the process id changes with every boot and is left out.
        assertEquals("MainKey: key 576 down", WheelMonitor.logPattern("D/MainKey( 1234): key 576 down"))
        assertEquals("MainKey: key 576 down", WheelMonitor.logPattern("D/MainKey(27): key 576 down"))
        assertEquals("KeyEvent: KEYCODE_VOLUME_UP", WheelMonitor.logPattern("I/KeyEvent  (  585): KEYCODE_VOLUME_UP"))
        assertEquals("some other line about a key", WheelMonitor.logPattern("  some other line about a key  "))
        val key = WheelKey.log("key 576")
        assertEquals("l:key 576", key.id)
        assertTrue(key.matchesLog("MainKey: key 576 down"))
        assertFalse(key.matchesLog("MainKey: key 577 down"))
        assertFalse(WheelKey.log("").matchesLog("anything"))
        assertFalse(WheelKey.input("KEY_PHONE@event2").matchesLog("KEY_PHONE@event2"))
    }

    @Test
    fun aUnitKeyAndTheSameAndroidKeyAreOneButton() {
        // The unit's key service gives no scan code: the key code alone names the button.
        assertEquals(WheelKey(24, 115).id, WheelKey(24, 0).id)
    }

    @Test
    fun inputLinesGiveTheKeyGoingDownWithItsDevice() {
        assertEquals("KEY_NEXTSONG@event2", WheelMonitor.inputPress("/dev/input/event2: EV_KEY       KEY_NEXTSONG         DOWN"))
        assertNull(WheelMonitor.inputPress("/dev/input/event2: EV_KEY       KEY_NEXTSONG         UP"))
        assertNull(WheelMonitor.inputPress("/dev/input/event2: EV_SYN       SYN_REPORT           00000000"))
        assertEquals("KEY_VOLUMEUP", WheelMonitor.inputPress("EV_KEY       KEY_VOLUMEUP         DOWN"))
        assertNull(WheelMonitor.inputPress("/dev/input/event1: EV_KEY       BTN_TOUCH            DOWN"))
        assertNull(WheelMonitor.inputPress("/dev/input/event1: EV_KEY       BTN_TOOL_FINGER      DOWN"))
        assertNull(WheelMonitor.inputPress("/dev/input/event5: EV_KEY       BTN_LEFT             DOWN"))
    }
}
