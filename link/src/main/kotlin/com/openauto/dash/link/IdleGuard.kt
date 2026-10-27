package com.openauto.dash.link

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
