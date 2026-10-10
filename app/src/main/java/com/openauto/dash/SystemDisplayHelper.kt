package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.system.Os
import android.util.Log
import android.view.Surface
import kotlin.system.exitProcess

/**
 * The tile displays' owner, as the system user. Runs in a process of its own
 * started through root (`app_process`, see [SystemDisplays]).
 *
 * Android 10 lets an app open a screen on a virtual display only when the
 * display is its own, the system's, or the app declares `allowEmbedded`. The
 * first screen of an app in a tile gets there because Dashwheel opens it
 * with a system permission; every screen the app opened from there by
 * itself (Waze's trip overview, its search results, its voice prompt) was
 * refused the display and thrown full screen onto the main screen, task and
 * all. A display owned by the system user keeps every screen of the apps on
 * it.
 *
 * Started as root, drops to the system user, makes and sizes the displays
 * Dashwheel asks for over Binder, and hands its Binder to Dashwheel in a
 * broadcast carrying the token it was started with. Exits when Dashwheel's
 * process dies, releasing its displays with it (their apps close, as they do
 * when Dashwheel's own displays go).
 */
object SystemDisplayHelper {
    private const val TAG = "SystemDisplayHelper"

    const val ACTION = "com.openauto.dash.SYSTEM_DISPLAYS"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_TOKEN = "token"
    const val DESCRIPTOR = "com.openauto.dash.SystemDisplays"

    const val HELLO = 1
    const val CREATE = 2
    const val RESIZE = 3
    const val SURFACE = 4
    const val RELEASE = 5
    const val PING = 6

    private const val SYSTEM_UID = 1000

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.getOrNull(0).orEmpty()
        val pkg = args.getOrNull(1) ?: "com.openauto.dash"
        try {
            run(token, pkg, selfTest = "--self-test" in args)
        } catch (e: Throwable) {
            Log.e(TAG, "died", e)
            System.err.println("SystemDisplayHelper: $e")
            exitProcess(1)
        }
    }

    private fun run(token: String, pkg: String, selfTest: Boolean) {
        Looper.prepareMainLooper()
        Os.setgid(SYSTEM_UID)
        Os.setuid(SYSTEM_UID)
        val context = systemContext()
        val dm = context.getSystemService(DisplayManager::class.java) ?: error("no DisplayManager")
        val server = Server(dm)
        if (selfTest) {
            selfTest(dm)
        } else {
            val intent = Intent(ACTION).setPackage(pkg).putExtras(Bundle().apply {
                putBinder(EXTRA_BINDER, server)
                putString(EXTRA_TOKEN, token)
            })
            broadcast(intent)
            Log.i(TAG, "up as uid ${Os.getuid()}, handed to $pkg")
        }
        Looper.loop()
    }

    /** The system's own context: the package "android", which the system user owns. */
    private fun systemContext(): Context {
        val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)!!
        return thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
    }

    /** [intent] to Dashwheel, straight through the activity manager (there is no app context to send it from). */
    private fun broadcast(intent: Intent) {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val method = am.javaClass.methods.first { it.name == "broadcastIntent" && it.parameterTypes.size == 13 }
        // caller, intent, resolvedType, resultTo, resultCode, resultData, map, requiredPermissions, appOp, options, serialized, sticky, userId
        method.invoke(am, null, intent, null, null, -1, null, null, null, -1, null, true, false, 0)
    }

    /** A display drawn into a reader nobody looks at, its id printed: to try the launches on it from a shell. */
    private fun selfTest(dm: DisplayManager) {
        val reader = ImageReader.newInstance(522, 638, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val vd = dm.createVirtualDisplay("Dashwheel:test", 522, 638, 160, reader.surface, SystemDisplays.FLAGS)
            ?: error("no display")
        println("display ${vd.display.displayId} as uid ${Os.getuid()}")
        System.out.flush()
    }

    private class Server(private val dm: DisplayManager) : Binder() {
        private val displays = HashMap<Int, VirtualDisplay>()
        private val surfaces = HashMap<Int, Surface?>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in HELLO..PING) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            try {
                when (code) {
                    HELLO -> {
                        val client = data.readStrongBinder()
                        client?.linkToDeath({
                            Log.i(TAG, "Dashwheel went: releasing ${displays.size} display(s)")
                            releaseAll()
                            exitProcess(0)
                        }, 0)
                        reply?.writeNoException()
                    }
                    CREATE -> {
                        val name = data.readString() ?: "Dashwheel"
                        val width = data.readInt()
                        val height = data.readInt()
                        val dpi = data.readInt()
                        val displayFlags = data.readInt()
                        val surface = readSurface(data)
                        val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, displayFlags) ?: error("no display for $name")
                        val id = vd.display.displayId
                        synchronized(displays) {
                            displays[id] = vd
                            surfaces[id] = surface
                        }
                        Log.i(TAG, "display $id: $name ${width}x$height at $dpi dpi")
                        reply?.writeNoException()
                        reply?.writeInt(id)
                    }
                    RESIZE -> {
                        val id = data.readInt()
                        val width = data.readInt()
                        val height = data.readInt()
                        val dpi = data.readInt()
                        find(id).resize(width, height, dpi)
                        reply?.writeNoException()
                    }
                    SURFACE -> {
                        val id = data.readInt()
                        val surface = readSurface(data)
                        val vd = find(id)
                        vd.surface = surface
                        synchronized(displays) { surfaces.put(id, surface) }?.release()
                        reply?.writeNoException()
                    }
                    RELEASE -> {
                        val id = data.readInt()
                        val vd = synchronized(displays) { surfaces.remove(id)?.release(); displays.remove(id) }
                        vd?.release()
                        Log.i(TAG, "display $id released")
                        reply?.writeNoException()
                    }
                    PING -> {
                        reply?.writeNoException()
                        reply?.writeInt(Os.getuid())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "transaction $code failed", e)
                reply?.writeException(IllegalStateException(e.toString()))
            }
            return true
        }

        private fun find(id: Int): VirtualDisplay =
            synchronized(displays) { displays[id] } ?: throw IllegalStateException("no display $id")

        private fun readSurface(data: Parcel): Surface? =
            if (data.readInt() != 0) Surface.CREATOR.createFromParcel(data) else null

        private fun releaseAll() {
            val all = synchronized(displays) { displays.values.toList().also { displays.clear(); surfaces.clear() } }
            all.forEach { runCatching { it.release() } }
        }
    }
}
