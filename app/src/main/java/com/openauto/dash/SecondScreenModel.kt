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

enum class ClusterBackground { BLACK, DARK, LIGHT }

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
    val mediaKeysTurnPages: Boolean = false,
    /** A sideways swipe on the launcher's bar turns the cluster's page instead of the launcher's. */
    val barSwipeTurnsPages: Boolean = false,
    /** Pages the driver laid out on the board; the others keep [ClusterLayouts.default]. */
    val layouts: Map<ClusterPage, ClusterLayout> = emptyMap(),
    /** The cluster's background; it stays as chosen, day and night, unless [followDayNight]. */
    val background: ClusterBackground = ClusterBackground.BLACK,
    /** Light by day, [background] (or dark) by night, as the dashboard's automatic look. */
    val followDayNight: Boolean = false,
    /** An alert up on the dashboard also covers the whole second screen ([SecondScreenAlerts]). */
    val alertsFullScreen: Boolean = true,
    /** The monitor is mounted upside down: the display turns every picture 180°. */
    val upsideDown: Boolean = false,
    /** The monitor's backlight by day and by night, 1..100, for a display wired to its buttons. */
    val brightnessDay: Int = 100,
    val brightnessNight: Int = 30,
    /** The last display linked could set its brightness: the levels stay offered while it is away. */
    val brightnessWired: Boolean = false
) {
    /** What [page] shows: the driver's board, or the page as it comes. */
    fun layoutFor(page: ClusterPage): ClusterLayout = layouts[page] ?: ClusterLayouts.default(page)
}

/** How a cluster page shares the screen between its widgets; [slots] is how many it holds. */
enum class ClusterArrangement(val slots: Int) {
    /** One widget, the whole screen. */
    ONE(1),

    /** A big widget, a smaller one beside it. */
    BIG_SIDE(2),

    /** A big widget, two stacked beside it. */
    BIG_STACK(3),

    /** Two widgets, half each. */
    HALVES(2),

    /** Three widgets side by side. */
    THREE(3),

    /** Four widgets, two by two. */
    GRID(4)
}

/**
 * One widget on a cluster page, in the design it is drawn in. The dashboard's
 * 3D map ([BuiltinKind.NAVMAP]) is drawn afresh, and [BuiltinKind.MAPS_INSIDE]
 * is a copy of [app]'s picture from its tile on the dashboard: both have no
 * designs ([WidgetDesign.STANDARD]).
 */
data class ClusterSlot(val kind: BuiltinKind, val design: WidgetDesign, val app: String? = null)

/** A cluster page: its [arrangement] and as many [slots] as that holds, the big one first. */
data class ClusterLayout(val arrangement: ClusterArrangement, val slots: List<ClusterSlot>)

/** The cluster's pages as they come, and the rules for laying one out on the board. */
object ClusterLayouts {

    /**
     * The widgets the cluster can show: every widget the dashboard redraws
     * from its readings. Not the live views (map, windows), the bar, the
     * favourites or the screen light: they are things to touch, and the cluster can't be touched.
     */
    val KINDS: List<BuiltinKind> = BuiltinKind.entries - setOf(
        BuiltinKind.PIP_ANCHOR, BuiltinKind.MY_CAR, BuiltinKind.DASH_BAR, BuiltinKind.QUICK_DIAL, BuiltinKind.SCREEN_LIGHT,
        BuiltinKind.RADIOS, BuiltinKind.UNIT_LIGHT, BuiltinKind.RADIO_PRESETS, BuiltinKind.HOME_WORK,
        BuiltinKind.SHARE_ETA, BuiltinKind.VOICE_NOTES, BuiltinKind.QUICK_SWITCHES,
        BuiltinKind.WIFI_NETWORKS, BuiltinKind.BT_DEVICES, BuiltinKind.PARKING_TIMER, BuiltinKind.FUEL_LOG
    )

    /** The live pictures: the 3D map and an app's copy. Drawn as they are, without designs. */
    val PICTURES = setOf(BuiltinKind.NAVMAP, BuiltinKind.MAPS_INSIDE)

    /** The app whose picture a new [BuiltinKind.MAPS_INSIDE] slot copies. */
    const val DEFAULT_APP = "com.google.android.apps.maps"

    /** The designs [kind] can wear on the cluster, the ones made for it first. Not Standard: that is the dashboard's own tile, which the cluster doesn't draw. */
    fun designsFor(kind: BuiltinKind): List<WidgetDesign> =
        if (kind in PICTURES) emptyList() else WidgetDesign.offeredFor(kind, framed = false).filter { it != WidgetDesign.STANDARD }

    /** What a new slot of [kind] wears: the first design made for it, else Hero; Standard for the pictures. */
    fun defaultDesign(kind: BuiltinKind): WidgetDesign =
        if (kind in PICTURES) WidgetDesign.STANDARD else designsFor(kind).firstOrNull { it.isSignature } ?: WidgetDesign.HERO

    /** Whether [design] is one [kind] can wear here. */
    private fun suits(kind: BuiltinKind, design: WidgetDesign): Boolean =
        if (kind in PICTURES) design == WidgetDesign.STANDARD else design in designsFor(kind)

    fun default(page: ClusterPage): ClusterLayout = when (page) {
        ClusterPage.DRIVE -> ClusterLayout(
            ClusterArrangement.BIG_STACK,
            listOf(
                ClusterSlot(BuiltinKind.SPEED_HUD, WidgetDesign.SPEED_TAPE),
                ClusterSlot(BuiltinKind.CLOCK, WidgetDesign.HERO),
                ClusterSlot(BuiltinKind.RANGE, WidgetDesign.FUEL_TANK)
            )
        )
        ClusterPage.MEDIA -> ClusterLayout(
            ClusterArrangement.BIG_STACK,
            listOf(
                ClusterSlot(BuiltinKind.MEDIA, WidgetDesign.COVER_ART),
                ClusterSlot(BuiltinKind.SPEED_HUD, WidgetDesign.HERO),
                ClusterSlot(BuiltinKind.CLOCK, WidgetDesign.HERO)
            )
        )
        ClusterPage.NAV -> ClusterLayout(
            ClusterArrangement.BIG_STACK,
            listOf(
                ClusterSlot(BuiltinKind.NAVIGATION, WidgetDesign.TURN_CARD),
                ClusterSlot(BuiltinKind.SPEED_HUD, WidgetDesign.HERO),
                ClusterSlot(BuiltinKind.CLOCK, WidgetDesign.HERO)
            )
        )
        ClusterPage.OBD -> ClusterLayout(
            ClusterArrangement.BIG_SIDE,
            listOf(
                ClusterSlot(BuiltinKind.TELEMETRY, WidgetDesign.TWIN_DIALS),
                ClusterSlot(BuiltinKind.OBD_ALL, WidgetDesign.PAPER)
            )
        )
    }

    /** Widgets for a slot a bigger arrangement adds, in order, skipping those already on the page. */
    private val FILLERS = listOf(BuiltinKind.SPEED_HUD, BuiltinKind.CLOCK, BuiltinKind.RANGE, BuiltinKind.MEDIA, BuiltinKind.NAVIGATION, BuiltinKind.TELEMETRY)

    /**
     * [layout] in [arrangement]: the widgets it had keep their place, the
     * ones that no longer fit are dropped, new slots get the page's own
     * widgets first, then common ones not on the page yet.
     */
    fun arrange(layout: ClusterLayout, arrangement: ClusterArrangement, page: ClusterPage): ClusterLayout {
        val slots = layout.slots.take(arrangement.slots).toMutableList()
        val candidates = default(page).slots + FILLERS.map { ClusterSlot(it, defaultDesign(it)) }
        for (c in candidates) {
            if (slots.size >= arrangement.slots) break
            if (slots.none { it.kind == c.kind }) slots += c
        }
        while (slots.size < arrangement.slots) slots += ClusterSlot(BuiltinKind.CLOCK, WidgetDesign.HERO)
        return ClusterLayout(arrangement, slots)
    }

    /**
     * [layout] with slot [index] showing [kind] in [design], or in its first
     * design when [design] doesn't suit it; [app] is the app an app picture
     * copies ([DEFAULT_APP] when not given).
     */
    fun withSlot(layout: ClusterLayout, index: Int, kind: BuiltinKind, design: WidgetDesign? = null, app: String? = null): ClusterLayout {
        if (index !in layout.slots.indices) return layout
        val wear = design?.takeIf { suits(kind, it) } ?: defaultDesign(kind)
        val copied = if (kind == BuiltinKind.MAPS_INSIDE) app ?: DEFAULT_APP else null
        return layout.copy(slots = layout.slots.toMutableList().also { it[index] = ClusterSlot(kind, wear, copied) })
    }

    /** [layout] made whole: as many slots as its arrangement holds, widgets the cluster shows, designs that suit them. */
    fun sanitize(layout: ClusterLayout, page: ClusterPage): ClusterLayout {
        val slots = layout.slots.filter { it.kind in KINDS }.map {
            val app = if (it.kind == BuiltinKind.MAPS_INSIDE) it.app?.takeIf(String::isNotBlank) ?: DEFAULT_APP else null
            (if (suits(it.kind, it.design)) it else it.copy(design = defaultDesign(it.kind))).copy(app = app)
        }
        return arrange(ClusterLayout(layout.arrangement, slots), layout.arrangement, page)
    }
}

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
enum class SecondScreenBlock { NONE, NO_APP_CHOSEN, NO_VIDEO, VIDEO_WHILE_MOVING, APPS_NOT_SUPPORTED }

object SecondScreenRules {

    /** The background the cluster shows now: the chosen one, or light by [day] when it follows day and night. */
    fun background(config: SecondScreenConfig, day: Boolean): ClusterBackground = when {
        !config.followDayNight -> config.background
        day -> ClusterBackground.LIGHT
        config.background == ClusterBackground.LIGHT -> ClusterBackground.DARK
        else -> config.background
    }

    /** The monitor's backlight now: the day level or the night one. */
    fun brightness(config: SecondScreenConfig, day: Boolean): Int = if (day) config.brightnessDay else config.brightnessNight

    val STREAM_HEIGHTS = listOf(480, 720, 1080)

    /**
     * An app goes onto the second screen with `am display move-stack`, through the
     * head unit's root shell. Android 12 dropped stacks and that command with them.
     */
    fun appsMovable(sdk: Int): Boolean = sdk <= 30

    /**
     * What to send, and why the choice isn't what [config] asks for, if it isn't.
     * [canStream]: the display decodes H.264 and the encoder here is available.
     * [appIsVideo]: the chosen app plays video (films, YouTube), which the
     * driver must not see while [moving] unless the monitor is for the back seats.
     * [canMoveApps]: this Android can move an app's window onto the streamed display ([appsMovable]).
     */
    fun output(
        config: SecondScreenConfig,
        connected: Boolean,
        canStream: Boolean,
        moving: Boolean,
        appIsVideo: Boolean,
        canMoveApps: Boolean = true
    ): Pair<SecondScreenOutput, SecondScreenBlock> {
        if (config.mode == SecondScreenMode.OFF || !connected) return SecondScreenOutput.NONE to SecondScreenBlock.NONE
        val cluster = if (config.video && canStream) SecondScreenOutput.VIDEO_CLUSTER else SecondScreenOutput.DATA
        if (config.mode == SecondScreenMode.CLUSTER) return cluster to SecondScreenBlock.NONE
        return when {
            !canMoveApps -> cluster to SecondScreenBlock.APPS_NOT_SUPPORTED
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

    /** How long the stream must play clean at its held rate before it tries a little more, ms. */
    const val BITRATE_PROBE_AFTER_MS = 60_000L

    /**
     * Where the stream's bitrate stands: [kbps] the encoder runs at, [ceilingKbps]
     * the rate that last lost frames (null when none has, or it was lifted past
     * the target), [cleanSinceMs] since when every report came back clean.
     */
    data class BitrateState(val kbps: Int, val ceilingKbps: Int? = null, val cleanSinceMs: Long? = null) {
        /** Where a new stream starts: the target, or just under a known ceiling; the ceiling is kept (same Wi-Fi). */
        fun restart(targetKbps: Int): BitrateState =
            BitrateState(minOf(targetKbps, holdKbps(ceilingKbps, targetKbps)), ceilingKbps.takeIf { holdKbps(it, targetKbps) < targetKbps })
    }

    /** Just under [ceilingKbps] (a tenth below), never under the floor nor over [targetKbps]. */
    fun holdKbps(ceilingKbps: Int?, targetKbps: Int): Int =
        if (ceilingKbps == null) targetKbps else (ceilingKbps * 9 / 10).coerceIn(MIN_BITRATE_KBPS, maxOf(MIN_BITRATE_KBPS, targetKbps))

    /**
     * The next bitrate from the display's report of the last few seconds, so a
     * weak 2.4 GHz link settles instead of sawing. More than one frame in twenty
     * dropped: a quarter down, and the rate that failed becomes the ceiling. A
     * clean report: a tenth of [targetKbps] back up, but only to just under the
     * ceiling. Only after [BITRATE_PROBE_AFTER_MS] of clean play there does the
     * ceiling lift, by a twentieth of the target, until it clears the target.
     * A frame or two lost holds the rate and starts the clean stretch over.
     */
    fun adaptBitrate(state: BitrateState, targetKbps: Int, shown: Int, dropped: Int, nowMs: Long): BitrateState {
        val total = shown + dropped
        val current = state.kbps.coerceAtMost(targetKbps)
        return when {
            total == 0 -> state.copy(kbps = current)
            dropped * 20 > total -> BitrateState(maxOf(MIN_BITRATE_KBPS, current * 3 / 4), ceilingKbps = current)
            dropped > 0 -> state.copy(kbps = current, cleanSinceMs = null)
            else -> {
                val since = state.cleanSinceMs ?: nowMs
                var ceiling = state.ceilingKbps
                var cleanSince: Long? = since
                val hold = holdKbps(ceiling, targetKbps)
                if (ceiling != null && current >= hold && nowMs - since >= BITRATE_PROBE_AFTER_MS) {
                    ceiling += maxOf(50, targetKbps / 20)
                    if (holdKbps(ceiling, targetKbps) >= targetKbps) ceiling = null
                    cleanSince = nowMs
                }
                val top = holdKbps(ceiling, targetKbps)
                val next = if (current < top) minOf(top, current + maxOf(100, targetKbps / 10)) else current
                BitrateState(next, ceiling, cleanSince)
            }
        }
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

    /** Board pages as "DRIVE=BIG_STACK:SPEED_HUD/SPEED_TAPE,MAPS_INSIDE/STANDARD/com.google.android.apps.maps;MEDIA=...". */
    fun encodeLayouts(layouts: Map<ClusterPage, ClusterLayout>): String =
        layouts.entries.sortedBy { it.key.ordinal }.joinToString(";") { (page, layout) ->
            page.name + "=" + layout.arrangement.name + ":" + layout.slots.joinToString(",") { s ->
                s.kind.name + "/" + s.design.name + (s.app?.let { "/$it" } ?: "")
            }
        }

    /** A page that doesn't read back (unknown page or arrangement) keeps its original look; a widget that doesn't is replaced. */
    fun decodeLayouts(raw: String?): Map<ClusterPage, ClusterLayout> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<ClusterPage, ClusterLayout>()
        for (entry in raw.split(';')) {
            val page = ClusterPage.entries.firstOrNull { it.name == entry.substringBefore('=').trim() } ?: continue
            val body = entry.substringAfter('=', "")
            val arrangement = ClusterArrangement.entries.firstOrNull { it.name == body.substringBefore(':').trim() } ?: continue
            val slots = body.substringAfter(':', "").split(',').mapNotNull { s ->
                val parts = s.split('/').map { it.trim() }
                val kind = BuiltinKind.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
                ClusterSlot(kind, WidgetDesign.fromName(parts.getOrNull(1)), parts.getOrNull(2))
            }
            out[page] = ClusterLayouts.sanitize(ClusterLayout(arrangement, slots), page)
        }
        return out
    }
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
