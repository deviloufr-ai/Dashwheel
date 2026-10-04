package com.openauto.dash.display

import java.io.File

/**
 * The monitor mounted upside down (the head unit's "Turn the picture" setting):
 * every picture is turned 180°. Kept in `rotate180` in the config folder, so
 * the next start is already the right way up, the boot logo included (as the
 * HDMI panel's orientation on the kernel command line, which Plymouth follows).
 */
object Rotation {
    private const val FILE = "rotate180"
    private val PANEL = Regex("""\s*video=HDMI-A-1:panel_orientation=[a-z_]+""")
    private const val UPSIDE_DOWN = " video=HDMI-A-1:panel_orientation=upside_down"

    @Volatile var upsideDown: Boolean = false
        private set

    fun load(dir: File) {
        upsideDown = File(dir, FILE).exists()
    }

    /** True when it changed. */
    fun set(dir: File, on: Boolean): Boolean {
        if (on == upsideDown) return false
        upsideDown = on
        ReadOnlyCard.write(dir) {
            val flag = File(dir, FILE)
            if (on) flag.writeText("") else flag.delete()
            val boot = dir.parentFile ?: return@write
            val cmdline = File(boot, "cmdline.txt")
            if (cmdline.isFile) {
                val line = cmdline.readText().trim().replace(PANEL, "")
                cmdline.writeText(line + (if (on) UPSIDE_DOWN else "") + "\n")
            }
        }
        return true
    }
}
