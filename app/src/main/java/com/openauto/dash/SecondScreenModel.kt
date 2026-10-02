package com.openauto.dash

/*
 * The second screen: a Raspberry Pi wired to a monitor, on the phone's hotspot
 * (see DisplayLink and tools/pi/README.md). What it should show and how, as
 * plain values and rules, so they are unit-tested without Android.
 */

enum class SecondScreenMode {
    OFF,

    /** A read-only instrument cluster: speed, media, directions, readings. */
    CLUSTER,

    /** A chosen app (Google Maps, say), running on the head unit and shown there. */
    APP
}

/** The cluster's pages, cycled from Settings, the tile or a steering-wheel key. */
enum class ClusterPage { DRIVE, MEDIA, NAV, OBD }

data class SecondScreenConfig(
    val mode: SecondScreenMode = SecondScreenMode.CLUSTER,
    /** The app shown in [SecondScreenMode.APP]. */
    val appPackage: String? = null,
    /** The pages cycled through, in order; never empty. */
    val pages: List<ClusterPage> = ClusterPage.entries,
    val page: ClusterPage = ClusterPage.DRIVE,
    /** Stream the cluster as video (as on the head unit) rather than let the display draw it from data. */
    val video: Boolean = true,
    /** Tallest picture to stream, whatever the monitor's size: less is lighter on the Wi-Fi. */
    val maxHeight: Int = 720,
    val bitrateKbps: Int = 2_500,
    /** The monitor faces the back seats: video apps may play while driving. */
    val rearSeat: Boolean = false,
    /** Key codes learnt as "next page" (steering-wheel buttons). */
    val pageKeys: Set<Int> = emptySet(),
    /** A long press on media next / previous turns the page, while the cluster shows. */
    val mediaKeysTurnPages: Boolean = false
)

/** What the head unit actually sends the display. */
enum class SecondScreenOutput {
    /** Nothing: off, or no display connected. */
    NONE,

    /** The cluster, drawn here and streamed as H.264. */
    VIDEO_CLUSTER,

    /** The chosen app, running on the streamed screen. */
    VIDEO_APP,

    /** Readings only; the display draws its own cluster. */
    DATA
}

/** Why the app asked for can't be shown (the display then shows the cluster from data). */
enum class SecondScreenBlock { NONE, NO_APP_CHOSEN, NO_VIDEO, VIDEO_WHILE_MOVING }

object SecondScreenRules {

    val STREAM_HEIGHTS = listOf(480, 720, 1080)

    /**
     * What to send, and why the choice isn't what [config] asks for, if it isn't.
     * [canStream]: the display decodes H.264 and the encoder here is available.
     * [appIsVideo]: the chosen app plays video (films, YouTube), which the
     * driver must not see while [moving] unless the monitor is for the back seats.
     */
    fun output(
        config: SecondScreenConfig,
        connected: Boolean,
        canStream: Boolean,
        moving: Boolean,
        appIsVideo: Boolean
    ): Pair<SecondScreenOutput, SecondScreenBlock> {
        if (config.mode == SecondScreenMode.OFF || !connected) return SecondScreenOutput.NONE to SecondScreenBlock.NONE
        val cluster = if (config.video && canStream) SecondScreenOutput.VIDEO_CLUSTER else SecondScreenOutput.DATA
        if (config.mode == SecondScreenMode.CLUSTER) return cluster to SecondScreenBlock.NONE
        return when {
            config.appPackage == null -> cluster to SecondScreenBlock.NO_APP_CHOSEN
            !canStream -> SecondScreenOutput.DATA to SecondScreenBlock.NO_VIDEO
            appIsVideo && moving && !config.rearSeat -> cluster to SecondScreenBlock.VIDEO_WHILE_MOVING
            else -> SecondScreenOutput.VIDEO_APP to SecondScreenBlock.NONE
        }
    }

    /** The page [step] places after [current] in [pages] (wrapping); the first page when [current] isn't one of them. */
    fun nextPage(pages: List<ClusterPage>, current: ClusterPage, step: Int = 1): ClusterPage {
        if (pages.isEmpty()) return current
        val at = pages.indexOf(current)
        if (at < 0) return pages.first()
        return pages[Math.floorMod(at + step, pages.size)]
    }

    /**
     * The size to encode for a [screenWidth] × [screenHeight] monitor: its
     * shape, no taller than [maxHeight] nor bigger than the display's decoder
     * takes, in multiples of 16 (what hardware encoders work in; the display
     * scales the picture to the screen anyway).
     */
    fun streamSize(screenWidth: Int, screenHeight: Int, maxHeight: Int, decoderWidth: Int = 1920, decoderHeight: Int = 1080): Pair<Int, Int> {
        val w0 = screenWidth.coerceAtLeast(16)
        val h0 = screenHeight.coerceAtLeast(16)
        val scale = minOf(1.0, maxHeight.toDouble() / h0, decoderWidth.toDouble() / w0, decoderHeight.toDouble() / h0)
        fun align(v: Double) = (Math.round(v / 16.0) * 16).toInt().coerceAtLeast(16)
        return align(w0 * scale) to align(h0 * scale)
    }

    /** Lowest video bitrate adapted down to, kbit/s: still a legible cluster at 720p. */
    const val MIN_BITRATE_KBPS = 600

    /**
     * The next bitrate from the display's report of the last few seconds: a
     * quarter down when it dropped more than one frame in twenty (the Wi-Fi
     * can't keep up), a tenth back up towards [targetKbps] when it dropped none.
     */
    fun adaptBitrate(currentKbps: Int, targetKbps: Int, shown: Int, dropped: Int): Int {
        val total = shown + dropped
        return when {
            total == 0 -> currentKbps
            dropped * 20 > total -> maxOf(MIN_BITRATE_KBPS, currentKbps * 3 / 4)
            dropped == 0 && currentKbps < targetKbps -> minOf(targetKbps, currentKbps + maxOf(100, targetKbps / 10))
            else -> currentKbps
        }.coerceAtMost(targetKbps)
    }

    /** Density for the streamed screen: what a head unit of that height would use, so apps lay out alike. */
    fun streamDensity(height: Int): Int = when {
        height <= 480 -> 120
        height <= 600 -> 140
        height <= 720 -> 160
        else -> 240
    }

    /**
     * Addresses to try on the hotspot when the display didn't announce itself:
     * every other host of [address]'s network ([prefix] bits), limited to its
     * own /24 on a bigger network, skipping [skip] (this unit, the phone).
     * IPv4 as ints, most significant byte first.
     */
    fun hostsToProbe(address: Int, prefix: Int, skip: Set<Int> = emptySet()): List<Int> {
        val bits = prefix.coerceIn(24, 30)
        val mask = -1 shl (32 - bits)
        val network = address and mask
        val size = 1 shl (32 - bits)
        return (1 until size - 1).map { network or it }.filter { it != address && it !in skip }
    }
}

/** How lists are kept in preferences: comma-separated; unknown or broken entries are dropped. */
object SecondScreenCodec {
    fun encodePages(pages: List<ClusterPage>): String = pages.joinToString(",") { it.name }

    fun decodePages(raw: String?): List<ClusterPage> {
        if (raw == null) return ClusterPage.entries
        val pages = raw.split(',').mapNotNull { name -> ClusterPage.entries.firstOrNull { it.name == name.trim() } }.distinct()
        return pages.ifEmpty { ClusterPage.entries }
    }

    fun encodeKeys(keys: Set<Int>): String = keys.sorted().joinToString(",")

    fun decodeKeys(raw: String?): Set<Int> =
        raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { code -> code > 0 } }.toSet()
}

/**
 * When the display's cluster readings ([com.openauto.dash.link.ClusterState])
 * are worth sending: as soon as something shown changes, but no more than
 * every [minGapMs]; and every [heartbeatMs] regardless, which moves the
 * display's clock and tells it the link is alive. [key] is the state without
 * the clock.
 */
class ClusterThrottle(private val minGapMs: Long = 200, private val heartbeatMs: Long = 5_000) {
    private var lastKey: Any? = null
    private var lastSentAt = Long.MIN_VALUE / 2

    fun shouldSend(key: Any, now: Long): Boolean {
        val due = now - lastSentAt >= heartbeatMs || (key != lastKey && now - lastSentAt >= minGapMs)
        if (due) {
            lastKey = key
            lastSentAt = now
        }
        return due
    }

    fun reset() {
        lastKey = null
        lastSentAt = Long.MIN_VALUE / 2
    }
}

/** Apps that play video: the driver mustn't watch them while driving (see [SecondScreenBlock.VIDEO_WHILE_MOVING]). */
object VideoApps {
    /** `ApplicationInfo.CATEGORY_VIDEO`. */
    const val CATEGORY_VIDEO = 2

    /** Package names, or their prefixes: "com.netflix" covers "com.netflix.mediaclient". */
    private val KNOWN = listOf(
        "com.google.android.youtube", "com.google.android.apps.youtube", "app.revanced.android.youtube",
        "com.netflix", "com.amazon.avod", "com.disney.disneyplus", "org.videolan.vlc",
        "com.mxtech.videoplayer", "org.xbmc.kodi", "com.plexapp", "tv.twitch", "com.canal.android",
        "fr.francetv", "com.google.android.videos", "com.dailymotion", "com.hbo", "com.apple.atve"
    )

    fun isVideo(packageName: String, category: Int?): Boolean =
        category == CATEGORY_VIDEO || KNOWN.any { packageName == it || packageName.startsWith("$it.") }
}
