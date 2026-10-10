package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFilterTest {

    @Test
    fun theKeysPassThroughTheServiceOnlyWhenSomethingNeedsThem() {
        // The K706 with wheel buttons assigned and its own key service: the firmware's keys are left alone.
        assertFalse(keysWanted(learning = false, wheelBound = true, unitKeys = true, clusterKeys = false, keysAway = false))
        // A unit without a key service of its own: the wheel's buttons only come through the filter.
        assertTrue(keysWanted(learning = false, wheelBound = true, unitKeys = false, clusterKeys = false, keysAway = false))
        assertFalse(keysWanted(learning = false, wheelBound = false, unitKeys = false, clusterKeys = false, keysAway = false))
        // Learning a button, the second screen's page keys, an app in a tile holding the keys: filtered.
        assertTrue(keysWanted(learning = true, wheelBound = false, unitKeys = true, clusterKeys = false, keysAway = false))
        assertTrue(keysWanted(learning = false, wheelBound = false, unitKeys = true, clusterKeys = true, keysAway = false))
        assertTrue(keysWanted(learning = false, wheelBound = false, unitKeys = true, clusterKeys = false, keysAway = true))
    }
}
