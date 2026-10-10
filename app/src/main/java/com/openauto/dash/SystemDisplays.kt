package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The tile displays made by the system user ([SystemDisplayHelper]) rather
 * than by Dashwheel itself, so that the apps on them keep every screen they
 * open there. Needs root: the helper is started through it, as a process of
 * its own, and hands its Binder back in a broadcast. Without root, or until
 * the helper answers, the tiles use Dashwheel's own displays as before.
 */
internal object SystemDisplays {
    private const val TAG = "SystemDisplays"

    /** How long the helper gets to answer before the tiles stop waiting for it. */
    private const val START_TIMEOUT_MS = 10_000L

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*: the app's content only, never a
    // mirror, and the app closed with the display instead of thrown onto the
    // main screen (DESTROY_CONTENT_ON_REMOVAL is @hide). Not public: a private
    // display takes the apps already on it and nobody else's presentations.
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val FLAGS = FLAG_OWN_CONTENT_ONLY or FLAG_DESTROY_CONTENT_ON_REMOVAL

    enum class State {
        /** Not started (the Play edition, or not asked yet). */
        OFF,
        /** The helper is being started: a tile waits for it before making its display. */
        STARTING,
        /** The helper answers: the tile displays are the system's. */
        READY,
        /** No root, the helper failed to start, or it died: Dashwheel's own displays. */
        GONE
    }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()
    val ready: Boolean get() = _state.value == State.READY

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val clientToken = Binder()

    @Volatile private var binder: IBinder? = null
    @Volatile private var token = ""
    private var started = false

    fun start(context: Context) {
        if (started || Edition.play) return
        started = true
        val app = context.applicationContext
        ContextCompat.registerReceiver(app, receiver, IntentFilter(SystemDisplayHelper.ACTION), ContextCompat.RECEIVER_EXPORTED)
        _state.value = State.STARTING
        scope.launch(Dispatchers.IO) { spawn(app) }
        main.postDelayed({
            if (_state.value == State.STARTING) {
                Log.w(TAG, "the helper did not answer in ${START_TIMEOUT_MS / 1000} s: the tile displays stay Dashwheel's own")
                _state.value = State.GONE
            }
        }, START_TIMEOUT_MS)
    }

    /** Waits until the helper answered or was given up on; a tile makes its display after. */
    suspend fun awaitDecided() {
        withTimeoutOrNull(START_TIMEOUT_MS + 1_000) { _state.first { it != State.STARTING } }
    }

    private fun spawn(app: Context) {
        if (!SystemInstaller.isRootAvailable()) {
            Log.i(TAG, "no root: the tile displays stay Dashwheel's own")
            main.post { if (_state.value == State.STARTING) _state.value = State.GONE }
            return
        }
        val fresh = java.util.UUID.randomUUID().toString().replace("-", "")
        token = fresh
        val cmd = helperCommand(app.applicationInfo.sourceDir, app.packageName, fresh)
        val out = runCatching { RootShell.su(cmd, 8) }.getOrNull()
        if (out == null || out.exit != 0) {
            Log.w(TAG, "the helper could not be started: ${out?.all?.trim()?.lines()?.firstOrNull() ?: "no root shell"}")
            main.post { if (_state.value == State.STARTING) _state.value = State.GONE }
        }
    }

    /** The root shell line that starts the helper detached, so it outlives the shell. Pure, for the tests. */
    internal fun helperCommand(apk: String, packageName: String, token: String): String =
        "nohup sh -c 'CLASSPATH=$apk exec app_process /system/bin ${SystemDisplayHelper::class.java.name} $token $packageName' </dev/null >/dev/null 2>&1 &"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val got = intent.getStringExtra(SystemDisplayHelper.EXTRA_TOKEN)
            if (token.isEmpty() || got != token) return
            val b = intent.extras?.getBinder(SystemDisplayHelper.EXTRA_BINDER) ?: return
            try {
                transact(b, SystemDisplayHelper.HELLO, { it.writeStrongBinder(clientToken) })
                b.linkToDeath({ lost() }, 0)
            } catch (e: Exception) {
                Log.w(TAG, "the helper answered but can't be talked to", e)
                _state.value = State.GONE
                return
            }
            binder = b
            _state.value = State.READY
            Log.i(TAG, "the tile displays are the system's from now on")
        }
    }

    private fun lost() {
        binder = null
        _state.value = State.GONE
        Log.w(TAG, "the helper died: its displays went with it, the tiles make their own")
        main.post { EmbeddedApp.systemDisplaysLost() }
    }

    /** A display of [width]x[height] at [dpi] drawn on [surface]: its id, or null when the helper refused. */
    fun create(name: String, width: Int, height: Int, dpi: Int, surface: Surface?): Int? {
        val b = binder ?: return null
        var id = -1
        return runCatching {
            transact(b, SystemDisplayHelper.CREATE, {
                it.writeString(name)
                it.writeInt(width)
                it.writeInt(height)
                it.writeInt(dpi)
                it.writeInt(FLAGS)
                writeSurface(it, surface)
            }) { id = it.readInt() }
            id
        }.onFailure { Log.w(TAG, "no system display for $name", it) }.getOrNull()
    }

    fun resize(id: Int, width: Int, height: Int, dpi: Int) {
        val b = binder ?: return
        runCatching {
            transact(b, SystemDisplayHelper.RESIZE, { it.writeInt(id); it.writeInt(width); it.writeInt(height); it.writeInt(dpi) })
        }.onFailure { Log.w(TAG, "display $id not resized", it) }
    }

    fun setSurface(id: Int, surface: Surface?) {
        val b = binder ?: return
        runCatching {
            transact(b, SystemDisplayHelper.SURFACE, { it.writeInt(id); writeSurface(it, surface) })
        }.onFailure { Log.w(TAG, "display $id not given its picture", it) }
    }

    fun release(id: Int) {
        val b = binder ?: return
        runCatching {
            transact(b, SystemDisplayHelper.RELEASE, { it.writeInt(id) })
        }.onFailure { Log.w(TAG, "display $id not released", it) }
    }

    private fun writeSurface(p: Parcel, surface: Surface?) {
        if (surface == null) {
            p.writeInt(0)
        } else {
            p.writeInt(1)
            surface.writeToParcel(p, 0)
        }
    }

    private fun transact(b: IBinder, code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> Unit = {}) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SystemDisplayHelper.DESCRIPTOR)
            write(data)
            if (!b.transact(code, data, reply, 0)) error("transaction $code not handled")
            reply.readException()
            read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

/**
 * A tile's display: made by the system user through [SystemDisplays] when
 * the helper is up, by Dashwheel itself otherwise. The same handful of calls
 * either way, so the tile host needn't care which.
 */
internal sealed class TileDisplay {
    abstract val displayId: Int

    /** Owned by the system user: gone when the helper dies ([EmbeddedApp.systemDisplaysLost]). */
    abstract val system: Boolean
    abstract fun resize(width: Int, height: Int, dpi: Int)
    abstract var surface: Surface?
    abstract fun release()

    class Own(private val vd: VirtualDisplay) : TileDisplay() {
        override val displayId: Int get() = vd.display.displayId
        override val system = false
        override fun resize(width: Int, height: Int, dpi: Int) = vd.resize(width, height, dpi)
        override var surface: Surface?
            get() = vd.surface
            set(value) { vd.surface = value }
        override fun release() = vd.release()
    }

    class System(override val displayId: Int, private var current: Surface?) : TileDisplay() {
        override val system = true
        override fun resize(width: Int, height: Int, dpi: Int) = SystemDisplays.resize(displayId, width, height, dpi)
        override var surface: Surface?
            get() = current
            set(value) {
                current = value
                SystemDisplays.setSurface(displayId, value)
            }
        override fun release() = SystemDisplays.release(displayId)
    }

    companion object {
        private const val TAG = "TileDisplay"

        /** The system's display when the helper is up, else Dashwheel's own with [ownFlags]; throws when neither can be made. */
        fun create(context: Context, name: String, width: Int, height: Int, dpi: Int, surface: Surface, ownFlags: Int): TileDisplay {
            if (SystemDisplays.ready) {
                SystemDisplays.create(name, width, height, dpi, surface)?.let { return System(it, surface) }
                Log.w(TAG, "$name: the system refused a display, Dashwheel's own instead")
            }
            val dm = context.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, ownFlags) ?: error("no display for $name")
            return Own(vd)
        }
    }
}
