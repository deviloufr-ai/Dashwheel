package com.openauto.dash.display

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One `gst-launch-1.0` fed on its stdin by a writer thread, so the network
 * reader never blocks on the decoder. [offer] refuses a chunk when [capacity]
 * are already waiting: the caller decides what to drop.
 */
class GstProcess(private val command: List<String>, capacity: Int) {

    private val queue = ArrayBlockingQueue<ByteArray>(capacity)
    private val process: Process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    @Volatile private var failed = false
    private val writer = Thread({ pump() }, "gst-writer").apply {
        isDaemon = true
        start()
    }

    val alive: Boolean get() = !failed && process.isAlive

    /** False when the queue is full or the process is gone; nothing was queued. */
    fun offer(chunk: ByteArray): Boolean = alive && queue.offer(chunk)

    /** Throws away whatever is still waiting (a newer picture replaces it). */
    fun clearQueue() = queue.clear()

    fun stop() {
        failed = true
        writer.interrupt()
        runCatching { process.outputStream.close() }
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
    }

    companion object {
        /** Null (logged) when GStreamer can't be started at all, e.g. not installed. */
        fun startOrNull(command: List<String>, capacity: Int): GstProcess? =
            runCatching { GstProcess(command, capacity) }
                .onFailure { log("could not start ${command.firstOrNull()}: ${it.message}") }
                .getOrNull()
    }

    private fun pump() {
        val out = process.outputStream
        try {
            while (true) {
                val chunk = queue.take()
                out.write(chunk)
                out.flush()
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            if (!failed) System.err.println("${command.getOrNull(2) ?: "gst"} stopped taking input: ${e.message}")
            failed = true
        }
    }
}
