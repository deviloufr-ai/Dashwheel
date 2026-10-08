package com.openauto.dash

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Self-installs the app into `/system/priv-app` over the head unit's **internal
 * wireless ADB** socket (ROCO/K706: fixed `service.adb.tcp.port` = 9876 in
 * build.prop). This is how some OEM launchers self-privilege.
 *
 * Steps: connect (with an app-owned RSA key), make sure `adbd` is **root**
 * (`adb root` restart — the same thing an external "adb root" does), push the
 * APK to a writable temp dir, then root-`cp` it into `/system/priv-app` and fix
 * perms + SELinux label. Pushing straight into `/system` fails ("sync FAIL")
 * even when connected, so we always stage through `/data/local/tmp`.
 */
object AdbInstaller {

    const val DEFAULT_PORT = 9876
    private const val HOST = "127.0.0.1"
    private const val TMP = "/data/local/tmp/OpenAutoDash.apk"

    /** A hung adbd (or an authorization prompt nobody answers) gives up after this, instead of hanging the caller. */
    internal const val TIMEOUT_MS = 10_000

    /** For connections that push a file or run a long script (copying the APK into /system, writing the logo). */
    internal const val LONG_TIMEOUT_MS = 120_000

    /**
     * A connection to the unit's adbd with the app's own key. Connecting and
     * every read give up after [connectTimeoutMs] / [readTimeoutMs], so nothing
     * waits forever on a stuck socket.
     */
    internal fun connect(
        context: Context,
        port: Int,
        connectTimeoutMs: Int = TIMEOUT_MS,
        readTimeoutMs: Int = connectTimeoutMs
    ): Dadb {
        val keyDir = File(context.filesDir, "adb").apply { mkdirs() }
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            AdbKeyPair.generate(priv, pub)
        }
        val keyPair = AdbKeyPair.read(priv, pub)
        return Dadb.create(HOST, port, keyPair, connectTimeoutMs, readTimeoutMs)
    }

    /**
     * A shell on the unit's adbd for [DockShell]: one connection, kept until
     * closed, each command read within [timeoutMs].
     */
    internal fun openShell(context: Context, port: Int, timeoutMs: Int): DockShell.AdbShell {
        val dadb = connect(context, port, timeoutMs)
        return object : DockShell.AdbShell {
            override fun run(cmd: String): String = dadb.shell(cmd).let { it.output + it.errorOutput }
            override fun close() = dadb.close()
        }
    }

    /** The unit's ADB TCP port from `service.adb.tcp.port`, else the K706 default. */
    fun announcedPort(): Int = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tcp.port"))
        p.inputStream.bufferedReader().use { it.readText() }.trim().toIntOrNull()
    }.getOrNull() ?: DEFAULT_PORT

    /**
     * The port adbd answers on at 127.0.0.1: the one the unit announces, else
     * the K706 default or the stock 5555. Null when nothing listens (ADB is off),
     * found in a second instead of a failed connect deep inside an install.
     */
    internal fun listeningPort(): Int? =
        listOfNotNull(announcedPort(), DEFAULT_PORT, 5555).distinct().firstOrNull { port ->
            runCatching { Socket().use { it.connect(InetSocketAddress(HOST, port), PROBE_TIMEOUT_MS) } }.isSuccess
        }

    private const val PROBE_TIMEOUT_MS = 1_000

    private fun currentUid(context: Context, port: Int): String? =
        runCatching { connect(context, port).use { it.shell("id -u").output.trim() } }.getOrNull()

    /** Restart adbd as root (like `adb root`), then wait for it to come back. */
    internal fun ensureRoot(context: Context, port: Int): Boolean {
        if (currentUid(context, port) == "0") return true
        runCatching {
            connect(context, port).use { dadb ->
                val stream = dadb.open("root:")
                runCatching { stream.close() }
            }
        }
        repeat(10) {
            Thread.sleep(1000)
            if (currentUid(context, port) == "0") return true
        }
        return false
    }

    fun installViaAdb(context: Context, port: Int = DEFAULT_PORT): Result<Unit> = runCatching {
        val apk = File(context.applicationInfo.sourceDir)
        ensureRoot(context, port)
        // Pushing the APK and copying it into /system (then sync) take a while on this unit.
        connect(context, port, readTimeoutMs = LONG_TIMEOUT_MS).use { dadb ->
            val uid = dadb.shell("id -u").output.trim()
            // Stage the APK in a writable temp dir (sync straight to /system FAILs).
            dadb.push(apk, TMP)
            // /system is often 100% full on these units, so try every privileged
            // partition Android scans and install into the first one with space.
            val res = dadb.shell(INSTALL_SCRIPT)
            val ok = res.allOutput.lineSequence().firstOrNull { it.startsWith("OKINSTALL:") }
            if (ok == null) {
                val detail = res.allOutput.trim().ifBlank { context.getString(R.string.sys_install_failed) }
                error(if (uid != "0") context.getString(R.string.sys_install_failed_not_root, detail, uid) else detail)
            }
            Log.d("AdbInstaller", "Installed via ADB :$port (uid=$uid): ${ok.removePrefix("OKINSTALL:")}")
        }
        PrivApp.compileInBackground { cmd ->
            connect(context, port, readTimeoutMs = PrivApp.COMPILE_TIMEOUT_MS).use { it.shell(cmd).allOutput }
        }
    }

    // Tries each privileged partition; installs into the first that has room.
    // The privileged-permission whitelist goes into that partition's
    // etc/permissions, as the Magisk path does: on a ROM that enforces the
    // whitelist (ro.control_privapp_permissions=enforce) a priv-app without one
    // stops the system from booting. Afterwards the staged APK is removed and
    // the partitions go back to read-only (best effort).
    // Uses \$ for shell variables so Kotlin doesn't interpolate them.
    private val INSTALL_SCRIPT: String = buildString {
        append("TMP=$TMP; ERR=/data/local/tmp/oad_err; : > \$ERR; ")
        // Without the whitelist a priv-app only stops the boot where the ROM
        // enforces it; elsewhere a failed write must not fail an install that
        // worked before the whitelist was added.
        append("ENF=\$(getprop ro.control_privapp_permissions); ")
        append("for BASE in /system /product /system_ext /vendor /odm; do ")
        append("  DIR=\$BASE/priv-app/OpenAutoDash; PERM=\$BASE/etc/permissions; XML=\$PERM/${PrivApp.PRIVAPP_XML_NAME}; ")
        append("  mount -o remount,rw \$BASE 2>>\$ERR; mount -o remount,rw / 2>>\$ERR; ")
        append("  if mkdir -p \"\$DIR\" 2>>\$ERR && cp \"\$TMP\" \"\$DIR/OpenAutoDash.apk\" 2>>\$ERR; then ")
        append("    if mkdir -p \"\$PERM\" 2>>\$ERR && echo '${PrivApp.PRIVAPP_XML}' > \"\$XML\" 2>>\$ERR; then ")
        append("      chmod 644 \"\$XML\" 2>>\$ERR; chcon u:object_r:system_file:s0 \"\$XML\" 2>>\$ERR; ")
        append("    elif [ \"\$ENF\" = enforce ]; then rm -rf \"\$DIR\" 2>/dev/null; rm -f \"\$XML\" 2>/dev/null; continue; ")
        append("    else rm -f \"\$XML\" 2>/dev/null; fi; ")
        append("    chmod 755 \"\$DIR\" 2>>\$ERR; chmod 644 \"\$DIR/OpenAutoDash.apk\" 2>>\$ERR; ")
        append("    chcon u:object_r:system_file:s0 \"\$DIR/OpenAutoDash.apk\" 2>>\$ERR; sync; ")
        append("    echo \"OKINSTALL:\$DIR\"; break; ")
        append("  else rm -rf \"\$DIR\" 2>/dev/null; fi; ")
        append("done; ")
        append("rm -f \$TMP; ")
        append("for BASE in /system /product /system_ext /vendor /odm /; do mount -o remount,ro \$BASE 2>/dev/null; done; ")
        append("echo '---DIAG---'; df /system /product /system_ext /vendor /odm 2>/dev/null; ")
        append("echo '---ERR---'; cat \$ERR 2>/dev/null")
    }

    fun rebootViaAdb(context: Context, port: Int = DEFAULT_PORT): Result<Unit> = runCatching {
        ensureRoot(context, port)
        connect(context, port).use { it.shell("svc power reboot || reboot") }
        Unit
    }
}
