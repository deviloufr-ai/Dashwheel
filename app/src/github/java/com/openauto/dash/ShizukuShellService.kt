package com.openauto.dash

import android.os.Bundle
import android.os.Process
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The service Shizuku starts for Dashwheel ([ShizukuShell] binds it): a
 * process of its own, run from this APK by `app_process` as Shizuku's user,
 * the shell (uid 2000) over wireless debugging or ADB, root where Shizuku was
 * started by root. One command at a time ([ShizukuShell] serialises), each
 * bounded by its caller's timeout, both streams read together so a full pipe
 * cannot stall it. No Application here: only what a bare process has.
 */
class ShizukuShellService : IShizukuShell.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun uid(): Int = Process.myUid()

    override fun run(cmd: String, timeoutS: Int): Bundle {
        val result = Bundle()
        val process = try {
            ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            Log.w(TAG, "could not start `$cmd`: ${e.message}")
            return result.apply { putString(OUT, "Error: ${e.message}"); putInt(EXIT, -1) }
        }
        // Nothing is ever typed to it: a command that reads its input would otherwise wait for the timeout.
        runCatching { process.outputStream.close() }
        // Read on its own thread, so the wait below can run out while the command still prints.
        val out = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8 * 1024)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        // Binder carries about a megabyte per call: keep well under it.
                        if (out.length < MAX_OUT) out.append(buf, 0, minOf(n, MAX_OUT - out.length))
                    }
                }
            }
        }, "shizuku-shell-out").apply { isDaemon = true; start() }
        val exit = if (process.waitFor(timeoutS.toLong().coerceAtLeast(1L), TimeUnit.SECONDS)) {
            reader.join(2_000)
            process.exitValue()
        } else {
            process.destroyForcibly()
            reader.join(500)
            Log.w(TAG, "`${cmd.take(80)}` ran out of time (${timeoutS}s)")
            out.append("\nError: timed out after ${timeoutS}s")
            -1
        }
        return result.apply { putString(OUT, out.toString()); putInt(EXIT, exit) }
    }

    companion object {
        private const val TAG = "ShizukuShellService"
        const val OUT = "out"
        const val EXIT = "exit"
        private const val MAX_OUT = 512 * 1024
    }
}
