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
    Rotation.load(dir)
    Words.load(dir)
    LocalClock.load(dir)
    if (printPairing) {
        println(pairing.offer.toUri())
        return
    }

    val detected = ScreenMode.detect()
    val mode = if (config.width != null && config.height != null) ScreenMode(config.width, config.height)
    else detected ?: ScreenMode.FALLBACK
    log("screen ${mode.width}x${mode.height}" + if (detected == null) " (none detected, assumed)" else "")
    PixelShape.detect(mode.width, mode.height)
    BoardHealth.readAndLog()
    if (PixelShape.kms != (1 to 1)) log("the monitor reports a size that is not its shape: pixels taken for ${PixelShape.kms.first}/${PixelShape.kms.second}")

    val version = DisplayServer::class.java.`package`?.implementationVersion ?: "dev"
    log("dashwheel-display $version, protocol ${com.openauto.dash.link.DISPLAY_PROTOCOL}")
    val hello = DisplayHello(
        name = config.name,
        appVersion = version,
        width = mode.width,
        height = mode.height,
        refreshHz = mode.refreshHz,
        overscanPct = config.overscanPct,
        model = runCatching { File("/proc/device-tree/model").readText().trim('\u0000', ' ', '\n') }.getOrDefault(""),
        brightness = config.brightnessUpGpio != null,
        protocol = com.openauto.dash.link.DISPLAY_PROTOCOL
    )
    val buttons = config.brightnessUpGpio?.let { up -> BrightnessButtons(up, config.brightnessDownGpio!!).apply { start() } }
    val server = DisplayServer(config, pairing, hello, buttons)
    Runtime.getRuntime().addShutdownHook(Thread { server.screen.stop() })
    server.run()
}

private fun usage(): Nothing {
    System.err.println("usage: dashwheel-display [--config DIR] [--print-pairing]")
    exitProcess(2)
}
