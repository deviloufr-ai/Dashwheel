package com.openauto.dash.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * What the phone and the head unit say to each other once the channel is up.
 * One JSON object per encrypted frame, told apart by its "t" field. Both
 * sides skip a message they don't know, so a newer companion app still talks
 * to an older launcher and the other way round.
 */

/** Bumped when a change can't be skipped over by the older side. */
const val PROTOCOL_VERSION = 1

/** TCP port the companion app listens on, on the phone's hotspot address. */
const val LINK_PORT = 47810

@Serializable
sealed interface LinkMessage

/** First message each way once the channel is up: who is on the other end. */
@Serializable
@SerialName("hello")
data class Hello(
    val deviceName: String,
    val appVersion: String,
    val protocol: Int = PROTOCOL_VERSION
) : LinkMessage

/** Keep-alive; answered with [Pong]. A silent link is dropped and redialled. */
@Serializable
@SerialName("ping")
data object Ping : LinkMessage

@Serializable
@SerialName("pong")
data object Pong : LinkMessage

/**
 * Head unit → phone: where the car is, so the companion app can lead back to
 * it. Sent when the car comes to a stop (the last stop before it is switched
 * off is where it was parked), when the spot is saved on the dashboard's
 * Parking tile ([saved]), and when the link comes up. [at] is when the car was
 * there; the phone keeps the newest.
 */
@Serializable
@SerialName("car_location")
data class CarLocation(val lat: Double, val lng: Double, val at: Long, val saved: Boolean = false) : LinkMessage

/**
 * Head unit → phone: the parking paid until [endsAt] (0: the timer was
 * stopped), for the phone to remind the driver [warnMs] before, wherever they are.
 */
@Serializable
@SerialName("parking_timer")
data class ParkingTimer(val endsAt: Long, val warnMs: Long = 10 * 60_000L, val label: String = "") : LinkMessage

/**
 * Head unit → phone: the drives it logged (newest first; see [DriveSummary]),
 * sent when the link comes up, with the drive under way first when there is
 * one. The phone keeps them by their start time, so a drive already known is
 * replaced by its newer figures.
 */
@Serializable
@SerialName("drive_sync")
data class DriveSync(val drives: List<DriveSummary>) : LinkMessage {
    companion object {
        /** More than the phone shows anyway; the log on the head unit is capped lower. */
        const val MAX_ITEMS = 50

        /** A sync of [drives] (newest first) that always fits in one frame. */
        fun of(drives: List<DriveSummary>, maxBytes: Int = LinkSession.MAX_MESSAGE): DriveSync {
            var items = drives.take(MAX_ITEMS)
            while (items.isNotEmpty() && LinkCodec.encode(DriveSync(items)).size > maxBytes) items = items.dropLast(1)
            return DriveSync(items)
        }
    }
}

/**
 * Head unit → phone: a drive's latest figures. Sent every so often for the
 * drive under way ([DriveSummary.ongoing]), so the phone has them even when the
 * key turned off cuts the link before the drive is closed, then once more when
 * it ends. The phone keeps one entry per start time.
 */
@Serializable
@SerialName("drive")
data class DriveReport(val drive: DriveSummary) : LinkMessage

/** Phone → head unit: every notification currently shown, sent after [Hello]. */
@Serializable
@SerialName("notif_sync")
data class NotificationSync(val notifications: List<PhoneNotification>) : LinkMessage {
    companion object {
        /** The head unit's card keeps 20 notifications in all: more would only be dropped there. */
        const val MAX_ITEMS = 20

        /**
         * A sync of [notifications] (newest first) that always fits in one frame:
         * at most [MAX_ITEMS], each app's icon only on its first notification
         * (the head unit keeps one icon per app, taken from the first it reads),
         * and the oldest left out until it fits in [maxBytes].
         */
        fun of(notifications: List<PhoneNotification>, maxBytes: Int = LinkSession.MAX_MESSAGE): NotificationSync {
            val withIcon = HashSet<String>()
            var items = notifications.take(MAX_ITEMS).map { n ->
                if (n.iconPng == null || withIcon.add(n.packageName)) n else n.copy(iconPng = null)
            }
            // Dropping from the end never drops an app's icon while one of its notifications stays.
            while (items.isNotEmpty() && LinkCodec.encode(NotificationSync(items)).size > maxBytes) items = items.dropLast(1)
            return NotificationSync(items)
        }
    }
}

/** Phone → head unit: a notification appeared or changed. */
@Serializable
@SerialName("notif")
data class NotificationPosted(val notification: PhoneNotification) : LinkMessage

/** Phone → head unit: a notification went away on the phone. */
@Serializable
@SerialName("notif_removed")
data class NotificationRemoved(val key: String) : LinkMessage

/** Head unit → phone: answer a conversation through the app's own reply action. */
@Serializable
@SerialName("reply")
data class Reply(val key: String, val text: String) : LinkMessage

/** Head unit → phone: mark a conversation as read, as the app's own action does. */
@Serializable
@SerialName("mark_read")
data class MarkRead(val key: String) : LinkMessage

/** Head unit → phone: clear a notification from the phone's shade. */
@Serializable
@SerialName("dismiss")
data class Dismiss(val key: String) : LinkMessage

/** Phone → head unit: how a [Reply], [MarkRead] or [Dismiss] went. */
@Serializable
@SerialName("result")
data class ActionResult(val key: String, val action: Action, val ok: Boolean) : LinkMessage {
    @Serializable
    enum class Action { REPLY, MARK_READ, DISMISS }
}

/**
 * Phone → head unit: the phone call, each time it changes (and once when the
 * link comes up). A phone call, or a call in an app (WhatsApp, Signal,
 * Telegram…, [app]) seen through its call notification.
 */
@Serializable
@SerialName("call")
data class CallState(
    val phase: Phase,
    /** The other party's number, when the phone can read it. */
    val number: String? = null,
    /** Their name from the phone's contacts (or as the calling app names them). */
    val name: String? = null,
    /** Their contact photo as a small PNG, base64. */
    val photoPng: String? = null,
    /** How long the call has been answered, when sent (clocks may differ between the two). */
    val activeForMs: Long = 0,
    /** False when the companion isn't allowed to answer / hang up: the head unit only shows the call. */
    val canControl: Boolean = true,
    /** The app the call is in ("WhatsApp"…); null for a phone call. */
    val app: String? = null,
    /** That app's package, for a head unit that runs the same app (a linked WhatsApp) to leave the call to it. */
    val packageName: String? = null
) : LinkMessage {
    @Serializable
    enum class Phase { IDLE, RINGING, ACTIVE }
}

/**
 * Head unit → phone: answer, decline or end the call, or ([Action.DIAL]) call
 * [number]. A companion older than DIAL can't read the action and skips the
 * whole message, so the head unit only asks one that sent [PhoneContacts]
 * (the two came together); the phone answers a DIAL with [DialResult].
 */
@Serializable
@SerialName("call_cmd")
data class CallCommand(val action: Action, val number: String? = null) : LinkMessage {
    @Serializable
    enum class Action { ANSWER, DECLINE, HANG_UP, DIAL }
}

/** Phone → head unit: whether the phone placed the call a [CallCommand.Action.DIAL] asked for. */
@Serializable
@SerialName("dialed")
data class DialResult(val number: String, val placed: Boolean) : LinkMessage

/**
 * Phone → head unit: the phone's starred contacts and its last calls, for the
 * Quick dial tile. Sent when the link comes up and whenever the contacts or the
 * call log change on the phone.
 */
@Serializable
@SerialName("contacts")
data class PhoneContacts(
    val favourites: List<PhoneFavourite>,
    /** Newest first. */
    val recentCalls: List<RecentCall> = emptyList()
) : LinkMessage {
    companion object {
        /** A tile's width of avatars. */
        const val MAX_FAVOURITES = 8
        const val MAX_CALLS = 5
        /** Photos are small, but ten of them add up: the list stays well under a frame. */
        const val MAX_BYTES = 256 * 1024

        /** At most [MAX_FAVOURITES] and [MAX_CALLS], photos dropped from the last favourite up until it fits in [maxBytes]. */
        fun of(favourites: List<PhoneFavourite>, recentCalls: List<RecentCall>, maxBytes: Int = MAX_BYTES): PhoneContacts {
            val calls = recentCalls.take(MAX_CALLS)
            val items = favourites.take(MAX_FAVOURITES).toMutableList()
            var i = items.lastIndex
            while (i >= 0 && LinkCodec.encode(PhoneContacts(items, calls)).size > maxBytes) {
                items[i] = items[i].copy(photoPng = null)
                i--
            }
            return PhoneContacts(items, calls)
        }
    }
}

/** A starred contact: [photoPng] is a small PNG, base64. */
@Serializable
data class PhoneFavourite(val name: String, val number: String, val photoPng: String? = null)

/** A call from the phone's call log. [at] is when it began, on the phone's clock. */
@Serializable
data class RecentCall(val number: String, val name: String? = null, val type: Type, val at: Long) {
    @Serializable
    enum class Type { INCOMING, OUTGOING, MISSED }
}

/**
 * Phone → head unit: the phone's calendar for the next [AgendaSync.HOURS]
 * hours, soonest first. Sent when the link comes up and whenever the calendar
 * changes; not sent at all while the phone may not read its calendar.
 */
@Serializable
@SerialName("agenda")
data class AgendaSync(val events: List<PhoneEvent>) : LinkMessage {
    companion object {
        const val HOURS = 36
        const val MAX_ITEMS = 12
        /** A long description pasted into a location field stays a line. */
        const val MAX_TEXT = 200

        fun of(events: List<PhoneEvent>): AgendaSync =
            AgendaSync(events.take(MAX_ITEMS).map { it.copy(title = it.title.take(MAX_TEXT), location = it.location.take(MAX_TEXT)) })
    }
}

@Serializable
data class PhoneEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean = false, val location: String = "")

/**
 * Phone → head unit: be guided there. A place shared from a map app on the
 * phone: its position when the share carried one, else [query], an address
 * or a place name for the navigation app to look up. [label] is what to call
 * it out loud.
 */
@Serializable
@SerialName("destination")
data class Destination(
    val label: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val query: String? = null
) : LinkMessage {
    val hasPosition: Boolean get() = lat != null && lng != null
}

/**
 * Phone → head unit: text from the phone, for the text field selected on the
 * car's screen. [Mode.REPLACE]: the field becomes [text] (the phone's keyboard
 * card sends its whole field on every change, so the car's field follows it);
 * [Mode.INSERT]: [text] is pasted at the cursor, and stays on the head unit's
 * clipboard (a paste or a share from the phone). [enter] then presses Enter,
 * to run a search. Answered with [TypeResult] under the same [id].
 */
@Serializable
@SerialName("type_text")
data class TypeText(val text: String, val mode: Mode = Mode.INSERT, val enter: Boolean = false, val id: Long = 0) : LinkMessage {
    @Serializable
    enum class Mode { INSERT, REPLACE }

    companion object {
        /** Far more than a search box or a message needs, and always one frame. */
        const val MAX_CHARS = 10_000
    }
}

/**
 * Head unit → phone: what became of a [TypeText]. [typingOff]: the head unit
 * cannot see the selected field at all, its accessibility service being off,
 * so the text went to its clipboard ([Outcome.COPIED]); [EnableTyping] turns
 * it on.
 */
@Serializable
@SerialName("typed")
data class TypeResult(val id: Long, val outcome: Outcome, val typingOff: Boolean = false) : LinkMessage {
    @Serializable
    enum class Outcome {
        /** In the field selected on the car's screen. */
        TYPED,
        /** No text field selected there: the text is on the head unit's clipboard, to paste. */
        COPIED,
        /** Refused by the field or the head unit. */
        FAILED
    }
}

/**
 * Phone → head unit: the driver asks, from the keyboard card, for typing into
 * the car's fields to be turned on (the head unit's accessibility service,
 * which Dashwheel can switch on itself with root). Answered with [TypingAccess].
 */
@Serializable
@SerialName("enable_typing")
data object EnableTyping : LinkMessage

/** Head unit → phone, after an [EnableTyping]: whether text from the phone now goes into the car's fields. */
@Serializable
@SerialName("typing_access")
data class TypingAccess(val on: Boolean) : LinkMessage

/*
 * OBD through the phone: the phone holds the Bluetooth link to the car's
 * ELM327 adapter and passes its serial bytes both ways, so the head unit's
 * own OBD code runs unchanged over the link. The bytes travel as ISO-8859-1
 * text (the adapter speaks ASCII). Enum-free, so an older side just skips them.
 */

/** Phone → head unit, on link up and whenever it changes: the adapter the phone can relay, or null for none. */
@Serializable
@SerialName("obd_offer")
data class ObdOffer(val adapter: String? = null) : LinkMessage

/** Head unit → phone: connect to the offered adapter and start relaying. */
@Serializable
@SerialName("obd_open")
data object ObdOpen : LinkMessage

/** Head unit → phone: stop relaying and let the adapter go. */
@Serializable
@SerialName("obd_close")
data object ObdClose : LinkMessage

/** Phone → head unit: the relay is up ([open]), or it failed or closed, with why for the log. */
@Serializable
@SerialName("obd_state")
data class ObdRelayState(val open: Boolean, val reason: String? = null) : LinkMessage

/** Head unit → phone: bytes for the adapter. */
@Serializable
@SerialName("obd_tx")
data class ObdTx(val data: String) : LinkMessage

/** Phone → head unit: bytes from the adapter. */
@Serializable
@SerialName("obd_rx")
data class ObdRx(val data: String) : LinkMessage

/**
 * A refuel the head unit noticed (the fuel level jumping up while parked):
 * how much went in, at what price where known, and the mileage then, from
 * which the companion works out the real consumption and the spending.
 * [estimated]: the level was worked back from the car's range, so the litres are rough.
 */
@Serializable
data class FuelFill(
    val at: Long,
    val liters: Double,
    val pricePerL: Double? = null,
    val currency: String? = null,
    val odometerKm: Int? = null,
    val station: String? = null,
    val estimated: Boolean = false
) {
    val cost: Double? get() = pricePerL?.let { it * liters }
}

/** Head unit → phone, on link up: the refuels it keeps, newest first. */
@Serializable
@SerialName("fuel_sync")
data class FuelSync(val fills: List<FuelFill>) : LinkMessage

/** Head unit → phone: a refuel just noticed. */
@Serializable
@SerialName("fuel")
data class FuelReport(val fill: FuelFill) : LinkMessage

/** The refuels each app keeps, and the sums over them: the same on both sides. */
object FuelFills {
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(FuelFill.serializer())
    const val MAX = 100

    /** [fill] into [fills] (newest first), replacing one at the same time. */
    fun merge(fills: List<FuelFill>, fill: FuelFill, max: Int = MAX): List<FuelFill> =
        (listOf(fill) + fills.filter { it.at != fill.at }).sortedByDescending { it.at }.take(max)

    fun mergeAll(fills: List<FuelFill>, more: List<FuelFill>, max: Int = MAX): List<FuelFill> =
        more.fold(fills) { acc, f -> merge(acc, f, max) }

    /**
     * Litres per 100 km over the refuels with a mileage: what went in after
     * the first, over the distance from the first to the last. Null under
     * [minKm], where a partly filled tank still skews it.
     */
    fun litersPer100(fills: List<FuelFill>, minKm: Int = 300): Double? {
        val known = fills.filter { it.odometerKm != null }.sortedBy { it.at }
        if (known.size < 2) return null
        val km = known.last().odometerKm!! - known.first().odometerKm!!
        if (km < minKm) return null
        return known.drop(1).sumOf { it.liters } / km * 100
    }

    fun encode(fills: List<FuelFill>): String = json.encodeToString(list, fills)

    /** Empty for a text this side can't read. */
    fun decode(text: String): List<FuelFill> =
        try {
            json.decodeFromString(list, text)
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
}

/**
 * Something about the car worth a notification on the phone, where the
 * driver sees it once out of the car: servicing coming up, a new fault, a
 * weak battery, the particulate filter. Written by the head unit in its own
 * language; [id] stays the same for the same news, so the phone tells it once.
 */
@Serializable
data class CarNotice(val id: String, val kind: String, val title: String, val text: String, val at: Long) {
    companion object {
        const val UPKEEP = "upkeep"
        const val FAULT = "fault"
        const val BATTERY = "battery"
        const val FILTER = "filter"
    }
}

/** Head unit → phone, on link up and whenever it changes: every notice standing now. */
@Serializable
@SerialName("notices")
data class CarNotices(val notices: List<CarNotice>) : LinkMessage

/** Phone → head unit, on link up: what this companion can do for the car beyond the basics. */
@Serializable
@SerialName("abilities")
data class PhoneAbilities(val sendsTexts: Boolean = false) : LinkMessage

/** Head unit → phone: send [text] by SMS to [number] ("On my way"); answered by [TextSent] with the same [id]. */
@Serializable
@SerialName("send_text")
data class SendText(val number: String, val text: String, val id: Long) : LinkMessage

/**
 * Phone → head unit: what the phone is playing (the audio the car hears over
 * Bluetooth), with its cover, which the unit's Bluetooth doesn't pass on.
 * [artKey] names the picture: [artJpeg] (base64) comes only when it changed,
 * so a play or pause doesn't resend it. An empty [title]: nothing playing.
 */
@Serializable
@SerialName("now_playing")
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val durationMs: Long = 0,
    val artKey: Int = 0,
    val artJpeg: String? = null
) : LinkMessage

/** Phone → head unit, on link up and at each change: the phone's battery, exact, and whether it charges. */
@Serializable
@SerialName("battery")
data class PhoneBattery(val percent: Int, val charging: Boolean = false) : LinkMessage

/**
 * Phone → head unit, about once a second while linked: the phone's own GPS
 * fix, for the head unit to use in place of its weaker receiver. [ageMs] is
 * how old the fix was when sent (the two clocks differ, so no timestamp);
 * [satellites] are the ones used in the fix, [seen] all in view.
 */
@Serializable
@SerialName("phone_fix")
data class PhoneFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val ageMs: Long = 0,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val altitudeM: Double? = null,
    val satellites: Int = 0,
    val seen: Int = 0
) : LinkMessage

/** Phone → head unit: whether the [SendText] with [id] went. */
@Serializable
@SerialName("text_sent")
data class TextSent(val id: Long, val sent: Boolean) : LinkMessage

/** A notification as the head unit shows it. [key] is the phone's own key. */
@Serializable
data class PhoneNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** The latest lines of a messaging conversation, oldest first; empty otherwise. */
    val messages: List<ConversationLine> = emptyList(),
    val canReply: Boolean = false,
    val canMarkRead: Boolean = false,
    /** The app's icon as a small PNG, base64. */
    val iconPng: String? = null
)

@Serializable
data class ConversationLine(val sender: String, val text: String, val at: Long)

/**
 * One drive, as the dashboard's trip computer counted it and its eco-driving
 * card judged it. Only what the head unit knows for sure travels: the eco
 * figures are null without the OBD adapter (or under a kilometre), the fuel
 * ones are an estimate at the car's usual consumption and the driver's own
 * fuel price. [startedAt] identifies the drive on both sides.
 */
@Serializable
data class DriveSummary(
    val startedAt: Long,
    /** When the car last moved: the end of the drive, or the time of an ongoing one's figures. */
    val endedAt: Long,
    val distanceKm: Double,
    /** Time spent moving, not counting the stops. */
    val movingMs: Long,
    val maxSpeedKmh: Int,
    /** 0-100: smooth, relaxed driving scores high. */
    val ecoScore: Int? = null,
    /** Share of the moving time spent in the car's relaxed rev band, 0-100. */
    val sweetPercent: Int? = null,
    val hardAccel: Int = 0,
    val hardBrake: Int = 0,
    /** A robotised gearbox held on the throttle, standing still. */
    val clutchHolds: Int = 0,
    val fuelLiters: Double? = null,
    val fuelCost: Double? = null,
    val currency: String? = null,
    /** Still under way when sent; false once the drive is closed. */
    val ongoing: Boolean = false,
    /** Where the drive ended, when known (the commute history tells home and work by it). */
    val endLat: Double? = null,
    val endLng: Double? = null
) {
    val elapsedMs: Long get() = (endedAt - startedAt).coerceAtLeast(0L)
    val avgSpeedKmh: Int get() = if (movingMs <= 0L) 0 else Math.round(distanceKm / (movingMs / 3_600_000.0)).toInt()
}

/** The drives each app keeps, and how they are stored: the same on both sides. */
object DriveSummaries {
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(DriveSummary.serializer())

    /**
     * [drive] into [drives] (newest first): it replaces the entry with the same
     * start time, and only the [max] newest are kept.
     */
    fun merge(drives: List<DriveSummary>, drive: DriveSummary, max: Int): List<DriveSummary> =
        (listOf(drive) + drives.filter { it.startedAt != drive.startedAt }).sortedByDescending { it.startedAt }.take(max)

    fun mergeAll(drives: List<DriveSummary>, more: List<DriveSummary>, max: Int): List<DriveSummary> =
        more.fold(drives) { acc, drive -> merge(acc, drive, max) }

    fun encode(drives: List<DriveSummary>): String = json.encodeToString(list, drives)

    /** Empty for a text this side can't read. */
    fun decode(text: String): List<DriveSummary> =
        try {
            json.decodeFromString(list, text)
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
}

/** JSON <-> [LinkMessage]. */
object LinkCodec {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
    }

    fun encode(message: LinkMessage): ByteArray =
        json.encodeToString(LinkMessage.serializer(), message).encodeToByteArray()

    /** Null for a message this side doesn't know (a newer peer) or can't read. */
    fun decode(bytes: ByteArray): LinkMessage? =
        try {
            json.decodeFromString(LinkMessage.serializer(), bytes.decodeToString())
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}

/**
 * Head unit → phone: the unit's log (what it did around switch-off and
 * wake-up), for the driver to share as a bug report. [id] pairs it with the
 * [CarLogAck].
 */
@Serializable
@SerialName("car_log")
data class CarLog(val id: Long, val title: String, val text: String) : LinkMessage {
    companion object {
        /** The newest part of [text] that fits in one frame, with room for the rest of the message. */
        fun fit(text: String, maxBytes: Int = LinkSession.MAX_MESSAGE - 1024): String {
            var t = text
            // The JSON frame escapes newlines and quotes, so budget double: cut from the front until it fits.
            while (t.isNotEmpty() && t.toByteArray().size * 2 > maxBytes) t = t.substring(t.length / 4)
            return t
        }
    }
}

/** Phone → head unit: the [CarLog] is saved on the phone, ready to share. */
@Serializable
@SerialName("car_log_ack")
data class CarLogAck(val id: Long, val ok: Boolean) : LinkMessage

/**
 * Phone → head unit, EXPERIMENTAL: a picture of the driver's own car (a car
 * pack or an image), for the head unit to show in place of its drawn car. Too
 * big for one frame, it goes in parts: [data] is part [index] of [count]
 * (base64), all under one [id]. The head unit answers the last part, or the
 * first one it can't take, with a [CarLookAck].
 */
@Serializable
@SerialName("car_look_part")
data class CarLookPart(val id: Long, val index: Int, val count: Int, val name: String, val data: String) : LinkMessage {
    companion object {
        /** Raw bytes per part: base64 makes it a third bigger, well inside a frame. */
        const val PART_BYTES = 256 * 1024

        /** The largest file sent: a car pack is a few pictures. */
        const val MAX_BYTES = 24 * 1024 * 1024
    }
}

/** Head unit → phone: what became of the [CarLookPart]s under [id]. */
@Serializable
@SerialName("car_look_ack")
data class CarLookAck(val id: Long, val ok: Boolean) : LinkMessage
