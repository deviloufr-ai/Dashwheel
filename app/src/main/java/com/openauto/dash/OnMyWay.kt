package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

/*
 * "On my way": one tap texts a favourite the arrival time from the guidance
 * under way ("On my way to Mum's, I should be there around 18:40"), sent by
 * the linked phone as a plain SMS so nobody types at the wheel. Only once the
 * companion says it may send texts ([PhoneLink.textsOn]).
 */

/** The message, pure so it's unit-tested. */
internal object OnMyWayText {
    /** A destination older than this belongs to another trip. */
    const val DESTINATION_MS = 6 * 3_600_000L

    /**
     * The text for [arrival] (null: not guiding) to [destination] (null:
     * unknown), with [time] formatting the arrival.
     */
    fun of(arrival: Long?, destination: String?, time: (Long) -> String, strings: (Int, Array<Any>) -> String): String = when {
        arrival != null && !destination.isNullOrBlank() -> strings(R.string.onmyway_text_to, arrayOf(destination, time(arrival)))
        arrival != null -> strings(R.string.onmyway_text_eta, arrayOf(time(arrival)))
        else -> strings(R.string.onmyway_text_plain, emptyArray())
    }
}

internal object OnMyWay {
    private const val PREFS = "on_my_way"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The phone's favourites that have a number, the last one texted first. */
    fun contacts(context: Context): List<Favourite> {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)
        return PhoneLink.lists.value.favourites.orEmpty().filter { !it.number.isNullOrBlank() }.sortedByDescending { it.number == last }
    }

    /** The text as it would go now, in the launcher's language. */
    fun message(context: Context): String {
        val res = AppLanguage.wrap(context.applicationContext).resources
        val nav = NavDirections.state.value
        val now = System.currentTimeMillis()
        val arrival = if (nav.active) arrivalMs(nav.eta, now) else null
        val destination = NavHandoff.destination?.takeIf { now - it.second < OnMyWayText.DESTINATION_MS }?.first
        return OnMyWayText.of(
            arrival, destination,
            time = { Units.current.value.time(Date(it), res.configuration.locales[0]) },
            strings = { id, args -> res.getString(id, *args) }
        )
    }

    /** Texts [to] and says how it went. */
    fun send(context: Context, to: Favourite) {
        val app = context.applicationContext
        val number = to.number ?: return
        val text = message(app)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", number).apply()
        scope.launch {
            when (PhoneLink.sendText(number, text)) {
                true -> HandsFree.say(app, R.string.onmyway_sent, to.name)
                false -> HandsFree.say(app, R.string.onmyway_failed, to.name)
                null -> HandsFree.say(app, R.string.onmyway_no_answer)
            }
        }
    }
}
