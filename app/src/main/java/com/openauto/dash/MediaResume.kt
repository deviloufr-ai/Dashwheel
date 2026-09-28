package com.openauto.dash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * The music carries on where it stopped: when the car is switched off with
 * an app playing, that app is told to play again when the car starts, without
 * opening its screen. An option (Settings, Driving), off until the driver
 * turns it on: some units resume their own players by themselves, and two
 * resumes would fight. The unit's own Bluetooth music and radio are the
 * unit's business and left alone.
 */
internal object MediaResume {
    private const val TAG = "MediaResume"
    private const val PREFS = "media_resume"
    private const val KEY_ON = "on"
    private const val KEY_PACKAGE = "package"

    /** Playing this shortly before the switch-off counts: the unit may pause it as the key turns. */
    const val PLAYING_BEFORE_OFF_MS = 30_000L

    /** Waited after the start: the app, the Bluetooth and the unit's own resume settle first. */
    private const val SETTLE_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    private var loaded = false

    // The app last seen playing, and when (elapsed realtime), from the media tile's controller.
    @Volatile private var playingPackage: String? = null
    @Volatile private var playingAt = 0L

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _on.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    }

    fun save(context: Context, on: Boolean) {
        _on.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    /** From [CarMediaController]: [packageName]'s session is (or isn't) playing. */
    fun note(packageName: String?, playing: Boolean) {
        if (!playing || packageName == null || HeadUnitMedia.isStock(packageName)) return
        playingPackage = packageName
        playingAt = SystemClock.elapsedRealtime()
    }

    /** The car is switched off: the app to resume at the next start, or none. */
    fun carStopped(context: Context) {
        if (DemoMode.isOn) return
        val pkg = playingPackage.takeIf { wasPlaying(playingAt, SystemClock.elapsedRealtime()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PACKAGE, pkg).apply()
    }

    /** The car starts: the app that was playing plays again, if the option is on and nothing else plays. */
    fun carStarted(context: Context) {
        load(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_PACKAGE, null) ?: return
        prefs.edit().remove(KEY_PACKAGE).apply()
        if (!_on.value) return
        val app = context.applicationContext
        scope.launch {
            delay(SETTLE_MS)
            resume(app, pkg)
        }
    }

    private fun resume(context: Context, pkg: String) {
        val sessions = runCatching {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            manager.getActiveSessions(ComponentName(context, MediaNotificationListenerService::class.java))
        }.getOrNull().orEmpty()
        // Something plays already (the unit's radio, the driver was quicker): left alone.
        if (sessions.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }) return
        val session = sessions.firstOrNull { it.packageName == pkg }
        if (session != null) {
            runCatching { session.transportControls.play() }
            Log.i(TAG, "resumed $pkg")
            return
        }
        // Its session is gone (the app was stopped overnight): its media button, as a key on the wheel would.
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            runCatching { context.sendBroadcast(intent) }
        }
        Log.i(TAG, "asked $pkg to play")
    }
}

/** Whether music last heard playing at [playingAt] was still on at a switch-off at [now]. */
internal fun wasPlaying(playingAt: Long, now: Long): Boolean =
    playingAt > 0 && now - playingAt in 0..MediaResume.PLAYING_BEFORE_OFF_MS
