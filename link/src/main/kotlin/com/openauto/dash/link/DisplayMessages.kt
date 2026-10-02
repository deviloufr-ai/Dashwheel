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
    val protocol: Int = PROTOCOL_VERSION
) : LinkMessage

/** Head unit → Pi: what to show. The Pi shows its own idle screen for [Mode.IDLE]. */
@Serializable
@SerialName("display_mode")
data class DisplayMode(val mode: Mode) : LinkMessage {
    @Serializable
    enum class Mode { IDLE, VIDEO, DATA }
}

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

/** Pi → head unit, every few seconds while it plays video: how well it keeps up. */
@Serializable
@SerialName("display_stats")
data class DisplayStats(
    val framesShown: Int,
    val framesDropped: Int,
    /** Bytes of video received in the period, over its length. */
    val kbps: Int,
    val periodMs: Long
) : LinkMessage

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
    val night: Boolean = false,
    /** The dashboard's accent colour, ARGB. */
    val accent: Long = 0xFF4FC3F7,
    val media: Media? = null,
    val nav: Nav? = null
) : LinkMessage {
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
