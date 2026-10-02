package com.openauto.dash.display

import com.openauto.dash.link.DisplayHello
import java.io.File
import kotlin.system.exitProcess

/**
 * `dashwheel-display [--config DIR] [--print-pairing]`
 *
 * DIR defaults to /boot/firmware/dashwheel, the FAT partition any computer can
 * edit. `--print-pairing` makes the pairing if there is none yet, prints its
 * code and exits (the installer does this before the card goes read-only).
 */
fun main(args: Array<String>) {
    var dir = File("/boot/firmware/dashwheel")
    var printPairing = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--config" -> dir = File(args.getOrNull(++i) ?: usage())
            "--print-pairing" -> printPairing = true
            else -> usage()
        }
        i++
    }

    val config = DisplayConfig.load(dir)
    val pairing = DisplayPairing(dir, config.name)
    if (printPairing) {
        println(pairing.offer.toUri())
        return
    }

    val detected = ScreenMode.detect()
    val mode = if (config.width != null && config.height != null) ScreenMode(config.width, config.height)
    else detected ?: ScreenMode.FALLBACK
    log("screen ${mode.width}x${mode.height}" + if (detected == null) " (none detected, assumed)" else "")

    val hello = DisplayHello(
        name = config.name,
        appVersion = DisplayServer::class.java.`package`?.implementationVersion ?: "dev",
        width = mode.width,
        height = mode.height,
        refreshHz = mode.refreshHz,
        overscanPct = config.overscanPct,
        model = runCatching { File("/proc/device-tree/model").readText().trim('\u0000', ' ', '\n') }.getOrDefault("")
    )
    val server = DisplayServer(config, pairing, hello)
    Runtime.getRuntime().addShutdownHook(Thread { server.screen.stop() })
    server.run()
}

private fun usage(): Nothing {
    System.err.println("usage: dashwheel-display [--config DIR] [--print-pairing]")
    exitProcess(2)
}
