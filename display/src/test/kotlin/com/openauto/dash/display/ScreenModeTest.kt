package com.openauto.dash.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScreenModeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun firstModeIsThePreferredOne() {
        assertEquals(ScreenMode(1024, 600), ScreenMode.parseModes("1024x600\n800x480\n640x480\n"))
        assertEquals(ScreenMode(720, 576), ScreenMode.parseModes("720x576i\n720x480i\n"))
        assertNull(ScreenMode.parseModes(""))
    }

    private fun output(name: String, status: String, modes: String) {
        File(tmp.root, name).apply {
            mkdirs()
            File(this, "status").writeText("$status\n")
            File(this, "modes").writeText(modes)
        }
    }

    @Test
    fun takesTheConnectedOutputHdmiFirst() {
        output("card0-Composite-1", "connected", "720x576i\n")
        output("card0-HDMI-A-1", "connected", "1280x720\n")
        output("card0-HDMI-A-2", "disconnected", "")
        assertEquals(ScreenMode(1280, 720), ScreenMode.detect(tmp.root))
    }

    @Test
    fun compositeWhenNoHdmi() {
        output("card0-HDMI-A-1", "disconnected", "")
        output("card0-Composite-1", "connected", "720x480i\n")
        File(tmp.root, "card0").mkdirs()
        assertEquals(ScreenMode(720, 480), ScreenMode.detect(tmp.root))
    }

    @Test
    fun nothingConnected() {
        output("card0-HDMI-A-1", "disconnected", "")
        assertNull(ScreenMode.detect(tmp.root))
        assertNull(ScreenMode.detect(File(tmp.root, "missing")))
    }
}
