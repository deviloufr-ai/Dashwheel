package com.openauto.dash.display

import java.io.File

/** The monitor's size, as the kernel's DRM driver reports it. */
data class ScreenMode(val width: Int, val height: Int, val refreshHz: Int = 60) {

    companion object {
        /** When nothing is connected (or the board has no DRM): a common 7" car monitor. */
        val FALLBACK = ScreenMode(1024, 600)

        private val SIZE = Regex("""^(\d{3,4})x(\d{3,4})""")

        /**
         * The first mode of the first connected output under [drm]
         * (`/sys/class/drm`): HDMI first, then composite. The first mode listed
         * is the monitor's preferred one.
         */
        fun detect(drm: File = File("/sys/class/drm")): ScreenMode? {
            val outputs = drm.listFiles { f -> f.isDirectory && f.name.startsWith("card") && '-' in f.name }
                ?.sortedBy { if ("HDMI" in it.name) 0 else 1 }
                ?: return null
            for (output in outputs) {
                val status = runCatching { File(output, "status").readText().trim() }.getOrNull()
                if (status != "connected") continue
                val modes = runCatching { File(output, "modes").readText() }.getOrNull() ?: continue
                parseModes(modes)?.let { return it }
            }
            return null
        }

        /** The first mode in a DRM `modes` file ("1024x600\n800x480\n..."; composite may say "720x576i"). */
        fun parseModes(text: String): ScreenMode? =
            text.lineSequence().firstNotNullOfOrNull { line ->
                SIZE.find(line.trim())?.let { ScreenMode(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            }
    }
}
