package com.openauto.dash

/**
 * The next turn by name, for the second screen ([com.openauto.dash.link.ClusterState.maneuver]):
 * from the route's OSRM-style manoeuvre type and modifier, as the in-app
 * guidance has them. A display then draws the right sign without reading the
 * instruction's words.
 */
object Maneuvers {
    const val LEFT = "LEFT"
    const val RIGHT = "RIGHT"
    const val STRAIGHT = "STRAIGHT"
    const val UTURN = "UTURN"
    const val ROUNDABOUT = "ROUNDABOUT"
    const val ARRIVE = "ARRIVE"

    private val ROUNDABOUTS = setOf("roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary")
    private val ONWARD = setOf("continue", "new name", "depart", "merge", "use lane", "notification")

    /** Null when neither says which way: the display falls back to the words. */
    fun fromOsrm(type: String?, modifier: String?): String? {
        val t = type?.trim()?.lowercase().orEmpty()
        val m = modifier?.trim()?.lowercase().orEmpty()
        return when {
            t == "arrive" -> ARRIVE
            t in ROUNDABOUTS -> ROUNDABOUT
            "uturn" in m || "u-turn" in m -> UTURN
            "left" in m -> LEFT
            "right" in m -> RIGHT
            m == "straight" || (m.isEmpty() && t in ONWARD) -> STRAIGHT
            else -> null
        }
    }
}
