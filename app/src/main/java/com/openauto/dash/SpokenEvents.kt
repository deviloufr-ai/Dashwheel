package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the car says without being asked, one switch each (Settings,
 * Alerts, "What the car says"). Each feature asks [SpokenEvents.isOn] before
 * it speaks, and speaks through [CarVoice.announce], so Quiet covers it too.
 * What the driver asks for (a wheel button, the message card's read button)
 * is said whatever these switches say.
 */
enum class SpokenEvent(val key: String, val default: Boolean) {
    /** Ice, low fuel, the next appointment, the engine, as the car starts ([StartupBriefing]). */
    BRIEFING("briefing", true),
    /** The engine, the battery, the particle filter, a break, low fuel, the head unit running hot ([CarCare], [AiMechanic], [HeadUnitMonitor]). */
    CAR_TIPS("car_tips", true),
    /** Fault codes the scan hasn't seen before ([AiMechanic]). */
    FAULT_CODES("fault_codes", true),
    /** Staying over the road's limit ([SpeedLimit]); off until asked, a nag otherwise. */
    SPEED_LIMIT("speed_limit", false),
    /** A fixed camera ahead ([SpeedCameras]); off until asked, not allowed everywhere. */
    SPEED_CAMERAS("speed_cameras", false),
    /** Orange and red weather warnings where the car is and goes ([WeatherAlerts]). */
    WEATHER("weather", true),
    /** Who a phone message is from ([AlertVoice], [MessageAlerts]). */
    MESSAGES("messages", true),
    /** The second, LPG tank running low ([LpgTank]); shown only while LPG is on. */
    LPG("lpg", true)
}

object SpokenEvents {
    private const val PREFS = "spoken_events"
    private const val KEY_MIGRATED = "migrated"

    private val _on = MutableStateFlow(SpokenEvent.entries.filter { it.default }.toSet())
    /** The events said out loud now. */
    val on: StateFlow<Set<SpokenEvent>> = _on
    private var loaded = false

    /**
     * Reads the switches once. The first time, the switches that used to live
     * elsewhere seed these: the mechanic's "Warnings and tips" and briefing,
     * the speed limit's and the cameras' tile buttons, the message alert's.
     */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_MIGRATED, false)) {
            val ai = app.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            val warnings = ai.getBoolean("speak", true)
            val seeded = mapOf(
                SpokenEvent.BRIEFING to ai.getBoolean("briefing", true),
                SpokenEvent.CAR_TIPS to warnings,
                SpokenEvent.FAULT_CODES to warnings,
                SpokenEvent.LPG to warnings,
                // Spoken limits used to be on by default: only a driver who chose them keeps them.
                SpokenEvent.SPEED_LIMIT to app.getSharedPreferences("speed_limit", Context.MODE_PRIVATE).let { it.contains("speak") && it.getBoolean("speak", false) },
                SpokenEvent.SPEED_CAMERAS to app.getSharedPreferences("speed_cameras", Context.MODE_PRIVATE).getBoolean("speak", false),
                SpokenEvent.MESSAGES to app.getSharedPreferences("alert_styles", Context.MODE_PRIVATE).getBoolean("speak_messages", true)
            )
            p.edit().apply {
                seeded.forEach { (event, on) -> putBoolean(event.key, on) }
                putBoolean(KEY_MIGRATED, true)
            }.apply()
        }
        _on.value = SpokenEvent.entries.filter { p.getBoolean(it.key, it.default) }.toSet()
    }

    /** Whether [event] is said now; any thread. */
    fun isOn(context: Context, event: SpokenEvent): Boolean {
        load(context)
        return event in _on.value
    }

    fun set(context: Context, event: SpokenEvent, on: Boolean) {
        load(context)
        _on.value = if (on) _on.value + event else _on.value - event
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(event.key, on).apply()
    }
}
