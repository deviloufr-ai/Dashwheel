package com.openauto.dash.replay

import com.openauto.dash.roadwatch.Box
import com.openauto.dash.roadwatch.Detection
import com.openauto.dash.roadwatch.RoadBand
import com.openauto.dash.roadwatch.VehicleKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/*
 * roadreplay <video.mp4> [options]: Road watch's code over a recorded drive.
 *
 *   --detections <file.jsonl>   the vehicles per frame, from detect.py (none: the lane only)
 *   --clip <clip.json>          the recorder's notes beside the clip: its speeds and indicators
 *   --speed <km/h>              a steady speed instead, when there are no notes
 *   --horizon <0..1>            where the horizon is in the picture (default 0.5)
 *   --bonnet <0..1>             where the bonnet begins (default 0.92)
 *   --hfov <degrees>            the camera's view across the picture (default 66)
 *   --car-x <0..1>              where the car's middle is across the picture (default 0.5)
 *   --labels <labels.csv>       frames labelled by hand: frame,left,right,lead_m (empty cells allowed)
 *   --out <annotated.mp4>       the drive with what Road watch saw drawn over it
 *   --report <report.json>      the counts, and the timeline in <report>.csv beside it
 *   --dump <dir> --every <n>    every nth frame as a PNG with a ruler, for labelling
 *   --max <frames>              stop after this many frames
 *
 * Frames come from ffmpeg as raw RGBA, and the annotated video goes back
 * through it; both must be on the PATH.
 */

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: roadreplay <video.mp4> [--detections f.jsonl] [--clip clip.json] [--speed kmh] [--horizon y] [--bonnet y] [--hfov deg] [--car-x x] [--labels f.csv] [--out annotated.mp4] [--report report.json] [--dump dir --every n] [--max n]")
        exitProcess(2)
    }
    val video = File(args[0])
    val o = HashMap<String, String>()
    var i = 1
    while (i < args.size) {
        val k = args[i]
        if (!k.startsWith("--")) {
            System.err.println("unexpected $k"); exitProcess(2)
        }
        o[k.removePrefix("--")] = args.getOrNull(i + 1) ?: run { System.err.println("$k needs a value"); exitProcess(2) }
        i += 2
    }
    val band = RoadBand(horizonY = o["horizon"]?.toFloat() ?: 0.5f, bonnetY = o["bonnet"]?.toFloat() ?: 0.92f)
    val session = ReplaySession(band, carX = o["car-x"]?.toFloat() ?: 0.5f, hfovDeg = o["hfov"]?.toFloat() ?: 66f)
    val detections = o["detections"]?.let { readDetections(File(it)) } ?: emptyMap()
    val notes = o["clip"]?.let { readNotes(File(it)) } ?: emptyList()
    val steady = o["speed"]?.toFloat()
    val labels = o["labels"]?.let { readLabels(File(it)) } ?: emptyList()
    val max = o["max"]?.toInt() ?: Int.MAX_VALUE
    val dump = o["dump"]?.let { File(it).also { d -> d.mkdirs() } }
    val every = o["every"]?.toInt() ?: 30

    val (width, height, fps) = probe(video)
    System.err.println("$video: ${width}x$height at $fps fps")
    val decoder = ProcessBuilder("ffmpeg", "-v", "error", "-i", video.path, "-f", "rawvideo", "-pix_fmt", "rgba", "-").redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val encoder = o["out"]?.let { out ->
        ProcessBuilder(
            "ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "${width}x$height", "-r", fps.toString(), "-i", "-",
            "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p", out
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }
    val frames = DataInputStream(BufferedInputStream(decoder.inputStream, 1 shl 20))
    val sink = encoder?.let { BufferedOutputStream(it.outputStream, 1 shl 20) }
    val bytes = ByteArray(width * height * 4)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = IntArray(width * height)
    var index = 0
    try {
        while (index < max) {
            try {
                frames.readFully(bytes)
            } catch (e: EOFException) {
                break
            }
            val timeMs = (index * 1000L / fps)
            val telemetry = telemetryAt(notes, timeMs, steady)
            val result = session.frame(index, timeMs, ByteBuffer.wrap(bytes), width, height, width * 4, detections[index] ?: emptyList(), telemetry)
            if (sink != null || (dump != null && index % every == 0)) {
                toImage(bytes, pixels, image)
                Overlay.draw(image, result, band, ruler = dump != null && index % every == 0)
                if (dump != null && index % every == 0) ImageIO.write(image, "png", File(dump, "f%05d.png".format(index)))
                if (sink != null) {
                    fromImage(image, pixels, bytes)
                    sink.write(bytes)
                }
            }
            index++
            if (index % 300 == 0) System.err.println("frame $index")
        }
    } finally {
        decoder.destroy()
        sink?.close()
        encoder?.waitFor()
    }
    val report = session.report(labels)
    print(report.summary())
    o["report"]?.let { path ->
        File(path).writeText(reportJson(report))
        File(path.removeSuffix(".json") + ".csv").writeText(timelineCsv(session.results))
        System.err.println("report: $path")
    }
}

/** The video's size and frame rate, from ffprobe. */
private fun probe(video: File): Triple<Int, Int, Int> {
    val p = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate", "-of", "csv=p=0", video.path).start()
    val line = p.inputStream.bufferedReader().readText().trim().lines().first()
    val parts = line.split(",")
    val rate = parts[2].split("/").let { if (it.size == 2) it[0].toDouble() / it[1].toDouble() else it[0].toDouble() }
    return Triple(parts[0].toInt(), parts[1].toInt(), Math.round(rate).toInt().coerceAtLeast(1))
}

/** detect.py's lines: {"frame": n, "boxes": [[left, top, right, bottom, class, score], ...]}, fractions of the frame, COCO classes. */
private fun readDetections(file: File): Map<Int, List<Detection>> {
    val out = HashMap<Int, List<Detection>>()
    file.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val obj = Json.parseToJsonElement(line).jsonObject
        val frame = obj["frame"]!!.jsonPrimitive.int
        out[frame] = obj["boxes"]!!.jsonArray.mapNotNull { b ->
            val a = b.jsonArray.map { it.jsonPrimitive.floatOrNull ?: 0f }
            val kind = VehicleKind.ofCoco(a[4].toInt()) ?: return@mapNotNull null
            Detection(Box(a[0], a[1], a[2], a[3]), kind, a[5])
        }
    }
    return out
}

/** One row of the recorder's notes: when (ms into the clip), the speed the car gave, else the GPS's, and the indicators. */
private data class Note(val offsetMs: Long, val speedKmh: Float?, val indicating: Boolean)

private fun readNotes(file: File): List<Note> {
    val obj = Json.parseToJsonElement(file.readText()).jsonObject
    return obj["samples"]?.jsonArray?.map { s ->
        val o = s.jsonObject
        Note(
            offsetMs = o["offsetMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            speedKmh = o["carKmh"]?.jsonPrimitive?.floatOrNull ?: o["gpsKmh"]?.jsonPrimitive?.floatOrNull,
            indicating = listOf("turnLeft", "turnRight", "hazard").any { o[it]?.jsonPrimitive?.booleanOrNull == true }
        )
    }?.sortedBy { it.offsetMs } ?: emptyList()
}

private fun telemetryAt(notes: List<Note>, timeMs: Long, steady: Float?): Telemetry {
    if (notes.isEmpty()) return Telemetry(steady)
    val n = notes.minByOrNull { Math.abs(it.offsetMs - timeMs) }!!
    return Telemetry(n.speedKmh ?: steady, n.indicating)
}

/** frame,left,right,lead_m with a header line; an empty cell is "not labelled". */
private fun readLabels(file: File): List<Label> =
    file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
        val c = line.split(",").map { it.trim() }
        Label(c[0].toInt(), c.getOrNull(1)?.toFloatOrNull(), c.getOrNull(2)?.toFloatOrNull(), c.getOrNull(3)?.toFloatOrNull())
    }

private fun toImage(rgba: ByteArray, pixels: IntArray, image: BufferedImage) {
    for (p in pixels.indices) {
        val i = p * 4
        pixels[p] = (0xFF shl 24) or ((rgba[i].toInt() and 0xFF) shl 16) or ((rgba[i + 1].toInt() and 0xFF) shl 8) or (rgba[i + 2].toInt() and 0xFF)
    }
    image.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
}

private fun fromImage(image: BufferedImage, pixels: IntArray, rgba: ByteArray) {
    image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    for (p in pixels.indices) {
        val i = p * 4
        val c = pixels[p]
        rgba[i] = (c shr 16).toByte()
        rgba[i + 1] = (c shr 8).toByte()
        rgba[i + 2] = c.toByte()
        rgba[i + 3] = -1
    }
}

private fun reportJson(r: Report): String = buildString {
    appendLine("{")
    appendLine("  \"frames\": ${r.frames},")
    appendLine("  \"bothLines\": ${r.bothLines}, \"oneLine\": ${r.oneLine}, \"noLine\": ${r.noLine},")
    appendLine("  \"leadSeen\": ${r.leadSeen},")
    appendLine("  \"lineRecall\": ${r.lineRecall}, \"lineErrorX\": ${r.lineErrorX},")
    appendLine("  \"leadRecall\": ${r.leadRecall}, \"leadErrorM\": ${r.leadErrorM},")
    appendLine("  \"warnings\": [")
    appendLine(r.warnings.joinToString(",\n") { "    {\"warning\": \"${it.warning}\", \"frame\": ${it.frame}, \"timeMs\": ${it.timeMs}}" })
    appendLine("  ]")
    append("}")
}

private fun timelineCsv(results: List<FrameResult>): String = buildString {
    appendLine("frame,time_s,speed_kmh,lines,offset,left_x,right_x,lead_m,closing_mps,ttc_s,warning")
    for (r in results) {
        appendLine(
            listOf(
                r.index, "%.3f".format(Locale.ROOT, r.timeMs / 1000.0), r.speedKmh ?: "", r.lane.lines, r.lane.offset ?: "",
                r.lane.left?.bottomX ?: "", r.lane.right?.bottomX ?: "",
                r.lead?.distanceM ?: "", r.lead?.closingMps ?: "", r.lead?.ttcS ?: "", r.warning ?: ""
            ).joinToString(",")
        )
    }
}
