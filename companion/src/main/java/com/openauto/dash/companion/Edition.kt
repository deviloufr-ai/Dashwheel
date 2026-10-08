package com.openauto.dash.companion

/**
 * Which edition this build is (see companion/build.gradle.kts). The GitHub
 * edition updates itself from the GitHub release; the Google Play edition
 * leaves that to Play and asks for no install permission.
 */
object Edition {
    /** True in the Google Play edition. */
    val play: Boolean get() = BuildConfig.PLAY

    /** True in the GitHub edition: self-updating from the release. */
    val full: Boolean get() = !BuildConfig.PLAY
}
