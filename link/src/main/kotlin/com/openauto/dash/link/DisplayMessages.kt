package com.openauto.dash.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer

/*
 * The second screen: a Raspberry Pi wired to a monitor, on the phone's hotspot
 * like the head unit. The head unit dials it on [DISPLAY_PORT] with the pairing
 * the Pi showed as a QR code (see PairingOffer.Kind.DISPLAY) and the same
 * SecureChannel as the phone link, the Pi being the server.
 *
 * Mostly the head unit sends the picture itself: its own virtual screen,
 * encoded to H.264, one access unit per raw frame (see [VideoPacket]), which
 * the Pi decodes in hardware. When it can't (no encoder free, a weak link) it
 * sends [ClusterState] instead and the Pi draws a plain cluster from it.
 */

/** TCP port a display listens on. */
const val DISPLAY_PORT = 47811

/** DNS-SD type a display announces itself under (avahi on the Pi, NsdManager on the head unit). */
const val DISPLAY_SERVICE_TYPE = "_dashwheel-display._tcp"

/**
 * UDP port a display calls out on, once a second, while no head unit is linked:
 * found within a second of its start, sooner than DNS-SD, and also on a phone's
 * own hotspot, where Android neither scans nor resolves well. Only the pairing
 * id goes out; the link itself is authenticated as ever.
 */
const val DISPLAY_BEACON_PORT = 47812

/** A beacon's text: this, then the display's pairing id. */
const val DISPLAY_BEACON_PREFIX = "dashwheel-display "

/**
 * The display protocol as this side knows it ([DisplayHello.protocol]). Both
 * halves ship apart (the Pi by card, the head unit by OTA), so each one reads
 * the other's and says so in its settings or its log when they differ. Every
 * change is backward compatible (new fields with defaults); the number only
 * tells which fields the other side will act on.
 *
 * 1: the first release.
 * 2: [DisplayTime], [DisplayStats.droppedLate] and the board's health, the
 *    words in [DisplayWords.labels], [ClusterState.maneuver] and [ClusterState.layout];
 *    then [ClusterState.position] and [ClusterRoute] for the map the display draws itself.
 */
const val DISPLAY_PROTOCOL = 2

/** Pi → head unit, first thing on the channel: the screen it drives. */
@Serializable
@SerialName("display_hello")
data class DisplayHello(
    val name: String,
    val appVersion: String,
    /** The monitor's mode, in pixels. */
    val width: Int,
    val height: Int,
    val refreshHz: Int = 60,
    /** Codecs it decodes in hardware, e.g. "h264". Empty: it can only draw [ClusterState]. */
    val decoders: List<String> = listOf(CODEC_H264),
    /** Largest picture its decoder takes. */
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    /** Percent of each edge a TV-style (composite) monitor may crop. */
    val overscanPct: Int = 0,
    /** Board, e.g. "Raspberry Pi 3 Model B Rev 1.2". */
    val model: String = "",
    /** Wired to the monitor's brightness buttons: it follows [DisplayBrightness]. */
    val brightness: Boolean = false,
    /**
     * The display protocol the display speaks ([DISPLAY_PROTOCOL]). A display
     * passes its own; the default is what an older display, which never sent
     * the field, spoke (a default is not written out, so it can't be the current number).
     */
    val protocol: Int = 1
) : LinkMessage

/**
 * Head unit → Pi, right after [Hello] and with every ping: the head unit's
 * clock and time zone. The Pi has no clock of its own (no battery), its card
 * knows no zone, and it may have no network time: this sets both. [clock] is
 * epoch milliseconds, [zoneOffsetMin] the zone's offset from UTC at that
 * moment, [zone] its name when the head unit has one ("Europe/Paris").
 */
@Serializable
@SerialName("display_time")
data class DisplayTime(val clock: Long, val zoneOffsetMin: Int, val zone: String = "") : LinkMessage

/**
 * Head unit → Pi: what to show. The Pi shows its own idle screen for [Mode.IDLE].
 * [rotate180]: the monitor is mounted upside down; the Pi turns every picture,
 * and keeps the choice for its next start (logo and idle screen included).
 */
@Serializable
@SerialName("display_mode")
data class DisplayMode(val mode: Mode, val rotate180: Boolean = false) : LinkMessage {
    @Serializable
    enum class Mode { IDLE, VIDEO, DATA }
}

/**
 * Head unit → Pi: the monitor's backlight, 1..100, as its own brightness
 * setting counts. Sent when the link comes up, at each day/night switch and
 * when the driver changes a level. Only a Pi wired to the monitor's buttons
 * ([DisplayHello.brightness]) acts on it.
 */
@Serializable
@SerialName("display_brightness")
data class DisplayBrightness(val level: Int) : LinkMessage {
    companion object {
        const val MIN = 1
        const val MAX = 100
    }
}

/**
 * Head unit → Pi: the lines the Pi writes itself, in the head unit's language
 * ([language], e.g. "fr"). Sent when the link comes up and when the language
 * changes; the Pi keeps them for its next start, so the screens it shows before
 * a head unit links read the same. A line left out stays in English (an older
 * head unit sends none).
 */
@Serializable
@SerialName("display_words")
data class DisplayWords(
    val language: String = "",
    /** The idle screen's line while no head unit is linked, e.g. "Waiting for Dashwheel…". */
    val waiting: String? = null,
    /** In place of the head unit's picture or readings once it went silent. */
    val noSignal: String? = null,
    /** The idle screen's line while linked with nothing to show. */
    val connected: String? = null,
    /** Under the pairing code. */
    val scanApp: String? = null,
    /**
     * The words around the cluster's figures, sent here once per language
     * rather than with every reading; an older head unit sends them in
     * [ClusterState.labels] instead.
     */
    val labels: ClusterState.Labels? = null
) : LinkMessage

/**
 * Head unit → Pi, before the first [VideoPacket] of a stream and whenever the
 * encoder is restarted: the stream's shape and its parameter sets
 * ([csd], Annex-B SPS + PPS, base64), which the Pi feeds its decoder first.
 */
@Serializable
@SerialName("video_config")
data class VideoConfig(
    val codec: String = CODEC_H264,
    val width: Int,
    val height: Int,
    val fps: Int,
    val csd: String
) : LinkMessage

/**
 * Pi → head unit, for each video frame as it arrives: its [VideoPacket.ptsUs].
 * The head unit sends no further ahead of the last one confirmed than
 * [VideoWindow] allows, so the network's own buffers (a phone hotspot
 * relaying a film) can't hold seconds of video. A head unit that doesn't
 * know it ignores it, and sends as before.
 */
@Serializable
@SerialName("video_ack")
data class VideoAck(val ptsUs: Long) : LinkMessage

/**
 * The head unit's side of [VideoAck]: how far ahead of the display it may be.
 * Until the display has confirmed a first frame (an older one never does),
 * nothing is held back.
 */
class VideoWindow(private val maxAheadUs: Long = DEFAULT_MAX_AHEAD_US) {
    private var sent = Long.MIN_VALUE
    private var acked = Long.MIN_VALUE
    private var confirming = false

    /** A new link: nothing in flight, the display not yet known to confirm. */
    @Synchronized
    fun reset() {
        sent = Long.MIN_VALUE
        acked = Long.MIN_VALUE
        confirming = false
    }

    /** Whether one more frame may go now. */
    @Synchronized
    fun mayQueue(): Boolean = !confirming || sent == Long.MIN_VALUE || sent - acked <= maxAheadUs

    @Synchronized
    fun queued(ptsUs: Long) {
        sent = ptsUs
    }

    @Synchronized
    fun acked(ptsUs: Long) {
        confirming = true
        if (ptsUs > acked || acked - ptsUs > RESTART_US) acked = ptsUs
    }

    companion object {
        /** A third of a second of video unconfirmed at most. */
        const val DEFAULT_MAX_AHEAD_US = 300_000L
        /** A confirmed frame this far behind the last one is a new stream (the encoder started again). */
        private const val RESTART_US = 10_000_000L
    }
}

/** Pi → head unit. */
@Serializable
@SerialName("display_cmd")
data class DisplayCommand(val action: Action) : LinkMessage {
    @Serializable
    enum class Action {
        /** The decoder (re)started or lost a frame: send a key frame. */
        KEYFRAME_PLEASE
    }
}

/**
 * Pi → head unit, every few seconds: how well it keeps up with the video, and
 * how the board itself is doing. Sent in every mode (a heartbeat the head unit
 * hears sooner than a missed pong); the frame counts are zero without video.
 */
@Serializable
@SerialName("display_stats")
data class DisplayStats(
    val framesShown: Int,
    /** Every frame not shown, whatever the reason (an older head unit reads only this). */
    val framesDropped: Int,
    /** Bytes of video received in the period, over its length. */
    val kbps: Int,
    val periodMs: Long,
    /**
     * Of [framesDropped], those the decoder had no room for: the display was
     * too slow, not the link. Lowering the bitrate does nothing for these;
     * fewer pixels or frames does.
     */
    val droppedLate: Int = 0,
    /**
     * The board's throttle flags as `vcgencmd get_throttled` gives them:
     * [THROTTLE_UNDER_VOLTAGE], [THROTTLE_FREQ_CAPPED], [THROTTLE_THROTTLED],
     * [THROTTLE_SOFT_TEMP] now; the same bits shifted by 16 for "since it started". 0 when not read.
     */
    val throttled: Int = 0,
    /** The board's temperature, °C; null when not read. */
    val tempC: Int? = null
) : LinkMessage {
    companion object {
        const val THROTTLE_UNDER_VOLTAGE = 0x1
        const val THROTTLE_FREQ_CAPPED = 0x2
        const val THROTTLE_THROTTLED = 0x4
        const val THROTTLE_SOFT_TEMP = 0x8

        /** The flags that hold now, without the "since it started" ones. */
        const val THROTTLE_NOW = 0xF
    }
}

/**
 * Phone → head unit: the display's pairing code, scanned by the companion app
 * ([PairingOffer.toUri], kind DISPLAY). The head unit keeps it and dials the display.
 */
@Serializable
@SerialName("display_pair")
data class DisplayPair(val uri: String) : LinkMessage

/**
 * Head unit → Pi, in [DisplayMode.Mode.DATA]: what the Pi's own cluster draws.
 * Sent when something shown changed, a few times a second at most. Null means
 * "not known" (no OBD link, nothing playing...), never zero.
 */
@Serializable
@SerialName("cluster_state")
data class ClusterState(
    /** Wall clock of the head unit: the Pi has no clock of its own and may be offline. */
    val clock: Long,
    val page: String = "DRIVE",
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val coolantC: Int? = null,
    val fuelPct: Int? = null,
    val rangeKm: Int? = null,
    /** Doors / boot / bonnet open, by name as the head unit shows them. */
    val open: List<String> = emptyList(),
    val obdConnected: Boolean = false,
    /** Show miles and mph. */
    val imperial: Boolean = false,
    /** The speed's unit as the head unit writes it, e.g. "km/h"; null from an older head unit. */
    val speedUnit: String? = null,
    /** Show the coolant in °F. */
    val fahrenheit: Boolean = false,
    /** Show the clock as "2:05 PM" rather than "14:05". */
    val clock12: Boolean = false,
    val night: Boolean = false,
    /** The dashboard's accent colour, ARGB. */
    val accent: Long = 0xFF4FC3F7,
    val media: Media? = null,
    val nav: Nav? = null,
    /** The cluster's own words in the head unit's language; null from an older head unit. */
    val labels: Labels? = null,
    /** An alert up on the head unit, to show full screen over the page; null when none. */
    val alert: Alert? = null,
    /** How the display draws its pages, e.g. "DIALS"; null from an older head unit (the display's default). */
    val design: String? = null,
    /**
     * The Measures page's readings, written as the head unit shows them (its
     * language and units); sent with that page only, empty without an OBD
     * adapter and from an older head unit.
     */
    val measures: List<Measure> = emptyList(),
    /**
     * The next turn as the head unit's navigation knows it, one of [MANEUVERS];
     * null when it only has words (a notification), and the display reads them.
     */
    val maneuver: String? = null,
    /**
     * The page as laid out on the head unit's board, so the display draws the
     * same widgets in the same places; null from an older head unit, or for a
     * page whose widgets the display has no readings for: it then draws its own page.
     */
    val layout: Layout? = null,
    /** Where the car is, for the map the display draws itself ([ClusterFace.MAP]); null without a fix. */
    val position: Position? = null,
    /** How that map is seen; null from an older head unit: the display's default ([MapView] as it comes). */
    val mapView: MapView? = null
) : LinkMessage {
    /**
     * How the display's own map is seen: tilted and turned with the car, the
     * camera behind and above it, or flat with north up. [tilt] and
     * [distance] are names, so a display that doesn't know one keeps its default.
     */
    @Serializable
    data class MapView(
        val tilted: Boolean = true,
        /** [LOW], [MEDIUM] or [HIGH]: how far the camera looks toward the horizon. */
        val tilt: String = MEDIUM,
        /** [CLOSE], [NORMAL] or [FAR]: how much of the road the camera takes in around the car. */
        val distance: String = CLOSE,
        /** The buildings stand up in 3D, where the display has their shapes. */
        val buildings: Boolean = true
    ) {
        companion object {
            const val LOW = "LOW"
            const val MEDIUM = "MEDIUM"
            const val HIGH = "HIGH"
            const val CLOSE = "CLOSE"
            const val NORMAL = "NORMAL"
            const val FAR = "FAR"
        }
    }

    /** A GPS fix: degrees to five decimals (about a metre), the heading in degrees from north when the car moves. */
    @Serializable
    data class Position(val lat: Double, val lon: Double, val headingDeg: Int? = null)

    /**
     * A page's arrangement ([ClusterArrangement] names: ONE, BIG_SIDE, BIG_STACK,
     * HALVES, THREE, GRID) and what each of its slots shows, the big slot first,
     * as [ClusterFace] names. A face the display doesn't know is left empty.
     */
    @Serializable
    data class Layout(val arrangement: String, val faces: List<String>)

    /** One reading of the Measures page, e.g. "Coolant" and "87 °C". */
    @Serializable
    data class Measure(val label: String, val value: String)

    /** What the head unit is alerting about, in its language: an older display ignores it. */
    @Serializable
    data class Alert(
        /** e.g. "Doors". */
        val title: String,
        /** e.g. "Front left, Tailgate"; may be empty. */
        val detail: String = "",
        /** [INFO], [WARN] or [CRITICAL]: the colour it is drawn in. */
        val level: String = INFO
    ) {
        companion object {
            const val INFO = "info"
            const val WARN = "warn"
            const val CRITICAL = "critical"
        }
    }

    /**
     * What the Pi prints around the figures, as the head unit would write it.
     * Each one may be missing: the Pi then keeps its own English.
     */
    @Serializable
    data class Labels(
        /** The open doors' line, with %s where their names go, e.g. "Open: %s". */
        val open: String? = null,
        val nothingPlaying: String? = null,
        val noRoute: String? = null,
        /** The arrival line, with %s where the time goes, e.g. "Arrive %s". */
        val arrive: String? = null,
        val rpm: String? = null,
        val coolant: String? = null,
        val fuel: String? = null,
        /** Under the range, its unit included, e.g. "km range". */
        val range: String? = null,
        /** The Measures page without readings, e.g. "Waiting for the OBD adapter…". */
        val measuresNone: String? = null,
        /** The map without a fix yet, e.g. "Waiting for the GPS…". */
        val noPosition: String? = null,
        /** The map without tiles to draw, e.g. "No map tiles: set tile_url on the card". */
        val noTiles: String? = null
    )

    @Serializable
    data class Media(
        val title: String,
        val artist: String = "",
        val app: String = "",
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0
    )

    @Serializable
    data class Nav(
        /** What the navigation app says to do next, e.g. "Turn right". */
        val instruction: String,
        /** e.g. "300 m". */
        val distance: String = "",
        val street: String = "",
        /** e.g. "18:42". */
        val eta: String = ""
    )
}

const val CODEC_H264 = "h264"

/**
 * Head unit → Pi: the route the in-app navigation follows, as lat, lon pairs
 * flattened ([points] = lat0, lon0, lat1, lon1…), for the map the display
 * draws itself. Sent when the route changes and when the link comes up; empty
 * when there is no route (or the navigation is another app's, which keeps its route to itself).
 */
@Serializable
@SerialName("cluster_route")
data class ClusterRoute(val points: List<Double> = emptyList()) : LinkMessage {
    /** The route as (lat, lon) pairs; an odd trailing value is dropped. */
    fun pairs(): List<Pair<Double, Double>> = (0 until points.size / 2).map { points[2 * it] to points[2 * it + 1] }

    companion object {
        /** At most this many points go over the link: a long route is thinned to it first. */
        const val MAX_POINTS = 2_000
    }
}

/** The turns [ClusterState.maneuver] can name. */
val MANEUVERS = listOf("LEFT", "RIGHT", "STRAIGHT", "UTURN", "ROUNDABOUT", "ARRIVE")

/**
 * What one slot of a board page shows on a display that draws itself
 * ([ClusterState.Layout.faces]): each one is drawn from the readings the
 * display already gets, so the board and the display stay in step.
 */
object ClusterFace {
    const val SPEED = "SPEED"
    const val CLOCK = "CLOCK"
    const val FUEL = "FUEL"
    const val RANGE = "RANGE"
    const val RPM = "RPM"
    const val COOLANT = "COOLANT"
    /** Revs and coolant together, as the dashboard's engine gauges. */
    const val TELEMETRY = "TELEMETRY"
    const val MEDIA = "MEDIA"
    const val NAV = "NAV"
    const val MEASURES = "MEASURES"
    const val DOORS = "DOORS"
    /** The display's own map: its tiles, the car from [ClusterState.position], the route from [ClusterRoute]. */
    const val MAP = "MAP"

    val ALL = listOf(SPEED, CLOCK, FUEL, RANGE, RPM, COOLANT, TELEMETRY, MEDIA, NAV, MEASURES, DOORS, MAP)
}

/**
 * One encoded video access unit, carried as a raw frame
 * ([LinkSession.sendBinary]): a kind byte, a flags byte, the presentation time
 * in microseconds, then the Annex-B bytes.
 */
class VideoPacket(val keyFrame: Boolean, val ptsUs: Long, val data: ByteArray) {

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER + data.size)
        .put(KIND_VIDEO)
        .put(if (keyFrame) FLAG_KEY else 0)
        .putLong(ptsUs)
        .put(data)
        .array()

    companion object {
        private const val KIND_VIDEO: Byte = 1
        private const val FLAG_KEY: Byte = 1
        const val HEADER = 10

        /** The largest access unit that fits in one frame. */
        const val MAX_DATA = LinkSession.MAX_MESSAGE - 1 - HEADER

        /** Null for raw bytes that aren't a video packet this side knows. */
        fun decode(bytes: ByteArray): VideoPacket? {
            if (bytes.size < HEADER || bytes[0] != KIND_VIDEO) return null
            val buffer = ByteBuffer.wrap(bytes)
            buffer.get()
            val keyFrame = (buffer.get().toInt() and FLAG_KEY.toInt()) != 0
            val pts = buffer.long
            return VideoPacket(keyFrame, pts, bytes.copyOfRange(HEADER, bytes.size))
        }
    }
}
