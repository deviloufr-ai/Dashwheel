package com.openauto.dash

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Waze's next turn, read off its screen. Waze's notification while it guides
 * only says it is running (seen on the K706, 2026-10-09: "Waze · En cours
 * d'exécution"), so the directions tile and the second screen had nothing to
 * show. Its guidance banner is on screen though: the distance and the street
 * at the top, the arrival time and what is left at the bottom. The
 * accessibility service ([SplitAccessibilityService]) hands over Waze's window
 * changes; its text is read here, at most once a second, and nothing else of
 * Waze is touched. GitHub edition only: Google Play allows accessibility to
 * read other apps for people with disabilities alone.
 */
internal object WazeScreen {
    const val PACKAGE = "com.waze"
    private const val TAG = "WazeScreen"
    private const val EVERY_MS = 1_000L
    private const val LOG_EVERY_MS = 10_000L

    @Volatile private var lastAt = 0L
    @Volatile private var logged: String? = null
    @Volatile private var loggedAt = 0L

    /** One piece of text on Waze's screen and where it sits, in its window. */
    data class Piece(val text: String, val top: Int, val bottom: Int, val left: Int, val described: Boolean = false)

    /** A window change in Waze: its banner is read again when the last read is a second old. Main thread. */
    fun onEvent(context: android.content.Context, event: AccessibilityEvent) {
        if (!Edition.full || event.packageName != PACKAGE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < EVERY_MS) return
        lastAt = now
        val root = runCatching { event.source?.let(::rootOf) }.getOrNull() ?: return
        val height = Rect().also { root.getBoundsInScreen(it) }.height()
        val pieces = ArrayList<Piece>()
        runCatching { collect(root, pieces, 0) }
        val state = fromScreen(pieces, height, context.getString(R.string.info_nav_continue))
        log(pieces, state)
        NavDirections.publishScreen(state)
        // No turn: maybe its route preview, which names the destination ("Votre position", then the place).
        if (state == null) MapsScreen.destinationOf(pieces, height)?.let(MapsScreen::noticed)
    }

    private fun rootOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n = node
        while (true) n = n.parent ?: return n
    }

    private fun collect(node: AccessibilityNodeInfo, into: MutableList<Piece>, depth: Int) {
        if (depth > 40 || !node.isVisibleToUser) return
        val r = Rect().also { node.getBoundsInScreen(it) }
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { into += Piece(it, r.top, r.bottom, r.left) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { into += Piece(it, r.top, r.bottom, r.left, described = true) }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, into, depth + 1) }
    }

    /** What was read, when it changes and at most every [LOG_EVERY_MS]: to teach the reader a format it gets wrong. */
    private fun log(pieces: List<Piece>, state: NavState?) {
        val what = pieces.joinToString(" | ") { (if (it.described) "~" else "") + it.text + "@" + it.top }
        val now = SystemClock.elapsedRealtime()
        if (what == logged || now - loggedAt < LOG_EVERY_MS) return
        logged = what
        loggedAt = now
        Log.i(TAG, "read ${if (state == null) "no turn" else "${state.distance} ${state.instruction} (${state.eta})"} from [$what]")
    }

    private val CLOCK = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")
    private val DURATION = Regex("""\b\d+\s*(min|h|hr)\b""", RegexOption.IGNORE_CASE)
    /** The word before the turn after next ("puis", "then"): not a street. */
    private val THEN = setOf("puis", "then", "dann", "luego", "poi", "depois", "daarna", "potem", "затем", "далее")

    /**
     * The turn on Waze's screen: in its top quarter the distance (the first
     * text that is only a distance) and the street after it, with the arrow's
     * description when it has one; in its bottom third the arrival time and
     * what is left ("6 min • 3.1 km"). Null without a distance up top: Waze is
     * not guiding. Pure, so the format can be unit tested.
     */
    fun fromScreen(pieces: List<Piece>, height: Int, continueLabel: String = "Continue"): NavState? {
        if (height <= 0) return null
        val top = pieces.filter { it.top < height / 4 }
        val distance = top.firstOrNull { !it.described && NavState.DISTANCE.matchEntire(it.text) != null } ?: return null
        val street = top.firstOrNull {
            !it.described && it !== distance && NavState.DISTANCE.matchEntire(it.text) == null && it.text.lowercase() !in THEN
        }?.text
        val arrow = top.firstOrNull {
            it.described && NavState.DISTANCE.matchEntire(it.text) == null && it.text != street && it.text.lowercase() !in THEN
        }?.text
        val instruction = listOfNotNull(arrow, street).distinct().joinToString(", ").ifEmpty { continueLabel }
        // Arrival line, as Maps words it: what is left, then the time.
        val bottom = pieces.filter { !it.described && it.top >= height * 2 / 3 }
        val left = bottom.flatMap { it.text.split('•', '·') }.map { it.trim() }
            .filter { DURATION.containsMatchIn(it) || NavState.DISTANCE.matchEntire(it) != null }
        val arrival = bottom.firstOrNull { CLOCK.matches(it.text) }?.text
        val eta = (left + listOfNotNull(arrival)).distinct().joinToString(" · ")
        return NavState(active = true, instruction = instruction, distance = distance.text, eta = eta, packageName = PACKAGE)
    }
}
