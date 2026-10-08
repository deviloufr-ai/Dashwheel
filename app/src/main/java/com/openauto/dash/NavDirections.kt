package com.openauto.dash

import android.app.Notification
import android.os.SystemClock
import android.util.Log
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The next manoeuvre of a running navigation app, as shown on the dashboard.
 *
 * Google Maps (and Waze) cannot be embedded on this unit, but while they
 * navigate they post an ongoing notification carrying the turn icon, the
 * distance to the turn, the street and the ETA line. [NavDirections] reads that
 * notification and publishes it here for the Directions tile and the banner
 * over the MapLibre map.
 */
data class NavState(
    val active: Boolean = false,
    /** e.g. "Turn right onto Rue de Rivoli". */
    val instruction: String = "",
    /** Distance to the manoeuvre, e.g. "300 m". Empty when unknown. */
    val distance: String = "",
    /** ETA line as posted, e.g. "12 min · 6.4 km · 09:48". Empty when unknown. */
    val eta: String = "",
    /** The manoeuvre arrow, usually a white glyph on transparent. */
    val icon: Bitmap? = null,
    val packageName: String = ""
) {
    /** Distance split into value and unit: "300 m" → ("300", "m"). */
    val distanceParts: Pair<String, String>
        get() {
            val m = DISTANCE.find(distance) ?: return distance to ""
            return m.groupValues[1] to m.groupValues[2]
        }

    /**
     * ETA segments as separate chips: "12 min · 6.4 km · 09:48" → three entries.
     * The arrival comes as just its time: Maps words it "Arrivée : 13:51" or "ETA 1:51 PM".
     */
    val etaParts: List<String>
        get() = eta.split('·', '•', '|').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
            // Only a time that ends the part: "1h05 min" is a duration.
            val clock = CLOCK.find(part)?.takeIf { it.range.last == part.lastIndex } ?: return@map part
            if (part.removeRange(clock.range).any { it.isDigit() }) part else clock.value
        }

    companion object {
        private val CLOCK = Regex("""\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?""")
        val DISTANCE = Regex("""(\d+(?:[.,]\d+)?)\s?(km|m|mi|ft|yd)\b""")
    }
}

object NavDirections {
    /** Navigation apps whose turn-by-turn notification we read. */
    val PACKAGES = setOf("com.google.android.apps.maps", "com.waze")

    /** "In", "Dans", "En", "A", "À" (+ optional comma) at the start of an instruction. */
    private val LEADING_PREPOSITION = Regex("^(in|dans|en|a|à)\\s*,?\\s*", RegexOption.IGNORE_CASE)

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    private val _running = MutableStateFlow<String?>(null)
    /**
     * The navigation app with an ongoing notification now (Waze, Google Maps),
     * whether or not its turns could be read from it: the tile names it and
     * opens it rather than say there is no route.
     */
    val running: StateFlow<String?> = _running
    @Volatile private var runningKey: String? = null

    /** What was last logged of a notification whose turns couldn't be read, and when. */
    @Volatile private var unreadLogged: String? = null
    @Volatile private var unreadLoggedAt = 0L

    /** Notification key currently driving [state], so its removal clears the route. */
    @Volatile
    private var currentKey: String? = null

    /**
     * Google Maps' "Exit navigation", the one button on its guidance
     * notification, while it guides. Only taken when the notification has a
     * single button: with more, which one exits can't be told.
     */
    @Volatile
    private var exit: PendingIntent? = null

    /** Asks the navigation app that guides to stop; false when none does, or it offers no way to. */
    fun exitNavigation(): Boolean {
        val intent = exit ?: return false
        exit = null
        return runCatching { intent.send() }.isSuccess
    }

    /** The real route, kept up to date while the demo shows its own, and put back when it ends. */
    @Volatile
    private var real = NavState()

    /**
     * Per navigation app: updates in a row where inflating its custom layout
     * found nothing the extras hadn't given. That inflation runs on the main
     * thread about once a second while navigating, so an app whose layout
     * never helps stops paying for it (and is tried again now and then).
     */
    private val fruitlessInflations = HashMap<String, Int>()
    private const val MAX_FRUITLESS = 3
    private const val RETRY_INFLATION_EVERY = 30

    /** [DemoMode]'s route. */
    internal fun demoWrite(state: NavState) {
        _state.value = state
    }

    /** The dashboard's own guidance ([InAppNav]): ahead of Google Maps' and Waze's turns while it runs. */
    @Volatile
    private var inApp: NavState? = null

    /** The demo is over: the real route back, as it is now (a route ended meanwhile stays ended). */
    internal fun endDemo() {
        _state.value = inApp ?: real
    }

    private fun publish(state: NavState) {
        real = state
        if (!DemoMode.isOn && inApp == null) _state.value = state
    }

    /** [InAppNav]'s next turn; null when its guidance ends and the navigation app's turns come back. */
    internal fun publishInApp(state: NavState?) {
        inApp = state
        if (!DemoMode.isOn) _state.value = state ?: real
    }

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        if (sbn.isOngoing) {
            runningKey = sbn.key
            _running.value = sbn.packageName
        }
        val parsed = parse(context, sbn) ?: return
        currentKey = sbn.key
        exit = sbn.notification?.actions?.singleOrNull()?.actionIntent?.takeIf { sbn.packageName == NavHandoff.MAPS }
        publish(parsed)
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key == runningKey) {
            runningKey = null
            _running.value = null
        }
        if (sbn.key == currentKey) {
            currentKey = null
            exit = null
            publish(NavState())
        }
    }

    fun clear() {
        runningKey = null
        _running.value = null
        currentKey = null
        exit = null
        publish(NavState())
    }

    /**
     * Extracts a [NavState] from a notification, or null when it is not a
     * turn-by-turn notification (Maps also posts plain "running" notices).
     */
    private fun parse(context: Context, sbn: StatusBarNotification): NavState? {
        val n = sbn.notification ?: return null
        if (!sbn.isOngoing) return null
        val extras = n.extras

        val lines = mutableListOf<String>()
        fun add(cs: CharSequence?) {
            val t = cs?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && t !in lines) lines += t
        }
        add(extras.getCharSequence(Notification.EXTRA_TITLE))
        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))

        var icon: Bitmap? = runCatching {
            n.getLargeIcon()?.loadDrawable(context)?.toBitmap()
        }.getOrNull()

        // Maps often uses a custom layout with nothing in the extras, so inflate
        // the remote views and read their text and image views directly.
        val pkg = sbn.packageName
        val continueLabel = context.getString(R.string.info_nav_continue)
        val fruitless = fruitlessInflations[pkg] ?: 0
        val worthInflating = lines.isEmpty() || fruitless < MAX_FRUITLESS || fruitless % RETRY_INFLATION_EVERY == 0
        if ((lines.isEmpty() || icon == null) && worthInflating) {
            val without = fromLines(lines.toList(), icon, pkg, continueLabel)
            @Suppress("DEPRECATION")
            for (rv in listOf(n.bigContentView, n.contentView, n.headsUpContentView)) {
                val (texts, bmp) = remoteContent(context, rv)
                texts.forEach { add(it) }
                if (icon == null) icon = bmp
                if (lines.isNotEmpty() && icon != null) break
            }
            // Helped only if what the tile shows came out different.
            val helped = fromLines(lines, icon, pkg, continueLabel) != without
            fruitlessInflations[pkg] = if (helped) 0 else fruitless + 1
        } else if (!worthInflating) {
            fruitlessInflations[pkg] = fruitless + 1
        }
        val parsed = if (lines.isEmpty()) null else fromLines(lines, sameIcon(icon), pkg, continueLabel)
        if (parsed == null) logUnread(pkg, lines)
        return parsed
    }

    /**
     * What a navigation app's ongoing notification said when no turn could be
     * read from it (Waze's format is not known yet): logged when it changes, at
     * most every [UNREAD_LOG_MS], for the parser to be taught it.
     */
    private fun logUnread(pkg: String, lines: List<String>) {
        val what = "$pkg $lines"
        val now = SystemClock.elapsedRealtime()
        if (what == unreadLogged || now - unreadLoggedAt < UNREAD_LOG_MS) return
        unreadLogged = what
        unreadLoggedAt = now
        Log.i(TAG, "no turn read from $what")
    }

    private const val TAG = "NavDirections"
    private const val UNREAD_LOG_MS = 10_000L

    /**
     * Every update (about once a second) carries its arrow as a new bitmap;
     * the one on screen is kept while the arrow looks the same, so an update
     * that changed nothing else doesn't redraw the tile.
     */
    private fun sameIcon(icon: Bitmap?): Bitmap? {
        val shown = real.icon ?: return icon
        if (icon == null || shown === icon) return icon
        // sameAs checks size and format before comparing the pixels.
        return if (!shown.isRecycled && shown.sameAs(icon)) shown else icon
    }

    /**
     * Builds a [NavState] from the text lines of a navigation notification
     * (title, text, sub text, big text or the custom layout's text views, in
     * that order, de-duplicated). Null when the lines don't describe a turn.
     * Pure so the Maps / Waze formats can be unit tested.
     */
    internal fun fromLines(
        lines: List<String>,
        icon: Bitmap?,
        packageName: String,
        /** Shown when the card has a distance but no instruction text (localized by [parse]). */
        continueLabel: String = "Continue"
    ): NavState? {
        if (lines.isEmpty()) return null

        val eta = lines.firstOrNull { it.contains('·') || it.contains('•') } ?: lines.firstOrNull {
            ETA_LINE.containsMatchIn(it) && NavState.DISTANCE.find(it)?.value != it
        }
        val rest = lines.filter { it != eta }
        val distanceOnly = rest.firstOrNull { NavState.DISTANCE.matchEntire(it) != null }

        var distance = distanceOnly.orEmpty()
        var instruction = rest.firstOrNull { it != distanceOnly }.orEmpty()
        if (distance.isEmpty()) {
            // "In 300 m, turn right" style: pull the distance out of the instruction.
            NavState.DISTANCE.find(instruction)?.let { m ->
                distance = m.value
                instruction = instruction.replace(m.value, "").trim().trimStart(',', '-', '–', ':', ' ')
                    // "In 300 m, turn right" leaves "In , turn right"; drop the preposition.
                    .replace(LEADING_PREPOSITION, "")
                    .replaceFirstChar { it.uppercase() }
            }
        }
        if (instruction.isEmpty() && distance.isEmpty()) return null
        // Not a turn-by-turn card unless it has a distance or an ETA line.
        if (distance.isEmpty() && eta == null) return null

        return NavState(
            active = true,
            instruction = instruction.ifEmpty { continueLabel },
            distance = distance,
            eta = eta.orEmpty(),
            icon = icon,
            packageName = packageName
        )
    }

    private val ETA_LINE = Regex("""\b\d+\s*(min|h|hr)\b""", RegexOption.IGNORE_CASE)

    private fun remoteContent(context: Context, rv: RemoteViews?): Pair<List<String>, Bitmap?> {
        rv ?: return emptyList<String>() to null
        return runCatching {
            val root = rv.apply(context, FrameLayout(context))
            val texts = mutableListOf<String>()
            var bmp: Bitmap? = null
            fun walk(v: View) {
                when (v) {
                    is TextView -> v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    is ImageView -> if (bmp == null) bmp = (v.drawable as? BitmapDrawable)?.bitmap
                        ?: v.drawable?.let { d -> runCatching { d.toBitmap() }.getOrNull() }
                    is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(root)
            texts to bmp
        }.getOrDefault(emptyList<String>() to null)
    }
}
