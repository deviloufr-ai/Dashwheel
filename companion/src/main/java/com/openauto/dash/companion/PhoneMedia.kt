package com.openauto.dash.companion

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.openauto.dash.link.NowPlaying
import java.io.ByteArrayOutputStream

/**
 * What the phone plays, for the car's media tile: the unit's Bluetooth
 * passes the sound but not the cover (and often not the title), so the
 * companion reads the phone's own media sessions, through its notification
 * access, and sends title, artist and cover over the link ([NowPlaying]).
 */
object PhoneMedia {
    /** Covers go as a JPEG this wide at most: sharp on the tile, a few dozen KB on the link. */
    private const val ART_PX = 320
    /** Title and state changes come in bursts; one message for each burst. */
    private const val SETTLE_MS = 300L

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    /** The cover the car has, so it is sent once per track. */
    private var sentArt = 0
    private var last: NowPlaying? = null

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
        override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
        override fun onSessionDestroyed() {
            controller?.unregisterCallback(this)
            controller = null
            changed()
        }
    }

    /** Follows the phone's media while the link service runs; needs notification access. */
    fun start(context: Context) = main.post {
        if (appContext != null) return@post
        val app = context.applicationContext
        val m = app.getSystemService(MediaSessionManager::class.java) ?: return@post
        val listener = ComponentName(app, PhoneNotificationListener::class.java)
        val active = try {
            m.addOnActiveSessionsChangedListener(sessions, listener, main)
            m.getActiveSessions(listener)
        } catch (e: SecurityException) {
            // No notification access yet: tried again when the link comes up.
            return@post
        }
        appContext = app
        manager = m
        follow(active)
    }

    fun stop() = main.post {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessions) }
        controller?.unregisterCallback(callback)
        controller = null
        manager = null
        appContext = null
        last = null
    }

    /** The link just came up: the car has no cover yet, so everything goes again. */
    fun linked(context: Context) = main.post {
        if (appContext == null) start(context)
        sentArt = 0
        last = null
        changed()
    }

    /** Follows the session that is playing, else the first one. */
    private fun follow(active: List<MediaController>) {
        val pick = active.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: active.firstOrNull()
        if (pick?.sessionToken == controller?.sessionToken) return changed()
        controller?.unregisterCallback(callback)
        controller = pick
        pick?.registerCallback(callback, main)
        changed()
    }

    private fun changed() {
        main.removeCallbacks(send)
        main.postDelayed(send, SETTLE_MS)
    }

    private val send = Runnable {
        val c = controller
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val art = meta?.let { it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) }
        val artKey = art?.let { artKeyOf(it) } ?: 0
        val message = NowPlaying(
            title = title.orEmpty(),
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING,
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            artKey = artKey
        )
        if (message == last) return@Runnable
        last = message
        val withArt = if (art != null && artKey != sentArt) message.copy(artJpeg = jpeg(art)) else message
        LinkServer.send(withArt)
        if (withArt.artJpeg != null) sentArt = artKey
    }

    /** Tells covers apart cheaply: size and a few pixels. */
    private fun artKeyOf(b: Bitmap): Int {
        var h = 31 * b.width + b.height
        for (i in 1..8) {
            val x = (b.width * i / 9).coerceIn(0, b.width - 1)
            val y = (b.height * i / 9).coerceIn(0, b.height - 1)
            h = 31 * h + runCatching { b.getPixel(x, y) }.getOrDefault(0)
        }
        return if (h == 0) 1 else h
    }

    private fun jpeg(b: Bitmap): String? = runCatching {
        val scale = minOf(1f, ART_PX.toFloat() / maxOf(b.width, b.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true) else b
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 82, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
