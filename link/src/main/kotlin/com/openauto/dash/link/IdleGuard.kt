package com.openauto.dash.link

import java.io.InputStream

/**
 * Closes a link that went quiet. A TCP socket has a read timeout that ends a
 * blocked read; a Bluetooth socket has none, so a link that silently died
 * would block its reader for ever. The reader calls [kick] whenever it hears
 * anything; after [timeoutMs] without one, [onIdle] runs once (it closes the
 * socket, which ends the blocked read).
 */
class IdleGuard(
    timeoutMs: Long,
    private val onIdle: () -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val tickMs: Long = 1_000
) {
    /** May be changed while running: a handshake gets a shorter wait than an open link. */
    @Volatile var timeoutMs: Long = timeoutMs

    @Volatile private var lastHeard = now()
    @Volatile private var stopped = false
    private val thread = Thread(::watch, "link-idle-guard").apply { isDaemon = true }

    fun start(): IdleGuard {
        lastHeard = now()
        thread.start()
        return this
    }

    /** Something was heard from the other end. */
    fun kick() {
        lastHeard = now()
    }

    /**
     * [input] with every byte read from it counted as hearing from the other
     * end, the way a TCP read timeout does. Kicked only at each whole message,
     * a big one (the phone's contacts with their photos, its notifications
     * with their icons) that took longer than [timeoutMs] to cross a slow
     * Bluetooth link ended the link in the middle of it, at every try (#118).
     */
    fun hearing(input: InputStream): InputStream = object : InputStream() {
        override fun read(): Int = input.read().also { if (it >= 0) kick() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also { if (it > 0) kick() }

        override fun available(): Int = input.available()

        override fun close() = input.close()
    }

    /** The link ended on its own: no more watching, [onIdle] never runs. */
    fun stop() {
        stopped = true
        thread.interrupt()
    }

    private fun watch() {
        while (!stopped) {
            try {
                Thread.sleep(tickMs)
            } catch (e: InterruptedException) {
                return
            }
            if (!stopped && now() - lastHeard > timeoutMs) {
                stopped = true
                onIdle()
                return
            }
        }
    }
}
