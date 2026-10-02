package com.openauto.dash

import com.openauto.dash.ClusterKeyTracker.Companion.KEYCODE_BACK
import com.openauto.dash.ClusterKeyTracker.Companion.KEYCODE_MEDIA_NEXT
import com.openauto.dash.ClusterKeyTracker.Companion.KEYCODE_MEDIA_PREVIOUS
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterKeysTest {

    private val learnt = SecondScreenConfig(pageKeys = setOf(KEY_MODE))
    private val mediaPages = SecondScreenConfig(mediaKeysTurnPages = true)

    @Test
    fun aLearntKeyTurnsThePageOnceAndIsSwallowed() {
        val t = ClusterKeyTracker()
        assertEquals(KeyDecision(true, ClusterKeyAction.NEXT_PAGE), t.onKey(KEY_MODE, down = true, repeatCount = 0, config = learnt, clusterShowing = true))
        assertEquals(KeyDecision.SWALLOW, t.onKey(KEY_MODE, down = true, repeatCount = 1, config = learnt, clusterShowing = true))
        assertEquals(KeyDecision.SWALLOW, t.onKey(KEY_MODE, down = false, repeatCount = 0, config = learnt, clusterShowing = true))
    }

    @Test
    fun nothingIsTakenWhileTheClusterIsntShowing() {
        val t = ClusterKeyTracker()
        assertEquals(KeyDecision.PASS, t.onKey(KEY_MODE, true, 0, learnt, clusterShowing = false))
        assertEquals(KeyDecision.PASS, t.onKey(KEYCODE_MEDIA_NEXT, true, 0, mediaPages, clusterShowing = false))
    }

    @Test
    fun mediaKeysPassUnlessAskedFor() {
        val t = ClusterKeyTracker()
        assertEquals(KeyDecision.PASS, t.onKey(KEYCODE_MEDIA_NEXT, true, 0, SecondScreenConfig(), true))
        assertEquals(KeyDecision.PASS, t.onKey(KEYCODE_MEDIA_NEXT, false, 0, SecondScreenConfig(), true))
    }

    @Test
    fun aShortMediaPressStillSkipsTheTrack() {
        val t = ClusterKeyTracker()
        assertEquals(KeyDecision.SWALLOW, t.onKey(KEYCODE_MEDIA_NEXT, true, 0, mediaPages, true))
        assertEquals(KeyDecision(true, ClusterKeyAction.SKIP_NEXT), t.onKey(KEYCODE_MEDIA_NEXT, false, 0, mediaPages, true))
        assertEquals(KeyDecision.SWALLOW, t.onKey(KEYCODE_MEDIA_PREVIOUS, true, 0, mediaPages, true))
        assertEquals(KeyDecision(true, ClusterKeyAction.SKIP_PREVIOUS), t.onKey(KEYCODE_MEDIA_PREVIOUS, false, 0, mediaPages, true))
    }

    @Test
    fun aHeldMediaKeyTurnsThePageOnce() {
        val t = ClusterKeyTracker()
        t.onKey(KEYCODE_MEDIA_PREVIOUS, true, 0, mediaPages, true)
        assertEquals(KeyDecision(true, ClusterKeyAction.PREVIOUS_PAGE), t.onKey(KEYCODE_MEDIA_PREVIOUS, true, 1, mediaPages, true))
        assertEquals(KeyDecision.SWALLOW, t.onKey(KEYCODE_MEDIA_PREVIOUS, true, 2, mediaPages, true))
        // Released after turning the page: no skip.
        assertEquals(KeyDecision.SWALLOW, t.onKey(KEYCODE_MEDIA_PREVIOUS, false, 0, mediaPages, true))
    }

    @Test
    fun anUpWithoutItsDownGoesToThePlayer() {
        val t = ClusterKeyTracker()
        assertEquals(KeyDecision.PASS, t.onKey(KEYCODE_MEDIA_NEXT, false, 0, mediaPages, true))
    }

    @Test
    fun learningTakesTheNextKeyButNeverBack() {
        val t = ClusterKeyTracker()
        var got: Int? = null
        t.learning = { got = it }
        assertEquals(KeyDecision.PASS, t.onKey(KEYCODE_BACK, true, 0, SecondScreenConfig(), false))
        assertEquals(KeyDecision.SWALLOW, t.onKey(KEY_MODE, true, 0, SecondScreenConfig(), false))
        assertEquals(KEY_MODE, got)
        // Learning is over: the next press is an ordinary key again.
        assertEquals(KeyDecision.PASS, t.onKey(KEY_MODE, false, 0, SecondScreenConfig(), false))
    }

    private companion object {
        /** A key code a steering-wheel button might send. */
        const val KEY_MODE = 55
    }
}
