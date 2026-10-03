package com.openauto.dash

/**
 * What [MainActivity] asks of the dashboard (Home pressed, a wheel button's
 * "next dashboard", the app drawer...) is kept as the time of the last ask. A
 * dashboard built anew, after the screen turned or the language changed,
 * reads that same last value again: [fresh] lets each ask through once, so a
 * press of Home from an hour ago isn't answered a second time by the new
 * dashboard (it closed the Settings a turned screen had just opened again).
 */
internal object OneShot {
    private val answered = HashMap<String, Long>()

    /** True the first time the ask [what] made at [at] is seen; never for 0, "not asked yet". Main thread only. */
    fun fresh(what: String, at: Long): Boolean {
        if (at == 0L || answered[what] == at) return false
        answered[what] = at
        return true
    }
}
