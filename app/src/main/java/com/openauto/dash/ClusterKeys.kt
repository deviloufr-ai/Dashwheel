package com.openauto.dash

/*
 * Steering-wheel keys for the second screen's cluster. Pure Kotlin (key codes
 * as ints), fed by MainActivity.dispatchKeyEvent while the dashboard is in
 * front and by SplitAccessibilityService.onKeyEvent while another app is.
 */

enum class ClusterKeyAction {
    NEXT_PAGE,
    PREVIOUS_PAGE,

    /** A short press on a media key that was held back to see whether it would be long: skip the track after all. */
    SKIP_NEXT,
    SKIP_PREVIOUS
}

/** What to do with one key event: swallow it or let it through, and what it does here. */
data class KeyDecision(val consume: Boolean, val action: ClusterKeyAction? = null) {
    companion object {
        val PASS = KeyDecision(false)
        val SWALLOW = KeyDecision(true)
    }
}

/**
 * Turns key events into cluster page changes.
 *
 * - A key learnt in Settings ([SecondScreenConfig.pageKeys]) turns the page,
 *   and does nothing else while the cluster is showing.
 * - With [SecondScreenConfig.mediaKeysTurnPages], media next / previous held
 *   down turn the page; a short press still skips the track (done here, as the
 *   key was held back to tell the two apart).
 * - Everything else, and everything while the cluster isn't showing, passes.
 *
 * While [learning], the next key pressed is handed to [onLearnt] instead.
 */
class ClusterKeyTracker {

    @Volatile var learning: ((Int) -> Unit)? = null

    /** The media key being held, and whether it has turned the page already. */
    private var heldMedia: Int? = null
    private var heldTurned = false

    @Synchronized
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, config: SecondScreenConfig, clusterShowing: Boolean): KeyDecision {
        learning?.let { learnt ->
            if (keyCode in NEVER_LEARNT) return KeyDecision.PASS
            if (down && repeatCount == 0) {
                learning = null
                learnt(keyCode)
            }
            return KeyDecision.SWALLOW
        }
        if (!clusterShowing) {
            heldMedia = null
            return KeyDecision.PASS
        }
        if (keyCode in config.pageKeys) {
            return if (down && repeatCount == 0) KeyDecision(true, ClusterKeyAction.NEXT_PAGE) else KeyDecision.SWALLOW
        }
        if (!config.mediaKeysTurnPages || (keyCode != KEYCODE_MEDIA_NEXT && keyCode != KEYCODE_MEDIA_PREVIOUS)) {
            return KeyDecision.PASS
        }
        val next = keyCode == KEYCODE_MEDIA_NEXT
        return when {
            down && repeatCount == 0 -> {
                heldMedia = keyCode
                heldTurned = false
                KeyDecision.SWALLOW
            }
            down -> {
                // Held: the key repeats. The first repeat turns the page, once.
                if (heldMedia == keyCode && !heldTurned) {
                    heldTurned = true
                    KeyDecision(true, if (next) ClusterKeyAction.NEXT_PAGE else ClusterKeyAction.PREVIOUS_PAGE)
                } else {
                    KeyDecision.SWALLOW
                }
            }
            else -> {
                val wasHeld = heldMedia == keyCode
                val turned = heldTurned
                heldMedia = null
                heldTurned = false
                when {
                    // An up without its down (pressed before the cluster showed): leave it to the player.
                    !wasHeld -> KeyDecision.PASS
                    turned -> KeyDecision.SWALLOW
                    else -> KeyDecision(true, if (next) ClusterKeyAction.SKIP_NEXT else ClusterKeyAction.SKIP_PREVIOUS)
                }
            }
        }
    }

    companion object {
        const val KEYCODE_BACK = 4
        const val KEYCODE_HOME = 3
        const val KEYCODE_MEDIA_NEXT = 87
        const val KEYCODE_MEDIA_PREVIOUS = 88

        /** Keys a driver must never lose to learning. */
        private val NEVER_LEARNT = setOf(KEYCODE_BACK, KEYCODE_HOME)
    }
}
