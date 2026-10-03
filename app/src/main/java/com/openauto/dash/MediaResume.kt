package com.openauto.dash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
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

    // The app last seen playing, and when it was last known to (elapsed realtime), from the media tile's controller.
    @Volatile private var playingPackage: String? = null
    @Volatile private var playingAt = 0L
    @Volatile private var stillPlaying = false

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
        if (packageName == null || HeadUnitMedia.isStock(packageName)) return
        if (playing) {
            playingPackage = packageName
            playingAt = SystemClock.elapsedRealtime()
            stillPlaying = true
        } else if (stillPlaying && packageName == playingPackage) {
            // It stops now: this is when it was last playing, not its last word
            // while it played (a player says nothing for the length of a track).
            stillPlaying = false
            playingAt = SystemClock.elapsedRealtime()
        }
    }

    /** The car is switched off: the app to resume at the next start, or none. */
    fun carStopped(context: Context) {
        if (DemoMode.isOn) return
        // Playing right now, whatever was last heard from it; else paused a
        // moment ago, as the unit pauses the music when the key turns: by its
        // player's own word, or as the media tile saw it.
        val now = SystemClock.elapsedRealtime()
        val players = sessions(context).filter { !HeadUnitMedia.isStock(it.packageName) }
        val pkg = players.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.packageName
            ?: players.firstOrNull { player ->
                player.playbackState?.let { it.state == PlaybackState.STATE_PAUSED && wasPlaying(it.lastPositionUpdateTime, now) } == true
            }?.packageName
            ?: playingPackage.takeIf { wasPlaying(playingAt, now) }
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

    /** The media sessions open now; none without the notification access they are read through. */
    private fun sessions(context: Context): List<MediaController> = runCatching {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager.getActiveSessions(ComponentName(context, MediaNotificationListenerService::class.java))
    }.getOrNull().orEmpty()

    private fun resume(context: Context, pkg: String) {
        val sessions = sessions(context)
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
