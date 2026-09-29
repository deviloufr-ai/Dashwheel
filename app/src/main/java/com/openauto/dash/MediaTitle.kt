package com.openauto.dash

/**
 * Video apps (YouTube Music, YouTube) publish the video's name as the title:
 * "Artist - Song (Official Music Video)". The artist is already on its own
 * line and the rest is noise, so both go; what the song is called stays.
 */
internal object MediaTitle {
    private val NOISE = Regex(
        """\s*[(\[][^()\[\]]*\b(official|officiel|officielle|video|vidéo|clip|audio|lyrics?|paroles|visuali[sz]er|hd|hq|4k|m/?v)\b[^()\[\]]*[)\]]\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val DASH = Regex("""^\s*[-–—:|]\s*""")

    fun clean(title: String, artist: String): String {
        var t = title.trim()
        if (artist.isNotBlank() && t.startsWith(artist.trim(), ignoreCase = true)) {
            val rest = t.substring(artist.trim().length)
            if (DASH.containsMatchIn(rest)) t = rest.replaceFirst(DASH, "")
        }
        while (true) {
            val shorter = t.replace(NOISE, "")
            if (shorter == t || shorter.isBlank()) break
            t = shorter
        }
        return t.ifBlank { title }
    }
}
