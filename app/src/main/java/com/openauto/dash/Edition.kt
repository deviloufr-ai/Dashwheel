package com.openauto.dash

/**
 * Which edition this build is. The GitHub edition is the full launcher, root and
 * firmware hooks included. The Play edition leaves out everything that needs
 * root, the unit's internal ADB or PMPatch3, and everything Google Play does not
 * allow a launcher to do (install its own updates, send SMS, read the call log).
 * Every such feature checks [play] once at its entry point, so the rest of the
 * code stays shared.
 */
object Edition {
    /** True in the Google Play edition. */
    val play: Boolean get() = BuildConfig.PLAY

    /** True in the GitHub edition: root, ADB and firmware features may be offered. */
    val full: Boolean get() = !BuildConfig.PLAY
}
