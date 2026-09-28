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

/** Head unit → phone: what became of a [TypeText]. */
@Serializable
@SerialName("typed")
data class TypeResult(val id: Long, val outcome: Outcome) : LinkMessage {
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
    val ongoing: Boolean = false
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
